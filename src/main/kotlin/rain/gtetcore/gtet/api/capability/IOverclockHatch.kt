package rain.gtetcore.gtet.api.capability

/**
 * 「超频仓」部件能力接口。
 *
 * 任何多方块部件只要实现本接口，[rain.gtetcore.gtet.mixin.GTM.MixinOverclockingLogic]
 * 就会在控制器算超频时认出它，并把该多方块的**普通超频整体替换**成由
 * [overclockSpeed] / [overclockEnergyFactor] 描述的特殊超频：
 *
 * ```
 * 每消耗 1 级超频：duration × (1 / S)   ，   EUt × (E × S)
 * ```
 *
 * 其中 `S = overclockSpeed`、`E = overclockEnergyFactor`。
 *
 * ## 与并行仓的关系
 * 超频仓声明的是 [rain.gtetcore.gtet.api.capability.ETPartAbility.OVERCLOCK_HATCH] 这个**独立能力**，
 * 既不实现 GTM 的 `IParallelHatch`，也不复用 `PartAbility.PARALLEL_HATCH`：
 * - 复用 `PARALLEL_HATCH` 会让「超频仓」和「并行仓」在结构里互斥（都是 maxGlobalLimited(1) 的同一能力）；
 * - 实现 `IParallelHatch` 会顶掉控制器缓存的真正并行仓（`getParallelHatch()` 只取一个实例）。
 *
 * ## 思路来源
 * - 【借鉴形状】GTCEu `com.gregtechceu.gtceu.api.capability.IParallelHatch` —— 只借了「用一个部件接口向控制器暴露一个数值 getter，供超频/并行逻辑读取」这个形状；它的方法签名与实现一行都没照抄。
 * - 【自研】接口本身与 `overclockSpeed` / `overclockEnergyFactor` 两个字段的语义 —— GTM 的 `IParallelHatch` 只有 `getParallel()` 一个 int，没有「速度倍率 / 能效系数」这种二维描述，也就没有能抄的东西。
 *
 * @author rain fox
 */
interface IOverclockHatch {

    /**
     * 每消耗 1 级超频的速度倍率 S。
     *
     * 每级超频把配方耗时除以 S（8× 仓 → 耗时 ÷8，16× 仓 → 耗时 ÷16）。
     */
    val overclockSpeed: Int

    /**
     * 每消耗 1 级超频的能效系数 E。
     *
     * 每级超频把 EUt 乘以 `E × S`：E < 1 表示「省电」，E > 1 表示「损能」，
     * E = 1 即所谓 perfect（同样的速度提升不额外加价）。
     */
    val overclockEnergyFactor: Double
}
