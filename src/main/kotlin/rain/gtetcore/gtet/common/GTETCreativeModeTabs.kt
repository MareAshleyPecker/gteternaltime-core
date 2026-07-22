package rain.gtetcore.gtet.common

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs
import com.gregtechceu.gtceu.common.data.GTItems
import com.gregtechceu.gtceu.common.data.GTMachines
import com.gregtechceu.gtceu.common.data.machines.GTMultiMachines
import com.tterrag.registrate.util.entry.RegistryEntry
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs.BLOCK
import rain.gtetcore.gtet.common.GTETCreativeModeTabs.FLUID
import rain.gtetcore.gtet.common.GTETCreativeModeTabs.ITEM
import rain.gtetcore.gtet.common.GTETCreativeModeTabs.MACHINE
import rain.gtetcore.gtet.common.GTETCreativeModeTabs.MULTIBLOCK
import rain.gtetcore.gtet.common.GTETCreativeModeTabs.ORE
import rain.gtetcore.gtet.util.lang.LangUtil

object GTETCreativeModeTabs {

    @JvmStatic
    fun registerTab(registrate: GTRegistrate, modId: String, name: String, icon: () -> ItemStack, titleDefault: String, titleCN: String = titleDefault): RegistryEntry<CreativeModeTab> {
        LangUtil.TAB_LANG[name] = titleCN
        return registrate.defaultCreativeTab(name) { builder ->
            builder.displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator(name, registrate))
                .icon(icon).title(registrate.addLang("itemGroup", ResourceLocation.fromNamespaceAndPath(modId, name), titleDefault)).build()
        }.register()
    }

    @JvmField val MACHINE: RegistryEntry<CreativeModeTab> = registerTab(ETRegistrate, Gtetcore.MODID, "machine", { GTMachines.ELECTROLYZER[GTValues.LV].asStack() }, "GTET Machines", "GTET 机器")
    @JvmField val ITEM: RegistryEntry<CreativeModeTab> = registerTab(ETRegistrate, Gtetcore.MODID, "item", { GTItems.BATTERY_HULL_LV.asStack() }, "GTET Items", "GTET 物品")
    @JvmField val BLOCK: RegistryEntry<CreativeModeTab> = registerTab(ETRegistrate, Gtetcore.MODID, "block", { GTBlocks.COIL_NAQUADAH.asStack() }, "GTET Blocks", "GTET 方块")
    @JvmField val MULTIBLOCK: RegistryEntry<CreativeModeTab> = registerTab(ETRegistrate, Gtetcore.MODID, "multiblock", { GTMultiMachines.LARGE_BOILER_BRONZE.asStack() }, "GTET Multiblocks", "GTET 多方块")
    @JvmField val FLUID: RegistryEntry<CreativeModeTab> = registerTab(ETRegistrate, Gtetcore.MODID, "fluid", { net.minecraft.world.item.Items.WATER_BUCKET.defaultInstance }, "GTET Fluids", "GTET 流体")
    @JvmField val ORE: RegistryEntry<CreativeModeTab> = registerTab(ETRegistrate, Gtetcore.MODID, "ore", { net.minecraft.world.item.Items.DIAMOND_ORE.defaultInstance }, "GTET Ores", "GTET 矿石")

    fun init() {}
}
