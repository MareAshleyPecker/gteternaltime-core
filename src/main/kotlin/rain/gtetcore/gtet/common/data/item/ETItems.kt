package rain.gtetcore.gtet.common.data.item

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.common.item.StructureDetectBehavior
import rain.gtetcore.gtet.common.item.StructureWriteBehavior
import rain.gtetcore.gtet.common.item.TerminalBehavior
import rain.gtetcore.gtet.common.item.recipe.RecipeEditorBehavior
import rain.gtetcore.gtet.common.item.tool.StructureToolBehavior
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.util.tooltips
import rain.gtetcore.gtet.common.GTETCreativeModeTabs

object ETItems {

    fun init() {}

    init {
        OnlyETreg.ETRegistrate.creativeModeTab(GTETCreativeModeTabs.ITEM)
    }

    /** 结构工具 — 右键方块选区，右键空气打开导出 GUI */
    val STRUCTURE_TOOLS: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("structure_tools", "结构工具", ComponentItem::create)
        .tooltips("structure_tools",
            "Right-click a block: drag the nearer corner (grow or shrink)" to "右键方块：拖动较近的那个角（可扩可缩）",
            "Shift+right-click to clear" to "潜行右键清除选区",
            "Right-click air to open export GUI" to "右键空气打开导出 GUI",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister { it.attachComponents(StructureWriteBehavior.INSTANCE); StructureWriteBehavior.STRUCTURE_TOOLS_ITEM = it }
        .register()

    /** 结构刷新工具 — Shift+右键多方块控制器触发重检 */
    val STRUCTURE_CHECKER: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("structure_checker", "结构刷新工具", ComponentItem::create)
        .tooltips("structure_checker",
            "Shift+right-click multiblock controller to recheck" to "Shift+右键多方块控制器触发重检",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister { it.attachComponents(TerminalBehavior) }
        .register()

    /** 结构检测工具 — 右键多方块控制器，红框标出错误位置 */
    val STRUCTURE_DETECT: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("structure_detect", "结构检测工具", ComponentItem::create)
        .tooltips("structure_detect",
            "Right-click multiblock controller to detect bad blocks" to "右键多方块控制器检测错误方块",
            "Error boxes disappear on their own after a configurable time" to "错误框会按配置的时间自动消失",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister { it.attachComponents(StructureDetectBehavior) }
        .register()

    /** 结构工具（合并版） — 一个物品三种工作模式，对着空气 Shift+滚轮切换 */
    val STRUCTURE_TOOL: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("structure_tool", "结构工具（合并版）", ComponentItem::create)
        .tooltips("structure_tool",
            "Aim at air + Shift + scroll to switch work mode" to "对着空气 Shift+滚轮 切换工作模式",
            "Modes: area export / recheck / detect" to "模式：选区导出 / 结构重检 / 结构检测",
            "Export: right-click a block to drag the nearer corner, Shift+right-click to clear" to "导出模式：右键方块拖动较近的角（可扩可缩），Shift+右键清空",
            "Recheck: Shift+right-click a controller / Detect: right-click a controller" to "重检模式：Shift+右键控制器 / 检测模式：右键控制器",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister { it.attachComponents(StructureToolBehavior.INSTANCE) }
        .register()

    /** 配方编辑器 — 右键空气打开可视化配方编辑界面，导出 GT datagen 代码 */
    val RECIPE_EDITOR: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("recipe_editor", "配方编辑器", ComponentItem::create)
        .tooltips("recipe_editor",
            "Right-click air to open the visual recipe editor" to "右键空气打开可视化配方编辑器",
            "Vanilla 5 stations + all GT recipe types" to "支持原版五类工作台/熔炉系/锻造台/切石机 + 全部 GT 配方类型",
            "Export GT datagen code to GtetExport/recipes" to "导出 GT datagen 代码到 GtetExport/recipes",
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/paper")) }
        .onRegister { it.attachComponents(RecipeEditorBehavior) }
        .register()
}
