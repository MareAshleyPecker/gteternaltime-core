package rain.gtetcore.gtet.common.machine.multiblock.timeflow

import com.gregtechceu.gtceu.api.machine.ConditionalSubscriptionHandler
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.feature.IDataStickInteractable
import com.gregtechceu.gtceu.api.machine.feature.IDropSaveMachine
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.ChatFormatting
import net.minecraft.core.GlobalPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import rain.gtetcore.gtet.api.timeflow.ETTimeFlow
import rain.gtetcore.gtet.api.timeflow.ITimeFlowTower
import rain.gtetcore.gtet.common.item.timeflow.TimeBottleData
import rain.gtetcore.gtet.config.GTETConfig
import java.util.Locale
import java.util.UUID

/**
 * **主控塔** —— 全服时间流（TF）的收储与换汇处（设定 §2 / §6.1）。
 *
 * ## 抄的是谁
 * 结构骨架抄 GTM 自己的 `PowerSubstationMachine`：多方块成型时把部件聚合成一个能量容器，
 * 每 tick 从那个容器里"搬电"，储备用 `saveCustomPersistedData` 落盘。差别只有三处：
 * 1. 搬进来的 EU 不是存成 EU，而是按 `1 TF = 8192 EU`（[ETTimeFlow.EU_PER_TF]）**换成 TF**，
 *    充入效率 η₁ = 100%（无损，设定 §3.3 基线）；取整**向下**，不足 1 TF 的那点 EU **留在塔的能源仓里不动**。
 * 2. 储备是**有上限**的（容量 = 塔身段数 × 每段容量），满了就一点电都不取（不会白吞玩家的电）。
 * 3. 多了所有者 / 白名单与「全服唯一」两道门。
 *
 * ## 为什么继承 [WorkableElectricMultiblockMachine] 而不是 `WorkableMultiblockMachine`
 * 前者已经替我们把「部件里的能源仓聚合成一个 `EnergyContainerList`」这件事做完了
 * （`onStructureFormed` 里的 `energyContainer = getEnergyContainer()`），
 * 换汇那一 tick 直接读它就行 —— 这正是 PSS 自己手写的那一段（`PowerSubstationMachine:127-128`）。
 * 本机挂 `DUMMY_RECIPES`，永远没有配方可跑，配方逻辑只是躺着当状态灯。
 *
 * ## EU → TF 那一 tick（充入）
 * 见 [exchangeTick]：**从塔自己聚合出来的 [`energyContainer`] 取电**（也就是玩家插在塔上的 GTM 能源仓），
 * 算出「这些电最多值几个 TF」，再拿塔的剩余容量夹一次，**先算 TF 再反算 EU**去扣 —— 这样永远不会出现
 * 「扣了电但 TF 没进账」的零头损耗（零头原样留在能源仓里）。
 *
 * ## 拆塔封存（设定 §6.1「避免单点故障」）
 * 塔芯 = **控制器方块本身**。走 GTM 现成的 [IDropSaveMachine] 通道：
 * 敲掉控制器时 `saveToItem` → `saveCustomPersistedData(tag, forDrop=true)` 把储备写进掉落物 NBT；
 * 再放下时 `MetaMachineBlock#setPlacedBy` → `loadFromItem` → `loadCustomPersistedData` 还回来。
 *
 * ⚠️ **封存与「拆一半再建」不打架**：只有**控制器被敲掉**才走掉落物通道；敲的是机壳 / 能源仓时
 * 控制器方块还在，储备一直躺在控制器自己的存档数据里，什么都没动。真正的边界是
 * **重建后的容量比原来小**（例如只接 2 段，原来存了 5 段的量）：这时**不销毁超出部分**，
 * 见 [setTimeFlow] 的注释。
 *
 * ## 唯一性
 * 门写在 [checkPattern] 里，**只读内存快照**（[MasterTowerRegistry.snapshot]），不碰 [net.minecraft.world.level.saveddata.SavedData]
 * —— `checkPattern()` 会跑在 GTM 的后台检测线程上。存档记账只在服务端线程读写。
 *
 * @author rain fox
 */
class MasterTowerMachine(holder: IMachineBlockEntity) :
    WorkableElectricMultiblockMachine(holder),
    ITimeFlowTower,
    IDropSaveMachine,
    IMachineLife,
    IDataStickInteractable {

    /**
     * 本机自己记的「成型标志」。
     *
     * 为什么不直接用 `isFormed()`：它是 GTM 的字段，而这里的用法很特别 ——
     * [checkPattern] 需要在**成型状态还没变之前**就知道"我这座塔已经建好过了"，
     * 好在唯一性拦下来时**优先放行**（否则 GTM 会走 `onStructureInvalid` 把建好的塔拆掉）。
     * 自己维护一个布尔，语义明确、也不依赖 GTM 的写法。
     */
    private var formed: Boolean = false

    /** 塔身段数（成型时按结构算）。 */
    private var segments: Int = 0

    /** 当前容量（TF）= 段数 × 每段容量。 */
    private var capacity: Long = 0L

    /** 储备（TF，long，无亚 TF 精度）。 */
    private var stored: Long = 0L

    /** 上次成型检查是不是被「全服唯一」挡下的（只用来在面板里显示提示）。 */
    @Volatile
    private var deniedByUniqueness: Boolean = false

    /** 每 tick 的换汇；只在成型时订阅（未成型自动退订，不白吃 tick）。 */
    private val tickSubscription =
        ConditionalSubscriptionHandler(this, this::exchangeTick) { formed }

    // ================================================================
    //  ITimeFlowStorage —— 塔的 TF 存储（ITimeFlowTower 继承了它）
    // ================================================================

    /** 当前储备（TF）。 */
    override fun getTimeFlow(): Long = stored

    /**
     * 直接写入储备，返回夹取后的实际值。
     *
     * ⚠️ 上限取 **`max(容量, 当前存量)`**，不是裸的容量 —— 这是为了「塔重建后段数变少」这一种情况：
     * 存了 5 段的量、重建只接 2 段时，如果按 2 段夹，第一笔取出就会把超出的那部分**悄悄销毁**。
     * 换成 `max` 之后超出部分只进不出（[getTimeFlowRoom] 自然变成 0），玩家可以慢慢取走。
     * 正常路径（充入 / 取出）永远走 [getTimeFlowCapacity] 这条线，行为与接口契约一致。
     */
    override fun setTimeFlow(amount: Long): Long {
        val upper = maxOf(capacity, stored)
        val clamped = amount.coerceIn(0L, upper)
        if (clamped != stored) {
            stored = clamped
            markDirty()
        }
        return clamped
    }

    /** 容量上限（TF）＝ 塔身段数 × 每段容量；未成型时为 0。 */
    override fun getTimeFlowCapacity(): Long = capacity

    // ================================================================
    //  ITimeFlowTower —— 所有者 / 白名单
    // ================================================================

    /** 塔的所有者 = GTM 记在机器上的所有者（放下控制器的玩家，或第一次右键控制器的玩家）。 */
    override fun getOwnerUuid(): UUID? = ownerUUID

    /**
     * 谁能从这座塔取用 TF —— **按 UUID 判定**：白名单命中，或过一遍接口的默认实现
     * （未认领放行 / 本人 / **同队**，同队那一层见 [rain.gtetcore.gtet.util.ETTeamAccess]，设定 §2.4）。
     *
     * 仓在 tick 里没有 `Player`，走的就是这一版；玩家右键（时序之瓶）走
     * [ITimeFlowTower.canUseTimeFlow]，它默认就是转发到本方法 ⇒ 两条路必然同一个答案。
     */
    override fun canUseTimeFlowByUuid(uuid: UUID): Boolean =
        MasterTowerWhitelist.contains(uuid) || super.canUseTimeFlowByUuid(uuid)

    // ================================================================
    //  结构成型 / 失效
    // ================================================================

    /**
     * 结构成型：算段数与容量、记账唯一性、订阅换汇 tick。
     *
     * 段数来自图案匹配时写进结构上下文的计数器（见
     * [rain.gtetcore.gtet.common.data.machine.multiblock.ETMasterTower] 里的段锚点谓词），
     * 一个可重复段 = 一个计数，所以「段数」= 玩家往上接了几节塔身。
     */
    override fun onStructureFormed() {
        super.onStructureFormed()
        formed = true
        deniedByUniqueness = false

        val raw = multiblockState.matchContext.getInt(SEGMENT_KEY)
        segments = raw.coerceIn(1, GTETConfig.towerMaxSegments())
        capacity = segments.toLong() * GTETConfig.towerSegmentCapacity()

        // 唯一性记账：只在服务端线程做（这里就是服务端线程）
        val serverLevel = level as? ServerLevel
        if (serverLevel != null) {
            MasterTowerRegistry.onTowerFormed(serverLevel.server, selfPos(serverLevel))
        }
        tickSubscription.updateSubscription()
    }

    override fun onStructureInvalid() {
        formed = false
        tickSubscription.unsubscribe()
        super.onStructureInvalid()
    }

    /**
     * 控制器方块**被拆掉**（换成了别的方块）时的收尾。
     *
     * 只有这里才释放唯一名额；结构失效（敲一格机壳）**不**释放，免得拆一格再补上的空窗里
     * 别处抢建第二座（那会让「唯一」名存实亡）。
     */
    override fun onMachineRemoved() {
        formed = false
        tickSubscription.unsubscribe()
        val serverLevel = level as? ServerLevel
        if (serverLevel != null) {
            MasterTowerRegistry.onTowerControllerRemoved(serverLevel.server, selfPos(serverLevel))
        }
    }

    // ================================================================
    //  唯一性门（⚠️ 这一段会跑在后台线程，只能读内存快照）
    // ================================================================

    /**
     * 结构检测。**唯一性门就写在这里**，但有三条硬规矩（设定 §6.1 + API 核实报告 §9.4/§9.5）：
     *
     * 1. **已成型的塔优先放行**（第一行）—— GTM 在已成型状态下重检失败会走 `onStructureInvalid`
     *    把建好的塔**拆掉**；把唯一性判定放在前面就等于「配置一改，老塔全碎」。
     * 2. **只读内存快照**（[MasterTowerRegistry.snapshot]）—— 这个方法会被
     *    `MultiblockWorldSavedData` 的后台单线程执行器调到，读 [net.minecraft.world.level.saveddata.SavedData] 是数据竞争。
     * 3. **不追溯**—— 曾经成型过的坐标永远放行（见 [MasterTowerRegistry.Snapshot.allows]），
     *    这样「false → true」的配置翻转不会波及既有塔，重启后老塔也照样能重新成型。
     *
     * 判定不通过时**只返回 false**，不在这里发聊天消息（这里是后台线程，碰玩家 / 发包都不安全）；
     * 提示落在控制器面板里，见 [addDisplayText]。
     */
    override fun checkPattern(): Boolean {
        if (formed) return super.checkPattern()
        if (!GTETConfig.masterTowerUnique()) {
            deniedByUniqueness = false
            return super.checkPattern()
        }
        val lvl = level ?: return super.checkPattern()
        if (!MasterTowerRegistry.snapshot().allows(selfPos(lvl))) {
            deniedByUniqueness = true
            return false
        }
        deniedByUniqueness = false
        return super.checkPattern()
    }

    // ================================================================
    //  EU → TF 换汇（每 tick）
    // ================================================================

    /**
     * 一 tick 的换汇：**从塔自己聚合出来的能源容器里**取电，按 `1 TF = 8192 EU` 换成 TF。
     *
     * 顺序很讲究：
     * 1. 容量满了（`room <= 0`）→ **直接返回，一点电都不取**（不能白吞玩家的电）；
     * 2. 读聚合容器的 `getEnergyStored()`，`euToTf` 向下取整算出「最多能换几个 TF」；
     * 3. 用剩余容量夹一次，得到真正要换的 TF 数；
     * 4. **反算 EU**（`tfToEu`）再 `changeEnergy(-eu)` —— 先算 TF 再算 EU，就不会出现
     *    「扣了不足 1 TF 的电」这种零头损耗；那点零头原样留在能源仓里，下一 tick 继续攒。
     *
     * 充入效率 η₁ = 100%（无损，设定 §3.3 基线）；潮汐汇率**不参与**充入，只在面板里显示 ——
     * 设定 §3.1 的「换算只在主控塔里发生」指的是塔是唯一闸口，不是"充入要乘潮汐"。
     */
    private fun exchangeTick() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        if (!formed || !isWorkingEnabled) return

        val room = getTimeFlowRoom()
        if (room <= 0L) return

        val container = energyContainer ?: return
        val eu = container.energyStored
        if (eu < ETTimeFlow.EU_PER_TF) return

        val tf = minOf(ETTimeFlow.euToTf(eu), room)
        if (tf <= 0L) return

        val consumed = -container.changeEnergy(-ETTimeFlow.tfToEu(tf))
        if (consumed <= 0L) return
        // 实际扣到的电按同样口径折回 TF（正常情况下 consumed == tfToEu(tf)）
        setTimeFlow(stored + ETTimeFlow.euToTf(consumed))
    }

    // ================================================================
    //  持久化：存档 + 拆塔封存
    // ================================================================

    /**
     * 落盘。`forDrop = true`（敲掉控制器、掉落物带走）与 `forDrop = false`（区块存档）都走这里，
     * 所以储备**同一条通道**既进存档也进掉落物 —— 这就是「拆塔封存」的全部实现。
     */
    override fun saveCustomPersistedData(tag: CompoundTag, forDrop: Boolean) {
        super.saveCustomPersistedData(tag, forDrop)
        tag.putLong(KEY_TIME_FLOW, stored)
        tag.putInt(KEY_SEGMENTS, segments)
    }

    /**
     * 读回。放下控制器时（`MetaMachineBlock#setPlacedBy` → `loadFromItem`）就会调用，
     * 那一刻结构还没成型（容量 = 0），所以这里**直接给字段赋值**、不走 [setTimeFlow]（否则会被夹成 0）。
     */
    override fun loadCustomPersistedData(tag: CompoundTag) {
        super.loadCustomPersistedData(tag)
        if (tag.contains(KEY_TIME_FLOW)) {
            stored = tag.getLong(KEY_TIME_FLOW).coerceAtLeast(0L)
        }
        if (tag.contains(KEY_SEGMENTS)) {
            segments = tag.getInt(KEY_SEGMENTS).coerceAtLeast(0)
        }
    }

    // ================================================================
    //  闪存范式（备用路径，未删）
    // ================================================================

    /**
     * GTM 闪存右键转发。**保留**为备用入口（[TimeBottleData.onDataStickUse] 那套没删）。
     *
     * ⚠️ 实话：这条路今天**不会**触发绑瓶子 —— GTM 的 `DataItemBehavior` 分发只在手里拿的是**闪存本体**时
     * 才回调到这里，而它传进来的 `dataStick` 是那根闪存、不是时序之瓶，所以
     * `TimeBottleData.isTimeBottle(...)` 恒为 false、恒返回 PASS。瓶子的真实入口是
     * [TimeBottleBehavior][rain.gtetcore.gtet.common.item.timeflow.TimeBottleBehavior] 自己那条
     * `onItemUseFirst` / `useOn`（见任务 B）。保留它只是不给将来留坑、也不改变闪存的任何现有行为。
     */
    override fun onDataStickUse(player: Player, dataStick: ItemStack): InteractionResult {
        val lvl = level ?: return InteractionResult.PASS
        return TimeBottleData.onDataStickUse(player, dataStick, lvl, pos, frontFacing)
    }

    /** 同上，潜行那一档。 */
    override fun onDataStickShiftUse(player: Player, dataStick: ItemStack): InteractionResult {
        val lvl = level ?: return InteractionResult.PASS
        return TimeBottleData.onDataStickShiftUse(player, dataStick, lvl, pos, frontFacing)
    }

    // ================================================================
    //  UI（能省就省：只往控制器面板里追加几行）
    // ================================================================

    /**
     * 面板里追加：储备 / 容量 / 潮汐汇率 / 所有者，外加「因唯一性没成型」的红字提示。
     *
     * 这段文本由 `ComponentPanelWidget` 在**服务端**求值后同步过去，所以读的是服务端那份真数据，
     * 不需要额外写同步字段。
     */
    override fun addDisplayText(textList: MutableList<Component>) {
        super.addDisplayText(textList)

        if (!formed && deniedByUniqueness) {
            textList += Component.translatable(MasterTowerLang.DUPLICATE).withStyle(ChatFormatting.RED)
            return
        }

        textList += Component.translatable(
            MasterTowerLang.STORED,
            num(stored),
            num(ETTimeFlow.tfToEu(stored))
        ).withStyle(ChatFormatting.AQUA)

        textList += Component.translatable(
            MasterTowerLang.CAPACITY,
            num(capacity),
            segments.toString(),
            num(GTETConfig.towerSegmentCapacity())
        ).withStyle(ChatFormatting.GRAY)

        val gameTime = level?.gameTime
        if (gameTime != null) {
            textList += Component.translatable(
                MasterTowerLang.RATE,
                fixed(ETTimeFlow.tideRate(gameTime)),
                fixed(ETTimeFlow.tidePhase(gameTime) * 100.0, 1)
            ).withStyle(ChatFormatting.LIGHT_PURPLE)
        }

        val owner = ownerUUID
        textList += if (owner == null) {
            Component.translatable(MasterTowerLang.OWNER_NONE).withStyle(ChatFormatting.DARK_GRAY)
        } else {
            Component.translatable(MasterTowerLang.OWNER, owner.toString()).withStyle(ChatFormatting.DARK_GRAY)
        }
    }

    // ================================================================
    //  小工具
    // ================================================================

    /** 控制器自己的全局坐标（维度取自传进来的 level，免得再碰一次可空的 `level` 属性）。 */
    private fun selfPos(lvl: Level): GlobalPos = GlobalPos.of(lvl.dimension(), pos)

    private fun num(value: Long): String = FormattingUtil.formatNumbers(value)

    private fun fixed(value: Double, decimals: Int = 4): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    companion object {

        /** 结构上下文里「塔身段数」的键；谓词往里 +1，成型时读出来（见注册文件的段锚点谓词）。 */
        const val SEGMENT_KEY: String = "gtetcore_master_tower_segments"

        /** 储备在 NBT 里的键（存档与掉落物共用）。 */
        const val KEY_TIME_FLOW: String = "time_flow"

        /** 段数在 NBT 里的键（只为掉落物上的 tooltip，可读性用）。 */
        const val KEY_SEGMENTS: String = "segments"
    }
}
