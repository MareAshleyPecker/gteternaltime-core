package rain.gtetcore.gtet.common.data.machine

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.data.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.hatch.*

/**
 * **部件仓**（超频仓 / 线程仓 / 并行仓 / 时序仓 / **无线能源仓** / ME 标签库存仓 / ME 多阶段样板总成 / ME 库存输入仓族）
 * 的注册入口，进 [rain.gtetcore.gtet.common.data.GTETCreativeModeTabs.MACHINE] 页。
 *
 * ⚠️ 它**不含单方块机器** —— 名字里的 Machine 是历史叫法，这里只有部件仓。
 *
 * ⚠️ 要在 `multiblock.ALLMmachine` 之前调用：多方块测试机注册时要读线程仓与**时序仓**的能力方块表
 * （`PartAbility#getAllBlocks()` 是首取即定的快照，见 [ETTimeFlowHatches] 的类注释）。
 * 无线能源仓共用的是 GTM 的 `PartAbility.INPUT_ENERGY`，同一张快照规则、同一条顺序要求。
 */
object ALLSmachine {

    /** 注册全部部件仓。 */
    fun init() {
        registerMachines()
    }

    /** 全部部件仓 → [rain.gtetcore.gtet.common.data.GTETCreativeModeTabs.MACHINE]。 */
    fun registerMachines() {
        OnlyETreg.ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MACHINE)
        OVERCLOCK_HATCHES = ETOverclockHatches.register(OnlyETreg.ETRegistrate)
        THREAD_HATCHES = ETThreadHatches.register(OnlyETreg.ETRegistrate)
        PARALLEL_HATCHES = ETParallelHatches.register(OnlyETreg.ETRegistrate)
        // 时序仓（TF 供给仓）七档：⚠️ 必须在多方块之前注册（能力方块表首取即定的快照），
        // 本函数由 MachineRegister 保证早于 ALLMmachine.init()，见 ETTimeFlowHatches 的类注释
        TIME_FLOW_HATCHES = ETTimeFlowHatches.register(OnlyETreg.ETRegistrate)
        // 无线能源仓（10 电压档 × 11 安培档 = 110 件）：同样必须在多方块之前注册 ——
        // 它复用 GTM 的 `PartAbility.INPUT_ENERGY`，而那条能力的方块表也是首取即定的快照
        WIRELESS_ENERGY_HATCHES = ETWirelessEnergyHatches.register(OnlyETreg.ETRegistrate)
        // ME 标签库存件：⚠️ AE2 没装时这里返回空表（那两件直接引用 appeng.*，装不上就加载不了，
        // 与 GTM 自己的 GTAEMachines 一样按 isAE2Loaded 挡在外面）
//        TAG_FILTER_HATCHES = ETTagFilterHatches.register(ETRegistrate)
        // ME 多阶段样板总成（四档）+ 通用镜像（一件，能连所有档位）：同样按 isAE2Loaded 挡在外面；
        // ⚠️ 它还会把每档容量登记进 ETPatternBufferCapacities（mixin 按方块定义查容量用，
        //    通用镜像的代理表也按那张表的最大值建）
        PATTERN_BUFFER_HATCHES = ETMEPatternBufferHatches.register(OnlyETreg.ETRegistrate)
        // ME 库存输入总线 / 输入仓（GTM 那两件的同行为另注册）+ ME 二合一库存输入总成：
        // 同样按 isAE2Loaded 挡在外面
        STOCKING_INPUT_HATCHES = ETStockingInputHatches.register(OnlyETreg.ETRegistrate)
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

    /** 「时序仓」部件方块（TF 供给仓，UHV ~ ETV 七档 = 1h ~ 16384h；最高档是虚档位，见 ETValues）。 */
    var TIME_FLOW_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /**
     * 「无线能源仓」部件方块（10 电压档 × 11 安培档 = 110 件，IV ~ MAX）。
     *
     * 声明的是 GTM 现成的 `PartAbility.INPUT_ENERGY`，所以它天然能被任意 GTM / GCYM 多方块的
     * 能源仓槽位接受（见 [ETWirelessEnergyHatches] 的类注释）。
     */
    var WIRELESS_ENERGY_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /** 「ME 标签库存输入总线 / 输入仓」两件（标签过滤 + 定量拉取）；AE2 缺失时为空表。 */
    var TAG_FILTER_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /** 「ME 样板总成 / 镜像」五件（LuV 27 / UV 63 / UEV 126 / UXV 216 + 一件全档通用镜像）；AE2 缺失时为空表。 */
    var PATTERN_BUFFER_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /**
     * 「ME 库存输入总线 / 输入仓 / 二合一库存输入总成」三件（前两件是 GTM 对应件的同行为另注册，
     * 标签与定量默认全关；二合一件两侧各带一套配置）；AE2 缺失时为空表。
     */
    var STOCKING_INPUT_HATCHES: List<MachineDefinition> = emptyList()
        private set
}