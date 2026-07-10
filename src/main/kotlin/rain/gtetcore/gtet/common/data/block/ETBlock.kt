package rain.gtetcore.gtet.common.data.block

import com.gregtechceu.gtceu.common.block.CoilBlock
import com.tterrag.registrate.util.entry.BlockEntry
import rain.gtetcore.gtet.api.ETREGISTRATE
import rain.gtetcore.gtet.api.ETREGISTRATE.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs

object ETBlock {
    /**
     * init {} 约等于 java ： static {}
     * block init to {GTETCreativeModeTabs.BLOCK}
     * */
    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.BLOCK)
    }

    fun init() {
    }
}
