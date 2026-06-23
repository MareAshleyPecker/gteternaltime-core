package rain.gtetcore.gtet.common

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs
import com.gregtechceu.gtceu.common.data.GTItems
import com.gregtechceu.gtceu.common.data.GTMachines
import com.gregtechceu.gtceu.common.data.machines.GTMultiMachines
import com.tterrag.registrate.util.entry.RegistryEntry
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Items
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.ETREGISTRATE.ETREGISTRATE

object GTETCreativeModeTabs {

    @JvmField
    val MACHINE: RegistryEntry<CreativeModeTab> = ETREGISTRATE
        .defaultCreativeTab("machine") { builder ->
            builder
                .displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator("machine", ETREGISTRATE))
                .icon { GTMachines.ELECTROLYZER[GTValues.LV].asStack() }
                .title(ETREGISTRATE.addLang("itemGroup", Gtetcore.id("machine"), "GTET Machines"))
                .build()
        }.register()

    @JvmField
    val ITEM: RegistryEntry<CreativeModeTab> = ETREGISTRATE
        .defaultCreativeTab("item") { builder ->
            builder
                .displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator("item", ETREGISTRATE))
                .icon { GTItems.BATTERY_HULL_LV.asStack() }
                .title(ETREGISTRATE.addLang("itemGroup", Gtetcore.id("item"), "GTET Items"))
                .build()
        }.register()

    @JvmField
    val BLOCK: RegistryEntry<CreativeModeTab> = ETREGISTRATE
        .defaultCreativeTab("block") { builder ->
            builder
                .displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator("block", ETREGISTRATE))
                .icon { GTBlocks.COIL_NAQUADAH.asStack() }
                .title(ETREGISTRATE.addLang("itemGroup", Gtetcore.id("block"), "GTET Blocks"))
                .build()
        }.register()

    @JvmField
    val MULTIBLOCK: RegistryEntry<CreativeModeTab> = ETREGISTRATE
        .defaultCreativeTab("multiblock") { builder ->
            builder
                .displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator("multiblock", ETREGISTRATE))
                .icon { GTMultiMachines.LARGE_BOILER_BRONZE.asStack() }
                .title(ETREGISTRATE.addLang("itemGroup", Gtetcore.id("multiblock"), "GTET Multiblocks"))
                .build()
        }.register()

    @JvmStatic
    fun init() {}
}
