package rain.gtetcore.gtet.common

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs
import com.gregtechceu.gtceu.common.data.GTItems
import com.gregtechceu.gtceu.common.data.GTMachines
import com.gregtechceu.gtceu.common.data.machines.GTMultiMachines
import com.tterrag.registrate.util.entry.RegistryEntry
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate

object GTETCreativeModeTabs {

    fun registerTab(name: String, icon: () -> ItemStack, titleDefault: String): RegistryEntry<CreativeModeTab> {
        return ETRegistrate.defaultCreativeTab(name) { builder ->
            builder.displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator(name, ETRegistrate))
                .icon(icon).title(ETRegistrate.addLang("itemGroup", Gtetcore.id(name), titleDefault)).build()
        }.register()
    }

    val MACHINE: RegistryEntry<CreativeModeTab> = registerTab("machine", { GTMachines.ELECTROLYZER[GTValues.LV].asStack() }, "GTET Machines")
    @JvmField val ITEM: RegistryEntry<CreativeModeTab> = registerTab("item", { GTItems.BATTERY_HULL_LV.asStack() }, "GTET Items")
    val BLOCK: RegistryEntry<CreativeModeTab> = registerTab("block", { GTBlocks.COIL_NAQUADAH.asStack() }, "GTET Blocks")
    val MULTIBLOCK: RegistryEntry<CreativeModeTab> = registerTab("multiblock", { GTMultiMachines.LARGE_BOILER_BRONZE.asStack() }, "GTET Multiblocks")

    fun init() {}
}
