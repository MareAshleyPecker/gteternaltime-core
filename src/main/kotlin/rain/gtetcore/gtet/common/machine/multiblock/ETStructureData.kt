package rain.gtetcore.gtet.common.machine.multiblock

import com.gregtechceu.gtceu.api.pattern.MultiblockState
import com.gregtechceu.gtceu.api.pattern.Predicates
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate
import com.lowdragmc.lowdraglib.utils.BlockInfo
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * 「结构里的模块方块喂数据」——图案里的谓词除了判定方块，还能往 [MultiblockState] 的
 * `PatternMatchContext` 里写数据，机器成型时读出来（GTM 自己就这么用：`SimplePredicate` 里的
 * `renderMask` / `slots`）。
 *
 * ⚠️ `PatternMatchContext` 每次结构检测前都会 `reset()`，所以收集方不需要自己清空；
 * 反过来，**读的一方只能在 `onStructureFormed` 之后读**，结构失效后数据就没了。
 */
object ETStructureData {

    /** 收集型谓词存坐标用的键。 */
    const val MODULE_BLOCKS: String = "gtet_module_blocks"

    /**
     * 收集型谓词：命中 [matches] 的方块照常参与匹配，同时把自己的坐标记进 [MODULE_BLOCKS]。
     *
     * [previews] 只用于 JEI 预览（拿不到就给空数组，少一格预览而已）。
     */
    @JvmStatic
    fun collectingModuleBlocks(matches: Predicate<BlockState>, previews: Array<BlockInfo>): TraceabilityPredicate =
        Predicates.custom({ state ->
            val matched = matches.test(state.blockState)
            if (matched) {
                state.matchContext.getOrCreate(MODULE_BLOCKS) { LinkedHashSet<BlockPos>() }.add(state.pos)
            }
            matched
        }, { previews })

    /** 结构里检出的模块方块数量。 */
    @JvmStatic
    fun moduleBlockCount(state: MultiblockState): Int = moduleBlocks(state).size

    /** 结构里检出的模块方块坐标。 */
    @JvmStatic
    fun moduleBlocks(state: MultiblockState): Set<BlockPos> =
        state.matchContext.getOrDefault(MODULE_BLOCKS, emptySet())
}
