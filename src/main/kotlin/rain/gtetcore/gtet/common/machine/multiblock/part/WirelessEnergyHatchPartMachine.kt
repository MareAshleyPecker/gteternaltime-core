package rain.gtetcore.gtet.common.machine.multiblock.part

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.TickableSubscription
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredIOPartMachine
import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer
import com.gregtechceu.gtceu.utils.FormattingUtil
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import rain.gtetcore.gtet.api.timeflow.ETTimeFlow
import rain.gtetcore.gtet.api.timeflow.ITimeFlowTower
import rain.gtetcore.gtet.api.timeflow.TimeFlowTowers
import rain.gtetcore.gtet.common.item.timeflow.TimeBottleData
import rain.gtetcore.gtet.common.machine.multiblock.timeflow.MasterTowerRegistry
import rain.gtetcore.gtet.config.GTETConfig
import java.util.Locale

/**
 * 「无线能源仓」多方块部件 —— 把主控塔的**时间流（TF）换成本仓的 EU**（设定 §2.1 / §2.3）。
 *
 * ## 一句话
 * 它是**时序仓的镜像**：时序仓把塔里的 TF 原样搬进自己的 TF 缓冲（只搬运、不换汇）；
 * 本仓则是「拿 TF 去塔里换电」—— 每 tick 向绑定的塔要一笔 TF，塔把那笔 TF 折成 EU 还回来，
 * 本仓再把它灌进自己的**输入侧能量容器**，于是这个容器就成了多方块的一张「无线能源仓」。
 *
 * ## 骨架照抄谁（GTM 7.5.3 源码树）
 * 机器 / 容器的挂法逐条对着 `common/machine/multiblock/part/EnergyHatchPartMachine.java` 抄：
 * | 抄的东西 | GTM 出处 |
 * |---|---|
 * | 基类 `TieredIOPartMachine`（`IO.IN`）、字段 `amperage` | `EnergyHatchPartMachine.java:26` / `:26`/`:37-41` |
 * | 输入侧容器 = `NotifiableEnergyContainer.receiverContainer(this, 容量, 电压, 电流)` | `EnergyHatchPartMachine.java:59-62` |
 * | 容量 `V[tier] × 64 × amperage`（**全程 Long**） | `EnergyHatchPartMachine.java:112-114`（GTM 自己的 output 仓口径） |
 * | 自己定义 `MANAGED_FIELD_HOLDER` 并挂在 `TieredIOPartMachine` 后面 | `EnergyHatchPartMachine.java:28-29` / `:46-49` |
 *
 * ### ⚠️「能被多方块聚合」到底靠什么（这一步挂错就是「能放、不供电」，不开游戏很难发现）
 * 多方块取电的链路是：
 * ```
 * WorkableElectricMultiblockMachine#getEnergyContainer()          // :237-247
 *   → getCapabilitiesFlat(IO.IN, EURecipeCapability.CAP)          // :239
 *   → 由 onStructureFormed() 里的 addHandlerList(...) 填出来      // WorkableMultiblockMachine.java:135-138
 *   → 而 handlerList 来自 part.getRecipeHandlers()
 *   → MultiblockPartMachine#getHandlerList()                       // :100-118
 *       直接遍历 `traits`，取每个 `IRecipeHandlerTrait`（**不看 hasCapability**）
 * ```
 * 所以「能被聚合」的充分条件是：**容器必须是这台机器的 trait，且 `handlerIO != IO.NONE`**。
 * 两条都由 `NotifiableEnergyContainer` 自己满足：
 * - 它是 `MachineTrait`，而 `MachineTrait` 的构造函数里就 `machine.attachTraits(this)`
 *   （`MachineTrait.java:34-38`）⇒ **不需要我们手写 `attachTrait` / `getTrait`**，
 *   GTM 的做法就是「构造期 new 一个、留个字段」（trait 事后加不进去，`MetaMachine.java:499-504`）；
 * - `handlerIO` 由「输入电压与输入电流是否都为 0」推出（`NotifiableEnergyContainer.java:72-74`）
 *   ⇒ **电压 / 电流必须非 0**，否则 `handlerIO = IO.NONE`、多方块直接看不见本仓。
 *
 * ### 为什么把外部能力关掉（`setCapabilityValidator { false }`）
 * 「外部能不能接电缆」与「能不能被多方块聚合」是**两条独立的路**：
 * - 外部暴露走 `MetaMachineBlockEntity#getCapabilitiesFromTraits`（`:299` 那里按 `trait.hasCapability(side)` 过滤）；
 * - 多方块聚合走上面那条链，**不经过 `hasCapability`**。
 * 所以把 `capabilityValidator` 恒判 false 只是「不对外接电缆」，聚合照旧 ——
 * 这正是无线仓该有的样子：**它的电只来自塔**，接上电缆反而会让「无线」名不副实。
 *
 * ## 每 tick 取电（[pullTick]）
 * 顺序：容量/上限判定 → 空转保护 → 找塔与权限 → 算损耗 → **先算该扣多少 TF** → 向塔换 EU → 灌缓冲。
 * 详见 [pullTick] 的注释。
 *
 * ## 本期只做同维度
 * 跨维度要连接塔（设定 §6.3）**还没实现**，所以跨维度**一律不供电**、并在面板里写明原因。
 * 这里刻意不放行 —— 放行等于绕掉连接塔与它的跨维度损耗。
 *
 * ## 未实现 / 待实机确认
 * - 只有开游戏才能确认的：多方块是否真的把本仓算进能源（`getCapabilitiesFlat` 命中）、
 *   每 tick 是否真的把电灌进缓冲、绑定手势是否真的触发。静态读码得出的只是「应该成立」。
 *
 * @param tier     电压档位（IV ~ MAX；本族没有虚档位，`ETValues.gtmTierOf` 不需要介入）
 * @param amperage 安培档（1 / 16 / 64 / 256 … / 4194304）
 *
 * @author rain fox
 */
class WirelessEnergyHatchPartMachine(
    holder: IMachineBlockEntity,
    tier: Int,
    val amperage: Int
) : TieredIOPartMachine(holder, tier, IO.IN), IInteractedMachine {

    /**
     * 本仓缓冲容量（EU）= `V[tier] × 64 × amperage`（设定 §7 第 8 条）。
     *
     * ⚠️ **必须全程 Long**：`GTValues.V[MAX] = 2147483647`，`× 64 × 4194304 ≈ 5.76E+17`，
     * 用 `Int` 中间量会直接溢出成一个毫无意义的小数（`EnergyHatchPartMachine.java:112-114`
     * 里那个 `64L` 就是为了这件事）。
     */
    val capacityEu: Long = GTValues.V[tier] * 64L * amperage.toLong()

    /**
     * 输入侧能量容器 —— 本仓唯一的功能性 trait，也是多方块取电的那个口子（见类注释）。
     *
     * - `receiverContainer(...)`（`NotifiableEnergyContainer.java:82-85`）= 只进不出（`handlerIO = IO.IN`）；
     * - 参数顺序：容量 / 最大输入电压 / 最大输入电流；**电压必须给 `V[tier]`**，
     *   多方块的 `getMaxVoltage()` 就是从这里读的（`WorkableElectricMultiblockMachine.java:269`）；
     * - `@Persisted`：缓冲存量要进存档（GTM 自己的仓也是这么标的）。
     */
    @Persisted
    val energyContainer: NotifiableEnergyContainer = NotifiableEnergyContainer.receiverContainer(
        this, capacityEu, GTValues.V[tier], amperage.toLong()
    ).apply {
        // 见类注释「为什么把外部能力关掉」：只影响外部电缆，不影响多方块聚合
        setCapabilityValidator { false }
    }

    /** 每 tick 的取电订阅（未订阅状态见 [unsubscribeTick]）。 */
    private var tickSubscription: TickableSubscription? = null

    // ================================================================
    //  绑定（维度 + 坐标；键名与形态照 `TimeFlowHatchPartMachine` / `TimeBottleData` 那一套）
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

    /** 是否显式绑定过一座塔（与「能不能取电」不是一回事，后者还要看唯一塔自动连接，见 [resolveTowerPos]）。 */
    val isBound: Boolean get() = boundDim.isNotEmpty()

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

    /**
     * 本仓这一 tick 该去哪座塔取电。
     *
     * 两条来源，**显式绑定优先**（设定 §2.4 的两行）：
     * 1. 手持已绑定的时序之瓶右键本仓绑上的那座（多塔模式下的唯一途径）；
     * 2. 没显式绑定时，若 `masterTowerUnique = true`（默认），**自动连全服唯一的那座塔** ——
     *    §2.4 写的就是「零操作」。
     *
     * 第二行直接复用 [MasterTowerRegistry.autoTower]：**时序仓读的是同一个函数**，
     * 所以两族的「该连哪座塔」永远同一个答案，不会出现一族自动连接、另一族不认的情况。
     */
    fun resolveTowerPos(): GlobalPos? = getBoundTower() ?: MasterTowerRegistry.autoTower()

    // ================================================================
    //  损耗（设定 §6.2）
    // ================================================================

    /**
     * 基础损耗（设定 §6.2「按档位 2% ~ 8%」）—— **逐档线性**，从 IV 的 8% 降到 MAX 的 2%。
     *
     * ```
     * baseLoss(tier) = 0.02 + (0.08 - 0.02) × (MAX − tier) / (MAX − IV)
     * ```
     * 代入验一下两个端点与中间一档：
     * `IV(5) = 0.02 + 0.06 × 9/9 = 8%`、`ZPM(7) = 0.02 + 0.06 × 7/9 ≈ 6.67%`、`MAX(14) = 2%`。
     *
     * 档位超出 `IV..MAX` 时由两个端点常量自然延长（本族只出 IV..MAX，不会越界）。
     */
    private fun baseLoss(tier: Int): Double {
        val span = (BASE_LOSS_LOW_TIER - BASE_LOSS_HIGH_TIER).toDouble()
        val fromHigh = (BASE_LOSS_LOW_TIER - tier).toDouble()
        return BASE_LOSS_HIGH + (BASE_LOSS_LOW - BASE_LOSS_HIGH) * (fromHigh / span)
    }

    /**
     * 距离税（设定 §6.2 的「中和方案」）：**免计 128 格、4096 格封顶、系数上限 +15%**。
     *
     * ```
     * distanceLoss(d) = clamp(d − 128, 0, 4096 − 128) / (4096 − 128) × 0.15
     * ```
     *
     * ⚠️ **分母刻意取 `4096 − 128` 而不是 `4096`**：这样 `d = 4096` 时距离项**正好**是 +15%，
     * 与设定档那句「到 4096 格封顶 +15%」字面一致；若取 4096 当分母，到封顶时只有 14.53%，
     * 与档里写的数对不上。两种写法差 0.47 个百分点，这里选了「档里写多少就是多少」。
     *
     * ⚠️ **距离用切比雪夫距离**（三轴差值取最大），不是欧氏距离：
     * - 这是「**隔了多少格**」最直观的口径 —— 斜着走 100 格与直着走 100 格在这个口径下同样是 100，
     *   玩家心里数的是「几个区块」，而不是 `sqrt(Δx² + Δz²)`；
     * - 免计距离写的是「128 格（8 区块）」，区块本身就是方形口径，切比雪夫与它同构。
     * （所以看到 `maxOf(abs(dx), abs(dy), abs(dz))` **不是写错了**。）
     */
    private fun distanceLoss(from: BlockPos, to: BlockPos): Double {
        val dx = kotlin.math.abs(from.x - to.x)
        val dy = kotlin.math.abs(from.y - to.y)
        val dz = kotlin.math.abs(from.z - to.z)
        val distance = maxOf(dx, dy, dz).toDouble()
        val billed = (distance - DISTANCE_FREE).coerceIn(0.0, DISTANCE_BILLABLE)
        return billed / DISTANCE_BILLABLE * DISTANCE_LOSS_MAX
    }

    /** 本仓到 [towerPos] 的总损耗（基础 + 距离），并夹到 `[0, 1)` 免得后面当成除数时炸掉。 */
    private fun lossAt(towerPos: BlockPos): Double =
        (baseLoss(tier) + distanceLoss(pos, towerPos)).coerceIn(0.0, MAX_LOSS)

    // ================================================================
    //  每 tick 取电
    // ================================================================

    /**
     * 一 tick 的取电：**拿 TF 去塔里换 EU，再灌进自己的缓冲**。
     *
     * ## 为什么要有「空转保护」（设定：不要空转耗电）
     * 本仓每 tick 都会试着补货，而「补货」= 从塔里**真的扣掉** TF（那是玩家的资产）。
     * 如果一台多方块只是摆在那里不干活，还每 tick 都把缓冲顶到满，就等于：
     * ① 玩家的 TF 被换成电、**长期趴在缓冲里不动**（换汇不可逆，等于强迫消费）；
     * ② 缓冲满了以后塔侧储备被抽干，别的机器没得用。
     * 所以只在**确实需要电**的时候才拉，判据两条取或：
     * - **①有控制器在工作**：`getControllers()` 里有已成型、且配方逻辑正在跑（`RecipeLogic.isWorking`）的
     *   控制器 ⇒ 这一刻它在烧电，我们该补；
     * - **②缓冲低于「一 tick 的量」**：`stored < V[tier] × amperage`。
     *   这一条是必须的 —— 配方**跑不动**时 GTM 的状态是 WAITING / SUSPEND（TF/EU 不足那一段，
     *   见 `RecipeLogic` 的状态机），**不是 WORKING**；只看①的话缓冲见底后就永远补不上，
     *   会卡死在「没电→不工作→不补电」的死循环里。
     *   「一 tick 的量」取 `V × A`（本档一 tick 的通过上限）：低于它就说明连一 tick 的量都不够，
     *   补到刚好够跑，也不会把缓冲堆满。
     *
     * ## 顺序（每一步都别换）
     * 1. 客户端 / 满仓 / 上限为 0 ⇒ 直接返回；
     * 2. 空转保护（上面那条）；
     * 3. 找塔 → **维度不同 / 塔不在 / 没成型 / 没权限 ⇒ 不取电**（原因见 [resolveLink]，面板里会写出来）；
     * 4. **先算该扣多少 TF**：`wantTf = ceil(wantEu / (EU_PER_TF × (1 − loss)))`
     *    —— `wantEu` 是「想让缓冲涨多少」，除以每 TF 的**到账** EU 再向上取整，
     *    这样塔侧扣掉的 TF 一定是整数、且覆盖得住损耗；
     * 5. 向塔要：`tower.extractTimeFlowAsEu(wantTf)` —— 换汇在塔里做（设定 §3.1「换算只在主控塔里发生」），
     *    返回的是**真实到账的 EU**（塔里的 TF 不够时有少给少，**不能假设取满**）；
     * 6. 按真实到账量反算并灌入：`delivered = floor(gotEu × (1 − loss))` → `changeEnergy(+delivered)`。
     *
     * ⚠️ 这样走下来**永远不会出现「扣了不足 1 TF 的零头」**：TF 侧是整数向上取整，
     * EU 侧是从「实际到账的 TF」反算出来的，零头留在塔里、下一 tick 继续算。
     */
    private fun pullTick() {
        if (isRemote) return
        val lvl = level ?: return
        if (lvl.isClientSide) return

        val stored = energyContainer.energyStored
        val room = energyContainer.energyCapacity - stored
        if (room <= 0L) return

        val perTickEu = throughputEu()
        if (perTickEu <= 0L) return

        // 空转保护：不干活、而且缓冲够跑一 tick ⇒ 这一 tick 一点 TF 都不换
        if (!hasWorkingController() && stored >= perTickEu) return

        val link = resolveLink()
        val tower = link.tower ?: return
        val towerPos = link.towerPos ?: return

        val wantEu = minOf(room, perTickEu)
        if (wantEu <= 0L) return

        val loss = lossAt(towerPos)
        val euPerTf = euPerTfAfterLoss(loss)
        if (euPerTf <= 0L) return

        val wantTf = ceilDiv(wantEu, euPerTf)
        if (wantTf <= 0L) return

        val gotEu = tower.extractTimeFlowAsEu(wantTf)
        if (gotEu <= 0L) return

        val delivered = applyLoss(gotEu, loss)
        if (delivered <= 0L) return

        energyContainer.changeEnergy(delivered)
    }

    /** 本档一 tick 的通过上限（EU）= `V[tier] × amperage`；也是「低于多少就算缺电」的那条线。 */
    private fun throughputEu(): Long = GTValues.V[tier] * amperage.toLong()

    /**
     * 每个 TF 扣掉损耗后**实际到账**多少 EU（向下取整，至少 1）。
     *
     * 损耗用**百万分之一（ppm）的整数**表达再参与整除，避免浮点参与取整判定：
     * `loss = 0.08` ⇒ `80000 ppm` ⇒ `8192 × (1e6 − 8e4) / 1e6 = 7536 EU/TF`。
     * 整除截断让每 TF 的到账量**偏小**，于是 `wantTf` 偏大一点点 —— 宁可多要一丁点，
     * 也不让仓「想要的电拿不够」。
     */
    private fun euPerTfAfterLoss(loss: Double): Long {
        val ppm = (loss * PPM).toLong().coerceIn(0L, PPM - 1L)
        return (ETTimeFlow.EU_PER_TF * (PPM - ppm)) / PPM
    }

    /** `value × (1 − loss)` 向下取整。走 Double 只是为了不溢出（`value` 可以到 9E+15）。 */
    private fun applyLoss(value: Long, loss: Double): Long {
        if (value <= 0L) return 0L
        return Math.floor(value.toDouble() * (1.0 - loss)).toLong().coerceAtLeast(0L)
    }

    /** 向上取整的整数除法（`b > 0`）。 */
    private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0L) 0L else (a + b - 1L) / b

    /**
     * 本仓现在有没有「正在干活」的控制器（空转保护的第①条）。
     *
     * `getControllers()` 给的是本仓所属的全部控制器（一件仓可以被多个结构共享）；
     * 「在干活」= 已成型 **且** 配方逻辑处于 `WORKING`（`RecipeLogic.isWorking()`，
     * `RecipeLogic.java:447-449`）。只看 `isFormed()` 是不够的 —— 成型但闲置的结构
     * 每 tick 都会让本仓去换电，那正是空转耗电。
     */
    private fun hasWorkingController(): Boolean {
        for (controller in getControllers()) {
            if (!controller.isFormed) continue
            val workable = controller as? WorkableMultiblockMachine ?: continue
            if (workable.recipeLogic.isWorking) return true
        }
        return false
    }

    // ================================================================
    //  链接状态（tick 与面板共用同一份判定，免得两边说法不一致）
    // ================================================================

    /** 本仓与塔的连接状态；[LinkState.OK] 之外一律不取电，且每一种都在面板里有对应文案。 */
    private enum class LinkState { OK, UNBOUND, WRONG_DIMENSION, NO_TOWER, NO_PERMISSION }

    /** [resolveLink] 的结果：状态 + 塔坐标 + 塔本身（只有 OK 时塔非空）。 */
    private data class LinkInfo(val state: LinkState, val towerPos: BlockPos?, val tower: ITimeFlowTower?)

    /**
     * 把「本仓现在能不能从塔取电」一次性判完（**无副作用**，tick 与面板都调它）。
     *
     * 判定顺序（每一步都对应面板里一条提示）：
     * 1. 找不到目标塔 ⇒ 未接线；
     * 2. 目标塔在别的维度 ⇒ **不供电**（设定 §6.3 的连接塔还没做，跨维度一律不放行）；
     * 3. 塔不在（区块未加载 / 那一格不是塔）或**没成型** ⇒ 不供电；
     *    按 `TimeFlowTowers.find` 的契约，未加载的区块**不会**被强加载，所以这里就是「拉不到就算了」；
     * 4. 放置者没有取用权限（`ITimeFlowTower.canUseTimeFlowByUuid`：本人 / 白名单 / 同队）⇒ 不供电。
     */
    private fun resolveLink(): LinkInfo {
        val lvl = level ?: return LinkInfo(LinkState.NO_TOWER, null, null)
        val target = resolveTowerPos() ?: return LinkInfo(LinkState.UNBOUND, null, null)
        if (target.dimension() != lvl.dimension()) {
            return LinkInfo(LinkState.WRONG_DIMENSION, target.pos(), null)
        }
        val tower = TimeFlowTowers.find(lvl, target.pos())
            ?: return LinkInfo(LinkState.NO_TOWER, target.pos(), null)
        // 「已成型」只能从 IMultiController 那一侧问：MasterTowerMachine.formed 是 private
        if ((tower as? IMultiController)?.isFormed != true) {
            return LinkInfo(LinkState.NO_TOWER, target.pos(), null)
        }
        // 权限按**放置者**判：tick 时没有 Player，只有这台机器记下的 ownerUUID（GTM 在放置/首次右键时写入）
        val owner = ownerUUID
        if (owner != null && !tower.canUseTimeFlowByUuid(owner)) {
            return LinkInfo(LinkState.NO_PERMISSION, target.pos(), null)
        }
        return LinkInfo(LinkState.OK, target.pos(), tower)
    }

    // ================================================================
    //  绑定手势（手持已绑塔的时序之瓶右键本仓）—— 与 TimeFlowHatchPartMachine 同构
    // ================================================================

    /**
     * GTM 的机器右键钩子（`IInteractedMachine#onUse`）。
     *
     * ⚠️ 它排在 `MetaMachineBlock#use` 里 `tryToOpenUI(...)` **之前**
     * （`MetaMachineBlock.java:313-319`：先 `instanceof IInteractedMachine` → `onUse`，
     * 返回非 `PASS` 直接 return），所以这个手势不会被部件自身的 UI 吃掉。
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
        if (!TimeBottleData.isTimeBottle(stack)) return InteractionResult.PASS

        if (!level.isClientSide) {
            val tower = TimeBottleData.getBoundTower(stack)
            if (tower == null) {
                player.displayClientMessage(
                    Component.translatable(WirelessEnergyHatchLang.BOTTLE_UNBOUND).withStyle(ChatFormatting.RED), true
                )
                return InteractionResult.PASS
            }
            if (!player.isShiftKeyDown && tower.dimension() != level.dimension()) {
                // 本仓本期只做同维度供电，跨维度的绑定先拦下来（免得绑了却永远拉不到电）
                player.displayClientMessage(
                    Component.translatable(WirelessEnergyHatchLang.WRONG_DIMENSION).withStyle(ChatFormatting.RED), true
                )
                return InteractionResult.PASS
            }

            if (player.isShiftKeyDown) {
                unbindTower()
                player.displayClientMessage(
                    Component.translatable(WirelessEnergyHatchLang.UNBOUND), true
                )
            } else {
                bindTower(tower)
                val p = tower.pos()
                player.displayClientMessage(
                    Component.translatable(WirelessEnergyHatchLang.BOUND, p.x, p.y, p.z), true
                )
            }
        }
        // 两端都返回「已消耗」，免得客户端再跑一遍同样的手势
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    // ================================================================
    //  面板文本（多方块控制器那一侧聚合显示）
    // ================================================================

    /**
     * 往**控制器面板**里追加本仓的状态。
     *
     * 部件没有自己的 UI（本类也不打算开一个），GTM 给部件准备的显示口子就是这一条：
     * `IDisplayUIMachine#addDisplayText` 会遍历所有部件调 `part.addMultiText(textList)`
     * （`IDisplayUIMachine.java:21-25`），而 `WorkableElectricMultiblockMachine` 正是
     * `IDisplayUIMachine`（`:43-44`）并且在它自己的 `addDisplayText` 里显式调了
     * `IDisplayUIMachine.super.addDisplayText(...)`（`:134`）。
     * ⇒ 玩家右键**多方块控制器**就能看到本仓为什么没在供电。
     *
     * 文本由服务端求值后同步过去，所以这里读的就是服务端那份真数据；
     * 判定与 [pullTick] 共用 [resolveLink]，不会出现「面板说通了、其实没在拉」这种不一致。
     *
     * ⚠️ 不调 `super`：[IMultiPart.addMultiText] 的默认实现是空方法，本层的父类也没有覆写它，
     * 调了等于什么都没做（还会顺带绕进「Kotlin 类调 Java 接口默认方法」那套解析规则里）。
     */
    override fun addMultiText(textList: MutableList<Component>) {
        val link = resolveLink()
        textList += Component.translatable(
            WirelessEnergyHatchLang.DISPLAY_BUFFER,
            num(energyContainer.energyStored),
            num(energyContainer.energyCapacity),
            num(throughputEu())
        ).withStyle(ChatFormatting.AQUA)

        when (link.state) {
            LinkState.OK -> {
                val towerPos = link.towerPos ?: return
                val loss = lossAt(towerPos)
                textList += Component.translatable(
                    WirelessEnergyHatchLang.DISPLAY_LINK,
                    towerPos.x.toString(), towerPos.y.toString(), towerPos.z.toString(),
                    maxOf(
                        kotlin.math.abs(pos.x - towerPos.x),
                        kotlin.math.abs(pos.y - towerPos.y),
                        kotlin.math.abs(pos.z - towerPos.z)
                    ).toString(),
                    percent(loss),
                    percent(baseLoss(tier)),
                    percent(distanceLoss(pos, towerPos))
                ).withStyle(ChatFormatting.GRAY)
            }

            LinkState.UNBOUND ->
                textList += deny(WirelessEnergyHatchLang.DISPLAY_UNBOUND)

            LinkState.WRONG_DIMENSION ->
                textList += deny(WirelessEnergyHatchLang.DISPLAY_WRONG_DIMENSION)

            LinkState.NO_TOWER ->
                textList += deny(WirelessEnergyHatchLang.DISPLAY_NO_TOWER)

            LinkState.NO_PERMISSION ->
                textList += deny(WirelessEnergyHatchLang.DISPLAY_NO_PERMISSION)
        }
    }

    private fun deny(key: String): Component =
        Component.translatable(key).withStyle(ChatFormatting.RED)

    private fun num(value: Long): String = FormattingUtil.formatNumbers(value)

    /** 损耗打成一个百分数（两位小数，固定小数点，免得不同语言环境打出逗号）。 */
    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.2f", value * 100.0)

    // ================================================================
    //  生命周期
    // ================================================================

    /**
     * 订阅每 tick 的取电。
     *
     * ⚠️ 这里**不用** `ConditionalSubscriptionHandler`（时序仓用了它）：那个 handler 的条件只在
     * `updateSubscription()` 被显式调到时才重算，而本仓的取电前提会在**我们收不到通知**的时刻变化
     * （唯一塔被别人建起来 / 塔的结构被打散 / 别的维度的塔被拆），条件一旦卡在 false 就再也不会翻回来。
     * 所以老老实实每 tick 调一次 [pullTick]：它的**第一件事就是几个整数比较**，
     * 不需要电时不换任何 TF（见 [pullTick] 的空转保护），代价可以忽略。
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

    /** 本类有 `@Persisted` 字段（容器 + 四个绑定字段），必须自己接 [TieredIOPartMachine] 的字段持有者。 */
    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    /**
     * 部件共享的闸门，返回全局开关 [GTETConfig.partsShareable]（默认 `false` = 禁止共享，与其它仓一致）。
     *
     * **打开配置就恢复串用电风险**：本仓的缓冲与绑定的塔都是每件独立的，
     * 被两个已成型结构共享时两个控制器会同时从同一份缓冲里取电。
     * ⚠️ 该值只在 `BlockPattern#checkPatternAt` 那一刻被读，改配置后要等下一次结构检测才生效。
     */
    override fun canShared(): Boolean = GTETConfig.partsShareable()

    companion object {

        /**
         * 基础损耗的两个端点档位（设定 §6.2「按档位 2% ~ 8%」）：
         * 低端是 `GTValues.IV = 5`（8%）、高端是 `GTValues.MAX = 14`（2%），中间逐档线性。
         * 这里写成字面量而不是引用 `GTValues`：它们要参与 `const` 级别的文档表述，
         * 且 GTM 的 `IV` / `MAX` 是 Java 静态常量、在 Kotlin 的 `const` 里用不了。
         */
        private const val BASE_LOSS_LOW_TIER: Int = 5

        /** 基础损耗高端档位 = `GTValues.MAX`。 */
        private const val BASE_LOSS_HIGH_TIER: Int = 14

        /** 基础损耗在低端档位（IV）的取值：8%。 */
        private const val BASE_LOSS_LOW: Double = 0.08

        /** 基础损耗在高端档位（MAX）的取值：2%。 */
        private const val BASE_LOSS_HIGH: Double = 0.02

        /** 距离税的**免计距离**：128 格 = 8 区块（设定 §6.2 已定）。 */
        private const val DISTANCE_FREE: Double = 128.0

        /** 距离税的**封顶距离**：4096 格（设定 §6.2 已定）。 */
        private const val DISTANCE_CAP: Double = 4096.0

        /**
         * 距离项的分母 = `封顶距离 − 免计距离`。
         * 刻意这么取：让 `d = 4096` 时距离项正好爬到系数上限，与设定档的字面对上（详见 [distanceLoss]）。
         */
        private const val DISTANCE_BILLABLE: Double = DISTANCE_CAP - DISTANCE_FREE

        /** 距离系数上限：+15%（设定 §6.2 已定）。 */
        private const val DISTANCE_LOSS_MAX: Double = 0.15

        /** 总损耗的硬上限（设定：基础损耗与距离税**都不许超过 1**；这里是二者的和再兜一道）。 */
        private const val MAX_LOSS: Double = 0.999999

        /** 百万分之一，用来把损耗变成整数再参与整除（见 [euPerTfAfterLoss]）。 */
        private const val PPM: Long = 1_000_000L

        /**
         * 挂在 [TieredIOPartMachine] 的字段持有者后面，保证父类的
         * `workingEnabled`（`@Persisted`）、`controllerPositions`（`@DescSynced`）
         * 与本类的 `energyContainer` 及四个绑定字段都串得起来。
         */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder = ManagedFieldHolder(
            WirelessEnergyHatchPartMachine::class.java,
            TieredIOPartMachine.MANAGED_FIELD_HOLDER
        )
    }
}
