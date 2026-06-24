package rain.gtetcore.gtet.common.Coil

import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.api.data.chemical.material.Material
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.material.ETMaterial

enum class ETCoilEnum(
    private val coilName: String,
    private val _coilTemperature: Int,
    private val _level: Int,
    private val _energyDiscount: Int,
    materialSupplier: () -> Material,
    private val _texture: ResourceLocation
) : StringRepresentable, ICoilType {

    MACHINE_COIL_CUPRONICKEL(
        "machine_coil_cupronickel",
        114514, 20, 1,
        { ETMaterial.TEST },
        Gtetcore.id("block/coils/testcoil/machine_coil_cupronickel")
    );

    /**
     * 材料使用 lazy 延迟加载，避免初始化顺序问题：
     * [ETMaterial.TEST] 是 `lateinit var`，在 [ETElementMaterials.register] 中赋值。
     * lazy 确保材料只在首次访问时读取，而非枚举类加载时。
     */
    private val cachedMaterial: Material by lazy { materialSupplier() }

    override fun getCoilTemperature(): Int = _coilTemperature
    override fun getLevel(): Int = _level
    override fun getEnergyDiscount(): Int = _energyDiscount
    override fun getMaterial(): Material = cachedMaterial
    override fun getTexture(): ResourceLocation = _texture
    override fun getName(): String = coilName
    override fun getSerializedName(): String = coilName
    override fun getTier(): Int = ordinal

    override fun toString(): String = coilName
}
