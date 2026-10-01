package rain.gtetcore.gtet.studio.interaction

import kotlin.math.sqrt

/**
 * 交互层自己的三维向量（Double）。
 *
 * ## 为什么不直接用 `Vec3` / `Vector3f` / `org.joml`
 * `interaction/` 里这一小块数学（射线求交、吸附、拖拽增量）**必须能脱离游戏跑自检**：
 * 自检的 classpath 只有 kotlin-stdlib 与 gson（见 [StudioInteractionSelfCheck]），
 * Minecraft 的 `Vec3` 与 JOML 都不在上面。所以纯数学一律用这个类，
 * **只在真正与世界/渲染打交道的地方**才转换（[StudioCameraRay] 与世界 → 局部、
 * [StudioGizmoRenderer] 局部 → 屏幕）。
 *
 * ## 坐标系
 * 这个类本身不带坐标系语义，只是数。用它的两处：
 * - **世界（相机相对）空间**：射线从相机出发；
 * - **面坐标系局部空间**：`+X` 面内向右、`+Y` 面内向上、`+Z` 沿宿主正面法线朝外 ——
 *   也就是 `anchor.offset` 所在的那个坐标系（与 [rain.gtetcore.gtet.studio.render.StudioFaceTransform] 一致）。
 *
 * @author rain fox
 */
data class StudioVec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(other: StudioVec3): StudioVec3 = StudioVec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: StudioVec3): StudioVec3 = StudioVec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Double): StudioVec3 = StudioVec3(x * scalar, y * scalar, z * scalar)

    fun dot(other: StudioVec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: StudioVec3): StudioVec3 = StudioVec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    val lengthSqr: Double get() = x * x + y * y + z * z

    val length: Double get() = sqrt(lengthSqr)

    /** 单位化；**零向量返回 null**（调用方必须显式处理，别让 NaN 悄悄流下去）。 */
    fun normalized(): StudioVec3? {
        val len = length
        return if (len <= 1e-9 || !len.isFinite()) null else StudioVec3(x / len, y / len, z / len)
    }

    /** 三分量都有限（防 NaN 污染渲染与拾取）。 */
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    /** 按轴取分量 —— 轴锁定/吸附都按轴操作，有了它就不用写 `when`。 */
    operator fun get(axis: StudioAxis): Double = when (axis) {
        StudioAxis.X -> x
        StudioAxis.Y -> y
        StudioAxis.Z -> z
    }

    /** 换掉一个分量（其余不变）。 */
    fun with(axis: StudioAxis, value: Double): StudioVec3 = when (axis) {
        StudioAxis.X -> StudioVec3(value, y, z)
        StudioAxis.Y -> StudioVec3(x, value, z)
        StudioAxis.Z -> StudioVec3(x, y, value)
    }

    /** 只保留 [axes] 里的分量（其余清零）—— 轴锁定 / 平面锁定就靠它。 */
    fun mask(axes: Collection<StudioAxis>): StudioVec3 = StudioVec3(
        if (StudioAxis.X in axes) x else 0.0,
        if (StudioAxis.Y in axes) y else 0.0,
        if (StudioAxis.Z in axes) z else 0.0,
    )

    override fun toString(): String = "(${fmt(x)}, ${fmt(y)}, ${fmt(z)})"

    companion object {

        @JvmField
        val ZERO = StudioVec3(0.0, 0.0, 0.0)

        /** 打印用：去掉没意义的小数尾巴（`1.0` → `1`，`0.25` → `0.25`）。 */
        @JvmStatic
        fun fmt(value: Double): String = StudioNumberFormat.trim(value)
    }
}