package rain.gtetcore.gtet.common.data.machine.multiblock

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern
import com.gregtechceu.gtceu.api.pattern.Predicates
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.api.capability.ETPartAbility
import rain.gtetcore.gtet.util.lang.LangUtil

/*
 * ⚠️ `.tier(GTValues.IV)` 的含义是「注册/铭牌等级」（`MachineDefinition#setTier`，用于物品 tier 染色），
 * **不**限制实际配方等级：GTM 的多方块一律不设 tier（`GTMultiMachines` / `GCYMMachines` 里一处 `.tier(` 都没有），
 * 配方等级由插进去的能源仓在运行时决定（`WorkableElectricMultiblockMachine#onStructureFormed` 里的
 * `GTUtil.getFloorTierByVoltage(getMaxVoltage())`）。
 *
 * @author rain fox
 */
object ETTestMultiblocks {

    /** 多方块注册名（同时决定方块 id `gtetcore:test_multiblock` 与 lang 键 `block.gtetcore.<id>`）。 */
    const val TEST_MULTIBLOCK_ID: String = "test_multiblock"

    /**
     * 注册「多方块测试机」。
     *
     * @param registrate GTET 的注册器（`OnlyETreg.ETRegistrate`）
     * @return 注册好的多方块定义
     */
    @JvmStatic
    fun register(registrate: GTRegistrate): MultiblockMachineDefinition {

        // ── 双语 ──
        // 英文名走 .langValue(...)（Registrate 写进 en_us 的 block.gtetcore.<id>）；
        // 中文名走 LangUtil.BLOCK_LANG，由 LangHandler 写进 zh_cn 的同名键。
        LangUtil.BLOCK_LANG[TEST_MULTIBLOCK_ID] = "多方块测试机"
        LangUtil.add(
            "gtetcore.machine.$TEST_MULTIBLOCK_ID.tooltip.0",
            "Test bench for GTET thread hatches. Runs Macerator recipes only.",
            "GTET 线程仓试验台，只跑研磨机（Macerator）配方。"
        )
        LangUtil.add(
            "gtetcore.machine.$TEST_MULTIBLOCK_ID.tooltip.1",
            "3×3×3 steel casing shell; accepts energy / item / maintenance / parallel / GTET overclock / GTET thread hatches",
            "3×3×3 钢机壳外壳；可插能源仓、输入仓、输出仓、维护仓、并行仓、GTET 超频仓与 GTET 线程仓"
        )

        return registrate
            .multiblock(TEST_MULTIBLOCK_ID) { holder -> TestMultiblockMachine(holder) }
            .langValue("Multiblock Test Bench")
            // 注册/铭牌等级（见 KDoc：GTM 的多方块本身不设 tier，配方等级由能源仓运行时决定）
            .tier(GTValues.IV)
            .rotationState(RotationState.ALL)
            // 只吃研磨配方 —— 配方最多，最容易验证「不同配方各自跑」
            .recipeType(GTRecipeTypes.MACERATOR_RECIPES)
            // ⚠️ 这里**故意没有** PARALLEL_HATCH：本机的配方逻辑是 ThreadedRecipeLogic，
            // 并行由它逐线程按并行仓的 getCurrentParallel() 施加（线程数 × 并行数 = 总处理次数上限）。
            // 再挂一条并行修改器等于把并行套两遍（并行²）—— 契约见 ThreadedRecipeLogic 类 KDoc。
            // 并行仓本身照旧要装：线程逻辑正是从控制器的 getParallelHatch() 读那张并行倍率。
            .recipeModifiers(
                GTRecipeModifiers.OC_NON_PERFECT_SUBTICK,
                GTRecipeModifiers.BATCH_MODE
            )
            .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
            .pattern { definition ->
                FactoryBlockPattern.start()
                    .aisle("XXX", "XXX", "XXX")
                    .aisle("XXX", "X X", "XXX")
                    .aisle("XXX", "XSX", "XXX")
                    .where('S', Predicates.controller(Predicates.blocks(definition.block)))
                    .where('X', Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get())
                            .setMinGlobalLimited(15)
                            .or(Predicates.autoAbilities(true, false, true))
                            .or(Predicates.abilities(ETPartAbility.THREAD_HATCH)
                                    .setMaxGlobalLimited(1)
                                    .setPreviewCount(1)
                            )
                    )
                    .where(' ', Predicates.air())
                    .build()
            }
            .tooltips(
                Component.translatable("gtceu.multiblock.parallelizable.tooltip"),
                Component.translatable(
                    "gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.macerator")
                ),
                Component.translatable("gtetcore.machine.$TEST_MULTIBLOCK_ID.tooltip.0"),
                Component.translatable("gtetcore.machine.$TEST_MULTIBLOCK_ID.tooltip.1")
            )
            // 贴图全部复用 GTM 现成的：
            // - 机壳贴图 = 外观方块「钢机壳」自己的贴图；
            // - 动画覆盖层 = GCYM 大型研磨塔（同为研磨主题）的 overlay_front 系列。
            // 换美术时只需要改这两个 id，别处不含贴图路径。
            // TODO 以后画 GTET 自己的多方块贴图，把第二个 id 换成 block/multiblock/gtet/test_multiblock
            .workableCasingModel(
                GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                GTCEu.id("block/multiblock/gcym/large_maceration_tower")
            )
            .register()
    }
}