package rain.gtetcore.gtet.studio.kernel
import rain.gtetcore.gtet.studio.kernel.StudioMesh.Companion.DEFAULT_GROUP

/**
 * 工作室的**自有网格内核** —— 设计文档 §3 里「几何内核」的第一块砖。
 *
 * ## 它解决的是 M0 留下的地基问题
 * M0 的几何是 Forge 的 `ObjModel` / `CompositeRenderable`：能画，但**我们拿不到顶点**
 * —— 以后做 CAD 图元（M3）、网格编辑（M6）、布尔（M7）时没有可操作的对象。
 * 从这里开始，模型在工作室内部就是"一份顶点数组 + 索引 + 分组"，谁来谁走都由我们自己定。
 *
 * ## 结构
 * - [vertices] 全局顶点表（OBJ 的 `v/vt/vn` 合并去重之后的），**所有组共用**；
 * - [indices] 三角形索引（`indices[3k..3k+2]` 是第 k 个三角形）；
 * - [groups] 按 **OBJ 的 `g`/`o` 组名**切出来的连续区间 —— 渲染时"哪个部件在动"就是按它分的
 *   （M0 的 `CompositeRenderable.Transforms` 按组名叠矩阵，换成自有网格后由我们自己按组名分 VBO）；
 * - [bounds] 包围盒（只统计被引用到的顶点）。
 *
 * ## 纪律
 * 这里**一行都不许 import Minecraft / Forge / GTM**，也不许出现任何"渲染"概念（GL、RenderType、VBO 都不行）。
 * 它必须能脱离游戏单独跑 —— 证据见 `format/StudioFormatSelfCheck`（一个不需要启动游戏的解析自检）。
 *
 * @author rain fox
 */
class StudioMesh private constructor(
    val vertices: List<StudioVertex>,
    val indices: IntArray,
    val groups: List<Group>,
    val bounds: StudioBounds,
) {

    /**
     * 一个子网格（= OBJ 的一个 `g`/`o` 组）。
     *
     * 同一个名字**可能出现多段**：OBJ 允许 `g body … g other … g body` 这样重新选中同一个组名
     * （Blender 按材质分组导出时就很常见）。所以对外用 [groupsNamed] 拿全集，
     * 渲染时按段分别累加到同一个部件上，结果等价。
     */
    class Group(
        /** 组名 = OBJ 的 `g`/`o` 名字；没有分组的三角形落在 [DEFAULT_GROUP]。 */
        val name: String,
        /** 在 [indices] 里的起始下标（一定是 3 的倍数）。 */
        val firstIndex: Int,
        /** 这一段有多少个索引（一定是 3 的倍数）。 */
        val indexCount: Int,
        /** 这一段三角形用的材质名（MTL 的 `newmtl` 名字），没有就是 null。 */
        val material: String?,
    ) {
        val triangleCount: Int get() = indexCount / 3

        override fun toString(): String =
            "$name[${firstIndex}..${firstIndex + indexCount}) ×${triangleCount} 材质=$material"
    }

    val triangleCount: Int get() = indices.size / 3

    /** 组名全集（去重，保持出现顺序）—— 就是"这份模型有哪些部件"。 */
    val groupNames: List<String> = groups.map { it.name }.distinct()

    /** 名字匹配的第一段（多数情况下一组只有一段）。 */
    fun group(name: String): Group? = groups.firstOrNull { it.name == name }

    /** 名字匹配的全部段（见 [Group] 的说明）。 */
    fun groupsNamed(name: String): List<Group> = groups.filter { it.name == name }

    override fun toString(): String =
        "StudioMesh(顶点 ${vertices.size}，三角形 $triangleCount，组 $groupNames，$bounds)"

    companion object {

        /** 没有 `g`/`o` 的三角形落在哪个组名上（OBJ 里这是合法的：整份文件可以只有一个默认组）。 */
        const val DEFAULT_GROUP = "default"

        /**
         * 建一份网格，**顺手做完所有校验**。
         *
         * 校验清单（每条都带"哪个下标、期望什么"，不做静默修补）：
         * - 顶点表非空，且每个顶点的 8 个分量都是有限数（NaN/Inf 会在 GL 里变成整片三角消失，很难查）；
         * - 索引数非空且是 3 的倍数；每个索引都在 `[0, vertices.size)` 内；
         * - 组区间**连续、无缝、无重叠**地铺满整个索引数组（每个三角形恰好属于一个组）。
         *
         * @param where 出错时写进消息里的「这是哪份数据」，例如 `clock.json`
         */
        @JvmStatic
        fun create(
            vertices: List<StudioVertex>,
            indices: IntArray,
            groups: List<Group>,
            where: String,
        ): StudioMesh {
            if (vertices.isEmpty()) {
                throw StudioMeshException("$where: 网格里一个顶点都没有")
            }
            for (i in vertices.indices) {
                val v = vertices[i]
                if (!v.isFinite()) {
                    throw StudioMeshException("$where: 第 $i 个顶点里有 NaN/Inf —— $v")
                }
            }
            if (indices.isEmpty()) {
                throw StudioMeshException("$where: 网格里一个三角形都没有（面全部被跳过了？看解析汇总）")
            }
            if (indices.size % 3 != 0) {
                throw StudioMeshException("$where: 索引数 ${indices.size} 不是 3 的倍数，凑不出三角形")
            }
            for (i in indices.indices) {
                val index = indices[i]
                if (index < 0 || index >= vertices.size) {
                    throw StudioMeshException(
                        "$where: 第 $i 个索引 = $index 越界（顶点表只有 ${vertices.size} 个）"
                    )
                }
            }

            var expected = 0
            for (g in groups) {
                if (g.name.isBlank()) {
                    throw StudioMeshException("$where: 有个组的名字是空的（OBJ 的 `g`/`o` 后面要有名字）")
                }
                if (g.indexCount <= 0 || g.indexCount % 3 != 0) {
                    throw StudioMeshException("$where: 组 \"${g.name}\" 的索引数 ${g.indexCount} 不是正的三的倍数")
                }
                if (g.firstIndex != expected) {
                    throw StudioMeshException(
                        "$where: 组 \"${g.name}\" 从 ${g.firstIndex} 开始，但上一段结束在 $expected" +
                            "（组区间必须连续铺满整个索引数组）"
                    )
                }
                expected += g.indexCount
            }
            if (expected != indices.size) {
                throw StudioMeshException(
                    "$where: 各组的索引加起来是 $expected，但索引数组有 ${indices.size} 个（有三角形没归组）"
                )
            }

            return StudioMesh(vertices, indices, groups, StudioBounds.ofReferenced(vertices, indices))
        }
    }
}