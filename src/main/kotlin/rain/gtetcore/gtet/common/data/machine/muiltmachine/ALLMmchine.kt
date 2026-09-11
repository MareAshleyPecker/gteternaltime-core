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

    /** [init] 只跑一次（幂等标志：将来即使多入口重复调用，也不会重复注册）。 */
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
     * 本 mod 的机器注册入口**只有一个**：
     * - `ETGTAddon.initializeAddon()`（GTCEu 官方 addon 回调）：**一定会响**，但它在 GTM 冻结机器表之后。
     *
     * ⚠️ 曾经还有第二个入口 `CommonProxy.registerMachines`（GTCEu 注册事件），已删除：
     * 它只有在我们的 mod 构造先于 GTM 那次事件完成时才会响，是个竞态 —— 两条路径会让渲染态 id 的
     * 分配顺序（= 网络协议）在服务端/客户端之间错位，所以必须只留确定的那一条。
     *
     * 所以这里按 GTM 自己的写法补一层：只要表是冻着的就先解冻、注册完立刻冻回去。
     * 注意 `GTRegistry.unfreeze()` 只在「当前活动 mod 容器是 gtceu / minecraft」时才真的生效
     * （`checkActiveModContainerIsGregtech()`），而 addon 回调正是在 GTM 自己的 mod 构造期间跑的，
     * 所以这里的解冻是有效的。
     *
     * ## 为什么注册完还要自己补一遍「渲染态」登记
     * GTM 有一张和原版方块状态表**无关**的独立 id 表：`MachineDefinition.RENDER_STATE_REGISTRY`
     * （`IdMapper<MachineRenderState>`，声明见 `MachineDefinition` 第 48 行）。机器的外观状态
     * （`is_formed` / `recipe_logic_status` / `is_painted` …）**不是** BlockState 属性 ——
     * `MetaMachineBlock.createBlockStateDefinition`（第 83-91 行）只加朝向属性；这些是
     * `MachineBuilder.setupStateDefinition`（第 637-650 行）按 `modelProperty(...)` 建出来的
     * `MachineRenderState`。服务端把渲染态同步给客户端时走
     * `MachineRenderStatePayload.writePayload` → `FriendlyByteBuf.writeId(RENDER_STATE_REGISTRY, state)`，
     * 也就是**两边各自按登记顺序推出来的数字 id**。
     *
     * 问题是这张表 GTM 只填一次：`GTMachines.init()` 在发完 `RegisterEvent`、`GTRegistries.MACHINES.freeze()`
     * 之后，遍历机器表把所有定义的 `getStateDefinition().getPossibleStates()` 逐条 `add` 进去
     * （第 1103-1107 行）。而 addon 回调 `IGTAddon.initializeAddon()` 排在它**后面**
     * （`CommonProxy` 第 155 行 `GTMachines.init()` → 第 162 行 `AddonFinder.getAddons().forEach(IGTAddon::initializeAddon)`），
     * 所以在 [init] 里晚注册的机器**一个都进不了这张表**。后果是：机器只要需要同步一次渲染态
     * （多方块成型/失稳、配方状态变化、被喷漆、部件加入/离开成型结构……），服务端就会在 writeId 时抛
     * `IllegalArgumentException: Can't find id for 'gtetcore:test_multiblock[is_formed=false,recipe_logic_status=idle]'
     * in map net.minecraft.core.IdMapper@…`（`IdMapper.getId` 对没登记过的对象返回 -1）。
     *
     * [wasFrozen] 这个判断是有意的：只有「来的时候机器表已经冻着」才说明 GTM 那次一次性登记已经跑完、
     * 需要我们自己补；如果表是解冻的，说明本次注册发生在 `RegisterEvent` 窗口里，GTM 随后那次循环
     * 会连我们一起登记（它那个循环**没有**去重），这时再补一次就会给同一批状态发两个 id。
     * （唯一入口 `ETGTAddon.initializeAddon` 跑在 GTM 冻结之后，所以这个分支实际总是成立；
     * 保留它是防御性的，**不要**因为「看起来恒为 true」就把它删掉。）
     *
     * ⚠️ 补登记的**顺序**必须稳定：客户端与服务端各自在本地推 id，只有两边 `add` 的顺序一致，
     * 数字 id 才对得上。所以 [backfillRenderStates] 的入参顺序要和上面的注册顺序保持一致，
     * 不要随手调整这三行。
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

        // 晚注册路径（表已冻 = GTM 的一次性登记已经跑完）下，补登记本次注册出来的全部机器渲染态。
        // 三个部件表一起补：超频仓 / 线程仓同样是晚注册，它们的 modelProperty 里也有 is_formed /
        // recipe_logic_status（部件加入或离开成型结构时会被改，见 MultiblockPartMachine 第 141-165 行），
        // 只是这次日志里先炸的是多方块控制器。
        if (wasFrozen) {
            val definitions: List<MachineDefinition> =
                OVERCLOCK_HATCHES + THREAD_HATCHES + listOfNotNull(TEST_MULTIBLOCK)
            backfillRenderStates(definitions)
        }
    }

    /**
     * 把 [definitions] 的渲染态补登记进 `MachineDefinition.RENDER_STATE_REGISTRY`。
     *
     * 语义与 `GTMachines.init()` 里那次循环（第 1103-1107 行）一致：逐个定义遍历
     * `getStateDefinition().getPossibleStates()` 并 `add`。区别只有一个 —— 这里先查
     * `getId(state) == -1` 才 add，用来兜住「GTM 已经登记过」的情况：
     * `IdMapper.getId` 对未登记对象返回 -1（`IdMapper` 构造里 `defaultReturnValue(-1)`），
     * 而已登记对象返回的是它当前的 id（正常 id 从 0 起，不会是 -1），所以这个判断是幂等的，
     * 不会像 GTM 那次循环一样把同一批状态 add 两遍。
     *
     * ⚠️ 入参顺序 = 数字 id 的分配顺序 = 网络协议的一部分。客户端与服务端都会跑本方法，
     * 顺序一致才谈得上正确；以后新增晚注册的机器注册入口（例如 `ALLSmahine` 里将来加单方块机器）
     * 也必须按同样的思路补一次，别把这台机器漏在外面。
     */
    internal fun backfillRenderStates(definitions: List<MachineDefinition>) {
        val renderStates = MachineDefinition.RENDER_STATE_REGISTRY
        for (definition in definitions) {
            for (renderState in definition.stateDefinition.possibleStates) {
                if (renderStates.getId(renderState) == -1) {
                    renderStates.add(renderState)
                }
            }
        }
    }
}
