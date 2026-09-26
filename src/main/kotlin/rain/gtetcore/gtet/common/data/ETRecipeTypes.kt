package rain.gtetcore.gtet.common.data

import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.common.data.GTRecipeTypes

object ETRecipeTypes {
    const val STEAM: String = "steam"
    const val ELECTRIC: String = "electric"
    const val GENERATOR: String = "generator"
    const val MULTIBLOCK: String = "multiblock"
    const val DUMMY: String = "dummy"

    val STELLMAKING_FURNACE: GTRecipeType = GTRecipeTypes.register("steelmaking_furnace", MULTIBLOCK)
        .setMaxIOSize(3, 3, 2, 2)
}