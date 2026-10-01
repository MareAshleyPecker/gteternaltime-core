package rain.gtetcore.gtet.common.machine.multiblock.timeflow
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.TickableSubscription
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerList
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import rain.gtetcore.gtet.api.ETValues
import rain.gtetcore.gtet.api.timeflow.ETTimeFlowHandler
import rain.gtetcore.gtet.api.timeflow.ITimeFlowStorage
import rain.gtetcore.gtet.api.timeflow.ITimeFlowTower
import rain.gtetcore.gtet.api.timeflow.TimeFlowTowers
import rain.gtetcore.gtet.common.item.timeflow.TimeClockData
import rain.gtetcore.gtet.config.GTETConfig
import rain.gtetcore.gtet.data.lang.TimeFlowHatchLang

/**
 * 「时序仓」多方块部件（1h ~ 16384h 共 7 档，ETV 专属）。
 *
 * ## 它干三件事
 * 1. **持有 TF 缓冲**：内部一个 [ETTimeFlowHandler]（long，上限 = 本档容量），
 *    本类只通过 [ITimeFlowStorage] 与它打交道 —— **没有第二份 TF 缓冲**；
 * 2. **把 TF 暴露给配方**：[getRecipeHandlers] 返回 `RecipeHandlerList.of(IO.IN, tfHandler)`，
 *    多方块成型时 GTM 自己会 `part.getRecipeHandlers()` → `addHandlerList(...)`
 *    （`WorkableMultiblockMachine#onStructureFormed`），于是配方的 `tickInputs` 里只要带
 *    `time_flow`，每 tick 就会真的从本仓扣（链路见 [ETTimeFlowHandler] 的类注释）；
 * 3. **从主控塔拉 TF**：绑定一座塔，每 tick `tower.extractTimeFlow(剩余空间)` 灌进自己的缓冲。
 *
 * ## 为什么不自己再写一个 handler
 * `ETTimeFlowHandler` 就是「GTM 扣 TF 的那个口子」（long 缓冲 + `handleRecipeInner` + 变更订阅），
 * 本仓只是**持有**它并在 tick 里 `insert`。多写一份等于把扣费语义写两遍，迟早对不上。
 *
 * ## 为什么 getRecipeHandlers 要自己覆写
 * `MultiblockPartMachine#getHandlerList()` 只把**同时是 `MachineTrait`** 的 trait 收进列表
 * （javap 实证：它对 `traits` 逐个 `instanceof IRecipeHandlerTrait`），
 * 而 `ETTimeFlowHandler` 刻意不继承 `MachineTrait`（见它的类注释）⇒ 走不到那条路。
 * 但 `IRecipeHandlerTrait` 本身就是能被 `RecipeHandlerList` 收下的形态，所以直接返回一个自建的
 * `RecipeHandlerList` 即可 —— 多方块那一侧一行都不用改。
 *
 * ## 存档怎么不丢
 * - **绑定**（维度 + 坐标）走 `@Persisted` 字段，`getFieldHolder()` 把本类挂在
 *   [MultiblockPartMachine] 的字段持有者后面（照 `ThreadHatchPartMachine` 的写法）；
 * - **TF 存量**走 `saveCustomPersistedData` / `loadCustomPersistedData`：
 *   真值在 `ETTimeFlowHandler#amount` 里，而它 **不能标 `@Persisted`**
 *   （不是 LDLib 的 `IManaged`，标了也不生效；改它又违反「不改已就绪设施」的约束）。
 *   `saveManagedPersistentData` 的顺序是「先写 `@Persisted` 字段、再回调 `saveCustomPersistedData`」，
 *   所以镜像到 `@Persisted` 字段会**慢一截**（配方扣费发生在别的机器的 tick 里），
 *   直接用自定义数据通道才是零误差的 —— 主控塔的储备也是走的同一条通道。
 *
 * ## 本期只做同维度
 * 绑定塔与自己在**同一维度**、且塔所在区块已加载时才拉；跨维度一律不拉（设定 §5：
 * 跨维度只能靠时序之瓶搬运）。拉不到就是「本次不拉」，**静默等待**，不刷日志、不报错。
 *
 * ## 档位：交给 GTM 的一定要先夹（⚠️）
 * 本仓可能落在**虚档位 `ETV`**（`= MAX + 1`）上，而 GTM 的档位表只有 15 项（`0..MAX`）：
 * `TieredPartMachine` 存下的那个 `tier` 会被 GTM 拿去做电压 / 名字 / GUI 相关的查表
 * （`GTValues.V` / `VN` 都不做边界检查），塞 15 进去就是数组越界。
 * 所以构造时**只有夹过的档位**能进 `super`（走 `ETValues.gtmTierOf`），
 * 逻辑档位另存在 [logicalTier] 里供我们自己的文案 / 覆盖层用（见 [ETValues] 的类注释）。
 * **后果**：`4096h=MAX` 与 `16384h=ETV` 两档在本类的 `getTier()` 上**都是 `MAX`** ——
 * 电压同为 `GTValues.V[MAX]`（`ETV` 的 4 倍电压 GTM 那侧表达不出来），这是虚档位的
 * 必然结果、不是 bug。
 *
 * @param logicalTier **逻辑档位**（`ETTimeFlowHatches` 的变体表给出，可能是虚档位 `ETV`）
 * @param capacity    本档容量上限（TF），由 `ETTimeFlowHatches` 的变体表显式给出
 *
 * @author rain fox
 */
class TimeFlowHatchPartMachine(
    holder: IMachineBlockEntity,
    /** 逻辑档位（可能是虚档位 `ETV`）；GTM 侧读到的是夹过的 `0..MAX`，见类注释。 */
    val logicalTier: Int,
    capacity: Long
) : TieredPartMachine(holder, ETValues.gtmTierOf(logicalTier)), ITimeFlowStorage, IInteractedMachine {

    /**
     * TF 缓冲，同时也是本仓唯一的真值源：配方扣的就是它，拉塔灌的也是它。
     *
     * 容量写死成构造参数（变体表给的档位值），之后不再变化，所以不需要 `@Persisted`。
     */
    val tfHandler: ETTimeFlowHandler = ETTimeFlowHandler(0L, capacity)

    /**
     * 暴露给所在多方块的配方处理器。
     *
     * 只建一次并复用：GTM 在**每次**结构成型时都会读一遍 `getRecipeHandlers()`，
     * 每次都新建 `RecipeHandlerList` 会让配方逻辑的变更订阅挂到旧对象上。
     */
    private val recipeHandlers: List<RecipeHandlerList> = listOf(RecipeHandlerList.of(IO.IN, tfHandler))

    // ================================================================
    //  绑定（维度 + 坐标；键名与形态照 `TimeBottleData` 那一套）
    // ================================================================

    /** 绑定塔所在维度（`minecraft:overworld` 这种）；空串 = 未绑定。 */
    @Persisted
    var boundDim: String = ""
        private set

    /** 绑定塔的 X 坐标。 */
    @Persisted
    var boundX: Int = 0
        private set

    /** 绑定塔的 Y 坐标。 */
    @Persisted
    var boundY: Int = 0
        private set

    /** 绑定塔的 Z 坐标。 */
    @Persisted
    var boundZ: Int = 0
        private set

    /** 是否已绑定一座塔。 */
    val isBound: Boolean get() = boundDim.isNotEmpty()

    /**
     * 每 tick 拉一次塔的订阅句柄。
     *
     * ⚠️ **不用** `ConditionalSubscriptionHandler`（原先用它，条件是 `isBound`）：那个 handler 的条件
     * 只在 `updateSubscription()` 被显式调到时才重算，而本仓「有没有塔可拉」会在**我们收不到通知**的
     * 时刻变化 —— 唯一塔被建起来 / 被别人拆掉、多塔模式下占位易主、塔所在区块卸载又加载。
     * 条件一旦卡在 `false`，就再也不会自己翻回来，仓会一直不工作。
     * 所以改成无条件每 tick 调一次 [pullTick]：它开头就是几次整数比较，没塔就直接返回
     * （与无线能源仓同一条取舍，见 `WirelessEnergyHatchPartMachine#onLoad`）。
     */
    private var tickSubscription: TickableSubscription? = null

    init {
        // 存量每次变化（拉塔灌入 / 配方扣掉 / 手动取出）都标脏，保证区块存档时写的是最新值
        tfHandler.addChangedListener { markDirty() }
    }

    /** 读回绑定好的塔；未绑定 / 维度串坏了返回 `null`。 */
    fun getBoundTower(): GlobalPos? {
        if (!isBound) return null
        val dim = ResourceLocation.tryParse(boundDim) ?: return null
        return GlobalPos.of(
            ResourceKey.create(Registries.DIMENSION, dim),
            BlockPos(boundX, boundY, boundZ)
        )
    }

    /** 绑定到一座塔；返回是否真的改了（重复绑同一座塔返回 `false`）。 */
    fun bindTower(tower: GlobalPos): Boolean {
        val dim = tower.dimension().location().toString()
        val pos = tower.pos()
        if (dim == boundDim && pos.x == boundX && pos.y == boundY && pos.z == boundZ) return false
        boundDim = dim
        boundX = pos.x
        boundY = pos.y
        boundZ = pos.z
        markDirty()
        return true
    }

    /** 解绑；返回是否真的改了。 */
    fun unbindTower(): Boolean {
        if (!isBound) return false
        boundDim = ""
        boundX = 0
        boundY = 0
        boundZ = 0
        markDirty()
        return true
    }

    // ================================================================
    //  ITimeFlowStorage（本仓的 TF 缓冲就是 tfHandler）
    // ================================================================

    /** 当前存量（TF）。 */
    override fun getTimeFlow(): Long = tfHandler.amount

    /**
     * 直接写入存量，返回夹取后的实际值（契约见 [ITimeFlowStorage]）。
     *
     * `ETTimeFlowHandler` 只有 `insert` / `extract` 两个口子，所以这里是「先清空、再灌入」；
     * 夹取由 `insert` 自己做（它按容量截断）。
     */
    override fun setTimeFlow(amount: Long): Long {
        val clamped = amount.coerceIn(0L, getTimeFlowCapacity())
        tfHandler.extract(Long.MAX_VALUE)
        tfHandler.insert(clamped)
        return clamped
    }

    /** 容量上限（TF）= 本档容量，构造时定死。 */
    override fun getTimeFlowCapacity(): Long = tfHandler.capacity

    // ================================================================
    //  配方接线
    // ================================================================

    /**
     * 交给所在多方块的处理器列表。
     *
     * ⚠️ 覆写它的理由见类注释（基类只收 `MachineTrait` 形态的 handler）。
     * 本仓没有任何 `MachineTrait` 形态的处理器，所以**不需要**再调 `super` 的结果。
     */
    override fun getRecipeHandlers(): List<RecipeHandlerList> = recipeHandlers

    // ================================================================
    //  从塔拉 TF（每 tick）
    // ================================================================

    /**
     * 一 tick 的拉取：`tower.extractTimeFlow(剩余空间)` → 灌进自己的缓冲。
     *
     * 顺序与判据：
     * 1. 客户端 / 缓冲已满 ⇒ 直接返回；
     * 2. **没绑定也没得自动连** ⇒ 返回（这种情况订阅本来就退掉了，兜一道）；
     * 3. **维度不同 ⇒ 不拉**（设定 §5：跨维度只能靠时序之瓶搬运）；
     * 4. [TimeFlowTowers.find] 找不到（塔不在 / 区块未加载 / 那一格不是塔）⇒ **静默返回**，
     *    不刷日志也不报错 —— 塔没强加载，区块滚出视野是常态，不该变成刷屏；
     * 5. 塔**没成型** ⇒ 不拉（没成型就是一堆方块，不是「闸口」）；
     * 6. 放置者没有取用权限 ⇒ 不拉（与无线仓同一条判据，见 [ITimeFlowTower.canUseTimeFlowByUuid]）；
     * 7. 拉到多少算多少，`extractTimeFlow` 不够就有多少给多少（接口契约）。
     *
     * ⚠️ 第 2 步的「自动连」= [MasterTowerRegistry.autoTower]：唯一性开着（默认）时自动连全服唯一那座塔，
     * 关掉（多塔）时必须显式绑定。**无线仓读的是同一个函数**，两族行为一致。
     * ⚠️ 第 6 步只在**自动连**这条路上才是新防线：显式绑定那条路在瓶子绑塔时就已经验过权限了；
     * 若不加这一步，「没权限的玩家在唯一塔旁边摆一台时序仓」就能白拿 TF。
     *
     * ⚠️ 这里**只搬运、不换汇**（设定 §3.1）：充入 / 取出效率不在仓里做，
     * 折损只发生在主控塔那个唯一闸口上。
     */
    private fun pullTick() {
        if (isRemote) return
        val lvl = level ?: return
        if (lvl.isClientSide) return

        val room = getTimeFlowRoom()
        if (room <= 0L) return

        val target = getBoundTower() ?: MasterTowerRegistry.autoTower() ?: return
        if (target.dimension() != lvl.dimension()) return

        val tower = TimeFlowTowers.find(lvl, target.pos()) ?: return
        if ((tower as? IMultiController)?.isFormed != true) return
        val owner = ownerUUID
        if (owner != null && !tower.canUseTimeFlowByUuid(owner)) return

        val got = tower.extractTimeFlow(room)
        if (got > 0L) tfHandler.insert(got)
    }

    // ================================================================
    //  绑定手势（手持已绑塔的时序之瓶右键本仓）
    // ================================================================

    /**
     * GTM 的机器右键钩子（`IInteractedMachine#onUse`）。
     *
     * ⚠️ 它排在 `MetaMachineBlock#use` 里 `tryToOpenUI(...)` **之前**（javap 实证：先
     * `instanceof IInteractedMachine` → `onUse`，返回非 `PASS` 直接 `areturn`），
     * 所以这个手势不会被部件的 UI 吃掉。
     *
     * 手势（与瓶子的「右键塔 = 绑定 / 潜行右键 = 解绑」同构）：
     * - 手里的**时序之瓶已经绑了塔** → 非潜行 = 本仓绑到那座塔；潜行 = 解绑；
     * - 瓶子没绑塔 / 塔在别的维度 ⇒ 只发一条提示，右键**交还给方块本身**（返回 `PASS`）；
     * - 手里不是时序之瓶 ⇒ 什么都不做（`PASS`）。
     */
    override fun onUse(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (!TimeClockData.isTimeBottle(stack)) return InteractionResult.PASS

        if (!level.isClientSide) {
            val tower = TimeClockData.getBoundTower(stack)
            if (tower == null) {
                player.displayClientMessage(
                    Component.translatable(TimeFlowHatchLang.BOTTLE_UNBOUND).withStyle(ChatFormatting.RED), true
                )
                return InteractionResult.PASS
            }
            if (!player.isShiftKeyDown && tower.dimension() != level.dimension()) {
                // 本仓本期只做同维度直扣塔，跨维度的绑定先拦下来（免得绑了却永远拉不到）
                player.displayClientMessage(
                    Component.translatable(TimeFlowHatchLang.WRONG_DIMENSION).withStyle(ChatFormatting.RED), true
                )
                return InteractionResult.PASS
            }

            if (player.isShiftKeyDown) {
                unbindTower()
                player.displayClientMessage(
                    Component.translatable(TimeFlowHatchLang.UNBOUND), true
                )
            } else {
                bindTower(tower)
                val p = tower.pos()
                player.displayClientMessage(
                    Component.translatable(TimeFlowHatchLang.BOUND, p.x, p.y, p.z), true
                )
            }
        }
        // 两端都返回「已消耗」，免得客户端再跑一遍同样的手势
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    // ================================================================
    //  存档
    // ================================================================

    /**
     * 落盘 TF 存量（`@Persisted` 字段由 LDLib 先写，见类注释）。
     *
     * 键名与 `TimeBottleData` / 主控塔一致（`time_flow`），方便排查时统一读。
     */
    override fun saveCustomPersistedData(tag: CompoundTag, forDrop: Boolean) {
        super.saveCustomPersistedData(tag, forDrop)
        tag.putLong(KEY_TIME_FLOW, tfHandler.amount)
    }

    /**
     * 读回 TF 存量。
     *
     * 走 [setTimeFlow] 而不是直接塞：旧存档 / 手改 NBT 里的越界值会被夹回
     * `0 .. 本档容量`（同一位置上换成低一档的仓时，这个夹取就是必须的）。
     */
    override fun loadCustomPersistedData(tag: CompoundTag) {
        super.loadCustomPersistedData(tag)
        if (tag.contains(KEY_TIME_FLOW)) {
            setTimeFlow(tag.getLong(KEY_TIME_FLOW).coerceAtLeast(0L))
        }
    }

    // ================================================================
    //  生命周期
    // ================================================================

    /**
     * 建立 tick 订阅。
     *
     * 无条件订阅：见 [tickSubscription] 上那段「为什么不用 `ConditionalSubscriptionHandler`」。
     * 客户端不订阅（拉塔是纯服务端行为）。
     */
    override fun onLoad() {
        super.onLoad()
        if (isRemote) return
        tickSubscription = subscribeServerTick { pullTick() }
    }

    /** 卸载时退订，免得区块滚出视野后还在吃 tick。 */
    override fun onUnload() {
        unsubscribe(tickSubscription)
        tickSubscription = null
        super.onUnload()
    }

    /** 本类自己不要字段持有者就会有字段存档丢数据 —— 挂上 [MultiblockPartMachine] 的持有者（见类注释）。 */
    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    /**
     * 部件共享的闸门，返回全局开关 [GTETConfig.partsShareable]（默认 `false` = 禁止共享）。
     *
     * 原先这里写死 `false`（与线程仓 / 并行仓 / 超频仓同一条约定）。现在由配置兜底 ——
     * **打开配置就恢复串配方风险**：本件的 TF 缓冲与「绑定的主控塔」是每件独立的，
     * 被两个已成型结构共享时两个控制器会从同一份缓冲里取走同额的时间流。
     * ⚠️ 该值只在 `BlockPattern#checkPatternAt` 那一刻被读，改配置后要等下一次结构检测才生效。
     */
    override fun canShared(): Boolean = GTETConfig.partsShareable()

    companion object {

        /** TF 存量在 NBT 里的键（键名与 `TimeBottleData` / 主控塔统一）。 */
        const val KEY_TIME_FLOW: String = "time_flow"

        /**
         * 挂在 [MultiblockPartMachine] 的字段持有者后面，保证父类的
         * `controllerPositions`（`@DescSynced`）与本类的四个绑定字段（`@Persisted`）都串得起来。
         */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder = ManagedFieldHolder(
            TimeFlowHatchPartMachine::class.java,
            MultiblockPartMachine.MANAGED_FIELD_HOLDER
        )
    }
}