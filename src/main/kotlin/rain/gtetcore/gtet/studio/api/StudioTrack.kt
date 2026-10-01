package rain.gtetcore.gtet.studio.api

import net.minecraft.core.Direction

/**
 * 一条动画轨道 —— **M0 只实现「绕单轴匀速旋转」这一种**。
 *
 * [speedDegPerSecond] 是**度 / 秒**：
 * `6.0` ⇒ 60 秒一圈（时针对得上真实感的比例），`72.0` ⇒ 5 秒一圈（肉眼一眼能看出在转）。
 *
 * ## 符号约定（很重要，容易看反）
 * 旋转用**右手定则**：绕 +Z 轴正角就是从 +Z 方向看过去的**逆时针**。
 * 我们的模型约定「+Z = 表盘正面（朝观众）」，所以**正的 speed 看上去是逆时针**；
 * 想要指针从正面看顺时针走，把 speed 写成负数即可。
 *
 * ## 关键帧（M4 预留）
 * JSON 里每条轨道还会有一个 `keys` 数组（`[{ "t": 0, "v": 0 }, ...]`）。
 * M0 **不解析它**：出现 `keys` 时只在日志里提示「M0 忽略关键帧，按 speed 匀速转」，
 * 这样以后接 M4 时老文件不会突然报错。
 *
 * @author rain fox
 */
data class StudioTrack(
    /** 部件名（= OBJ 里的 `g`/`o` 组名）。 */
    val part: String,
    /** 绕哪根**模型局部**轴转。 */
    val axis: Direction.Axis,
    /** 角速度，单位：度 / 秒。 */
    val speedDegPerSecond: Float,
)