package rain.gtetcore.gtet.data

import com.tterrag.registrate.providers.ProviderType
import net.minecraft.data.DataGenerator
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.data.lang.LangHandler

/** 数据生成入口。 */
object GTETDatagen {

    /** mod 构造阶段调用，挂载 en_us 到 Registrate LANG provider。 */
    fun initRegistrate() {
        OnlyETreg.ETRegistrate.addDataGenerator(ProviderType.LANG) { LangHandler.init(it) }
    }

    /**
     * GatherDataEvent 中调用，注册 zh_cn LanguageProvider。
     *
     * ## ⚠️ 配方**不能**挂在这里（试过，会崩）
     *
     * 直觉上应该在这里再挂一个 `RecipeProvider` 把 [rain.gtetcore.gtet.data.recipes.ALLRecipes.init] 接上，
     * 但**在 GTM 7.5.3 下这条路走不通**，实测 `gradlew runData` 直接 BUILD FAILED：
     * ```
     * Caused by: java.lang.NullPointerException: Cannot invoke "net.minecraft.client.Minecraft.isSameThread()"
     *   because the return value of "net.minecraft.client.Minecraft.getInstance()" is null
     *     at gtceu@7.5.3/com.gregtechceu.gtceu.GTCEu.isClientThread(GTCEu.java:120)
     *     at gtceu@7.5.3/com.gregtechceu.gtceu.api.registry.GTRegistries.builtinRegistry(GTRegistries.java:119)
     *     at gtceu@7.5.3/com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder.toJson(GTRecipeBuilder.java:1599)
     * ```
     * 链条是：`RecipeProvider` 写文件 → `FinishedRecipe.serializeRecipeData` → `GTRecipeBuilder.toJson()`
     * → `GTRegistries.builtinRegistry()` → `GTCEu.isClientThread()`。
     * 而 `isClientThread()` 是 `FMLEnvironment.dist.isClient() && Minecraft.getInstance().isSameThread()`：
     * ForgeGradle 的 `runData` 跑的是**客户端 dist**（`ForgeDataUserdevLaunchHandler`），
     * `isClientSide()` 为 true，但数据生成环境里根本没有 `Minecraft` 实例 ⇒ NPE。
     *
     * 所以 GTM 的配方**本来就不走 datagen**：它走运行时动态数据包
     * （`GTRecipes.recipeAddition(Consumer<FinishedRecipe>)` → `GTDynamicDataPack::addRecipe`，
     * 由 `AddPackFindersEvent` 挂成 `gtceu:dynamic_data`），插件入口就是
     * `IGTAddon.addRecipes(Consumer<FinishedRecipe>)`。
     * GTET 的配方因此挂在 [rain.gtetcore.gtet.ETGTAddon.addRecipes]，
     * 详见 [rain.gtetcore.gtet.data.recipes.ALLRecipes] 的类注释。
     *
     * ⇒ **不要**在这里加 RecipeProvider / Registrate 的 `ProviderType.RECIPE`，会重现上面那个 NPE。
     */
    fun init(gen: DataGenerator) {
        gen.addProvider(true, LangHandler.ZhCNProvider(gen.packOutput))
    }
}
