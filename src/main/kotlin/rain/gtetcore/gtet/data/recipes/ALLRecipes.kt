@file:Suppress("UNCHECKED_CAST", "DEPRECATION", "unused")

package rain.gtetcore.gtet.data.recipes

import net.minecraft.data.recipes.FinishedRecipe
import rain.gtetcore.gtet.data.recipes.ALLRecipes.init
import java.util.function.Consumer


/**
 * 配方注册入口。
 *
 * 在本模组的配方在此处定义并注册。
 * 通过 [init] 方法统一触发所有配方注册逻辑 —— 调用方是
 * [rain.gtetcore.gtet.ETGTAddon.addRecipes]（GTM 的 `IGTAddon.addRecipes` 钩子，
 * 见 [rain.gtetcore.gtet.data.GTETDatagen.init] 的注释：配方**不能**走 datagen）。
 *
 * ## ⚠️ 写 GT 配方时的两个坑
 *
 * 1. **物品必须带 `TagPrefix`**：`GTRecipeBuilder` 有一堆 `inputItems(Object, int)` /
 *    `outputItems(Object, int)` 重载，而它们**不认 `Material`** ——
 *    传 `Material` 进去会在 else 分支里打一条 error 日志然后**静默什么都不加**
 *    （`GTRecipeBuilder.java:343-349` / `:537-543`）。
 *    正确写法是 `inputItems(dust, GTMaterials.Iron, 2)`（走 `(TagPrefix, Material, int)` 重载）。
 *    本文件里 [rain.gtetcore.gtet.common.item.recipe.RecipeCodeWriter] 导出代码时也遵循这条。
 * 2. **配方 id 用本模组命名空间**：`recipeBuilder(String)` 会把 id 塞进 `gtceu:` 命名空间
 *    （`GTRecipeType.java:258` → `GTCEu.id(id)`），最终注册名 = `<ns>:<配方类型>/<id>`。
 *    所以这里统一写 `recipeBuilder(Gtetcore.id("xxx"))`，
 *    而不是往 `gtceu:` 命名空间里塞本模组的配方（那会让人分不清配方归属，
 *    也可能和 GTM 将来新增的同名配方撞 id）。
 */
object ALLRecipes {

    /** 触发所有配方注册。 */
    fun init(provider: Consumer<FinishedRecipe>) {
        MixerRecipes.init(provider)
        ChemicalRecipes.init(provider)
    }
}
