package rain.gtetcore.gtet.common.machine.multiblock.part.ae
import appeng.api.config.Actionable
import appeng.api.networking.IGrid
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.GenericStack
import appeng.api.storage.MEStorage
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.IDropSaveMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank
import com.gregtechceu.gtceu.integration.ae2.machine.MEHatchPartMachine
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot
import com.gregtechceu.gtceu.integration.ae2.utils.AEUtil
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction
import rain.gtetcore.gtet.config.GTETConfig
import rain.gtetcore.gtet.integration.ae2.ETTagFilter
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator
import rain.gtetcore.gtet.integration.ae2.IMEStockingHost
import java.util.function.Supplier

/**
 * 「ME 标签库存输入仓」：GTM 的 `me_stocking_input_hatch`（ME 库存输入仓）+ 标签过滤 + 定量拉取。
 *
 * 与物品版 [ETTagFilterStockBusPartMachine] 是同构的两件东西（GTM 自己那两件也是同构的：
 * `MEStockingBusPartMachine` / `MEStockingHatchPartMachine` 的成员几乎一一对应），
 * 所以这里不再重复说明设计取舍 —— 路线依据（GTM 7.5.3 没有 `test(AEKey)`、走公开的
 * `setAutoPullTest` + 覆写 `syncME` + 自带库存槽）与把关点表格见物品版类注释。
 *
 * ## 流体侧的两处差异
 *
 * - 取数点是 `ExportOnlyAEFluidSlot#drain(int, FluidAction)`（GTM 的库存流体槽覆写的是它），
 * 而不是 `extractItem`；`drain(FluidStack, FluidAction)` 会转发到它，所以两条路都覆盖到。
 * - 「每次拉 N 个」的 N 单位是 **mB**（1000 mB = 1 桶），上限 `BATCH_MAX` 因此取 1_000_000 mB = 1000 桶。
 *
 * @author rain fox
 */
open class ETTagFilterStockHatchPartMachine(holder: IMachineBlockEntity, vararg args: Any?) :
    MEStockingHatchPartMachine(holder, *args), IMEStockingHost, IDropSaveMachine {

    /** 标签判定器：构造一次、按表达式变化与 key 缓存。 */
    private val tagFilter = ETTagFilter()

    @field:Persisted
    private var tagWhite = ""

    @field:Persisted
    private var tagBlack = ""

    /** 每次从网络备货的上限（mB）；0 = 不限制（默认）。 */
    @field:Persisted
    private var batchSize = 0

    /**
     * 「允许多方块共享」开关，**默认 false = 隔离**（与本族其他件一致）。
     *
     * ⚠️ 默认值不能改成 true：流体侧的标签 / 定量 / 库存列表同样是每件独立的配置，
     * 两个控制器读同一份 `stock` 就是串配方（详见物品版
     * [ETTagFilterStockBusPartMachine.canShared] 的完整说明）。
     *
     * ⚠️ 带 `@DescSynced`（理由见物品版同名字段）：开关面板要在客户端读这个值。
     */
    @field:DescSynced
    @field:Persisted
    private var shareEnabled = false

    init {
        applyTagFilter()
    }

    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    /**
     * ⚠️ 这一条是 GTM 的不对称处：物品侧 `MEBusPartMachine` 有 public 的 `getActionSource()`，
     * 流体侧 `MEHatchPartMachine` 只有 `protected final IActionSource actionSource` 字段、**没有 getter**
     * （javap 两份类的方法表可直接对比）。库存槽需要一个统一视图，所以这里把那个 protected 字段暴露出来。
     */
    override fun getActionSource(): IActionSource {
        return actionSource
    }

    // ////////////////////////////////
    // ***** 仓室隔离（IMultiPart）****//
    // ////////////////////////////////

    /**
     * **仓室隔离（玩家可切换）**：默认禁止、面板开关打开后放行，
     * 返回值是「[shareEnabled] OR 全局配置 `multiblock.partsShareable`」。
     *
     * 语义、运行时影响（`BlockPattern#checkPatternAt` 里
     * `isFormed() && !canShared() && !hasController(...)` 那一处判定）、拨动开关两个方向分别发生什么、
     * 以及「为什么默认必须隔离」的完整说明见物品版
     * [ETTagFilterStockBusPartMachine.canShared] —— 流体侧的配置
     * （[tagWhite] / [tagBlack] / `batchSize`）同样是每件独立的，
     * 共享会让两个控制器的配方匹配读到同一份 `stock`。
     *
     * **⚠️ 全局配置兜底：** 返回值现在是「面板开关 OR `multiblock.partsShareable`」
     * —— 默认 `false` 时与原来只读 [shareEnabled] **完全一致**（用 OR 而非 AND 正是为此），
     * 配置打开后本件无条件放行、**串配方风险随之恢复**（多人服慎开）；
     * 该值只在 `BlockPattern#checkPatternAt` 那一刻被读，改配置不会立刻复检。
     */
    override fun canShared(): Boolean {
        return shareEnabled || GTETConfig.partsShareable()
    }

    /**
     * 面板开关的显示状态，与 [canShared] 同源（同样带配置兜底）：
     * 配置打开时面板如实显示「允许共享」，此时拨开关不会再改变结果。
     */
    override fun canBeShared(): Boolean {
        return shareEnabled || GTETConfig.partsShareable()
    }

    /** 同物品版：服务端改值 + 让本件所属的每个多方块立刻复检结构（⚠️ 先复制再遍历，理由见物品版）。 */
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
        // ⚠️ 每次取用都先按当前两条原始表达式校准一次（理由见物品版同名方法：@Persisted 是反射直写字段，
        //    读档不经过 setter）。set() 在字符串没变时直接返回，无重复解析代价。
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
     * 换掉 GTM 的流体库存列表（理由见物品版类注释）。
     *
     * ⚠️ 构造期被调用，槽只持有 `this` 视图，不在构造期读字段。
     *
     * ⚠️ `CONFIG_SIZE` 用 [MEHatchPartMachine.CONFIG_SIZE]（= 16，protected）而不是
     * `MEStockingHatchPartMachine.CONFIG_SIZE`（那个是 private）；
     * 两者数值一致 —— GTM 给本仓构造时传的就是 `MEHatchPartMachine.CONFIG_SIZE`。
     */
    override fun createTank(initialCapacity: Int, slots: Int, vararg args: Any?): NotifiableFluidTank {
        this.aeFluidHandler = ETTagFilterStockFluidList(this, MEHatchPartMachine.CONFIG_SIZE)
        return this.aeFluidHandler
    }

    override fun addedToController(controller: IMultiController) {
        super.addedToController(controller)
        // ⚠️ 必须在 super 之后：GTM 的 IMEStockingPart#addedToController 会覆盖 autoPullTest
        setAutoPullTest(tagAutoPullTest(this::testConfiguredInOtherPart))
    }

    // ///////////////////////////////
    // ********** Sync ME *********//
    // ///////////////////////////////

    /**
     * 与 GTM 的 `MEStockingHatchPartMachine#syncME()` 逐行等价，只多了判空、标签闸门与定量上限
     * （见物品版同名方法的注释）。
     */
    override fun syncME() {
        val grid: IGrid? = getMainNode().getGrid()
        if (grid == null) return
        val networkInv: MEStorage = grid.getStorageService().getInventory()
        val batch = batchLimit()

        for (slot in this.aeFluidHandler.getInventory()) {
            val config = slot.getConfig()
            if (config != null && testTag(config.what())) {
                val key = config.what()
                val available = networkInv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource)
                val want = Math.min(available, batch)
                if (want >= getMinStackSize()) {
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
        configuratorPanel.attachConfigurators(ETTagFilterConfigurator(this, true))
    }

    // ////////////////////////////////
    // ****** Configuration ******//
    // ////////////////////////////////

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
     * GTET 自己的 AE 流体库存列表：只为让每个槽都是 [ETTagFilterStockFluidSlot]。
     *
     * ⚠️ 不需要另做什么：`ExportOnlyAEFluidList` 构造时会为每个槽建一个
     * `FluidStorageDelegate`，而那个委托把 `drain` 转发给**我们的槽实例**，
     * 所以配方 / 能力两条取数路都落到 [ETTagFilterStockFluidSlot.drain]。
     */
    class ETTagFilterStockFluidList(private val host: IMEStockingHost, slots: Int) :
        ExportOnlyAEFluidList(host as MetaMachine, slots, Supplier { ETTagFilterStockFluidSlot(host) }) {

        override fun isStocking(): Boolean {
            return true
        }

        override fun isAutoPull(): Boolean {
            return host.isAutoPull()
        }

        /** 同物品版：把 GTM 库存列表 `hasStackInConfig(stack, checkExternal)` 的外部检查语义搬过来。 */
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
     * GTET 自己的 AE 库存流体槽：把 GTM `MEStockingHatchPartMachine.ExportOnlyAEStockingFluidSlot#drain`
     * 那段逻辑重写一遍，并加上标签闸门与取数上限。
     */
    class ETTagFilterStockFluidSlot : ExportOnlyAEFluidSlot {

        private val host: IMEStockingHost

        constructor(host: IMEStockingHost) {
            this.host = host
        }

        constructor(host: IMEStockingHost, config: GenericStack?, stock: GenericStack?) : super(config, stock) {
            this.host = host
        }

        /** 真正的取数点（`drain(FluidStack, FluidAction)` 会转发到这里）。 */
        override fun drain(maxDrain: Int, action: FluidAction): FluidStack {
            val stock = this.stock
            val config = this.config
            if (stock == null || config == null) return FluidStack.EMPTY
            val stockKey = stock.what()
            if (stockKey !is AEFluidKey) return FluidStack.EMPTY

            // 闸门一：标签
            if (!host.getTagFilter().test(config.what())) return FluidStack.EMPTY
            // 闸门二：取数上限 = min(调用方要的量, 本仓当前持有量, 每批 N)
            val limit = Math.min(Math.min(maxDrain.toLong(), stock.amount()), host.batchLimit())
            if (limit <= 0 || !host.isOnline()) return FluidStack.EMPTY

            val grid: IGrid? = host.getMainNode().getGrid()
            if (grid == null) return FluidStack.EMPTY
            val aeNetwork: MEStorage = grid.getStorageService().getInventory()

            val actionable = if (action.simulate()) Actionable.SIMULATE else Actionable.MODULATE
            val extracted = aeNetwork.extract(stockKey, limit, actionable, host.getActionSource())
            if (extracted <= 0) return FluidStack.EMPTY

            val resultStack = AEUtil.toFluidStack(stockKey, extracted)
            if (action.execute()) {
                // may as well update the display here
                val newStock = ExportOnlyAESlot.copy(stock, stock.amount() - extracted)
                this.stock = newStock
                if (newStock.amount() <= 0) this.stock = null
                if (this.onContentsChanged != null) this.onContentsChanged.run()
            }
            return resultStack
        }

        override fun copy(): ETTagFilterStockFluidSlot = ETTagFilterStockFluidSlot(
            host,
            if (this.config == null) null else ExportOnlyAESlot.copy(this.config),
            if (this.stock == null) null else ExportOnlyAESlot.copy(this.stock)
        )
    }

    companion object {

        /**
         * 挂在 GTM 的库存输入仓字段持有者后面，保证两条链上的字段都串得起来。
         */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder = ManagedFieldHolder(
            ETTagFilterStockHatchPartMachine::class.java,
            MEStockingHatchPartMachine.MANAGED_FIELD_HOLDER
        )

        /** 「每次拉 N 个」的可配范围（N 的单位是 mB，上限 1_000_000 mB = 1000 桶）。0 = 不限制。 */
        const val BATCH_MIN: Int = 0
        const val BATCH_MAX: Int = 1_000_000

        /** 定量上限在数据棒 / 拆方块里的键名。 */
        private const val NBT_BATCH_SIZE: String = "ETBatchSize"

        /**
         * 共享开关在数据棒 / 拆方块里的键名。
         *
         * ⚠️ 必须显式写这一对键（理由见物品版 `NBT_SHARE`）：`@Persisted` 只管方块实体存档，
         * 掉落物走的是 `saveToItem` / `loadFromItem`。缺键时不动，默认 false = 隔离。
         */
        private const val NBT_SHARE: String = "ETShareEnabled"
    }
}