package rain.gtetcore.gtet.api.create

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.machine.multiblock.IBatteryData
import com.gregtechceu.gtceu.common.block.BatteryBlock
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Blocks
import rain.gtetcore.gtet.api.ETREGISTRATE

class CreateBatteryBlock(private val batteryData: IBatteryData) {

    val entry: BlockEntry<BatteryBlock> = ETREGISTRATE.etreg
        .block("${batteryData.batteryName}_battery") { p -> BatteryBlock(p, batteryData) }
        .initialProperties { Blocks.IRON_BLOCK }
        .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
        .blockstate(GTModels.createBatteryBlockModel(batteryData))
        .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
        .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
        .build()
        .register()

    init {
        GTCEuAPI.PSS_BATTERIES[batteryData] = entry
    }
}
