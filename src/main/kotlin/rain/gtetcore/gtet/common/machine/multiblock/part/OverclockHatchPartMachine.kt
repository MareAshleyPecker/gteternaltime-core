package rain.gtetcore.gtet.common.machine.multiblock.part

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import rain.gtetcore.gtet.api.capability.IOverclockHatch

/**
 * 「超频仓」多方块部件。
 *
 * 一个分级多方块部件（方块本体只是一个分级 part machine）：
 * - 实现 [IFancyUIMachine] 给一个只读信息面板；
 * - 实现 [IOverclockHatch] 让超频逻辑能认出它；
 * - `canShared() = false`，禁止多方块部件共享。
 *
 * 真正的超频改写发生在 [rain.gtetcore.gtet.mixin.GTM.MixinOverclockingLogic]：
 * 它扫控制器的 `getParts()`，找到本类实例后用 [overclockSpeed] / [overclockEnergyFactor]
 * 描述的特殊超频替换掉该多方块的普通超频。
 *
 * ## 没有持久化字段
 * S 与 E 由注册表在构造时注入、之后不再变化，所以不需要 `@Persisted`，
 * `MANAGED_FIELD_HOLDER` 只是为了让 ldlib 把父类那些 `@DescSynced` 字段（控制器坐标等）串起来。
 *
 * ## 思路来源
 * - 【自研】不实现 `IParallelHatch` 的决定 —— 实现它会顶掉控制器缓存的真正并行仓（`getParallelHatch()` 只取一个实例），因此改用 GTET 自己的 `IOverclockHatch`；这个取舍在 GTM 里没有对应物。
 *
 * @param holder       方块实体持有者
 * @param tier         电压等级（同时决定外壳贴图，见 `ETOverclockHatches` 的变体表）
 * @param speed        每级超频的速度倍率 S（耗时 ÷S）
 * @param energyFactor 每级超频的能效系数 E（EUt × `E × S`）
 *
 * @author rain fox
 */
class OverclockHatchPartMachine(
    holder: IMachineBlockEntity,
    tier: Int,
    private val speed: Int,
    private val energyFactor: Double
) : TieredPartMachine(holder, tier), IFancyUIMachine, IOverclockHatch {

    override val overclockSpeed: Int get() = speed

    override val overclockEnergyFactor: Double get() = energyFactor

    /**
     * 只显示两行信息的简单面板（不需要任何输入控件）：
     * 1. 方块名 —— `block.gtetcore.<id>`，客户端按当前语言解析；
     * 2. 规格 —— `gtetcore.machine.<id>.info`，例如「8× 速度 / 总能耗 ×32」。
     *
     * 这里传的是 **lang key**：ldlib 的 `LabelWidget` 在客户端用
     * `LocalizationUtils.format(...)` → `I18n` 解析，所以两种语言各显示各的。
     */
    override fun createUIWidget(): Widget {
        val id = definition.name
        val group = WidgetGroup(0, 0, 140, 32)
        // 用 setColor(-1)（白字）而不是已弃用的 setTextColor —— 两者等价，后者只是 ldlib 的老 API
        group.addWidget(LabelWidget(5, 5, "block.gtetcore.$id").apply { setColor(-1) })
        group.addWidget(LabelWidget(5, 19, "gtetcore.machine.$id.info").apply { setColor(-1) })
        return group
    }

    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    override fun canShared(): Boolean = false

    companion object {

        /**
         * 挂在 [MultiblockPartMachine] 的字段持有者后面，保证父类的
         * `controllerPositions`（`@DescSynced`，客户端靠它找回控制器）照常同步。
         */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder = ManagedFieldHolder(
            OverclockHatchPartMachine::class.java,
            MultiblockPartMachine.MANAGED_FIELD_HOLDER
        )
    }
}
