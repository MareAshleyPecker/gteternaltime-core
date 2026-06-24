package rain.gtetcore.gtet.api.create.casingblock

import com.gregtechceu.gtceu.common.data.models.GTModels
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import rain.gtetcore.gtet.api.ETREGISTRATE

/**
 * 砖块纹理外壳方块。
 */
class CreateBrickCasingBlock(
    private val name: String,
    private val texture: ResourceLocation
) {
    val entry: BlockEntry<Block> = ETREGISTRATE.etreg.block(name) { Block(it) }
        .initialProperties { Blocks.IRON_BLOCK }
        .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
        .exBlockstate(GTModels.cubeAllModel(texture))
        .tag(BlockTags.MINEABLE_WITH_PICKAXE)
        .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
        .build()
        .register()
}

