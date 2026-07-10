package rain.gtetcore.gtet.common.data.block

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.common.block.CoilBlock
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import com.tterrag.registrate.util.nullness.NonNullBiFunction
import com.tterrag.registrate.util.nullness.NonNullSupplier
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import org.jetbrains.annotations.NotNull
import rain.gtetcore.gtet.api.registrate.ETREGISTRATE.ETRegistrate
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import java.util.function.Supplier


object ETBlock {
    /**
     * init {} 约等于 java ： static {}
     *
     * block init to {GTETCreativeModeTabs.BLOCK}
     *
     * 该init下面（不是里面）都将注册到GTETCreativeModeTabs.BLOCK
     * */
    init {
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.BLOCK)
    }

    /** -> [rain.gtetcore.gtet.common.CommonProxy.kotlinInit] */
    @SuppressWarnings("all")
    fun init() {
        val COIL_RTMALLOY : BlockEntry<CoilBlock?> = createCoilBlock(CoilType.NAME)
    }


    @NotNull
    fun createCoilBlock(coilType: ICoilType): BlockEntry<CoilBlock?> {
        val coilBlock = ETRegistrate
            .block("%s_coil_block".format(coilType.name)) { p: BlockBehaviour.Properties? -> CoilBlock(p!!, coilType) }
            .initialProperties(NonNullSupplier { Blocks.IRON_BLOCK })
            .properties { p: BlockBehaviour.Properties? ->
                p!!.isValidSpawn { state: BlockState?, level: BlockGetter?, pos: BlockPos?, ent: EntityType<*>? -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate { ctx, prov ->
                val model = prov.models().cubeAll(ctx.name, coilType.texture)
                prov.simpleBlock(ctx.get(), model)
            }
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item(NonNullBiFunction { pBlock: CoilBlock?, pProperties: Item.Properties? -> BlockItem(pBlock!!, pProperties!!) })
            .build()
            .register()
        @Suppress("UNCHECKED_CAST")
        GTCEuAPI.HEATING_COILS[coilType] = coilBlock as Supplier<CoilBlock>
        return coilBlock
    }
}
