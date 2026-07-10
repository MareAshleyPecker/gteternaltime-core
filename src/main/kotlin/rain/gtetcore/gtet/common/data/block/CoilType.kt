package rain.gtetcore.gtet.common.data.block

import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.api.data.chemical.material.Material
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable
import rain.gtetcore.gtet.Gtetcore.Companion.id
import rain.gtetcore.gtet.common.material.ETMaterial
import java.util.function.Supplier

enum class CoilType(
    private val sname: String,
    // electric blast furnace properties
    private val coilTemperature: Int,
    // multi smelter properties
    private val level: Int,
    private val energyDiscount: Int,
    private val materialSupplier: Supplier<Material>,
    private val texture: ResourceLocation
) : StringRepresentable, ICoilType {

    NAME("name", 114514, 1, 1, Supplier { ETMaterial.MaterialNAME },
        id("block/coil/testcoil/machine_coil_cupronickel")),



    ;

    override fun getName(): String = sname
    override fun getCoilTemperature(): Int = coilTemperature
    override fun getLevel(): Int = level
    override fun getEnergyDiscount(): Int = energyDiscount
    override fun getTexture(): ResourceLocation = texture
    override fun getMaterial(): Material = materialSupplier.get()
    override fun getTier(): Int = ordinal
    override fun toString(): String = sname
    override fun getSerializedName(): String = sname
}