package rain.gtetcore.gtet.api.registrate

import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.gregtechceu.gtceu.api.item.TagPrefixItem
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTItems
import com.gregtechceu.gtceu.utils.FormattingUtil
import com.tterrag.registrate.builders.BlockBuilder
import com.tterrag.registrate.builders.ItemBuilder
import com.tterrag.registrate.providers.ProviderType
import com.tterrag.registrate.util.entry.ItemEntry
import com.tterrag.registrate.util.nullness.NonNullBiConsumer
import com.tterrag.registrate.util.nullness.NonNullFunction
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import rain.gtetcore.gtet.util.lang.BlockLangUtil
import rain.gtetcore.gtet.util.lang.ItemLangUtil
import rain.gtetcore.gtet.util.math.then
import java.util.function.Supplier

/**
 * GTET 专用 [GTRegistrate] 子类，提供本模组高频使用的注册快捷方法。
 *
 * ## 扩展功能
 * - [itemAndLang] / [blockAndLang] — 注册物品/方块并自动生成双语翻译
 * - [materialItem] — 参照 GTO 的 `ItemRegisterUtils.generateMaterialItem()`，
 *   为材料+前缀组合注册物品条目（含双语 lang）
 * - 常规 [item] / [block] 方法继承自 [GTRegistrate]
 *
 * @param modid 模组 ID
 */
class ETREGISTRATE(modid: String) : GTRegistrate(modid) {

    // ======================== 物品双语注册 ========================

    /**
     * 注册物品并自动生成双语翻译。
     *
     * 中文名存入 [ItemLangUtil.ITEM_LANG] 供 LangHandler 消费，
     * 英文名通过 [FormattingUtil.toEnglishName] 从 ID 自动推断。
     *
     * ```kotlin
     * val TOOLS = ETRegistrate.itemAndLang("structure_tools", "结构工具", ComponentItem::create)
     *     .properties { p -> p.stacksTo(1) }
     *     .register()
     * ```
     */
    fun <T : Item> itemAndLang(
        name: String,
        cnName: String,
        factory: NonNullFunction<Item.Properties, T>
    ): ItemBuilder<T, GTRegistrate> {
        ItemLangUtil.ITEM_LANG[name] = cnName
        return super.item(name, factory)
            .lang(FormattingUtil.toEnglishName(name))
    }

    /**
     * 注册物品，显式中英双语。
     */
    fun <T : Item> itemAndLang(
        name: String,
        enName: String,
        cnName: String,
        factory: NonNullFunction<Item.Properties, T>
    ): ItemBuilder<T, GTRegistrate> {
        ItemLangUtil.ITEM_LANG[name] = cnName
        return super.item(name, factory).lang(enName)
    }

    // ======================== 方块双语注册 ========================

    /**
     * 注册方块 + BlockItem，自动从 ID 推断英文名。
     */
    fun <T : Block> blockAndLang(
        name: String,
        cnName: String,
        factory: NonNullFunction<BlockBehaviour.Properties, T>
    ): BlockBuilder<T, GTRegistrate> {
        BlockLangUtil.BLOCK_LANG[name] = cnName
        return super.block(name, factory)
            .lang(FormattingUtil.toEnglishName(name))
    }

    // ======================== 材料物品注册 ========================

    /**
     * 参照 GTO `ItemRegisterUtils.generateMaterialItem()`，
     * 为材料+TagPrefix 组合生成物品条目。
     *
     * 自动处理：
     * - 耐火材料属性（[MaterialFlags.FIRE_RESISTANT]）
     * - GTCEu 统一化（[GTItems.unificationItem]）
     * - 最大堆叠数（[TagPrefix.maxStackSize]）
     * - 方块颜色（[TagPrefixItem.tintColor]）
     * - 炼药锅交互（[GTItems.cauldronInteraction]）
     * - 双语 lang（中文名由材料名 + 前缀组合，英文由 Registrate 自动处理）
     *
     * @param tagPrefix 物品前缀
     * @param material  材料
     * @return 已注册的 [ItemEntry]
     */
    fun materialItem(tagPrefix: TagPrefix, material: Material): ItemEntry<out Item> {
        val itemPath = tagPrefix.idPattern().format(material.name)
        return super.item(itemPath) { properties: Item.Properties ->
            tagPrefix.itemConstructor().create(
                material.hasFlag(MaterialFlags.FIRE_RESISTANT) then properties.fireResistant() or properties,
                tagPrefix, material
            )
        }
            .setData(ProviderType.LANG, NonNullBiConsumer.noop())  // 由 Material+TagPrefix 系统提供翻译
            .transform(GTItems.unificationItem(tagPrefix, material))
            .properties { p -> p.stacksTo(tagPrefix.maxStackSize()) }
            .model(NonNullBiConsumer.noop())
            .color { Supplier { TagPrefixItem.tintColor(material) } }
            .onRegister { GTItems.cauldronInteraction(it) }
            .register()
    }
}
