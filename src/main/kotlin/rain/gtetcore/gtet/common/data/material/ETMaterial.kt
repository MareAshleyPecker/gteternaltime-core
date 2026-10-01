package rain.gtetcore.gtet.common.data.material
import com.gregtechceu.gtceu.api.data.chemical.material.Material

/**
 * 本 mod 自定义材料的集中持有处：注册阶段写进来，别处直接读。
 * 字段在 mod 注册阶段赋值，读取方都在那之后取值，所以用非空 `lateinit`。
 *
 * @see ETElementMaterials
 * @author rain fox
 */
object ETMaterial {

    lateinit var MaterialNAME: Material
    lateinit var GFRP: Material
    lateinit var Resin: Material
    lateinit var GlassFiber: Material
    lateinit var Al2O3: Material
    lateinit var CaO: Material
    lateinit var Na2O: Material
    lateinit var K2O: Material

    @JvmStatic
    fun init() {}
}