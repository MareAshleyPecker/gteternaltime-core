package rain.gtetcore.gtet.common

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs
import com.gregtechceu.gtceu.common.data.GTItems
import com.gregtechceu.gtceu.common.data.GTMachines
import com.gregtechceu.gtceu.common.data.machines.GTMultiMachines
import com.tterrag.registrate.util.entry.RegistryEntry
import net.minecraft.world.item.CreativeModeTab
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.registrate.ETREGISTRATE.ETRegistrate

object GTETCreativeModeTabs {

    val MACHINE: RegistryEntry<CreativeModeTab> = ETRegistrate
        .defaultCreativeTab("machine") { builder ->
            builder
                .displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator("machine", ETRegistrate))
                .icon { GTMachines.ELECTROLYZER[GTValues.LV].asStack() }
                .title(ETRegistrate.addLang("itemGroup", Gtetcore.id("machine"), "GTET Machines"))
                .build()
        }.register()

    val ITEM: RegistryEntry<CreativeModeTab> = ETRegistrate
        .defaultCreativeTab("item") { builder ->
            builder
                .displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator("item", ETRegistrate))
                .icon { GTItems.BATTERY_HULL_LV.asStack() }
                .title(ETRegistrate.addLang("itemGroup", Gtetcore.id("item"), "GTET Items"))
                .build()
        }.register()

    val BLOCK: RegistryEntry<CreativeModeTab> = ETRegistrate
        .defaultCreativeTab("block") { builder ->
            builder
                .displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator("block", ETRegistrate))
                .icon { GTBlocks.COIL_NAQUADAH.asStack() }
                .title(ETRegistrate.addLang("itemGroup", Gtetcore.id("block"), "GTET Blocks"))
                .build()
        }.register()

    val MULTIBLOCK: RegistryEntry<CreativeModeTab> = ETRegistrate
        .defaultCreativeTab("multiblock") { builder ->
            builder.displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator("multiblock", ETRegistrate))
                .icon { GTMultiMachines.LARGE_BOILER_BRONZE.asStack() }
                .title(ETRegistrate.addLang("itemGroup", Gtetcore.id("multiblock"), "GTET Multiblocks"))
                .build()
        }.register()

    fun init() {}
}
