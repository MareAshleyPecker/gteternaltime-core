package rain.gtetcore.gtet.common.item.terminal

import appeng.api.networking.IGrid
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.pattern.BlockPattern
import com.gregtechceu.gtceu.api.pattern.MultiblockState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.BlockHitResult
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.items.IItemHandler
import org.apache.commons.lang3.ArrayUtils

/**
 * 结构搭建执行器 —— 按 [StructureBuildPlanner] 的规划把方块放到世界上。
 *
 * 骨架取自 GTCEu `BlockPattern#autoBuild`（放置 + 朝向修正那两段），
 * 两处不同：
 *
 * 1. 方块来源是「背包 → 已链接的 AE 网络」，AE 只提取；
 * 2. 每格放什么由终端里的**分级组偏好**决定（见 [TerminalSettings.resolve]）。
 *
 * @author rain fox
 */
object StructureBuilder {

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
     */
    data class Result(
        @get:JvmName("placed") val placed: Int,
        @get:JvmName("missing") val missing: Int,
        @get:JvmName("missingItems") val missingItems: List<ItemStack>,
        @get:JvmName("aeLinked") val aeLinked: Boolean,
    )

    /**
     * 对控制器执行一次自动搭建。
     *
     * @param player   操作者
     * @param pattern  控制器的结构
     * @param state    控制器的 [MultiblockState]
     * @param terminal 手上的高级终端（设置与 AE 链接都在它的 NBT 里）
     */
    @JvmStatic
    fun build(player: Player, pattern: BlockPattern, state: MultiblockState, terminal: ItemStack): Result {
        val level = player.level()
        val grid: IGrid? = if (player.isCreative) null else AeGridLink.resolveGrid(terminal, level, player)

        val plan = StructureBuildPlanner.plan(pattern, state, level)

        val placed = HashSet<BlockPos>()
        val missing = ArrayList<ItemStack>()

        for (slot in plan.slots) {
            val wanted = TerminalSettings.resolve(terminal, slot)
            val blockItem = wanted.item as? BlockItem
            if (wanted.isEmpty || blockItem == null) continue

            val source = Source.find(player, wanted, grid)
            if (source == null) {
                missing.add(wanted)
                continue
            }

            val context = BlockPlaceContext(
                level, player, InteractionHand.MAIN_HAND,
                source.stack, BlockHitResult.miss(player.getEyePosition(0f), Direction.UP, slot.pos)
            )
            val result = blockItem.place(context)
            if (result == InteractionResult.FAIL) {
                missing.add(wanted)
                continue
            }

            source.consume()
            placed.add(slot.pos)
        }

        // 朝向修正：让线缆/仓室等朝向空位（与 GTCEu autoBuild 一致）
        val frontFacing = if (state.controller == null) Direction.NORTH
        else state.controller.self().frontFacing
        for (pos in ArrayList(placed)) {
            fixFacing(level, pos, frontFacing, placed)
        }

        // java.util.List.copyOf 必须写全限定名：Kotlin 的 kotlin.collections.List 上没有这个静态方法
        return Result(placed.size, missing.size, java.util.List.copyOf(missing), grid != null)
    }

    /** 把缺失的物品按种类合并，便于提示玩家。 */
    @JvmStatic
    fun summarizeMissing(missing: List<ItemStack>): Map<ItemStack, Int> {
        val summary = LinkedHashMap<ItemStack, Int>()
        for (stack in missing) {
            var merged = false
            for (entry in summary.entries) {
                if (ItemStack.isSameItemSameTags(entry.key, stack)) {
                    entry.setValue(entry.value + 1)
                    merged = true
                    break
                }
            }
            if (!merged) summary[stack.copy()] = 1
        }
        return summary
    }

    // ======================== 方块来源 ========================

    /**
     * 一份待放置的方块，以及放好之后怎么扣。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名。
     */
    private data class Source(
        @get:JvmName("stack") val stack: ItemStack,
        @get:JvmName("handler") val handler: IItemHandler?,
        @get:JvmName("slot") val slot: Int,
        @get:JvmName("grid") val grid: IGrid?,
        @get:JvmName("player") val player: Player?,
    ) {

        /** 放置成功后扣物品。 */
        fun consume() {
            val handler = handler
            if (handler != null && slot >= 0) {
                handler.extractItem(slot, 1, false)
            } else {
                val grid = grid
                val player = player
                if (grid != null && player != null) {
                    AeGridLink.extract(grid, player, stack, 1)
                }
            }
        }

        companion object {

            @JvmStatic
            fun find(player: Player, wanted: ItemStack, grid: IGrid?): Source? {
                if (player.isCreative) {
                    return Source(wanted.copy(), null, -1, null, null)
                }

                val handler = player.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null)
                for (i in 0 until handler.slots) {
                    val inSlot = handler.getStackInSlot(i)
                    if (!inSlot.isEmpty && ItemStack.isSameItemSameTags(inSlot, wanted)) {
                        return Source(inSlot.copy(), handler, i, null, null)
                    }
                }

                // AE：先只做模拟检查，放成功后才真正提取（避免放了失败还白扣）
                if (AeGridLink.canExtract(grid, player, wanted, 1)) {
                    return Source(wanted.copy(), null, -1, grid, player)
                }
                return null
            }
        }
    }

    // ======================== 朝向修正 ========================

    private fun fixFacing(level: Level, pos: BlockPos, frontFacing: Direction, placed: Set<BlockPos>) {
        val state = level.getBlockState(pos)
        if (state.hasProperty(BlockStateProperties.FACING)) {
            tryFacings(
                level, pos, state, BlockStateProperties.FACING,
                ArrayUtils.addAll(arrayOf(frontFacing), *FACINGS), placed
            )
        } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            val order: Array<Direction> = if (frontFacing.axis == Direction.Axis.Y) FACINGS_H
            else ArrayUtils.addAll(arrayOf(frontFacing), *FACINGS_H)
            tryFacings(level, pos, state, BlockStateProperties.HORIZONTAL_FACING, order, placed)
        }
    }

    private fun tryFacings(level: Level, pos: BlockPos, state: BlockState, property: Property<Direction>,
                           order: Array<Direction>, placed: Set<BlockPos>) {
        var found: Direction? = null
        for (direction in order) {
            if (isFacingUsable(level, pos, direction, placed)) {
                found = direction
                break
            }
        }
        level.setBlock(pos, state.setValue(property, found ?: Direction.NORTH), 3)
    }

    private fun isFacingUsable(level: Level, pos: BlockPos, direction: Direction, placed: Set<BlockPos>): Boolean {
        val neighbor = pos.relative(direction)

        // 机器类方块：朝向要合法，且前方为空
        val blockEntity = level.getBlockEntity(pos)
        if (blockEntity is IMachineBlockEntity) {
            val machine: MetaMachine? = blockEntity.metaMachine
            if (machine != null) {
                return level.isEmptyBlock(neighbor) && machine.isFacingValid(direction)
            }
        }

        // 普通方块：前方要么是空气，要么是我们这次刚放下的（那就算被挡）
        return !placed.contains(neighbor) && level.isEmptyBlock(neighbor)
    }
}