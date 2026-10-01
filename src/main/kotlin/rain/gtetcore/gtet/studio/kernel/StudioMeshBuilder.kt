package rain.gtetcore.gtet.studio.kernel

/**
 * [StudioMesh] 的装配器 —— 解析器（`format/ObjFormat`）与以后的图元/布尔（M3/M7）都走它。
 *
 * 它只管"攒"，**校验全留给 [StudioMesh.create]**：这样解析过程中遇到坏行可以继续往下走
 * （坏行由 format 层计数报汇总），最后由一次 `create` 把"这份几何到底成不成立"一次性判掉。
 *
 * @param where 出错时写进消息里的「这是哪份数据」
 * @author rain fox
 */
class StudioMeshBuilder(private val where: String) {

    private val vertices = ArrayList<StudioVertex>()
    private var indices = IntArray(1024)
    private var indexCount = 0

    private val closedGroups = ArrayList<StudioMesh.Group>()

    /** 当前开着的组：名字 + 起始下标 + 材质。为 null 表示还没遇到过 `g`/`o`。 */
    private var openName: String? = null
    private var openFirst = 0
    private var openMaterial: String? = null

    /** 当前材质（`usemtl` 设的），新开组时继承它。 */
    private var currentMaterial: String? = null

    val vertexCount: Int get() = vertices.size
    val triangleCount: Int get() = indexCount / 3

    /** 加一个顶点，返回它在 [StudioMesh.vertices] 里的下标。 */
    fun addVertex(vertex: StudioVertex): Int {
        vertices += vertex
        return vertices.size - 1
    }

    /**
     * 切到组 [name]（OBJ 的 `g`/`o`）。
     *
     * 上一段如果没有三角形就**丢弃**（`g a` 紧跟 `g b`、或空组，都不该在结果里留下空段）；
     * 同名组被再次选中时会**另开一段**，见 [StudioMesh.Group]。
     */
    fun selectGroup(name: String) {
        closeOpenGroup()
        openName = name.ifBlank { StudioMesh.DEFAULT_GROUP }
        openFirst = indexCount
        openMaterial = currentMaterial
    }

    /**
     * 设置当前材质（OBJ 的 `usemtl`）。
     *
     * 顺手把**已经开着的组**的材质也改掉 —— 因为 OBJ 里正常的写法是
     * ```
     * g body
     * usemtl frame
     * ```
     * 即先开组后指材质，只在开组那一刻记材质会全是 null。
     */
    fun useMaterial(name: String?) {
        currentMaterial = name
        if (openName != null) openMaterial = name
    }

    fun addTriangle(a: Int, b: Int, c: Int) {
        ensureOpenGroup()
        if (indexCount + 3 > indices.size) {
            indices = indices.copyOf(maxOf(indices.size * 2, indexCount + 3))
        }
        indices[indexCount++] = a
        indices[indexCount++] = b
        indices[indexCount++] = c
    }

    /**
     * 加一个四边形 —— 按标准扇形拆成两个三角形 `(a,b,c)` + `(a,c,d)`。
     *
     * OBJ 的 `f` 是**多边形**（Blender 导出的四方片很常见），所以拆分的顺序必须和
     * 原来 Forge 的做法一致，否则绕序会翻、背面剔除的模型会整片消失。
     */
    fun addQuad(a: Int, b: Int, c: Int, d: Int) {
        addTriangle(a, b, c)
        addTriangle(a, c, d)
    }

    /** 收尾 + 校验，产出不可变的 [StudioMesh]。 */
    fun build(): StudioMesh {
        closeOpenGroup()
        return StudioMesh.create(vertices, indices.copyOf(indexCount), closedGroups, where)
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun ensureOpenGroup() {
        if (openName == null) selectGroup(StudioMesh.DEFAULT_GROUP)
    }

    private fun closeOpenGroup() {
        val name = openName ?: return
        val count = indexCount - openFirst
        openName = null
        if (count <= 0) return
        closedGroups += StudioMesh.Group(name, openFirst, count, openMaterial)
    }
}