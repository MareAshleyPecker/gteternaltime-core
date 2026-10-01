package rain.gtetcore.gtet.common.machine.multiblock.part.ae
import appeng.api.config.Actionable
import appeng.api.networking.IGrid
import appeng.api.networking.IManagedGridNode
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.AEKey
import appeng.api.stacks.GenericStack
import appeng.api.storage.MEStorage
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AEFluidConfigWidget
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AEItemConfigWidget
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputBusPartMachine
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import it.unimi.dsi.fastutil.objects.Object2LongMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import rain.gtetcore.gtet.common.machine.multiblock.part.ae.ETMEDualStockingPartMachine.Companion.FLUID_BLOCK_Y
import rain.gtetcore.gtet.common.machine.multiblock.part.ae.ETMEDualStockingPartMachine.Companion.LANG_TITLE_FLUIDS
import rain.gtetcore.gtet.integration.ae2.ETTagFilter
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator
import rain.gtetcore.gtet.integration.ae2.IMEStockingHost
import java.util.*
import java.util.function.Supplier

/**
 * 「ME 二合一库存输入总成」：**一个方块**同时挂 `IMPORT_ITEMS` 与 `IMPORT_FLUIDS`，
 * 物品与流体**都走库存拉取**（不是把两个现成部件拼在一起），并且两侧**各带一套**标签白/黑名单 + 定量拉取。
 *
 * ## 为什么这么组合（而不是照抄一份 500+ 行的二合一实现）
 *
 * 另一个同类「二合一仓」的实现（BetterGregTechAndAppliedEnergistics，**GPL-3.0**，
 * 本项目是 **LGPL-3.0**）是**一份 600 行上下的单类**：自己继承物品总线、自己重写
 * `refreshList` / `syncME` / 四份 `ExportOnlyAE*` 内部类 / 数据棒 / 拆方块。
 * 本项目**只借鉴那个形状**（"机器本身继承物品侧那一支 + 流体侧另起一个列表 + 一块挂两种能力"），
 * 代码自己写，并且把能省的都省掉：
 *
 * - **物品侧整支继承** [ETTagFilterStockBusPartMachine]（第一批已提交的「ME 标签库存输入总线」）——
 * 库存列表、库存槽、标签过滤、定量拉取、`syncME`、自动拉取、数据棒、拆方块、物品侧面板全部现成；
 * - **流体侧的库存槽不重写**：直接复用第一批的
 * [ETTagFilterStockHatchPartMachine.ETTagFilterStockFluidSlot]（它只依赖
 * [IMEStockingHost]，从不把宿主强转成机器，所以可以喂给它一个**只认流体配置的适配视图**）；
 * - 所以本类里**没有一行 AE 抽取逻辑**，只有「流体侧的列表 / 备货 / 自动配置 / 面板 / 存档」这些管道。
 *
 * 代价（已实测，不是估计）：新代码约 430 行，其中机器约 330 行；**既有的标签过滤两件与样板总成零改动**。
 * 若照抄那份 GPL 实现，除了许可证问题，还要多背 4 份 `ExportOnlyAE*` 内部类与两套面板同步逻辑。
 *
 * ## 两侧怎么共用一套「库存宿主机」
 *
 * [IMEStockingHost] 只描述**一侧**（一套标签 + 一个定量上限 + 几个 AE 句柄），
 * 而库存槽又是**跨包拿不到的 private 内部类**（只能靠第一批自己写的槽子类），所以两边这样接：
 *
 * - **物品侧**：宿主就是本机器自己 —— 本机器继承的那一支已经把 `getTagFilter()` /
 * `getBatchSize()` 实现成「物品侧那一套」（父类的 `tagWhite/tagBlack/batchSize`），
 * 于是父类的 [ETTagFilterStockBusPartMachine.ETTagFilterStockItemList] 直接拿来用；
 * - **流体侧**：宿主是 [FluidSideView] —— 一个把 AE 句柄转发给本机器、把标签/定量指向
 * 本机器**流体字段**的适配视图；机器本身仍然以 `MetaMachine` 的身份传给列表构造器
 * （列表要它来挂 trait）。
 *
 * ## 两侧各一套配置
 *
 * 物品侧：`tagWhite/tagBlack/batchSize`（父类字段，`@Persisted`）。
 * 流体侧：[fluidTagWhite] / [fluidTagBlack] / [fluidBatchSize]（本类字段，`@Persisted`）。
 * ⚠️ 两侧的字段都是 `@Persisted`，而 LDLib 是**反射直写字段**的（读档、拆方块放回去都不走 setter），
 * 所以判定器一律在 getter 里按当前字符串校准（见 [getFluidTagFilter] 与父类同名方法）。
 *
 * ## ⚠️ 与 GTM 基类耦合的六处（GTM 升级后要跟着看）
 *
 * 1. [createInventory] —— 在父类造完物品侧列表后**顺手造流体侧列表**（这是唯一能在构造期挂 trait 的位置）；
 * 2. [addedToController] —— 必须在 `super` **之后**重装自动拉取谓词，否则被
 * `IMEStockingPart` 的默认实现顶掉（父类已经中过一次这个坑）；
 * 3. [autoIO] —— GTM 的 `refreshList()` 是 private 且只填物品侧，流体侧的自动配置
 * **必须抢在 `super.autoIO()` 之前**跑，否则流体侧要晚一整个周期才备上货；
 * 4. [syncME] —— `super` 管物品侧，本类补流体侧；
 * 5. [testConfiguredInOtherPart] / [validateConfig] —— `IMEStockingPart` 只认**一个**
 * `getSlotList()`（物品侧），流体侧的去重与校验要自己补；
 * 6. [writeConfigToTag] / [readConfigFromTag] —— 数据棒那两个入口在 GTM 里是
 * `final`（`MEInputBusPartMachine#onDataStickUse`），改不了；但它们都走这两个 protected 方法，
 * 所以流体配置挂在这里就能随数据棒与拆方块一起走。
 *
 * ## 没做的事
 *
 * - 「保底数量」(`minStackSize`) 与「周期」(`ticksPerCycle`) **两侧共用一份**：
 * 它们由 GTM 自带的 `AutoStockingFancyConfigurator` 编辑，而那个面板只认一个
 * `IMEStockingPart`。要分两侧就得自己写一块面板（本轮不做，故不假装支持）。
 * - 物品侧那块标签面板复用父类挂上的那份（标题是共用的「标签过滤」、没标侧别），
 * 流体侧这块的标题带「流体侧」字样 —— 见 [LANG_TITLE_FLUIDS] 与
 * [SideConfigurator]。刻意**不**重写 `attachConfigurators` 的全套
 * （那样会把 GTM 的电路槽 / 去重开关 / 库存保底面板的挂载逻辑复制一遍，GTM 一改就漂）。
 *
 * @author rain fox
 */
open class ETMEDualStockingPartMachine(holder: IMachineBlockEntity, vararg args: Any?) :
    ETTagFilterStockBusPartMachine(holder, *args) {

    /**
     * 流体侧库存列表（16 个配置槽，与物品侧、与 GTM 的库存件一致）。
     *
     * ⚠️ **非 final、且在 [createInventory] 里赋值**：那个方法是在 `super(...)` 构造链里被调的，
     * 那时本类的字段初始化器还没跑；而 Java 的「隐式置空」发生在对象分配时、早于所有构造器，
     * 所以不带初始化器的字段在这里赋的值**不会被随后的字段初始化器覆盖**。
     * （GTM 的 `MEInputHatchPartMachine.aeFluidHandler` 与那份 GPL 二合一实现都是这个写法。）
     *
     * ⚠️ Kotlin 侧对应写法是 `lateinit`：`lateinit` **不生成任何构造期赋值**，
     * 与 Java「无初始化器」的语义逐位一致；写成 `var x: T? = null` 虽然本编译器会把那句
     * 冗余赋值优化掉，但那是编译器的优化、不是语言保证，不能拿来赌。
     */
    @field:Persisted
    protected lateinit var aeFluidHandler: ExportOnlyAEFluidList

    /** 流体侧宿主视图；同 [aeFluidHandler]，在构造链里赋值。 */
    private lateinit var fluidSide: FluidSideView

    /** 流体侧标签判定器：构造一次、按表达式变化与 key 缓存。 */
    private val fluidTagFilter = ETTagFilter()

    @field:Persisted
    private var fluidTagWhite = ""

    @field:Persisted
    private var fluidTagBlack = ""

    /** 流体侧「每次拉 N 个」的 N，单位 mB；0 = 不限制（默认，与物品侧一致）。 */
    @field:Persisted
    private var fluidBatchSize = 0

    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    /**
     * **仓室隔离（玩家可切换）**：直接用父类那一份实现（字段 `shareEnabled` + 拨动后的结构复检）。
     *
     * ⚠️ 这里**不再**像上一版那样写死 `false`：那样会让父类的开关对本件失效（覆写掉就再也回不去），
     * 而玩家的诉求正是「自己决定要不要共享」。显式再写一遍是为了把「本件只有一个开关」这件事钉在这里。
     *
     * ⚠️ **一块方块只有一个开关，不是物品侧 / 流体侧各一个**：`canShared()` 是机器级的一个方法
     * （`IMultiPart` 上就一个），没有任何「按侧分别判定」的时机 —— 结构检查问的是
     * 「这一格方块能不能被别的多方块占用」，答案只有一个。所以面板上物品侧那块（共用的「标签过滤」）
     * 显示开关，流体侧那块（「标签过滤（流体侧）」）不显示（见 [attachConfigurators]），
     * 免得玩家以为要拨两次。
     *
     * 本件比单侧件更需要隔离：一块方块同时挂在 `IMPORT_ITEMS` 与 `IMPORT_FLUIDS` 两条能力链上，
     * 两侧各有一套标签 / 定量 / 库存列表（物品侧在父类字段、流体侧在本类字段），
     * 一旦被两个多方块共享，两个控制器会同时读**同一份两侧配置**，串配方比单侧件更严重。
     * 完整语义与拨动开关两个方向的效果见父类 [ETTagFilterStockBusPartMachine.canShared]。
     *
     * **⚠️ 全局配置兜底不用在这里重复写一遍：** 父类的 `canShared()` / `canBeShared()`
     * 已经改成「`shareEnabled` OR `multiblock.partsShareable`」，本件照旧转发即可 ——
     * 转发链把配置一并带过来了。默认配置 `false` 时与今天**完全一致**；
     * 配置打开后本件（一块方块同时挂在两条能力链上）**串配方比单侧件更严重**，多人服慎开。
     * 该值只在 `BlockPattern#checkPatternAt` 那一刻被读，改配置不会立刻复检。
     */
    override fun canShared(): Boolean {
        return canBeShared()
    }

    // ///////////////////////////////
    // ***** Machine LifeCycle ****//
    // ///////////////////////////////

    /**
     * 造库存列表：父类造物品侧那份，这里**再顺手造流体侧那份**。
     *
     * ⚠️ 为什么在这里而不是构造器里：`MetaMachine` 的 trait 只能在机器创建期挂
     * （`MachineTrait` 的构造器自己调 `machine.attachTraits(this)`，而
     * `MetaMachine#attachTraits` 的注释写明「不可以在运行期动态加」），
     * 而这个方法是构造链里唯一一处、且父类已经在这里造过物品侧。
     *
     * ⚠️ trait 的**加入顺序**有后果：`MultiblockPartMachine#getHandlerList()` 把
     * **第一个** recipe-handler trait 的 IO 当作整张处理器表的 IO，物品侧（`IO.IN`）必须先加，
     * 流体侧后加 —— 顺序正好就是这里「先 super、后流体」。这也正是 GTM 自己
     * 物品总线（`inventory` 先于 `circuitInventory`）的排法。
     */
    override fun createInventory(vararg args: Any?): NotifiableItemStackHandler {
        val items = super.createInventory(*args)
        this.fluidSide = FluidSideView(this)
        // ⚠️ CONFIG_SIZE 取父类链上 MEInputBusPartMachine 的（protected，可继承）；
        //    MEHatchPartMachine.CONFIG_SIZE 是别的包里的 protected，跨包取不到，数值同为 16。
        this.aeFluidHandler = DualFluidList(this, MEInputBusPartMachine.CONFIG_SIZE, this.fluidSide)
        return items
    }

    override fun addedToController(controller: IMultiController) {
        super.addedToController(controller)
        // ⚠️ 必须在 super 之后：父类会把 autoPullTest 装成「物品侧标签 ∧ 去重」，
        //    这里换成「按 key 类型分派到两侧标签 ∧ 去重」，否则流体侧自动拉取会不看标签。
        setAutoPullTest(this::autoPullAllows)
    }

    /**
     * 自动拉取（screwdriver / 自动拉取按钮打开时）选配置槽的放行判定。
     *
     * `autoPullTest` 只有一个（GTM 的字段是 private 且只有 setter），所以**按 key 的类型分派**：
     * 物品 key 走物品侧标签，流体 key 走流体侧标签；两边的「别的库存件没配过」语义都保留。
     */
    private fun autoPullAllows(stack: GenericStack): Boolean {
        val what = stack.what()
        val tagged = if (what is AEFluidKey) fluidSide.testTag(what) else testTag(what)
        return tagged && !testConfiguredInOtherPart(stack)
    }

    override fun removedFromController(controller: IMultiController) {
        super.removedFromController(controller)
        // ⚠️ GTM 的 IMEStockingPart#removedFromController 只会清 getSlotList()（物品侧那份），流体侧要自己补
        if (isAutoPull && this::aeFluidHandler.isInitialized) aeFluidHandler.clearInventory(0)
    }

    /**
     * 配置去重校验。
     *
     * ⚠️ 必须整个覆写：`IMEStockingPart` 只声明了**一个** `getSlotList()`（= 物品侧），
     * 所以接口的默认实现管不到流体侧 —— 同一个多方块里另一件库存仓配过的流体会被留在配置里。
     *
     * ⚠️ 不能写 `IMEStockingPart.super.validateConfig()`：javac 只允许在「该接口是直接超接口
     * 且方法不是从超类继承来的」时用这种限定调用，而这里的接口是由**超类**实现的
     * （报错原文：the interface IMEStockingPart is extended by ETTagFilterStockBusPartMachine）。
     * 所以这里把接口默认实现那段循环搬过来，两侧各跑一遍。
     */
    override fun validateConfig() {
        clearDuplicateConfigs(slotList)
        clearDuplicateConfigs(aeFluidHandler)
    }

    /** 把「已经配置在同一多方块的另一个库存件上」的槽清掉（GTM 的 `IMEStockingPart#validateConfig` 循环体）。 */
    private fun clearDuplicateConfigs(list: IConfigurableSlotList) {
        for (i in 0 until list.configurableSlots) {
            val slot = list.getConfigurableSlot(i)
            val config = slot.config
            if (config != null && testConfiguredInOtherPart(config)) {
                slot.config = null
                slot.stock = null
            }
        }
    }

    /**
     * 「该配置是否已经在同一个多方块的另一个库存件上」。
     *
     * ⚠️ 物品侧交给 GTM 的物品版实现（它会跳过 distinct 模式、只看别件物品总成）；
     * 流体配置它**看不见**（那套只扫 `MEStockingBusPartMachine` 的物品列表），所以流体要自己扫一遍。
     * 扫描语义与 GTM 的 `MEStockingHatchPartMachine#testConfiguredInOtherPart` 一致
     * —— 注意流体那版与物品版是**不对称**的：流体版**不**判 distinct，这里照做，不擅自"修好"它。
     */
    override fun testConfiguredInOtherPart(config: GenericStack?): Boolean {
        if (config == null) return false
        if (config.what() is AEFluidKey) return testFluidConfiguredInOtherPart(config)
        return super.testConfiguredInOtherPart(config)
    }

    /** 流体侧的 [testConfiguredInOtherPart]：扫别的库存输入仓，以及别的二合一件的流体列表。 */
    private fun testFluidConfiguredInOtherPart(config: GenericStack): Boolean {
        if (!isFormed) return false
        for (controller in getControllers()) {
            for (part in controller.parts) {
                if (part === this) continue
                if (part is MEStockingHatchPartMachine) {
                    // getSlotList() 是 public 的（IMEStockingPart 声明），不必碰它的 private 字段
                    if (part.slotList.hasStackInConfig(config, false)) return true
                } else if (part is ETMEDualStockingPartMachine) {
                    if (part.aeFluidHandler.hasStackInConfig(config, false)) return true
                }
            }
        }
        return false
    }

    // ///////////////////////////////
    // ********** Sync ME *********//
    // ///////////////////////////////

    /**
     * 库存刷新：`super` 管物品侧（标签 + 定量，父类实现），本类补流体侧（同构的一份）。
     *
     * 调用点全在 GTM 那边（`MEStockingBusPartMachine#autoIO` 与
     * `MEInputBusPartMachine#autoIO`），本类只覆写不新增调用点，所以两侧天然同周期同步。
     */
    override fun syncME() {
        super.syncME()
        syncFluidME()
    }

    /**
     * 流体侧备货：与父类物品侧那份**逐行同构**（GTM 的 `MEStockingBusPartMachine#syncME`
     * 与 `MEStockingHatchPartMachine#syncME` 本来也是同构的），只多了判空、标签闸门与定量上限。
     *
     * 这一处的标签闸门不是装饰：`stock` 就是多方块配方匹配看到的「本仓有多少」，
     * 把一个不放行的 key 留在 stock 里，配方会以为有料、开起来才发现取不到，于是卡住。
     */
    private fun syncFluidME() {
        val grid: IGrid = mainNode.grid ?: return
        val networkInv: MEStorage = grid.storageService.inventory
        val batch = fluidSide.batchLimit()

        for (slot in aeFluidHandler.getInventory()) {
            val config = slot.getConfig()
            if (config != null && fluidSide.testTag(config.what())) {
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

    /**
     * 自动配置（autoPull 模式）时，先把**流体侧**的配置槽按「网络上存量最大的若干个」填好。
     *
     * ⚠️ 顺序是本方法存在的唯一理由：GTM 的 `refreshList()` 是 private 且只填**物品侧**，
     * 而 `super.autoIO()` 内部紧接着就会调 `syncME()`。若在 `super` **之后**才刷流体配置，
     * 这一次 `syncME` 看到的还是旧配置，流体侧要等下一个 `ticksPerCycle` 才备上货
     * （默认 40 tick —— 面板上会表现为"流体格空着两秒"）。
     */
    override fun autoIO() {
        if (isAutoPull && isWorkingEnabled && shouldSyncME()) {
            val cycle = ticksPerCycle
            // ⚠️ 与 GTM 的 autoIO 一样要挡 0：ticksPerCycle 可能是 0（老存档 / 直接赋值），
            //    GTM 那边是在自己的 autoIO 里补成 updateIntervals，这里不能抢在它前面除零。
            if (cycle != 0 && offsetTimer % cycle == 0L) refreshFluidList()
        }
        super.autoIO()
    }

    /**
     * 流体侧的自动配置列表刷新：与 GTM 的 `MEStockingHatchPartMachine#refreshList` 同一套算法
     * （取网络上存量最大的 `CONFIG_SIZE` 个流体，按存量从大到小倒着填进配置槽），
     * 只把「该不该收这个 key」换成两侧共用的 [autoPullAllows]。
     */
    private fun refreshFluidList() {
        val grid: IGrid? = mainNode.grid
        if (grid == null) {
            aeFluidHandler.clearInventory(0)
            return
        }

        val networkStorage: MEStorage = grid.storageService.inventory
        val counter = networkStorage.availableStacks

        // 小顶堆：留下存量最大的 CONFIG_SIZE 个
        val topFluids = PriorityQueue(
            Comparator.comparingLong(Object2LongMap.Entry<AEKey>::getLongValue)
        )

        for (entry in counter) {
            val amount = entry.longValue
            val what = entry.key

            if (amount <= 0) continue
            if (what !is AEFluidKey) continue

            val request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource)
            if (request == 0L) continue

            if (!autoPullAllows(GenericStack(what, amount))) continue
            if (amount >= minStackSize) {
                if (topFluids.size < CONFIG_SIZE) {
                    topFluids.offer(entry)
                } else if (amount > topFluids.peek().longValue) {
                    topFluids.poll()
                    topFluids.offer(entry)
                }
            }
        }

        // poll() 先出最小的，所以从后往前填，面板上就是「多的在上面」
        val fluidAmount = topFluids.size
        var index = 0
        while (index < CONFIG_SIZE) {
            if (topFluids.isEmpty()) break
            val entry = topFluids.poll()
            val what = entry.key

            val request = networkStorage.extract(what, entry.longValue, Actionable.SIMULATE, actionSource)

            val slot = aeFluidHandler.getInventory()[fluidAmount - index - 1]
            slot.setConfig(GenericStack(what, 1))
            slot.setStock(GenericStack(what, request))
            index++
        }

        aeFluidHandler.clearInventory(index)
    }

    // ///////////////////////////////
    // ********** GUI ***********//
    // ///////////////////////////////

    /**
     * 主页面：上半物品侧配置槽、下半流体侧配置槽（各 8×2，与 GTM 的 ME 部件布局一致）。
     *
     * ⚠️ 尺寸必须显式给：`FancyMachineUIWidget` 是拿 `page.getSize()` 反推整个界面大小的
     * （`size = (max(172, pageW + 8), max(86, pageH + 8))`），GTM 的 ME 部件给
     * `new WidgetGroup(new Position(0,0))`（动态尺寸组）也能用，只是因为它的最小尺寸 172×86
     * 刚好装得下一块 144×74 的配置面板。两块就装不下了。
     *
     * ⚠️ 本组是**固定尺寸**组（不是 `WidgetGroup(Position)` 那种按子控件撑开的动态组），
     * 所以 `PANEL_HEIGHT` 必须**正好等于最后一块配置面板的底边**（[FLUID_BLOCK_Y] + 74 = 168）：
     * 多留 = 主界面底部多一块空白（界面按内容反推，空白会整体变成窗口的一部分），
     * 少留 = 内容画出组外、越到物品栏分割线以下。物品栏分割线固定在「主页面底边 + 4px」（窗口布局里 page
     * 居中于 container，container 高 = page 高 + border*2），所以只要底边给准，内容变多时是**向上长**、
     * 底边不动。
     *
     * ⚠️ 两块之间的间距给 8（原来是 4）：4px 时物品侧那 8×2 槽区的下沿与流体侧槽区的上沿几乎贴在一起，
     * 看着像压在一起。间距**不要**大于 50 —— GTM 的 `ConfigWidget` 会在自己上方 50px 处摆一块
     * 80×30 的「数量」浮层（构造器里写死 `new AmountSetWidget(31, -50, this)`，占 `[顶边-50, 顶边-20]`），
     * 间距一旦小于 50，那块浮层就会落到上面一块面板的槽区里。
     * 好在库存列表 `isStocking() == true`（见本类两个列表的实现），GTM 侧左键点配置槽时
     * 那个浮层**不会**弹出来（`AEItemConfigSlotWidget#mouseClicked` 与流体版同名方法里
     * `enableAmountClient` 都被 `!parentWidget.isStocking()` 挡着，服务端 `enableAmount`
     * 只改服务端实例的 visible、不会同步给客户端），这里只是把边界写清楚。
     *
     * @see com.gregtechceu.gtceu.integration.ae2.gui.widget.ConfigWidget 每块配置面板 144×74
     */
    override fun createUIWidget(): Widget {
        val group = WidgetGroup(0, 0, PANEL_WIDTH, PANEL_HEIGHT)

        // ME 网络状态
        group.addWidget(
            LabelWidget(3, 0, Supplier {
                if (isOnline()) "gtceu.gui.me_network.online" else "gtceu.gui.me_network.offline"
            })
        )

        // 物品侧配置槽（父类那份列表）
        group.addWidget(AEItemConfigWidget(3, ITEM_BLOCK_Y, aeItemHandler))
        // 流体侧配置槽
        group.addWidget(AEFluidConfigWidget(3, FLUID_BLOCK_Y, aeFluidHandler))

        return group
    }

    override fun attachConfigurators(configuratorPanel: ConfiguratorPanel) {
        // 父类一行把该有的都挂上了：自动拉取按钮、去重开关、电路槽、GTM 的库存保底面板、物品侧的标签面板
        //（物品侧那块标签面板里带「多方块共享」开关 —— 全机就这一个，是本件唯一的共享开关）
        super.attachConfigurators(configuratorPanel)
        // 再补流体侧那块标签面板（标题带「流体侧」，用来和上面那块没标侧别的区分）
        configuratorPanel.attachConfigurators(SideConfigurator(fluidSide, true, LANG_TITLE_FLUIDS))
    }

    /**
     * 复用第一批那块「标签过滤 + 定量拉取」面板（[借鉴形状] GTOCore，LGPL-3.0），只换标题。
     *
     * ⚠️ 为什么不改 [ETTagFilterConfigurator] 的标题键：那个键是**三件部件共用**的
     * （两件单独的标签库存件 + 本件），改了会连带把它们的面板标题也改掉，
     * 而本件的目标只是「在同一个界面里把两侧分开」。
     */
    private class SideConfigurator(host: IMEStockingHost, fluid: Boolean, private val titleKey: String) :
        ETTagFilterConfigurator(host, fluid, false) {

        override fun getTitle(): Component {
            return Component.translatable(titleKey)
        }
    }

    // ////////////////////////////////
    // ****** Configuration ******//
    // ////////////////////////////////

    /**
     * 数据棒：GTM 的物品版在这里写「AutoPull / GhostCircuit / 各配置槽」，父类又补了物品侧标签与定量，
     * 本类再补流体侧。
     *
     * ⚠️ 数据棒的两个入口（`onDataStickShiftUse` / `onDataStickUse`）在 GTM 里是 **final**，
     * 改不了；但两个都走本方法 / [readConfigFromTag]，所以流体配置照样能随数据棒走。
     */
    override fun writeConfigToTag(): CompoundTag {
        val tag = super.writeConfigToTag()
        // autoPull 模式下流体配置是自动填的，跟 GTM 对物品侧的处理一样不存（存了也会被下次刷新覆盖）
        if (!isAutoPull) writeFluidSide(tag)
        return tag
    }

    override fun readConfigFromTag(tag: CompoundTag) {
        super.readConfigFromTag(tag)
        readFluidSide(tag)
    }

    /** 拆方块：流体侧配置要能存进掉落物，换位置装回去不丢。 */
    override fun saveToItem(tag: CompoundTag) {
        super.saveToItem(tag)
        writeFluidSide(tag)
    }

    override fun loadFromItem(tag: CompoundTag) {
        super.loadFromItem(tag)
        readFluidSide(tag)
    }

    /** 把流体侧的两条表达式、定量上限与全部配置槽写进给定 tag。 */
    private fun writeFluidSide(tag: CompoundTag) {
        tag.putString(NBT_FLUID_TAG_WHITE, fluidTagWhite)
        tag.putString(NBT_FLUID_TAG_BLACK, fluidTagBlack)
        tag.putInt(NBT_FLUID_BATCH_SIZE, fluidBatchSize)
        val configs = CompoundTag()
        tag.put(NBT_FLUID_CONFIGS, configs)
        for (i in 0 until aeFluidHandler.configurableSlots) {
            val config = aeFluidHandler.getConfigurableSlot(i).config
            if (config != null) configs.put(i.toString(), GenericStack.writeTag(config))
        }
    }

    /** 读回流体侧配置；缺键就不动（老存档 / 没存过的数据棒）。 */
    private fun readFluidSide(tag: CompoundTag) {
        if (tag.contains(NBT_FLUID_TAG_WHITE)) setFluidTagWhite(tag.getString(NBT_FLUID_TAG_WHITE))
        if (tag.contains(NBT_FLUID_TAG_BLACK)) setFluidTagBlack(tag.getString(NBT_FLUID_TAG_BLACK))
        if (tag.contains(NBT_FLUID_BATCH_SIZE)) setFluidBatchSize(tag.getInt(NBT_FLUID_BATCH_SIZE))
        if (tag.contains(NBT_FLUID_CONFIGS)) {
            val configs = tag.getCompound(NBT_FLUID_CONFIGS)
            for (i in 0 until aeFluidHandler.configurableSlots) {
                val key = i.toString()
                aeFluidHandler.getConfigurableSlot(i).config = if (configs.contains(key)) GenericStack.readTag(configs.getCompound(key)) else null
            }
        }
    }

    // ///////////////////////////////
    // ***** 流体侧宿主视图 *******//
    // ///////////////////////////////

    /**
     * 判定器取用：**每次取用都先按当前那两条原始表达式校准一次**。
     *
     * ⚠️ 理由与父类同名方法一样：`@Persisted` 字段是 LDLib 用反射**直接写进字段**的
     * （读档、拆方块放回去、以后有人直接赋值），一个 setter 都不会经过 —— 只在 setter 里解析的话，
     * 存档读回之后判定器里还是空表达式，过滤会**静默失效**（看着有配置、实际不过滤）。
     * `ETTagFilter#set` 在两条字符串都没变时第一步就 return，所以这么做没有重复解析的代价。
     */
    fun getFluidTagFilter(): ETTagFilter {
        fluidTagFilter.set(fluidTagWhite, fluidTagBlack)
        return fluidTagFilter
    }

    fun getFluidTagWhite(): String {
        return fluidTagWhite
    }

    fun setFluidTagWhite(expression: String?) {
        fluidTagWhite = expression ?: ""
    }

    fun getFluidTagBlack(): String {
        return fluidTagBlack
    }

    fun setFluidTagBlack(expression: String?) {
        fluidTagBlack = expression ?: ""
    }

    fun getFluidBatchSize(): Int {
        return fluidBatchSize
    }

    fun setFluidBatchSize(size: Int) {
        // 上下限沿用物品侧那两个常量（0 = 不限制），两侧一致才好解释
        fluidBatchSize = Mth.clamp(size, ETTagFilterStockBusPartMachine.BATCH_MIN, ETTagFilterStockBusPartMachine.BATCH_MAX)
    }

    /**
     * 流体侧的库存宿主视图：AE 句柄转发给机器本身，标签与定量指向机器的流体字段。
     *
     * ⚠️ 必须是 `static` 嵌套类并自己持有机器引用：它在**构造链里**就被创建
     * （见 [createInventory]），此时用非静态内部类捕获 `this` 虽然也能编译，
     * 但一旦构造器里碰到未初始化的字段就会拿到默认值 —— 拆成显式引用后，这里只保存引用、不读字段。
     */
    private class FluidSideView(private val machine: ETMEDualStockingPartMachine) : IMEStockingHost {

        override fun getTagFilter(): ETTagFilter {
            return machine.getFluidTagFilter()
        }

        override fun getTagWhite(): String {
            return machine.getFluidTagWhite()
        }

        override fun setTagWhite(expression: String?) {
            machine.setFluidTagWhite(expression)
        }

        override fun getTagBlack(): String {
            return machine.getFluidTagBlack()
        }

        override fun setTagBlack(expression: String?) {
            machine.setFluidTagBlack(expression)
        }

        override fun isOnline(): Boolean {
            return machine.isOnline()
        }

        override fun getMainNode(): IManagedGridNode {
            return machine.mainNode
        }

        override fun getActionSource(): IActionSource {
            return machine.getActionSource()
        }

        override fun isAutoPull(): Boolean {
            return machine.isAutoPull
        }

        override fun getBatchSize(): Int {
            return machine.getFluidBatchSize()
        }

        override fun setBatchSize(size: Int) {
            machine.setFluidBatchSize(size)
        }

        override fun testConfiguredInOtherPart(config: GenericStack?): Boolean {
            return machine.testConfiguredInOtherPart(config)
        }

        // ⚠️ 共享开关**两侧共用机器那一个**（canShared() 是机器级的方法），所以这里只是转发，
        //    不是「流体侧自己有一个开关」。流体侧那块面板不显示这个开关，见机器类的 attachConfigurators。
        override fun canBeShared(): Boolean {
            return machine.canBeShared()
        }

        override fun setCanBeShared(shared: Boolean) {
            machine.setCanBeShared(shared)
        }
    }

    // ///////////////////////////////
    // ******* 库存实现 *************//
    // ///////////////////////////////

    /**
     * 流体侧库存列表：只为把槽换成本 mod 自己的
     * [ETTagFilterStockHatchPartMachine.ETTagFilterStockFluidSlot]（配方/能力两条取数路都落到它）。
     *
     * ⚠️ 为什么不直接用第一批的 `ETTagFilterStockFluidList`：它的构造器要把宿主同时当
     * `MetaMachine`（拿去挂 trait）用，而流体侧的宿主是 [FluidSideView]（不是机器）。
     * 这里把两件事拆开 —— 机器当 `MetaMachine`、视图当 `IMEStockingHost`，
     * 于是槽本身**一行都不用重写**。
     */
    class DualFluidList(machine: MetaMachine, slots: Int, private val side: IMEStockingHost) :
        ExportOnlyAEFluidList(machine, slots, Supplier {
            ETTagFilterStockHatchPartMachine.ETTagFilterStockFluidSlot(side)
        }) {

        /** 让 GTM 的配置面板把本列表当作「库存列表」画（否则槽会被画成可编辑的普通槽）。 */
        override fun isStocking(): Boolean {
            return true
        }

        override fun isAutoPull(): Boolean {
            return side.isAutoPull()
        }

        /** 把 GTM 库存列表 `hasStackInConfig(stack, checkExternal)` 的外部检查语义搬过来。 */
        override fun hasStackInConfig(stack: GenericStack?, checkExternal: Boolean): Boolean {
            if (super.hasStackInConfig(stack, false)) return true
            return checkExternal && side.testConfiguredInOtherPart(stack)
        }
    }

    companion object {

        /**
         * 挂在父类（物品侧那一支）的字段持有者后面，把本类流体侧的 `@Persisted` 字段也串起来。
         */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder = ManagedFieldHolder(
            ETMEDualStockingPartMachine::class.java,
            ETTagFilterStockBusPartMachine.MANAGED_FIELD_HOLDER
        )

        /** 流体侧面板标题键（物品侧那块沿用共用键「标签过滤」，所以这里必须标清侧别）。 */
        const val LANG_TITLE_FLUIDS: String = "gtetcore.machine.et_tag_filter.title.fluids"

        /** 主页面宽度：两块配置面板各 144 宽、左边距 3 → 内容 147；留到 150，窗口最小宽 172 依旧由 GTM 兜底。 */
        private const val PANEL_WIDTH: Int = 150

        /** 物品侧配置面板的 Y（在上面；下面那块变大时它向上让位）。 */
        private const val ITEM_BLOCK_Y: Int = 12

        /** 流体侧配置面板的 Y：物品块底边（12 + 74 = 86）再留 8px 间距。 */
        private const val FLUID_BLOCK_Y: Int = 94

        /**
         * 主页面高度 = 流体块底边（94 + GTM 配置面板的 74）—— 别改成别的数，
         * 理由见 [createUIWidget] 上那两条 ⚠️。
         */
        private const val PANEL_HEIGHT: Int = FLUID_BLOCK_Y + 74

        /** 流体侧配置（标签两条 + 定量上限 + 全部配置槽）在 NBT 里的键名。 */
        private const val NBT_FLUID_TAG_WHITE: String = "ETFluidTagWhite"
        private const val NBT_FLUID_TAG_BLACK: String = "ETFluidTagBlack"
        private const val NBT_FLUID_BATCH_SIZE: String = "ETFluidBatchSize"
        private const val NBT_FLUID_CONFIGS: String = "ETFluidConfigStacks"
    }
}