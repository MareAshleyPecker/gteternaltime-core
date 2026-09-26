package rain.gtetcore.gtet.common.machine.multiblock.part

import com.gregtechceu.gtceu.api.capability.IParallelHatch
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import net.minecraft.util.Mth

/**
 * GTET 的「并行仓」多方块部件（IV ~ MAX 共 10 档）。
 *
 * 实现 GTM 的 [IParallelHatch]：GTM 的 `GTRecipeModifiers.hatchParallel` 与 GTET 的
 * `ThreadedRecipeLogic` 都是通过 `IMultiController#getParallelHatch()` 拿一个
 * `IParallelHatch` 再读 [getCurrentParallel]，所以只要实现这个接口，两边都能读到本仓的值。
 *
 * @param tier        电压等级，决定外壳贴图
 * @param maxParallel 该档并行上限（同时是默认值），数值来自 `ETParallelHatches` 的变体表
 *
 * @author rain fox
 */
class ETParallelHatchPartMachine(
    holder: IMachineBlockEntity,
    tier: Int,
    val maxParallel: Int
) : TieredPartMachine(holder, tier), IFancyUIMachine, IParallelHatch {

    /**
     * 当前生效的并行数（1 ~ [maxParallel]）。
     *
     * `@Persisted` 所以存档重载不丢；字段名与接口的 `getCurrentParallel()` 刻意不同
     * —— Kotlin 的属性访问器 `getCurrentParallel()` 会和手写的同名实现撞 JVM 签名。
     */
    @Persisted
    var parallelValue: Int = maxParallel
        private set

    override fun getCurrentParallel(): Int = parallelValue

    /**
     * 面板：方块名 + 并行数输入框。
     *
     * 范围由 `setMin` / `setMax` 卡住，含义由名字给出（中文名里带并行数），不另生成说明键。
     */
    override fun createUIWidget(): Widget {
        val id = definition.name
        val group = WidgetGroup(0, 0, 150, 44)
        group.addWidget(LabelWidget(5, 5, "block.gtetcore.$id").apply { setColor(-1) })
        group.addWidget(
            IntInputWidget(5, 19, 100, 20, { parallelValue }, { setParallelAmount(it) })
                .setMin(MIN_PARALLEL)
                .setMax(maxParallel)
        )
        return group
    }

    /**
     * 玩家改并行数：夹到合法区间，值真的变了才敲控制器让配方逻辑下一轮重取。
     *
     *  并行仓不像线程仓那样有「已经吃掉的料」，所以这里不需要「只封新线程、放老线程跑完」那套，
     * 直接改数即可。
     */
    fun setParallelAmount(amount: Int) {
        val clamped = Mth.clamp(amount, MIN_PARALLEL, maxParallel)
        if (clamped == parallelValue) return
        parallelValue = clamped
        for (controller in controllers) {
            if (controller is IRecipeLogicMachine) {
                controller.recipeLogic.markLastRecipeDirty()
            }
        }
    }

    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    override fun canShared(): Boolean = false

    companion object {

        /** 并行下限：1 = 退化成 GTCEu 原版不并行的机器。 */
        const val MIN_PARALLEL: Int = 1

        /**
         * 挂在 [MultiblockPartMachine] 的字段持有者后面，保证父类的 `controllerPositions`（`@DescSynced`）
         * 与本类的 `parallelValue`（`@Persisted`）都串得起来。
         */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder = ManagedFieldHolder(
            ETParallelHatchPartMachine::class.java,
            MultiblockPartMachine.MANAGED_FIELD_HOLDER
        )
    }
}
