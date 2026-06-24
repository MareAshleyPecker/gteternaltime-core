package rain.gtetcore.gtet.api.create

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.common.block.CoilBlock
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Blocks
import rain.gtetcore.gtet.api.ETREGISTRATE
import java.util.function.Supplier

class CreateCoilBlock(private val coilType: ICoilType) {

    val entry: BlockEntry<CoilBlock>

    init {
        val blockName = coilType.getName() + "_coil_block"
        entry = ETREGISTRATE.etreg
            .block(blockName) { p -> CoilBlock(p, coilType) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createCoilModel(coilType))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        GTCEuAPI.HEATING_COILS[coilType] = entry
    }
}
