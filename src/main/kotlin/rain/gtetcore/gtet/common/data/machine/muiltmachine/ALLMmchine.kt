package rain.gtetcore.gtet.common.data.machine.muiltmachine

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.registry.GTRegistries
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.ETOverclockHatches

/**
 * 多方块机器
 * 所有多方块机器在此初始化并注册到 [GTETCreativeModeTabs.MULTIBLOCK] 选项卡。
 */
object ALLMmchine {

    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MULTIBLOCK)
    }

    /** [init] 只跑一次（addon 回调与 GTCEu 注册事件都可能来敲）。 */
    private var initialized = false

    /**
     * 全部「超频仓」部件方块（6 个变体），由 [init] 填充。
     *
     * 超频仓虽然是多方块**部件**，但注册入口挂在多方块这边（它是为多方块服务的），
     * 创造模式页在 `ETOverclockHatches.register` 里显式切到 [GTETCreativeModeTabs.MACHINE]。
     */
    var OVERCLOCK_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /**
     * 注册本模组的全部机器。
     *
     * ## 为什么这里要自己 unfreeze / freeze 一次
     * GTM 的机器表 `GTRegistries.MACHINES` 默认是**冻结**的，GTM 只在自己
     * `GTMachines.init()` 里走一遍「`unfreeze()` → 发 `GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition>`
     * → `freeze()`」，把「往机器表里加东西」的窗口限定在那一瞬间（见 `GTMachines` 第 68 / 1099 / 1101 行）。
     *
     * 本 mod 有两个入口可能敲到这里：
     * - `CommonProxy.registerMachines`（GTCEu 注册事件）：只有在我们的 mod 构造先于 GTM 那次事件完成时才会响，
     *   是个竞态，不能依赖；
     * - `ETGTAddon.initializeAddon()`（GTCEu 官方 addon 回调）：**一定会响**，但它在 GTM 冻结机器表之后。
     *
     * 所以这里按 GTM 自己的写法补一层：只要表是冻着的就先解冻、注册完立刻冻回去。
     * 注意 `GTRegistry.unfreeze()` 只在「当前活动 mod 容器是 gtceu / minecraft」时才真的生效
     * （`checkActiveModContainerIsGregtech()`），而 addon 回调正是在 GTM 自己的 mod 构造期间跑的，
     * 所以这里的解冻是有效的。
     */
    fun init() {
        if (initialized) return
        initialized = true

        val registry = GTRegistries.MACHINES
        val wasFrozen = registry.isFrozen
        if (wasFrozen) registry.unfreeze()
        try {
            OVERCLOCK_HATCHES = ETOverclockHatches.register(ETRegistrate)
        } finally {
            // 在 GTCEu 注册事件的窗口里注册时表本来就没冻，这时不要多此一举（freeze() 会抛异常）
            if (wasFrozen) registry.freeze()
        }
    }
}
