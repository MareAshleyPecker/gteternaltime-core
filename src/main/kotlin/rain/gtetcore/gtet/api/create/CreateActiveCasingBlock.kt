package rain.gtetcore.gtet.api.create

import com.gregtechceu.gtceu.api.block.ActiveBlock
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.ETREGISTRATE
import java.util.function.Supplier

class CreateActiveCasingBlock(
    private val name: String,
    private val baseModelPath: String
) {
    val entry: BlockEntry<ActiveBlock> = ETREGISTRATE.etreg.block(name) { p: BlockBehaviour.Properties -> ActiveBlock(p) }
        .initialProperties { Blocks.IRON_BLOCK }
        .addLayer { Supplier { RenderType.cutoutMipped() } }
        .blockstate(GTModels.createActiveModel(Gtetcore.id(baseModelPath)))
        .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
        .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
        .model { ctx, prov -> prov.withExistingParent(prov.name(ctx), Gtetcore.id(baseModelPath)) }
        .build()
        .register()
}
