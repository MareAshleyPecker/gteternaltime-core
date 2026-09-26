package rain.gtetcore.gtet.common.data.material

import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags.*
import com.gregtechceu.gtceu.common.data.GTMaterials.*
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.data.ETMaterial.*
import rain.gtetcore.gtet.util.lang.cn


object ETMaterialRegister {
    fun register() {

        Al2O3 = Material.Builder(Gtetcore.id("aluminium_oxide"))
            .color(0xF2EFE8)
            .cn("氧化铝")
            .dust()
            .components(Aluminium, 2, Oxygen, 3)
            .buildAndRegister()

        CaO = Quicklime

        Na2O = Material.Builder(Gtetcore.id("na2o"))
            .color(0xEDE9E0)
            .cn("氧化钠")
            .langValue("Na2O")
            .dust()
            .components(Sodium, 2, Oxygen, 1)
            .buildAndRegister()

        K2O = Material.Builder(Gtetcore.id("k2o"))
            .color(0xE4DDCD)
            .cn("氧化钾")
            .langValue("K2O")
            .dust()
            .components(Potassium, 2, Oxygen, 1)
            .buildAndRegister()

        GlassFiber = Material.Builder(Gtetcore.id("glass_fiber"))
            .color(0xE8EDF1)
            .cn("玻璃纤维")
            .dust()
            .components(
                SiliconDioxide, 11,
                CaO, 4,
                Al2O3, 2,
                Magnesia, 1,
                Na2O, 1,
                K2O, 1
            )
            .flags(NO_SMASHING, NO_SMELTING)
            .buildAndRegister()


        Resin = Material.Builder(Gtetcore.id("resin"))
            .color(0xC8A233)
            .cn("树脂")
            .dust()
            .components(Carbon, 20, Hydrogen, 20, Oxygen, 5)
            .flags(FLAMMABLE)
            .buildAndRegister()

        GFRP = Material.Builder(Gtetcore.id("glass_fiber_reinforced_polymer"))
            .color(0x8FA08F)
            .cn("树脂基复合材料-GFRP")
            .components(
                Resin, 20,
                GlassFiber, 20,
                CalciumCarbonate, 1
            )
            .dust().ingot()
            .flags(DISABLE_DECOMPOSITION)
            .buildAndRegister()
    }

}