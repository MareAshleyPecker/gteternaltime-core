package rain.gtetcore.gtet.util

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.registries.ForgeRegistries
import rain.gtetcore.gtet.Gtetcore

/**
 * 注册表按 id 查东西 / 反查 id 的小工具（结构导出的生成代码里会用到）。
 *
 * 查不到时**不抛异常**，打一条 error 日志并回退到空气（水），
 * 这样导出的脚本在目标整合包里缺东西时是"少一个方块"而不是直接崩。
 *
 * @author rain fox
 */
object RegistriesUtil {

    @JvmStatic
    fun BlockId(block: Block): String = ForgeRegistries.BLOCKS.getKey(block)!!.toString()

    @JvmStatic
    fun getBlock(string: String): Block {
        val block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(string))
        if (block == null) {
            Gtetcore.LOGGER.error("Block {} is null", string)
            return Blocks.AIR
        }
        return block
    }

    @JvmStatic
    fun ItemId(item: Item): String = ForgeRegistries.ITEMS.getKey(item)!!.toString()

    @JvmStatic
    fun getItem(string: String): Item {
        val item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(string))
        if (item == null) {
            Gtetcore.LOGGER.error("Item {} is null", string)
            return Items.AIR
        }
        return item
    }

    @JvmStatic
    fun getFluid(string: String): Fluid {
        val fluid = ForgeRegistries.FLUIDS.getValue(ResourceLocation.tryParse(string))
        if (fluid == null) {
            Gtetcore.LOGGER.error("Fluid {} is null", string)
            return Fluids.WATER
        }
        return fluid
    }

    @JvmStatic
    fun FluidId(fluids: Fluid): String = ForgeRegistries.FLUIDS.getKey(fluids)!!.toString()
}
