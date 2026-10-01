package rain.gtetcore.gtet.studio.integration

import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB
import org.slf4j.Logger
import rain.gtetcore.gtet.studio.data.StudioLibrary
import rain.gtetcore.gtet.studio.editor.StudioEditTarget
import rain.gtetcore.gtet.studio.editor.StudioEditor
import rain.gtetcore.gtet.studio.editor.StudioTargetSource
import rain.gtetcore.gtet.studio.interaction.StudioAnchorOffset
import rain.gtetcore.gtet.studio.interaction.StudioRay
import rain.gtetcore.gtet.studio.interaction.StudioRayMath
import rain.gtetcore.gtet.studio.interaction.StudioVec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * **「机器 → studio 实例」的客户端静态表 + 查询**（M2a 的宿主信息入口）。
 *
 * ## 它解决什么问题
 * 编辑模式要回答"这片世界里，哪些机器带着 studio 模型、它朝哪、方块在哪、实例是谁" ——
 * 只有 `integration/` 知道（唯一能 import GTM 的地方）。所以：
 * - `integration/StudioDynamicRender` 每次被渲染/剔除询问时，把 **(方块坐标 → 实例)** 登记进来；
 * - 对外只暴露 [StudioEditTarget]/[StudioTargetSource] 这两个**窄接口**（定义在 `editor/`），
 *   `editor/` 与 `interaction/` 拿到的就是这个接口，**一行 GTM 都不认识**。
 *
 * ## 为什么"登记"而不是"深挖 GTM 的模型 JSON"
 * 我们要的信息其实只有三条（坐标、朝向、还活着吗）。深挖 `MachineModel` 里
 * `dynamic_renders` 的结构，等于把 studio 焊死在 GTM 的模型格式上 ——
 * 现在的做法换个宿主（ETV 那台）也照样能用。
 *
 * ## 线程
 * `DynamicRender.shouldRender` / `getRenderBoundingBox` **可能被区块构建工作线程调用**
 * （见 `config/StudioLimits` 的注释），而查询在主线程 ⇒ 表必须是并发的
 * （[ConcurrentHashMap]）；换维度时整表清空（避免攥着上一个世界的机器引用）。
 *
 * @author rain fox
 */
object StudioMachineRegistry : StudioTargetSource {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** `机器方块坐标 → 实例`。同一格挂两份不同模型时后登记的覆盖前者（M2a 不处理这种用法）。 */
    private val entries = ConcurrentHashMap<BlockPos, MachineEditTarget>()

    /** 上次登记时所在的维度；变了就整表作废。 */
    @Volatile
    private var levelIdentity: Any? = null

    /** 把本表装进编辑器（`StudioDynamicRenders.register` 里调一次）。 */
    @JvmStatic
    fun install() {
        StudioEditor.installSource(this)
        LOGGER.info("[studio] 编辑器已接上宿主查询：/gtetstudio edit 可以从准星或最近距离找 studio 实例")
    }

    /**
     * 登记一台机器（由 [StudioDynamicRender] 在渲染/剔除询问时调用，**每帧都会走**）。
     *
     * 已有的那条如果指向同一台机器、同一个模型，就**什么都不做** ——
     * 这条路径每帧都跑，不能在里面产生垃圾。
     */
    @JvmStatic
    fun register(machine: MetaMachine, modelId: ResourceLocation) {
        val level = machine.level ?: return
        val clientLevel = Minecraft.getInstance().level ?: return
        if (level !== clientLevel) return // 只登记玩家所在的维度

        if (levelIdentity !== level) {
            entries.clear()
            levelIdentity = level
        }

        val pos = machine.pos
        val existing = entries[pos]
        if (existing != null && existing.matches(machine, modelId)) return
        entries[pos] = MachineEditTarget(machine, modelId)
    }

    /** 当前维度下所有登记过的实例（**只给命令/诊断用**，渲染路径别调它 —— 它会新建列表）。 */
    @JvmStatic
    fun all(): List<StudioEditTarget> = entries.values.filter { it.isValid() }

    // ────────────────────────── StudioTargetSource ──────────────────────────

    /**
     * 准星指向的那台机器。
     *
     * 两条口径，按顺序：
     * 1. **原版的方块拾取**（`Minecraft.getInstance().hitResult`）所在那一格 ——
     *    任务指定的口径，指着控制器/机壳时最准；
     * 2. **兜底：准星射线打"机器方块 ± 模型包围盒"** —— 模型明显比一个方块大得多，
     *    指着伸出去的钟面时第 1 条根本打不到（方块拾取只会给你空气）。
     */
    override fun byCrosshair(): StudioEditTarget? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        ensureFresh(level)

        (mc.hitResult as? net.minecraft.world.phys.BlockHitResult)?.let { hit ->
            entries[hit.blockPos]?.takeIf { it.isValid() }?.let { return it }
        }

        val camera = mc.gameRenderer.mainCamera
        val look = camera.lookVector
        val ray = StudioRay.of(
            StudioVec3(camera.position.x, camera.position.y, camera.position.z),
            StudioVec3(look.x().toDouble(), look.y().toDouble(), look.z().toDouble()),
        ) ?: return null
        return nearestAlong(ray)
    }

    /** 离玩家最近的那个该 id 实例（准星不好对准时的兜底入口）。 */
    override fun nearest(modelId: ResourceLocation): StudioEditTarget? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        ensureFresh(level)

        var best: MachineEditTarget? = null
        var bestDistance = Double.MAX_VALUE
        for (entry in entries.values) {
            if (entry.modelId != modelId || !entry.isValid()) continue
            val distance = player.position().distanceToSqr(
                entry.pos.x + 0.5, entry.pos.y + 0.5, entry.pos.z + 0.5
            )
            if (distance < bestDistance) {
                bestDistance = distance
                best = entry
            }
        }
        return best
    }

    /** 射线打到哪台机器（返回沿射线的距离）。**只有没命中 gizmo 手柄时才会被调用。** */
    override fun pickMachine(ray: StudioRay): Double? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        ensureFresh(level)

        var best: Double? = null
        for (entry in entries.values) {
            if (!entry.isValid()) continue
            val box = entry.boundingBox()
            val t = StudioRayMath.aabbHit(
                ray,
                StudioVec3(box.minX, box.minY, box.minZ),
                StudioVec3(box.maxX, box.maxY, box.maxZ),
            ) ?: continue
            if (best == null || t < best) best = t
        }
        return best
    }

    private fun nearestAlong(ray: StudioRay): StudioEditTarget? {
        var best: MachineEditTarget? = null
        var bestT = Double.MAX_VALUE
        for (entry in entries.values) {
            if (!entry.isValid()) continue
            val box = entry.boundingBox()
            val t = StudioRayMath.aabbHit(
                ray,
                StudioVec3(box.minX, box.minY, box.minZ),
                StudioVec3(box.maxX, box.maxY, box.maxZ),
            ) ?: continue
            if (t < bestT) {
                bestT = t
                best = entry
            }
        }
        return best
    }

    private fun ensureFresh(level: Any) {
        if (levelIdentity !== level) {
            entries.clear()
            levelIdentity = level
        }
    }

    override fun toString(): String = "StudioMachineRegistry(${entries.size} 台)"
}

/**
 * [StudioEditTarget] 的 GTM 实现 —— **studio 里唯一"知道机器是什么"的地方**。
 *
 * 所有需要问 `StudioLibrary` 的东西（缩放、包围盒、**文件里那份偏移**）都是**现算的 getter**：
 * `/gtetstudio reload` 换掉实例之后不用做任何失效处理。
 * 这条是刻意的纪律 —— 曾经 [fileOffset] 是个构造期快照，结果 `save → reload → 再次 edit`
 * 会拿最早的偏移把活值顶回去（实机可见：模型当场跳回原位）。
 * 编辑器这边因此**不需要**（也不许）自己去调 `StudioLibrary`（那是防死锁的纪律）。
 */
private class MachineEditTarget(
    private val machine: MetaMachine,
    override val modelId: ResourceLocation,
) : StudioEditTarget {

    override val label: String = "$modelId @ ${machine.pos.x}, ${machine.pos.y}, ${machine.pos.z}"

    override val pos: BlockPos get() = machine.pos

    override val facing: Direction get() = machine.frontFacing

    override val scale: Float get() = StudioLibrary.get(modelId)?.model?.anchor?.scale ?: 1f

    override val reachRadius: Double get() = StudioLibrary.get(modelId)?.model?.reachRadius ?: 1.0

    /**
     * 文件里那份偏移 —— **现读**（与 [scale] / [reachRadius] 一样是 getter，不是快照）。
     *
     * ⚠️ 这里原来是构造期快照，实机踩过：`save` → `reload` → 再次 `edit` 时，
     * 注册表里缓存的这个 target 还留着**最早那份**偏移，`StudioEditor.enter` 拿它当基准
     * ⇒ 活值被重置回原始位置，模型当场跳回去（用户反馈的"reload 后再次 edit 会重置到原始位置"）。
     * 凡是"必须与当前已载入的模型一致"的东西都必须是现读的 —— 快照只在"那一刻的值"才是语义时才对。
     */
    override val fileOffset: StudioAnchorOffset
        get() {
            val anchor = StudioLibrary.get(modelId)?.model?.anchor ?: return StudioAnchorOffset()
            return StudioAnchorOffset(anchor.offsetX, anchor.offsetY, anchor.offsetZ)
        }

    override fun isValid(): Boolean =
        !machine.isInValid && machine.level === Minecraft.getInstance().level

    /** 拾取/命中的包围盒：方块本身，再按模型的包围半径放大（模型明显超出一格）。 */
    fun boundingBox(): AABB = AABB(machine.pos).inflate(max(1.0, reachRadius))

    fun matches(other: MetaMachine, otherModelId: ResourceLocation): Boolean =
        machine === other && modelId == otherModelId

    override fun toString(): String = label
}