package rain.gtetcore.gtet.common.data.machine.samplemachine

import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs

/**
 * 单方块机器
 * 所有单方块机器在此初始化并注册到 [GTETCreativeModeTabs.MACHINE] 选项卡。
 */
object ALLSmahine {

    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MACHINE)
    }

    fun init() {}
}