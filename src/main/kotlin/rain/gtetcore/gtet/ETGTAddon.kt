package rain.gtetcore.gtet

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.addon.GTAddon
import com.gregtechceu.gtceu.api.addon.IGTAddon
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import net.minecraft.data.recipes.FinishedRecipe
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.javafmlmod.FMLModContainer
import net.minecraftforge.registries.ForgeRegistries
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.data.item.ETItems
import rain.gtetcore.gtet.common.data.machine.ALLMmachine
import rain.gtetcore.gtet.common.data.material.ETElements
import rain.gtetcore.gtet.data.recipes.ALLRecipes
import java.util.function.Consumer

/**
 * GTET 的 GTCEu 插件入口。
 *
 * 通过 [@GTAddon][GTAddon] 注解注册到 GTCEu 的插件系统，
 * 在 GTCEu 加载时自动回调各生命周期方法。
 *
 * ## 初始化流程
 * 1. [addonModId] — 返回模组 ID
 * 2. [getRegistrate] — 提供统一的 [GTRegistrate] 注册实例
 * 3. [initializeAddon] — 注册物品 [ETItems]、方块 [ETBlock]；**不含机器**（见该方法注释）
 * 4. [registerElements] — 注册自定义化学元素 [ETElements]
 * 5. [removeRecipes] — 从 GTM 的配方生成里剔除它自带并行仓的 4 条配方（GTET 自己接管并行仓）
 *
 * @see OnlyETreg 全局注册表单例
 */
@GTAddon
@SuppressWarnings("all")
open class ETGTAddon : IGTAddon {

    /** 返回模组共享的 [GTRegistrate] 注册实例。 */
    override fun getRegistrate(): GTRegistrate {
        return OnlyETreg.ETRegistrate
    }

    /**
     * 初始化物品、方块与化学元素。此方法由 GTCEu 在加载时回调。
     *
     * ⚠️ 机器**不在这里**注册：这个回调跑在 GTM 冻结机器表之后（`CommonProxy` 第 162 行），
     * 那时 `GTRegistries.MACHINES` 已冻、GTM 登记渲染态的那次循环也已经跑完。机器的注册入口是
     * `rain.gtetcore.gtet.init.CommonProxy.registerMachines`（GTM 的 `GTCEuAPI.RegisterEvent`）。
     * 下面这一行只是「注册确实发生了」的响亮断言：真没注册上就直接崩，别等进游戏才发现机器没了。
     */
    override fun initializeAddon() {
//        ETItems.init()
//        ETBlock.init()
        check(ALLMmachine.TEST_MULTIBLOCK != null) {
            "GTET 的机器没有被注册：GTM 的 GTCEuAPI.RegisterEvent 没有触发 CommonProxy.registerMachines"
        }
        hideGtmXXXFromXXXCreativeTabs()
    }

    /** 返回本模组的 MODID。 */
    override fun addonModId(): String {
        return Gtetcore.MODID
    }

    /** 注册自定义化学元素。 */
    override fun registerElements() {
        ETElements.init()
    }

    /** 注册本模组的全部配方 —— GTET 的配方入口。*/
    override fun addRecipes(provider: Consumer<FinishedRecipe>) {
        ALLRecipes.init(provider)
    }

    /**
     * @param consumer GTM 的配方过滤器（`GTRecipes.RECIPE_FILTERS`），它会先跑一遍、再生成配方
     */
    override fun removeRecipes(consumer: Consumer<ResourceLocation>) {
        forRemoveRecipes(GTM_PARALLEL_HATCH_RECIPE_NAMES,consumer,"shaped")
        forRemoveRecipes(GTM_ASSEMBLY_LINE_RECIPE_NAMES,consumer,"assembly_line")
    }

    private fun hideGtmXXXFromXXXCreativeTabs() {
        forHideCTabs(GTM_PARALLEL_HATCH_NAMES)
        forHideCTabs(GTM_ASSEMBLY_LINE_RECIPE_NAMES)
    }



    companion object {
        /**
         * @param itemOrBlockOrAnyList idList
         * @return HideCreativeModeTabs form [itemOrBlockOrAnyList]
         * */
        fun forHideCTabs(itemOrBlockOrAnyList: List<String>){
            (ModList.get().getModContainerById(Gtetcore.MODID).orElse(null) as? FMLModContainer ?: return)
                .eventBus.addListener(Consumer<BuildCreativeModeTabContentsEvent> {  /*取不到注册项（GTM 没装/被改）时 getValue 返回 null，直接跳过，别把空栈塞进表里*/
                        event -> for (name in itemOrBlockOrAnyList) event.entries.remove(ItemStack(ForgeRegistries.ITEMS.getValue(GTCEu.id(name)) ?: continue))
                })
        }
        /**
         * @param itemOrBlockOrAnyList idList
         * @param recipeTypes GTRecipeTypesOrCustomRecipeTypes
         * @return RemoveRecipes form [itemOrBlockOrAnyList]
         * */
        fun forRemoveRecipes(itemOrBlockOrAnyList: List<String>, consumer: Consumer<ResourceLocation>, recipeTypes: String){
            itemOrBlockOrAnyList.forEach { consumer.accept(GTCEu.id("$recipeTypes/$it")) }
        }

        /** GTM 自带并行仓的方块注册名（IV / LuV / ZPM / UV 四档）。 */
        private val GTM_PARALLEL_HATCH_NAMES = listOf(
            "iv_parallel_hatch",
            "luv_parallel_hatch",
            "zpm_parallel_hatch",
            "uv_parallel_hatch",
        )

        /** GTM 那 4 条配方的注册名（不含 `gtceu:` 命名空间与 `shaped/` 前缀）。 */
        private val GTM_PARALLEL_HATCH_RECIPE_NAMES = listOf(
            "parallel_hatch_mk1",
            "parallel_hatch_mk2",
            "parallel_hatch_mk3",
            "parallel_hatch_mk4",
        )

        private val GTM_ASSEMBLY_LINE_RECIPE_NAMES = listOf(
            "me_pattern_buffer",
            "me_pattern_buffer_proxy"
        )

    }
}
