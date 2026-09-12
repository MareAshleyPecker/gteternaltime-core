@file:Suppress("UNCHECKED_CAST", "DEPRECATION","unused")
package rain.gtetcore.gtet.init

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.addon.AddonFinder
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.registry.GTRegistries
import net.minecraftforge.common.MinecraftForge
import rain.gtetcore.gtet.common.data.machine.muiltmachine.ALLMmchine
import rain.gtetcore.gtet.common.data.machine.samplemachine.ALLSmahine
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
import rain.gtetcore.gtet.common.item.recipe.PhantomCountSlotWidget
import rain.gtetcore.gtet.common.item.terminal.TerminalLang
import rain.gtetcore.gtet.common.item.tool.ToolNetwork
import rain.gtetcore.gtet.common.machine.ThreadedRecipeStatus
import rain.gtetcore.gtet.common.material.ETElementMaterials
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
        TerminalLang.init()
        // 多线程内核的线程状态文本（机器 UI 与 GTET 自己的 Jade provider 共用同一批语言键）。
        // 同样必须在数据生成**之前**登记：Jade 插件类要等加载末尾被注解扫描到才会加载，
        // 把语言键挂在那个类里会赶不上 GatherDataEvent。
        ThreadedRecipeStatus.initLang()
        // 配方编辑器「中键改数量」对话框的文案（同上：必须在数据生成之前登记）。
        PhantomCountSlotWidget.initLang()
        // GTET 自己的 Jade provider 在 Jade 的插件配置界面里也要有翻译键：Jade 会遍历所有 provider 的 uid，
        // 断言 `config.jade.plugin_<命名空间>.<uid 路径>` 这条键必须存在，缺一条就在标题界面抛
        // AssertionError 把客户端崩掉（校验点是 snownee.jade.JadeClient#onGui，第三方 mod 的原话见
        // GTETJadeLang 的类注释）。这里必须和上面两处一样在**数据生成之前**登记：
        // Jade 插件类要等加载末尾的注解扫描才会被加载，把键挂在那个类里赶不上 GatherDataEvent。
        GTETJadeLang.initLang()
        // 结构工具的网络包（客户端滚轮切模式 → 服务端改 NBT）
        ToolNetwork.register()
        GTETCreativeModeTabs.init()
        ETItems.init()
        ETBlock.init()
        // 机器（ALLMmchine / ALLSmahine）不在这里注册，而是由上面的 [registerMachines] 在
        // GTM 的机器注册窗口里注册 —— 那里是 GTM 唯一允许往机器表里加东西的时刻。
        // 幂等保护：ALLMmchine.init() 内部有 `initialized` 标志，重复调用只空转。
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
     * GTM 只在 `GTMachines.init()` 里发一次这个事件（`GTMachines` 第 1099 行），紧接着就
     * `GTRegistries.MACHINES.freeze()`（第 1101 行）并遍历全表登记渲染态（第 1103-1107 行）。
     * 所以在事件里注册 = **冻结之前入表** + **渲染态由 GTM 自己那次循环一起登记**，
     * 两头都不需要 GTET 自己动手（不要再写 unfreeze()/freeze() 或补登记循环）。
     *
     * ⚠️ 参数必须写成 `RegisterEvent<*, *>`，**不要**写成 `RegisterEvent<ResourceLocation, MachineDefinition>`：
     * `RegisterEvent<K, V> extends GenericEvent<V>`，而 Forge EventBus 解析监听器泛型时只认监听器
     * 自己那层参数化类型的类型参数、不认父类 `GenericEvent<V>` 里的类型变量 —— 写成两个具体类型时
     * 这条方法**一次都不会被调用**（实测：同一次加载里通配写法收到 11 次，具体写法 0 次，且不报错），
     * 机器就会静默地一个都不注册。所以这里用通配写法 + [net.minecraftforge.eventbus.api.GenericEvent.getGenericType]
     * 精确过滤出「机器表那一场」。
     *
     * 确定性：事件由 GTM 在主线程按固定顺序 `ModList.forEachModInOrder` 发给各 mod 的 mod 总线，
     * 不依赖线程调度；各 mod 的注册顺序 = mod 加载顺序，机器表内容与插入顺序两端完全一致，
     * 因此 GTM 那次渲染态循环推出来的数字 id（= 网络协议）也两端一致。
     */
    @SubscribeEvent
    fun registerMachines(event: GTCEuAPI.RegisterEvent<*, *>) {
        if (event.genericType != MachineDefinition::class.java) return
        ALLMmchine.init()
        ALLSmahine.init()
        Gtetcore.LOGGER.info(
            "GTET machines registered inside GTM's machine-registry window: total={}",
            GTRegistries.MACHINES.registry().size
        )
    }


    /** 创建本模组专属的 [MaterialRegistry] 材料注册表。 */
    @SubscribeEvent
    fun registerMaterialRegistry(event: MaterialRegistryEvent?) {
        // 先强制填充 AddonFinder 缓存，确保 MaterialRegistry 构造时 getAddon(modId) 能找到我们的 addon
        // 从而 registry.getRegistrate() 返回 OnlyETreg.ETRegistrate 而非 standalone registrate
        AddonFinder.getAddons()
        materialRegistry = GTCEuAPI.materialManager.createRegistry(Gtetcore.MODID)
    }

    /** 注册自定义材料（MaterialEvent 阶段只能注册，不能读取 allMaterials）。 */
    @SubscribeEvent
    fun registerMaterials(event: MaterialEvent?) {
        ETElementMaterials.register()
    }

    /*
     * ⚠️ 关于「机器注册路径」的踩坑记录，别再走回头路：
     *
     * 1. `IGTAddon.initializeAddon()`（GTM `CommonProxy` 第 162 行）**在冻结之后**才被调用；
     *    它之前的那些 addon 回调（registerCovers / collectMaterialCasings / …）又都跑在
     *    `GTMachines` 类初始化之前 —— 实测那一刻 `GTRegistries.MACHINES.isFrozen == true`。
     *    所以「在 addon 回调里注册机器」这条路根本不存在：唯一解是上面的 RegisterEvent。
     * 2. 曾经删掉过这个监听器，理由是「GTM 在自己 mod 构造期发事件、Forge 并行构造 mod ⇒ 竞态」。
     *    实测（datagen 一次加载，日志留在 run-data/logs）：
     *    - 竞态不成立：`MaterialRegistryEvent`（GTM `CommonProxy` 第 213 行发，早于机器事件）每次都收到，
     *      说明本 mod 的 mod 总线监听器在 GTM 跑到第 155 行 `GTMachines.init()` 之前就已就位；
     *    - 真正让监听器「收不到」的是**泛型写法**：`RegisterEvent<ResourceLocation, MachineDefinition>`
     *      这种具体化写法 EventBus 匹配不上（见 [registerMachines] 的 ⚠️），换成 `RegisterEvent<*, *>` 立刻正常。
     */

    /**
     * 数据生成入口 — 注册 en_us / zh_cn 语言文件 Provider。
     */
    @SubscribeEvent
    fun onGatherData(event: GatherDataEvent) {
        GTETDatagen.init(event.generator)
    }
}

