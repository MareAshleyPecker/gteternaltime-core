@file:Suppress("UNCHECKED_CAST", "DEPRECATION","unused")
package rain.gtetcore.gtet.init

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry
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
        // 多线程内核的线程状态文本（机器 UI 与 GTET 自己的 Jade provider 共用同一批语言键）。
        // 同样必须在数据生成**之前**登记：Jade 插件类要等加载末尾被注解扫描到才会加载，
        // 把语言键挂在那个类里会赶不上 GatherDataEvent。
        rain.gtetcore.gtet.common.machine.ThreadedRecipeStatus.initLang()
        // 结构工具的网络包（客户端滚轮切模式 → 服务端改 NBT）
        rain.gtetcore.gtet.common.item.tool.ToolNetwork.register()
        GTETCreativeModeTabs.init()
        ETItems.init()
        ETBlock.init()
        // 注意：机器注册（ALLMmchine.init / ALLSmahine.init）不在这里，而是放在 GTCEu 官方的
        // addon 回调 [rain.gtetcore.gtet.ETGTAddon.initializeAddon] 里（GTM 的材料/机器/模型那时都已就绪）。
        // 这里**不能**再挂 GTCEu 的 RegisterEvent 监听器：那个事件由 GTM 在自己的 mod 构造期间发出，
        // 与本 mod 的构造是竞态，会让渲染态 id 的分配顺序在服务端/客户端之间错位
        // —— 详细的「为什么」见本类末尾那段注释。
        //
        // 唯一入口 + 幂等保护：ALLMmchine.init() 内部有 `initialized` 标志（见 ALLMmchine 第 22-23 行声明、
        // 第 112-113 行 `if (initialized) return`），即使将来某处再调一次也只是空转，不会重复注册。
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

    /*
     * ⚠️ 这里**曾经**有一个机器注册监听器，已刻意删除，不要加回来：
     *
     *     @SubscribeEvent
     *     fun registerMachines(event: GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition>) {
     *         ALLMmchine.init()
     *         ALLSmahine.init()
     *     }
     *
     * ## 为什么删
     * GTCEu 的 `RegisterEvent<ResourceLocation, MachineDefinition>` 是 GTM 在**它自己的 mod 构造期间**
     * （`GTMachines.init()` 里 `ModLoader.postEvent`）发出来的，而 Forge 是**并行构造 mod** 的 ——
     * 本 mod 的 CommonProxy 与 GTM 谁先构造完，取决于线程调度，是竞态。
     * 于是「GTET 的机器从哪条路径进 `GTRegistries.MACHINES`」变成了掷骰子：
     * - 抢到了 → 机器在 RegisterEvent 窗口里进表，渲染态随后由 GTM 自己的遍历登记，
     *   位置取决于 GTM 遍历机器表的顺序（HashMap 顺序），我们的机器被插在**中间**；
     * - 没抢到（走 addon 回调）→ 机器在 GTM 冻结机器表之后才注册，渲染态由
     *   `ALLMmchine.init()` 末尾的 `backfillRenderStates(...)` 按固定顺序**追加到末尾**。
     *
     * ## 为什么这很致命
     * `MachineDefinition.RENDER_STATE_REGISTRY`（`IdMapper<MachineRenderState>`）的 id 是
     * **按插入顺序**分配的，并且会经 `MachineRenderStatePayload` → `FriendlyByteBuf.writeId(...)`
     * 当作网络数字 id 发给客户端。同一个 JVM 内哪条路径都自洽，但专职服务器与客户端是**两个 JVM**：
     * 只要两边的竞态结果不同，id 就会整体错位 —— 不崩溃，但客户端会把机器解成错误的渲染态
     * （成型 / 配方状态 / 喷漆全部对不上号）。
     *
     * ## 现在的约定
     * 机器注册**永远**只走 [rain.gtetcore.gtet.ETGTAddon.initializeAddon] 这一条确定路径：
     * 该回调由 GTCEu 在 GTM `CommonProxy.init()` 末尾按固定顺序调用，与线程调度无关，
     * 因此渲染态 id 的分配顺序在两端必然一致（顺序 = 网络协议的一部分，
     * 见 [rain.gtetcore.gtet.common.data.machine.muiltmachine.ALLMmchine.init] 的 ⚠️）。
     * 删掉监听器后，本类不再调用 ALLMmchine.init() / ALLSmahine.init()，全仓也只有 ETGTAddon 在调。
     */

    /**
     * 数据生成入口 — 注册 en_us / zh_cn 语言文件 Provider。
     */
    @SubscribeEvent
    fun onGatherData(event: GatherDataEvent) {
        GTETDatagen.init(event.generator)
    }
}

