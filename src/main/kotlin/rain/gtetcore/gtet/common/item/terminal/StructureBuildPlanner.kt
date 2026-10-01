package rain.gtetcore.gtet.common.item.terminal

import com.gregtechceu.gtceu.api.pattern.BlockPattern
import com.gregtechceu.gtceu.api.pattern.MultiblockState
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection
import com.lowdragmc.lowdraglib.utils.BlockInfo
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.registries.ForgeRegistries
import org.apache.commons.lang3.ArrayUtils
import java.lang.reflect.Field

/**
 * 结构规划器 —— 把 [BlockPattern] 摊成「每个格子要放什么」的清单。
 *
 * 逻辑取自 GTCEu 自己的 `BlockPattern#autoBuild`（候选挑选那段），
 * 但不直接改世界：只算出每个位置的世界坐标与**候选方块列表**，
 * 并把这些候选按「同一组候选」归类 —— 这样线圈、聚变玻璃、火箱、分级机壳
 * 都会被自动识别成一个个「分级组」，玩家在终端里按组挑具体方块即可，
 * 不需要为每种方块硬编码等级枚举。
 *
 * `BlockPattern` 的 `blockMatches` 等字段是 protected，用反射读取。
 *
 * @author rain fox
 */
object StructureBuildPlanner {

    /** pattern 内部结构（反射缓存）。 */
    private val F_BLOCK_MATCHES: Field = patternField("blockMatches")
    private val F_FINGER: Field = patternField("fingerLength")
    private val F_THUMB: Field = patternField("thumbLength")
    private val F_PALM: Field = patternField("palmLength")
    private val F_CENTER_OFFSET: Field = patternField("centerOffset")

    /** 取 `BlockPattern` 的私有字段并置为可访问；拿不到就抛 `ExceptionInInitializerError`（与原来的静态块一致）。 */
    private fun patternField(name: String): Field {
        return try {
            BlockPattern::class.java.getDeclaredField(name).also { it.isAccessible = true }
        } catch (e: ReflectiveOperationException) {
            throw ExceptionInInitializerError(e)
        }
    }

    /**
     * 一个待放置（或已符合）的格子。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     */
    data class Slot(
        @get:JvmName("pos") val pos: BlockPos,
        @get:JvmName("candidates") val candidates: List<ItemStack>,
        @get:JvmName("groupKey") val groupKey: String?,
    ) {

        fun isEmpty(): Boolean {
            return candidates.isEmpty()
        }
    }

    /**
     * 一组共享同一候选列表的格子（通常就是一个「分级组」）。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     */
    data class Group(
        @get:JvmName("key") val key: String,
        @get:JvmName("candidates") val candidates: List<ItemStack>,
    ) {

        /** 是否真的有多档可选（多档才是「分级方块」）。 */
        fun tiered(): Boolean {
            return candidates.size > 1
        }

        /** 默认取第一个候选。 */
        fun defaultChoice(): ItemStack {
            return if (candidates.isEmpty()) ItemStack.EMPTY else candidates[0]
        }
    }

    /**
     * 规划结果。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     */
    data class Plan(
        @get:JvmName("slots") val slots: List<Slot>,
        @get:JvmName("groups") val groups: Map<String, Group>,
    ) {

        /** 需要放置的格子（世界为空的位置）。 */
        fun emptySlots(): List<Slot> {
            return slots
        }

        fun tieredGroups(): List<Group> {
            return groups.values.stream().filter { it.tiered() }.toList()
        }
    }

    /**
     * 规划选项（`planCells` 用）。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     *
     * @param repeatCount     可重复层的重复次数；只对「最小 ≠ 最大」的层生效
     * @param flip            坐标映射的镜像位（与机器自身的 `isFlipped()` 取或）
     * @param includeOccupied 是否把「已经有方块」的格子也收进清单
     */
    data class Options(
        @get:JvmName("repeatCount") val repeatCount: Int,
        @get:JvmName("flip") val flip: Boolean,
        @get:JvmName("includeOccupied") val includeOccupied: Boolean,
    ) {

        companion object {

            /** 默认：按最小重复数展开、不额外镜像、只收空格子。 */
            @JvmField
            val DEFAULT = Options(0, flip = false, includeOccupied = false)
        }
    }

    /**
     * 一个格子。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     *
     * @param occupied 这一格世界上已经有方块（`candidates` 此时是「谓词的普通候选 ∪ 限次候选」）
     * @param required 这一格的候选是「限次谓词的最小数量」逼出来的（必须放），而不是兜底挑出来的
     */
    data class Cell(
        @get:JvmName("pos") val pos: BlockPos,
        @get:JvmName("candidates") val candidates: List<ItemStack>,
        @get:JvmName("groupKey") val groupKey: String?,
        @get:JvmName("occupied") val occupied: Boolean,
        @get:JvmName("required") val required: Boolean,
    )

    /**
     * `planCells` 的结果。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     */
    data class CellPlan(
        @get:JvmName("cells") val cells: List<Cell>,
        @get:JvmName("groups") val groups: Map<String, Group>,
    )

    /**
     * 扫描结构，产出规划。
     *
     * @param pattern 控制器上的结构
     * @param state   该控制器的 [MultiblockState]
     * @param level   世界（用于判断哪些格子已经是空的）
     */
    @JvmStatic
    fun plan(pattern: BlockPattern, state: MultiblockState, level: Level): Plan {
        val cells = planCells(pattern, state, level, Options.DEFAULT)
        val slots = ArrayList<Slot>()
        for (cell in cells.cells) {
            if (cell.occupied) continue
            slots.add(Slot(cell.pos, cell.candidates, cell.groupKey))
        }
        return Plan(java.util.List.copyOf(slots), cells.groups)
    }

    /**
     * 与 `plan` 同一套遍历顺序与坐标映射，额外支持三件事（都要靠选项开启）：
     *
     * - 把「已经有方块」的格子也收进清单 —— 线圈替换模式与拆除模式必须看到这些格子，
     *   否则换不了线圈、也拆不掉方块；
     * - 可重复层的实际重复数 = 把 `repeatCount` 夹到 [最小, 最大]（最小 == 最大时用该固定值）；
     * - 坐标映射的镜像位 = 选项 **或** 机器自身的 `isFlipped()`。
     */
    @JvmStatic
    fun planCells(pattern: BlockPattern, state: MultiblockState, level: Level, options: Options): CellPlan {
        val cells = ArrayList<Cell>()
        val groups = LinkedHashMap<String, Group>()

        try {
            val blockMatches = F_BLOCK_MATCHES.get(pattern) as Array<Array<Array<TraceabilityPredicate?>>>
            val finger = F_FINGER.getInt(pattern)
            val thumb = F_THUMB.getInt(pattern)
            val palm = F_PALM.getInt(pattern)
            val centerOffset = F_CENTER_OFFSET.get(pattern) as IntArray

            val controller = state.controller ?: return CellPlan(emptyList(), emptyMap())
            val centerPos = controller.self().pos
            val facing = controller.self().frontFacing
            val upwardsFacing = controller.self().upwardsFacing
            val flipped = controller.self().isFlipped || options.flip
            val structureDir = pattern.structureDir

            val cacheGlobal = state.globalCount
            val cacheLayer = state.layerCount

            var minZ = -centerOffset[4]
            var z = minZ++
            for (c in 0 until finger) {
                // 可重复层：最小 == 最大时恒用该固定值；否则把设置里的重复次数夹到 [最小, 最大]
                val repMin = pattern.aisleRepetitions[c][0]
                val repMax = pattern.aisleRepetitions[c][1]
                val reps = if (repMin == repMax) repMin
                else Math.max(repMin, Math.min(options.repeatCount, Math.max(repMax, repMin)))
                for (rep in 0 until reps) {
                    cacheLayer.clear()
                    var y = -centerOffset[1]
                    for (b in 0 until thumb) {
                        var x = -centerOffset[0]
                        for (a in 0 until palm) {
                            val predicate = blockMatches[c][b][a]
                            if (predicate == null) {
                                x++
                                continue
                            }

                            val local = setActualRelativeOffset(
                                x, y, z, facing, upwardsFacing, flipped, structureDir
                            )
                            val pos = local.offset(centerPos.x, centerPos.y, centerPos.z)

                            // 已经有方块的位置不用管（和 autoBuild 一致：只补空格）；
                            // 但线圈替换 / 拆除要看这些格子，所以选项开着时也收进清单
                            if (!level.isEmptyBlock(pos)) {
                                state.update(pos, predicate)
                                for (limit in predicate.limited) {
                                    limit.testLimited(state)
                                }
                                if (options.includeOccupied) {
                                    val raw = rawCandidates(predicate)
                                    if (raw.isNotEmpty()) {
                                        val key = groupKey(raw)
                                        groups.getOrPut(key) { Group(key, raw) }
                                        cells.add(Cell(pos, raw, key, occupied = true, required = false))
                                    }
                                }
                                x++
                                continue
                            }

                            state.update(pos, predicate)
                            val picked = candidatesOf(predicate, state, cacheGlobal, cacheLayer)
                            val candidates = picked.candidates
                            if (candidates.isEmpty()) {
                                x++
                                continue
                            }

                            val key = groupKey(candidates)
                            groups.getOrPut(key) { Group(key, candidates) }
                            cells.add(Cell(pos, candidates, key, false, picked.required))
                            x++
                        }
                        y++
                    }
                    z++
                }
            }
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("无法读取 BlockPattern 内部结构", e)
        }

        // copyOf 必须写全限定名：Kotlin 的 kotlin.collections.List / Map 上没有这两个静态方法
        return CellPlan(java.util.List.copyOf(cells), java.util.Map.copyOf(groups))
    }

    /**
     * 谓词的「普通候选 ∪ 限次候选」—— 不按限次顺序挑，就是要这一格**接受的全集**。
     * 拆除模式的防误删判定与线圈替换都用它。
     */
    private fun rawCandidates(predicate: TraceabilityPredicate): List<ItemStack> {
        val blocks = LinkedHashSet<Block>()
        collect(blocks, predicate.common)
        collect(blocks, predicate.limited)
        val candidates = ArrayList<ItemStack>()
        for (block in blocks) {
            if (block === Blocks.AIR) continue
            val stack = block.asItem().defaultInstance
            if (!stack.isEmpty) candidates.add(stack)
        }
        return candidates
    }

    private fun collect(out: MutableSet<Block>, predicates: List<SimplePredicate>) {
        for (predicate in predicates) {
            val supplier = predicate.candidates ?: continue
            val infos = supplier.get() ?: continue
            for (info in infos) out.add(info.blockState.block)
        }
    }

    /**
     * 从谓词里挑出的候选。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     *
     * @param required 这批候选是「限次谓词的最小数量」逼出来的（这一格**必须**放），不是兜底挑的
     */
    data class Picked(
        @get:JvmName("candidates") val candidates: List<ItemStack>,
        @get:JvmName("required") val required: Boolean,
    )

    /** 从谓词里挑出这一格的候选物品（与 GTCEu autoBuild 同序）。 */
    private fun candidatesOf(predicate: TraceabilityPredicate, state: MultiblockState,
                             cacheGlobal: Reference2IntOpenHashMap<SimplePredicate>,
                             cacheLayer: Reference2IntOpenHashMap<SimplePredicate>): Picked {
        var infos: Array<BlockInfo>? = null
        var find = false

        for (limit in predicate.limited) {
            if (limit.minLayerCount > 0) {
                val curr = cacheLayer.getInt(limit)
                if (curr < limit.minLayerCount && (limit.maxLayerCount == -1 || curr < limit.maxLayerCount)) {
                    cacheLayer.addTo(limit, 1)
                } else {
                    continue
                }
            } else {
                continue
            }
            infos = limit.candidates?.get()
            find = true
            break
        }
        if (!find) {
            for (limit in predicate.limited) {
                if (limit.minCount > 0) {
                    val curr = cacheGlobal.getInt(limit)
                    if (curr < limit.minCount && (limit.maxCount == -1 || curr < limit.maxCount)) {
                        cacheGlobal.addTo(limit, 1)
                    } else {
                        continue
                    }
                } else {
                    continue
                }
                infos = limit.candidates?.get()
                find = true
                break
            }
        }
        if (!find) {
            for (limit in predicate.limited) {
                if (limit.maxLayerCount != -1 &&
                    cacheLayer.getOrDefault(limit, Int.MAX_VALUE) == limit.maxLayerCount
                ) {
                    continue
                }
                if (limit.maxCount != -1 &&
                    cacheGlobal.getOrDefault(limit, Int.MAX_VALUE) == limit.maxCount
                ) {
                    continue
                }
                cacheLayer.addTo(limit, 1)
                cacheGlobal.addTo(limit, 1)
                infos = appendAll(infos, limit.candidates?.get())
            }
            for (common in predicate.common) {
                infos = appendAll(infos, common.candidates?.get())
            }
        }

        val candidates = ArrayList<ItemStack>()
        if (infos != null) {
            for (info in infos) {
                if (info.blockState.block !== Blocks.AIR) {
                    val stack = info.itemStackForm
                    if (!stack.isEmpty) candidates.add(stack)
                }
            }
        }
        // find = true 表示这次是被「层 / 全局最小数量」逼出来的一格：这一格必须放，不能算作「多出来的仓室」
        return Picked(candidates, find)
    }

    /**
     * `ArrayUtils.addAll` 的直译：Java 的 `T...` 在 Kotlin 里是 vararg，
     * 传数组要展开、而 null 数组没法展开，所以在这里先判空（语义与 commons 的 addAll 一致）。
     */
    private fun appendAll(a: Array<BlockInfo>?, b: Array<BlockInfo>?): Array<BlockInfo>? {
        if (a == null) return b?.clone()
        if (b == null) return a.clone()
        return ArrayUtils.addAll(a, *b)
    }

    /** 一组候选的稳定标识：把所有候选的物品 id 排序后拼起来。 */
    @JvmStatic
    fun groupKey(candidates: List<ItemStack>): String {
        val ids = LinkedHashSet<String>()
        for (stack in candidates) {
            val id = itemId(stack)
            ids.add(id ?: "minecraft:air")
        }
        val sorted = ArrayList(ids)
        sorted.sort()
        return sorted.joinToString("|")
    }

    /** 物品注册名；拿不到（例如空气、未注册物品）时返回 null。 */
    @JvmStatic
    fun itemId(stack: ItemStack): String? {
        val key = ForgeRegistries.ITEMS.getKey(stack.item)
        return key?.toString()
    }

    /** 注册名 → 物品栈（面板里存的偏好是注册名字符串，搭建时要换回物品）。取不到返回 null。 */
    @JvmStatic
    fun itemStackOf(itemId: String): ItemStack? {
        val location = ResourceLocation.tryParse(itemId) ?: return null
        val item = ForgeRegistries.ITEMS.getValue(location) ?: return null
        return item.defaultInstance
    }

    /**
     * 坐标换算 —— 与结构检测使用的 `setActualRelativeOffset`（枚举版）算法保持一致，
     * 这样算出来的世界坐标才能和结构检测完全对得上。
     */
    private fun setActualRelativeOffset(x: Int, y: Int, z: Int, facing: Direction,
                                        upwardsFacing: Direction, isFlipped: Boolean,
                                        structureDir: Array<RelativeDirection>): BlockPos {
        val c0 = intArrayOf(x, y, z)
        val c1 = IntArray(3)
        if (facing == Direction.UP || facing == Direction.DOWN) {
            val of = if (facing == Direction.DOWN) upwardsFacing else upwardsFacing.opposite
            for (i in 0 until 3) {
                when (structureDir[i].getActualDirection(of)) {
                    Direction.UP -> c1[1] = c0[i]
                    Direction.DOWN -> c1[1] = -c0[i]
                    Direction.WEST -> c1[0] = -c0[i]
                    Direction.EAST -> c1[0] = c0[i]
                    Direction.NORTH -> c1[2] = -c0[i]
                    Direction.SOUTH -> c1[2] = c0[i]
                }
            }
            val xOffset = upwardsFacing.stepX
            val zOffset = upwardsFacing.stepZ
            if (xOffset == 0) {
                val tmp = c1[2]
                c1[2] = if (zOffset > 0) c1[1] else -c1[1]
                c1[1] = if (zOffset > 0) -tmp else tmp
            } else {
                val tmp = c1[0]
                c1[0] = if (xOffset > 0) c1[1] else -c1[1]
                c1[1] = if (xOffset > 0) -tmp else tmp
            }
            if (isFlipped) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    c1[0] = -c1[0]
                } else {
                    c1[2] = -c1[2]
                }
            }
        } else {
            val ordinal = facing.ordinal
            for (i in 0 until 3) {
                when (structureDir[i].getActualDirection(Direction.from3DDataValue(ordinal))) {
                    Direction.UP -> c1[1] = c0[i]
                    Direction.DOWN -> c1[1] = -c0[i]
                    Direction.WEST -> c1[0] = -c0[i]
                    Direction.EAST -> c1[0] = c0[i]
                    Direction.NORTH -> c1[2] = -c0[i]
                    Direction.SOUTH -> c1[2] = c0[i]
                }
            }
            val east = upwardsFacing == Direction.EAST
            if (east || upwardsFacing == Direction.WEST) {
                val clockwise = facing.clockWise
                val xOffset = if (east) clockwise.stepX else clockwise.opposite.stepX
                if (xOffset == 0) {
                    val tmp = c1[2]
                    val zOffset = if (east) clockwise.stepZ else clockwise.opposite.stepZ
                    c1[2] = if (zOffset > 0) -c1[1] else c1[1]
                    c1[1] = if (zOffset > 0) tmp else -tmp
                } else {
                    val tmp = c1[0]
                    c1[0] = if (xOffset > 0) -c1[1] else c1[1]
                    c1[1] = if (xOffset > 0) tmp else -tmp
                }
            } else if (upwardsFacing == Direction.SOUTH) {
                c1[1] = -c1[1]
                if (facing.stepX == 0) {
                    c1[0] = -c1[0]
                } else {
                    c1[2] = -c1[2]
                }
            }
            if (isFlipped) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    if (ordinal == 2 || ordinal == 3) {
                        c1[0] = -c1[0]
                    } else {
                        c1[2] = -c1[2]
                    }
                } else {
                    c1[1] = -c1[1]
                }
            }
        }
        return BlockPos(c1[0], c1[1], c1[2])
    }
}