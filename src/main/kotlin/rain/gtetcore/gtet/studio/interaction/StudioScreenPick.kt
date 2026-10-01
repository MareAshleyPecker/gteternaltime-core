package rain.gtetcore.gtet.studio.interaction

/**
 * **屏幕空间拾取** —— 用"光标离手柄在屏幕上多少像素"来判定命中。
 *
 * ## 为什么需要它（M2a 实机教训）
 * 原来的拾取判据是**世界空间半径**：`轴手柄命中半径 = 0.07 × 轴长`。
 * 轴长又按相机距离缩放（屏幕尺寸恒定），于是折算到屏幕上永远只有**三四个像素宽**——
 * 而鼠标是个像素级的指针，**根本戳不中**。实机反馈"三色轴拖不动"就是这么来的。
 *
 * 真实编辑器（Blender / Unity / Axiom）都是**按屏幕像素**判的：把轴的两端投影到屏幕上，
 * 量光标到这条线段有多少像素，小于阈值就算抓住。这样无论相机远近、是否斜看，
 * 手感都是恒定的"手指粗细"。
 *
 * ## 分工（重要）
 * **只用它来"选中"手柄**；拖拽过程中的位移计算仍然走世界空间射线求交
 * （[StudioDragSession]）—— 那个必须与几何一致，不能按像素来。
 *
 * ## 坐标
 * 全部用**窗口像素**（与 `MouseHandler.xpos()/ypos()` 同一套）。
 * 屏幕给的鼠标坐标是 GUI 缩放后的像素，用之前要乘 `guiScale`。
 *
 * @author rain fox
 */
data class StudioScreenPoint(val x: Double, val y: Double)

/** gizmo 各手柄投影到屏幕上的位置。 */
class StudioScreenGeometry(
    /** 每根轴：起点 → 轴尖（两个端点）。 */
    val axisSegments: Map<StudioAxis, Pair<StudioScreenPoint, StudioScreenPoint>>,
    /** 每个平面手柄：方块中心。 */
    val planeCenters: Map<StudioHandle.Plane, StudioScreenPoint>,
)

object StudioScreenPicker {

    /** 轴手柄的命中宽度（**GUI 像素**，实际阈值会乘缩放）。 */
    const val AXIS_GUI_PIXELS = 6.0

    /** 平面手柄的命中半径（GUI 像素）。 */
    const val PLANE_GUI_PIXELS = 9.0

    /**
     * 拾取：**轴优先于平面**（与 [StudioPicker] 同一条优先级），同类取屏幕距离最近的。
     *
     * @param guiScale GUI 缩放（阈值与光标都在窗口像素里，所以要乘它）
     * @return null = 屏幕上没碰到任何手柄（调用方会退回到世界空间射线拾取）
     */
    @JvmStatic
    fun pick(geometry: StudioScreenGeometry, cursor: StudioScreenPoint, guiScale: Double): StudioHandle? {
        val axisLimit = AXIS_GUI_PIXELS * guiScale
        val planeLimit = PLANE_GUI_PIXELS * guiScale

        var best: StudioHandle? = null
        var bestDistance = Double.MAX_VALUE

        for ((axis, segment) in geometry.axisSegments) {
            val d = distanceToSegment(cursor, segment.first, segment.second)
            if (d <= axisLimit && d < bestDistance) {
                bestDistance = d
                best = StudioHandle.Axis(axis)
            }
        }
        if (best != null) return best

        for ((handle, center) in geometry.planeCenters) {
            val d = distance(cursor, center)
            if (d <= planeLimit && d < bestDistance) {
                bestDistance = d
                best = handle
            }
        }
        return best
    }

    /** 点到线段的距离（像素）。退化成一个点时等价于点距。 */
    @JvmStatic
    fun distanceToSegment(p: StudioScreenPoint, a: StudioScreenPoint, b: StudioScreenPoint): Double {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lengthSqr = abx * abx + aby * aby
        if (lengthSqr <= 1e-9) return distance(p, a)
        // 投影参数夹到 [0,1] —— 夹取这一步就是"线段"与"直线"的全部区别
        var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / lengthSqr
        t = t.coerceIn(0.0, 1.0)
        return distance(p, StudioScreenPoint(a.x + abx * t, a.y + aby * t))
    }

    /** 两点距离。 */
    @JvmStatic
    fun distance(p: StudioScreenPoint, q: StudioScreenPoint): Double {
        val dx = p.x - q.x
        val dy = p.y - q.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}