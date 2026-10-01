package rain.gtetcore.gtet.common.data.machine.multiblock

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern
import com.gregtechceu.gtceu.api.pattern.MultiblockState
import com.gregtechceu.gtceu.api.pattern.Predicates
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import com.gregtechceu.gtceu.utils.FormattingUtil
import com.lowdragmc.lowdraglib.utils.BlockInfo
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block
import rain.gtetcore.gtet.api.timeflow.ETTimeFlow
import rain.gtetcore.gtet.common.data.machine.multiblock.ETMasterTower.MAX_SEGMENTS
import rain.gtetcore.gtet.common.machine.multiblock.timeflow.MasterTowerMachine
import rain.gtetcore.gtet.data.lang.MasterTowerLang
import rain.gtetcore.gtet.util.lang.LangUtil
import java.util.function.Supplier

/**
 * **主控塔**的多方块注册 —— 结构、外观、tooltip 全在这里。
 *
 * ## 结构（3×3 底面积，塔身 1~10 段，往上长）
 * ```
 *   y = N+1   顶盖：3×3 实心机壳
 *   y = 1..N  塔身段：3×3 环（8 格，中心是空腔），可重复 1~10 段 —— 「段」
 *   y = 0     基座：3×3 实心机壳，控制器嵌在一侧墙里
 * ```
 * 各格谓词：
 * - `S` 控制器（本方块）；
 * - `X` **塔身/基座/顶盖的机壳**，或 **GTM 的 GTM 能源仓**（[PartAbility.INPUT_ENERGY]，1~4 个 —— 玩家靠它在塔上接电）；
 * - `T` 段锚点：和 `X` 完全同一批方块（机壳或能源仓都可以），唯一区别是**每匹配到一格就往结构上下文 +1 段**，
 *   于是「段数」= 玩家往上接了几节塔身。锚点固定在每段的同一个角上，但因为它和 `X` 认的是同一批方块，
 *   玩家**看不出区别**、也不影响能源仓能插在哪；
 * - ` ` （空腔）= 必须是空气。
 *
 * ## 容量
 * `容量 = 段数 × GTETConfig.towerSegmentCapacity()`；段数上限是结构常量 [MAX_SEGMENTS]（图案是编译期常量，
 * 配置项只负责把「计入容量的段数」往下夹）。
 * ⚠️ **每段容量默认的 1,000,000 TF 是占位值，待定稿**（见 `GTETConfig.DEFAULT_TOWER_SEGMENT_CAPACITY`）
 * —— 别当成最终平衡数值写进文档。
 *
 * @author rain fox
 */
object ETMasterTower {

    /** 注册名（决定方块 id `gtetcore:master_tower` 与 lang 键 `block.gtetcore.<id>`）。 */
    const val MASTER_TOWER_ID: String = "master_tower"

    /**
     * 塔身段数上限（**结构常量**）。
     *
     * 图案在编译期就固定了，所以"最多几段"只能写死在这里；配置项 `towerMaxSegments`
     * 只把**计入容量的段数**往下夹（调小 = 同样的结构存得少，调大超过本常量没有意义）。
     */
    const val MAX_SEGMENTS: Int = 10//TODO之后改改最高塔的结构

    /** 机壳最少要几格（防「只有控制器 + 几个仓」的畸形塔）。 */
    private const val MIN_SHELL: Int = 16

    /** 塔身机壳（贴图与外观方块都用它）。 */
    private val SHELL: Block get() = GTBlocks.CASING_STEEL_SOLID.get()

    /**
     * 注册主控塔。
     *
     * @param registrate GTET 的注册器（`OnlyETreg.ETRegistrate`）
     * @return 注册好的多方块定义
     */
    @JvmStatic
    fun register(registrate: GTRegistrate): MultiblockMachineDefinition {
        // 运行时行（储量 / 容量 / 汇率 / 所有者）的双语条目
        MasterTowerLang.init()

        // ── 双语 ──
        LangUtil.BLOCK_LANG[MASTER_TOWER_ID] = "主控塔"
        LangUtil.add(
            "gtetcore.machine.$MASTER_TOWER_ID.tooltip.0",
            "The only gate where EU is exchanged into time flow (TF). 1 TF = 8,192 EU.",
            "全服唯一把 EU 换成时间流（TF）的闸口。1 TF = 8,192 EU。"
        )
        LangUtil.add(
            "gtetcore.machine.$MASTER_TOWER_ID.tooltip.1",
            "3x3 footprint; 1 to $MAX_SEGMENTS body segments stacked upwards. Capacity = segments x (TF per segment).",
            "3×3 底面积，塔身往上有 1~$MAX_SEGMENTS 段。容量 = 段数 × 每段容量。"
        )
        LangUtil.add(
            "gtetcore.machine.$MASTER_TOWER_ID.tooltip.2",
            "Accepts GT energy hatches. Breaking the controller seals the reserve into the dropped item; placing it back restores it.",
            "可插 GTM 能源仓。敲掉控制器会把储备封存进掉落物，放回去即还回塔里。"
        )

        // 能源仓方块集合：锚点谓词要认它，所以在图案里**懒取**一次。
        // ⚠️ 不能在 register() 里提前取 —— `PartAbility` 的方块表是「首次 getAllBlocks() 那一刻」定格的
        // （`PartAbility.java:61-62` 的 `GTMemoizer.memoize`），而 register() 跑在 GTM 的注册回调里、
        // 那一刻 GTM 自己的能源仓不一定都注册完了。图案是懒求值的（`GTMemoizer` 包在
        // `MultiblockMachineBuilder` 上），放到这里取才是安全的时机。
        return registrate
            .multiblock(MASTER_TOWER_ID) { holder -> MasterTowerMachine(holder) }
            .langValue("Master Tower")
            .tier(GTValues.IV)
            .rotationState(RotationState.ALL)
            // 本机不跑配方：只借 WorkableElectricMultiblockMachine 那套「聚合能源仓」的现成底座
            .recipeType(GTRecipeTypes.DUMMY_RECIPES)
            .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
            .pattern { definition ->
                val energyHatchBlocks: Set<Block> = PartAbility.INPUT_ENERGY.allBlocks.toHashSet()
                FactoryBlockPattern
                    // aisles 沿 UP 叠（第二/第三个参数分别是「行」与「层」的方向），塔身因此是竖着长出来的
                    .start(RelativeDirection.LEFT, RelativeDirection.FRONT, RelativeDirection.UP)
                    // 基座：3×3 实心，控制器嵌在一侧墙里
                    .aisle("XXX", "XXX", "XSX")
                    // 塔身段：3×3 环、中心空腔；T 是段锚点（与 X 认同一批方块，逐段计数）
                    .aisle("TXX", "X X", "XXX")
                    .setRepeatable(1, MAX_SEGMENTS)
                    // 顶盖：3×3 实心
                    .aisle("XXX", "XXX", "XXX")
                    .where('S', Predicates.controller(Predicates.blocks(definition.block)))
                    .where(
                        'X',
                        Predicates.blocks(SHELL)
                            .setMinGlobalLimited(MIN_SHELL)
                            .or(
                                Predicates.abilities(PartAbility.INPUT_ENERGY)
                                    .setMinGlobalLimited(1)
                                    .setMaxGlobalLimited(4)
                                    .setPreviewCount(1)
                            )
                    )
                    .where('T', segmentAnchor(energyHatchBlocks))
                    .where(' ', Predicates.air())
                    .build()
            }
            .tooltips(
                Component.translatable("gtetcore.machine.$MASTER_TOWER_ID.tooltip.0"),
                Component.translatable("gtetcore.machine.$MASTER_TOWER_ID.tooltip.1"),
                Component.translatable("gtetcore.machine.$MASTER_TOWER_ID.tooltip.2")
            )
            // 掉落物（塔芯）上显示封存的储备；没有储备就一个字都不加
            .tooltipBuilder { stack, components ->
                val sealedTf = stack.tag?.getLong(MasterTowerMachine.KEY_TIME_FLOW) ?: 0L
                if (sealedTf > 0L) {
                    components.add(
                        Component.translatable(
                            MasterTowerLang.SEALED,
                            FormattingUtil.formatNumbers(sealedTf),
                            FormattingUtil.formatNumbers(ETTimeFlow.tfToEu(sealedTf))
                        ).withStyle(ChatFormatting.AQUA)
                    )
                }
            }
            // 贴图复用 GTM 现成的（TODO 以后画 GTET 自己的塔贴图，只改这两行）
            .workableCasingModel(
                GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                GTCEu.id("block/multiblock/power_substation")
            )
            .register()
    }

    /**
     * 段锚点谓词：**每匹配到一格就往结构上下文 +1**（键 = [MasterTowerMachine.SEGMENT_KEY]）。
     *
     * 认的方块和 `X` 完全一样（机壳 **或** GTM 能源仓），所以：
     * - 玩家不用专门摆一种「段方块」，塔身整片都是机壳；
     * - 能源仓摆在哪一格都不影响段数 —— 锚点那一格是**位置固定、外观无差别**的，认到仓也算一段。
     *
     * 上下文由 GTM 在每次结构检测开始时 `clean()` 清空（`MultiblockState#clean` → `matchContext.reset()`），
     * 所以这里的 `increment` 不会跨检测累积。这套写法抄的是 GTM 自己的电池计数
     * （`Predicates#powerSubstationBatteries` + `PowerSubstationMachine.BatteryMatchWrapper`）。
     */
    private fun segmentAnchor(energyHatchBlocks: Set<Block>): TraceabilityPredicate =
        Predicates.custom(
            { state: MultiblockState ->
                val block = state.blockState.block
                val matched = block === SHELL || energyHatchBlocks.contains(block)
                if (matched) state.matchContext.increment(MasterTowerMachine.SEGMENT_KEY, 1)
                matched
            },
            Supplier { arrayOf(BlockInfo.fromBlock(SHELL)) }
        )
}