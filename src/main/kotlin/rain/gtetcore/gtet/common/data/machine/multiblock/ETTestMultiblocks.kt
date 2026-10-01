package rain.gtetcore.gtet.common.data.machine.multiblock

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern
import com.gregtechceu.gtceu.api.pattern.Predicates
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import com.gregtechceu.gtceu.common.data.models.GTMachineModels
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.api.capability.ETPartAbility
import rain.gtetcore.gtet.studio.integration.StudioDynamicRenders
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
     * 挂在测试机上的「机器渲染工作室」模型 id —— **OBJ 那份**（M0 的时钟）。
     *
     * ⚠️ 这是 **`config/gtetstudio/` 下 JSON 里的 `id` 字段**，不是文件名。
     * 以后要换成真机器（ETV 那台）时：**把下面那两行 `.andThen(StudioDynamicRenders.attach(...))`
     * 连同 `.hasBER(true)` 一起搬到那台机器的注册里**即可，studio 包一行都不用改。
     */
    const val STUDIO_TEST_MODEL_ID: String = "gtet:test_clock"

    /**
     * ★ **M3a 新增**：测试机上第二份 studio 模型 —— **CAD 那份**（`features` 参数化历史）。
     *
     * ## 为什么是"再挂一个 DynamicRender"而不是把上面那行改掉
     * `MachineModelBuilder.dynamicRenders` 是一个 `List`，`addDynamicRenderer` 是 `add`
     * （`MachineModelBuilder.java:46/173`），`ModelInitializer.andThen` 也只是把两个 initializer
     * 顺着执行（`MachineBuilder.java:734-740`）—— 所以**挂两个是官方支持的**，不是我们硬凑。
     *
     * 于是不必牺牲 OBJ 路径的实机样板：两台模型并排画在同一面墙上，
     * **一眼就能对照"新东西出来了没有、跟老的比形状对不对"**。
     * 两者的 `anchor.offset` 不同（时钟在 `[0,1,0]`、表盘在 `[5,1,0]`），所以不会叠在一起。
     *
     * ⚠️ 编辑器（`/gtetstudio edit`）那边有个已知取舍：`StudioMachineRegistry` 按**方块坐标**登记，
     * 同一格挂两份模型时后登记的覆盖前者（该类的 KDoc 里写明了"M2a 不处理这种用法"）。
     * 所以编辑模式在这台机器上只能编辑到其中一份（表盘）—— 渲染不受影响。
     * 要把时钟换回唯一的挂载，删掉下面那行 `.andThen(StudioDynamicRenders.attach(STUDIO_CAD_MODEL_ID))` 即可。
     */
    const val STUDIO_CAD_MODEL_ID: String = "gtet:test_dial"

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
            //
            // ⚠️ 这里**不能**再用 `workableCasingModel(...)` 那个便捷方法：它内部直接
            //    `model(createWorkableCasingMachineModel(...))`，没有插 `andThen` 的位置。
            //    要挂 DynamicRender 就得自己把这两步摊开（GTM 自己的聚变堆也是这么写的：
            //    GTMultiMachines.java:736-739），并且**必须补上那句 modelProperty**，
            //    否则 createWorkableCasingMachineModel 里的 forAllStates 读不到该属性会炸。
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            .model(
                GTMachineModels.createWorkableCasingMachineModel(
                    GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                    GTCEu.id("block/multiblock/gcym/large_maceration_tower")
                )
                    // ★ 机器渲染工作室（studio M0）：把 config/gtetstudio 里那份模型挂在这台机器上。
                    //   换机器 / 换模型只改这一行。
                    .andThen(StudioDynamicRenders.attach(STUDIO_TEST_MODEL_ID))
                    // ★ M3a：再挂一份 CAD 模型（features 参数化历史）。两份并排画，互不干扰；
                    //   不想要就删掉这一行（见 STUDIO_CAD_MODEL_ID 的 KDoc）。
                    .andThen(StudioDynamicRenders.attach(STUDIO_CAD_MODEL_ID))
            )
            // 没有 BER 就没有任何东西会去调 DynamicRender.render —— 这行不能少。
            .hasBER(true)
            .register()
    }
}