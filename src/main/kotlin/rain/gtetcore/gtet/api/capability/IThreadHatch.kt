package rain.gtetcore.gtet.api.capability

/**
 * 「线程仓」部件能力接口。
 *
 * 语义：把 N 台同型机器**融合成一台**。线程仓往控制器上声明「这台机器最多能同时跑几条线程」，
 * 由 [rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeLogic] 读取：
 *
 * - 一台装了这个仓的机器最多同时跑 [threadCount] 条线程；⚠️ 是**线程条数**而不是「配方种数」——
 *   同一种配方现在可以占多条线程（空闲线程会发给已经在跑的同一种配方，见该内核的「吃线程并行」），
 *   所以线程条数 ≥ 同时跑的配方种数；
 * - 每条线程**自己计时**：A 配方 200 tick、B 配方 20 tick，互不影响，各自到点各自结算；
 * - 每条线程**各自吃一遍**并行仓的并行倍率 M（见 `ThreadedRecipeLogic#applyThreadParallel`）——
 *   所以一台机器的总处理次数上限 ≈ 线程数 × M。
 *
 * ## 与并行仓的关系
 * 线程仓声明的是 [rain.gtetcore.gtet.api.capability.ETPartAbility.THREAD_HATCH] 这个**独立能力**，
 * 既不实现 GTM 的 `IParallelHatch`，也不复用 `PartAbility.PARALLEL_HATCH`：
 * - 复用 `PARALLEL_HATCH` 会让「线程仓」和「并行仓」在结构里互斥（同一能力通常被
 *   `maxGlobalLimited(1)` 之类的谓词限定数量）；
 * - 实现 `IParallelHatch` 会顶掉控制器缓存的真正并行仓（`IMultiController#getParallelHatch()`
 *   只缓存一个实例，见 `MultiblockControllerMachine` 第 56 行的 `parallelHatch` 字段），
 *   于是「每线程各自吃 M 倍并行」这件事就废了。
 *
 * ## 两个语义为什么要分开
 * GTM 的并行仓与 GTO 的线程仓都只暴露一个 getter；这里把「线程数」拆成 [threadCount]（当前生效、
 * 玩家可下调）与 [maxThreads]（变体表给的上限），两者都要有。
 *
 * @author rain fox
 */
interface IThreadHatch {

    /**
     * 该仓**当前生效**的线程数（= 这台机器最多能同时跑几种不同配方）。
     *
     * 玩家可以在部件 UI 里把它往下调到 1（用 GTCEu 现成的 `IntInputWidget`），
     * 但不会超过 [maxThreads]。
     */
    val threadCount: Int

    /**
     * 该仓提供的线程数**上限**（玩家只能往下调，不能往上加）。
     *
     * 数值由 `ETThreadHatches.VARIANTS` 变体表显式给出，构造时注入部件：
     * ZPM=4、UV=8、UHV=16、UEV=32、UIV=64、UXV=128、OpV=256、MAX=512。
     * 本接口只约定语义（「上限」而不是「当前值」），不管这个数字从哪来。
     */
    val maxThreads: Int
}
