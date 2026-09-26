package rain.gtetcore.gtet.common.data.machine.hatch

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.common.machine.multiblock.part.ETTagFilterStockBusPartMachine
import rain.gtetcore.gtet.common.machine.multiblock.part.ETTagFilterStockHatchPartMachine
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator
import rain.gtetcore.gtet.util.lang.LangUtil

/** GTM 的 AE 覆盖层命名空间：贴图在 GTM 自己的 jar 里，我们只引用。 */
private const val GTCEU_NS = "gtceu"

/** 物品件正面覆盖层：与 GTM 的 `me_input_bus` / `me_stocking_input_bus` 用同一张。 */
private const val OVERLAY_ME_INPUT_BUS = "block/overlay/appeng/me_input_bus"

/** 流体件正面覆盖层：与 GTM 的 `me_input_hatch` / `me_stocking_input_hatch` 用同一张。 */
private const val OVERLAY_ME_INPUT_HATCH = "block/overlay/appeng/me_input_hatch"

/**
 * 「ME 标签库存输入总线 / 输入仓」的注册入口：两件部件，进 [rain.gtetcore.gtet.common.data.GTETCreativeModeTabs.MACHINE] 页。
 *
 * ## 这两件是什么 / 不是什么
 *
 * **是**：GTM 自带的 `me_stocking_input_bus`（ME 库存输入总线）与 `me_stocking_input_hatch`（ME 库存输入仓）
 * **加上** GTET 自己的两条策略 —— 标签白/黑名单过滤 + 「每次拉 N 个」的定量拉取。
 *
 * **不是**：不是重做 GTM 已有的那两件。GTM 7.5.3 已经自带并注册了
 * `me_input_bus` / `me_stocking_input_bus` / `me_output_bus` / `me_input_hatch` /
 * `me_stocking_input_hatch` / `me_output_hatch` / `me_pattern_buffer` / `me_pattern_buffer_proxy`
 * （见 GTM 的 `common/data/machines/GTAEMachines.java`），本文件**一件都不碰**，只新增这两件。
 *
 * ## 档位与能力
 *
 * 与 GTM 对应件**逐项一致**（照抄它的选择，不是自己拍的）：
 * - tier = `LuV`：与 GTM 的 `me_stocking_input_bus` / `me_stocking_input_hatch` 相同
 *   （GTM 的非库存版 `me_input_bus` / `me_input_hatch` 是 EV，库存版才是 LuV）；
 * - abilities = `PartAbility.IMPORT_ITEMS` / `PartAbility.IMPORT_FLUIDS`，与 GTM 对应件相同；
 * - 贴图 = GTM 的 `block/overlay/appeng/me_input_bus` / `me_input_hatch`
 *   （已核实两张 png 确实在 GTM 的 jar 里：`assets/gtceu/textures/block/overlay/appeng/me_input_bus.png`
 *   与 `.../me_input_hatch.png`），走 `colorOverlayTieredHullModel`，
 *   所以外观与 GTM 自己的库存件**完全一样**、底座是 LuV 电压外壳 —— 目前没有 GTET 自己的 AE 贴图，
 *   靠**名字**区分（见下）。
 *
 * ## 显示
 *
 * 与 [ETThreadHatches] / [ETOverclockHatches] 同一套约定：英文名走 `.langValue(...)`、
 * 中文名走 [LangUtil.BLOCK_LANG]，**规格并进名字**（中文名里直接写「标签过滤 + 定量拉取」），
 * 不额外生成说明性 tooltip 键；tooltip 只有一条 GTET 自己的功能说明 + GTM 自带的
 * `gtceu.part_sharing.disabled`。
 *
 * 面板用的 8 条界面键（标题 / 白名单 / 黑名单 / 定量 / 4 行说明）也在这里用 [LangUtil.add] 一次登记 ——
 * 它们是 `LangUtil.CUSTOM_LANG`，由 `data.lang.LangHandler` 同时写进 en_us 与 zh_cn。
 *
 * ## 注册位置
 *
 * 放在 `common/data/machine/hatch/` 与超频 / 线程 / 并行三族同目录，由 [rain.gtetcore.gtet.common.data.machine.ALLSmachine.registerMachines] 调用，
 * 理由：这三族与本族都是**多方块部件仓**，同一张表、同一个入口，加一件只需要在这里加一行；
 * 而 `multiblock.ALLMmchine` 那边是多方块本体，不该混部件。
 *
 * @author rain fox
 */
object ETTagFilterHatches {

    /** 物品件注册名。 */
    const val ITEM_BUS_ID: String = "me_tag_filter_stocking_bus"

    /** 流体件注册名。 */
    const val FLUID_HATCH_ID: String = "me_tag_filter_stocking_hatch"

    /**
     * 注册两件部件。
     *
     * ⚠️ AE2 没装时**一件都不注册**（返回空表）：这两件的类继承 GTM 的 AE 部件、直接引用 `appeng.*`，
     * AE2 缺失时连类都加载不了。这与 GTM 自己的做法一致（GTM 的 `GTMachines.init()` 里是
     * `if (GTCEu.Mods.isAE2Loaded()) GTAEMachines.init();`），所以这里也必须用
     * [GTCEu.Mods.isAE2Loaded] 把它挡在外面，而不是无条件调用。
     *
     * @param registrate GTET 的注册器（`OnlyETreg.ETRegistrate`）；用 GTET 自己的，否则方块会进 `gtceu:` 命名空间
     * @return 按注册顺序排列的 [MachineDefinition]；AE2 缺失时是空表
     */
    @JvmStatic
    private fun register(registrate: GTRegistrate): List<MachineDefinition> {
        if (!GTCEu.Mods.isAE2Loaded()) return emptyList()
        registerLang()
        return listOf(registerItemBus(registrate), registerFluidHatch(registrate))
    }

    /** 物品件：ME 库存输入总线 + 标签过滤 + 定量拉取。 */
    private fun registerItemBus(registrate: GTRegistrate): MachineDefinition {
        LangUtil.BLOCK_LANG[ITEM_BUS_ID] = "ME 标签库存输入总线"
        return registrate
            .machine(ITEM_BUS_ID) { holder -> ETTagFilterStockBusPartMachine(holder) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(GTValues.LuV)
            .langValue("ME Tag-Filtered Stocking Input Bus")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_ITEMS)
            .colorOverlayTieredHullModel(gtmOverlay(OVERLAY_ME_INPUT_BUS))
            .tooltips(
                Component.translatable("gtceu.machine.item_bus.import.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_item.tooltip.0"),
                Component.translatable(TAG_FILTER_TOOLTIP_KEY),
                Component.translatable(SHARE_TOOLTIP_KEY),
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }

    /** 流体件：ME 库存输入仓 + 标签过滤 + 定量拉取。 */
    private fun registerFluidHatch(registrate: GTRegistrate): MachineDefinition {
        LangUtil.BLOCK_LANG[FLUID_HATCH_ID] = "ME 标签库存输入仓"
        return registrate
            .machine(FLUID_HATCH_ID) { holder -> ETTagFilterStockHatchPartMachine(holder) }
            .tier(GTValues.LuV)
            .langValue("ME Tag-Filtered Stocking Input Hatch")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_FLUIDS)
            .colorOverlayTieredHullModel(gtmOverlay(OVERLAY_ME_INPUT_HATCH))
            .tooltips(
                Component.translatable("gtceu.machine.fluid_hatch.import.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_fluid.tooltip.0"),
                Component.translatable(TAG_FILTER_TOOLTIP_KEY),
                Component.translatable(SHARE_TOOLTIP_KEY),
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }

    /** 一件功能的 tooltip 键（两件共用，中英各一条）。 */
    private const val TAG_FILTER_TOOLTIP_KEY = "gtetcore.machine.et_tag_filter.tooltip"

    /**
     * 「多方块共享」那条 tooltip 的键（三件 ME 库存件共用）。
     *
     * ⚠️ 三件的共享开关**默认关（隔离）**、可在「标签过滤」面板里切换，所以
     * 光留 GTM 的 `gtceu.part_sharing.disabled`（"Multiblock Sharing §4Disabled"）会让玩家以为改不了 ——
     * 那条保留（它描述的正是默认状态），后面再补这一条说明「可以切」。
     */
    const val SHARE_TOOLTIP_KEY: String = "gtetcore.machine.et_tag_filter.share.tooltip"

    /** GTM 的贴图路径 → `ResourceLocation`（命名空间固定 gtmOverlay 的 `gtceu`）。 */
    private fun gtmOverlay(path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(GTCEU_NS, path)

    /**
     * 登记面板与 tooltip 的全部双语键。
     *
     * ⚠️ 说明行刻意写得短：面板宽 150px，LDLib 的 `LabelWidget` 不换行，太长会画出面板外。
     * 共享开关的 4 条 tooltip 不受这个限制（tooltip 会自己折行），所以那里把语义写全。
     */
    private fun registerLang() {
        LangUtil.add(TAG_FILTER_TOOLTIP_KEY, "AE tag filtering + batch pull", "AE 标签过滤 + 定量拉取")
        LangUtil.add(SHARE_TOOLTIP_KEY,
            "Multiblock sharing: isolated by default, toggle in the Tag Filter panel",
            "多方块共享：默认隔离，可在「标签过滤」面板里切换"
        )

        LangUtil.add(ETTagFilterConfigurator.LANG_TITLE, "Tag Filter", "标签过滤")
        LangUtil.add(ETTagFilterConfigurator.LANG_WHITE, "Whitelist (blank = no limit)", "白名单（留空 = 不限制）")
        LangUtil.add(ETTagFilterConfigurator.LANG_BLACK, "Blacklist (blank = no limit)", "黑名单（留空 = 不限制）")
        LangUtil.add(ETTagFilterConfigurator.LANG_BATCH, "Pull per batch (0 = no limit)", "每次拉取量（0 = 不限制）")
        LangUtil.add(ETTagFilterConfigurator.LANG_HINT_0, "Ops: & | ! ^ ( ) *", "运算符 & | ! ^ ( ) *")
        LangUtil.add(ETTagFilterConfigurator.LANG_HINT_1, "Also accepts , and #", "也认 , 与 # 前缀")
        LangUtil.add(ETTagFilterConfigurator.LANG_HINT_2, "Phantom slot fills tags", "幻影槽放样本自动填标签")
        LangUtil.add(ETTagFilterConfigurator.LANG_HINT_3, "N must be >= min count", "N 需不小于保底数量")

        // 多方块共享开关（第四行）。⚠️ 状态文字必须短：LabelWidget 不换行，写长了会画出面板外；
        // 两个方向与时序全部放在 tooltip 里（tip.1 / tip.2 / tip.3）。
        LangUtil.add(ETTagFilterConfigurator.LANG_SHARE, "Multiblock sharing", "多方块共享")
        LangUtil.add(ETTagFilterConfigurator.LANG_SHARE_ON, "Allowed", "允许共享")
        LangUtil.add(ETTagFilterConfigurator.LANG_SHARE_OFF, "Isolated", "隔离")
        LangUtil.add(
            ETTagFilterConfigurator.LANG_SHARE_TIP_0,
            "Whether other multiblocks may occupy this part",
            "本件能不能被别的多方块占用"
        )
        LangUtil.add(
            ETTagFilterConfigurator.LANG_SHARE_TIP_1,
            "Off (isolated): if this part already belongs to a formed multiblock, another structure's check fails here. Prevents recipe mixups.",
            "关（隔离）：本件已属于某个已成型多方块时，别的结构检查到这一格就判失败 —— 防止两个结构串配方"
        )
        LangUtil.add(
            ETTagFilterConfigurator.LANG_SHARE_TIP_2,
            "On (allowed): another structure must be re-formed (checked again) to take this part; already formed structures do not change by themselves.",
            "开（允许共享）：别的结构要重新成型（重新检查一次结构）才会占用本件；已成型结构不会自己变化"
        )
        LangUtil.add(
            ETTagFilterConfigurator.LANG_SHARE_TIP_3,
            "Toggling re-checks this part's own multiblocks at once; the change takes effect on structure re-check.",
            "拨动开关会立刻让本件所属的多方块复检一次；改动在结构重新检查后生效"
        )
    }
}
