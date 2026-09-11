package rain.gtetcore.gtet.common.data.machine.muiltmachine

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.registry.GTRegistries
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.ETOverclockHatches
import rain.gtetcore.gtet.common.data.machine.ETTestMultiblocks
import rain.gtetcore.gtet.common.data.machine.ETThreadHatches

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
     * 全部「线程仓」部件方块（7 个变体，UV ~ MAX），由 [init] 填充。
     *
     * 与 [OVERCLOCK_HATCHES] 同理：线程仓也是多方块**部件**，但注册入口挂在多方块这边；
     * 创造模式页同样在 `ETThreadHatches.register` 里显式切到 [GTETCreativeModeTabs.MACHINE]。
     *
     * 注册顺序放在超频仓**之后**、[TEST_MULTIBLOCK] **之前**：
     * 这两个部件的 `register` 都自己显式设过创造页（MACHINE），前后无所谓；
     * 而 [ETTestMultiblocks.register] 开头会自己切回 [GTETCreativeModeTabs.MULTIBLOCK]，
     * 所以多方块必须放在最后。
     */
    var THREAD_HATCHES: List<MachineDefinition> = emptyList()
        private set

    /**
     * GTET 的第一台多方块机器「多方块测试机」（`gtetcore:test_multiblock`），由 [init] 填充。
     *
     * 注册细节全在 [ETTestMultiblocks.register] 里，这里只做接线（和 [OVERCLOCK_HATCHES] 一样，
     * 复用本对象已有的 `unfreeze()` / `freeze()` 包装，不重复实现）。
     *
     * 注册顺序放在两个部件表**之后**是有意的：`ETOverclockHatches.register` 与
     * `ETThreadHatches.register` 都会把 registrate 的当前创造页切到 [GTETCreativeModeTabs.MACHINE]，
     * 而 [ETTestMultiblocks.register] 开头会显式切回 [GTETCreativeModeTabs.MULTIBLOCK]，
     * 两者互不影响。
     */
    var TEST_MULTIBLOCK: MultiblockMachineDefinition? = null
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
            // 多方块部件「线程仓」。它自己也把当前创造页设成 MACHINE，所以在两个部件表之间
            // 顺序无所谓；放在多方块之前是因为 ETTestMultiblocks.register 会切到 MULTIBLOCK。
            THREAD_HATCHES = ETThreadHatches.register(ETRegistrate)
            // 多方块机器。注意 ETTestMultiblocks.register 内部会自己把当前创造页切到
            // MULTIBLOCK（上两步的部件把它切到了 MACHINE）。
            TEST_MULTIBLOCK = ETTestMultiblocks.register(ETRegistrate)
        } finally {
            // 在 GTCEu 注册事件的窗口里注册时表本来就没冻，这时不要多此一举（freeze() 会抛异常）
            if (wasFrozen) registry.freeze()
        }
    }
}
