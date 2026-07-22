package rain.gtetcore.gtet.common.data.item

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.GTET.common.StructureWriteBehavior
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
/**
 * 物品注册入口。
 *
 * ## 双语注册
 * 使用 [ETREGISTRATE.itemAndLang][rain.gtetcore.gtet.api.registrate.ETREGISTRATE.itemAndLang]
 * 一键完成中英双语翻译注册。
 *
 * @see ETMaterialItems 材料物品自动生成器
 */
object ETItems {

    fun init() {}

    init {
        OnlyETreg.ETRegistrate.creativeModeTab(GTETCreativeModeTabs.ITEM)
    }

    /** 结构工具 — 右键方块选区，右键空气打开导出 GUI。 */
    val STRUCTURE_TOOLS: ItemEntry<ComponentItem> = OnlyETreg.ETRegistrate
        .itemAndLang("structure_tools", "结构工具", ComponentItem::create)
        .properties { p -> p.stacksTo(1) }
        .model { ctx, prov -> prov.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick")) }
        .onRegister {
            it.attachComponents(StructureWriteBehavior.INSTANCE)
            StructureWriteBehavior.STRUCTURE_TOOLS_ITEM = it
        }
        .register()
}
