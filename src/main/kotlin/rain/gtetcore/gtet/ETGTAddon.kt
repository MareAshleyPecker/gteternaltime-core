package rain.gtetcore.gtet

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.addon.GTAddon
import com.gregtechceu.gtceu.api.addon.IGTAddon
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.javafmlmod.FMLModContainer
import net.minecraftforge.registries.ForgeRegistries
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.data.item.ETItems
import rain.gtetcore.gtet.common.data.machine.muiltmachine.ALLMmchine
import rain.gtetcore.gtet.common.material.ETElements
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
        ETItems.init()
        ETBlock.init()
        check(ALLMmchine.TEST_MULTIBLOCK != null) {
            "GTET 的机器没有被注册：GTM 的 GTCEuAPI.RegisterEvent 没有触发 CommonProxy.registerMachines"
        }
        hideGtmParallelHatchesFromCreativeTabs()
    }

    /** 返回本模组的 MODID。 */
    override fun addonModId(): String {
        return Gtetcore.MODID
    }

    /** 注册自定义化学元素。 */
    override fun registerElements() {
        ETElements.init()
    }

    /**
     * 剔除 GTM 自带并行仓（`GCYMMachines.PARALLEL_HATCH`，IV/LuV/ZPM/UV 四档）的合成配方
     * —— 这四档由 GTET 自己的并行仓（[rain.gtetcore.gtet.common.data.machine.otherMachine.ETParallelHatches]）接管。
     *
     * 为什么走这个钩子而不是改 GTM 的数值：数值改了还会在 EMI/JEI 里并排出现两种「同档不同并行数」
     * 的仓，玩家分不清；直接从配方层面拿掉才是「整合包里没有它」。
     *
     * ⚠️ 配方 id 不是 `gtceu:parallel_hatch_mk1`：GTM 的 `ShapedRecipeBuilder.getId()` 会在注册名
     * 前面拼上 `shaped/`（GTM 7.5.3 `data/recipe/builder/ShapedRecipeBuilder.java:166-168`），
     * 而 `GCYMRecipes` 用的是 `VanillaRecipeHelper.addShapedRecipe(provider, "parallel_hatch_mk1", ...)`，
     * 注册名再由 `GTCEu.id()` 补上 `gtceu:` 命名空间 ⇒ **`gtceu:shaped/parallel_hatch_mk1`**。
     *
     * @param consumer GTM 的配方过滤器（`GTRecipes.RECIPE_FILTERS`），它会先跑一遍、再生成配方
     */
    override fun removeRecipes(consumer: Consumer<ResourceLocation>) {
        GTM_PARALLEL_HATCH_RECIPE_NAMES.forEach { consumer.accept(GTCEu.id("shaped/$it")) }
    }

    /**
     * 把 GTM 那 4 个并行仓方块从创造页条目里删掉（它们落在 GT 的 machine 页：`GTMachines` 第 67 行
     * 把 registrate 的 currentTab 设成 MACHINE，`GTRegistrate.isInCreativeTab` 查的就是这张表）。
     *
     * 用 Forge 官方事件而不是 mixin：`BuildCreativeModeTabContentsEvent.getEntries()` 就是
     * GTM 的 displayItems 生成器跑完之后、结果写回创造页之前的那张**可变**表
     * （Forge `ForgeHooks#onCreativeModeTabBuildContents` 的次序是「跑生成器 → 发事件 → 拷进创造页」），
     * 所以在这里 `remove` 是官方支持的删除方式，删掉就真的不出现。
     * ⚠️ 该事件只在客户端发（`Event` 的 javadoc 明写 LogicalSide.CLIENT），服务端注册了也收不到，无副作用。
     *
     * ⚠️ 不能用 `FMLJavaModLoadingContext.get()` 拿事件总线：本回调由 GTM 在 `GTCEu` 的**构造器**里触发
     * （GTM `CommonProxy:162`），而 `ModLoadingContext.get()` 读的是 ThreadLocal，那一刻里面装的是
     * GTCEu 自己的上下文，拿到的会是 GTM 的总线。这里按 GTM 自己的做法（`CommonProxy:184-189`）
     * 从 `ModList` 里取本 mod 容器的总线。
     *
     * 注：EMI / JEI 的物品列表另有 `c:hidden_from_recipe_viewers` 标签负责（见 `src/main/resources/data/c/tags/`）。
     */
    private fun hideGtmParallelHatchesFromCreativeTabs() {
        val container = ModList.get().getModContainerById(Gtetcore.MODID).orElse(null) as? FMLModContainer ?: return
        container.eventBus.addListener(Consumer<BuildCreativeModeTabContentsEvent> { event ->
            for (name in GTM_PARALLEL_HATCH_NAMES) {
                // 取不到注册项（GTM 没装/被改）时 getValue 返回 null，直接跳过，别把空栈塞进表里
                val item = ForgeRegistries.ITEMS.getValue(GTCEu.id(name)) ?: continue
                event.entries.remove(ItemStack(item))
            }
        })
    }

    companion object {

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
    }
}
