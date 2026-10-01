package rain.gtetcore.gtet.common.item.terminal

import appeng.api.networking.IGrid
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.items.IItemHandler
import rain.gtetcore.gtet.common.item.terminal.TerminalItemSource.Inventory.Companion.of

/**
 * 方块来源（背包 / AE）—— 一次搭建里把「查」和「扣」都做成批量操作。
 *
 * 两件事：
 * 1. [Inventory]：循环**前**把玩家背包扫一遍建索引（含背包里各物品自带的容器），
 *    之后每种物品 O(1) 查存量，扣的时候直接落到具体槽位 —— 不再每格重扫背包；
 * 2. [AeDemand]：按**物品类型**聚合需求，每种类型只做 1 次 SIMULATE
 *    （[AeGridLink.canExtract]）与 1 次 MODULATE（[AeGridLink.extract]），
 *    并在整轮里复用同一个 `IActionSource`。
 *
 * 扣物品的时机仍然是「放置成功之后」：预留 → 放置 → 成功则 commit、失败则 release。
 *
 * @author rain fox
 */
object TerminalItemSource {

    /** 嵌套容器只往下找一层（背包里那件物品自带的容器）。 */
    private const val MAX_NESTING = 1

    /**
     * 物品键：与 [ItemStack.isSameItemSameTags] 等价的哈希键（物品同一性 + NBT 深比较）。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     */
    data class Key(
        @get:JvmName("item") val item: Item,
        @get:JvmName("tag") val tag: CompoundTag?,
    ) {

        companion object {

            /** 物品栈的键；空栈为 `null`。 */
            @JvmStatic
            fun of(stack: ItemStack): Key? {
                return if (stack.isEmpty) null else Key(stack.item, stack.tag)
            }
        }
    }

    // ======================== 背包索引 ========================

    /** 玩家背包（含一层嵌套容器）的索引。 */
    class Inventory private constructor() {

        private val byKey = LinkedHashMap<Key, MutableList<SlotRef>>()
        private val totals = HashMap<Key, Int>()
        private var readCount = 0

        private fun scan(handler: IItemHandler, depth: Int) {
            val slots = handler.slots
            for (i in 0 until slots) {
                val stack = handler.getStackInSlot(i)
                readCount++
                if (stack.isEmpty) continue

                // 嵌套容器：先看这一件物品自带的容器（只往下找一层）
                if (depth < MAX_NESTING) {
                    val nested = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null)
                    if (nested != null && nested !== handler) scan(nested, depth + 1)
                }

                val key = Key.of(stack) ?: continue
                byKey.getOrPut(key) { ArrayList() }.add(SlotRef.create(key, handler, i, stack.count))
                totals[key] = (totals[key] ?: 0) + stack.count
            }
        }

        /** 这种物品一共还能拿出几个（已扣掉本轮预留/已扣的）。 */
        fun count(wanted: ItemStack): Int {
            val key = Key.of(wanted) ?: return 0
            return totals[key] ?: 0
        }

        /** 索引建立时的 `getStackInSlot` 调用次数（诊断用）。 */
        fun slotReads(): Int = readCount

        /**
         * 预留一个（**不**真扣）：放置成功后再 [commit]，失败就 [release]。
         *
         * @return 预留到的槽位；没有存量时为 `null`
         */
        fun reserve(wanted: ItemStack): SlotRef? {
            val key = Key.of(wanted) ?: return null
            val slots = byKey[key] ?: return null
            for (ref in slots) {
                if (ref.remainingCount > 0) {
                    ref.remainingCount--
                    totals[key] = (totals[key] ?: 0) - 1
                    return ref
                }
            }
            return null
        }

        /** 放置成功：真正从槽位里扣掉一个。 */
        fun commit(ref: SlotRef) {
            ref.handler.extractItem(ref.slotIndex, 1, false)
        }

        /** 放置失败：把预留还回去（这一格没消耗物品，也不需要再读一次槽位）。 */
        fun release(ref: SlotRef) {
            ref.remainingCount++
            totals[ref.key] = (totals[ref.key] ?: 0) + 1
        }

        /**
         * 一个可扣的槽位，以及它还剩几个可扣。
         *
         * ⚠️ 构造器仍是 `private`（与原 Java 一致：Java 侧只能从 [Inventory.reserve] 拿到它）：
         * 建索引那一步走伴生对象里的 `internal` 工厂 —— Kotlin 的外层类看不到嵌套类的
         * `private` 成员，而 [Inventory] 的预留 / 提交 / 归还必须直接改这几个计数。
         */
        class SlotRef private constructor(
            internal val key: Key,
            internal val handler: IItemHandler,
            internal val slotIndex: Int,
            internal var remainingCount: Int,
        ) {

            fun index(): Int = slotIndex

            fun remaining(): Int = remainingCount

            companion object {

                /** 只给 [Inventory.scan] 建索引用。 */
                internal fun create(key: Key, handler: IItemHandler, slotIndex: Int, remaining: Int): SlotRef {
                    return SlotRef(key, handler, slotIndex, remaining)
                }
            }
        }

        companion object {

            /**
             * 建索引。每个槽位只调**一次** `getStackInSlot`。
             *
             * @param player 玩家；`null` 时返回空索引
             */
            @JvmStatic
            fun of(player: Player?): Inventory {
                if (player == null) return Inventory()
                return ofHandler(player.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null))
            }

            /**
             * 从一个物品栏处理器建索引（[of] 最终也走这里；也方便脱离玩家单独测）。
             */
            @JvmStatic
            fun ofHandler(handler: IItemHandler?): Inventory {
                val inventory = Inventory()
                if (handler != null) inventory.scan(handler, 0)
                return inventory
            }
        }
    }

    // ======================== AE 需求聚合 ========================

    /**
     * 一轮搭建的 AE 取料计划。
     *
     * 用法：[simulate] 一次性为**每种**物品类型做一次模拟提取；
     * 逐格放置时用 [reserve] 领额度、成功后 [commit]；
     * 整轮结束调 [flush]，把实际用掉的量**每种类型一次**真扣掉。
     */
    class AeDemand private constructor(
        private val grid: IGrid?,
        private val player: Player?,
        private val ops: AeOps,
    ) {

        private val allowance = LinkedHashMap<Key, Int>()
        private val taken = LinkedHashMap<Key, Int>()
        private val samples = LinkedHashMap<Key, ItemStack>()

        /** 这一轮到底能不能动 AE：没网络、或者玩家在创造模式（创造不消耗物品）都不动。 */
        private fun usable(): Boolean {
            return grid != null && (player == null || !player.isCreative)
        }

        /**
         * 按类型聚合需求：每种类型最多一次 SIMULATE。
         *
         * @param demand  物品键 → 总需求
         * @param fromBag 物品键 → 背包能提供的数量（剩下的缺口才问 AE 要）
         * @param stacks  物品键 → 该键的代表物品栈
         */
        fun simulate(demand: Map<Key, Int>, fromBag: Map<Key, Int>, stacks: Map<Key, ItemStack>) {
            if (!usable()) return
            val activeGrid = grid ?: return
            for ((key, value) in demand) {
                val shortfall = value - (fromBag[key] ?: 0)
                if (shortfall <= 0) continue
                val sample = stacks[key]
                if (sample == null || sample.isEmpty) continue
                samples[key] = sample
                if (ops.canExtract(activeGrid, player, sample, shortfall)) {
                    allowance[key] = shortfall
                }
            }
        }

        /** 领一个额度（不真扣）。 */
        fun reserve(wanted: ItemStack): Boolean {
            val key = Key.of(wanted) ?: return false
            val left = allowance[key] ?: 0
            if (left <= 0) return false
            allowance[key] = left - 1
            return true
        }

        /** 放置成功：记一笔，等整轮结束后一次性真扣。 */
        fun commit(wanted: ItemStack) {
            val key = Key.of(wanted) ?: return
            taken[key] = (taken[key] ?: 0) + 1
        }

        /** 整轮结束：每种用过 AE 的类型各一次 MODULATE。 */
        fun flush() {
            if (!usable()) return
            val activeGrid = grid ?: return
            for ((key, count) in taken) {
                val sample = samples[key]
                if (sample != null && count > 0) {
                    ops.extract(activeGrid, player, sample, count)
                }
            }
            taken.clear()
        }

        /** 是否绑定了可用的 AE 网络。 */
        fun linked(): Boolean = grid != null

        companion object {

            /** @param grid 已解析出的网络；`null` 表示没绑定 / 解析不出来，这个计划取不到任何东西 */
            @JvmStatic
            fun of(grid: IGrid?, player: Player?): AeDemand {
                return of(grid, player, AeOps.DEFAULT)
            }

            /** 同上，但换一套取料入口（默认那套就是 [AeGridLink]）。 */
            @JvmStatic
            fun of(grid: IGrid?, player: Player?, ops: AeOps): AeDemand {
                return AeDemand(grid, player, ops)
            }
        }
    }

    /**
     * AE 取料的两个入口。
     *
     * 存在的意义是把「怎么取」与「取多少」分开：[AeDemand] 只管按类型聚合，
     * 真正的网络操作永远只有这两下。默认实现直接转发到 [AeGridLink]。
     *
     * ⚠️ 两个形参保持可空：原 Java 侧没有非空标注，默认实现也是把 `null` 原样转给
     * [AeGridLink]（`grid` 为 `null` 时它自己返回"取不到"）。写成非空会插进
     * `checkNotNullParameter`，把「传 null 走 null 分支」变成抛 NPE。
     */
    interface AeOps {

        /** 模拟提取：够不够。 */
        fun canExtract(grid: IGrid?, player: Player?, wanted: ItemStack, count: Int): Boolean

        /** 真提取。 */
        fun extract(grid: IGrid?, player: Player?, wanted: ItemStack, count: Int): ItemStack

        companion object {

            /** 默认：本终端自带链接的 AE 实现（只取不存）。 */
            @JvmField
            val DEFAULT: AeOps = object : AeOps {

                override fun canExtract(grid: IGrid?, player: Player?, wanted: ItemStack, count: Int): Boolean {
                    // 与原 Java 一致：player 为 null 时由 AeGridLink 的非空参数检查抛 NPE
                    return AeGridLink.canExtract(grid, player!!, wanted, count)
                }

                override fun extract(grid: IGrid?, player: Player?, wanted: ItemStack, count: Int): ItemStack {
                    return AeGridLink.extract(grid, player!!, wanted, count)
                }
            }
        }
    }

    // ======================== 物品键收集 ========================

    /** 物品键 → 该键的代表栈（AE 取料与提示都要用到栈本身）。 */
    class Demand {

        private val counts = LinkedHashMap<Key, Int>()
        private val stacks = LinkedHashMap<Key, ItemStack>()

        fun add(stack: ItemStack) {
            val key = Key.of(stack) ?: return
            counts[key] = (counts[key] ?: 0) + 1
            stacks.putIfAbsent(key, stack)
        }

        fun counts(): Map<Key, Int> = counts

        fun stacks(): Map<Key, ItemStack> = stacks

        fun total(): Int {
            var sum = 0
            for (value in counts.values) sum += value
            return sum
        }
    }
}