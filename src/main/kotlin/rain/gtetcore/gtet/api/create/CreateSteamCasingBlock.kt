package rain.gtetcore.gtet.api.create

import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import rain.gtetcore.gtet.api.ETREGISTRATE

class CreateSteamCasingBlock(
    private val name: String,
    private val material: String
) {

    val entry: BlockEntry<Block> = ETREGISTRATE.etreg.block(name) { Block(it) }
        .initialProperties { Blocks.IRON_BLOCK }
        .blockstate(GTModels.createSteamCasingModel(material))
        .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
        .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
        .build()
        .register()
}
