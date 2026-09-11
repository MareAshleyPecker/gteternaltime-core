package rain.gtetcore.gtet.common.data.machine

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
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.machine.multiblock.TestMultiblockMachine
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * GTET 多方块机器的注册入口（本文件目前只有一台：`test_multiblock`）。
 *
 * 注册链与 `ETOverclockHatches` 保持同一套写法：一个 `register(registrate)` +
 * 内部一条 `registrate.multiblock(id, ::Machine)` 链，由 `ALLMmchine.init()`
 * 在「解冻机器表」的窗口里调用。
 *
 * ## 结构：3×3×3，控制器在**正面正中**
 * ```
 *   第 1 层(后)      第 2 层(中)      第 3 层(前)
 *    X X X            X X X            X X X
 *    X X X            X   X            X S X     ← S = 控制器
 *    X X X            X X X            X X X
 * ```
 * 即「3 aisles × 每 aisle 3 行 × 每行 3 字 = 3×3×3」；除控制器外共 **25 个机壳位**，
 * 唯一的空位是几何正中心那 1 格空气（`' '` → [Predicates.air]）。
 *
 * ## 能插什么（四类槽位）
 * 机壳谓词上挂了四条 `.or(...)`：
 * 1. `Predicates.autoAbilities(definition.recipeTypes)` —— 按研磨配方的 IO 自动开槽：
 *    能源仓（`INPUT_ENERGY`，最少 1 最多 2）、输入仓（`IMPORT_ITEMS`）、输出仓（`EXPORT_ITEMS`）。
 *    `MACERATOR_RECIPES` 是 `setMaxIOSize(1, 4, 0, 0)` + `setEUIO(IO.IN)`，所以**不会有流体仓槽位**。
 * 2. `Predicates.autoAbilities(true, false, true)` —— 维护仓（`MAINTENANCE`，最多 1）与
 *    并行仓（`PARALLEL_HATCH`，最多 1）。
 * 3. 同一条 `autoAbilities(true, false, true)` 还被 GTET 的 `MixinPredicatesAutoAbilities` 在
 *    `checkParallel == true` 时追加了一条 **超频仓**能力（`ETPartAbility.OVERCLOCK_HATCH`，全局最多 1），
 *    所以这里传 `true` 就等于「同时接受并行仓与超频仓」。**这条能力不是本文件写的**，本文件只是用对了参数。
 * 4. 本文件**自己显式**加的 **线程仓**槽位（`ETPartAbility.THREAD_HATCH`，全局最多 1）。
 *    线程仓**不**走上面那条 mixin —— 走它会让线程仓在 GTM / GCYM 的多方块上也「能插但不生效」
 *    （那些控制器不实现 `IThreadedRecipeMachine`），所以范围限定在 GTET 自己的多方块上，由本机自己开槽。
 *
 * ## 为什么 minGlobalLimited 取 15 而不是 25
 * 除控制器外 25 个位置既可放机壳也可放部件；若写 `setMinGlobalLimited(25)`，
 * 只要插一个仓（位置被仓占掉）就永远无法成型。最坏情况需要的部件位是
 * 能源仓 ×2 + 输入仓 + 输出仓 + 维护仓 + 并行仓 + 超频仓 + 线程仓 = 8，故留 10 个空位（15 机壳）足够
 * （25 − 8 = 17 ≥ 15，余量 2）；
 * 15 这个下限同时还能挡住「随便搭个壳就成型」的情况。
 *
 * ## 配方修改器：这里**故意没有**并行
 * `.recipeModifiers(...)` 只留 `OC_NON_PERFECT_SUBTICK`（超频）与 `BATCH_MODE`（批处理），
 * **不含** `GTRecipeModifiers.PARALLEL_HATCH`。原因是 [TestMultiblockMachine] 用的是
 * [rain.gtetcore.gtet.common.machine.ThreadedRecipeLogic]，并行由它**逐线程**按并行仓的
 * `getCurrentParallel()` 施加（见 `ThreadedRecipeLogic#applyThreadParallel`）；
 * 若这里再挂一条并行修改器，同一份配方就会先被套一次并行、又被套一次，变成「并行²」。
 * 所以并行仓照旧要装（线程逻辑从控制器上读它），但**不能**再挂并行修改器。
 *
 * ## 思路来源
 * - 【自研】`test_multiblock` 这个 id、双语名与两条 tooltip 文案、`minGlobalLimited = 15` 的取值推导（上面那段「25 个位置 vs 最坏 8 个部件位」的算术），以及「这台机器只作为线程仓试验台」的定位 —— GTM 里没有对应物。
 * - 【自研】`.tier(GTValues.IV)` 这一行 —— GTM 的多方块全部**不**设 tier（`GTMultiMachines` / `GCYMMachines` 里一处 `.tier(` 都没有），因为多方块的配方等级由插进去的能源仓在运行时决定（`WorkableElectricMultiblockMachine#onStructureFormed` 里的 `GTUtil.getFloorTierByVoltage(getMaxVoltage())`）。这里按任务要求钉成 IV，含义是「注册/铭牌等级」（`MachineDefinition#setTier`，用于物品 tier 染色），**不**限制实际配方等级。
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
        // 多方块进「多方块」页。这里显式设一次：调用方 ALLMmchine.init 会先注册超频仓，
        // 而 ETOverclockHatches.register 会把当前页切到 MACHINE，不设就会串页。
        registrate.creativeModeTab(GTETCreativeModeTabs.MULTIBLOCK)

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
            // 外观方块：GTCEu 现成的「钢机壳」，JEI 预览与部件外观都用它
            .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
            .pattern { definition ->
                FactoryBlockPattern.start()
                    // 第 1 层（后）：整面机壳
                    .aisle("XXX", "XXX", "XXX")
                    // 第 2 层（中）：只有正中心 1 格空气
                    .aisle("XXX", "X X", "XXX")
                    // 第 3 层（前）：控制器在正面正中
                    .aisle("XXX", "XSX", "XXX")
                    .where('S', Predicates.controller(Predicates.blocks(definition.block)))
                    .where(
                        'X',
                        Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get())
                            .setMinGlobalLimited(15)
                            // 按配方自动开能源仓 / 输入仓 / 输出仓槽位
                            .or(Predicates.autoAbilities(*definition.recipeTypes))
                            // 维护仓 + 并行仓 + GTET 超频仓（后两条里：并行仓来自 GTM 原文，
                            // 超频仓由 GTET 的 MixinPredicatesAutoAbilities 在 checkParallel=true 时追加）
                            .or(Predicates.autoAbilities(true, false, true))
                            // 线程仓槽位 —— **只**加在 GTET 自己的多方块上，这是有意的范围限制：
                            // THREAD_HATCH 不走 autoAbilities 那条 mixin（那会让它在 GTM / GCYM 的
                            // 多方块上也「能插但不生效」），而是由本机在这里显式加一条。
                            // 规格与并行仓/超频仓一致：全局最多 1 个、JEI 预览 1 个。
                            // 前提：线程仓方块必须先注册（ALLMmchine.init 里 THREAD_HATCHES 在
                            // TEST_MULTIBLOCK 之前），否则这里读到的能力方块表是空的。
                            .or(
                                Predicates.abilities(ETPartAbility.THREAD_HATCH)
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
