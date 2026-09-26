package rain.gtetcore.gtet.common.block

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.block.ActiveBlock
import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.common.block.CoilBlock
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.common.registry.GTRegistration
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.providers.DataGenContext
import com.tterrag.registrate.providers.RegistrateItemModelProvider
import com.tterrag.registrate.util.entry.BlockEntry
import com.tterrag.registrate.util.nullness.NonNullBiFunction
import com.tterrag.registrate.util.nullness.NonNullFunction
import com.tterrag.registrate.util.nullness.NonNullSupplier
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.GlassBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.data.GTETCreativeModeTabs
import rain.gtetcore.gtet.util.GtocoreAssets
import rain.gtetcore.gtet.util.lang.LangUtil
import java.util.function.Supplier

object ETBlockReg {
    /** 玻璃机壳。 */
    fun createGlassCasingBlock(name: String, cn: String, texture: ResourceLocation?): BlockEntry<GlassBlock?> {
        LangUtil.BLOCK_LANG[name] = cn
        return ETRegistrate.block(name) { name: BlockBehaviour.Properties? -> GlassBlock(name!!) }
            .initialProperties(NonNullSupplier { Blocks.GLASS })
            .properties { p: BlockBehaviour.Properties? -> p!!.isValidSpawn { state: BlockState?, level: BlockGetter?, pos: BlockPos?, ent: EntityType<*>? -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .exBlockstate(GTModels.cubeAllModel(texture))
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .item(NonNullBiFunction { pBlock: GlassBlock?, pProperties: Item.Properties? ->
                BlockItem(pBlock!!, pProperties!!)
            })
            .build()
            .register()
    }

    fun createGlassCasingBlock(name: String, cn: String, texture: ResourceLocation?, type: Supplier<Supplier<RenderType?>?>): BlockEntry<GlassBlock?> {
        LangUtil.BLOCK_LANG[name] = cn
        return ETRegistrate.block(name) { name: BlockBehaviour.Properties? -> GlassBlock(name!!) }
            .initialProperties(NonNullSupplier { Blocks.GLASS })
            .properties { p: BlockBehaviour.Properties? -> p!!.isValidSpawn { state: BlockState?, level: BlockGetter?, pos: BlockPos?, ent: EntityType<*>? -> false } }
            .addLayer(type)
            .exBlockstate(GTModels.cubeAllModel(texture))
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .item(NonNullBiFunction { pBlock: GlassBlock?, pProperties: Item.Properties? ->
                BlockItem(pBlock!!, pProperties!!)
            })
            .build()
            .register()
    }
    /** 目录型机壳：目录里只有 side.png + top.png，用 cubeColumn 拼。 */
    fun createSidedCasingBlock(name: String, cn: String, texture: ResourceLocation?): BlockEntry<Block?> {
        LangUtil.BLOCK_LANG[name] = cn
        return ETRegistrate.block(name) { p: BlockBehaviour.Properties -> Block(p) }
            .initialProperties(NonNullSupplier { Blocks.IRON_BLOCK })
            .properties { p: BlockBehaviour.Properties? -> p!!.isValidSpawn { _: BlockState?, _: BlockGetter?, _: BlockPos?, _: EntityType<*>? -> false } }
            .addLayer { Supplier { RenderType.solid() } }
            .exBlockstate { ctx, prov ->
                prov.simpleBlock(ctx.get(), prov.models().cubeColumn(ctx.name, texture!!.withSuffix("/side"), texture.withSuffix("/top")))
            }
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item(NonNullBiFunction { pBlock: Block?, pProperties: Item.Properties? ->
                BlockItem(pBlock!!, pProperties!!)
            })
            .build()
            .register()
    }


    fun createCasingBlock(name: String, cn: String, texture: ResourceLocation?): BlockEntry<Block?> {
        return createCasingBlock(
            name, cn, { pProperties: BlockBehaviour.Properties? -> Block(pProperties!!) },
            texture, NonNullSupplier { Blocks.IRON_BLOCK }, { Supplier { RenderType.solid() } })
    }

    fun createCasingBlock(name: String,
                          cn: String,
                          blockSupplier: NonNullFunction<BlockBehaviour.Properties, Block>,
                          texture: ResourceLocation?,
                          properties: NonNullSupplier<out Block>,
                          type: Supplier<Supplier<RenderType?>?>
    ): BlockEntry<Block?> {
        LangUtil.BLOCK_LANG[name] = cn
        return ETRegistrate.block(name, blockSupplier)
            .initialProperties(properties)
            .properties { p: BlockBehaviour.Properties? -> p!!.isValidSpawn { _: BlockState?, _: BlockGetter?, _: BlockPos?, _: EntityType<*>? -> false } }
            .addLayer(type)
            .exBlockstate(GTModels.cubeAllModel(texture))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item(NonNullBiFunction { pBlock: Block?, pProperties: Item.Properties? ->
                BlockItem(pBlock!!, pProperties!!)
            })
            .build()
            .register()
    }


    fun createActiveCasing(name: String,cn: String, baseModelPath: String): BlockEntry<ActiveBlock?> {
        LangUtil.BLOCK_LANG[name] = cn
        return GTRegistration.REGISTRATE.block(name) { properties: BlockBehaviour.Properties? -> ActiveBlock(properties!!) }
            .initialProperties(NonNullSupplier { Blocks.IRON_BLOCK })
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createActiveModel(GtocoreAssets.gtocoreTexture(baseModelPath)))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item(NonNullBiFunction { pBlock: ActiveBlock?, pProperties: Item.Properties? ->
                BlockItem(pBlock!!, pProperties!!)
            })
            .model { ctx: DataGenContext<Item?, BlockItem?>?, prov: RegistrateItemModelProvider? ->
                prov!!.withExistingParent(prov.name(ctx!!), GTCEu.id(baseModelPath))
            }
            .build()
            .register()
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

    /** 星辰石：12 级发光方块，共用一张贴图。 */
    class GlowingBlock(properties: Properties, lightLevel: Int, color: MapColor) :
        Block(properties.lightLevel { _ -> lightLevel.coerceIn(0, 15) }.mapColor(color))

    private val STAR_STONE_NUM = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")

    fun createStarStone(): Array<BlockEntry<GlowingBlock>> = Array(STAR_STONE_NUM.size) { i ->
        LangUtil.BLOCK_LANG["star_stone_${i + 1}"] = "星辰石 " + STAR_STONE_NUM[i]
        OnlyETreg.ETRegistrate.block("star_stone_${i + 1}") { p: BlockBehaviour.Properties ->
            GlowingBlock(p, i + 4, MapColor.TERRACOTTA_WHITE)
        }
            .lang("Star Stone " + STAR_STONE_NUM[i])
            .exBlockstate { ctx, prov ->
                prov.simpleBlock(
                    ctx.get(),
                    prov.models().cubeAll(ctx.name, ResourceLocation.fromNamespaceAndPath(GtocoreAssets.NAMESPACE, "block/star_stone"))
                )
            }
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .item(NonNullBiFunction { pBlock: GlowingBlock?, pProperties: Item.Properties? ->
                BlockItem(pBlock!!, pProperties!!)
            })
            .build()
            .register()
    }

}