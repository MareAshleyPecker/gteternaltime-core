package rain.gtetcore.gtet

import com.gregtechceu.gtceu.api.addon.GTAddon
import com.gregtechceu.gtceu.api.addon.IGTAddon
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.data.item.ETItems
import rain.gtetcore.gtet.common.material.ETElements

@GTAddon
@SuppressWarnings("all")
open class ETGTAddon : IGTAddon {

    override fun getRegistrate(): GTRegistrate {
        return OnlyETreg.ETRegistrate
    }

    override fun initializeAddon() {
        ETItems.init()
        ETBlock.init()
    }

    override fun addonModId(): String {
        return Gtetcore.MODID
    }

    override fun registerElements() {
        ETElements.init()
    }
}