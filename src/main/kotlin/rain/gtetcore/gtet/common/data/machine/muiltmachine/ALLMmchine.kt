package rain.gtetcore.gtet.common.data.machine.muiltmachine

import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs

/**
 * 多方块机器
 * 所有多方块机器在此初始化并注册到 [GTETCreativeModeTabs.MULTIBLOCK] 选项卡。
 */
object ALLMmchine {

    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MULTIBLOCK)
    }

    fun init() {}
}