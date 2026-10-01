package rain.gtetcore.gtet.studio.interaction

import rain.gtetcore.gtet.studio.editor.StudioEditor
import kotlin.math.sqrt

/**
 * **可变的** `anchor.offset`（面坐标系，单位格）—— 编辑期间"当前值"就住在这里。
 *
 * ## 为什么是可变的
 * 实时预览要的是"拖一下就变"，而撤销栈要的是"按下时的值"和"松开时的值"两个**快照**。
 * 所以：编辑中的活值是一个可变对象（[StudioEditor] 拿着它），命令里存的是 [copy] 出来的快照
 * —— 快照不可变，命令的 `apply/revert` 才敢反复调用（`apply → revert → apply` 必须对称）。
 *
 * ## 为什么是 Float
 * 与 JSON / [rain.gtetcore.gtet.studio.api.StudioAnchor] 保持一致（那边的偏移就是 Float）。
 * 0.25 格吸附在二进制里是精确的（0.25 = 2⁻²），所以对齐过的值比较可以用很小的容差。
 *
 * @author rain fox
 */
class StudioAnchorOffset(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
) {

    constructor(v: StudioVec3) : this(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())

    /** 深拷贝（命令的快照、拖拽的起始值都用它）。 */
    fun copy(): StudioAnchorOffset = StudioAnchorOffset(x, y, z)

    /** 覆盖成 [other] 的值（**不换对象**，因为渲染和命令都按对象身份引用它）。 */
    fun setFrom(other: StudioAnchorOffset) {
        x = other.x
        y = other.y
        z = other.z
    }

    /** 覆盖成 [v] 的值（拖拽每帧走这条）。 */
    fun setVec(v: StudioVec3) {
        x = v.x.toFloat()
        y = v.y.toFloat()
        z = v.z.toFloat()
    }

    fun vec(): StudioVec3 = StudioVec3(x.toDouble(), y.toDouble(), z.toDouble())

    operator fun get(axis: StudioAxis): Float = when (axis) {
        StudioAxis.X -> x
        StudioAxis.Y -> y
        StudioAxis.Z -> z
    }

    operator fun set(axis: StudioAxis, value: Float) {
        when (axis) {
            StudioAxis.X -> x = value
            StudioAxis.Y -> y = value
            StudioAxis.Z -> z = value
        }
    }

    /**
     * 值相等（容差 1e-4）—— 判断"有没有未保存改动"。
     *
     * 用容差而不是 `==`：没吸附的精细拖动会产生 0.30000001 这种值，
     * 手一抖就多出一个"未保存改动"是错误的。
     */
    fun equalsValue(other: StudioAnchorOffset, eps: Float = 1e-4f): Boolean =
        kotlin.math.abs(x - other.x) <= eps &&
            kotlin.math.abs(y - other.y) <= eps &&
            kotlin.math.abs(z - other.z) <= eps

    /** 偏移向量长度（渲染包围盒要用：编辑中的偏移可能比文件里那份更长）。 */
    fun length(): Double = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)

    /** `(0, 1, 0)` 这种写法 —— 与 clock.json 里的注释写法一致，反馈里直接给用户看。 */
    fun format(): String =
        "(${StudioNumberFormat.trim(x)}, ${StudioNumberFormat.trim(y)}, ${StudioNumberFormat.trim(z)})"

    override fun toString(): String = format()
}