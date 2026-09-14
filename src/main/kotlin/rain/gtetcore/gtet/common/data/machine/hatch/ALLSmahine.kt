package rain.gtetcore.gtet.common.data.machine.hatch

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.data.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.hatch.ETMEPatternBufferHatches
import rain.gtetcore.gtet.common.data.machine.hatch.ETOverclockHatches
import rain.gtetcore.gtet.common.data.machine.hatch.ETParallelHatches
import rain.gtetcore.gtet.common.data.machine.hatch.ETStockingInputHatches
import rain.gtetcore.gtet.common.data.machine.hatch.ETTagFilterHatches
import rain.gtetcore.gtet.common.data.machine.hatch.ETThreadHatches

/**
 * 六种**部件仓**（超频仓 / 线程仓 / 并行仓 / ME 标签库存仓 / ME 多阶段样板总成 / ME 库存输入仓族）
 * 的注册入口，进 [GTETCreativeModeTabs.MACHINE] 页。
 *
 * ⚠️ 它**不含单方块机器** —— 名字里的 Machine 是历史叫法，这里只有部件仓。
 *
 * ⚠️ 要在 `multiblock.ALLMmchine` 之前调用：多方块测试机注册时要读线程仓的能力方块表。
 */
object ALLSmahine {

    /** 注册六种部件仓。 */
    fun init() {
        registerMachines()
    }

    /** 六种部件仓 → [GTETCreativeModeTabs.MACHINE]。 */
    fun registerMachines() {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MACHINE)
        OVERCLOCK_HATCHES = ETOverclockHatches.register(ETRegistrate)
        THREAD_HATCHES = ETThreadHatches.register(ETRegistrate)
        PARALLEL_HATCHES = ETParallelHatches.register(ETRegistrate)
        // ME 标签库存件：⚠️ AE2 没装时这里返回空表（那两件直接引用 appeng.*，装不上就加载不了，
        // 与 GTM 自己的 GTAEMachines 一样按 isAE2Loaded 挡在外面）
        TAG_FILTER_HATCHES = ETTagFilterHatches.register(ETRegistrate)
        // ME 多阶段样板总成 + 镜像（四档）：同样按 isAE2Loaded 挡在外面；
        // ⚠️ 它还会把每档容量登记进 ETPatternBufferCapacities（mixin 按方块定义查容量用）
        PATTERN_BUFFER_HATCHES = ETMEPatternBufferHatches.register(ETRegistrate)
        // ME 库存输入总线 / 输入仓（GTM 那两件的同行为另注册）+ ME 二合一库存输入总成：
        // 同样按 isAE2Loaded 挡在外面
        STOCKING_INPUT_HATCHES = ETStockingInputHatches.register(ETRegistrate)
    }

    /** 「超频仓」部件方块（全部变体）。 */
    var OVERCLOCK_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /** 「线程仓」部件方块（ZPM ~ MAX）。 */
    var THREAD_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /** GTET 自己的「并行仓」部件方块（IV ~ MAX）。 */
    var PARALLEL_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /** 「ME 标签库存输入总线 / 输入仓」两件（标签过滤 + 定量拉取）；AE2 缺失时为空表。 */
    var TAG_FILTER_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /** 「ME 多阶段样板总成 / 镜像」八件（LuV 27 / UV 63 / UEV 126 / UXV 216，各配一份镜像）；AE2 缺失时为空表。 */
    var PATTERN_BUFFER_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /**
     * 「ME 库存输入总线 / 输入仓 / 二合一库存输入总成」三件（前两件是 GTM 对应件的同行为另注册，
     * 标签与定量默认全关；二合一件两侧各带一套配置）；AE2 缺失时为空表。
     */
    var STOCKING_INPUT_HATCHES: List<MachineDefinition> = emptyList()
        private set
}
