package rain.gtetcore.gtet.common.material

import com.gregtechceu.gtceu.api.data.chemical.material.Material
import rain.gtetcore.gtet.api.registrate.ETREGISTRATE.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.material.ETElementMaterials.register
import rain.gtetcore.gtet.common.material.ETMaterial.MaterialNAME

/**
 * java Origin : public static Material Naquadah -> To Kotlin : lateinit val Naquadah : Material
 *
 * @sample MaterialNAME
 * @see ETElementMaterials
 * @see com.gregtechceu.gtceu.common.data.GTMaterials
 * */
object ETMaterial {

    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.ITEM)
    }

    fun init() {
        register()
        ETElements.init()
    }

    lateinit var MaterialNAME : Material




}