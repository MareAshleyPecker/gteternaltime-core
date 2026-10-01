package rain.gtetcore.gtet.studio.kernel.cad

import kotlin.math.abs

/**
 * 工作室 CAD 内核（M3a）的**异常**。
 *
 * 与 [rain.gtetcore.gtet.studio.kernel.StudioMeshException] 的分工：
 * 那个说「**这份几何**不成立」，这个说「**这份特征历史**不成立」——
 * 参数非法（r<=0、count=0）、引用不存在的名字、引用了后面的特征、布尔算不出东西……
 * 一律在**载入期**抛出来，绝不静默削面（设计文档 §7.1 的精神）。
 *
 * @author rain fox
 */
class StudioCadException(message: String) : IllegalArgumentException(message)

// ══════════════════════════════════════════════════════════════════════════
//  2D 基本件
// ══════════════════════════════════════════════════════════════════════════

/**
 * 平面上的一个点。
 *
 * **为什么是 Double 而不是 Float**：布尔运算的中间量（交点、切割参数、扫描线样本）
 * 全是这里出来的，Float 的 7 位有效数字在「两个几乎重合的顶点」上会直接判错；
 * 降到 Float 只发生在最后写进 `StudioMesh` 那一刻。
 */
class Vec2(val x: Double, val y: Double) {

    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)

    val length: Double get() = kotlin.math.sqrt(x * x + y * y)

    /** 到另一点的距离。 */
    fun distanceTo(o: Vec2): Double = (this - o).length

    /** 按 [t] 线性插值（`t=0` 是 this，`t=1` 是 [o]）。 */
    fun lerp(o: Vec2, t: Double) = Vec2(x + (o.x - x) * t, y + (o.y - y) * t)

    override fun equals(other: Any?): Boolean = other is Vec2 && other.x == x && other.y == y

    override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()

    override fun toString(): String = "(${trim(x)}, ${trim(y)})"

    private fun trim(v: Double): String {
        val r = kotlin.math.round(v * 10000.0) / 10000.0
        return if (r == kotlin.math.floor(r) && abs(r) < 1e15) r.toLong().toString() else r.toString()
    }
}

/** 轴对齐的 2D 包围盒。 */
class Box2(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val sizeX: Double get() = maxX - minX
    val sizeY: Double get() = maxY - minY
    override fun toString(): String = "[($minX, $minY) ~ ($maxX, $maxY)]"
}

/** 两个向量的叉积（z 分量）。> 0 表示 a→b→c 是左转。 */
internal fun cross(a: Vec2, b: Vec2, c: Vec2): Double =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

/**
 * 一条**闭合轮廓**：`points` 按顺序连成环，**首尾不重复**（最后一点自动连回第 0 点）。
 *
 * 朝向是有意义的（外环逆时针、孔顺时针），由 [Profile.normalized] 负责统一。
 */
class Contour(val points: List<Vec2>) {

    init {
        require(points.size >= 3) { "轮廓至少要 3 个点，实际 ${points.size}" }
    }

    val size: Int get() = points.size

    /** 有向面积（鞋带公式）÷2：逆时针为正。 */
    fun signedArea(): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum * 0.5
    }

    fun bounds(): Box2 {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return Box2(minX, minY, maxX, maxY)
    }

    /** 周长（自检与 UV 都用它）。 */
    fun perimeter(): Double {
        var sum = 0.0
        for (i in points.indices) sum += points[i].distanceTo(points[(i + 1) % points.size])
        return sum
    }

    fun reversed(): Contour = Contour(points.reversed())

    /** 改成指定朝向：[counterClockwise]=true 返回逆时针那份。 */
    fun oriented(counterClockwise: Boolean): Contour =
        if ((signedArea() > 0.0) == counterClockwise) this else reversed()

    /**
     * 点在轮廓内部吗 —— **奇偶规则**（射线向 +x，数穿越次数）。
     *
     * 选奇偶而不是非零环绕：奇偶天然把"孔"表达成另一条环（见 [Profile]），
     * `ring`（外圆 + 内圆）这类特征不需要跑一遍布尔就能成立。
     */
    fun contains(px: Double, py: Double): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > py) != (b.y > py)) {
                val x = a.x + (py - a.y) / (b.y - a.y) * (b.x - a.x)
                if (px < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    override fun toString(): String = "Contour(${points.size} 点，面积 ${signedArea()})"
}

/**
 * 一份 **2D 轮廓集** = 一个平面区域（可带孔、可多块）。
 *
 * ## 区域怎么理解（这条定死了，别处都按它推）
 * **奇偶规则**：一个点属于该区域 ⇔ 它在**奇数条**轮廓内部。
 * - 所以"带孔的盘" = [外圆, 内圆] 两条环即可（[StudioCadFeature] 的 `ring` 就是这么实现的）；
 * - 所以"圆减圆"的布尔**不需要**先跑布尔：直接给两条环；
 * - 所以 [array] 的副本若**互相重叠**，重叠处会被奇偶规则异或掉 —— 这是刻意的、写进文档的行为，
 *   不是 bug（时钟的孔/齿都是不重叠的；真要重叠合并请显式写 `union`）。
 *
 * ## 朝向约定
 * [normalized] 之后：外环逆时针（正面积）、孔顺时针（负面积）；
 * 于是 [area] 就是各环有向面积之和，法线方向也由它唯一确定。
 */
class Profile(val contours: List<Contour>) {

    val isEmpty: Boolean get() = contours.isEmpty()

    fun bounds(): Box2 {
        if (contours.isEmpty()) return Box2(0.0, 0.0, 0.0, 0.0)
        var box = contours[0].bounds()
        for (i in 1 until contours.size) {
            val b = contours[i].bounds()
            box = Box2(
                kotlin.math.min(box.minX, b.minX), kotlin.math.min(box.minY, b.minY),
                kotlin.math.max(box.maxX, b.maxX), kotlin.math.max(box.maxY, b.maxY),
            )
        }
        return box
    }

    /** 各环有向面积之和。**只有 [normalized] 之后它才是真实面积**（否则孔的方向可能反了）。 */
    fun signedAreaSum(): Double {
        var sum = 0.0
        for (c in contours) sum += c.signedArea()
        return sum
    }

    /** 区域面积（归一化之后取绝对值）。 */
    fun area(): Double = abs(normalized().signedAreaSum())

    /** 点在区域内吗（奇偶规则，跨所有环）。 */
    fun contains(px: Double, py: Double): Boolean {
        var inside = false
        for (c in contours) if (c.contains(px, py)) inside = !inside
        return inside
    }

    /**
     * 统一朝向：**嵌套深度为偶数的当外环（逆时针）、奇数当孔（顺时针）**。
     *
     * 深度用"这个环上的点被另外几条环包住"来数（奇偶意义下的包含）。
     * 输入是简单环（不自交）时这是准确的 —— 布尔运算的产物与手写图元都满足。
     */
    fun normalized(): Profile {
        val n = contours.size
        if (n == 0) return this
        val out = ArrayList<Contour>(n)
        for (i in 0 until n) {
            val probe = contours[i].points[0]
            var depth = 0
            for (j in 0 until n) {
                if (i == j) continue
                if (contours[j].contains(probe.x, probe.y)) depth++
            }
            out += contours[i].oriented(counterClockwise = depth % 2 == 0)
        }
        return Profile(out)
    }

    override fun toString(): String = "Profile(${contours.size} 环，面积 ${area()})"
}

/**
 * 两线段的交点参数 `(t, u)`：`a0 + t·(a1-a0) == b0 + u·(b1-b0)`。
 * 平行返回 null。
 */
internal fun intersectParams(a0: Vec2, a1: Vec2, b0: Vec2, b1: Vec2): Pair<Double, Double>? {
    val rx = a1.x - a0.x
    val ry = a1.y - a0.y
    val sx = b1.x - b0.x
    val sy = b1.y - b0.y
    val denom = rx * sy - ry * sx
    if (abs(denom) < 1e-14) return null
    val qpx = b0.x - a0.x
    val qpy = b0.y - a0.y
    return Pair((qpx * sy - qpy * sx) / denom, (qpx * ry - qpy * rx) / denom)
}