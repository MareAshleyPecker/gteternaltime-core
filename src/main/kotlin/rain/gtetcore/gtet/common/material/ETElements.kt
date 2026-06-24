package rain.gtetcore.gtet.common.material

import com.gregtechceu.gtceu.api.data.chemical.Element

/**
 * 自定义元素定义。
 *
 * 直接创建 [Element] 实例，<b>不</b>注册到 GTRegistries.ELEMENTS。
 * Material 通过 `.element()` 接收的是对象引用，无需注册表查询，
 * 因此绕开了 data-gen 及 GTCEu 注册表冻结的限制。
 *
 * 添加新元素：照格式加一行 val 即可。
 * 构造参数: (质子, 中子, 半衰期秒, 衰变产物, 名称, 符号, 是否同位素)
 */
object ETElements {

    val TEST = Element(1, 0, -1, null, "test", "test", false)
}
