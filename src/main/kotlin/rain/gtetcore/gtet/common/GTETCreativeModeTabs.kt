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
import rain.gtetcore.gtet.util.lang.TabLangUtil

/**
 * GTET 创造模式选项卡定义。
 *
 * 提供 6 个选项卡，分别容纳不同类型的注册内容：
 * - [MACHINE] — 单方块机器
 * - [ITEM] — 物品（材料部件等）
 * - [BLOCK] — 方块（线圈等）
 * - [MULTIBLOCK] — 多方块机器控制器
 * - [FLUID] — 流体容器（桶装流体等）
 * - [ORE] — 矿石方块
 *
 * 所有选项卡通过 [ETRegistrate] 统一注册，中英双语名自动生成。
 *
 * ## 给附属
 * ```kotlin
 * // Kotlin
 * GTETCreativeModeTabs.registerTab(myRegistrate, myModId, "my_tab", { iconStack }, "My Tab", "我的选项卡")
 * ```
 */
object GTETCreativeModeTabs {

    @JvmStatic
    fun registerTab(registrate: GTRegistrate, modId: String, name: String, icon: () -> ItemStack, titleDefault: String, titleCN: String = titleDefault): RegistryEntry<CreativeModeTab> {
        TabLangUtil.TAB_LANG[name] = titleCN
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
