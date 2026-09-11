@file:Suppress("UNCHECKED_CAST", "DEPRECATION","unused")
package rain.gtetcore.gtet.init

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.data.event.GatherDataEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.gtet.config.GTETConfig
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.data.item.ETItems
import rain.gtetcore.gtet.common.data.machine.muiltmachine.ALLMmchine
import rain.gtetcore.gtet.common.data.machine.samplemachine.ALLSmahine
import rain.gtetcore.gtet.common.material.ETElementMaterials
import rain.gtetcore.gtet.data.GTETDatagen

/**
 * 通用代理 —— 客户端和服务端都需要加载的初始化逻辑。
 */

open class CommonProxy(private val context: FMLJavaModLoadingContext) {

    @Suppress("unused")
    private lateinit var materialRegistry: MaterialRegistry

    init {
        val bus: IEventBus = context.modEventBus
        bus.register(this)
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this)
        kotlinInit()
    }

    /**
     * Kotlin 端额外的初始化逻辑。
     *
     * 子类可重写以注入额外初始化。当前触发 [GTETConfig.init] 与 [GTETCreativeModeTabs.init]。
     */
    protected fun kotlinInit() {
        // 注册配置：config/gtetcore/gtetcore-common.toml（ForgeConfigSpec，COMMON 类型）
        // 用 [Gtetcore] 构造器注入进来的 context 注册 —— 不再走已弃用的 ModLoadingContext.get()
        GTETConfig.init(context)
        // 高级终端扩展用到的双语条目（必须在数据生成前注册）
        rain.gtetcore.gtet.common.item.terminal.TerminalLang.init()
        // 结构工具的网络包（客户端滚轮切模式 → 服务端改 NBT）
        rain.gtetcore.gtet.common.item.tool.ToolNetwork.register()
        GTETCreativeModeTabs.init()
        ETItems.init()
        ETBlock.init()
        // 注意：机器注册（ALLMmchine.init / ALLSmahine.init）不在这里，也不靠下面的
        // registerMachines 事件 —— 那个 GTCEu 事件是 GTM 在自己 mod 构造期间发的，本 mod 的
        // CommonProxy 当时还不存在，监听器永远收不到。机器注册放在 GTCEu 官方的 addon 回调
        // [rain.gtetcore.gtet.ETGTAddon.initializeAddon] 里（GTM 的材料/机器/模型那时都已就绪）。
    }

    /** Forge 通用设置阶段回调。 */
    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        // 材料物品由 MixinGTMaterialItems @Overwrite 接管，此处无需额外生成
    }


    /** 创建本模组专属的 [MaterialRegistry] 材料注册表。 */
    @SubscribeEvent
    fun registerMaterialRegistry(event: MaterialRegistryEvent?) {
        // 先强制填充 AddonFinder 缓存，确保 MaterialRegistry 构造时 getAddon(modId) 能找到我们的 addon
        // 从而 registry.getRegistrate() 返回 OnlyETreg.ETRegistrate 而非 standalone registrate
        com.gregtechceu.gtceu.api.addon.AddonFinder.getAddons()
        materialRegistry = GTCEuAPI.materialManager.createRegistry(Gtetcore.MODID)
    }

    /** 注册自定义材料（MaterialEvent 阶段只能注册，不能读取 allMaterials）。 */
    @SubscribeEvent
    fun registerMaterials(event: MaterialEvent?) {
        ETElementMaterials.register()
    }

    /**
     * GTCEu 的机器注册事件。
     *
     * 注意：这个监听器**不一定收得到** —— GTM 是在它自己的 mod 构造期间
     * （`GTMachines.init()` 里 `ModLoader.postEvent`）发出该事件的，而 Forge 是并行构造 mod 的，
     * 本 mod 的 CommonProxy 有可能还没构造完（竞态，实测经常收不到）。
     * 所以机器注册的真正入口是 [rain.gtetcore.gtet.ETGTAddon.initializeAddon]（一定会被 GTCEu 回调），
     * 这里保留只是为了「万一赶上了就先注册」，[ALLMmchine.init] / [ALLSmahine.init] 自身有幂等保护。
     */
    @SubscribeEvent
    fun registerMachines(event: GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition>) {
        ALLMmchine.init()
        ALLSmahine.init()
    }

    /**
     * 数据生成入口 — 注册 en_us / zh_cn 语言文件 Provider。
     */
    @SubscribeEvent
    fun onGatherData(event: GatherDataEvent) {
        GTETDatagen.init(event.generator)
    }
}

