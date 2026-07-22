package rain.gtetcore.gtet.common.data.item

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.common.item.StructureDetectBehavior
import rain.gtetcore.gtet.common.item.StructureWriteBehavior
import rain.gtetcore.gtet.common.item.TerminalBehavior
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
            "Right-click block to select area" to "右键方块选区",
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
        )
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister { it.attachComponents(StructureDetectBehavior) }
        .register()
}
