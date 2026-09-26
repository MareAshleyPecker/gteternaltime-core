@file:Suppress("UNCHECKED_CAST", "DEPRECATION", "unused")

package rain.gtetcore.gtet.init

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.addon.AddonFinder
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.registry.GTRegistries
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.data.event.GatherDataEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.data.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.data.item.ETItems
import rain.gtetcore.gtet.common.data.machine.MachineRegister
import rain.gtetcore.gtet.common.data.material.ETElementMaterials
import rain.gtetcore.gtet.common.data.material.ETMaterialRegister
import rain.gtetcore.gtet.common.item.recipe.FluidCountSlotWidget
import rain.gtetcore.gtet.common.item.recipe.PhantomCountSlotWidget
import rain.gtetcore.gtet.common.item.terminal.TerminalLang
import rain.gtetcore.gtet.common.item.tool.ToolNetwork
import rain.gtetcore.gtet.common.machine.multiblock.modular.ETModularMachine
import rain.gtetcore.gtet.common.machine.multiblock.modular.ETModuleHostMachine
import rain.gtetcore.gtet.common.machine.multiblock.modular.ETModuleMachine
import rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeStatus
import rain.gtetcore.gtet.config.GTETConfig
import rain.gtetcore.gtet.data.GTETDatagen
import rain.gtetcore.gtet.integration.jade.GTETJadeLang

/**
 * 通用代理 —— 客户端和服务端都需要加载的初始化逻辑。
 */

open class CommonProxy(private val context: FMLJavaModLoadingContext) {

    @Suppress("unused")
    private lateinit var materialRegistry: MaterialRegistry

    init {
        val bus: IEventBus = context.modEventBus
        bus.register(this)
        MinecraftForge.EVENT_BUS.register(this)
        kotlinInit()
    }

    /** Kotlin 端初始化：配置、语言键（必须在数据生成之前）、网络包、物品 / 方块与创造页。 */
    protected fun kotlinInit() {
        // 配置：config/gtetcore/gtetcore-common.toml（ForgeConfigSpec，COMMON 类型）
        GTETConfig.init(context)
        initLang()
        // 结构工具的网络包（客户端滚轮切模式 → 服务端改 NBT）
        ToolNetwork.register()
        GTETCreativeModeTabs.init()
        ETItems.init()
        ETBlock.init()
    }

    private fun initLang() {
        // 高级终端扩展用到的双语条目（必须在数据生成前注册）
        TerminalLang.init()
        // 线程状态文本（机器 UI 与 GTET 的 Jade provider 共用；⚠️ 必须赶在数据生成之前登记 —— Jade 插件类加载太晚）
        ThreadedRecipeStatus.initLang()
        // 配方编辑器「中键改数量」对话框的文案（同上）
        PhantomCountSlotWidget.initLang()
        FluidCountSlotWidget.initLang()
        // 模块化多方块基类的文案（同上：必须在数据生成之前）
        ETModularMachine.initLang()
        ETModuleMachine.initLang()
        ETModuleHostMachine.initLang()
        // GTET 的 Jade provider 也要在 Jade 的插件配置界面里有翻译键：Jade 会遍历所有 provider 的 uid 并断言
        // `config.jade.plugin_<ns>.<uid>` 存在，缺一条就在标题界面抛 AssertionError 崩客户端 —— 同上，必须赶在数据生成前
        GTETJadeLang.initLang()
    }

    /** Forge 通用设置阶段回调。 */
    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        // 材料物品由 MixinGTMaterialItems @Overwrite 接管，此处无需额外生成
    }

    // ===== 机器注册：GTM 的正门 =====

    /**
     * 在 GTM 打开机器注册窗口的**那一瞬间**注册 GTET 的全部机器。
     *
     * GTM 只在 `GTMachines.init()` 里发一次这个事件，紧接着就 `GTRegistries.MACHINES.freeze()` 并登记渲染态，
     * 所以在事件里注册 = 冻结之前入表 + 渲染态由 GTM 那次循环一并登记，GTET 不必自己 unfreeze 或补登记。
     *
     * ⚠️ 参数必须写 `RegisterEvent<*, *>`：写成 `RegisterEvent<ResourceLocation, MachineDefinition>` 时
     * EventBus 匹配不上（`RegisterEvent<K, V> extends GenericEvent<V>`，它不认父类里的类型变量），
     * 这条方法**一次都不会被调用**、也不报错，机器会静默地一个都不注册。
     *
     * 注册顺序 = mod 加载顺序（GTM 在主线程按 `ModList.forEachModInOrder` 发事件），两端一致，
     * 所以渲染态循环推出来的数字 id（= 网络协议）也一致。
     */
    @SubscribeEvent
    fun registerMachines(event: GTCEuAPI.RegisterEvent<*, *>) {
        if (event.genericType != MachineDefinition::class.java) return
        // 单方块机器 / 部件仓 / 多方块机器都经这个入口注册（内部各自切创造页）
        MachineRegister.init()
        Gtetcore.LOGGER.info(
            "GTET machines registered inside GTM's machine-registry window: total={}",
            GTRegistries.MACHINES.registry().size
        )
    }


    /** 创建本模组专属的 [MaterialRegistry] 材料注册表。 */
    @SubscribeEvent
    fun registerMaterialRegistry(event: MaterialRegistryEvent?) {
        // 先填 AddonFinder 缓存：这样 MaterialRegistry 构造时 getAddon(modId) 才能找到我们，
        // registry.getRegistrate() 才会返回 ETRegistrate 而不是 standalone registrate
        AddonFinder.getAddons()
        materialRegistry = GTCEuAPI.materialManager.createRegistry(Gtetcore.MODID)
    }

    /** 注册自定义材料（MaterialEvent 阶段只能注册，不能读取 allMaterials）。 */
    @SubscribeEvent
    fun registerMaterials(event: MaterialEvent?) {
        ETElementMaterials.register()
        ETMaterialRegister.register()
    }

    /**
     * 数据生成入口 — 注册 en_us / zh_cn 语言文件 Provider。
     */
    @SubscribeEvent
    fun onGatherData(event: GatherDataEvent) {
        GTETDatagen.init(event.generator)
    }
}

