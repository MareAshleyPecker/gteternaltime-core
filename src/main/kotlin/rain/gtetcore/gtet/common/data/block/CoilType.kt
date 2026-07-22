package rain.gtetcore.gtet.common.data.block

import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.api.data.chemical.material.Material
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable
import rain.gtetcore.GTET.common.data.ETMaterial
import rain.gtetcore.gtet.Gtetcore.Companion.id
import rain.gtetcore.gtet.util.lang.BlockLangUtil
import java.util.function.Supplier


/**
 * 本模组的线圈类型定义。
 *
 * 每个枚举常量定义一个线圈方块，通过 [ETBlock.createCoilBlock] 自动注册，
 * 并加入 GTCEu 的 [com.gregtechceu.gtceu.api.GTCEuAPI.HEATING_COILS] 映射表。
 *
 * ## 字段说明
 * @param serializedName 线圈序列化名称，用于方块 ID 和语言键
 * @param cnName 中文显示名，自动写入 [BlockLangUtil.BLOCK_LANG] 供 LangHandler 消费
 * @param coilTemperature 加热温度 (开尔文)，适用于电力高炉 — 温度越高可冶炼的材料越多
 * @param level 线圈等级，适用于电力熔炉 — 熔炉并行: [level] × 32，熔炉耗能: max(1, 16 × [level] / [energyDiscount])
 * @param energyDiscount 能量折扣，适用于热解炉和电力熔炉 — 裂化耗能: 100 - 10 × [level]，热解速度: tier=0 → 75，否则 50 × ([level] + 1)
 * @param materialSupplier 材料引用，使用 [Supplier] 延迟加载以支持在材料注册前定义线圈
 * @param texture 线圈纹理位置
 *
 * @see ETMaterial 材料持有者
 * @see ETBlock 方块注册入口
 */
enum class CoilType(
    private val serializedName: String,
    private val cnName: String,
    private val coilTemperature: Int,
    private val level: Int,
    private val energyDiscount: Int,
    private val materialSupplier: Supplier<Material>,
    private val texture: ResourceLocation
) : StringRepresentable, ICoilType {

    /** 示例线圈 — 铜镍合金，基础等级 */
    NAME("cupronickel", "测试线圈方块", 1800, 1, 1, Supplier { ETMaterial.MaterialNAME },
        id("block/coil/testcoil/machine_coil_cupronickel"))


    ;

    init {
        val blockId = "%s_coil_block".format(serializedName)
        BlockLangUtil.BLOCK_LANG[blockId] = cnName
    }

    // ======================== ICoilType 实现 ========================

    override fun getName(): String = serializedName
    override fun getCoilTemperature(): Int = coilTemperature
    override fun getLevel(): Int = level
    override fun getEnergyDiscount(): Int = energyDiscount
    override fun getTexture(): ResourceLocation = texture
    override fun getMaterial(): Material = materialSupplier.get()
    override fun getTier(): Int = ordinal
    override fun toString(): String = serializedName
    override fun getSerializedName(): String = serializedName
}