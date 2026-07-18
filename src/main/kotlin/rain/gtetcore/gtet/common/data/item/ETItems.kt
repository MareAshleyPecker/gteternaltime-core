package rain.gtetcore.gtet.common.data.item

import rain.gtetcore.gtet.common.material.ETElementMaterials

object ETItems {
    fun init() {
        ETMaterialItems.generateMaterialItems()
        ETElementMaterials.register()
    }

}