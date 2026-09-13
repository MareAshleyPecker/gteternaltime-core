package rain.gtetcore.gtet.common.data.machine.samplemachine

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.otherMachine.ETOverclockHatches
import rain.gtetcore.gtet.common.data.machine.otherMachine.ETParallelHatches
import rain.gtetcore.gtet.common.data.machine.otherMachine.ETThreadHatches

/**
 * 单方块机器与部件仓（超频仓 / 线程仓 / 并行仓）的注册入口，进 [GTETCreativeModeTabs.MACHINE] 页。
 *
 * ⚠️ 要在 `muiltmachine.ALLMmchine` 之前调用：多方块测试机注册时要读线程仓的能力方块表。
 */
object ALLSmahine {

    /** 注册单方块机器与部件仓。 */
    fun init() {
        registerMachines()
    }

    /** 部件仓 + 单方块机器 → [GTETCreativeModeTabs.MACHINE]。 */
    fun registerMachines() {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MACHINE)
        OVERCLOCK_HATCHES = ETOverclockHatches.register(ETRegistrate)
        THREAD_HATCHES = ETThreadHatches.register(ETRegistrate)
        PARALLEL_HATCHES = ETParallelHatches.register(ETRegistrate)
    }

    /** 「超频仓」部件方块（全部变体）。 */
    var OVERCLOCK_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /** 「线程仓」部件方块（UV ~ MAX）。 */
    var THREAD_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /** GTET 自己的「并行仓」部件方块（IV ~ MAX）。 */
    var PARALLEL_HATCHES: List<MachineDefinition> = emptyList()
        private set
}
