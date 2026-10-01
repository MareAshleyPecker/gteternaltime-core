package rain.gtetcore.gtet.data.recipes

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper
import com.gregtechceu.gtceu.api.data.tag.TagPrefix.*
import com.gregtechceu.gtceu.common.data.GTMaterials
import net.minecraft.advancements.critereon.InventoryChangeTrigger
import net.minecraft.core.registries.Registries
import net.minecraft.data.recipes.FinishedRecipe
import net.minecraft.data.recipes.RecipeCategory
import net.minecraft.data.recipes.ShapedRecipeBuilder
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.Ingredient
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.data.item.ETItems
import java.util.function.Consumer

/**
 * 高级终端的工作台配方：图案 3×3 为
 * ```
 * S G S
 * P B P
 * P W P
 * ```
 * S = 钢螺丝、G = `forge:glass_panes` 标签、B = 书、P = 钢板、W = 锡单线。
 *
 * 配方 id 由结果物品推出（`gtetcore:advanced_terminal`），不会和别的命名空间撞。
 */
object TerminalRecipes {

    fun init(provider: Consumer<FinishedRecipe>) {
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ETItems.ADVANCED_TERMINAL.get())
            .pattern("SGS")
            .pattern("PBP")
            .pattern("PWP")
            .define('S', Ingredient.of(ChemicalHelper.get(screw, GTMaterials.Steel)))
            .define('G', Ingredient.of(TagKey.create(Registries.ITEM, Gtetcore.id("forge", "glass_panes"))))
            .define('B', Items.BOOK)
            .define('P', Ingredient.of(ChemicalHelper.get(plate, GTMaterials.Steel)))
            .define('W', Ingredient.of(ChemicalHelper.get(wireFine, GTMaterials.Tin)))
            .unlockedBy("has_book", InventoryChangeTrigger.TriggerInstance.hasItems(Items.BOOK))
            .save(provider)
    }
}
