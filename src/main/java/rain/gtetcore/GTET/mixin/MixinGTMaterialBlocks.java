package rain.gtetcore.GTET.mixin;

import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.block.MaterialBlock;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.item.MaterialBlockItem;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.gregtechceu.gtceu.common.data.GTMaterialBlocks;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.entry.BlockEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import org.spongepowered.asm.mixin.Unique;
import rain.gtetcore.gtet.api.registrate.OnlyETreg;
import rain.gtetcore.gtet.common.GTETCreativeModeTabs;

/**
 * 覆写 GTCEu 的 {@link GTMaterialBlocks#generateMaterialBlocks()}，
 * 确保本模组的材料方块注册到 GTET 自己的标签页。
 * <p>
 * 参照 GTOCore {@code GTMaterialBlocksMixin} 的实现模式。
 */
@SuppressWarnings("removal")
@Mixin(value = GTMaterialBlocks.class, remap = false)
public abstract class MixinGTMaterialBlocks {

    @Shadow
    static ImmutableTable.Builder<TagPrefix, Material, BlockEntry<? extends Block>> MATERIAL_BLOCKS_BUILDER;

    @Shadow
    public static Table<TagPrefix, Material, BlockEntry<? extends Block>> MATERIAL_BLOCKS;

    /**
     * @author GTET
     * @reason 将本模组材料方块定向到 GTET 自己的标签页
     */
    @Overwrite
    public static void generateMaterialBlocks() {
        OnlyETreg.INSTANCE.getETRegistrate().creativeModeTab(GTETCreativeModeTabs.BLOCK);

        for (TagPrefix tagPrefix : TagPrefix.values()) {
            if (!TagPrefix.ORES.containsKey(tagPrefix) && tagPrefix.doGenerateBlock()) {
                for (MaterialRegistry registry : GTCEuAPI.materialManager.getRegistries()) {
                    GTRegistrate registrate = registry.getRegistrate();
                    for (Material material : registry.getAllMaterials()) {
                        if (tagPrefix.doGenerateBlock(material)) {
                            gteternaltime_core$registerMaterialBlock(tagPrefix, material, registrate);
                        }
                    }
                }
            }
        }
    }

    @Unique
    private static void gteternaltime_core$registerMaterialBlock(TagPrefix tagPrefix, Material material, GTRegistrate registrate) {
        MATERIAL_BLOCKS_BUILDER.put(tagPrefix, material, registrate
                .block(tagPrefix.idPattern().formatted(material.getName()),
                        properties -> tagPrefix.blockConstructor().create(properties, tagPrefix, material))
                .initialProperties(() -> Blocks.IRON_BLOCK)
                .properties(p -> tagPrefix.blockProperties().properties().apply(p).noLootTable())
                .transform(GTBlocks.unificationBlock(tagPrefix, material))
                .addLayer(tagPrefix.blockProperties().renderType())
                .setData(ProviderType.BLOCKSTATE, NonNullBiConsumer.noop())
                .setData(ProviderType.LANG, NonNullBiConsumer.noop())
                .setData(ProviderType.LOOT, NonNullBiConsumer.noop())
                .color(() -> MaterialBlock::tintedColor)
                .item((b, p) -> tagPrefix.blockItemConstructor().create(b, p, tagPrefix, material))
                .model(NonNullBiConsumer.noop())
                .color(() -> () -> MaterialBlockItem.tintColor(material))
                .build()
                .register());
    }
}
