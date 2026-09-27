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
import rain.gtetcore.gtet.config.GTETConfig

/**
 * 「超频仓」多方块部件。
 *
 * 一个分级多方块部件（方块本体只是一个分级 part machine）：
 * - 实现 [IFancyUIMachine] 给一个只读信息面板；
 * - 实现 [IOverclockHatch] 让超频逻辑能认出它；
 * - `canShared()` 默认禁止多方块部件共享，闸门由配置 `multiblock.partsShareable` 兜底（见 [canShared]）。
 *
 * 真正的超频改写发生在 [rain.gtetcore.gtet.mixin.GTM.MixinOverclockingLogic]：
 * 它扫控制器的 `getParts()`，找到本类实例后用 [overclockSpeed] / [overclockEnergyFactor]
 * 描述的特殊超频替换掉该多方块的普通超频。
 *
 * ## 没有持久化字段
 * S 与 E 由注册表在构造时注入、之后不再变化，所以不需要 `@Persisted`，
 * `MANAGED_FIELD_HOLDER` 只是为了让 ldlib 把父类那些 `@DescSynced` 字段（控制器坐标等）串起来。
 *
 * ⚠️ 不实现 `IParallelHatch`：实现它会顶掉控制器缓存的真正并行仓
 * （`getParallelHatch()` 只取一个实例），所以用独立能力 [IOverclockHatch]。
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
     * 面板里**只有名字一行**（不需要任何输入控件，也不再单独显示规格）。
     *
     * 名字键 `block.gtetcore.<id>` 本身已经带齐信息 —— 中文「ZPM 超频仓（4× Speed|×32 Energy）」、
     * 英文「ZPM Overclock Hatch (4× Speed / ×32 Energy)」，也就是「电压等级 + 速度 + 能效」；
     * 原先那行 `gtetcore.machine.<id>.info`（以及物品提示里的 `tooltip.0` / `tooltip.1`）
     * 已随显示简化一并删除，`runData` 后不再出现在语言文件里。
     *
     * 这里传的是 **lang key**：ldlib 的 `LabelWidget` 在客户端用
     * `LocalizationUtils.format(...)` → `I18n` 解析，所以两种语言各显示各的。
     */
    override fun createUIWidget(): Widget {
        val id = definition.name
        val group = WidgetGroup(0, 0, 140, 20)
        // 用 setColor(-1)（白字）而不是已弃用的 setTextColor —— 两者等价，后者只是 ldlib 的老 API
        group.addWidget(LabelWidget(5, 5, "block.gtetcore.$id").apply { setColor(-1) })
        return group
    }

    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    /**
     * 部件共享的闸门，返回全局开关 [GTETConfig.partsShareable]（默认 `false` = 禁止共享）。
     *
     * 原先这里写死 `false`：本件不能被两个已成型结构同时占用（防串配方）。
     * 现在由配置兜底 —— **打开配置就恢复串配方风险**：本件属于 `IOverclockHatch`，
     * 超频改写扫的是控制器的 `getParts()`，共享后两个控制器会同时按本件的 S / E 改写自己的配方。
     * ⚠️ 该值只在 `BlockPattern#checkPatternAt` 那一刻被读，改配置后要等下一次结构检测才生效。
     */
    override fun canShared(): Boolean = GTETConfig.partsShareable()

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
