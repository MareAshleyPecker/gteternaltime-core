package rain.gtetcore.gtet.api.create

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import rain.gtetcore.gtet.api.ETREGISTRATE
import java.util.*
import java.util.function.Supplier

class CreateHermeticCasingBlock(private val tier: Int) {

    val entry: BlockEntry<Block>

    init {
        val tierName = GTValues.VN[tier].lowercase(Locale.ROOT)
        entry = ETREGISTRATE.etreg
            .block("${tierName}_hermetic_casing") { Block(it) }
            .lang("Hermetic Casing %s".format(GTValues.LVT[tier]))
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createHermeticCasingModel(tierName))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        if (!GTCEuAPI.isHighTier() && tier > GTValues.UHV) {
            ETREGISTRATE.etreg.setCreativeTab(entry, null)
        }
    }
}
