package rain.gtetcore.gtet.common.item.timeflow

import com.gregtechceu.gtceu.api.item.ComponentItem
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import rain.gtetcore.gtet.api.timeflow.ETTimeFlow
import rain.gtetcore.gtet.api.timeflow.TimeFlowTowers

/**
 * 时序之瓶的数据层 —— **全部状态都写在物品 NBT 里**。
 *
 * ## 为什么手写 NBT
 * 物品用不了 LDLib 的 `ManagedFieldHolder`（它要求 `Class<? extends IManaged>`，`Item` 不是），
 * `IElectricItem` 又是 EU 语义硬编码。所以自定义单位「时间流」只能手写 NBT + `IAddInformation`。
 *
 * ## NBT 键
 *
 * | 键 | 类型 | 含义 |
 * |---|---|---|
 * | `time_flow` | long | 瓶内 TF |
 * | `capacity_tier` | int | 容量档位 1~3（L1 / L2 / L3） |
 * | `tf_tower_x` / `tf_tower_y` / `tf_tower_z` | int | 绑定主控塔的坐标 |
 * | `tf_tower_face` | String | `Direction#getName()`，与 GTM 闪存的 `face` 同构 |
 * | `tf_tower_dim` | String | `level.dimension().location().toString()`，与 GTM 闪存的 `dim` 同构 |
 *
 * 键名整体照 GTM 闪存的 `targetX/targetY/targetZ + face + dim` 那套写（含维度），只是加了 `tf_tower_` 前缀
 * —— 闪存的键是没有命名空间隔离的裸键，带前缀免得跟别的实现撞名。
 */
object TimeBottleData {

    // ======================== NBT 键 ========================

    /** 瓶内 TF（long）。 */
    const val KEY_TIME_FLOW = "time_flow"

    /** 容量档位（int，1~3）。 */
    const val KEY_CAPACITY_TIER = "capacity_tier"

    /** 绑定塔的 X 坐标（int）。 */
    const val KEY_TOWER_X = "tf_tower_x"

    /** 绑定塔的 Y 坐标（int）。 */
    const val KEY_TOWER_Y = "tf_tower_y"

    /** 绑定塔的 Z 坐标（int）。 */
    const val KEY_TOWER_Z = "tf_tower_z"

    /** 绑定塔的交互面（String，`Direction#getName()`）。 */
    const val KEY_TOWER_FACE = "tf_tower_face"

    /** 绑定塔所在维度（String，`minecraft:overworld` 这种）。 */
    const val KEY_TOWER_DIM = "tf_tower_dim"

    // ======================== 身份判定 ========================

    /**
     * 是不是时序之瓶。
     *
     * 按**行为组件**判定而不是按注册表条目判定 —— 免得数据层与 [rain.gtetcore.gtet.common.data.item.ETItems] 互相引用。
     */
    @JvmStatic
    fun isTimeBottle(stack: ItemStack): Boolean {
        val item = stack.item
        return item is ComponentItem && item.components.contains(TimeBottleBehavior.INSTANCE)
    }

    // ======================== 档位与容量 ========================

    /** 容量档位；没写过时按最低档 L1。越界值夹到 `[1, 3]`。 */
    @JvmStatic
    fun getTier(stack: ItemStack): Int {
        val raw = stack.tag?.getInt(KEY_CAPACITY_TIER) ?: ETTimeFlow.BOTTLE_TIER_MIN
        return raw.coerceIn(ETTimeFlow.BOTTLE_TIER_MIN, ETTimeFlow.BOTTLE_TIER_MAX)
    }

    /** 当前容量上限（TF）。 */
    @JvmStatic
    fun getCapacity(stack: ItemStack): Long = ETTimeFlow.bottleCapacity(getTier(stack))

    /**
     * 设置档位。**只抬容量上限**，不动 [KEY_TIME_FLOW]。
     *
     * @return 档位真的变了才返回 `true`
     */
    @JvmStatic
    fun setTier(stack: ItemStack, tier: Int): Boolean {
        val clamped = tier.coerceIn(ETTimeFlow.BOTTLE_TIER_MIN, ETTimeFlow.BOTTLE_TIER_MAX)
        if (clamped == getTier(stack)) return false
        stack.getOrCreateTag().putInt(KEY_CAPACITY_TIER, clamped)
        return true
    }

    /**
     * **升级入口** —— 升一档，容量上限变大，**瓶内 TF 不丢、物品不换**（设定 §5「一件物品、按升级提升」）。
     *
     * 升级件的具体形态（物品 / 配方）本期不做，将来由升级件调用本方法即可。
     *
     * @return 真的升上去了才返回 `true`（已经 L3 时返回 `false`）
     */
    @JvmStatic
    fun upgrade(stack: ItemStack): Boolean = setTier(stack, getTier(stack) + 1)

    // ======================== 瓶内 TF ========================

    /** 瓶内 TF。 */
    @JvmStatic
    fun getTimeFlow(stack: ItemStack): Long = stack.tag?.getLong(KEY_TIME_FLOW) ?: 0L

    /** 写入瓶内 TF；自动夹到 `0..容量上限`，返回夹取后的实际值。 */
    @JvmStatic
    fun setTimeFlow(stack: ItemStack, amount: Long): Long {
        val clamped = amount.coerceIn(0L, getCapacity(stack))
        stack.getOrCreateTag().putLong(KEY_TIME_FLOW, clamped)
        return clamped
    }

    // ======================== 绑定主控塔 ========================

    /** 绑定到某个坐标上的主控塔（维度 + 坐标 + 交互面，一起写进瓶 NBT）。 */
    @JvmStatic
    fun bindTower(stack: ItemStack, level: Level, pos: BlockPos, face: Direction?) {
        val tag = stack.getOrCreateTag()
        tag.putInt(KEY_TOWER_X, pos.x)
        tag.putInt(KEY_TOWER_Y, pos.y)
        tag.putInt(KEY_TOWER_Z, pos.z)
        tag.putString(KEY_TOWER_FACE, (face ?: Direction.NORTH).getName())
        tag.putString(KEY_TOWER_DIM, level.dimension().location().toString())
    }

    /** 解绑；顺手清掉全部绑定键。 */
    @JvmStatic
    fun unbindTower(stack: ItemStack) {
        val tag = stack.tag ?: return
        tag.remove(KEY_TOWER_X)
        tag.remove(KEY_TOWER_Y)
        tag.remove(KEY_TOWER_Z)
        tag.remove(KEY_TOWER_FACE)
        tag.remove(KEY_TOWER_DIM)
    }

    /** 读绑定的塔；没绑过 / 维度串坏了返回 `null`。 */
    @JvmStatic
    fun getBoundTower(stack: ItemStack): GlobalPos? {
        val tag = stack.tag ?: return null
        if (!tag.contains(KEY_TOWER_DIM)) return null
        val dim = ResourceLocation.tryParse(tag.getString(KEY_TOWER_DIM)) ?: return null
        return GlobalPos.of(
            ResourceKey.create(Registries.DIMENSION, dim),
            BlockPos(tag.getInt(KEY_TOWER_X), tag.getInt(KEY_TOWER_Y), tag.getInt(KEY_TOWER_Z))
        )
    }

    /** 读绑定时记下的交互面；没绑过 / 名字解析不出来返回 `null`。 */
    @JvmStatic
    fun getBoundFace(stack: ItemStack): Direction? {
        val name = stack.tag?.getString(KEY_TOWER_FACE) ?: return null
        return Direction.byName(name)
    }

    // ======================== 闪存范式手势 ========================

    /**
     * 闪存范式手势（**非潜行**右键）= 绑定。
     *
     * 将来主控塔实现 GTM 的 `IDataStickInteractable` 时，照抄这一段转发即可：
     * ```java
     * public InteractionResult onDataStickUse(Player player, ItemStack held) {
     *     return TimeBottleData.onDataStickUse(player, held, this.getLevel(), this.getPos(), this.getFrontFacing());
     * }
     * ```
     * 语义与 GTM 的 `WirelessTransmitterCover#onDataStickUse` 同构：**被右键的塔把自己的坐标写进手里那个物品的 NBT**。
     *
     * ⚠️ GTM 的闪存分发只认「覆盖物」与「方块实体是机器」两种情况（`DataItemBehavior`），
     * 所以这个回调今天还不会自己触发 —— 塔没做之前它只是个留好的入口。
     */
    @JvmStatic
    fun onDataStickUse(
        player: Player,
        stack: ItemStack,
        towerLevel: Level,
        towerPos: BlockPos,
        face: Direction?
    ): InteractionResult {
        if (!isTimeBottle(stack)) return InteractionResult.PASS
        if (!towerLevel.isClientSide) {
            bindTower(stack, towerLevel, towerPos, face)
            player.displayClientMessage(
                Component.translatable(TimeBottleLang.BOUND, towerPos.x, towerPos.y, towerPos.z), true
            )
        }
        return InteractionResult.sidedSuccess(towerLevel.isClientSide)
    }

    /**
     * 闪存范式手势（**潜行**右键）= 解绑。转发写法同 [onDataStickUse]，对应 `onDataStickShiftUse`。
     */
    @JvmStatic
    fun onDataStickShiftUse(
        player: Player,
        stack: ItemStack,
        towerLevel: Level,
        towerPos: BlockPos,
        face: Direction?
    ): InteractionResult {
        if (!isTimeBottle(stack)) return InteractionResult.PASS
        if (!towerLevel.isClientSide) {
            unbindTower(stack)
            player.displayClientMessage(Component.translatable(TimeBottleLang.UNBOUND), true)
        }
        return InteractionResult.sidedSuccess(towerLevel.isClientSide)
    }

    // ======================== 扣费来源（同维度扣塔 / 跨维度只用瓶内） ========================

    /** TF 的扣费来源。 */
    enum class PaymentSource {
        /** 同维度 + 塔所在区块已加载 ⇒ 直接从塔扣，瓶内 TF 不动。 */
        BOUND_TOWER,

        /** 未绑定 / 跨维度 / 塔的区块没加载 ⇒ 只能用瓶内 TF。 */
        BOTTLE_ONLY
    }

    /**
     * 判定这次该从哪里扣 TF（设定 §5）。
     *
     * 跨维度**一律**回到瓶内 TF —— 想跨维度就得先在塔旁充装再拎过去，搬运有成本。
     */
    @JvmStatic
    fun resolvePaymentSource(bottle: ItemStack, player: Player): PaymentSource {
        val bound = getBoundTower(bottle) ?: return PaymentSource.BOTTLE_ONLY
        val level = player.level()
        if (bound.dimension() != level.dimension()) return PaymentSource.BOTTLE_ONLY
        // 塔所在区块没加载就不认这座塔（不破例做强加载）
        if (!level.isLoaded(bound.pos())) return PaymentSource.BOTTLE_ONLY
        return PaymentSource.BOUND_TOWER
    }

    /**
     * 支付 [amount] TF：同维度优先从绑定的塔扣，否则从瓶内扣。
     *
     * ⚠️ 塔本期不做，[TimeFlowTowers.find] 恒返回 `null`，所以 `BOUND_TOWER` 这一档眼下必然回落到瓶内。
     * 接口与字段先留好，塔落地后本方法不用改。
     *
     * @return 付得起并已扣掉才返回 `true`（付不起时**一分不扣**）
     */
    @JvmStatic
    fun tryPay(bottle: ItemStack, player: Player, amount: Long): Boolean {
        if (amount <= 0L) return true

        if (resolvePaymentSource(bottle, player) == PaymentSource.BOUND_TOWER) {
            val bound = getBoundTower(bottle)
            if (bound != null) {
                val tower = TimeFlowTowers.find(player.level(), bound.pos())
                // 先问余额再扣，避免「扣了一半发现不够」
                if (tower != null && tower.canUseTimeFlow(player) && tower.hasTimeFlow(amount)) {
                    return tower.extractTimeFlow(amount) >= amount
                }
            }
            // 塔不在 / 无权限 / 余额不足 ⇒ 回落到瓶内 TF
        }
        return TimeBottleNbtStorage.of(bottle).extractTimeFlow(amount) >= amount
    }

    // ======================== 只读视图 ========================

    /** 取一份 [ITimeFlowStorage] 视图；写进去的东西立刻落在物品 NBT 上。 */
    @JvmStatic
    fun storageOf(stack: ItemStack): TimeBottleNbtStorage = TimeBottleNbtStorage.of(stack)
}
