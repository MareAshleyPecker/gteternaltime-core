package rain.gtetcore.gtet.api.create

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.block.IFilterType
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SoundType
import rain.gtetcore.gtet.api.ETREGISTRATE

class CreateCleanroomFilterBlock(private val filterType: IFilterType) {

    val entry: BlockEntry<Block> = ETREGISTRATE.etreg.block(filterType.serializedName) { Block(it) }
        .initialProperties { Blocks.IRON_BLOCK }
        .properties { p ->
            p.strength(2.0f, 8.0f).sound(SoundType.METAL)
                .isValidSpawn { _, _, _, _ -> false }
        }
        .blockstate(GTModels.createCleanroomFilterModel(filterType))
        .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH, CustomTags.TOOL_TIERS[1])
        .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
        .build()
        .register()

    init {
        GTCEuAPI.CLEANROOM_FILTERS[filterType] = entry
    }
}
