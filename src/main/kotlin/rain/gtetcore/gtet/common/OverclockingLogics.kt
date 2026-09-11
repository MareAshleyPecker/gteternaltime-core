package rain.gtetcore.gtet.common

import com.gregtechceu.gtceu.api.recipe.OverclockingLogic
import kotlin.math.pow

/**
 * GTET 「超频仓」特殊超频算法的工厂。
 *
 * ## 算法
 * 每消耗（apply）1 级超频，同时做两件事：
 * ```
 * duration × (1 / S)
 * EUt      × (E × S)
 * ```
 * - `S = speed`：速度倍率，8× 仓 S=8、16× 仓 S=16；
 * - `E = energyFactor`：能效系数，E=1 表示「每级耗时 ÷S 的同时 EUt 只 ×S」（不吃亏），
 *   E<1 表示越超越省电，E>1 表示越超越费电。
 *
 * ## 和 GTM 原版 `standardOC` 的差别
 * GTM 的 standardOC 每级是 `duration × 0.5`、`EUt × 4`（即 1 级 = 1 个电压等级 = 4 倍电、一半时间）。
 * 这里每级一次就吃掉 S 倍时间、`E × S` 倍电 —— 也就是说**1 级超频 = 原本 log₂(S) 级的收益**，
 * 但只占用 1 级超频额度（`OCs = 机器等级 - 配方等级`），这正是「超频仓」的卖点。
 *
 * ## 循环终止条件（先判电再判时间）
 * 1. `eut × (E × S) > maxVoltage` → 停（不许超出机器可承受电压）；
 * 2. `duration × (1 / S) < 1` → 停（耗时不许低于 1 tick，且**这一级整体不生效**）。
 *
 * 返回 `OCResult(eutMultiplier, durationMultiplier, ocLevel, 1)`：
 * 前两个是「已经生效的倍数」，第三个是实际消耗掉的超频级数（写进 `recipe.ocLevel`），
 * 第四个 parallels 恒为 1 —— 超频仓只改速度，不碰并行（并行仍然交给并行仓 / `PARALLEL_HATCH`）。
 *
 * ## 思路来源
 * - 【自研】每级 `duration ÷S`、`EUt ×(E×S)` 的倍率公式与 `S` / `E` 两个参数化旋钮 —— GTM 只有写死的 ÷2 / ÷4 与 ×4 这两档，没有「速度倍率 × 能效系数」这种可配置组合；`OCResult(..., parallels = 1)` 取 1 也是本算法的决定（超频仓只改速度，不碰并行）。
 *
 * @author rain fox
 */
object OverclockingLogics {

    /**
     * 造一个「S 倍速 / E 能效」的特殊超频算法。
     *
     * @param speed        速度倍率 S（每级耗时 ÷S），例如 8 或 16
     * @param energyFactor 能效系数 E（每级 EUt × `E × S`），例如 1.0 / 0.5 / 2.0 / 4.0
     */
    @JvmStatic
    fun create(speed: Int, energyFactor: Double): OverclockingLogic =
        SpecialOverclock(speed, energyFactor)

    /** [create] 产出的算法实现；只实现 `runOverclockingLogic`，`getModifier` 沿用接口默认实现。 */
    private class SpecialOverclock(private val speed: Int, private val energyFactor: Double) : OverclockingLogic {

        override fun runOverclockingLogic(
            ocParams: OverclockingLogic.OCParams,
            maxVoltage: Long
        ): OverclockingLogic.OCResult {
            // 每级超频的乘数：时间 ÷ speed，电 × (energyFactor × speed)
            val durationFactor = 1.0 / speed
            val eutFactor = energyFactor * speed

            var eut = ocParams.eut().toDouble()
            var duration = ocParams.duration().toDouble()
            var remaining = ocParams.ocAmount()
            var ocLevel = 0

            while (remaining-- > 0) {
                // 先看电够不够：这一级加上去会不会超过机器能承受的电压
                val nextEUt = eut * eutFactor
                if (nextEUt > maxVoltage) break

                // 再看时间：耗时不许降到 1 tick 以下；不满足就整级作废（与 GTM standardOC 一致）
                val nextDuration = duration * durationFactor
                if (nextDuration < 1.0) break

                eut = nextEUt
                duration = nextDuration
                ocLevel++
            }

            return OverclockingLogic.OCResult(
                eutFactor.pow(ocLevel.toDouble()),
                durationFactor.pow(ocLevel.toDouble()),
                ocLevel,
                1
            )
        }
    }
}
