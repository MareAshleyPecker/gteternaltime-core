package rain.gtetcore.gtet.common.data.block

import rain.gtetcore.gtet.api.registrate.ETREGISTRATE.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs

object ETBlock {
    /**
     * init {} 约等于 java ： static {}
     *
     * block init to {GTETCreativeModeTabs.BLOCK}
     *
     * 该init下面（不是里面）都将注册到GTETCreativeModeTabs.BLOCK
     * */
    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.BLOCK)
    }

    /** -> [kotlinInit] */
    fun init() {
    }
}
