@file:Suppress("UNCHECKED_CAST", "DEPRECATION","unused")
package rain.gtetcore.gtet.data.recipes

import com.gregtechceu.gtceu.api.GTValues.*
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.gregtechceu.gtceu.api.data.tag.TagPrefix.dust
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.common.data.GTRecipeTypes.*
import net.minecraft.data.recipes.FinishedRecipe
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.registries.ForgeRegistries
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.data.ETMaterial.*
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
    fun init(provider: Consumer<FinishedRecipe>){

        // gtetcore:large_chemical_reactor / alloy_blast_smelter_uv_parallel_hatch / 200t / 122880 EU/t / ZPM
        //
        // ⚠️ 这条原来挂在 `ALLOY_SMELTER_RECIPES` 上，两处接错了，顺手修掉：
        //    1. `ALLOY_SMELTER_RECIPES` 是 `setMaxIOSize(2, 1, 0, 0)`（GTRecipeTypes.java:91）——
        //       **流体输入槽 = 0**。原配方往里塞了 1000mB 流体，GTM 只会打
        //       「trying to add more inputs than its recipe type can support, Max fluid inputs: 0」，
        //       单方块合金炉的流体仓是 0 格，这条配方永远跑不起来。
        //    2. 注释里写的是 `gtceu:alloy_blast_smelter`（合金高炉，`GCYMRecipeTypes.ALLOY_BLAST_RECIPES`
        //       = `setMaxIOSize(9, 0, 3, 1)`，GCYMRecipeTypes.java:29-30）—— 那个类型**物品输出槽 = 0**
        //       （它只出熔融金属流体），所以 64 个 `uv_parallel_hatch` 物品在那边也交不出来。
        //    ⇒ 需要「多物品输入 + 物品输出 + 流体进出」的机器只有大型化学反应釜
        //      `LARGE_CHEMICAL_RECIPES = setMaxIOSize(3, 3, 5, 4)`（GTRecipeTypes.java:643-644）
        //      能全覆盖（1 物品入 / 1 物品出 / 1 流体入 / 1 流体出，都在上限内）。
        //      想改回合金高炉，就得把物品输出删掉、改成出流体；想用装配线（16 物品入 / 1 物品出 /
        //      4 流体入）则要删掉那个 1000mB 的流体输出（它的流体输出槽是 0）。
        LARGE_CHEMICAL_RECIPES.recipeBuilder(Gtetcore.id("alloy_blast_smelter_uv_parallel_hatch"))
            // ⚠️ 物品 id 原来是 `overclock_hatch_16x_saving_max`，**这个仓不存在** ——
            //    ETOverclockHatches.kt:119-144 的 17 档里只有 `..._1024x_saving_max` 带 `_max` 后缀，
            //    原来那个名字会让 `ForgeRegistries.ITEMS.getValue` 返回 null，
            //    GTM 直接报 "Input item 0 of recipe ... is empty"（实测）。
            .inputItems(ForgeRegistries.ITEMS.getValue(
                    ResourceLocation("gtetcore", "overclock_hatch_1024x_saving_max"))!!, 64)
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


        // gtceu:chemical_reactor / test_thread / 200t / 30 EU/t / LV
        // ⚠️ 原来写的是 `inputItems(GTMaterials.Aluminium, 2)` / `outputItems(Al2O3, 1)` ——
        //    这两个重载不认 Material，会静默丢掉输入和输出（见类注释坑点 1），现在补上 `dust` 前缀。
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

        // ---- 树脂基复合材料（搅拌机）----
        //
        // 为什么选搅拌机而不是合金炉 / 化学反应釜：
        //   · 合金炉 `ALLOY_SMELTER_RECIPES` 只有 **2 个**物品输入槽（GTRecipeTypes.java:91
        //     `setMaxIOSize(2, 1, 0, 0)`）—— 树脂 + 玻璃纤维 + 填料是 3 种固体，放不下；
        //   · 化学反应釜也只有 **2 个**物品输入槽（:180 `setMaxIOSize(2, 2, 3, 2)`）—— 同样放不下；
        //   · 搅拌机 6 个物品输入槽，3 种固体绰绰有余。而且物理上「把纤维和填料拌进树脂」
        //     本来就是搅拌的活，GTM 自己也就用搅拌机做「橡胶 + 硫 → 橡胶」这类混料。
        //
        // ⚠️ 输出 41 = 20 + 20 + 1，**必须**等于 GFRP 的 components 之和
        //    （GTM 惯例：成分之和 = 一次合成的产出数，例如青铜 3Cu + 1Sn → 4 青铜）。
        //    GFRP 上了 DISABLE_DECOMPOSITION，所以没有反向的电解配方来验证这个数，
        //    改成分时记得同步改这里。
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
