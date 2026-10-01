package rain.gtetcore.gtet.common.machine.multiblock.part.ae
import appeng.api.config.Actionable
import appeng.api.networking.IGrid
import appeng.api.stacks.AEItemKey
import appeng.api.stacks.GenericStack
import appeng.api.storage.MEStorage
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.IDropSaveMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputBusPartMachine
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.config.GTETConfig
import rain.gtetcore.gtet.integration.ae2.ETTagFilter
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator
import rain.gtetcore.gtet.integration.ae2.IMEStockingHost
import java.util.function.Supplier

/**
 * 「ME 标签库存输入总线」：GTM 的 `me_stocking_input_bus`（ME 库存输入总线）+ 两条 GTET 自己的策略。
 *
 * ## 两个模式
 *
 * 1. **标签模式**：白名单 / 黑名单两条标签表达式，只有「命中白名单且不命中黑名单」的 item key 才允许被拉进来。
 * 两条都留空 = 不过滤（默认）；判定语义与书写规则见 [ETTagFilter]。
 * 2. **定量模式**：`batchSize` = 每次从 AE 网络往本仓备多少（0 = 不限制，默认）。
 * 语义是**一批一批地备**，不是「保底补到 N」——保底那件事 GTM 自己已经有了
 * （`min_item_count`，面板见 GTM 的 `AutoStockingFancyConfigurator`）。
 *
 * 两者**互不干涉、可叠加**：标签决定「哪些 key 允许进来」，定量决定「一次备多少」。
 * ⚠️ 但定量与保底会打架：若本仓的「保底数量」比 batchSize 还大，备出来的量永远达不到保底，本仓会一直空着。
 *
 * ## 为什么走这条路（而不是覆写 GTM 的某个判定方法）
 *
 * GTOCore（LGPL-3.0）的 `METagFilterStockBusPartMachine` 是覆写
 * `boolean test(AEKey what)` 实现的，但那个方法**只存在于它自己复制的那份
 * `com.gtocore.common.machine.multiblock.part.ae.MEStockingBusPartMachine` 里**
 * （它把 GTM 的这个类整份搬进了自己的包，并把 GTM 的 `setAutoPullTest` 覆写成空方法）。
 * 官方 GTM 7.5.3 的 `com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine`
 * **没有** `test(AEKey)`：全量源码与 `javap -p` 的方法表都对不上，
 * 所以 mixin 注入 / Accessor 都无从下手（目标方法不存在，注入会硬失败）。
 *
 * 7.5.3 上能用的公开扩展点是 `setAutoPullTest(Predicate<GenericStack>)`，
 * 但它只覆盖 autoPull 模式「选哪些物品填进配置槽」这一步，**管不到取数**。
 * 所以 GTET 三条路一起用，覆盖「配置 → 备货 → 真取数」全链：
 *
 * **把关点**
 *
 * | 环节 | 方法 | 标签 | 定量 |
 * | --- | --- | --- | --- |
 * | autoPull 选配置 | `refreshList()`（GTM） | [installAutoPullTest] | — |
 * | 每个周期备货 | [syncME]（本类覆写） | ✔ | ✔（stock ≤ N） |
 * | 真正取数 | [ETTagFilterStockItemSlot.extractItem] | ✔（兜底） | ✔（min(amount, 持有量, N)） |
 *
 * ⚠️ `stock` 全 GTM 只有两处会写：GTM 的 `refreshList()` 与本类的 `syncME()`，
 * 而 `autoIO()` 里的顺序是「先 refreshList 再 syncME」，所以 autoPull 模式下定量上限**最终仍然生效**；
 * 取数点再夹一次是为了不依赖这个时序（玩家刚改完 N 的那一帧也不会超发）。
 *
 * ## ⚠️ 与 GTM 基类的耦合
 *
 * [syncME] 是 GTM 那段逻辑的等价重写（只多了标签判定与定量上限）。GTM 升级后若改了这个方法，
 * 这里必须同步跟改 —— 注释里标了与原版的逐行对应关系。
 *
 * @author rain fox
 */
open class ETTagFilterStockBusPartMachine(holder: IMachineBlockEntity, vararg args: Any?) :
    MEStockingBusPartMachine(holder, *args), IMEStockingHost, IDropSaveMachine {

    /** 标签判定器：构造一次、按表达式变化与 key 缓存，绝不在每次判定时重新解析字符串。 */
    private val tagFilter = ETTagFilter()

    @field:Persisted
    private var tagWhite = ""

    @field:Persisted
    private var tagBlack = ""

    /** 每次从网络备货的上限；0 = 不限制（默认）。 */
    @field:Persisted
    private var batchSize = 0

    /**
     * 「允许多方块共享」开关，**默认 false = 隔离**（与上手写死的 `canShared() = false` 行为一致）。
     *
     * ⚠️ 默认值不能改成 true：本件的标签 / 定量 / 库存列表都是每件独立的，一旦被两个多方块共享，
     * 两个控制器会读同一份 `stock` 与同一套标签闸门 —— 这就是「串配方」，
     * 也正是当初写死 `false` 的原因（见 [canShared]）。
     *
     * ⚠️ 带 `@DescSynced`（而不是只有 `@Persisted`）：开关面板在客户端要读这个值来画
     * 按下状态与状态文字，只写 `@Persisted` 的话客户端拿到的是默认值。
     * GTM 自己的 `MEStockingBusPartMachine#autoPull` 就是 `@DescSynced @Persisted` 两件套。
     *
     * ⚠️ 本字段只是「玩家那一侧的意愿」：[canShared] 取的是它与全局配置
     * `multiblock.partsShareable` 的**或**，配置打开时本字段拨不动结果。
     */
    @field:DescSynced
    @field:Persisted
    private var shareEnabled = false

    init {
        applyTagFilter()
    }

    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    // ////////////////////////////////
    // ***** 仓室隔离（IMultiPart）****//
    // ////////////////////////////////

    /**
     * **仓室隔离（玩家可切换）**：默认禁止本件被两个多方块同时占用（防串配方），
     * 面板上的「多方块共享」开关打开后放行。返回值是「[shareEnabled] OR 全局配置
     * `multiblock.partsShareable`」（后者默认 false，故默认行为与开关单独决定时完全一致）。
     *
     * ## ⚠️ 时序：这个值只在「结构检查那一刻」被读
     *
     * 全 GTM 唯一的消费点是 `BlockPattern#checkPatternAt`（下面详述），也就是说拨动开关
     * **不会**让已经成型的结构凭空变化。两个方向的实际差别：
     *
     * **开关两个方向**
     *
     * | 方向 | 立刻发生什么 | 玩家要做什么 |
     * | --- | --- | --- |
     * | 隔离 → 允许共享 | 本件所属结构复检仍然通过（`hasController` 那一项放行自己）；别家结构当时并没有在检查，所以什么都没发生 | 想让另一个多方块占用本件，必须让**那个**结构重新成型（重新检查一次结构） |
     * | 允许共享 → 隔离 | 本件所属的**每个**控制器都会复检（见 [setCanBeShared]），共享的那一方该格判失败 → 它当次就散架 | 不用做什么；被挤掉的结构的控制器界面会显示 `multiblocked.pattern.error.share` |
     *
     * 这两段话同时写在面板开关的 tooltip 里（中英各一份），不靠玩家猜。
     *
     * ## 这道闸门在运行时到底卡住了什么
     *
     * `IMultiPart#canShared()` 的默认实现返回 `true`（GTM 源码：接口里就一句
     * `default boolean canShared() { return true; }`），而全 GTM 只有**一个**消费点：
     * `BlockPattern#checkPatternAt` 逐格匹配时，若该格上的方块是 `IMultiPart`：
     *
     * ```
     * if (part.isFormed() && !part.canShared() && !part.hasController(worldState.controllerPos)) {
     *     canPartShared = false;
     *     worldState.setError(new PatternStringError("multiblocked.pattern.error.share"));
     * }
     * ...
     * if (!predicate.test(worldState) || !canPartShared) { ... 该格匹配失败 ... }
     * ```
     *
     * 于是：
     *
     * - `isFormed()` 在 `MultiblockPartMachine` 里就是 `!controllerPositions.isEmpty()`
     * —— 也就是「本件已经属于某个**已成型**的多方块」；
     * - 此时若本件不允许共享、且当前正在检查的这个控制器**不是**它已有的控制器，本格直接判失败
     * → **第二个多方块结构成不了型**（控制器界面在该格显示
     * `multiblocked.pattern.error.share`，⚠️ 该键 GTM 与 LDLib 的 lang 里都没有，玩家看到的是原文键名）；
     * - `hasController(controllerPos)` 那一项保证**同一个控制器重新检查自己的结构**（成型后周期性复检）
     * 不会被自己挡住；
     * - `predicate.isAny()` 那层守卫只对「任意方块」的通配位放行，本族部件所在的位置都是
     * `autoAbilities(...)` 里的具体 `blocks(...)` 候选，不是通配，所以照卡。
     *
     * ## 为什么默认必须隔离
     *
     * 「标签 / 定量 / 库存列表」全是**每件独立**的配置（[tagWhite]、[tagBlack]、
     * `batchSize`、`stock`）。被两个多方块共享时，两个控制器会读同一份 `stock` 与同一套标签闸门，
     * 配方匹配会互相看见对方的料 —— 这就是「串配方」。
     * 同族先例：本项目自己的 `ThreadHatchPartMachine` / `OverclockHatchPartMachine` /
     * `ETParallelHatchPartMachine` 都写了这一条，GTM 自己的 `ParallelHatchPartMachine` /
     * `TankValvePartMachine` / `MaintenanceHatchPartMachine`（接口默认方法里覆写）同样如此。
     * 所以开关默认关（= 隔离），只有玩家明确知道代价时才打开。
     *
     * ⚠️ 这**不是**「一个结构里只能放一件」：它只挡「同一格方块同时属于两个已成型结构」，
     * 一个结构里放两件各自独立的库存总线照旧允许（仓库去重由 `distinct` 与
     * [testConfiguredInOtherPart] 管）。
     *
     * **⚠️ 全局配置兜底：** 返回值现在是「面板开关 OR `multiblock.partsShareable`」
     * —— 默认 `false` 时与原来只读 [shareEnabled] **完全一致**（用 OR 而非 AND 正是为此），
     * 而配置一旦打开就**无条件放行**、面板开关失去作用，上面那条「两个控制器读同一份 stock 与同一套标签闸门」
     * 的**串配方风险随之恢复**（多人服慎开）。
     * ⚠️ 该值只在 `BlockPattern#checkPatternAt` 那一刻被读，所以拨开关 / 改配置都不会立刻复检。
     */
    override fun canShared(): Boolean {
        return shareEnabled || GTETConfig.partsShareable()
    }

    /**
     * 面板开关的显示状态（[IMEStockingHost.canBeShared]）：与 [canShared] 同源，
     * 同样带上配置兜底 —— 配置打开时面板显示「允许共享」，如实反映「此刻确实允许」，
     * 此时拨开关不会再改变结果（[setCanBeShared] 只写 [shareEnabled]）。
     */
    override fun canBeShared(): Boolean {
        return shareEnabled || GTETConfig.partsShareable()
    }

    /**
     * 拨动共享开关，并让本件所属的每个多方块**立刻复检一次结构**。
     *
     * ⚠️ 必须主动复检：`canShared()` 只在 `BlockPattern#checkPatternAt` 那一刻被读，
     * 光改字段的话玩家要等到下一次结构复检（周期检查 / 方块变化）才看得到效果。复检用的是 GTM
     * 自己的入口 `IMultiController#requestCheck()` —— 它不是接口里那句简单的默认实现，
     * `MultiblockControllerMachine` 把它覆写成「拿锁 → `checkPatternWithLock()` →
     * 通过就 `onStructureFormed()`，不通过就 `onStructureInvalid()`」，
     * 内部自带 `!checking && isFormed && ServerLevel` 三道守卫，
     * GTM 自己在 `setFrontFacing` / `setUpwardsFacing` / `MultiblockState` 里都这么调。
     *
     * ⚠️ **先复制再遍历**：`requestCheck()` 不通过时会走 `onStructureInvalid()` →
     * 每个部件的 `removedFromController()`，而 `MultiblockPartMachine#removedFromController`
     * 会直接 `controllers.remove(controller)`；`getControllers()` 返回的是那个集合的
     * **不可修改视图**（不是快照），边遍历边删会抛 `ConcurrentModificationException`。
     *
     * ⚠️ 只由服务端改：`shareEnabled` 是 `@Persisted` 字段（服务端权威），
     * 客户端自己赋值会在下一次同步时被覆盖，看起来像「点了没用」（与定量框同理）。
     */
    override fun setCanBeShared(shared: Boolean) {
        if (isRemote) return
        shareEnabled = shared
        for (controller in java.util.List.copyOf(controllers)) {
            controller.requestCheck()
        }
    }

    // ///////////////////////////////
    // ****** 标签过滤（接口实现）*****//
    // ///////////////////////////////

    override fun getTagFilter(): ETTagFilter {
        // ⚠️ 每次取用都先用当前那两条原始表达式校准一次，而不是只在 setter 里解析。
        //    原因：@Persisted 字段是 LDLib 用反射**直接写进字段**的（读档、拆方块放回去、以后有人直接赋值），
        //    一个 setter 都不会经过 —— 只在 setter 里解析的话，存档读回之后判定器里还是空表达式，
        //    过滤会**静默失效**（看着有配置、实际不过滤）。
        //    ETTagFilter#set 在两条字符串都没变时第一步就 return，所以这么做没有重复解析的代价。
        tagFilter.set(tagWhite, tagBlack)
        return tagFilter
    }

    override fun getTagWhite(): String {
        return tagWhite
    }

    override fun setTagWhite(expression: String?) {
        tagWhite = expression ?: ""
        applyTagFilter()
    }

    override fun getTagBlack(): String {
        return tagBlack
    }

    override fun setTagBlack(expression: String?) {
        tagBlack = expression ?: ""
        applyTagFilter()
    }

    // ///////////////////////////////
    // ******* 定量模式 *************//
    // ///////////////////////////////

    override fun getBatchSize(): Int {
        return batchSize
    }

    override fun setBatchSize(size: Int) {
        batchSize = Mth.clamp(size, BATCH_MIN, BATCH_MAX)
    }

    // ///////////////////////////////
    // ***** Machine LifeCycle ****//
    // ///////////////////////////////

    /**
     * 换掉 GTM 的库存列表，好让每个槽都由 [ETTagFilterStockItemSlot] 承担取数（见类注释「为什么走这条路」）。
     *
     * ⚠️ 这个方法是**在 `super(...)` 构造期间**被调用的，那时本类的字段还没初始化 ——
     * 所以槽只持有 `this` 这个视图，绝不在构造期读 `batchSize` / `tagFilter`。
     */
    override fun createInventory(vararg args: Any?): NotifiableItemStackHandler {
        this.aeItemHandler = ETTagFilterStockItemList(this, MEInputBusPartMachine.CONFIG_SIZE)
        return this.aeItemHandler
    }

    override fun addedToController(controller: IMultiController) {
        super.addedToController(controller)
        installAutoPullTest()
    }

    /**
     * 装回自动拉取谓词。
     *
     * ⚠️ 必须在 `super.addedToController` **之后**调：GTM 的
     * `IMEStockingPart#addedToController` 会把 `autoPullTest` 覆盖成「不同仓去重」检查，
     * 构造函数里设的会被它抹掉。这里把「标签放行」与「去重」两条语义组合回去。
     */
    private fun installAutoPullTest() {
        setAutoPullTest(tagAutoPullTest(this::testConfiguredInOtherPart))
    }

    // ///////////////////////////////
    // ********** Sync ME *********//
    // ///////////////////////////////

    /**
     * 库存刷新：把「网络上有多少」按标签闸门与定量上限折算成「本仓备多少」，写进 `stock`。
     *
     * ⚠️ 与 GTM 的 `MEStockingBusPartMachine#syncME()` 逐行等价，只多了三处：
     * ① 网络取不到时直接返回（GTM 原版这里没判空，只在在线时被调）；② 标签闸门；
     * ③ `want = min(available, batchLimit())` —— 这就是「每次只备 N 个」。
     *
     * 这一处的标签闸门不是装饰：`stock` 就是多方块配方匹配看到的「本仓有多少」，
     * 若把一个不放行的 key 留在 stock 里，配方会以为有料、开起来才发现取不到，于是卡住。
     */
    override fun syncME() {
        val grid: IGrid = getMainNode().grid ?: return
        val networkInv: MEStorage = grid.storageService.inventory
        val batch = batchLimit()

        for (slot in this.aeItemHandler.getInventory()) {
            val config = slot.getConfig()
            if (config != null && testTag(config.what())) {
                val key = config.what()
                val available = networkInv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource)
                val want = available.coerceAtMost(batch)
                if (want >= minStackSize) {
                    slot.setStock(GenericStack(key, want))
                    continue
                }
            }
            slot.setStock(null)
        }
    }

    // ///////////////////////////////
    // ********** GUI ***********//
    // ///////////////////////////////

    override fun attachConfigurators(configuratorPanel: ConfiguratorPanel) {
        super.attachConfigurators(configuratorPanel)
        configuratorPanel.attachConfigurators(ETTagFilterConfigurator(this, false))
    }

    /**
     * 主页面：GTM 那一页（`LabelWidget` + `AEItemConfigWidget`）**原样**装在下面，只是把页高从 84 抬到 100。
     *
     * ## ⚠️ 为什么要抬这 16px：左侧那 26px 的竖槽被两列控件共用，本件正好顶到阈值
     *
     * fancy 界面的左边距里挤着**两列**控件（都在 `FancyMachineUIWidget` 的负 x 上）：
     *
     * - **侧栏页签列**（`VerticalTabsWidget`）：固定 (-20, 0)、宽 24 → x ∈ [-20, 4]，
     * 图标**自顶向下**排，第 0 个占 y ∈ [8, 32]；
     * - **配置器列**（`ConfiguratorPanel`）：固定 x = -(24+2) = -26、宽 24 → x ∈ [-26, -2]，
     * 高度 = `26 * 配置器个数 - 2`，位置由 GTM 钉在
     * `y = 界面高 - 列高 - 4`（**自底向上**长）。
     *
     * 两列在 x ∈ [-20, -2] 上重叠 18px，且配置器列画在后面（子控件顺序：pageContainer → 物品栏 →
     * 标题栏 → 侧栏页签 → tooltip 面板 → 配置器面板），所以**配置器列会盖住侧栏页签**。
     *
     * 本件的界面高 H = 页容器高 + 物品栏高 86，页容器高 = max(86, 页高 + 8)。GTM 原来的页高是 84
     * （`AEItemConfigWidget` 是 144×74、摆在 y=10 → 动态尺寸组算出 3+144 = 147 宽、10+74 = 84 高），
     * 于是 H = 92 + 86 = 178，配置器列顶边 = 178 - (26n - 2) - 4 = 176 - 26n：
     *
     * - GTM 自己的 `me_stocking_input_bus` 有 5 个配置器（电源 / 去重 / 电路 / 自动拉取 / 库存保底）
     * → 顶边 = 46，比侧栏页签底边 32 还低 14px，**安全**；
     * - 本件多挂了一块「标签过滤」= 第 6 个 → 列高 154 → 顶边 = **20**，
     * 于是它**盖住侧栏第 0 个页签的下半 12px**（24px 的按钮里正好一半）——
     * 这就是玩家看到的「最上面那个侧栏图标只露出一半」。
     *
     * 要让 6 个配置器不越过页签，需要配置器列顶边 ≥ 32：
     * `H - 158 ≥ 32 → H ≥ 190 → 页容器高 ≥ 104 → 页高 ≥ 96`。这里取 **100**（留 4px 余量：
     * 顶边 = 194 - 158 = 36，页签按钮底边 32、图标像素底边 28，都不碰）。
     *
     * ## 代价与为什么这么修
     *
     * 代价是本件主页面底部多出 16px 空白（GTM 的配置控件本身底部就留了 18px 不画东西的行位，
     * 所以观感上就是「槽区下面空一点」）。之所以不去动别的：侧栏页签列与配置器列的位置都是
     * `FancyMachineUIWidget` 里写死的全局布局，动它们会波及**所有**机器的界面；
     * 而把它做成「本件自己的页高」是本件唯一可控、且不影响他人的那一维。
     * 本件所属的二合一件覆盖了自己的 `createUIWidget()`（150×168 的固定页，H = 262，
     * 7 个配置器的顶边 = 78，本来就安全），所以这条修正只作用于单件物品总线。
     *
     * ⚠️ GTM 的页是**动态尺寸**组（`new WidgetGroup(new Position(0,0))`），所以这里不能改它的尺寸，
     * 只能在外面套一个固定尺寸组当外框 —— 直接 `setSize` 会在下一次 `onChildSizeUpdate` 时被重算掉。
     */
    override fun createUIWidget(): Widget {
        val page = WidgetGroup(0, 0, PANEL_WIDTH, PANEL_HEIGHT)
        page.addWidget(super.createUIWidget())
        return page
    }

    // ////////////////////////////////
    // ****** Configuration ******//
    // ////////////////////////////////

    /** 数据棒：在 GTM 原有的配置之外，把标签、定量与共享开关一起带走。 */
    override fun writeConfigToTag(): CompoundTag {
        val tag = super.writeConfigToTag()
        writeTagFilter(tag)
        tag.putInt(NBT_BATCH_SIZE, batchSize)
        tag.putBoolean(NBT_SHARE, shareEnabled)
        return tag
    }

    override fun readConfigFromTag(tag: CompoundTag) {
        super.readConfigFromTag(tag)
        readTagFilter(tag)
        if (tag.contains(NBT_BATCH_SIZE)) setBatchSize(tag.getInt(NBT_BATCH_SIZE))
        if (tag.contains(NBT_SHARE)) setCanBeShared(tag.getBoolean(NBT_SHARE))
    }

    /** 拆方块：标签、定量与共享开关要能存进掉落物，换位置装回去不丢。 */
    override fun saveToItem(tag: CompoundTag) {
        super<IDropSaveMachine>.saveToItem(tag)
        writeTagFilter(tag)
        tag.putInt(NBT_BATCH_SIZE, batchSize)
        tag.putBoolean(NBT_SHARE, shareEnabled)
    }

    override fun loadFromItem(tag: CompoundTag) {
        super<IDropSaveMachine>.loadFromItem(tag)
        readTagFilter(tag)
        if (tag.contains(NBT_BATCH_SIZE)) setBatchSize(tag.getInt(NBT_BATCH_SIZE))
        if (tag.contains(NBT_SHARE)) setCanBeShared(tag.getBoolean(NBT_SHARE))
    }

    // ///////////////////////////////
    // ******* 库存实现 *************//
    // ///////////////////////////////

    /**
     * GTET 自己的 AE 库存列表：只做一件 GTM 的 private 内部列表做不了的事 —— 让每个槽都是
     * [ETTagFilterStockItemSlot]（GTM 的库存槽是 `MEStockingBusPartMachine` 的 private 内部类，跨包继承不到）。
     *
     * ⚠️ 槽实例是在这里由构造器通过 slot 工厂造出来的；存档读回时 LDLib 会**复用已有元素**而不是按字段声明类型
     * 重建数组（`ArrayAccessor.writeManagedField` 在子访问器非 managed 时走
     * `Array.get(array, i)` 再写元素，长度不等则直接抛异常），所以子类行为在重新读档后仍然在。
     */
    class ETTagFilterStockItemList(private val host: IMEStockingHost, slots: Int) :
        ExportOnlyAEItemList(host as MetaMachine, slots, Supplier { ETTagFilterStockItemSlot(host) }) {

        /** 让 GTM 的配置面板把本列表当作「库存列表」画（否则配置槽会被画成可编辑的普通槽）。 */
        override fun isStocking(): Boolean {
            return true
        }

        override fun isAutoPull(): Boolean {
            return host.isAutoPull()
        }

        /**
         * GTM 自己那份库存列表在这里做的是
         * `super.hasStackInConfig(stack, false) || (checkExternal && testConfiguredInOtherPart(stack))`。
         * 我们把库存列表换成了自己的，就必须把同一条语义搬过来 —— 否则面板
         * （`AEItemConfigWidget#hasStackInConfig` → `AEConfigSlotWidget`）里
         * 「这个物品已经配置在本多方块的另一个库存总成上了」的判断会静默失效。
         *
         * ⚠️ 不能写 `IConfigurableSlotList.super.hasStackInConfig(...)`：那个接口在**父类**上，
         * 不在本类的直接 superinterface 列表里，Java 不允许这么限定。所以这里把它的循环照抄一遍。
         */
        override fun hasStackInConfig(stack: GenericStack?, checkExternal: Boolean): Boolean {
            if (stack != null && stack.amount() > 0) {
                for (i in 0 until getConfigurableSlots()) {
                    val config = getConfigurableSlot(i).getConfig()
                    if (config != null && config.what() == stack.what()) return true
                }
            }
            return checkExternal && host.testConfiguredInOtherPart(stack)
        }
    }

    /**
     * GTET 自己的 AE 库存槽：把 GTM `MEStockingBusPartMachine.ExportOnlyAEStockingItemSlot#extractItem`
     * 那段 AE 抽取逻辑重写一遍（它在 private 内部类里，跨包拿不到），并在里面加上两道 GTET 自己的闸门。
     */
    class ETTagFilterStockItemSlot : ExportOnlyAEItemSlot {

        private val host: IMEStockingHost

        constructor(host: IMEStockingHost) {
            this.host = host
        }

        constructor(host: IMEStockingHost, config: GenericStack?, stock: GenericStack?) : super(config, stock) {
            this.host = host
        }

        /**
         * 从 AE 网络真正取数（GTM 原逻辑）+ 两道闸门。
         *
         * ⚠️ 闸门对**模拟与真实两条路一视同仁**，这是刻意的：GT 的配方匹配用
         * `handleRecipe(..., simulate=true)` 走的就是 `simulate=true` 这条路，
         * 只卡真实抽取的话配方会「看着有料 → 开起来 → 实际拿不到 → 卡住」。
         */
        override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
            val stock = this.stock
            val config = this.config
            if (slot != 0 || stock == null || config == null) return ItemStack.EMPTY

            val key = config.what()
            // 闸门一：标签。stock 万一被别处的写法填上，这里也不放行。
            if (!host.getTagFilter().test(key)) return ItemStack.EMPTY
            // 闸门二：取数上限 = min(调用方要的量, 本仓当前持有量, 每批 N)。
            // ⚠️ 「不超过持有量」这一条是必需的：GTM 原版这里不看 stock，
            //    因为它的 stock 是全网存量（永远够），而定量模式下 stock 会被压到 N，
            //    不夹的话 stock 会被减成负数。
            val limit = Math.min(Math.min(amount.toLong(), stock.amount()), host.batchLimit())
            if (limit <= 0 || !host.isOnline()) return ItemStack.EMPTY

            val grid: IGrid? = host.getMainNode().getGrid()
            if (grid == null) return ItemStack.EMPTY
            val aeNetwork: MEStorage = grid.getStorageService().getInventory()

            val action = if (simulate) Actionable.SIMULATE else Actionable.MODULATE
            val extracted = aeNetwork.extract(key, limit, action, host.getActionSource())
            if (extracted <= 0) return ItemStack.EMPTY

            val resultStack: ItemStack = if (key is AEItemKey) key.toStack(extracted.toInt()) else ItemStack.EMPTY
            if (!simulate) {
                // may as well update the display here
                val newStock = ExportOnlyAESlot.copy(stock, stock.amount() - extracted)
                this.stock = newStock
                if (newStock.amount() <= 0) this.stock = null
                if (this.onContentsChanged != null) this.onContentsChanged.run()
            }
            return resultStack
        }

        override fun copy(): ETTagFilterStockItemSlot = ETTagFilterStockItemSlot(
            host,
            if (this.config == null) null else ExportOnlyAESlot.copy(this.config),
            if (this.stock == null) null else ExportOnlyAESlot.copy(this.stock)
        )
    }

    companion object {

        /**
         * 挂在 GTM 的库存总线字段持有者后面，保证两条链上的字段都串得起来。
         *
         * ⚠️ 必须在 `super(...)` 之前可读：LDLib 是在构造期拿 `getFieldHolder()` 去扫字段的。
         */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder = ManagedFieldHolder(
            ETTagFilterStockBusPartMachine::class.java,
            MEStockingBusPartMachine.MANAGED_FIELD_HOLDER
        )

        /** 「每次拉 N 个」的可配范围。上限取 1_000_000：物品侧远超任何实际缓冲需求，又让输入框能一眼看全。 */
        const val BATCH_MIN: Int = 0
        const val BATCH_MAX: Int = 1_000_000

        /** 主页面宽：继承 GTM 动态组的宽度（3 + 144），显式写出来是为了让外框与内容一致。 */
        private const val PANEL_WIDTH: Int = 147

        /**
         * 主页面高：GTM 的 84 + 16 = 100。
         *
         * ⚠️ 别改小：96 是「第 6 个配置器不压侧栏页签」的下限（推导见 [createUIWidget]），
         * 再小就回到那个 12px 的重叠；改大只会让底部空白更多（界面按内容反推，窗口会一起变高）。
         */
        private const val PANEL_HEIGHT: Int = 100

        /** 定量上限在数据棒 / 拆方块里的键名。 */
        private const val NBT_BATCH_SIZE: String = "ETBatchSize"

        /**
         * 共享开关在数据棒 / 拆方块里的键名。
         *
         * ⚠️ 与定量 / 标签不同，这里**必须**显式写这一对键（而不是只靠 `@Persisted`）：
         * `@Persisted` 只管方块实体的存档，拆下来的掉落物走的是
         * `saveToItem` / `loadFromItem` 这条另一条路（GTM 自己的
         * `minStackSize` / `ticksPerCycle` 就是 `@DropSaved` + 这两个方法）。
         * 缺键时不动（老存档 / 老数据棒），默认值 false = 隔离，与旧行为一致。
         */
        private const val NBT_SHARE: String = "ETShareEnabled"
    }
}