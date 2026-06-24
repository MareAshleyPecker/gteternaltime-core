package rain.gtetcore.gtet.api.create.casingblock

import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import rain.gtetcore.gtet.api.ETREGISTRATE
import java.util.function.Supplier

/**
 * 标准外壳方块。
 *
 * @param name     方块注册名
 * @param texture  纹理 ResourceLocation
 */
class CreateCasingBlock(
    private val name: String,
    private val texture: ResourceLocation
) {
    val entry: BlockEntry<Block> = ETREGISTRATE.etreg.block(name) { Block(it) }
        .initialProperties { Blocks.IRON_BLOCK }
        .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
        .addLayer { Supplier { RenderType.solid() } }
        .exBlockstate(GTModels.cubeAllModel(texture))
        .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
        .item { block, _ -> BlockItem(block, Item.Properties()) }
        .build()
        .register()
}