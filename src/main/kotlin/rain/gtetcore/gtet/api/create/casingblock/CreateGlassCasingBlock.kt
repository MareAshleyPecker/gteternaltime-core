package rain.gtetcore.gtet.api.create.casingblock

import com.gregtechceu.gtceu.common.data.models.GTModels
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.GlassBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import rain.gtetcore.gtet.api.ETREGISTRATE
import java.util.function.Supplier

/**
 * 玻璃外壳方块。
 */
open class CreateGlassCasingBlock(
    private val name: String,
    private val texture: ResourceLocation,
    private val type: Supplier<Supplier<RenderType>>
) {
    val entry: BlockEntry<GlassBlock> = ETREGISTRATE.etreg.block(name) { p: BlockBehaviour.Properties -> GlassBlock(p) }
        .initialProperties { Blocks.GLASS }
        .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
        .addLayer(type)
        .exBlockstate(GTModels.cubeAllModel(texture))
        .tag(BlockTags.MINEABLE_WITH_PICKAXE)
        .item { block, _ -> BlockItem(block, Item.Properties()) }
        .build()
        .register()
}
