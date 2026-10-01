package rain.gtetcore.gtet.studio.interaction

/**
 * 一根轴 —— **面坐标系**里的 X / Y / Z。
 *
 * 注意语义：这不是"世界坐标轴"，而是 `anchor.offset` 所在的那个坐标系
 * （`+X` 面内向右、`+Y` 面内向上、`+Z` 沿宿主正面法线朝外）。
 * 拖 X 轴改的就是 `offset[0]`，与机器朝南朝北无关。
 *
 * 之所以只枚举三根而不带"世界方向"：gizmo 的局部→世界变换**完全复用渲染器那一套**
 * （`moveToFace` + `applyFaceFrame`，见 [rain.gtetcore.gtet.studio.render.StudioFaceTransform]），
 * 于是局部空间里三根轴恒等于单位向量 `e_x / e_y / e_z` —— 不自己推一遍朝向，
 * 就不会出现"gizmo 画的位置和模型实际位置差一个旋转"这种最难查的错。
 *
 * @author rain fox
 */
enum class StudioAxis(
    /** 显示名（聊天栏/动作栏反馈用）。 */
    val label: String,
) {
    X("X"),
    Y("Y"),
    Z("Z"),
    ;

    /** 局部空间的单位向量。 */
    val unit: StudioVec3
        get() = when (this) {
            X -> StudioVec3(1.0, 0.0, 0.0)
            Y -> StudioVec3(0.0, 1.0, 0.0)
            Z -> StudioVec3(0.0, 0.0, 1.0)
        }

    companion object {

        /** 与 [axis] 垂直的两根轴（平面手柄用：法线确定的那块平面内的两根轴）。 */
        @JvmStatic
        fun others(axis: StudioAxis): List<StudioAxis> = entries.filter { it != axis }
    }
}