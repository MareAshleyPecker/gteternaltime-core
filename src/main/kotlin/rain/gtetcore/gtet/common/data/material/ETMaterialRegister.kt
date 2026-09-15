package rain.gtetcore.gtet.common.data.material

import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags.*
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.common.data.GTMaterials.*
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.data.ETMaterial.*
import rain.gtetcore.gtet.util.lang.cn

object ETMaterialRegister {
    fun register() {
        Q235 = Material.Builder(Gtetcore.id("q235"))
            .color(0x6E7A7A)
            .components(
                Iron, 10,
                Coal, 1,
                Manganese, 2,
                Silicon, 1,
                Phosphate, 1,
                Sulfur, 1
            ).ingot().dust()
            .cn("普通碳钢-Q235")
            .buildAndRegister()

        Q345 = Material.Builder(Gtetcore.id("q345"))
            .color(0x5A6B7A)
            .cn("低合金高强钢-Q345")
            .components(
                Iron, 10,
                Coal, 1,
                Manganese, 3,
                Silicon, 2,
                Phosphate, 1,
                Sulfur, 1,
                Vanadium, 1
            ).ingot().dust()
            .buildAndRegister()

        Q420C = Material.Builder(Gtetcore.id("q420c"))
            .color(0x3F4F5F)
            .cn("铁塔角钢-Q420C")
            .components(
                Iron, 10,
                Coal, 1,
                Manganese, 3,
                Silicon, 1,
                Phosphate, 1,
                Sulfur, 1,
                Vanadium, 2
            ).ingot().dust()
            .buildAndRegister()

        Al2O3 = Material.Builder(Gtetcore.id("aluminium_oxide"))
            .color(0xF2EFE8)
            .cn("氧化铝")
            .dust()
            .components(Aluminium, 2, Oxygen, 3)
            .buildAndRegister()

        CaO = GTMaterials.Quicklime

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
            .cn("树脂基复合材料")
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