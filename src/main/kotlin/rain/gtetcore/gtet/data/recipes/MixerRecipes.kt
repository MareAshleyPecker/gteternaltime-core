package rain.gtetcore.gtet.data.recipes

import com.gregtechceu.gtceu.api.GTValues.*
import com.gregtechceu.gtceu.api.data.tag.TagPrefix.dust
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.common.data.GTRecipeTypes.MIXER_RECIPES
import net.minecraft.data.recipes.FinishedRecipe
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.data.ETMaterial.*
import java.util.function.Consumer

object MixerRecipes {
    fun init(provider: Consumer<FinishedRecipe>) {

        MIXER_RECIPES.recipeBuilder(Gtetcore.id("glass_fiber"))
            .inputItems(dust, GTMaterials.SiliconDioxide, 11)
            .inputItems(dust, CaO, 4)                      // = GTM 的 Quicklime
            .inputItems(dust, Al2O3, 2)                    // 本模组自建（GTM 没有氧化铝）
            .inputItems(dust, GTMaterials.Magnesia, 1)
            .inputItems(dust, Na2O, 1)
            .inputItems(dust, K2O, 1)
            .outputItems(dust, GlassFiber, 20)
            .duration(200)
            .EUt(VA[MV].toLong())
            .save(provider)

        MIXER_RECIPES.recipeBuilder(Gtetcore.id("glass_fiber_reinforced_polymer"))
            .inputItems(dust, Resin, 20)
            .inputItems(dust, GlassFiber, 20)
            .inputItems(dust, GTMaterials.CalciumCarbonate, 1)   // 填料（见 ETMaterialRegister 的说明）
            .outputItems(dust, GFRP, 41)
            .duration(300)
            .EUt(VA[HV].toLong())
            .save(provider)
    }
}