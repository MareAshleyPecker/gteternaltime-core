package rain.gtetcore.gtet.common.data.machine.muiltmachine

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.ETOverclockHatches
import rain.gtetcore.gtet.common.data.machine.ETParallelHatches
import rain.gtetcore.gtet.common.data.machine.ETTestMultiblocks
import rain.gtetcore.gtet.common.data.machine.ETThreadHatches

/**
 * 多方块机器
 * 所有多方块机器在此初始化并注册到 [GTETCreativeModeTabs.MULTIBLOCK] 选项卡。
 */
object ALLMmchine {
    /** [init] 只跑一次（幂等标志：将来即使多入口重复调用，也不会重复注册）。 */
    private var initialized = false


    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MULTIBLOCK)
    }



    var TEST_MULTIBLOCK: MultiblockMachineDefinition? = null
        private set


    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MACHINE)
    }
    /**
     * 全部「超频仓」部件方块（6 个变体），由 [init] 填充。
     */
    var OVERCLOCK_HATCHES: List<MachineDefinition> = emptyList()
        private set
    /**
     * 全部「线程仓」部件方块（7 个变体，UV ~ MAX），由 [init] 填充。
     */
    var THREAD_HATCHES: List<MachineDefinition> = emptyList()
        private set
    /**
     * GTET 自己的「并行仓」部件方块（10 个变体，IV ~ MAX），由 [init] 填充。
     */
    var PARALLEL_HATCHES: List<MachineDefinition> = emptyList()
        private set
    /**
     * 注册本模组的全部机器。
     */
    fun init() {

        OVERCLOCK_HATCHES = ETOverclockHatches.register(ETRegistrate)
        THREAD_HATCHES = ETThreadHatches.register(ETRegistrate)
        PARALLEL_HATCHES = ETParallelHatches.register(ETRegistrate)
        // 多方块最后：上面三个部件的 register 都把创造页切到 MACHINE，
        // 而 ETTestMultiblocks.register 开头会自己切回 MULTIBLOCK。
        TEST_MULTIBLOCK = ETTestMultiblocks.register(ETRegistrate)
    }
}
