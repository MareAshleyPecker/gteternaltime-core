package rain.gtetcore.gtet.common.material

import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags.*
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.common.data.materials.ElementMaterials
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.CommonProxy
import rain.gtetcore.gtet.common.material.ETElementMaterials.sample
import rain.gtetcore.gtet.common.material.ETMaterial.MaterialNAME

/**
 * 所有标志位均定义在 [com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialFlags] 中，可通过 `MaterialFlags.FLAG_NAME` 调用。
 *
 * ## 通用标志 (GENERIC)
 * | 调用名 | 英文说明 | 中文说明 |
 * |---|---:|---|
 * | `NO_UNIFICATION` | Disable unification fully (deprecated) | 完全禁用统一化（已废弃） |
 * | `DISABLE_MATERIAL_RECIPES` | Disable material recipe generation | 禁用该材料的所有配方自动生成 |
 * | `DECOMPOSITION_BY_ELECTROLYZING` | Enable electrolyzer decomposition recipes | 启用电解机分解配方生成 |
 * | `DECOMPOSITION_BY_CENTRIFUGING` | Enable centrifuge decomposition recipes | 启用离心机分解配方生成 |
 * | `DISABLE_DECOMPOSITION` | Disable decomposition recipe generation | 禁用分解配方生成 |
 * | `EXPLOSIVE` | Material is explosive | 材料具有爆炸性 |
 * | `FLAMMABLE` | Material is flammable | 材料可燃 |
 * | `STICKY` | Material is sticky | 材料具有粘性 |
 * | `PHOSPHORESCENT` | Material is phosphorescent | 材料具有磷光性 |
 * | `FIRE_RESISTANT` | Material is fire resistant | 材料耐火 |
 *
 * ## 粉/尘类 (DUST) — 需要 PropertyKey.DUST
 * | 调用名 | 英文说明 | 中文说明 |
 * |---|---:|---|
 * | `GENERATE_PLATE` | Generate plate recipes | 生成板的合成/加工配方 |
 * | `GENERATE_DENSE` | Generate dense plate recipes | 生成致密板配方 (依赖 GENERATE_PLATE) |
 * | `GENERATE_ROD` | Generate rod recipes | 生成杆配方 |
 * | `GENERATE_BOLT_SCREW` | Generate bolt & screw recipes | 生成螺栓与螺丝配方 (依赖 GENERATE_ROD) |
 * | `GENERATE_FRAME` | Generate frame recipes | 生成框架配方 (依赖 GENERATE_ROD) |
 * | `GENERATE_GEAR` | Generate gear recipes | 生成齿轮配方 (依赖 GENERATE_PLATE + GENERATE_ROD) |
 * | `GENERATE_LONG_ROD` | Generate long rod recipes | 生成长杆配方 (依赖 GENERATE_ROD) |
 * | `FORCE_GENERATE_BLOCK` | Force block generation | 强制生成方块形式 |
 * | `EXCLUDE_BLOCK_CRAFTING_RECIPES` | Prevent dust↔block shapeless recipes | 禁用粉-方块之间的无序合成/挤出/合金冶炼配方 |
 * | `EXCLUDE_PLATE_COMPRESSOR_RECIPE` | Exclude compressor plate recipe | 禁用压缩机压板配方 (依赖 GENERATE_PLATE) |
 * | `EXCLUDE_BLOCK_CRAFTING_BY_HAND_RECIPES` | Prevent manual dust↔block recipes | 禁用手动粉-方块无序合成 |
 * | `MORTAR_GRINDABLE` | Can be ground with mortar | 可用研钵研磨 |
 * | `NO_WORKING` | Cannot be worked (only smashing/smelting) | 不可加工，仅能粉碎或熔炼 |
 * | `NO_SMASHING` | Cannot be smashed into dust | 不可粉碎成粉 |
 * | `NO_SMELTING` | Cannot be smelted | 不可熔炼 |
 * | `NO_ORE_SMELTING` | Cannot be smelted from ore | 不可从矿石熔炼得到 |
 * | `NO_ORE_PROCESSING_TAB` | Disable ore processing tab | 禁用矿石处理标签页 (需要 ORE property) |
 * | `BLAST_FURNACE_CALCITE_DOUBLE` | Blast furnace calcite gives 2x output | 高炉处理方解石粉双倍产出 |
 * | `BLAST_FURNACE_CALCITE_TRIPLE` | Blast furnace calcite gives 3x output | 高炉处理方解石粉三倍产出 |
 *
 * ## GCYM 合金高炉
 * | 调用名 | 英文说明 | 中文说明 |
 * |---|---:|---|
 * | `DISABLE_ALLOY_BLAST` | Disable alloy blast recipes | 禁用合金高炉配方生成 (需要 BLAST + FLUID) |
 * | `DISABLE_ALLOY_PROPERTY` | Disable alloy property entirely | 完全禁用合金属性 (依赖 DISABLE_ALLOY_BLAST) |
 *
 * ## 流体类 (FLUID) — 需要 PropertyKey.FLUID
 * | 调用名 | 英文说明 | 中文说明 |
 * |---|---:|---|
 * | `SOLDER_MATERIAL` | Material usable as solder | 可用作焊料 |
 * | `SOLDER_MATERIAL_BAD` | Low-quality solder material | 低品质焊料 |
 * | `SOLDER_MATERIAL_GOOD` | High-quality solder material | 高品质焊料 |
 *
 * ## 锭类 (INGOT) — 需要 PropertyKey.INGOT
 * | 调用名 | 英文说明 | 中文说明 |
 * |---|---:|---|
 * | `GENERATE_FOIL` | Generate foil recipes | 生成箔配方 (依赖 GENERATE_PLATE) |
 * | `GENERATE_RING` | Generate ring recipes | 生成环配方 (依赖 GENERATE_ROD) |
 * | `GENERATE_SPRING` | Generate spring recipes | 生成弹簧配方 (依赖 GENERATE_LONG_ROD) |
 * | `GENERATE_SPRING_SMALL` | Generate small spring recipes | 生成小弹簧配方 (依赖 GENERATE_ROD) |
 * | `GENERATE_SMALL_GEAR` | Generate small gear recipes | 生成小齿轮配方 (依赖 GENERATE_PLATE + GENERATE_ROD) |
 * | `GENERATE_FINE_WIRE` | Generate fine wire recipes | 生成细线配方 (依赖 GENERATE_FOIL) |
 * | `GENERATE_ROTOR` | Generate rotor recipes | 生成转子配方 (依赖 BOLT_SCREW + RING + PLATE) |
 * | `GENERATE_ROUND` | Generate round recipes | 生成圆配方 |
 * | `IS_MAGNETIC` | Material is a magnetized form | 标记为磁性材料（某材料的磁化形态） |
 *
 * ## 宝石类 (GEM) — 需要 PropertyKey.GEM
 * | 调用名 | 英文说明 | 中文说明 |
 * |---|---:|---|
 * | `CRYSTALLIZABLE` | Material can be crystallized | 材料可结晶 |
 * | `GENERATE_LENS` | Generate lens recipes | 生成透镜配方 (依赖 GENERATE_PLATE) |
 *
 * ## 矿石类 (ORE) — 需要 PropertyKey.GEM + PropertyKey.ORE
 * | 调用名 | 英文说明 | 中文说明 |
 * |---|---:|---|
 * | `HIGH_SIFTER_OUTPUT` | High sifter output for this ore | 矿石在筛分机中高倍产出 |
 *
 * ---
 * 共 **46** 个标志位，5 个分类。
 */
object ETElementMaterials {

    fun sample() {//这个是示例
        MaterialNAME = Material.Builder(Gtetcore.id("materialname"))
            .color(/*any 16EX sum*/0xFFB6C1)
            .ingot().fluid().dust().ore()
            .appendFlags(
                GTMaterials.EXT2_METAL,
                GENERATE_DENSE,
                GENERATE_DENSE,
                GENERATE_PLATE,
                GENERATE_BOLT_SCREW,
                GENERATE_FRAME,
                GENERATE_GEAR,
                GENERATE_LONG_ROD,
            )
            .element(ETElements.MaterialNAME)
            .buildAndRegister()
    }

    /**
     * 初始化在register里，到总线[CommonProxy.kotlinInit]里注册
     *
     * 注册请参考 [ElementMaterials]
     *
     * @sample sample
     * */
    fun register() {
        sample()
    }
}