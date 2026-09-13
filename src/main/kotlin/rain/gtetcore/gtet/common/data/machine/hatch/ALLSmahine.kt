package rain.gtetcore.gtet.common.data.machine.hatch

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.data.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.hatch.ETOverclockHatches
import rain.gtetcore.gtet.common.data.machine.hatch.ETParallelHatches
import rain.gtetcore.gtet.common.data.machine.hatch.ETThreadHatches

/**
 * 三种**部件仓**（超频仓 / 线程仓 / 并行仓）的注册入口，进 [GTETCreativeModeTabs.MACHINE] 页。
 *
 * ⚠️ 它**不含单方块机器** —— 名字里的 Machine 是历史叫法，这里只有部件仓。
 *
 * ⚠️ 要在 `multiblock.ALLMmchine` 之前调用：多方块测试机注册时要读线程仓的能力方块表。
 */
object ALLSmahine {

    /** 注册三种部件仓。 */
    fun init() {
        registerMachines()
    }

    /** 三种部件仓 → [GTETCreativeModeTabs.MACHINE]。 */
    fun registerMachines() {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MACHINE)
        OVERCLOCK_HATCHES = ETOverclockHatches.register(ETRegistrate)
        THREAD_HATCHES = ETThreadHatches.register(ETRegistrate)
        PARALLEL_HATCHES = ETParallelHatches.register(ETRegistrate)
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
}
