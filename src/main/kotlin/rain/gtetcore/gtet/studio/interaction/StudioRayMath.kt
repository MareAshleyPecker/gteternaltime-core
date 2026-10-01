package rain.gtetcore.gtet.studio.interaction

import rain.gtetcore.gtet.studio.interaction.StudioRay.Companion.of
import rain.gtetcore.gtet.studio.interaction.StudioRayMath.aabbHit
import rain.gtetcore.gtet.studio.interaction.StudioRayMath.closestApproach
import rain.gtetcore.gtet.studio.interaction.StudioRayMath.distanceToSegment
import rain.gtetcore.gtet.studio.interaction.StudioRayMath.rayPlanePoint
import rain.gtetcore.gtet.studio.interaction.StudioRayMath.rayPlaneT
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 世界（或局部）空间的一条射线。
 *
 * **方向必须已归一化** —— 归一化之后"射线参数 t"就等价于"距离"，拾取阈值、
 * 吸附增量、命中优先级（谁更近）才好比较。构造请走 [of]，它会拒绝零向量。
 *
 * @author rain fox
 */
class StudioRay private constructor(
    val origin: StudioVec3,
    private val unitDirection: StudioVec3,
) {

    /** 归一化方向（长度 1）。 */
    val direction: StudioVec3 get() = unitDirection

    /** 沿射线走 [t] 格的位置。 */
    fun at(t: Double): StudioVec3 = origin + unitDirection * t

    override fun toString(): String = "射线[$origin → $unitDirection]"

    companion object {

        /** 平行的判定阈值（方向都已归一化，所以这是"夹角余弦 ≈ 0"）。 */
        const val PARALLEL_EPS: Double = 1e-7

        /** @return null = 方向退化（零向量/NaN），调用方必须显式报错或放弃这次拾取 */
        @JvmStatic
        fun of(origin: StudioVec3, direction: StudioVec3): StudioRay? {
            val unit = direction.normalized() ?: return null
            if (!origin.isFinite) return null
            return StudioRay(origin, unit)
        }
    }
}

/** 两条**直线**的最近点（射线 × 轴线）。 */
class StudioApproach(
    /** 射线上的参数（= 距射线原点的距离）。< 0 表示最近点在射线**背后**。 */
    val rayParam: Double,
    /** 轴上的参数（= 距轴原点的距离，轴方向已归一化）。 */
    val axisParam: Double,
    /** 两个最近点之间的距离。 */
    val distance: Double,
) {
    override fun toString(): String =
        "最近点[射线 t=${StudioNumberFormat.trim(rayParam)}, 轴 s=${StudioNumberFormat.trim(axisParam)}, " +
            "距离 ${StudioNumberFormat.trim(distance)}]"
}

/**
 * 交互层的**射线数学** —— 全部纯 Kotlin、可脱机自检。
 *
 * 四个函数对应世界内编辑要做的四件事（设计文档 §6 的技术清单）：
 * | 函数 | 用在哪 |
 * |---|---|
 * | [rayPlaneT] / [rayPlanePoint] | 平面手柄求交 → 双轴拖动的增量 |
 * | [closestApproach] | 轴手柄求交 → 单轴拖动的增量 |
 * | [aabbHit] | 命中「机器/平面手柄的小方块」 |
 * | [distanceToSegment] | 轴手柄（细圆柱）的命中半径 |
 *
 * **退化情形一律返回 null，不返回 NaN/Infinity** —— 射线与轴平行、射线与平面平行、
 * 方向是零向量，这些在实机上都会发生（正对着轴拖、相机与平面共面），
 * 一旦让 NaN 流下去，模型坐标会被写成 NaN 并且**再也回不来**。
 *
 * @author rain fox
 */
object StudioRayMath {

    /** 求交/求最近点时的通用极小量。 */
    private const val EPS = 1e-9

    /**
     * 射线与平面的交点参数 t（`交点 = ray.at(t)`）。
     *
     * @return null = **平行**（或射线方向退化为零向量）。注意"射线躺在平面里"也算平行：
     *         那时交点不唯一，不能瞎选一个。
     */
    @JvmStatic
    fun rayPlaneT(ray: StudioRay, planePoint: StudioVec3, planeNormal: StudioVec3): Double? {
        val normal = planeNormal.normalized() ?: return null
        val denom = normal.dot(ray.direction)
        if (abs(denom) < StudioRay.PARALLEL_EPS) return null
        val t = planePoint.minus(ray.origin).dot(normal) / denom
        return if (t.isFinite()) t else null
    }

    /**
     * 射线与平面的交点。
     *
     * @param forwardOnly true = **背向的交点不要**（t < 0 时返回 null）。
     *                    拖拽/拾取都该用 true：屏幕背后的交点如果被当成命中，
     *                    手柄会在"你背对它的时候"莫名其妙地粘住。
     */
    @JvmStatic
    fun rayPlanePoint(
        ray: StudioRay,
        planePoint: StudioVec3,
        planeNormal: StudioVec3,
        forwardOnly: Boolean = true,
    ): StudioVec3? {
        val t = rayPlaneT(ray, planePoint, planeNormal) ?: return null
        if (forwardOnly && t < 0.0) return null
        return ray.at(t)
    }

    /**
     * 射线与**过 [axisPoint]、方向 [axisDir] 的直线**的最近点对。
     *
     * @return null = 射线与轴**平行**（两条平行线没有唯一最近点，硬算会除以 0）
     */
    @JvmStatic
    fun closestApproach(ray: StudioRay, axisPoint: StudioVec3, axisDir: StudioVec3): StudioApproach? {
        val d = axisDir.normalized() ?: return null
        val w0 = ray.origin - axisPoint
        val b = ray.direction.dot(d)
        val denom = 1.0 - b * b // a=c=1（两个方向都归一化了）
        if (abs(denom) < StudioRay.PARALLEL_EPS) return null

        val dd = ray.direction.dot(w0)
        val e = d.dot(w0)
        val rayParam = (b * e - dd) / denom
        val axisParam = (e - b * dd) / denom
        val pRay = ray.at(rayParam)
        val pAxis = axisPoint + d * axisParam
        val distance = pRay.minus(pAxis).length
        if (!rayParam.isFinite() || !axisParam.isFinite() || !distance.isFinite()) return null
        return StudioApproach(rayParam, axisParam, distance)
    }

    /**
     * 射线与**轴对齐包围盒**的求交（slab 法），返回进入点的 t。
     *
     * 用途：平面手柄那个小方块 —— 它在面坐标系里是轴对齐的，在世界里也是
     * （面坐标系只由 90° 的旋转和翻转组成），所以不必带一整套 OBB。
     *
     * @return null = 没打到；射线原点在盒内时返回 0
     */
    @JvmStatic
    fun aabbHit(ray: StudioRay, min: StudioVec3, max: StudioVec3): Double? {
        var tMin = Double.NEGATIVE_INFINITY
        var tMax = Double.POSITIVE_INFINITY
        for (axis in StudioAxis.entries) {
            val o = ray.origin[axis]
            val d = ray.direction[axis]
            val lo = min[axis]
            val hi = max[axis]
            if (abs(d) < EPS) {
                // 在这一维上不前进：原点必须落在板内，否则永远打不到
                if (o < lo || o > hi) return null
                continue
            }
            var t1 = (lo - o) / d
            var t2 = (hi - o) / d
            if (t1 > t2) {
                val tmp = t1
                t1 = t2
                t2 = tmp
            }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return null
        }
        if (tMax < 0.0) return null // 整个盒子在相机背后
        val t = if (tMin >= 0.0) tMin else 0.0
        return if (t.isFinite()) t else null
    }

    /** 点到线段（不是直线！）的最短距离。轴手柄的"细圆柱"命中判据就是它 ≤ 半径。 */
    @JvmStatic
    fun distanceToSegment(point: StudioVec3, a: StudioVec3, b: StudioVec3): Double {
        val ab = b - a
        val lenSqr = ab.lengthSqr
        if (lenSqr < EPS) return point.minus(a).length
        var t = point.minus(a).dot(ab) / lenSqr
        if (t < 0.0) t = 0.0
        if (t > 1.0) t = 1.0
        return point.minus(a + ab * t).length
    }

    /** 点到点的距离（可读性包装）。 */
    @JvmStatic
    fun distance(a: StudioVec3, b: StudioVec3): Double = sqrt((a - b).lengthSqr)
}