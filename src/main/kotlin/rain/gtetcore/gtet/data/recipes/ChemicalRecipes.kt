package rain.gtetcore.gtet.data.recipes

import net.minecraft.data.recipes.FinishedRecipe
import java.util.function.Consumer

import com.gregtechceu.gtceu.api.GTValues.*
import com.gregtechceu.gtceu.api.data.tag.TagPrefix.dust
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.common.data.GTRecipeTypes.CHEMICAL_RECIPES
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.data.ETMaterial.*


object Chemical_recipes {
    // gtceu:chemical_reactor / test_thread / 200t / 30 EU/t / LV
    // ⚠️ 原来写的是 `inputItems(GTMaterials.Aluminium, 2)` / `outputItems(Al2O3, 1)` ——
    //    这两个重载不认 Material，会静默丢掉输入和输出（见类注释坑点 1），现在补上 `dust` 前缀。
    fun init(provider: Consumer<FinishedRecipe>){
        CHEMICAL_RECIPES.recipeBuilder(Gtetcore.id("aluminium_oxide"))
            .inputItems(dust, GTMaterials.Aluminium, 2)
            .inputFluids(GTMaterials.Oxygen, 3000)
            .outputItems(dust, Al2O3, 1)
            .duration(200)
            .EUt(VA[MV].toLong())
            .save(provider)

        // CaO 是 GTM 的 gtceu:quicklime（见 ETMaterialRegister 里的别名）。
        // GTM 自己**没有**「钙 + 氧 → 氧化钙」这条化学反应釜配方（它的 quicklime 来自方解石/大理石那条链），
        // 所以这条不是重复，留着。
        CHEMICAL_RECIPES.recipeBuilder(Gtetcore.id("calcium_oxide"))
            .inputItems(dust, GTMaterials.Calcium, 1)
            .inputFluids(GTMaterials.Oxygen, 1000)
            .outputItems(dust, CaO, 1)
            .duration(200)
            .EUt(VA[LV].toLong())
            .save(provider)


        // ================================================================================
        //  玻璃纤维 / 树脂基复合材料（GFRP）链路
        //
        //  用户给的最终比例：20 Resin : 20 GlassFiber : 1 Filler
        //  拆到氧化物/元素：
        //    20C : 20H : 5O : 11SiO₂ : 4CaO : 2Al₂O₃ : 1MgO : 1Na₂O : 1K₂O : 1Filler
        // ================================================================================

        // ---- 氧化钠 / 氧化钾 ----
        //
        // GTM 里既没有 Na₂O 也没有 K₂O（全仓 grep `sodium_oxide` / `potassium_oxide` 零命中），
        // 也就没有任何现成配方能产出它们 —— 这两条是它们**唯一**的来源。
        // 配比严格配平：4M + O₂ → 2M₂O（左边 M₄O₂ = 右边 M₄O₂）。
        CHEMICAL_RECIPES.recipeBuilder(Gtetcore.id("na2o"))
            .inputItems(dust, GTMaterials.Sodium, 4)
            .inputFluids(GTMaterials.Oxygen, 1000)
            .outputItems(dust, Na2O, 2)
            .duration(200)
            .EUt(VA[LV].toLong())
            .save(provider)

        CHEMICAL_RECIPES.recipeBuilder(Gtetcore.id("k2o"))
            .inputItems(dust, GTMaterials.Potassium, 4)
            .inputFluids(GTMaterials.Oxygen, 1000)
            .outputItems(dust, K2O, 2)
            .duration(200)
            .EUt(VA[LV].toLong())
            .save(provider)

        // ---- 玻璃纤维（搅拌机）----
        //
        // ⚠️ 搅拌机的物品输入上限**正好 6 种**：`MIXER_RECIPES = register("mixer", ELECTRIC)
        //    .setMaxIOSize(6, 1, 2, 1)`（GTRecipeTypes.java:315：6 物品入 / 1 物品出 / 2 流体入 / 1 流体出），
        //    机器侧的输入仓就是按 `getMaxInputs(ItemRecipeCapability.CAP)` 建的 6 格
        //    （WorkableTieredMachine.java:119）。E-glass 的氧化物恰好 6 种 ⇒ **刚好放得下，不用缩配方**。
        //    再多一种 GTM 只会打一条 warn 日志（GTRecipeBuilder.java:1811-1824）然后照常注册，
        //    但 6 格输入仓塞不下，玩家永远做不出来 —— 所以别再往这条里加东西。
        //
        // 输出 20 = 11 + 4 + 2 + 1 + 1 + 1，与 GlassFiber 的 components 之和一致。

        // ---- 树脂（化学反应釜）----
        //
        // 4 苯酚 + 2 苯乙烯 + 3 氧气 → 2 树脂，**元素严格配平**：
        //   苯酚 C₆H₆O ×4 = C₂₄H₂₄O₄ ；苯乙烯 C₈H₈ ×2 = C₁₆H₁₆ ；O₂ ×3 = O₆
        //   左边合计 C₄₀H₄₀O₁₀ = 右边 2 × C₂₀H₂₀O₅  ✓
        // 为什么用化学反应釜：这是聚合/氧化反应，不是单纯的「拌一拌」；
        // 而且化学反应釜有 3 个流体输入槽（`setMaxIOSize(2, 2, 3, 2)`，GTRecipeTypes.java:180），
        // 3 种流体刚好放得下。
        CHEMICAL_RECIPES.recipeBuilder(Gtetcore.id("resin"))
            .inputFluids(GTMaterials.Phenol, 4000)
            .inputFluids(GTMaterials.Styrene, 2000)
            .inputFluids(GTMaterials.Oxygen, 3000)
            .outputItems(dust, Resin, 2)
            .duration(400)
            .EUt(VA[MV].toLong())
            .save(provider)
    }

}