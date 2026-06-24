package rain.gtetcore.gtet.api.create

import com.gregtechceu.gtceu.api.block.ActiveBlock
import com.gregtechceu.gtceu.common.block.BoilerFireboxType
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import com.tterrag.registrate.util.nullness.NonNullFunction
import com.tterrag.registrate.util.nullness.NonNullSupplier
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.GlassBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import rain.gtetcore.gtet.api.ETREGISTRATE
import java.util.function.Supplier

class CreateFireboxCasingBlock(private val type: BoilerFireboxType) {

    val entry: BlockEntry<ActiveBlock>

    init {
        entry = ETREGISTRATE.etreg
            .block("${type.name()}_casing") { p: BlockBehaviour.Properties -> ActiveBlock(p) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createFireboxModel(type))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        GTBlocks.ALL_FIREBOXES[type] = entry
    }
}
