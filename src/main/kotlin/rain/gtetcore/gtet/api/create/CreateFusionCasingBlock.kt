package rain.gtetcore.gtet.api.create

import com.gregtechceu.gtceu.api.block.IFusionCasingType
import com.gregtechceu.gtceu.common.block.FusionCasingBlock
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SoundType
import rain.gtetcore.gtet.api.ETREGISTRATE
import java.util.function.Supplier

class CreateFusionCasingBlock(private val casingType: IFusionCasingType) {

    val entry: BlockEntry<FusionCasingBlock> = ETREGISTRATE.etreg
        .block(casingType.serializedName) { p -> FusionCasingBlock(p, casingType) }
        .initialProperties { Blocks.IRON_BLOCK }
        .properties { p -> p.strength(5.0f, 10.0f).sound(SoundType.METAL) }
        .addLayer { Supplier { RenderType.cutoutMipped() } }
        .blockstate(GTModels.createFusionCasingModel(casingType))
        .tag(
            CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH,
            CustomTags.TOOL_TIERS[casingType.harvestLevel]
        )
        .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
        .build()
        .register()

    init {
        GTBlocks.ALL_FUSION_CASINGS[casingType] = entry
    }
}
