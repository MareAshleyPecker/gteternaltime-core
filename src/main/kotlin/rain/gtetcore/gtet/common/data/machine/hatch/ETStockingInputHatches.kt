package rain.gtetcore.gtet.common.data.machine.hatch

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.common.machine.multiblock.part.ETMEDualStockingPartMachine
import rain.gtetcore.gtet.common.machine.multiblock.part.ETTagFilterStockBusPartMachine
import rain.gtetcore.gtet.common.machine.multiblock.part.ETTagFilterStockHatchPartMachine
import rain.gtetcore.gtet.util.lang.LangUtil

/** GTM 的 AE 覆盖层命名空间：贴图在 GTM 自己的 jar 里，我们只引用。 */
private const val GTCEU_NS = "gtceu"

/** 物品件正面覆盖层：与 GTM 的 `me_input_bus` / `me_stocking_input_bus` 用同一张。 */
private const val OVERLAY_ME_INPUT_BUS = "block/overlay/appeng/me_input_bus"

/** 流体件正面覆盖层：与 GTM 的 `me_input_hatch` / `me_stocking_input_hatch` 用同一张。 */
private const val OVERLAY_ME_INPUT_HATCH = "block/overlay/appeng/me_input_hatch"

/**
 * 「ME 库存输入总线 / 输入仓 / 二合一输入总成」的注册入口：三件，进
 * [rain.gtetcore.gtet.common.data.GTETCreativeModeTabs.MACHINE] 页。
 *
 * ## 这三件分别是什么
 *
 * ### 1 / 2：`me_stocking_input_bus`、`me_stocking_input_hatch` —— GTM 那两件的**同行为另注册**
 *
 * 需求是「复刻 GTM 的 `me_stocking_input_bus` / `me_stocking_input_hatch`，注册到本项目命名空间，
 * 便于以后独立调数值 / 换贴图」。核查后的结论是：**不需要（也不应该）再写一份逻辑** ——
 *
 * - 第一批已提交的 [ETTagFilterStockBusPartMachine] / [ETTagFilterStockHatchPartMachine] 是
 *   GTM 那两件**加上**标签过滤与定量拉取，而这两条策略**留空即关闭**；
 * - 逐行核对（源码 + 字节码两侧都看过）「全关」状态下的行为与 GTM **等价**：
 *   ① 标签：`ETTagFilter#test` 在两条表达式都空时直接返回 `true`（GTM 侧没有这一步）；
 *   ② 定量：`batchSize = 0` 时 `batchLimit()` 返回 `Long.MAX_VALUE`，
 *   于是 `want = min(available, MAX_VALUE) = available`，与 GTM 的 `extracted` 逐位相同；
 *   ③ 自动拉取谓词：`tagAutoPullTest` 在全关时退化成 GTM 的 `!testConfiguredInOtherPart`；
 *   ④ 取数点：多出来的一道 `min(amount, stock.amount())` 夹取在「stock = 全网存量」时恒不起作用。
 *
 * 所以这两件就是**同一份机器类 + 另开两个注册名**（换 id / 名字 / 进本 mod 创造页），
 * 也就是「标签过滤版的『配置全关』预设」，而不是第二份实现。
 *
 * ⚠️ 与 GTM 那两件**唯一**的差别是附加物：多一块「标签过滤」配置面板 + 多一条 tooltip，
 * 以及数据棒 / 拆方块会多写几个 NBT 键（GTM 那两件不认识，互不干扰）。
 *
 * ### 3：`me_dual_stocking_input` —— 一个方块同时挂 `IMPORT_ITEMS` + `IMPORT_FLUIDS`
 *
 * 物品与流体**都走库存拉取**，两侧**各带一套**标签白/黑名单 + 定量拉取。实现见
 * [ETMEDualStockingPartMachine] 的类注释（复用第一批的槽与面板，本类里没有一行 AE 抽取逻辑）。
 * 「一方块多能力」在结构里成立，先例是 GTM 自己的 `me_pattern_buffer`（一块挂四种能力，
 * 见 GTM 的 `GTAEMachines` 与 `MEPatternBufferPartMachine`），本件是两块能力的同类。
 *
 * ## 档位 / 能力 / 贴图
 *
 * | 注册名 | tier | abilities | 覆盖层 |
 * |---|---|---|---|
 * | `me_stocking_input_bus` | LuV | `IMPORT_ITEMS` | `me_input_bus` |
 * | `me_stocking_input_hatch` | LuV | `IMPORT_FLUIDS` | `me_input_hatch` |
 * | `me_dual_stocking_input` | LuV | `IMPORT_ITEMS` + `IMPORT_FLUIDS` | `me_input_hatch` |
 *
 * 前两件的档位与贴图与 GTM 对应件**逐项一致**（GTM 的非库存版才是 EV，库存版是 LuV）；
 * 二合一件同样取 LuV（与 GTM 的库存件同档），覆盖层借用流体输入仓那张
 * —— GTM 没有「物品+流体合起来」的覆盖层，将来换本项目自己的贴图时只改这一行。
 * 三张 png 都已核实确实在 GTM 的 jar 里（`assets/gtceu/textures/block/overlay/appeng/`）。
 *
 * ## 显示
 *
 * 与 [ETTagFilterHatches] / [ETThreadHatches] 同一套约定：英文名走 `.langValue(...)`、
 * 中文名走 [LangUtil.BLOCK_LANG]；tooltip 保留 GTM 对应件那几条功能说明 + 一条本 mod 的功能说明。
 *
 * ⚠️ 多方块共享那两行：GTM 的 `gtceu.part_sharing.disabled`（"Multiblock Sharing §4Disabled"）
 * 描述的是**默认状态**，三件的机器类（`ETTagFilterStockBusPartMachine` /
 * `ETTagFilterStockHatchPartMachine` / `ETMEDualStockingPartMachine`）现在返回的都是
 * `canShared() = shareEnabled`，而 `shareEnabled` 默认 `false` = 隔离（防串配方，与上手写死的
 * `false` 行为一致），所以这一行照旧写「禁止」不算骗人；紧跟其后那条
 * [ETTagFilterHatches.SHARE_TOOLTIP_KEY] 才是新增的信息：**默认隔离、但玩家能在
 * 「标签过滤」面板里打开**。开关两个方向分别发生什么、以及「改动在结构重新检查后生效」
 * 都写在开关的 tooltip 里（见 `ETTagFilterConfigurator` 的 LANG_SHARE_TIP_*）。
 * ⚠️ 一台机器只有**一个**开关（`canShared()` 是机器级的一个方法）：二合一件的流体侧那块面板
 * 不画这一行（`SideConfigurator` 传 `showShareSwitch = false`）。
 *
 * ## 注册位置
 *
 * 与超频 / 线程 / 并行 / 标签库存 / 样板总成几族同在 `common/data/machine/hatch/`，
 * 由 [rain.gtetcore.gtet.common.data.machine.ALLSmachine.registerMachines] 调用：这里全是**多方块部件仓**，同一张表、同一个入口。
 *
 * @author rain fox
 */
object ETStockingInputHatches {

    /** 物品件注册名（与 GTM 同名，但命名空间是本 mod 的 `gtetcore`）。 */
    const val ITEM_BUS_ID: String = "me_stocking_input_bus"

    /** 流体件注册名（同上）。 */
    const val FLUID_HATCH_ID: String = "me_stocking_input_hatch"

    /** 二合一件注册名。 */
    const val DUAL_ID: String = "me_dual_stocking_input"

    /** 本 mod 的标签过滤功能说明键（与第一批那两件共用同一条键，不重复登记）。 */
    private const val TAG_FILTER_TOOLTIP_KEY = "gtetcore.machine.et_tag_filter.tooltip"

    /** 二合一件的功能说明键。 */
    private const val DUAL_TOOLTIP_KEY = "gtetcore.machine.et_dual_stocking.tooltip"

    /** 二合一件的面板提示键（讲清「哪块标签面板是哪一侧」与「保底数量两侧共用」）。 */
    private const val DUAL_HINT_KEY = "gtetcore.machine.et_dual_stocking.hint"

    /**
     * 注册三件。
     *
     * ⚠️ AE2 没装时**一件都不注册**（返回空表）：这些类继承 GTM 的 AE 部件、直接引用 `appeng.*`，
     * AE2 缺失时连类都加载不了。这与 GTM 自己的做法一致（GTM 的 `GTMachines.init()` 里是
     * `if (GTCEu.Mods.isAE2Loaded()) GTAEMachines.init();`），所以这里也必须用
     * [GTCEu.Mods.isAE2Loaded] 把它挡在外面，而不是无条件调用。
     *
     * @param registrate GTET 的注册器（`OnlyETreg.ETRegistrate`）；用 GTET 自己的，否则方块会进 `gtceu:` 命名空间
     * @return 按注册顺序排列的 [MachineDefinition]；AE2 缺失时是空表
     */
    @JvmStatic
    fun register(registrate: GTRegistrate): List<MachineDefinition> {
        if (!GTCEu.Mods.isAE2Loaded()) return emptyList()
        registerLang()
        return listOf(
            registerItemBus(registrate),
            registerFluidHatch(registrate),
            registerDual(registrate)
        )
    }

    /** 物品件：GTM `me_stocking_input_bus` 的同行为另注册（标签与定量默认全关）。 */
    private fun registerItemBus(registrate: GTRegistrate): MachineDefinition {
        LangUtil.BLOCK_LANG[ITEM_BUS_ID] = "ME 库存输入总线"
        return registrate
            .machine(ITEM_BUS_ID) { holder -> ETTagFilterStockBusPartMachine(holder) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(GTValues.LuV)
            .langValue("ME Stocking Input Bus")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_ITEMS)
            .colorOverlayTieredHullModel(gtmOverlay(OVERLAY_ME_INPUT_BUS))
            .tooltips(
                Component.translatable("gtceu.machine.item_bus.import.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_item.tooltip.0"),
                Component.translatable("gtceu.machine.me_import_item_hatch.configs.tooltip"),
                Component.translatable("gtceu.machine.me.copy_paste.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_item.tooltip.1"),
                Component.translatable(TAG_FILTER_TOOLTIP_KEY),
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }

    /** 流体件：GTM `me_stocking_input_hatch` 的同行为另注册（标签与定量默认全关）。 */
    private fun registerFluidHatch(registrate: GTRegistrate): MachineDefinition {
        LangUtil.BLOCK_LANG[FLUID_HATCH_ID] = "ME 库存输入仓"
        return registrate
            .machine(FLUID_HATCH_ID) { holder -> ETTagFilterStockHatchPartMachine(holder) }
            .tier(GTValues.LuV)
            .langValue("ME Stocking Input Hatch")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_FLUIDS)
            .colorOverlayTieredHullModel(gtmOverlay(OVERLAY_ME_INPUT_HATCH))
            .tooltips(
                Component.translatable("gtceu.machine.fluid_hatch.import.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_fluid.tooltip.0"),
                Component.translatable("gtceu.machine.me_import_fluid_hatch.configs.tooltip"),
                Component.translatable("gtceu.machine.me.copy_paste.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_fluid.tooltip.1"),
                Component.translatable(TAG_FILTER_TOOLTIP_KEY),
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }

    /**
     * 二合一件：一个方块同时是库存输入总线与库存输入仓。
     *
     * ⚠️ 两条能力一起挂，靠的是 `MachineBuilder#abilities` 把同一方块逐个注册进两份能力表
     * （`PartAbility#register(tier, block)`）；结构侧 `Predicates.autoAbilities` 里
     * `IMPORT_ITEMS` 与 `IMPORT_FLUIDS` 是两条 `or` 分支上的 `blocks(...)` 候选并集，
     * 没有 min/max global limited，所以同一方块被两条都接纳 —— 与 `me_pattern_buffer` 同理。
     */
    private fun registerDual(registrate: GTRegistrate): MachineDefinition {
        LangUtil.BLOCK_LANG[DUAL_ID] = "ME 二合一库存输入总成"
        return registrate
            .machine(DUAL_ID) { holder -> ETMEDualStockingPartMachine(holder) }
            .tier(GTValues.LuV)
            .langValue("ME Dual Stocking Input")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS)
            .colorOverlayTieredHullModel(gtmOverlay(OVERLAY_ME_INPUT_HATCH))
            .tooltips(
                Component.translatable("gtceu.machine.item_bus.import.tooltip"),
                Component.translatable("gtceu.machine.fluid_hatch.import.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_item.tooltip.0"),
                Component.translatable("gtceu.machine.me.stocking_fluid.tooltip.0"),
                Component.translatable(DUAL_TOOLTIP_KEY),
                Component.translatable(DUAL_HINT_KEY),
                Component.translatable(ETTagFilterHatches.SHARE_TOOLTIP_KEY),
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }

    /** GTM 的贴图路径 → `ResourceLocation`（命名空间固定 `gtceu`）。 */
    private fun gtmOverlay(path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(GTCEU_NS, path)

    /**
     * 登记本批新增的双语键。
     *
     * ⚠️ 面板那 8 条键（标题 / 白名单 / 黑名单 / 定量 / 4 行说明）由第一批的
     * [ETTagFilterHatches.registerLang] 登记，这里**不重复登记**（`LangUtil.CUSTOM_LANG` 是同一张 map，
     * 重复写同一个键等于把它改掉，会连带影响那两件单独部件）。
     * 本批只加「流体侧那块面板的标题」与二合一件自己的两条说明。
     */
    private fun registerLang() {
        // 二合一件：两侧各一套配置，所以流体侧那块面板的标题必须标清侧别
        //（物品侧那块沿用共用的「标签过滤」，见 ETMEDualStockingPartMachine 的类注释）
        LangUtil.add(ETMEDualStockingPartMachine.LANG_TITLE_FLUIDS, "Tag Filter (Fluids)", "标签过滤（流体侧）")
        LangUtil.add(
            DUAL_TOOLTIP_KEY,
            "Items and fluids both pull from the ME network, each with its own filter",
            "物品与流体都走 ME 库存拉取，两侧各有一套过滤"
        )
        LangUtil.add(
            DUAL_HINT_KEY,
            "Unlabeled Tag Filter tab = items; min count is shared",
            "未标注侧别的「标签过滤」是物品侧；保底数量两侧共用"
        )
    }
}
