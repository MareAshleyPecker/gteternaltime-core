package rain.gtetcore.gtet.common.machine.multiblock.timeflow

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.machine.multiblock.timeflow.MasterTowerRegistry.encode
import rain.gtetcore.gtet.common.machine.multiblock.timeflow.MasterTowerRegistry.snapshot
import rain.gtetcore.gtet.config.GTETConfig
import java.util.*
import java.util.function.Function
import java.util.function.Supplier

/**
 * 主控塔「全服唯一」的记账处 —— **两套数据，两条线程**，别混。
 *
 * ## 为什么必须分成两套
 * [rain.gtetcore.gtet.common.machine.multiblock.timeflow.MasterTowerMachine.checkPattern] 会跑在
 * `MultiblockWorldSavedData` 的**后台单线程执行器**上（GTM 的 `asyncCheckPattern` → `checkPatternWithTryLock`
 * → `checkPattern`），在那里碰 [SavedData] 就是数据竞争。所以：
 *
 * | 谁 | 存在哪 | 谁能读写 |
 * |---|---|---|
 * | [snapshot] | 进程内 `@Volatile`（不可变快照） | 任意线程**只读**；`checkPattern` 只读它 |
 * | [MasterTowerSavedData] | 主世界 `DataStorage`（存档里） | **只在服务端线程**读写 |
 *
 * 快照是**整体替换**的不可变对象，所以后台线程读到的要么是旧值要么是新值，不会是半个。
 *
 * ## 唯一性语义（设定 §6.1）
 * - `masterTowerUnique = false`：不设占位者，谁都能成型；成型过的坐标记进 [Snapshot.formedTowers]。
 * - `masterTowerUnique = true`：先成型的那座占住 [Snapshot.uniqueTower]；**没占位者时**新塔可以成型，
 *   一旦占住了，别的坐标就成型不了。
 * - **不追溯**：只要坐标在 [Snapshot.formedTowers] 里（= 这座塔曾经成型过），永远放行 ——
 *   这正是「配置从 false 改成 true，已有塔继续工作」的实现方式，也是重启后已建好的塔能重新成型的原因。
 * - 占位者在**控制器方块被拆掉**时释放（`onMachineRemoved`），普通的结构失效（敲掉一格机壳）不释放。
 */
object MasterTowerRegistry {

    /**
     * 内存快照 —— `checkPattern()` 唯一允许读的东西。
     *
     * @param uniqueTower 当前占住唯一名额的塔；`null` = 还没人占（或已释放）
     * @param formedTowers 曾经成型过的塔坐标（编码串，见 [encode]）；这些塔永远放行
     */
    data class Snapshot(val uniqueTower: GlobalPos?, val formedTowers: Set<String>) {

        /** 这个坐标现在能不能成型。 */
        fun allows(pos: GlobalPos): Boolean =
            formedTowers.contains(MasterTowerRegistry.encode(pos)) || uniqueTower == null || uniqueTower == pos

        companion object {
            val EMPTY = Snapshot(null, emptySet())
        }
    }

    /** 当前快照；后台线程只读这一个字段。 */
    @Volatile
    private var snapshot: Snapshot = Snapshot.EMPTY

    /** 已经把 [MasterTowerSavedData] 读进内存的那个服务器实例（只在服务端线程读写）。 */
    private var syncedServer: MinecraftServer? = null

    /** 只读快照 —— 给 `checkPattern()` 用（无 I/O、无 level 访问）。 */
    @JvmStatic
    fun snapshot(): Snapshot = snapshot

    /**
     * 「零操作自动连接」该连的那座塔（设定 §2.4 第一行）。
     *
     * - `masterTowerUnique = true`（默认）⇒ 返回当前占住唯一名额的那座塔的坐标，仓不用玩家做任何操作就能取用；
     * - `false`（多塔模式）⇒ 返回 `null` —— 这时**必须显式绑定**（手持已绑塔的时序之瓶右键仓），
     *   否则仓不知道该找哪一座（不做「连最近的那座」这种猜测，猜错了就是白白漏电）。
     *
     * ⚠️ 唯一名额还没被占（`uniqueTower == null`，例如塔都还没成型）时同样返回 `null`，
     * 调用方按「未接线」处理即可。**时刻可能变**（塔成型 / 拆控制器都会改写快照），
     * 所以每次要用的时候现取，不要缓存。
     */
    @JvmStatic
    fun autoTower(): GlobalPos? =
        if (GTETConfig.masterTowerUnique()) snapshot().uniqueTower else null

    /**
     * 把存档里的记账读进内存。**只在服务端线程调用**；同一个服务器实例只读一次。
     *
     * 触发点：[MasterTowerEvents.onServerStarted]（服务器起来时）与所有塔侧的写入口（见下）。
     */
    @JvmStatic
    fun ensureLoaded(server: MinecraftServer) {
        if (syncedServer === server) return
        val data = MasterTowerSavedData.get(server)
        syncedServer = server
        snapshot = Snapshot(data.uniqueTower, HashSet(data.formedTowers))
    }

    /**
     * 一座塔成型了（**服务端线程**）：记进「曾经成型过」名单，顺便占唯一名额（如果还空着）。
     */
    @JvmStatic
    fun onTowerFormed(server: MinecraftServer, pos: GlobalPos) {
        ensureLoaded(server)
        val data = MasterTowerSavedData.get(server)
        data.formedTowers.add(encode(pos))
        if (data.uniqueTower == null) data.uniqueTower = pos
        data.setDirty()
        snapshot = Snapshot(data.uniqueTower, HashSet(data.formedTowers))
    }

    /**
     * 一座塔的**控制器方块被拆掉**了（**服务端线程**）：只有它是当前占位者时才释放名额。
     *
     * ⚠️ 只敲掉一格机壳（结构失效但控制器还在）**不会**释放名额 —— 否则「拆一格再补上」的空窗里
     * 别处就能抢建第二座。
     */
    @JvmStatic
    fun onTowerControllerRemoved(server: MinecraftServer, pos: GlobalPos) {
        ensureLoaded(server)
        val data = MasterTowerSavedData.get(server)
        if (data.uniqueTower == pos) {
            data.uniqueTower = null
            data.setDirty()
            snapshot = Snapshot(null, HashSet(data.formedTowers))
        }
    }

    /** 服务器停了：把内存快照清空，免得单人游戏切存档时把上一个存档的记账带过去。 */
    @JvmStatic
    fun clearMemory() {
        syncedServer = null
        snapshot = Snapshot.EMPTY
    }

    // ======================== 坐标编解码 ========================

    /** 坐标 → 字符串（`维度|x|y|z`）。存进 NBT 的就是它。 */
    @JvmStatic
    fun encode(pos: GlobalPos): String =
        "${pos.dimension().location()}|${pos.pos().x}|${pos.pos().y}|${pos.pos().z}"

    /** 字符串 → 坐标；格式坏了返回 `null`（坏数据直接忽略，不让整份记账报废）。 */
    @JvmStatic
    fun decode(raw: String): GlobalPos? {
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val dim = ResourceLocation.tryParse(parts[0]) ?: return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val z = parts[3].toIntOrNull() ?: return null
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos(x, y, z))
    }
}

/**
 * 主控塔记账的存档载体 —— 挂在**主世界**的 `DataStorage` 上，所以是真正的「全服」一份（跨维度）。
 *
 * ⚠️ 只在服务端线程读写（[MasterTowerRegistry] 负责这件事），别在别处直接 `get()`。
 */
class MasterTowerSavedData : SavedData() {

    /** 占住唯一名额的那座塔；`null` = 没人占。 */
    var uniqueTower: GlobalPos? = null

    /** 曾经成型过的塔坐标（编码串）；重启后靠它「不追溯」。 */
    val formedTowers: MutableSet<String> = LinkedHashSet()

    override fun save(tag: CompoundTag): CompoundTag {
        uniqueTower?.let { tag.putString(KEY_UNIQUE, MasterTowerRegistry.encode(it)) }
        val list = ListTag()
        formedTowers.forEach { list.add(StringTag.valueOf(it)) }
        tag.put(KEY_FORMED, list)
        return tag
    }

    companion object {

        /** 存档键；改名 = 老存档的记账丢失。 */
        const val DATA_ID: String = "gtetcore_master_tower"

        private const val KEY_UNIQUE = "unique"
        private const val KEY_FORMED = "formed"

        /** 从 NBT 还原；坏坐标静默跳过。 */
        @JvmStatic
        fun load(tag: CompoundTag): MasterTowerSavedData {
            val data = MasterTowerSavedData()
            if (tag.contains(KEY_UNIQUE)) {
                data.uniqueTower = MasterTowerRegistry.decode(tag.getString(KEY_UNIQUE))
            }
            val list = tag.getList(KEY_FORMED, StringTag.valueOf("").id.toInt())
            for (i in 0 until list.size) {
                val decoded = MasterTowerRegistry.decode(list.getString(i)) ?: continue
                data.formedTowers.add(MasterTowerRegistry.encode(decoded))
            }
            return data
        }

        /** 取（或建）那份记账。**只在服务端线程调用**。 */
        @JvmStatic
        fun get(server: MinecraftServer): MasterTowerSavedData {
            val storage = server.overworld().dataStorage
            return storage.computeIfAbsent(
                Function { tag -> load(tag) },
                Supplier { MasterTowerSavedData() },
                DATA_ID
            )
        }
    }
}

/**
 * 服务器生命周期钩子：起来时把记账读进内存、停下时清掉。
 *
 * 挂在 `FORGE` 总线上（与 [rain.gtetcore.gtet.common.item.terminal.TerminalGroupSeeder] 同款写法）。
 * ⚠️ 这两个回调**只是把同步提前**：塔侧每个写入口也会自己 [MasterTowerRegistry.ensureLoaded]，
 * 所以哪天这里没被调到，功能也不会坏，只是重启后头几秒可能出现「记账还没进内存」的窗口。
 */
@Mod.EventBusSubscriber(modid = Gtetcore.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
class MasterTowerEvents private constructor() {

    companion object {

        @JvmStatic
        @SubscribeEvent
        fun onServerStarted(event: ServerStartedEvent) {
            MasterTowerRegistry.ensureLoaded(event.server)
        }

        @JvmStatic
        @SubscribeEvent
        fun onServerStopping(event: ServerStoppingEvent) {
            MasterTowerRegistry.clearMemory()
        }
    }
}

/**
 * 主控塔白名单（设定 §6.1 的最简实现）：
 * **所有者 + 配置里那份逗号分隔的 UUID 列表**，没有别的花样。
 *
 * 每次读配置都重新解析一遍太浪费，所以按配置原文缓存；配置热重载换了串就自动失效重解析。
 */
object MasterTowerWhitelist {

    private var cachedRaw: String? = null
    private var cached: Set<UUID> = emptySet()

    @JvmStatic
    @Synchronized
    fun contains(uuid: UUID): Boolean {
        val raw = GTETConfig.towerWhitelist()
        if (raw != cachedRaw) {
            cachedRaw = raw
            cached = raw.split(',')
                .mapNotNull { part ->
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) null else try {
                        UUID.fromString(trimmed)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                .toSet()
        }
        return cached.contains(uuid)
    }
}