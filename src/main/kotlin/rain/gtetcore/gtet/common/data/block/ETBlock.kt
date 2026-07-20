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

object ETBlock {

    /**
     * 注册方块到 [GTETCreativeModeTabs.BLOCK] 创造模式选项卡。
     * `init {}` 等价于 Java 中的 `static {}` 静态初始化块。
     */
    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.BLOCK)
    }

    /** 在 [rain.gtetcore.gtet.init.CommonProxy.kotlinInit] 中调用。 */
    @SuppressWarnings("all")
    fun init() {
        val coilRtMalloy = createCoilBlock(CoilType.NAME)
    }

    /**
     * 创建一个线圈方块并注册到 GTCEu 的 [GTCEuAPI.HEATING_COILS] 映射中。
     */
    fun createCoilBlock(coilType: ICoilType): BlockEntry<CoilBlock?> {
        val coilBlock = ETRegistrate.block("%s_coil_block".format(coilType.name)) { props -> CoilBlock(props, coilType) }
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
