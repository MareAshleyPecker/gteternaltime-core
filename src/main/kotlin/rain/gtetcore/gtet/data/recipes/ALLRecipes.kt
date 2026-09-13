@file:Suppress("UNCHECKED_CAST", "DEPRECATION","unused")
package rain.gtetcore.gtet.data.recipes

import com.gregtechceu.gtceu.api.GTValues.VA
import com.gregtechceu.gtceu.api.GTValues.ZPM
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient
import com.gregtechceu.gtceu.api.registry.GTRegistries
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import com.gregtechceu.gtceu.common.data.GTRecipeTypes.*
import net.minecraft.data.recipes.FinishedRecipe
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.registries.ForgeRegistries
import rain.gtetcore.gtet.data.recipes.ALLRecipes.init
import java.util.function.Consumer


/**
 * 配方注册入口。
 *
 * 在本模组的配方在此处定义并注册。
 * 通过 [init] 方法统一触发所有配方注册逻辑。
 */
object ALLRecipes {

    /** 触发所有配方注册。当前为空，待扩展。 */
    fun init(provider: Consumer<FinishedRecipe>){

        // gtceu:alloy_blast_smelter / alloy_blast_smelter_uv_parallel_hatch / 200t / 122880 EU/t / ZPM
        ALLOY_SMELTER_RECIPES.recipeBuilder("alloy_blast_smelter_uv_parallel_hatch")
            .inputItems(ForgeRegistries.ITEMS.getValue(
                    ResourceLocation("gtetcore", "overclock_hatch_16x_saving_max"))!!, 64)
            .inputFluids(FluidIngredient.of(ForgeRegistries
                        .FLUIDS.getValue(ResourceLocation("gtceu", "severely_hydro_cracked_naphtha")), 1000))
            .outputItems(ForgeRegistries.ITEMS.getValue(
                    ResourceLocation("gtceu", "uv_parallel_hatch"))!!, 64)
            .outputFluids(FluidIngredient.of(ForgeRegistries
                        .FLUIDS.getValue(ResourceLocation("gtceu", "severely_hydro_cracked_naphtha")), 1000))
            .circuitMeta(2)
            .duration(200)
            .EUt(VA[ZPM].toLong())
            .save(provider)

    }
}
