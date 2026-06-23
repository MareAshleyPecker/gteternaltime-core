package rain.gtetcore.gtet.api

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.block.*
import com.gregtechceu.gtceu.api.machine.multiblock.IBatteryData
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.block.*
import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import com.tterrag.registrate.util.nullness.NonNullFunction
import com.tterrag.registrate.util.nullness.NonNullSupplier
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.GlassBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import rain.gtetcore.gtet.Gtetcore
import java.util.*
import java.util.function.Supplier

object ETREGISTRATE {

    @JvmStatic
    val ETREGISTRATE: GTRegistrate = GTRegistrate.create(Gtetcore.MODID)

    // ============================================================
    //  Casing blocks
    // ============================================================

    @JvmStatic
    fun createCasingBlock(name: String, texture: ResourceLocation): BlockEntry<Block> {
        return createCasingBlock(
            name,
            { Block(it) },
            texture,
            NonNullSupplier { Blocks.IRON_BLOCK },
            { Supplier { RenderType.solid() } }
        )
    }

    @JvmStatic
    fun createCasingBlock(
        name: String,
        blockSupplier: NonNullFunction<BlockBehaviour.Properties, Block>,
        texture: ResourceLocation,
        properties: NonNullSupplier<out Block>,
        type: Supplier<Supplier<RenderType>>
    ): BlockEntry<Block> {
        return ETREGISTRATE.block(name, blockSupplier)
            .initialProperties(properties)
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .addLayer(type)
            .exBlockstate(GTModels.cubeAllModel(texture))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
    }

    @JvmStatic
    fun createSidedCasingBlock(name: String, texture: ResourceLocation): BlockEntry<Block> {
        return ETREGISTRATE.block(name) { Block(it) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .blockstate(GTModels.createSidedCasingModel(texture))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
    }

    @JvmStatic
    fun createBrickCasingBlock(name: String, texture: ResourceLocation): BlockEntry<Block> {
        return ETREGISTRATE.block(name) { Block(it) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .exBlockstate(GTModels.cubeAllModel(texture))
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
    }

    @JvmStatic
    fun createGlassCasingBlock(
        name: String,
        texture: ResourceLocation,
        type: Supplier<Supplier<RenderType>>
    ): BlockEntry<GlassBlock> {
        return ETREGISTRATE.block(name) { p: BlockBehaviour.Properties -> GlassBlock(p) }
            .initialProperties { Blocks.GLASS }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .addLayer(type)
            .exBlockstate(GTModels.cubeAllModel(texture))
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
    }

    // ============================================================
    //  Machine casing (by tier)
    // ============================================================

    @JvmStatic
    fun createMachineCasingBlock(tier: Int): BlockEntry<Block> {
        val tierName = GTValues.VN[tier].lowercase(Locale.ROOT)
        val entry = ETREGISTRATE
            .block("${tierName}_machine_casing") { Block(it) }
            .lang("%s Machine Casing".format(GTValues.VN[tier]))
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .blockstate(GTModels.createMachineCasingModel(tierName))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        if (!GTCEuAPI.isHighTier() && tier > GTValues.UHV) {
            ETREGISTRATE.setCreativeTab(entry, null)
        }
        return entry
    }

    // ============================================================
    //  Hermetic casing
    // ============================================================

    @JvmStatic
    fun createHermeticCasing(tier: Int): BlockEntry<Block> {
        val tierName = GTValues.VN[tier].lowercase(Locale.ROOT)
        val entry = ETREGISTRATE
            .block("${tierName}_hermetic_casing") { Block(it) }
            .lang("Hermetic Casing %s".format(GTValues.LVT[tier]))
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createHermeticCasingModel(tierName))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        if (!GTCEuAPI.isHighTier() && tier > GTValues.UHV) {
            ETREGISTRATE.setCreativeTab(entry, null)
        }
        return entry
    }

    // ============================================================
    //  Steam casing
    // ============================================================

    @JvmStatic
    fun createSteamCasing(name: String, material: String): BlockEntry<Block> {
        return ETREGISTRATE.block(name) { Block(it) }
            .initialProperties { Blocks.IRON_BLOCK }
            .blockstate(GTModels.createSteamCasingModel(material))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
    }

    // ============================================================
    //  Coil block
    // ============================================================

    @JvmStatic
    fun createCoilBlock(coilType: ICoilType): BlockEntry<CoilBlock> {
        val coilBlock = ETREGISTRATE
            .block("${coilType.name}_coil_block") { p -> CoilBlock(p, coilType) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createCoilModel(coilType))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        GTCEuAPI.HEATING_COILS[coilType] = coilBlock
        return coilBlock
    }

    // ============================================================
    //  Battery block
    // ============================================================

    @JvmStatic
    fun createBatteryBlock(batteryData: IBatteryData): BlockEntry<BatteryBlock> {
        val batteryBlock = ETREGISTRATE
            .block("${batteryData.batteryName}_battery") { p -> BatteryBlock(p, batteryData) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .blockstate(GTModels.createBatteryBlockModel(batteryData))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        GTCEuAPI.PSS_BATTERIES[batteryData] = batteryBlock
        return batteryBlock
    }

    // ============================================================
    //  Fusion casing
    // ============================================================

    @JvmStatic
    fun createFusionCasing(casingType: IFusionCasingType): BlockEntry<FusionCasingBlock> {
        val casingBlock = ETREGISTRATE
            .block(casingType.serializedName) { p -> FusionCasingBlock(p, casingType) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.strength(5.0f, 10.0f).sound(SoundType.METAL) }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createFusionCasingModel(casingType))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH, CustomTags.TOOL_TIERS[casingType.harvestLevel])
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        GTBlocks.ALL_FUSION_CASINGS[casingType] = casingBlock
        return casingBlock
    }

    // ============================================================
    //  Cleanroom filter
    // ============================================================

    @JvmStatic
    fun createCleanroomFilter(filterType: IFilterType): BlockEntry<Block> {
        val filterBlock = ETREGISTRATE.block(filterType.serializedName) { Block(it) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.strength(2.0f, 8.0f).sound(SoundType.METAL).isValidSpawn { _, _, _, _ -> false } }
            .blockstate(GTModels.createCleanroomFilterModel(filterType))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH, CustomTags.TOOL_TIERS[1])
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        GTCEuAPI.CLEANROOM_FILTERS[filterType] = filterBlock
        return filterBlock
    }

    // ============================================================
    //  Active casing
    // ============================================================

    @JvmStatic
    fun createActiveCasing(name: String, baseModelPath: String): BlockEntry<ActiveBlock> {
        return ETREGISTRATE.block(name) { p: BlockBehaviour.Properties -> ActiveBlock(p) }
            .initialProperties { Blocks.IRON_BLOCK }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createActiveModel(Gtetcore.id(baseModelPath)))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .model { ctx, prov -> prov.withExistingParent(prov.name(ctx), Gtetcore.id(baseModelPath)) }
            .build()
            .register()
    }

    // ============================================================
    //  Firebox casing
    // ============================================================

    @JvmStatic
    fun createFireboxCasing(type: BoilerFireboxType): BlockEntry<ActiveBlock> {
        val block = ETREGISTRATE
            .block("${type.name()}_casing") { p: BlockBehaviour.Properties -> ActiveBlock(p) }
            .initialProperties { Blocks.IRON_BLOCK }
            .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .blockstate(GTModels.createFireboxModel(type))
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
            .build()
            .register()
        GTBlocks.ALL_FIREBOXES[type] = block
        return block
    }
}
