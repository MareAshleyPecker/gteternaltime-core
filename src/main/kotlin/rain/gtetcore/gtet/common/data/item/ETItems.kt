package rain.gtetcore.gtet.common.data.item

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.data.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.item.recipe.RecipeEditorBehavior
import rain.gtetcore.gtet.common.item.terminal.AdvancedTerminalBehavior
import rain.gtetcore.gtet.common.item.tool.StructureDetectBehavior
import rain.gtetcore.gtet.common.item.tool.StructureWriteBehavior
import rain.gtetcore.gtet.common.item.tool.TerminalBehavior
import rain.gtetcore.gtet.util.tooltips

object ETItems {

    fun init() {}

    init {
        OnlyETreg.ETRegistrate.creativeModeTab(GTETCreativeModeTabs.ITEM)
    }

    /** 结构工具 — 右键方块选区，右键空气打开导出 GUI */
    val STRUCTURE_TOOLS: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("structure_tools", "结构工具", ComponentItem::create)
        .tooltips(
            "structure_tools",
            "Right-click a block: the first click sets the fixed start corner, later clicks drag the opposite end corner (grow or shrink)" to "右键方块：第一下确定固定起点，之后每次右键拖动对角终点（可扩可缩）",
            "Shift+right-click to clear" to "潜行右键清除选区",
            "Right-click air to open export GUI" to "右键空气打开导出 GUI",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister {
            it.attachComponents(StructureWriteBehavior.INSTANCE); StructureWriteBehavior.STRUCTURE_TOOLS_ITEM = it
        }
        .register()

    /** 结构刷新工具 — Shift+右键多方块控制器触发重检 */
    val STRUCTURE_CHECKER: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("structure_checker", "结构刷新工具", ComponentItem::create)
        .tooltips(
            "structure_checker",
            "Shift+right-click multiblock controller to recheck" to "Shift+右键多方块控制器触发重检",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister { it.attachComponents(TerminalBehavior) }
        .register()

    /** 结构检测工具 — 右键多方块控制器，红框标出错误位置 */
    val STRUCTURE_DETECT: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("structure_detect", "结构检测工具", ComponentItem::create)
        .tooltips(
            "structure_detect",
            "Right-click multiblock controller to detect bad blocks" to "右键多方块控制器检测错误方块",
            "Error boxes disappear on their own after a configurable time" to "错误框会按配置的时间自动消失",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister { it.attachComponents(StructureDetectBehavior) }
        .register()

    /** 配方编辑器 — 右键空气打开可视化配方编辑界面，导出 GT datagen 代码 */
    val RECIPE_EDITOR: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("recipe_editor", "配方编辑器", ComponentItem::create)
        .tooltips(
            "recipe_editor",
            "Right-click air to open the visual recipe editor" to "右键空气打开可视化配方编辑器",
            "Vanilla 5 stations + all GT recipe types" to "支持原版五类工作台/熔炉系/锻造台/切石机 + 全部 GT 配方类型",
            "Export GT datagen code to GtetExport/recipes" to "导出 GT datagen 代码到 GtetExport/recipes",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/paper")) }
        .onRegister { it.attachComponents(RecipeEditorBehavior) }
        .register()

    /**
     * 高级终端 — 潜行右键控制器自动搭建 / 拆除；潜行右键无线接入点绑定 AE 网络；右键空气打开设置。
     *
     * 贴图自备（`assets/gtetcore/textures/item/advanced_terminal.png`），不引用任何第三方命名空间的资源。
     */
    val ADVANCED_TERMINAL: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("advanced_terminal", "§bAdvanced Terminal", "§b高级终端", ComponentItem::create)
        .tooltips(
            "advanced_terminal",
            "Sneak + right-click a multiblock controller: auto build (or demolish)" to "潜行右键多方块控制器：自动搭建（或拆除）",
            "Sneak + right-click a wireless access point or ME block: link the AE network" to "潜行右键无线接入点或 ME 方块：绑定 AE 网络",
            "Right-click air: open the terminal settings" to "右键空气：打开终端设置",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, Gtetcore.id("item/advanced_terminal")) }
        .onRegister { it.attachComponents(AdvancedTerminalBehavior.INSTANCE) }
        .register()
}
