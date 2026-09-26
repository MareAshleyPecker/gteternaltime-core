@file:Suppress("DEPRECATION", "unused")
package rain.gtetcore.gtet.common.data.block

import com.gregtechceu.gtceu.api.block.ActiveBlock
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import com.tterrag.registrate.util.nullness.NonNullBiFunction
import com.tterrag.registrate.util.nullness.NonNullSupplier
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.GlassBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.block.ETBlockReg
import rain.gtetcore.gtet.common.block.ETBlockReg.createActiveCasing
import rain.gtetcore.gtet.common.block.ETBlockReg.createCasingBlock
import rain.gtetcore.gtet.common.block.ETBlockReg.createGlassCasingBlock
import rain.gtetcore.gtet.common.block.ETBlockReg.createSidedCasingBlock
import rain.gtetcore.gtet.common.block.ETBlockReg.createStarStone
import rain.gtetcore.gtet.util.GtocoreAssets
import rain.gtetcore.gtet.util.lang.LangUtil
import java.util.function.Supplier

/**
 * GTOCore 机壳贴图 → 批量注册成 GT 机壳方块（普通 [Block]，可被多方块结构谓词引用）。
 *
 * 贴图来自 GTOCore（LGPL-3.0），副本在 `src/main/resources/assets/gtocore/textures/`，见同目录 LICENSE.txt。
 *
 * 贴图参数一律收 `GtocoreAssets.gtocoreTexture(...)`（GUI 风格 `gtocore:textures/xxx.png`），
 * 注册时还原成模型要的 `gtocore:xxx`。
 *
 * ⚠️ 未跑 runClient：贴图显示、CTM 生效、结构谓词能否引用均未实测。
 */
object ETGtoCasingBlocks {

    /** 空方法：只要被调用就触发本对象的 <clinit>，注册全在里面，重复调用安全。 */
    fun init() {}

    /** 贴图相对 `assets/gtocore/textures/` 的路径 → 模型用 [ResourceLocation]。 */
    private fun gtoTex(path: String): ResourceLocation = ResourceLocation.tryBuild("gtocore", path)!!

    /** 目录型机壳（带发光叠层）：底层 side/top，顶层 side 换 side_bloom。 */
    fun createSidedBloomCasingBlock(name: String, cn: String, texture: ResourceLocation?): BlockEntry<Block?> {
        val base = gtoTex(texture!!.path)
        LangUtil.BLOCK_LANG[name] = cn
        return OnlyETreg.ETRegistrate.block(name) { p: BlockBehaviour.Properties -> Block(p) }
            .initialProperties(NonNullSupplier { Blocks.IRON_BLOCK })
            .properties { p: BlockBehaviour.Properties? -> p!!.isValidSpawn { _: BlockState?, _: BlockGetter?, _: BlockPos?, _: EntityType<*>? -> false } }
            .addLayer { Supplier { RenderType.cutoutMipped() } }
            .exBlockstate { ctx, prov ->
                val model = prov.models()
                    .withExistingParent(ctx.name, ResourceLocation.fromNamespaceAndPath("gtceu", "block/cube_2_layer/bottom_top"))
                    .texture("bot_bottom", base.withSuffix("/top"))
                    .texture("bot_side", base.withSuffix("/side"))
                    .texture("bot_top", base.withSuffix("/top"))
                    .texture("top_bottom", base.withSuffix("/top"))
                    .texture("top_side", base.withSuffix("/side_bloom"))
                    .texture("top_top", base.withSuffix("/top"))
                prov.simpleBlock(ctx.get(), model)
            }
            .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
            .item(NonNullBiFunction { pBlock: Block?, pProperties: Item.Properties? ->
                BlockItem(pBlock!!, pProperties!!)
            })
            .build()
            .register()
    }



    val RHENIUM_REINFORCED_ENERGY_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "rhenium_reinforced_energy_glass",
        "铼强化聚能玻璃",
         GtocoreAssets.gtocoreTexture("block/casings/rhenium_reinforced_energy_glass")
    )

    val ELECTRON_PERMEABLE_AMPROSIUM_COATED_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "electron_permeable_amprosium_coated_glass",
        "电子渗透安普洛涂层玻璃",
         GtocoreAssets.gtocoreTexture("block/casings/electron_permeable_neutronium_coated_glass")
    )

    val NON_PHOTONIC_MATTER_EXCLUSION_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "non_photonic_matter_exclusion_glass",
        "非光子物质排除玻璃",
         GtocoreAssets.gtocoreTexture("block/casings/non_photonic_matter_exclusion_glass")
    )

    val OMNI_PURPOSE_INFINITY_FUSED_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "omni_purpose_infinity_fused_glass",
        "全能无限融合玻璃",
         GtocoreAssets.gtocoreTexture("block/casings/omni_purpose_infinity_fused_glass")
    )

    val HAWKING_RADIATION_REALIGNMENT_FOCUS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "hawking_radiation_realignment_focus",
        "霍金辐射重新调整焦点",
         GtocoreAssets.gtocoreTexture("block/casings/hawking_radiation_realignment_focus")
    )

    val CHEMICAL_GRADE_GLASS: BlockEntry<GlassBlock?> =
        createGlassCasingBlock("chemical_grade_glass", "化学级玻璃",  GtocoreAssets.gtocoreTexture("block/casings/chemical_grade_glass"))

    val ANTIMATTER_CONTAINMENT_CASING: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "antimatter_containment_casing",
        "反物质隔离机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/antimatter_containment_casing")
    )

    val QUANTUM_GLASS: BlockEntry<GlassBlock?> =
        createGlassCasingBlock("quantum_glass", "量子玻璃",  GtocoreAssets.gtocoreTexture("block/casings/quantum_glass"))

    val FERMI_ENERGY_GAP_TRANSITION_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "fermi_energy_gap_transition_glass",
        "费米能隙跃迁玻璃",
         GtocoreAssets.gtocoreTexture("block/casings/fermi_energy_gap_transition_glass")
    )

    val PLASMA_FIELD_GLASS: BlockEntry<GlassBlock?> =
        createGlassCasingBlock("plasma_field_glass", "等离子体场玻璃",  GtocoreAssets.gtocoreTexture("block/casings/plasma_field_glass"))


    val FORCE_FIELD_GLASS: BlockEntry<GlassBlock?> =
        createGlassCasingBlock("force_field_glass", "力场玻璃",  GtocoreAssets.gtocoreTexture("block/force_field_glass"))

    val SPATIALLY_TRANSCENDENT_GRAVITATIONAL_LENS_BLOCK: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "spatially_transcendent_gravitational_lens_block",
        "超空间引力透镜块",
         GtocoreAssets.gtocoreTexture("block/spatially_transcendent_gravitational_lens_block")
    )


    val NAQUADRIA_CHARGE: BlockEntry<Block?> =
        createCasingBlock("naquadria_charge", "超能硅岩爆弹",  GtocoreAssets.gtocoreTexture("block/naquadria_charge"))

    val LEPTONIC_CHARGE: BlockEntry<Block?> =
        createCasingBlock("leptonic_charge", "轻子爆弹",  GtocoreAssets.gtocoreTexture("block/leptonic_charge"))

    val QUANTUM_CHROMODYNAMIC_CHARGE: BlockEntry<Block?> = createCasingBlock(
        "quantum_chromodynamic_charge",
        "量子色动力学爆弹",
         GtocoreAssets.gtocoreTexture("block/quantum_chromodynamic_charge")
    )

    val ANNIHILATE_CORE: BlockEntry<Block?> =
        createCasingBlock("annihilate_core", "湮灭核心",  GtocoreAssets.gtocoreTexture("block/annihilate_core"))

    val AMPROSIUM_PIPE_CASING: BlockEntry<Block?> =
        createCasingBlock("amprosium_pipe_casing", "安普洛管道方块",  GtocoreAssets.gtocoreTexture("block/neutronium_pipe_casing"))

    val IRIDIUM_PIPE_CASING: BlockEntry<Block?> =
        createCasingBlock("iridium_pipe_casing", "铱管道方块",  GtocoreAssets.gtocoreTexture("block/machine_casing_pipe_iridium"))

    val INCONEL_625_PIPE: BlockEntry<Block?> =
        createCasingBlock("inconel_625_pipe", "镍铬基合金-625温和分散管道",  GtocoreAssets.gtocoreTexture("block/inconel_625_pipe"))

    val HASTELLOY_N_75_PIPE: BlockEntry<Block?> =
        createCasingBlock("hastelloy_n_75_pipe", "哈斯特洛依合金-N75油膜管道",  GtocoreAssets.gtocoreTexture("block/hastelloy_n_75_pipe"))

    val AMPROSIUM_GEARBOX: BlockEntry<Block?> =
        createCasingBlock("amprosium_gearbox", "安普洛齿轮箱机械方块",  GtocoreAssets.gtocoreTexture("block/neutronium_gearbox"))

    val IRIDIUM_GEARBOX: BlockEntry<Block?> =
        createCasingBlock("iridium_gearbox", "铱齿轮箱机械方块",  GtocoreAssets.gtocoreTexture("block/machine_casing_gearbox_iridium"))

    val INCONEL_625_GEARBOX: BlockEntry<Block?> =
        createCasingBlock("inconel_625_gearbox", "镍铬基合金-625球磨齿轮箱",  GtocoreAssets.gtocoreTexture("block/inconel_625_gearbox"))

    val HASTELLOY_N_75_GEARBOX: BlockEntry<Block?> = createCasingBlock(
        "hastelloy_n_75_gearbox",
        "哈斯特洛依合金-N75齿轮箱",
         GtocoreAssets.gtocoreTexture("block/hastelloy_n_75_gearbox")
    )

    val LASER_COOLING_CASING: BlockEntry<Block?> =
        createCasingBlock("laser_cooling_casing", "激光冷却方块",  GtocoreAssets.gtocoreTexture("block/laser_cooling_casing"))

    val HIGH_ENERGY_LASER_EMITTER: BlockEntry<Block?> =
        createCasingBlock("high_energy_laser_emitter", "高能激光发射器",  GtocoreAssets.gtocoreTexture("block/high_energy_laser_emitter"))

    val SPACETIME_COMPRESSION_FIELD_GENERATOR: BlockEntry<Block?> = createCasingBlock(
        "spacetime_compression_field_generator",
        "压缩时空力场发生器",
         GtocoreAssets.gtocoreTexture("block/spacetime_compression_field_generator")
    )

    val DIMENSIONAL_BRIDGE_CASING: BlockEntry<Block?> =
        createCasingBlock("dimensional_bridge_casing", "维度桥接方块",  GtocoreAssets.gtocoreTexture("block/dimensional_bridge_casing"))

    val MACHINE_CASING_CIRCUIT_ASSEMBLY_LINE: BlockEntry<Block?> = createCasingBlock(
        "machine_casing_circuit_assembly_line",
        "电路装配线控制外壳",
         GtocoreAssets.gtocoreTexture("block/machine_casing_circuit_assembly_line")
    )

    val HIGH_STRENGTH_CONCRETE: BlockEntry<Block?> = createCasingBlock(
        "high_strength_concrete",
        "高强度混凝土",
         GtocoreAssets.gtocoreTexture("block/casings/space_elevator_module_base/side")
    )

    val AGGREGATIONE_CORE: BlockEntry<Block?> =
        createCasingBlock("aggregatione_core", "聚合核心",  GtocoreAssets.gtocoreTexture("block/aggregatione_core"))

    val ACCELERATED_PIPELINE: BlockEntry<Block?> =
        createCasingBlock("accelerated_pipeline", "加速管道",  GtocoreAssets.gtocoreTexture("block/accelerated_pipeline"))

    val DIMENSION_CREATION_CASING: BlockEntry<Block?> = createCasingBlock(
        "dimension_creation_casing",
        "维度创造机械方块",
         GtocoreAssets.gtocoreTexture("block/dimension_creation_casing")
    )

    val MACHINE_CASING_GRINDING_HEAD: BlockEntry<Block?> =
        createCasingBlock("machine_casing_grinding_head", "坚固钻头",  GtocoreAssets.gtocoreTexture("block/machine_casing_grinding_head"))

    val CREATE_HPCA_COMPONENT: BlockEntry<Block?> =
        createCasingBlock("create_hpca_component", "创造计算组件",  GtocoreAssets.gtocoreTexture("block/create_hpca_component"))

    val SPACETIME_ASSEMBLY_LINE_UNIT: BlockEntry<Block?> = createCasingBlock(
        "spacetime_assembly_line_unit",
        "超时空装配单元",
         GtocoreAssets.gtocoreTexture("block/spacetime_assembly_line_unit")
    )

    val SPACETIME_ASSEMBLY_LINE_CASING: BlockEntry<Block?> = createCasingBlock(
        "spacetime_assembly_line_casing",
        "超时空装配外壳",
         GtocoreAssets.gtocoreTexture("block/spacetime_assembly_line_casing")
    )

    val HOLLOW_CASING: BlockEntry<Block?> =
        createCasingBlock("hollow_casing", "中空机械方块",  GtocoreAssets.gtocoreTexture("block/hollow_casing"))

    val CONTAINMENT_FIELD_GENERATOR: BlockEntry<Block?> = createCasingBlock(
        "containment_field_generator",
        "遏制场发生器",
         GtocoreAssets.gtocoreTexture("block/containment_field_generator")
    )

    val STEAM_ASSEMBLY_BLOCK: BlockEntry<Block?> =
        createCasingBlock("steam_assembly_block", "蒸汽装配方块",  GtocoreAssets.gtocoreTexture("block/steam_assembly_block"))

    val RESTRAINT_DEVICE: BlockEntry<Block?> =
        createCasingBlock("restraint_device", "力场约束装置",  GtocoreAssets.gtocoreTexture("block/restraint_device"))

    val FLOTATION_CELL: BlockEntry<Block?> =
        createCasingBlock("flotation_cell", "浮选矿池单元",  GtocoreAssets.gtocoreTexture("block/flotation_cell"))

    val MANIPULATOR: BlockEntry<Block?> =
        createCasingBlock("manipulator", "量子操纵者机械方块",  GtocoreAssets.gtocoreTexture("block/manipulator"))

    val MODULE_CONNECTOR: BlockEntry<Block?> =
        createCasingBlock("module_connector", "太空电梯模块连接器",  GtocoreAssets.gtocoreTexture("block/module_connector"))


    val SPACE_ELEVATOR_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "space_elevator_mechanical_casing",
        "太空电梯机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/space_elevator_mechanical_casing")
    )

    val DYSON_CONTROL_CASING: BlockEntry<Block?> = createCasingBlock(
        "dyson_control_casing",
        "戴森球控制中心基座机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/dyson_control_casing")
    )

    val DYSON_CONTROL_TOROID: BlockEntry<Block?> = createCasingBlock(
        "dyson_control_toroid",
        "戴森球控制中心环形机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/dyson_control_toroid")
    )

    val DYSON_DEPLOYMENT_CORE: BlockEntry<Block?> = createCasingBlock(
        "dyson_deployment_core",
        "戴森球模块部署单元核心",
        GtocoreAssets.gtocoreTexture("block/casings/dyson_deployment_core")
    )

    val DYSON_DEPLOYMENT_CASING: BlockEntry<Block?> = createCasingBlock(
        "dyson_deployment_casing",
        "戴森球模块部署单元基座机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/dyson_deployment_casing")
    )

    val CREATE_CASING: BlockEntry<Block?> =
        createCasingBlock("create_casing", "创造机械方块",  GtocoreAssets.gtocoreTexture("block/casings/create_casing"))

    val SUPERCRITICAL_TURBINE_CASING: BlockEntry<Block?> = createCasingBlock(
        "supercritical_turbine_casing",
        "超临界涡轮机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/supercritical_turbine_casing")
    )

    val RED_STEEL_CASING: BlockEntry<Block?> =
        createCasingBlock("red_steel_casing", "高气密红钢机器外壳",  GtocoreAssets.gtocoreTexture("block/casings/red_steel_casing"))

    val MULTI_FUNCTIONAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "multi_functional_casing",
        "多功能机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/multi_functional_casing")
    )

    val INCONEL_625_CASING: BlockEntry<Block?> = createCasingBlock(
        "inconel_625_casing",
        "防震镍铬基合金-625机器外壳",
         GtocoreAssets.gtocoreTexture("block/casings/inconel_625_casing")
    )

    val HASTELLOY_N_75_CASING: BlockEntry<Block?> = createCasingBlock(
        "hastelloy_n_75_casing",
        "哈斯特洛依合金-N75防水机器外壳",
         GtocoreAssets.gtocoreTexture("block/casings/hastelloy_n_75_casing")
    )

    val DIMENSION_CONNECTION_CASING: BlockEntry<Block?> = createCasingBlock(
        "dimension_connection_casing",
        "维度连接机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/dimension_connection_casing")
    )

    val DIMENSION_INJECTION_CASING: BlockEntry<Block?> = createCasingBlock(
        "dimension_injection_casing",
        "维度注入机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/dimension_injection_casing")
    )

    val DIMENSIONALLY_TRANSCENDENT_CASING: BlockEntry<Block?> = createCasingBlock(
        "dimensionally_transcendent_casing",
        "超维度机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/dimensionally_transcendent_casing")
    )

    val ECHO_CASING: BlockEntry<Block?> =
        createCasingBlock("echo_casing", "回响强化机械方块",  GtocoreAssets.gtocoreTexture("block/casings/echo_casing"))

    val DRAGON_STRENGTH_TRITANIUM_CASING: BlockEntry<Block?> = createCasingBlock(
        "dragon_strength_tritanium_casing",
        "龙之力量三钛合金机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/extreme_strength_tritanium_casing")
    )

    val ALUMINIUM_BRONZE_CASING: BlockEntry<Block?> = createCasingBlock(
        "aluminium_bronze_casing",
        "铝青铜机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/aluminium_bronze_casing")
    )

    val ANTIFREEZE_HEATPROOF_MACHINE_CASING: BlockEntry<Block?> = createCasingBlock(
        "antifreeze_heatproof_machine_casing",
        "防冻隔热机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/antifreeze_heatproof_machine_casing")
    )

    val ENHANCE_HYPER_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "enhance_hyper_mechanical_casing",
        "强化超能机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/enhance_hyper_mechanical_casing")
    )

    val EXTREME_STRENGTH_TRITANIUM_CASING: BlockEntry<Block?> = createCasingBlock(
        "extreme_strength_tritanium_casing",
        "极限强度三钛合金机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/extreme_strength_tritanium_casing")
    )

    val GRAVITON_FIELD_CONSTRAINT_CASING: BlockEntry<Block?> = createCasingBlock(
        "graviton_field_constraint_casing",
        "引力场约束方块",
         GtocoreAssets.gtocoreTexture("block/casings/graviton_field_constraint_casing")
    )

    val HYPER_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "hyper_mechanical_casing",
        "超能机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/hyper_mechanical_casing")
    )

    val IRIDIUM_CASING: BlockEntry<Block?> =
        createCasingBlock("iridium_casing", "铱强化机械方块",  GtocoreAssets.gtocoreTexture("block/casings/iridium_casing"))

    val LAFIUM_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "lafium_mechanical_casing",
        "路菲恩机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/lafium_mechanical_casing")
    )

    val OXIDATION_RESISTANT_HASTELLOY_N_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "oxidation_resistant_hastelloy_n_mechanical_casing",
        "抗氧化哈斯特洛伊合金-N机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/oxidation_resistant_hastelloy_n_mechanical_casing")
    )

    val PIKYONIUM_MACHINE_CASING: BlockEntry<Block?> = createCasingBlock(
        "pikyonium_machine_casing",
        "皮卡优机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/pikyonium_machine_casing")
    )

    val SPS_CASING: BlockEntry<Block?> =
        createCasingBlock("sps_casing", "超临界外壳",  GtocoreAssets.gtocoreTexture("block/casings/sps_casing"))

    val NAQUADAH_ALLOY_CASING: BlockEntry<Block?> = createCasingBlock(
        "naquadah_alloy_casing",
        "硅岩合金机械外壳",
         GtocoreAssets.gtocoreTexture("block/casings/hyper_mechanical_casing")
    )

    val PROCESS_MACHINE_CASING: BlockEntry<Block?> =
        createCasingBlock("process_machine_casing", "处理机械方块",  GtocoreAssets.gtocoreTexture("block/casings/process_machine_casing"))

    val FISSION_REACTOR_CASING: BlockEntry<Block?> = createCasingBlock(
        "fission_reactor_casing",
        "裂变反应堆外壳",
         GtocoreAssets.gtocoreTexture("block/casings/fission_reactor_casing")
    )

    val DEGENERATE_RHENIUM_CONSTRAINED_CASING: BlockEntry<Block?> = createCasingBlock(
        "degenerate_rhenium_constrained_casing",
        "简并态铼约束外壳",
         GtocoreAssets.gtocoreTexture("block/casings/degenerate_rhenium_constrained_casing")
    )

    val PRESSURE_CONTAINMENT_CASING: BlockEntry<Block?> = createCasingBlock(
        "pressure_containment_casing",
        "压力容器机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/pressure_containment_casing")
    )

    val AWAKENED_DRACONIUM_CASING: BlockEntry<Block?> = createCasingBlock(
        "awakened_draconium_casing",
        "觉醒龙外壳",
         GtocoreAssets.gtocoreTexture("block/casings/awakened_draconium_casing")
    )

    val MAGTECH_CASING: BlockEntry<Block?> =
        createCasingBlock("magtech_casing", "磁力机械方块",  GtocoreAssets.gtocoreTexture("block/casings/magtech_casing"))

    val BRASS_REINFORCED_WOODEN_CASING: BlockEntry<Block?> = createCasingBlock(
        "brass_reinforced_wooden_casing",
        "黄铜加固木制机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/brass_reinforced_wooden_casing")
    )

    val COMPRESSOR_CONTROLLER_CASING: BlockEntry<Block?> = createCasingBlock(
        "compressor_controller_casing",
        "压缩控制机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/compressor_controller_casing")
    )

    val QUARK_EXCLUSION_CASING: BlockEntry<Block?> = createCasingBlock(
        "quark_exclusion_casing",
        "夸克排斥机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/quark_exclusion_casing")
    )

    val NAQUADAH_REINFORCED_PLANT_CASING: BlockEntry<Block?> = createCasingBlock(
        "naquadah_reinforced_plant_casing",
        "硅岩增强处理机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/naquadah_reinforced_plant_casing")
    )

    val BOUNDLESS_GRAVITATIONALLY_SEVERED_STRUCTURE_CASING: BlockEntry<Block?> = createCasingBlock(
        "boundless_gravitationally_severed_structure_casing",
        "无边重力切割结构机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/boundless_gravitationally_severed_structure_casing")
    )

    val CELESTIAL_MATTER_GUIDANCE_CASING: BlockEntry<Block?> = createCasingBlock(
        "celestial_matter_guidance_casing",
        "天体物质引导机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/celestial_matter_guidance_casing")
    )

    val SINGULARITY_REINFORCED_STELLAR_SHIELDING_CASING: BlockEntry<Block?> = createCasingBlock(
        "singularity_reinforced_stellar_shielding_casing",
        "奇点增强恒星屏蔽机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/singularity_reinforced_stellar_shielding_casing")
    )


    val STELLAR_ENERGY_SIPHON_CASING: BlockEntry<Block?> = createCasingBlock(
        "stellar_energy_siphon_casing",
        "恒星能量汲取机械方块",
         GtocoreAssets.gtocoreTexture("block/stellar_energy_siphon_casing")
    )

    val TRANSCENDENTALLY_AMPLIFIED_MAGNETIC_CONFINEMENT_CASING: BlockEntry<Block?> = createCasingBlock(
        "transcendentally_amplified_magnetic_confinement_casing",
        "超强放大磁约束机械方块",
         GtocoreAssets.gtocoreTexture("block/transcendentally_amplified_magnetic_confinement_casing")
    )

    val COMPRESSOR_PIPE_CASING: BlockEntry<Block?> =
        createCasingBlock("compressor_pipe_casing", "压缩管道机械方块",  GtocoreAssets.gtocoreTexture("block/compressor_pipe_casing"))

    val EXTREME_DENSITY_CASING: BlockEntry<Block?> =
        createCasingBlock("extreme_density_casing", "极密机械方块",  GtocoreAssets.gtocoreTexture("block/extreme_density_casing"))

    val FLOCCULATION_CASING: BlockEntry<Block?> =
        createCasingBlock("flocculation_casing", "光滑无菌絮凝机械方块",  GtocoreAssets.gtocoreTexture("block/flocculation_casing"))

    val GRAVITY_STABILIZATION_CASING: BlockEntry<Block?> = createCasingBlock(
        "gravity_stabilization_casing",
        "重力稳定机械方块",
         GtocoreAssets.gtocoreTexture("block/gravity_stabilization_casing")
    )

    val HIGH_PRESSURE_RESISTANT_CASING: BlockEntry<Block?> = createCasingBlock(
        "high_pressure_resistant_casing",
        "高能耐压机械方块",
         GtocoreAssets.gtocoreTexture("block/high_pressure_resistant_casing")
    )

    val LASER_CASING: BlockEntry<GlassBlock?> =
        createGlassCasingBlock("laser_casing", "激光机械方块",  GtocoreAssets.gtocoreTexture("block/laser_casing"))

    val AMPROSIUM_CASING: BlockEntry<Block?> =
        createCasingBlock("amprosium_casing", "安普洛机械方块",  GtocoreAssets.gtocoreTexture("block/neutronium_casing"))

    val OZONE_CASING: BlockEntry<Block?> =
        createCasingBlock("ozone_casing", "臭氧机械方块",  GtocoreAssets.gtocoreTexture("block/ozone_casing"))

    val PLASMA_HEATER_CASING: BlockEntry<Block?> =
        createCasingBlock("plasma_heater_casing", "等离子加热机械方块",  GtocoreAssets.gtocoreTexture("block/plasma_heater_casing"))

    val RADIATION_ABSORBENT_CASING: BlockEntry<Block?> = createCasingBlock(
        "radiation_absorbent_casing",
        "辐射吸收机械方块",
         GtocoreAssets.gtocoreTexture("block/radiation_absorbent_casing")
    )

    val REINFORCED_WOOD_CASING: BlockEntry<Block?> = createSidedCasingBlock(
        "reinforced_wood_casing",
        "增强木制机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/reinforced_wood_casing")
    )

    val SHIELDED_ACCELERATOR: BlockEntry<Block?> =
        createCasingBlock("shielded_accelerator", "屏蔽加速器机械方块",  GtocoreAssets.gtocoreTexture("block/shielded_accelerator"))

    val AMPROSIUM_ACTIVE_CASING: BlockEntry<Block?> =
        createCasingBlock("amprosium_active_casing", "安普洛活性机械方块",  GtocoreAssets.gtocoreTexture("block/neutronium_active_casing"))

    val QUARK_PIPE: BlockEntry<Block?> = createCasingBlock("quark_pipe", "夸克管道",  GtocoreAssets.gtocoreTexture("block/quark_pipe"))

    val INERT_NEUTRALIZATION_WATER_PLANT_CASING: BlockEntry<Block?> = createCasingBlock(
        "inert_neutralization_water_plant_casing",
        "惰性中和水处理机械方块",
         GtocoreAssets.gtocoreTexture("block/inert_neutralization_water_plant_casing")
    )

    val HIGH_ENERGY_ULTRAVIOLET_EMITTER_CASING: BlockEntry<Block?> = createCasingBlock(
        "high_energy_ultraviolet_emitter_casing",
        "高能紫外线发射器机械方块",
         GtocoreAssets.gtocoreTexture("block/high_energy_ultraviolet_emitter_casing")
    )

    val REINFORCED_STERILE_WATER_PLANT_CASING: BlockEntry<Block?> = createCasingBlock(
        "reinforced_sterile_water_plant_casing",
        "加固无菌水处理机械方块",
         GtocoreAssets.gtocoreTexture("block/reinforced_sterile_water_plant_casing")
    )

    val NEUTRONIUM_STABLE_CASING: BlockEntry<Block?> =
        createCasingBlock("neutronium_stable_casing", "中子稳定机械方块",  GtocoreAssets.gtocoreTexture("block/neutronium_stable_casing"))

    val STERILE_WATER_PLANT_CASING: BlockEntry<Block?> = createCasingBlock(
        "sterile_water_plant_casing",
        "无菌水处理机械方块",
         GtocoreAssets.gtocoreTexture("block/sterile_water_plant_casing")
    )

    val STABILIZED_NAQUADAH_WATER_PLANT_CASING: BlockEntry<Block?> = createCasingBlock(
        "stabilized_naquadah_water_plant_casing",
        "稳定硅岩水处理机械方块",
         GtocoreAssets.gtocoreTexture("block/stabilized_naquadah_water_plant_casing")
    )

    val STRENGTHEN_THE_BASE_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "strengthen_the_base_block",
        "强化基座方块",
         GtocoreAssets.gtocoreTexture("block/casings/strengthen_the_base_block")
    )


    val PVC_PLASTIC_MECHANICAL_HOUSING: BlockEntry<Block?> = createCasingBlock(
        "pvc_plastic_mechanical_housing",
        "PVC塑料机械外壳",
         GtocoreAssets.gtocoreTexture("block/pvc_plastic_mechanical_housing")
    )

    val PI_HIGH_TEMPERATURE_INSULATION_MECHANICAL_HOUSING: BlockEntry<Block?> = createCasingBlock(
        "pi_high_temperature_insulation_mechanical_housing",
        "PI高温绝缘机械外壳",
         GtocoreAssets.gtocoreTexture("block/pi_high_temperature_insulation_mechanical_housing")
    )

    val MC_NYLON_TENSILE_MECHANICAL_SHELL: BlockEntry<Block?> = createCasingBlock(
        "mc_nylon_tensile_mechanical_shell",
        "MC尼龙抗拉机械外壳",
         GtocoreAssets.gtocoreTexture("block/mc_nylon_tensile_mechanical_shell")
    )

    val PEEK_WEAR_RESISTANT_MECHANICAL_HOUSING: BlockEntry<Block?> = createCasingBlock(
        "peek_wear_resistant_mechanical_housing",
        "PEEK耐磨机械外壳",
         GtocoreAssets.gtocoreTexture("block/peek_wear_resistant_mechanical_housing")
    )

    val REINFORCED_EPOXY_RESIN_MECHANICAL_HOUSING: BlockEntry<Block?> = createCasingBlock(
        "reinforced_epoxy_resin_mechanical_housing",
        "强化环氧树脂机械外壳",
         GtocoreAssets.gtocoreTexture("block/reinforced_epoxy_resin_mechanical_housing")
    )

    val PPS_CORROSION_RESISTANT_MECHANICAL_HOUSING: BlockEntry<Block?> = createCasingBlock(
        "pps_corrosion_resistant_mechanical_housing",
        "PPS耐腐蚀机械外壳",
         GtocoreAssets.gtocoreTexture("block/pps_corrosion_resistant_mechanical_housing")
    )

    val PBI_RADIATION_RESISTANT_MECHANICAL_ENCLOSURE: BlockEntry<Block?> = createCasingBlock(
        "pbi_radiation_resistant_mechanical_enclosure",
        "PBI抗辐射机械外壳",
         GtocoreAssets.gtocoreTexture("block/pbi_radiation_resistant_mechanical_enclosure")
    )


    val CALCIUM_OXIDE_CERAMIC_ANTI_METAL_CORROSION_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "calcium_oxide_ceramic_anti_metal_corrosion_mechanical_block",
        "氧化钙陶瓷抗金属侵蚀机械方块",
         GtocoreAssets.gtocoreTexture("block/calcium_oxide_ceramic_anti_metal_corrosion_mechanical_block")
    )

    val ZIRCONIA_CERAMIC_HIGH_STRENGTH_BENDING_RESISTANCE_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "zirconia_ceramic_high_strength_bending_resistance_mechanical_block",
        "氧化锆陶瓷高强度耐弯折机械方块",
         GtocoreAssets.gtocoreTexture("block/zirconia_ceramic_high_strength_bending_resistance_mechanical_block")
    )

    val LITHIUM_OXIDE_CERAMIC_HEAT_RESISTANT_SHOCK_RESISTANT_MECHANICAL_CUBE: BlockEntry<Block?> = createCasingBlock(
        "lithium_oxide_ceramic_heat_resistant_shock_resistant_mechanical_cube",
        "氧化锂陶瓷防热抗震机械方块",
         GtocoreAssets.gtocoreTexture("block/lithium_oxide_ceramic_heat_resistant_shock_resistant_mechanical_cube")
    )

    val TITANIUM_NITRIDE_CERAMIC_IMPACT_RESISTANT_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "titanium_nitride_ceramic_impact_resistant_mechanical_block",
        "氮化钛陶瓷抗冲击机械方块",
         GtocoreAssets.gtocoreTexture("block/titanium_nitride_ceramic_impact_resistant_mechanical_block")
    )

    val STRONTIUM_CARBONATE_CERAMIC_RAY_ABSORBING_MECHANICAL_CUBE: BlockEntry<Block?> = createCasingBlock(
        "strontium_carbonate_ceramic_ray_absorbing_mechanical_cube",
        "碳酸锶陶瓷射线吸收机械方块",
         GtocoreAssets.gtocoreTexture("block/strontium_carbonate_ceramic_ray_absorbing_mechanical_cube")
    )

    val MAGNESIUM_OXIDE_CERAMIC_HIGH_TEMPERATURE_INSULATION_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "magnesium_oxide_ceramic_high_temperature_insulation_mechanical_block",
        "氧化镁陶瓷高温绝缘机械方块",
         GtocoreAssets.gtocoreTexture("block/magnesium_oxide_ceramic_high_temperature_insulation_mechanical_block")
    )

    val BORON_CARBIDE_CERAMIC_RADIATION_RESISTANT_MECHANICAL_CUBE: BlockEntry<Block?> = createCasingBlock(
        "boron_carbide_ceramic_radiation_resistant_mechanical_cube",
        "碳化硼陶瓷耐辐射机械方块",
         GtocoreAssets.gtocoreTexture("block/boron_carbide_ceramic_radiation_resistant_mechanical_cube")
    )

    val COBALT_OXIDE_CERAMIC_STRONG_THERMALLY_CONDUCTIVE_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "cobalt_oxide_ceramic_strong_thermally_conductive_mechanical_block",
        "氧化钴陶瓷坚固导热机械方块",
         GtocoreAssets.gtocoreTexture("block/cobalt_oxide_ceramic_strong_thermally_conductive_mechanical_block")
    )


    val ABS_BLACK_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_black_casing", "黑色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_black_casing"))

    val ABS_BLUE_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_blue_casing", "蓝色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_blue_casing"))

    val ABS_BROWN_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_brown_casing", "棕色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_brown_casing"))

    val ABS_GREEN_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_green_casing", "绿色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_green_casing"))

    val ABS_GREY_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_grey_casing", "灰色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_grey_casing"))

    val ABS_LIME_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_lime_casing", "黄绿色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_lime_casing"))

    val ABS_ORANGE_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_orange_casing", "橙色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_orange_casing"))

    val ABS_RED_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_red_casing", "红色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_red_casing"))

    val ABS_WHITE_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_white_casing", "白色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_white_casing"))

    val ABS_YELLOW_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_yellow_casing", "黄色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_yellow_casing"))

    val ABS_CYAN_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_cyan_casing", "青色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_cyan_casing"))

    val ABS_MAGENTA_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_magenta_casing", "品红色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_magenta_casing"))

    val ABS_PINK_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_pink_casing", "粉色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_pink_casing"))

    val ABS_PURPLE_CASING: BlockEntry<Block?> =
        createCasingBlock("abs_purple_casing", "紫色ABS塑料机械外壳",  GtocoreAssets.gtocoreTexture("block/casings/abs_purple_casing"))

    val ABS_LIGHT_BULL_CASING: BlockEntry<Block?> = createCasingBlock(
        "abs_light_bull_casing",
        "浅蓝色ABS塑料机械外壳",
         GtocoreAssets.gtocoreTexture("block/casings/abs_light_bull_casing")
    )

    val ABS_LIGHT_GREY_CASING: BlockEntry<Block?> = createCasingBlock(
        "abs_light_grey_casing",
        "浅灰色ABS塑料机械外壳",
         GtocoreAssets.gtocoreTexture("block/casings/abs_light_grey_casing")
    )


    val BIOCOMPUTER_CASING: BlockEntry<ActiveBlock?> =
        createActiveCasing("biocomputer_casing", "生物计算机外壳", "block/casings/about_computer/biocomputer_shell")

    val BIOACTIVE_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "bioactive_mechanical_casing",
        "生物活性机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/bioactive_mechanical_casing")
    )

    val BIOLOGICAL_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "biological_mechanical_casing",
        "生物机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/biological_mechanical_casing")
    )
    val PHASE_CHANGE_BIOCOMPUTER_COOLING_VENTS: BlockEntry<ActiveBlock?> = createActiveCasing(
        "phase_change_biocomputer_cooling_vents",
        "相变计算机散热口",
        "block/casings/about_computer/phase_change_biocomputer_cooling_vents"
    )

    val GRAVITON_COMPUTER_CASING: BlockEntry<Block?> = createCasingBlock(
        "graviton_computer_casing",
        "引力子计算机外壳",
         GtocoreAssets.gtocoreTexture("block/casings/about_computer/graviton_computer_casing")
    )

    val ANTI_ENTROPY_COMPUTER_CONDENSATION_MATRIX: BlockEntry<Block?> = createCasingBlock(
        "anti_entropy_computer_condensation_matrix",
        "逆熵计算机冷凝矩阵",
         GtocoreAssets.gtocoreTexture("block/casings/about_computer/anti_entropy_computer_condensation_matrix")
    )


    val STAINLESS_EVAPORATION_CASING: BlockEntry<Block?> = createCasingBlock(
        "stainless_evaporation_casing",
        "不锈钢蒸发外壳",
         GtocoreAssets.gtocoreTexture("block/casings/stainless_evaporation_casing")
    )



    val TRANSMUTATION_CATALYST: BlockEntry<Block?> =
        createCasingBlock("transmutation_catalyst", "嬗变催化器",  GtocoreAssets.gtocoreTexture("block/casings/transmutation_catalyst"))

    val INFUSED_GOLD_REINFORCED_WOODEN_CASING: BlockEntry<Block?> = createCasingBlock(
        "infused_gold_reinforced_wooden_casing",
        "注魔金加固木制机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/infused_gold_reinforced_wooden_casing")
    )

    val INFUSED_GOLD_CASING: BlockEntry<Block?> =
        createCasingBlock("infused_gold_casing", "注魔金外壳",  GtocoreAssets.gtocoreTexture("block/casings/infused_gold_casing"))

    val SOURCE_STONE_CASING: BlockEntry<Block?> =
        createCasingBlock("source_stone_casing", "魔源机械方块",  GtocoreAssets.gtocoreTexture("block/casings/source_stone_casing"))

    val SOURCE_FIBER_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "source_fiber_mechanical_casing",
        "魔源纤维机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/source_fiber_mechanical_casing")
    )

    val SPELL_PRISM_CASING: BlockEntry<Block?> =
        createCasingBlock("spell_prism_casing", "法术机械方块",  GtocoreAssets.gtocoreTexture("block/casings/spell_prism_casing"))

    val ORIGINAL_BRONZE_CASING: BlockEntry<Block?> =
        createCasingBlock("original_bronze_casing", "原始青铜外壳",  GtocoreAssets.gtocoreTexture("block/casings/original_bronze_casing"))

    val MANASTEEL_CASING: BlockEntry<Block?> =
        createCasingBlock("manasteel_casing", "魔力钢外壳",  GtocoreAssets.gtocoreTexture("block/casings/manasteel_casing"))

    val TERRASTEEL_CASING: BlockEntry<Block?> =
        createCasingBlock("terrasteel_casing", "泰拉钢外壳",  GtocoreAssets.gtocoreTexture("block/casings/terrasteel_casing"))

    val ELEMENTIUM_CASING: BlockEntry<Block?> =
        createCasingBlock("elementium_casing", "源质钢外壳",  GtocoreAssets.gtocoreTexture("block/casings/elementium_casing"))

    val ALFSTEEL_CASING: BlockEntry<Block?> =
        createCasingBlock("alfsteel_casing", "精灵钢外壳",  GtocoreAssets.gtocoreTexture("block/casings/alfsteel_casing"))

    val GAIASTEEL_CASING: BlockEntry<Block?> =
        createCasingBlock("gaiasteel_casing", "盖亚钢外壳",  GtocoreAssets.gtocoreTexture("block/casings/gaiasteel_casing"))

    val ORICHALCOS_CASING: BlockEntry<Block?> =
        createCasingBlock("orichalcos_casing", "奥利哈钢外壳",  GtocoreAssets.gtocoreTexture("block/casings/orichalcos_casing"))

    val HERETICAL_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "heretical_mechanical_casing",
        "邪术机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/heretical_mechanical_casing")
    )


    val STAR_STONE: Array<BlockEntry<ETBlockReg.GlowingBlock>> = createStarStone()


    val ACCELERATOR_PROTECTION_CASING: BlockEntry<Block?> = createCasingBlock(
        "accelerator_protection_casing",
        "加速器防护机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/accelerator/accelerator_protection_casing")
    )

    val ACCELERATOR_OBSERVATION_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "accelerator_observation_glass",
        "加速器观察玻璃",
         GtocoreAssets.gtocoreTexture("block/casings/accelerator/accelerator_observation_glass")
    )

    val ACCELERATOR_PARTICLE_INSTANT_CONDENSATION_CASING: BlockEntry<Block?> = createCasingBlock(
        "accelerator_particle_instant_condensation_casing",
        "加速器线圈冷凝机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/accelerator/accelerator_particle_instant_condensation_casing")
    )


    val ACCELERATOR_ELECTROMAGNETIC_COIL_CONSTRAINT_CASING_LUV: BlockEntry<Block?> = createCasingBlock(
        "accelerator_electromagnetic_coil_constraint_casing_luv",
        "LuV加速器磁约束线圈机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/accelerator/accelerator_electromagnetic_coil_constraint_casing_luv")
    )

    val ACCELERATOR_ELECTROMAGNETIC_COIL_CONSTRAINT_CASING_ZPM: BlockEntry<Block?> = createCasingBlock(
        "accelerator_electromagnetic_coil_constraint_casing_zpm",
        "ZPM加速器磁约束线圈机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/accelerator/accelerator_electromagnetic_coil_constraint_casing_zpm")
    )

    val ACCELERATOR_ELECTROMAGNETIC_COIL_CONSTRAINT_CASING_UV: BlockEntry<Block?> = createCasingBlock(
        "accelerator_electromagnetic_coil_constraint_casing_uv",
        "UV加速器磁约束线圈机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/accelerator/accelerator_electromagnetic_coil_constraint_casing_uv")
    )

    val ACCELERATOR_ELECTROMAGNETIC_COIL_CONSTRAINT_CASING_UHV: BlockEntry<Block?> = createCasingBlock(
        "accelerator_electromagnetic_coil_constraint_casing_uhv",
        "UHV加速器磁约束线圈机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/accelerator/accelerator_electromagnetic_coil_constraint_casing_uhv")
    )

    // 耐高压管道机械方块

    val HIGH_PRESSURE_PIPE_CASING: BlockEntry<Block?> = createCasingBlock(
        "high_pressure_pipe_casing",
        "耐高压管道机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/high_pressure_pipe_casing")
    )
    // 耐化学腐蚀管道机械方块

    val CHEMICAL_CORROSION_RESISTANT_PIPE_CASING: BlockEntry<Block?> = createCasingBlock(
        "chemical_corrosion_resistant_pipe_casing",
        "耐化学腐蚀管道机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/chemical_corrosion_resistant_pipe_casing")
    )
    // 油气输送密封管道机械方块

    val OIL_GAS_TRANSPORTATION_PIPE_CASING: BlockEntry<Block?> = createCasingBlock(
        "oil_gas_transportation_pipe_casing",
        "油气输送密封管道机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/oil_gas_transportation_pipe_casing")
    )

    val INDUSTRIAL_FRAMELESS_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "industrial_frameless_glass",
        "工业无框玻璃",
         GtocoreAssets.gtocoreTexture("block/industrial_frameless_glass")
    )
    // 工程机械方块

    val ENGINEERING_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "engineering_mechanical_casing",
        "工程机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/engineering_mechanical_casing")
    )
    // 高强度支撑主轴

    val HIGH_STRENGTH_SUPPORT_SPINDLE: BlockEntry<Block?> = createCasingBlock(
        "high_strength_support_spindle",
        "高强度支撑主轴",
         GtocoreAssets.gtocoreTexture("block/casings/high_strength_support_spindle")
    )
    // 高强度支撑机械方块

    val HIGH_STRENGTH_SUPPORT_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "high_strength_support_mechanical_casing",
        "高强度支撑机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/high_strength_support_mechanical_casing")
    )
    // 精密加工机械方块

    val PRECISION_PROCESSING_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "precision_processing_mechanical_casing",
        "精密加工机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/precision_processing_mechanical_casing")
    )
    // 宇宙探测接收器物质射线吸收阵面

    val COSMIC_DETECTION_RECEIVER_MATERIAL_RAY_ABSORBING_ARRAY: BlockEntry<Block?> = createCasingBlock(
        "cosmic_detection_receiver_material_ray_absorbing_array",
        "宇宙探测接收器物质射线吸收阵面",
         GtocoreAssets.gtocoreTexture("block/casings/cosmic_detection_receiver_material_ray_absorbing_array")
    )
    // 真空室防护外壳

    val VACUUM_CHAMBER_PROTECTION_CASING: BlockEntry<Block?> = createCasingBlock(
        "vacuum_chamber_protection_casing",
        "真空室防护外壳",
         GtocoreAssets.gtocoreTexture("block/casings/vacuum_chamber/vacuum_chamber_protection_casing")
    )
    // 真空室观察玻璃

    val VACUUM_CHAMBER_OBSERVATION_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "vacuum_chamber_observation_glass",
        "真空室观察玻璃",
         GtocoreAssets.gtocoreTexture("block/casings/vacuum_chamber/vacuum_chamber_observation_glass")
    )
    // 真空室束流方块

    val VACUUM_CHAMBER_BEAM_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "vacuum_chamber_beam_block",
        "真空室束流方块",
         GtocoreAssets.gtocoreTexture("block/casings/vacuum_chamber/vacuum_chamber_beam_block")
    )
    // 云室探测器方块

    val CLOUD_CHAMBER_DETECTOR_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "cloud_chamber_detector_block",
        "云室探测器方块",
         GtocoreAssets.gtocoreTexture("block/casings/vacuum_chamber/cloud_chamber_detector_block")
    )
    // 气泡室探测器方块

    val BUBBLE_CHAMBER_DETECTOR_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "bubble_chamber_detector_block",
        "气泡室探测器方块",
         GtocoreAssets.gtocoreTexture("block/casings/vacuum_chamber/bubble_chamber_detector_block")
    )
    // 闪烁计数器探测器方块

    val SCINTILLATION_COUNTER_DETECTOR_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "scintillation_counter_detector_block",
        "闪烁计数器探测器方块",
         GtocoreAssets.gtocoreTexture("block/casings/vacuum_chamber/scintillation_counter_detector_block")
    )
    // 多丝正比室探测器方块

    val MULTI_WIRE_PROPORTIONAL_CHAMBER_DETECTOR_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "multi_wire_proportional_chamber_detector_block",
        "多丝正比室探测器方块",
         GtocoreAssets.gtocoreTexture("block/casings/vacuum_chamber/multi_wire_proportional_chamber_detector_block")
    )
    // 太阳能集热管道机械方块

    val SOLAR_HEAT_COLLECTOR_PIPE_CASING: BlockEntry<Block?> = createCasingBlock(
        "solar_heat_collector_pipe_casing",
        "太阳能集热管道机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/solar_heat_collector_pipe_casing")
    )
    // 复合装甲防护层
    val COMPOSITE_ARMOR_PROTECTIVE_LAYER: BlockEntry<Block?> = createCasingBlock(
        "composite_armor_protective_layer",
        "复合装甲防护层",
         GtocoreAssets.gtocoreTexture("block/casings/composite_armor_protective_layer")
    )
    // 光学动态镀膜仪器防护罩玻璃
    val OPTICAL_DYNAMIC_COATING_INSTRUMENT_PROTECTIVE_SHIELD_GLASS: BlockEntry<GlassBlock?> = createGlassCasingBlock(
        "optical_dynamic_coating_instrument_protective_shield_glass",
        "光学动态镀膜仪器防护罩玻璃",
         GtocoreAssets.gtocoreTexture("block/casings/optical_dynamic_coating_instrument_protective_shield_glass")
    )
    // 黑体辐射防护机械外壳
    val BLACKBODY_RADIATION_PROTECTION_MECHANICAL_HOUSING: BlockEntry<Block?> = createCasingBlock(
        "blackbody_radiation_protection_mechanical_housing",
        "黑体防护机械外壳",
         GtocoreAssets.gtocoreTexture("block/casings/blackbody_radiation_protection_mechanical_housing")
    )
    // 铝合金-2090蒙皮机械方块
    val ALUMINUM_ALLOY_2090_SKIN_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "aluminum_alloy_2090_leather_covered_mechanical_block",
        "铝合金-2090蒙皮机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/aluminum_alloy_2090_leather_covered_mechanical_block")
    )
    // 钛合金-TB6机械方块
    val TITANIUM_ALLOY_TB6_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "titanium_alloy_tb6_mechanical_block",
        "钛合金-TB6机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/titanium_alloy_tb6_mechanical_block")
    )
    // 铝合金-8090蒙皮机械方块
    val ALUMINUM_ALLOY_8090_SKIN_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "aluminum_alloy_8090_leather_covered_mechanical_block",
        "铝合金-8090蒙皮机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/aluminum_alloy_8090_leather_covered_mechanical_block")
    )
    // 低温燃料管道机械方块
    val LOW_TEMPERATURE_FUEL_PIPE_CASING: BlockEntry<Block?> = createCasingBlock(
        "low_temperature_fuel_pip",
        "低温燃料管道机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/low_temperature_fuel_pip")
    )
    // 散热管道机械方块
    val HEAT_INSULATION_TILE_CASING: BlockEntry<Block?> = createCasingBlock(
        "heat_insulation_tile_casing",
        "隔热瓦机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/heat_insulation_tile_casing")
    )
    // 低温燃料储罐机械方块
    val LOW_TEMPERATURE_FUEL_TANK_CASING: BlockEntry<Block?> = createCasingBlock(
        "low_temperature_fuel_tank_casing",
        "低温燃料储罐机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/low_temperature_fuel_tank_casing")
    )
    // 隔热瓦机械方块
    val INSULATION_TILE_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "insulation_tile_mechanical_block",
        "散热管道机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/insulation_tile_mechanical_block")
    )
    // 铝合金-7050支撑机械方块
    val ALUMINUM_ALLOY_7050_SUPPORT_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "aluminum_alloy_7050_support_casing",
        "铝合金-7050支撑机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/aluminum_alloy_7050_support_casing")
    )
    // 耐压壳机械方块
    val PRESSURE_RESISTANT_HOUSING_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "pressure_resistant_housing_mechanical_block",
        "耐压壳机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/pressure_resistant_housing_mechanical_block")
    )
    // 钛合金内部框架
    val TITANIUM_ALLOY_FRAME_INTERNAL: BlockEntry<Block?> = createCasingBlock(
        "titanium_alloy_internal",
        "钛合金内部框架",
         GtocoreAssets.gtocoreTexture("block/casings/titanium_alloy_internal_frame")
    )
    // 航天器密封机械方块

    val SPACECRAFT_SEALING_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "spacecraft_sealing_casing",
        "航天器密封机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/spacecraft_sealing_casing")
    )
    // 钨合金抗冲击机械方块

    val TUNGSTEN_ALLOY_IMPACT_RESISTANT_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "tungsten_alloy_impact_resistant_casing",
        "钨合金抗冲击机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/tungsten_alloy_impact_resistant_casing")
    )
    // 承重结构钢机械方块

    val LOAD_BEARING_STRUCTURAL_STEEL_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "load_bearing_structural_steel_casing",
        "承重结构钢机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/load_bearing_structural_steel_casing")
    )
    // 钛合金防护机械方块

    val TITANIUM_ALLOY_PROTECTIVE_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "titanium_alloy_protective_mechanical_block",
        "钛合金防护机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/titanium_alloy_protective_mechanical_block")
    )
    // 三防计算机外壳

    val THREE_PROOF_COMPUTER_CASING: BlockEntry<Block?> = createCasingBlock(
        "three_proof_computer_casing",
        "三防计算机外壳",
         GtocoreAssets.gtocoreTexture("block/casings/three_proof_computer_casing")
    )
    // 钨合金辐射屏蔽机械方块

    val TUNGSTEN_ALLOY_RADIATION_SHIELDING_MECHANICAL_BLOCK: BlockEntry<Block?> = createCasingBlock(
        "tungsten_alloy_radiation_shielding_mechanical_block",
        "钨合金辐射屏蔽机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/tungsten_alloy_radiation_shielding_mechanical_block")
    )
    // 冷却液管道机械方块

    val COOLANT_PIPE_CASING: BlockEntry<Block?> =
        createCasingBlock("coolant_pip_casing", "冷却液管道机械方块",  GtocoreAssets.gtocoreTexture("block/casings/coolant_pip_casing"))
    // 不锈钢耐腐蚀机械方块 stainless_steel_corrosion_resistant_casing

    val STAINLESS_STEEL_CORROSION_RESISTANT_CASING: BlockEntry<Block?> = createCasingBlock(
        "stainless_steel_corrosion_resistant_casing",
        "不锈钢耐腐蚀机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/stainless_steel_corrosion_resistant_casing")
    )
    // 航天器对接机械方块 spacecraft_docking_casing

    val SPACECRAFT_DOCKING_CASING: BlockEntry<Block?> = createCasingBlock(
        "spacecraft_docking_casing",
        "航天器对接机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/spacecraft_docking_casing")
    )
    // 传感器防护罩机械方块 sensor_protective_cover_casing

    val SENSOR_PROTECTIVE_COVER_CASING: BlockEntry<Block?> = createCasingBlock(
        "sensor_protective_cover_casing",
        "传感器防护罩机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/sensor_protective_cover_casing")
    )
    // 冶炼炉内衬机械方块 inner_lining_of_smelting_furnace_casing

    val INNER_LINING_OF_SMELTING_FURNACE_CASING: BlockEntry<Block?> = createCasingBlock(
        "inner_lining_of_smelting_furnace_casing",
        "冶炼炉内衬机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/inner_lining_of_smelting_furnace_casing")
    )
    // 抗蠕变冶炼机械方块 creep_resistant_smelting_casing

    val CREEP_RESISTANT_SMELTING_CASING: BlockEntry<Block?> = createCasingBlock(
        "creep_resistant_smelting_casing",
        "抗蠕变冶炼机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/creep_resistant_smelting_casing")
    )
    // 空间站控制机械方块 space_station_control_casing

    val SPACE_STATION_CONTROL_CASING: BlockEntry<Block?> = createCasingBlock(
        "space_station_control_casing",
        "空间站控制机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/space_station_control_casing")
    )
    // 高压气体储罐机械方块 high_pressure_gas_storage_tanks_casing

    val HIGH_PRESSURE_GAS_STORAGE_TANKS_CASING: BlockEntry<Block?> = createCasingBlock(
        "high_pressure_gas_storage_tanks_casing",
        "高压气体储罐机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/high_pressure_gas_storage_tanks_casing")
    )
    // 稳定底座机械方块 stable_base_casing

    val STABLE_BASE_CASING: BlockEntry<Block?> =
        createCasingBlock("stable_base_casing", "稳定底座机械方块",  GtocoreAssets.gtocoreTexture("block/casings/stable_base_casing"))
    // 电力传输机械方块 electric_power_transmission_casing

    val ELECTRIC_POWER_TRANSMISSION_CASING: BlockEntry<Block?> = createCasingBlock(
        "electric_power_transmission_casing",
        "电力传输机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/electric_power_transmission_casing")
    )
    // 冶炼控制机械方块 smelting_control_casing

    val SMELTING_CONTROL_CASING: BlockEntry<Block?> = createCasingBlock(
        "smelting_control_casing",
        "冶炼控制机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/smelting_control_casing")
    )
    // 加工控制机械方块 precision_machining_control_casing

    val PRECISION_MACHINING_CONTROL_CASING: BlockEntry<Block?> = createCasingBlock(
        "precision_machining_control_casing",
        "加工控制机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/precision_machining_control_casing")
    )
    // 航天器热屏蔽机械方块 spacecraft_thermal_shielding_casing

    val SPACECRAFT_THERMAL_SHIELDING_CASING: BlockEntry<Block?> = createCasingBlock(
        "spacecraft_thermal_shielding_casing",
        "航天器热屏蔽机械方块",
         GtocoreAssets.gtocoreTexture("block/casings/spacecraft_thermal_shielding_casing")
    )
    // 航天发动机喷管 space_engine_nozzle
    val SPACECRAFT_DYNAMIC_PROTECTIVE_MECHANICAL_CASING: BlockEntry<Block?> = createCasingBlock(
        "spacecraft_dynamic_protective_mechanical_casing",
        "航天器防护机械外壳",
         GtocoreAssets.gtocoreTexture("block/casings/spacecraft_dynamic_protective_mechanical_casing")
    )
    // 时空结构维持机械方块 spacetime_structure_maintenance_casing

    val SPACETIME_STRUCTURE_MAINTENANCE_CASING: BlockEntry<Block?> = createCasingBlock(
        "spacetime_structure_maintenance_casing",
        "时空结构维持机械方块",
        GtocoreAssets.gtocoreTexture("block/casings/spacetime_structure_maintenance_casing")
    )
    // 起源之铭符 the_origin_casing

    val THE_ORIGIN_CASING: BlockEntry<Block?> =
        createCasingBlock("the_origin_casing", "起源之铭符",  GtocoreAssets.gtocoreTexture("block/casings/the_origin_casing"))
    // 终末之铭符 the_end_casing

    val THE_END_CASING: BlockEntry<Block?> =
        createCasingBlock("the_end_casing", "终末之铭符",  GtocoreAssets.gtocoreTexture("block/casings/the_end_casing"))
    // 混沌之铭符 the_chaos_casing

    val THE_CHAOS_CASING: BlockEntry<Block?> =
        createCasingBlock("the_chaos_casing", "混沌之铭符",  GtocoreAssets.gtocoreTexture("block/casings/the_chaos_casing"))
    // 天辉凝聚之镜 the_solaris_lens

    val THE_SOLARIS_LENS: BlockEntry<GlassBlock?> =
        createGlassCasingBlock("the_solaris_lens", "天辉凝聚之镜",  GtocoreAssets.gtocoreTexture("block/casings/the_solaris_lens"))

    // ===== GTOBlocks.java 有、本文件原先缺的 12 条 =====

    val HIGH_STRENGTH_SPACE_ELEVATOR_CABLE: BlockEntry<Block?> = createCasingBlock(
        "high_strength_space_elevator_cable",
        "高强度太空电梯绳索",
         GtocoreAssets.gtocoreTexture("block/casings/high_strength_space_elevator_cable")
    )

    val ENERGY_CONTROL_CASING_MK1: BlockEntry<Block?> = createSidedBloomCasingBlock(
        "energy_control_casing_mk1",
        "能量控制方块 MK I",
         GtocoreAssets.gtocoreTexture("block/casings/sided/energy_control_casing_mk1")
    )

    val ENERGY_CONTROL_CASING_MK2: BlockEntry<Block?> = createSidedBloomCasingBlock(
        "energy_control_casing_mk2",
        "能量控制方块 MK II",
         GtocoreAssets.gtocoreTexture("block/casings/sided/energy_control_casing_mk2")
    )

    val ENERGY_CONTROL_CASING_MK3: BlockEntry<Block?> = createSidedBloomCasingBlock(
        "energy_control_casing_mk3",
        "能量控制方块 MK III",
         GtocoreAssets.gtocoreTexture("block/casings/sided/energy_control_casing_mk3")
    )

    val MACHINING_CONTROL_CASING_MK1: BlockEntry<Block?> = createSidedBloomCasingBlock("machining_control_casing_mk1",
        "运行控制方块 MK I",
         GtocoreAssets.gtocoreTexture("block/casings/sided/machining_control_casing_mk1")
    )

    val MACHINING_CONTROL_CASING_MK2: BlockEntry<Block?> = createSidedBloomCasingBlock("machining_control_casing_mk2", "运行控制方块 MK II",
        GtocoreAssets.gtocoreTexture("block/casings/sided/machining_control_casing_mk2")
    )

    val MACHINING_CONTROL_CASING_MK3: BlockEntry<Block?> = createSidedBloomCasingBlock("machining_control_casing_mk3", "运行控制方块 MK III",
        GtocoreAssets.gtocoreTexture("block/casings/sided/machining_control_casing_mk3")
    )

    val FUSION_CASING_MK4: BlockEntry<Block?> = createCasingBlock("fusion_casing_mk4", "聚变机械方块 MK IV",
        GtocoreAssets.gtocoreTexture("block/casings/fusion/fusion_casing_mk4")
    )

    val FUSION_CASING_MK5: BlockEntry<Block?> = createCasingBlock("fusion_casing_mk5", "聚变机械方块 MK V",
        GtocoreAssets.gtocoreTexture("block/casings/fusion/fusion_casing_mk5")
    )

    val ENERGETIC_PHOTOVOLTAIC_BLOCK: BlockEntry<Block?> = createCasingBlock("energetic_photovoltaic_block", "充能光伏方块",
        GtocoreAssets.gtocoreTexture("block/energetic_photovoltaic_block")
    )

    val PULSATING_PHOTOVOLTAIC_BLOCK: BlockEntry<Block?> = createCasingBlock("pulsating_photovoltaic_block", "脉冲光伏方块",
        GtocoreAssets.gtocoreTexture("block/pulsating_photovoltaic_block"))

    val VIBRANT_PHOTOVOLTAIC_BLOCK: BlockEntry<Block?> = createCasingBlock("vibrant_photovoltaic_block", "振动光伏方块",
        GtocoreAssets.gtocoreTexture("block/vibrant_photovoltaic_block"))

}

