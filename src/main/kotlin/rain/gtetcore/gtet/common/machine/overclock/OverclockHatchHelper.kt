package rain.gtetcore.gtet.common.machine.overclock

import com.google.common.math.IntMath
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.OverclockingLogic
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import com.gregtechceu.gtceu.api.recipe.modifier.ModifierFunction
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic
import com.gregtechceu.gtceu.utils.GTMath
import com.gregtechceu.gtceu.utils.GTUtil
import java.math.RoundingMode

/**
 * 「超频仓」替代超频的静态入口 —— 自己算出 `OCs` 与 `OCParams`，再交给超频算法。
 *
 * ## 为什么这里要自己算一遍这段前置逻辑
 * `OverclockingLogic#getModifier(...)` 是被 [rain.gtetcore.gtet.mixin.GTM.MixinOverclockingLogic]
 * 注入（`@At("HEAD")` + `setReturnValue`）的**同一个方法**。如果本 helper 里再去调
 * `logic.getModifier(...)`，就会立刻重新进入注入点 → 无限递归。所以这里只把
 * 「算 OCs → 组 `OCParams`」这一段自己算一遍，最后直接调 `logic.runOverclockingLogic(...)`。
 *
 * ## 与 GTM 原版的唯一一处（有意）差异
 * GTM 7.5.3 写的是 `if (OCs == 0) return ModifierFunction.IDENTITY;`（只挡 0，不挡负数）。
 * 这里改成 `OCs <= 0`：`OCs < 0` 说明配方等级已经高于机器等级，本来就该原样返回，
 * 走 GTM 那条路会构造出负的 `ocAmount` 再返回一个「倍数全是 1 但把 `ocLevel` 重置为 0」的
 * `ModifierFunction`，语义上更糟。对 `OCs == 0` 两种写法完全等价。
 *
 * @author rain fox
 */
object OverclockHatchHelper {

    /**
     * 用 [logic] 计算「装了超频仓的控制器」该套用的配方修改器。
     *
     * @param machine       正在算配方的机器（调用方已确认是 `IMultiController`）
     * @param recipe        原始配方
     * @param maxVoltage    该控制器可承受的最高电压（即 GTM 传进来的 `maxVoltage`）
     * @param shouldParallel 是否允许参与并行（只影响 `OCParams.maxParallels`，
     *                       超频仓的算法自己返回 parallels = 1，不会真的去并行）
     * @param logic         超频算法，通常由 [OverclockingLogics.create] 生成
     */
    @JvmStatic
    fun modify(
        machine: MetaMachine,
        recipe: GTRecipe,
        maxVoltage: Long,
        shouldParallel: Boolean,
        logic: OverclockingLogic
    ): ModifierFunction {
        // 配方的真实 EUt：先看输入 EU，没有再看输出 EU（例如发电机配方）
        val eut = RecipeHelper.getRealEUt(recipe).totalEU
        if (eut == 0L) return ModifierFunction.IDENTITY

        // 配方等级 / 机器等级（都按「需要多少电压才能带动」算）
        val recipeTier = GTUtil.getTierByVoltage(eut).toInt()
        val maximumTier = GTUtil.getOCTierByVoltage(maxVoltage).toInt()

        // 可用的超频级数：机器等级 - 配方等级；配方在 ULV 时再减 1（ULV 没有「上一级」可用）
        var ocs = maximumTier - recipeTier
        if (recipeTier == GTValues.ULV) ocs--
        if (ocs <= 0) return ModifierFunction.IDENTITY

        // ── 下面这段是并行预算：剩余 OC 能换多少并行 ──
        // 它只被 GTM 的 subtick 类算法用来算「剩余 OC 换成的并行数」；
        // 超频仓的算法（OverclockingLogics）固定返回 parallels = 1，因此这里算出来的值暂时用不上，
        // 但保留下来可以让以后新增「会吃并行」的仓体算法直接复用，也保证 OCParams 的语义与 GTM 一致。
        val maxParallels: Int
        if (!shouldParallel) {
            maxParallels = 1
        } else {
            // lg = floor(log2(duration)) / 2，即「在完美超频（÷4）下把耗时压到 4 tick 以下需要几级」
            // OCs <= lg：耗时大概压不到 4 以下，老实按 1 倍并行
            // OCs >  lg：理论上能换 4^(OCs - lg) 倍并行，再让 ParallelLogic 按实际输入/输出卡一次
            val lg = IntMath.log2(recipe.duration, RoundingMode.FLOOR) / 2
            maxParallels = if (lg > ocs) {
                16
            } else {
                val p = GTMath.saturatedCast((1L shl (2 * (ocs - lg))) + 1)
                ParallelLogic.getParallelAmount(machine, recipe, p)
            }
        }

        val params = OverclockingLogic.OCParams(eut, recipe.duration, ocs, maxParallels)
        return logic.runOverclockingLogic(params, maxVoltage).toModifier()
    }
}
