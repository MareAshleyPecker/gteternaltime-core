package rain.gtetcore.gtet.studio.interaction

/**
 * **gizmo 的几何 + 拾取**（全部在面坐标系局部空间里，纯数学）。
 *
 * ## 为什么"局部空间"就够了
 * 局部→世界的变换**完全复用渲染器那一套**（`moveToFace` + `applyFaceFrame`，
 * 见 [rain.gtetcore.gtet.studio.render.StudioFaceTransform]）：它只有平移和 90° 旋转、没有缩放，
 * 所以局部空间的 1 单位 = 世界 1 格。于是：
 * - 三根轴在局部空间恒为 `e_x / e_y / e_z`，不必自己推朝向（推错就是"gizmo 和模型差一个旋转"这种坑）；
 * - `anchor.offset` 的每个分量恰好就是"沿对应轴的位移"，拖 X 轴 = 改 `offset.x`，
 *   不需要任何补偿系数；
 * - 拾取时把世界射线**反变换进局部空间**（[StudioGizmoRenderer] 用同一份矩阵求逆），
 *   之后所有数学都与机器朝向无关。
 *
 * ## 手柄布局（[length] 是整根轴长，随距离缩放，见 [StudioGizmoRenderer]）
 * ```
 *        端点小方块（轴尖）
 *           │
 *           │   ← 轴手柄：从 0.12L 到 1.0L 的细圆柱（命中半径 0.07L）
 *      原点 ●
 *        ╱
 *    平面手柄：位于 0.45L·(e_i+e_j)，边长 0.17L 的小方块（双轴拖动）
 * ```
 *
 * @author rain fox
 */
class StudioGizmoFrame(
    /** gizmo 原点 = **当前** `anchor.offset`（局部坐标）。 */
    val anchor: StudioVec3,
    /** 轴长（局部单位 = 格）。 */
    val length: Double,
) {

    init {
        require(length > 1e-6) { "gizmo 轴长必须为正，收到 $length" }
    }

    /** 轴手柄（细圆柱）的命中半径。 */
    val axisRadius: Double get() = length * AXIS_RADIUS_FACTOR

    /** 轴尖小方块的半边长。 */
    val tipHalf: Double get() = length * TIP_HALF_FACTOR

    /** 平面手柄小方块的半边长。 */
    val planeHalf: Double get() = length * PLANE_HALF_FACTOR

    /** 轴手柄的起点（留出一小段，免得三根轴的命中区在原点糊成一团）。 */
    private fun axisStartDistance(): Double = length * AXIS_START_FACTOR

    /** 平面手柄离原点的距离系数（沿两根轴各走这么多）。 */
    private fun planeDistance(): Double = length * PLANE_DISTANCE_FACTOR

    fun axisFrom(axis: StudioAxis): StudioVec3 = anchor + axis.unit * axisStartDistance()

    fun axisTo(axis: StudioAxis): StudioVec3 = anchor + axis.unit * length

    /** 轴尖小方块的中心。 */
    fun tipCenter(axis: StudioAxis): StudioVec3 = anchor + axis.unit * length

    /** 平面手柄小方块的中心（法线由 [normal] 决定，方块在法线垂直的那块平面里）。 */
    fun planeCenter(first: StudioAxis, second: StudioAxis): StudioVec3 =
        anchor + first.unit * planeDistance() + second.unit * planeDistance()

    /** 轴尖小方块（轴对齐）。 */
    fun tipBox(axis: StudioAxis): Pair<StudioVec3, StudioVec3> {
        val c = tipCenter(axis)
        val h = tipHalf
        return StudioVec3(c.x - h, c.y - h, c.z - h) to StudioVec3(c.x + h, c.y + h, c.z + h)
    }

    /** 平面手柄小方块（在局部空间里也是轴对齐的：面坐标系只由 90° 旋转/翻转组成）。 */
    fun planeBox(first: StudioAxis, second: StudioAxis): Pair<StudioVec3, StudioVec3> {
        val c = planeCenter(first, second)
        val h = planeHalf
        return StudioVec3(c.x - h, c.y - h, c.z - h) to StudioVec3(c.x + h, c.y + h, c.z + h)
    }

    companion object {
        const val AXIS_RADIUS_FACTOR = 0.07
        const val TIP_HALF_FACTOR = 0.05
        const val PLANE_HALF_FACTOR = 0.085
        const val AXIS_START_FACTOR = 0.12
        const val PLANE_DISTANCE_FACTOR = 0.45
    }
}

/** 被命中的手柄。 */
sealed class StudioHandle {

    /** 单轴手柄（拖 X 只改 X）。 */
    data class Axis(val axis: StudioAxis) : StudioHandle() {
        override fun toString(): String = "轴 ${axis.label}"
    }

    /** 平面手柄（双轴拖动；[normal] 是这块平面的法线轴）。 */
    data class Plane(val first: StudioAxis, val second: StudioAxis, val normal: StudioAxis) : StudioHandle() {
        override fun toString(): String = "平面 ${first.label}${second.label}"
    }

    companion object {

        /** 法线为 [normal] 的那块平面手柄（另外两根轴就是它的两条边）。 */
        @JvmStatic
        fun ofPlane(normal: StudioAxis): Plane {
            val others = StudioAxis.others(normal)
            return Plane(others[0], others[1], normal)
        }
    }
}

/** 射线命中机器的结果（距离 = 沿射线的参数）。 */
class StudioMachineHit(val distance: Double)

/** 拾取结果：**先手柄、再机器**（设计文档 §6 明确要求处理优先级）。 */
sealed class StudioPick {

    data class Handle(val handle: StudioHandle, val distance: Double) : StudioPick()

    data class Machine(val distance: Double) : StudioPick()
}

/**
 * 手柄拾取。
 *
 * 优先级：**轴手柄 → 平面手柄 → 机器**。
 * 轴优先于平面是刻意的：平面手柄离原点远、块也大，如果它优先，贴近原点处想抓轴就会老是抓到平面。
 * 同类里取**沿射线最近**的那个（否则两根轴在屏幕上重叠时会抓到你没在看的那个）。
 *
 * @author rain fox
 */
object StudioPicker {

    /**
     * 只拾手柄。
     *
     * @return null = 没命中任何手柄
     */
    @JvmStatic
    fun pickHandle(frame: StudioGizmoFrame, ray: StudioRay): StudioHandle? {
        var best: StudioHandle? = null
        var bestT = Double.MAX_VALUE

        // ── ① 三根轴（含轴尖小方块）──
        for (axis in StudioAxis.entries) {
            val from = frame.axisFrom(axis)
            val approach = StudioRayMath.closestApproach(ray, from, axis.unit) ?: continue
            // 最近点必须在射线前方，且落在 [起点, 轴尖] 这段上
            if (approach.rayParam < 0.0) continue
            val segmentLength = (frame.axisTo(axis) - from).length
            if (approach.axisParam < -frame.tipHalf || approach.axisParam > segmentLength + frame.tipHalf) continue
            var t: Double?
            if (approach.distance <= frame.axisRadius) {
                t = approach.rayParam
            } else {
                // 轴尖小方块：点在方块里也算抓到这根轴
                val (min, max) = frame.tipBox(axis)
                t = StudioRayMath.aabbHit(ray, min, max)
            }
            if (t != null && t < bestT) {
                bestT = t
                best = StudioHandle.Axis(axis)
            }
        }
        if (best != null) return best

        // ── ② 三个平面手柄 ──
        for (normal in StudioAxis.entries) {
            val handle = StudioHandle.ofPlane(normal)
            val (min, max) = frame.planeBox(handle.first, handle.second)
            val t = StudioRayMath.aabbHit(ray, min, max) ?: continue
            if (t < bestT) {
                bestT = t
                best = handle
            }
        }
        return best
    }

    /**
     * 完整拾取：**先手柄、再机器**。
     *
     * [machineHit] **只在不命中任何手柄时才会被调用** —— 这是优先级纪律的落点，
     * 自检里用"回调有没有被调用"钉住了它（手柄挡住机器时绝不能穿透到机器）。
     */
    @JvmStatic
    fun pick(
        frame: StudioGizmoFrame,
        ray: StudioRay,
        machineHit: () -> StudioMachineHit? = { null },
    ): StudioPick? {
        pickHandle(frame, ray)?.let { handle ->
            val t = handleDistance(frame, ray, handle)
            return StudioPick.Handle(handle, t)
        }
        val machine = machineHit() ?: return null
        return StudioPick.Machine(machine.distance)
    }

    /** 命中的手柄沿射线的参数（反馈/排序用；理论上不会为 null，兜底给 0）。 */
    private fun handleDistance(frame: StudioGizmoFrame, ray: StudioRay, handle: StudioHandle): Double = when (handle) {
        is StudioHandle.Axis -> StudioRayMath.closestApproach(ray, frame.axisFrom(handle.axis), handle.axis.unit)
            ?.takeIf { it.rayParam >= 0.0 }?.rayParam ?: 0.0

        is StudioHandle.Plane -> frame.planeBox(handle.first, handle.second)
            .let { (min, max) -> StudioRayMath.aabbHit(ray, min, max) } ?: 0.0
    }
}