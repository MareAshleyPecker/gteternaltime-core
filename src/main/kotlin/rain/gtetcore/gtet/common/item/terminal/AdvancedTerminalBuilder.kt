package rain.gtetcore.gtet.common.item.terminal

import appeng.api.networking.IGrid
import com.gregtechceu.gtceu.api.block.IMachineBlock
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine
import com.gregtechceu.gtceu.api.pattern.BlockPattern
import com.gregtechceu.gtceu.api.pattern.MultiblockState
import com.gregtechceu.gtceu.common.block.CoilBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.items.IItemHandler
import org.apache.commons.lang3.ArrayUtils
import rain.gtetcore.gtet.common.item.terminal.AdvancedTerminalBuilder.MAX_BLOCKS_PER_BUILD
import rain.gtetcore.gtet.common.machine.multiblock.modular.ETModularMachine
import rain.gtetcore.gtet.data.lang.AdvancedTerminalLang
import java.lang.reflect.Modifier

/**
 * 高级终端的搭建驱动 —— 潜行右键控制器后走完整条「选图案 → 规划 → 放置/拆除」链路。
 *
 * 设置只在开头读一次（[AdvancedTerminalSettings.read]），偏好表与已规划组表也各读一次，
 * 之后整轮都不再碰物品 NBT。
 *
 * 与「只补空格」的规划器的关系：[StructureBuildPlanner.planCells] 只在
 * 「线圈替换模式 / 拆除模式」下才把**已经有方块**的格子也吐出来，其余情况一律只处理空格子。
 *
 * @author rain fox
 */
object AdvancedTerminalBuilder {

    /**
     * 单次搭建处理的格子数上限。
     *
     * ⚠️ **取舍说明**：这里**不**做分 tick 的渐进式搭建 —— 那需要跨 tick 保存
     * 「还没放完的清单」，玩家中途退出 / 区块卸载 / 控制器被拆都会让这份状态悬空，
     * 反而更容易出问题。改成「一次放完，超过上限就整体拒绝并提示」，
     * 上限内是一 tick 完成，超过就让玩家自己分几次搭（或改大这个常量）。
     */
    const val MAX_BLOCKS_PER_BUILD: Int = 512

    private val FACINGS = arrayOf(
        Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    )
    private val FACINGS_H = arrayOf(
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    )

    /**
     * 一次搭建的结果。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     *
     * @param placed   放下的方块数
     * @param removed  拆掉的方块数
     * @param missing  没找到来源（背包 + AE 都没有）的格子数
     * @param rejected 因为超过 [MAX_BLOCKS_PER_BUILD] 而整体没执行
     */
    data class Result(
        @get:JvmName("placed") val placed: Int,
        @get:JvmName("removed") val removed: Int,
        @get:JvmName("missing") val missing: Int,
        @get:JvmName("rejected") val rejected: Boolean,
    ) {

        companion object {

            /** 什么都没做。 */
            @JvmField
            val NONE = Result(0, 0, 0, false)
        }
    }

    /**
     * 入口：对控制器执行一次搭建（或拆除）。
     *
     * @param player        操作者
     * @param terminal      手上的高级终端（设置 / 偏好 / AE 链接都在它的 NBT 里）
     * @param controllerPos 被潜行右键的方块位置
     */
    @JvmStatic
    fun run(player: Player, terminal: ItemStack, controllerPos: BlockPos): Result {
        val level = player.level()
        val machine: MetaMachine? = MetaMachine.getMachine(level, controllerPos)
        // 不是控制器：什么都不做（交互已经被吃掉，见行为组件）
        val controller = machine as? IMultiController ?: return Result.NONE

        val settings = AdvancedTerminalSettings.read(terminal)
        // 图案取不到（控制器没有图案 / 读不到图案内部字段）：整次调用什么都不做
        val pattern: BlockPattern = selectPattern(controller, settings.module) ?: return Result.NONE
        val state: MultiblockState = controller.multiblockState ?: return Result.NONE

        val formed = controller.isFormed

        // 拆除模式：成型与否都执行，结束后清一次计数缓存，不请求重检
        if (settings.demolition) {
            val result = execute(player, terminal, settings, controller, pattern, state,
                demolition = true,
                replaceCoil = false
            )
            state.clearCache()
            return result
        }
        // 未成型：直接搭
        if (!formed) {
            return execute(player, terminal, settings, controller, pattern, state,
                demolition = false,
                replaceCoil = false
            )
        }
        // 已成型 + 线圈替换：搭完额外让部件重新挂载
        if (machine is WorkableMultiblockMachine && settings.replaceCoil) {
            val result = execute(player, terminal, settings, controller, pattern, state,
                demolition = false,
                replaceCoil = true
            )
            machine.onPartUnload()
            return result
        }
        // 已成型又没开线圈替换：整次调用空转
        return Result.NONE
    }

    /**
     * 选这次的图案。
     *
     * `module == 0` 用控制器当前的图案；`module == N > 0` 取模块化多方块的第 N 档结构，
     * 取不到（不是模块机 / 档位非法 / 实现内部抛异常）就静默退回主结构。
     */
    private fun selectPattern(controller: IMultiController, module: Int): BlockPattern? {
        if (module > 0 && controller is ETModularMachine) {
            try {
                val tiered: BlockPattern = controller.patternForTier(module)
                if (tiered != null) return tiered
            } catch (ignored: Throwable) {
                // 静默退回主结构
            }
        }
        return try {
            controller.pattern
        } catch (ignored: Throwable) {
            null
        }
    }

    // ======================== 规划 + 执行 ========================

    private fun execute(player: Player, terminal: ItemStack, settings: AdvancedTerminalSettings.Snapshot,
                        controller: IMultiController, pattern: BlockPattern, state: MultiblockState,
                        demolition: Boolean, replaceCoil: Boolean): Result {
        val level = player.level()

        val includeOccupied = demolition || replaceCoil
        val options = StructureBuildPlanner.Options(settings.repeatCount, settings.flip, includeOccupied)
        val plan = StructureBuildPlanner.planCells(pattern, state, level, options)

        // 设置与偏好都只解析一次（见类注释）
        val prefs = TerminalSettings.getPreferences(terminal)
        val planned = TerminalSettings.plannedGroups(terminal)

        // 扫描写入：这次的规划结果 ∪ 静态组（静态组由 TerminalSettings 自己保证不被清掉）
        TerminalSettings.cachePlan(terminal, state.pos, level.dimension().location(), idGroups(plan))

        return if (demolition) demolish(player, level, plan, state.pos)
        else place(player, terminal, settings, controller, state, level, plan, prefs, planned, replaceCoil)
    }

    private fun idGroups(plan: StructureBuildPlanner.CellPlan): Map<String, List<String>> {
        val groups = LinkedHashMap<String, List<String>>()
        for ((key, group) in plan.groups) {
            val ids = ArrayList<String>()
            for (candidate in group.candidates) {
                val id = StructureBuildPlanner.itemId(candidate)
                if (id != null) ids.add(id)
            }
            if (ids.isNotEmpty()) groups[key] = ids
        }
        return groups
    }

    // ======================== 放置 ========================

    private fun place(player: Player, terminal: ItemStack, settings: AdvancedTerminalSettings.Snapshot,
                      controller: IMultiController, state: MultiblockState, level: Level,
                      plan: StructureBuildPlanner.CellPlan, prefs: Map<String, String>,
                      planned: Map<String, List<String>>, replaceCoil: Boolean): Result {
        val creative = player.isCreative

        // ① 逐格决议：这一格要放什么（候选加工 → 偏好 → 拆除旧线圈）
        val targets = ArrayList<BlockPos>()
        val wanted = ArrayList<ItemStack>()
        for (cell in plan.cells) {
            val current = level.getBlockState(cell.pos)
            if (cell.occupied) {
                // 只有「线圈替换模式」才碰已有方块，而且只换线圈
                if (!replaceCoil || current.block !is CoilBlock) continue
            }
            val candidates = effectiveCandidates(cell.candidates, cell.required, settings)
            if (candidates.isEmpty()) continue
            // 组键仍用「谓词原始候选」算（面板键与搭建键才能对上），见 TerminalSettings.lookupPreference
            val slot = StructureBuildPlanner.Slot(cell.pos, candidates, cell.groupKey)
            val want = TerminalSettings.resolve(slot, prefs, planned)
            if (want.isEmpty) continue
            targets.add(cell.pos)
            wanted.add(want)
        }

        if (targets.size > MAX_BLOCKS_PER_BUILD) {
            player.displayClientMessage(Component.translatable(AdvancedTerminalLang.BUILD_TOO_MANY,
                targets.size, MAX_BLOCKS_PER_BUILD), true)
            return Result(0, 0, 0, true)
        }
        if (targets.isEmpty()) return Result.NONE

        // ② 来源：先按物品类型聚合需求，再一次性建背包索引 / 一次性问 AE
        val demand = TerminalItemSource.Demand()
        if (!creative) {
            for (stack in wanted) demand.add(stack)
        }
        val bag = if (creative) TerminalItemSource.Inventory.of(null)
        else TerminalItemSource.Inventory.of(player)
        val fromBag = HashMap<TerminalItemSource.Key, Int>()
        if (!creative) {
            for ((key, count) in demand.counts()) {
                // 两张表总是同时写入（见 Demand.add），键一定在
                fromBag[key] = count.coerceAtMost(bag.count(demand.stacks()[key]!!))
            }
        }

        var grid: IGrid? = null
        if (!creative && settings.useAe && TerminalSettings.hasAeLink(terminal)) {
            grid = AeGridLink.resolveGrid(terminal, level, player)
        }
        val ae = TerminalItemSource.AeDemand.of(grid, player)
        ae.simulate(demand.counts(), fromBag, demand.stacks())

        // ③ 逐格放置（顺序与规划一致）
        var placed = 0
        var missing = 0
        val placedPositions = LinkedHashSet<BlockPos>()
        for (i in targets.indices) {
            val pos = targets[i]
            val want = wanted[i]
            val blockItem = want.item as? BlockItem ?: continue

            var fromAe = false
            var reserved: TerminalItemSource.Inventory.SlotRef? = null
            if (!creative) {
                reserved = bag.reserve(want)
                if (reserved == null) {
                    if (!ae.reserve(want)) {
                        missing++
                        continue
                    }
                    fromAe = true
                }
                // 线圈替换：先把旧线圈收进背包（收不进就跳过这一格），再放新的 ——
                // 旧线圈占着那一格，不先挪走的话放置会被「位置不可替换」挡下来
                if (replaceCoil) {
                    val current = level.getBlockState(pos)
                    if (current.block is CoilBlock) {
                        val old = ItemStack(current.block.asItem())
                        if (!canPickUp(player, old)) {
                            if (reserved != null) bag.release(reserved)
                            missing++
                            continue
                        }
                        level.removeBlock(pos, false)
                        give(player, level, pos, old)
                    }
                }
            }

            val context = BlockPlaceContext(level, player, InteractionHand.MAIN_HAND,
                want.copy(), BlockHitResult.miss(player.getEyePosition(0f), Direction.UP, pos))
            val result: InteractionResult = try {
                blockItem.place(context)
            } catch (ignored: Throwable) {
                InteractionResult.FAIL
            }

            // Java 原版是引用比较（`result == InteractionResult.FAIL`），这里保持引用比较
            if (result === InteractionResult.FAIL) {
                // 放失败：把预留还回去，物品一个都不扣
                if (reserved != null) bag.release(reserved)
                missing++
                continue
            }

            if (!creative) {
                if (reserved != null) bag.commit(reserved)
                if (fromAe) ae.commit(want)
            }
            placed++
            placedPositions.add(pos)
        }

        // ④ 整轮结束后才真正从 AE 扣（每种类型一次 MODULATE）
        if (!creative) ae.flush()

        val self: MetaMachine? = controller.self()
        val frontFacing = if (self == null) Direction.NORTH else self.frontFacing
        fixFacings(level, placedPositions, frontFacing)

        return Result(placed, 0, missing, false)
    }

    /**
     * 候选加工：线圈等级 + 无仓室模式。
     *
     * ⚠️ 线圈等级为 0 时**不**砍掉最高档（给出全部档位）—— 这样「面板里选的档」与
     * 「搭建时算出来的组键」天然一致，不需要靠回退匹配兜底。
     *
     * ⚠️ 无仓室模式只作用于「多出来的」仓室格：`required` 表示这一格的候选是
     * 限次谓词的**最小数量**逼出来的（结构必须要有它），那种格子照放 ——
     * 否则一台机器会因为缺必须的仓室而永远不成型。
     */
    private fun effectiveCandidates(candidates: List<ItemStack>, required: Boolean,
                                    settings: AdvancedTerminalSettings.Snapshot): List<ItemStack> {
        if (candidates.isEmpty()) return candidates
        var result = candidates

        if (settings.coilTier > 0 && anyCoil(candidates)) {
            val index = settings.coilTier.coerceAtMost(candidates.size) - 1
            result = listOf(candidates[index.coerceAtLeast(0)])
        }
        // 无仓室模式：默认不主动放仓室（看候选第 1 项是不是多方块部件）
        if (settings.noHatch && !required && isHatch(result[0])) return emptyList()
        return result
    }

    private fun anyCoil(candidates: List<ItemStack>): Boolean {
        for (candidate in candidates) {
            val blockItem = candidate.item as? BlockItem
            if (blockItem != null && blockItem.block is CoilBlock) {
                return true
            }
        }
        return false
    }

    /** 候选第 1 项是不是「多方块部件」（各种仓 / 总线 / 维护仓）。 */
    private fun isHatch(first: ItemStack): Boolean {
        val blockItem = first.item as? BlockItem ?: return false
        val block = blockItem.block
        return block is IMachineBlock && partBlocks().contains(block)
    }

    /** 部件方块表缓存（懒加载，只算一次）。 */
    private var cachedPartBlocks: MutableSet<Block>? = null

    /**
     * 全部「多方块部件」方块。
     *
     * 来源是 [PartAbility] 各能力上登记过的方块（GTM 注册部件时都会登记能力值），
     * 只算一次并缓存。
     *
     * ⚠️ 这是个近似：只在 `PartAbility` 自己的静态字段里找能力实例，
     * 别的模组自建的能力实例收不到。本环境里部件的注册都走 GTM 这套常量。
     */
    private fun partBlocks(): Set<Block> {
        val cached = cachedPartBlocks
        if (cached != null) return cached

        val blocks = HashSet<Block>()
        for (field in PartAbility::class.java.declaredFields) {
            if (!Modifier.isStatic(field.modifiers) || field.type != PartAbility::class.java) continue
            try {
                val ability = field.get(null) as PartAbility?
                if (ability != null) blocks.addAll(ability.allBlocks)
            } catch (ignored: ReflectiveOperationException) {
                // 个别能力取不到不影响其余
            }
        }
        cachedPartBlocks = blocks
        return blocks
    }

    // ======================== 拆除 ========================

    private fun demolish(player: Player, level: Level, plan: StructureBuildPlanner.CellPlan,
                         controllerPos: BlockPos): Result {
        val targets = ArrayList<BlockPos>()
        for (cell in plan.cells) {
            // 谓词为空 / 谓词是空气：候选被剔干净了，直接跳过
            if (cell.candidates.isEmpty()) continue
            // 谓词是控制器：控制器自己那一格永远不拆
            if (cell.pos == controllerPos) continue

            val current = level.getBlockState(cell.pos)
            if (current.isAir) continue
            // 不属于这一格谓词候选的方块一律不动 —— 防误删玩家方块的那道闸
            if (!matches(cell.candidates, current)) continue
            targets.add(cell.pos)
        }

        if (targets.size > MAX_BLOCKS_PER_BUILD) {
            player.displayClientMessage(Component.translatable(AdvancedTerminalLang.BUILD_TOO_MANY,
                targets.size, MAX_BLOCKS_PER_BUILD), true)
            return Result(0, 0, 0, true)
        }

        var removed = 0
        for (pos in targets) {
            val current = level.getBlockState(pos)
            if (current.isAir) continue
            val drop = ItemStack(current.block.asItem())
            if (level.removeBlock(pos, false)) {
                removed++
                give(player, level, pos, drop)
            }
        }
        return Result(0, removed, 0, false)
    }

    private fun matches(candidates: List<ItemStack>, current: BlockState): Boolean {
        val block = current.block
        for (candidate in candidates) {
            val blockItem = candidate.item as? BlockItem
            if (blockItem != null && blockItem.block === block) {
                return true
            }
        }
        return false
    }

    // ======================== 掉落 / 背包 ========================

    /** 拆下来的方块给玩家：先塞背包，塞不下就掉在原地（不会凭空消失）。 */
    private fun give(player: Player, level: Level, pos: BlockPos, stack: ItemStack) {
        if (stack.isEmpty) return
        val leftover = stack.copy()
        if (!player.addItem(leftover) && !leftover.isEmpty) {
            Block.popResource(level, pos, leftover)
        }
    }

    /** 先模拟塞一遍再决定要不要真塞（塞不下就什么都不做）。 */
    private fun canPickUp(player: Player, stack: ItemStack): Boolean {
        val handler: IItemHandler = player.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null)
            ?: return false
        var probe = stack.copy()
        var i = 0
        while (i < handler.slots && !probe.isEmpty) {
            probe = handler.insertItem(i, probe, true)
            i++
        }
        return probe.isEmpty
    }

    // ======================== 朝向修正 ========================

    /**
     * 让线缆 / 仓室这些带朝向的方块朝向空位（与 GTCEu 自己的自动搭建一致）。
     *
     * 两处省着来（不改变放置结果）：
     * 1. 方块没有 `FACING` / `HORIZONTAL_FACING` 属性时直接跳过 ——
     *    一次 `getBlockEntity` 都不查；
     * 2. 有属性时整格只查一次 `getBlockEntity`（原来每次试探都要查，最多 6 次）。
     */
    private fun fixFacings(level: Level, placed: Collection<BlockPos>, frontFacing: Direction) {
        for (pos in placed) {
            val state = level.getBlockState(pos)
            val property: Property<Direction>
            val order: Array<Direction>
            if (state.hasProperty(BlockStateProperties.FACING)) {
                property = BlockStateProperties.FACING
                order = ArrayUtils.addAll(arrayOf(frontFacing), *FACINGS)
            } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                property = BlockStateProperties.HORIZONTAL_FACING
                order = if (frontFacing.axis == Direction.Axis.Y) FACINGS_H
                else ArrayUtils.addAll(arrayOf(frontFacing), *FACINGS_H)
            } else {
                continue
            }

            val blockEntity = level.getBlockEntity(pos)
            val machine: MetaMachine? =
                if (blockEntity is IMachineBlockEntity) blockEntity.metaMachine else null

            var found: Direction? = null
            for (direction in order) {
                if (isFacingUsable(level, pos, direction, machine, placed)) {
                    found = direction
                    break
                }
            }
            if (found == null) found = Direction.NORTH
            // Java 原版是引用比较（`state.getValue(property) != found`），这里保持引用比较
            if (state.getValue(property) !== found) {
                level.setBlock(pos, state.setValue(property, found), 3)
            }
        }
    }

    private fun isFacingUsable(level: Level, pos: BlockPos, direction: Direction,
                               machine: MetaMachine?, placed: Collection<BlockPos>): Boolean {
        val neighbor = pos.relative(direction)
        // 机器类方块：朝向要合法，且前方为空
        if (machine != null) {
            return level.isEmptyBlock(neighbor) && machine.isFacingValid(direction)
        }
        // 普通方块：前方要么是空气，要么是我们这次刚放下的（那就算被挡）
        return !placed.contains(neighbor) && level.isEmptyBlock(neighbor)
    }
}