package rain.gtetcore.gtet.common.data.block

import com.gregtechceu.gtceu.common.block.CoilBlock
import com.tterrag.registrate.util.entry.BlockEntry
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.ETREGISTRATE
import rain.gtetcore.gtet.common.Coil.ETCoilEnum
import rain.gtetcore.gtet.common.GTETCreativeModeTabs

object ETBlock {

    init {
        ETREGISTRATE.etreg.creativeModeTab(GTETCreativeModeTabs.BLOCK)
    }

    val machine_coil_cupronickel: BlockEntry<CoilBlock> =
        ETREGISTRATE.createCoilBlock(ETCoilEnum.MACHINE_COIL_CUPRONICKEL)

    @JvmStatic
    fun init() {}
}
