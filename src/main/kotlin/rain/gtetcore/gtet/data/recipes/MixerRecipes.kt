package rain.gtetcore.gtet.data.recipes

import com.gregtechceu.gtceu.api.GTValues.HV
import com.gregtechceu.gtceu.api.GTValues.MV
import com.gregtechceu.gtceu.api.GTValues.VA
import com.gregtechceu.gtceu.api.data.tag.TagPrefix.dust
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.common.data.GTRecipeTypes.MIXER_RECIPES
import net.minecraft.data.recipes.FinishedRecipe
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.data.ETMaterial.Al2O3
import rain.gtetcore.gtet.common.data.ETMaterial.CaO
import rain.gtetcore.gtet.common.data.ETMaterial.GFRP
import rain.gtetcore.gtet.common.data.ETMaterial.GlassFiber
import rain.gtetcore.gtet.common.data.ETMaterial.K2O
import rain.gtetcore.gtet.common.data.ETMaterial.Na2O
import rain.gtetcore.gtet.common.data.ETMaterial.Resin
import java.util.function.Consumer

object Mixer_recipes {
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