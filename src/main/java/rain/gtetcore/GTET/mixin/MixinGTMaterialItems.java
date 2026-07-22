package rain.gtetcore.GTET.mixin;

import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.item.TagPrefixItem;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.data.GTMaterialItems;
import com.gregtechceu.gtceu.common.registry.GTRegistration;

import net.minecraft.world.item.Item;

import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import org.spongepowered.asm.mixin.Unique;
import rain.gtetcore.gtet.api.registrate.OnlyETreg;
import rain.gtetcore.gtet.common.GTETCreativeModeTabs;

/**
 * 覆写 GTCEu 的 {@link GTMaterialItems#generateMaterialItems()}，
 * 确保本模组的材料物品注册到 GTET 自己的标签页。
 * <p>
 * 参照 GTOCore {@code GTMaterialItemsMixin} 的实现模式。
 */
@Mixin(value = GTMaterialItems.class, remap = false)
public abstract class MixinGTMaterialItems {

    @Shadow
    static ImmutableTable.Builder<TagPrefix, Material, ItemEntry<? extends Item>> MATERIAL_ITEMS_BUILDER;

    @Shadow
    public static Table<TagPrefix, Material, ItemEntry<? extends Item>> MATERIAL_ITEMS;

    /**
     * @author GTET
     * @reason 将本模组材料物品定向到 GTET 自己的标签页
     */
    @Overwrite
    public static void generateMaterialItems() {
        GTRegistration.REGISTRATE.creativeModeTab(() -> com.gregtechceu.gtceu.common.data.GTCreativeModeTabs.MATERIAL_ITEM);
        OnlyETreg.INSTANCE.getETRegistrate().creativeModeTab(GTETCreativeModeTabs.ITEM);

        for (TagPrefix tagPrefix : TagPrefix.values()) {
            if (tagPrefix.doGenerateItem()) {
                for (MaterialRegistry registry : GTCEuAPI.materialManager.getRegistries()) {
                    GTRegistrate registrate = registry.getRegistrate();
                    for (Material material : registry.getAllMaterials()) {
                        if (tagPrefix.doGenerateItem(material)) {
                            gteternaltime_core$generateMaterialItem(tagPrefix, material, registrate);
                        }
                    }
                }
            }
        }
        MATERIAL_ITEMS = MATERIAL_ITEMS_BUILDER.build();
    }

    @Unique
    private static void gteternaltime_core$generateMaterialItem(TagPrefix tagPrefix, Material material, GTRegistrate registrate) {
        MATERIAL_ITEMS_BUILDER.put(tagPrefix, material, registrate
                .item(tagPrefix.idPattern().formatted(material.getName()),
                        properties -> tagPrefix.itemConstructor()
                                .create(properties, tagPrefix, material))
                .setData(ProviderType.LANG, NonNullBiConsumer.noop())
                .transform(GTItems.unificationItem(tagPrefix, material))
                .properties(p -> p.stacksTo(tagPrefix.maxStackSize()))
                .model(NonNullBiConsumer.noop())
                .color(() -> () -> TagPrefixItem.tintColor(material))
                .onRegister(GTItems::cauldronInteraction)
                .register());
    }
}
