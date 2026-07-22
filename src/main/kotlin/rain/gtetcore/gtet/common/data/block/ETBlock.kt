package rain.gtetcore.gtet.common.data.block

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.common.block.CoilBlock
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import com.tterrag.registrate.util.nullness.NonNullBiFunction
import com.tterrag.registrate.util.nullness.NonNullSupplier
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Blocks
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import java.util.function.Supplier

/**
 * 方块注册入口。
 *
 * 负责注册本模组的所有方块，当前主要提供线圈方块的创建与注册。
 * 所有方块自动归入 [GTETCreativeModeTabs.BLOCK] 选项卡。
 *
 * @see CoilType 线圈类型枚举
 * @see GTCEuAPI.HEATING_COILS GTCEu 线圈注册表
 */
object ETBlock {

    /**
     * 方块初始化入口，由 [rain.gtetcore.gtet.init.CommonProxy.kotlinInit] 调用。
     */
    @SuppressWarnings("all")
    fun init() {
        val COIL_TEST_NAME = createCoilBlock(CoilType.NAME)
    }

    /**
     * @param coilType 线圈类型，定义温度、等级、材质等属性
     * @return 已注册的方块条目
     */
    fun createCoilBlock(coilType: ICoilType): BlockEntry<CoilBlock?> {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.BLOCK)
        val blockId = "%s_coil_block".format(coilType.name)
        val coilBlock = ETRegistrate.block(blockId) { props -> CoilBlock(props, coilType) }
            .lang(com.gregtechceu.gtceu.utils.FormattingUtil.toEnglishName(coilType.name))
            .initialProperties(NonNullSupplier { Blocks.IRON_BLOCK })
            .properties { props -> props.isValidSpawn { _, _, _, _ -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate { ctx, prov ->
                val model = prov.models().cubeAll(ctx.name, coilType.texture)
                prov.simpleBlock(ctx.get(), model)
            }
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item(NonNullBiFunction { block, properties -> BlockItem(block, properties) })
            .build()
            .register()
        @Suppress("UNCHECKED_CAST")
        GTCEuAPI.HEATING_COILS[coilType] = coilBlock as Supplier<CoilBlock>
        return coilBlock
    }
}
