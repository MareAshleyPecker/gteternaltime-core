package rain.gtetcore.gtet.studio.api

import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.studio.kernel.StudioBounds
import rain.gtetcore.gtet.studio.kernel.StudioMesh

/**
 * 载入之后的模型 —— 一份 JSON + 一个模型文件**解析完之后**的全部静态产物，渲染器只认它。
 *
 * ## M1a 的变化（相对 M0）
 * M0 里这个类抱着一个 Forge 的 `CompositeRenderable`（`ObjModel.bakeRenderable()` 的产物），
 * 运行时靠 `Transforms.of(...)` 按 OBJ 组名叠矩阵。M1a 换成**工作室自己的网格**
 * （[rain.gtetcore.gtet.studio.kernel.StudioMesh]）：
 * - 顶点、索引、分组都在我们手里 ⇒ M3 的 CAD 图元、M6 的网格编辑才有地基；
 * - 不再受 `ObjLoader` 缓存影响 ⇒ 改 OBJ/MTL 只要 `/gtetstudio reload`，不用 F3+T；
 * - 渲染侧改成"每个部件一张 `VertexBuffer(Usage.STATIC)` + 每帧只算矩阵"。
 *
 * @param id            JSON 里的 `id`（例如 `gtet:test_clock`）
 * @param partNames     **全部**组名（OBJ 的 `g`/`o`）—— 就是"这份模型有哪些部件可以动"
 * @param materialNames MTL 里声明的材质名
 * @param bounds        模型局部空间的包围盒（OBJ 单位 = 格）
 * @param anchor        定位规则
 * @param mesh          自有网格（顶点 + 三角形索引 + 按组名的子网格）
 * @param viewDistance  视距（格），JSON 的 `viewDistance` 可覆盖（设计文档 §7.1）
 *
 * @author rain fox
 */
class StudioModel(
    val id: ResourceLocation,
    val partNames: Set<String>,
    val materialNames: Set<String>,
    val bounds: StudioBounds,
    val anchor: StudioAnchor,
    val mesh: StudioMesh,
    val viewDistance: Int,
) {

    /**
     * 叠上 [anchor] 的缩放/偏移之后，模型离锚点的**最大距离**（格）。
     *
     * 渲染包围盒按它放大 —— 这是「大模型不被区块剔除切掉半截」唯一有效的做法
     * （`getRenderBoundingBox` 只喂视锥剔除、不裁三角片；`shouldRenderOffScreen` 救不了这件事）。
     */
    val reachRadius: Double = bounds.maxRadius * anchor.scale + anchor.offsetLength
}