package rain.gtetcore.gtet.studio.kernel

/**
 * 网格里的一个顶点 —— 位置 / UV / 法线，**纯数据**。
 *
 * ## 为什么不用 JOML 的 Vector3f
 * 内核要能**脱离游戏单测**、以后也要能整包抽出去（设计文档 §3 的依赖纪律）。
 * 自带三个 Float 比拖一个数学库进来更省事，序列化/比较也直白。
 *
 * ## 单位与坐标系（与 M0 逐字一致，改动就等于 M0 回退）
 * - **1 OBJ 单位 = 1 格**（模型自己不做单位换算，缩放交给 JSON 的 `anchor.scale`）；
 * - Y 轴向上 —— 这里说的是**机器局部坐标系**，摆到世界上去是 `render/` 层 anchor 的事；
 * - UV 原点在**贴图左上角**，也就是 OBJ 的 `vt` 已经按 `flip_v` 约定翻好了
 *   （翻的动作在 `format/ObjFormat` 里做，内核只存最终值，不理解 OBJ）。
 *
 * @author rain fox
 */
data class StudioVertex(
    val x: Float,
    val y: Float,
    val z: Float,
    val u: Float,
    val v: Float,
    val nx: Float,
    val ny: Float,
    val nz: Float,
) {

    /** 三个分量都必须是有限数（NaN/Inf 一律视为坏数据，见 [StudioMesh.create] 的校验）。 */
    fun isFinite(): Boolean =
        x.isFinite() && y.isFinite() && z.isFinite() &&
            u.isFinite() && v.isFinite() &&
            nx.isFinite() && ny.isFinite() && nz.isFinite()

    override fun toString(): String =
        "v($x, $y, $z) uv($u, $v) n($nx, $ny, $nz)"
}