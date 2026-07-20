package rain.gtetcore.gtet.common.data.item

import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.material.ETElementMaterials

object ETItems {
    fun init() {
        ETMaterialItems.generateMaterialItems()
        ETElementMaterials.register()
    }

    init {
        OnlyETreg.ETRegistrate.creativeModeTab(GTETCreativeModeTabs.ITEM)
    }

}