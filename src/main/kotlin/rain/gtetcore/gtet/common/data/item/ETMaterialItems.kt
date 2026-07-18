package rain.gtetcore.gtet.common.data.item

import com.google.common.collect.ImmutableTable
import com.google.common.collect.Table
import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.gregtechceu.gtceu.api.item.TagPrefixItem
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTItems
import com.tterrag.registrate.providers.ProviderType
import com.tterrag.registrate.util.entry.ItemEntry
import com.tterrag.registrate.util.nullness.NonNullBiConsumer
import net.minecraft.world.item.Item
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.util.then
import java.util.function.Supplier

object ETMaterialItems {

    lateinit var MATERIAL_ITEMS: Table<TagPrefix?, Material?, ItemEntry<out Item?>?>
    var MATERIAL_ITEMS_BUILDER : ImmutableTable.Builder<TagPrefix, Material, ItemEntry<out Item>> = ImmutableTable.builder()

    fun generateMaterialItems() {
        OnlyETreg.ETRegistrate.creativeModeTab(GTETCreativeModeTabs.ITEM)
        TagPrefix.values().filter { it.doGenerateItem() }
            .forEach { tagPrefix -> GTCEuAPI.materialManager.registries.filter { it.modid == Gtetcore.MODID }
                .forEach { registry -> val registrate = registry.registrate
                    registry.allMaterials.filter { tagPrefix.doGenerateItem(it) }
                        .forEach { generateMaterialItem(tagPrefix, it, registrate) }
                }
            }
        MATERIAL_ITEMS = MATERIAL_ITEMS_BUILDER.build()
    }

    fun generateMaterialItem(tagPrefix: TagPrefix, material: Material, registrate: GTRegistrate) {
        MATERIAL_ITEMS_BUILDER.put(tagPrefix, material, registrate
                .item(tagPrefix.idPattern().format(material.name))
                { properties: Item.Properties -> tagPrefix.itemConstructor()
                .create(material.hasFlag(MaterialFlags.FIRE_RESISTANT) then properties.fireResistant() or properties, tagPrefix, material) }
                .setData(ProviderType.LANG, NonNullBiConsumer.noop())
                .transform(GTItems.unificationItem(tagPrefix, material))
                .properties { p -> p.stacksTo(tagPrefix.maxStackSize()) }
                .model(NonNullBiConsumer.noop())
                .color { Supplier { TagPrefixItem.tintColor(material) } }
                .onRegister { GTItems.cauldronInteraction(it) }
                .register()
        )
    }
}