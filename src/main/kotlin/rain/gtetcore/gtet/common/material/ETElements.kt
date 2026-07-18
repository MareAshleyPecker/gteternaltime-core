package rain.gtetcore.gtet.common.material

import com.gregtechceu.gtceu.api.data.chemical.Element


/**
 * 自定义元素定义。 \
 * 构造参数: (质子,中子,半衰期秒,衰变产物,名称,符号,是否同位素) \
 * 如果半衰期 = -1 那么等于没有半衰期则decayTo=null, 是否同位素只有 ture and false
 * @sample com.gregtechceu.gtceu.common.data.GTElements.Ag
 */
object ETElements {
    fun init() {}
    val MaterialNAME = Element(1, 1, -1, null, "name", "NAME", false)
}
