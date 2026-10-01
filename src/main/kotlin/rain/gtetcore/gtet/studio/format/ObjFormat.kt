package rain.gtetcore.gtet.studio.format

import rain.gtetcore.gtet.studio.kernel.StudioMeshBuilder
import rain.gtetcore.gtet.studio.kernel.StudioVertex
import kotlin.math.sqrt

/**
 * **自己写的 OBJ(+MTL) 解析器** —— M1a 把模型来源从 Forge 的 `forge:obj` 上摘下来的关键。
 *
 * ## 为什么不再用 Forge 的 `ObjModel`
 * 1. **缓存没有失效口**：`ObjLoader` 的 `modelCache` / `materialCache` 是私有状态，只有资源重载
 *    （F3+T）会清；M0 已经绕过模型缓存（自己调 `ObjModel.parse`），但 **MTL 仍然被卡在缓存里**，
 *    所以"改完 MTL 要按 F3+T"这条限制一直挂在 M0 上。自己解析之后，
 *    `/gtetstudio reload` 连 MTL 一起重读。
 * 2. **拿不到顶点**：`ObjModel.bakeRenderable()` 出来的是 `CompositeRenderable`（BakedQuad），
 *    以后做 CAD 图元（M3）与网格编辑（M6）都没有可操作的对象。这里直接产出 `kernel.StudioMesh`。
 * 3. **文件可以来自磁盘**：Forge 的加载器只认资源路径，自己解析之后 OBJ 能从 `config/` 下读。
 *
 * ## 支持的语法
 * | 指令 | 处理 |
 * |---|---|
 * | `v x y z [w]` | 位置（w 忽略，OBJ 里基本恒为 1） |
 * | `vt u [v] [w]` | UV（v 省略当 0；按 [StudioImportContext.flipV] 决定要不要翻 V 轴） |
 * | `vn x y z` | 法线（读进来就归一化，导出器们经常给非单位向量） |
 * | `f …` | 四种写法全支持：`v`、`v/vt`、`v//vn`、`v/vt/vn`；**支持负数索引**（`-1` = 最后一个） |
 * | `g` / `o` | 组名（就是 studio 里的"部件"）。同一个名字可以出现多段，见 `kernel.StudioMesh.Group` |
 * | `usemtl` | 当前材质名 |
 * | `mtllib` | MTL 文件名（可多个，按顺序合并） |
 * | `s` / `l` / `p` / `vp` / … | 认识但用不上，静默跳过（Blender 每次导出都写 `s off`，报出来是噪音） |
 *
 * ## 容错原则
 * 坏行**跳过 + 计数**，最后一条汇总（[StudioNoteSink]），不整体抛异常；
 * 只有"连一个三角形都没解出来"才算致命（[StudioFormatException]）。
 *
 * ## 法线
 * 没有 `vn` 时按**面法线**补（平面着色）。这与 M0 的观感一致：时钟那份模型全是平面四边形，
 * 几何法线与"顶点法线平均"本来就相同。同一三角形里不会混用几何法线与 OBJ 法线
 * （混用会出硬边），只要有一个角点缺 `vn`，整个三角形一起降级成面法线。
 *
 * @author rain fox
 */
object ObjFormat : StudioFormat {

    override val id: String = "obj"

    override val extensions: List<String> = listOf("obj")

    private val WHITESPACE = Regex("\\s+")

    /** 认识但用不上的 OBJ 指令（静默跳过）。 */
    private val IGNORED_DIRECTIVES = setOf(
        "s", "l", "p", "vp", "mg", "bevel", "c_interp", "d_interp", "lod", "usemap",
        "shadow_obj", "trace_obj", "ctech", "stech", "end", "call",
        "curv", "curv2", "surf", "parm", "trim", "hole", "scrv", "sp", "con",
        "bmat", "step", "cstype", "deg", "bzp", "cdc", "res",
    )

    /** 认识但用不上的 MTL 键（静默跳过）。 */
    private val IGNORED_MTL_KEYS = setOf(
        "Ka", "Ks", "Ke", "Ns", "Ni", "illum", "Tf", "sharpness", "aniso", "anisor",
        "map_Ka", "map_Ks", "map_Ns", "map_bump", "bump", "disp", "decal",
        "refl", "map_refl", "map_d", "norm", "Pr", "Pm", "Ps", "Pc", "Pcr",
    )

    private val ZERO_UV = floatArrayOf(0f, 0f)

    /** 一次解析里的各种计数（**局部变量**，绝不做成 object 的字段：那是跨线程共享的坑）。 */
    private class Stats {
        var faceCount = 0
        var skippedFaces = 0

        /** 扇形拆分出来的三角形（= 最终三角形 + 被丢弃的退化三角形）。 */
        var fanTriangles = 0
        var degenerateTriangles = 0
        var flatNormalTriangles = 0

        /** 平面法线的去重槽位序号：每个"用面法线"的三角形取一个不重复的负数。 */
        var flatSerial = 0
    }

    override fun parse(bytes: ByteArray, ctx: StudioImportContext): StudioImportResult {
        val sink = StudioNoteSink()
        val stats = Stats()
        val positions = ArrayList<FloatArray>()
        val uvs = ArrayList<FloatArray>()
        val normals = ArrayList<FloatArray>()
        val builder = StudioMeshBuilder(ctx.where)
        val cache = HashMap<CornerKey, Int>()
        val mtlLibs = ArrayList<String>()
        val usedMaterials = LinkedHashSet<String>()

        var lineNo = 0
        for (raw in bytes.toString(Charsets.UTF_8).lineSequence()) {
            lineNo++
            val line = raw.trim()
            // 只认"整行注释"。**不做行内 `#` 截断**：MTL 里 `map_Kd #别名` 是合法写法，
            // 一刀切会把贴图别名吃掉（M0 的 clock.json 正好演示了它）。
            if (line.isEmpty() || line.startsWith("#")) continue

            val space = line.indexOfAny(charArrayOf(' ', '\t'))
            val head = if (space < 0) line else line.substring(0, space)
            val body = if (space < 0) "" else line.substring(space + 1).trim()

            when (head) {
                "v" -> readFloats(body, 3, 3, lineNo, line, sink)?.let { positions += it }
                "vt" -> readFloats(body, 1, 2, lineNo, line, sink)?.let { uvs += it }
                "vn" -> readFloats(body, 3, 3, lineNo, line, sink)?.let { normals += normalize(it) }
                "g", "o" -> builder.selectGroup(body)
                "usemtl" -> {
                    if (body.isEmpty()) {
                        sink.warn(lineNo, line, "usemtl 后面没写材质名，忽略")
                    } else {
                        usedMaterials += body
                        builder.useMaterial(body)
                    }
                }
                "mtllib" -> {
                    // 一行可以列多个文件：`mtllib a.mtl b.mtl`
                    val names = body.split(WHITESPACE).filter { it.isNotEmpty() }
                    if (names.isEmpty()) sink.warn(lineNo, line, "mtllib 后面没写文件名，忽略") else mtlLibs += names
                }
                "f" -> face(
                    line, body, lineNo, positions, uvs, normals, builder, cache, ctx, sink, stats,
                )
                in IGNORED_DIRECTIVES -> Unit
                else -> sink.warn(lineNo, line, "不认识的 OBJ 指令 \"$head\"，整行跳过")
            }
        }

        if (positions.isEmpty()) {
            throw StudioFormatException("${ctx.where}: 一个 `v` 顶点都没有，这不像是 OBJ")
        }
        if (builder.triangleCount == 0) {
            throw StudioFormatException(
                "${ctx.where}: 一个三角形都没解出来（读到 ${stats.faceCount} 个面，" +
                    "跳过 ${stats.skippedFaces} 个；坏行 ${sink.errorCount} 处 / 警告 ${sink.warnCount} 处）"
            )
        }

        if (stats.flatNormalTriangles > 0) {
            sink.info(
                0, "",
                "这份 OBJ 没有（或部分没有）`vn`：${stats.flatNormalTriangles} 个三角形按**面法线**着色。" +
                    "平面四边形上与 Blender 的结果一致；曲面模型想要平滑着色，得在导出时勾上法线"
            )
        }
        if (stats.degenerateTriangles > 0) {
            sink.info(
                0, "",
                "扇形拆分出 ${stats.fanTriangles} 个三角形，其中 ${stats.degenerateTriangles} 个**面积为 0**" +
                    "（面里出现了重复/共线顶点，例如 `f a b b a` 这种写法）已丢弃 —— " +
                    "它们本来就画不出像素，丢掉只是省点显存"
            )
        }

        // ── 材质：mtllib 指向的 MTL 自己读、自己解（不再走 ObjLoader 的缓存） ──
        //    mtl_override 写在 JSON 里时**替代** OBJ 自己的 mtllib（与 M0/Forge 的语义一致）
        val libs = if (ctx.mtlOverride != null) listOf(ctx.mtlOverride) else mtlLibs
        val materials = LinkedHashMap<String, StudioImportedMaterial>()
        for (lib in libs) {
            val mtlBytes = ctx.openSibling(lib)
            if (mtlBytes == null) {
                sink.warn(0, lib, "mtllib 指向的 MTL 找不到（按本 OBJ 所在目录的相对路径找）")
                continue
            }
            parseMtl(mtlBytes, ctx, sink, materials)
        }
        for (name in usedMaterials) {
            if (name !in materials) {
                sink.warn(
                    0, name,
                    "OBJ 里用了材质 \"$name\"，但 MTL 里没有它 —— 这个部件会画成缺省贴图（紫黑）"
                )
                materials[name] = StudioImportedMaterial(name, null, declared = false)
            }
        }

        sink.info(
            0, "",
            "解析完成：读取顶点 ${positions.size} 个（去重后 ${builder.vertexCount}），" +
                "面 ${stats.faceCount} 个 → 扇形拆分 ${stats.fanTriangles} 个三角形" +
                "（丢弃退化 ${stats.degenerateTriangles} 个）→ 最终 ${builder.triangleCount} 个，" +
                "材质 ${materials.size} 个"
        )

        return StudioImportResult(builder.build(), materials.values.toList(), libs, sink)
    }

    // ────────────────────────── 面 ──────────────────────────

    /**
     * 解一条 `f`。
     *
     * - 少于 3 个顶点 → 跳过并计数；
     * - 任一角点解析失败 → **整面**跳过并计数（半个面拼不出来，硬拼只会得到错几何）；
     * - 多边形按扇形拆：`f a b c d…` → `(a,b,c) (a,c,d) …`（OBJ 的 `f` 可以是任意多边形，
     *   而 Blender 默认导出的就是四边形 —— 这条直接决定三角形数是"面数"还是"面数×2"）；
     * - 面积为 0 的三角形丢掉（它会让法线变成 NaN，而 NaN 顶点会被 `StudioMesh` 直接判死）。
     */
    private fun face(
        line: String,
        body: String,
        lineNo: Int,
        positions: List<FloatArray>,
        uvs: List<FloatArray>,
        normals: List<FloatArray>,
        builder: StudioMeshBuilder,
        cache: HashMap<CornerKey, Int>,
        ctx: StudioImportContext,
        sink: StudioNoteSink,
        stats: Stats,
    ) {
        stats.faceCount++
        val tokens = body.split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size < 3) {
            stats.skippedFaces++
            sink.warn(lineNo, line, "面至少要有 3 个顶点，这行只有 ${tokens.size} 个，跳过")
            return
        }

        val corners = ArrayList<Corner>(tokens.size)
        for (token in tokens) {
            val corner = corner(token, positions.size, uvs.size, normals.size)
            if (corner == null) {
                stats.skippedFaces++
                sink.warn(
                    lineNo, line,
                    "顶点引用 \"$token\" 解析不出来（越界索引或不是数字；" +
                        "此刻 v=${positions.size} vt=${uvs.size} vn=${normals.size}），整个面跳过"
                )
                return
            }
            corners += corner
        }

        var added = 0
        for (i in 1 until corners.size - 1) {
            val a = corners[0]
            val b = corners[i]
            val c = corners[i + 1]
            stats.fanTriangles++
            val hasNormals = a.normal >= 0 && b.normal >= 0 && c.normal >= 0
            var nx = 0f
            var ny = 0f
            var nz = 0f
            var slot = 0
            if (!hasNormals) {
                val pa = positions[a.pos]
                val pb = positions[b.pos]
                val pc = positions[c.pos]
                val ux = pb[0] - pa[0]
                val uy = pb[1] - pa[1]
                val uz = pb[2] - pa[2]
                val vx = pc[0] - pa[0]
                val vy = pc[1] - pa[1]
                val vz = pc[2] - pa[2]
                nx = uy * vz - uz * vy
                ny = uz * vx - ux * vz
                nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len < 1e-12f) {
                    stats.degenerateTriangles++
                    continue
                }
                nx /= len
                ny /= len
                nz /= len
                stats.flatNormalTriangles++
                // 每个"面法线三角形"一个独立的去重槽位，避免相邻三角形共用顶点把硬边磨平
                slot = -(++stats.flatSerial) - 1
            }

            val ia = vertex(a, positions, uvs, normals, nx, ny, nz, slot, hasNormals, builder, cache, ctx)
            val ib = vertex(b, positions, uvs, normals, nx, ny, nz, slot, hasNormals, builder, cache, ctx)
            val ic = vertex(c, positions, uvs, normals, nx, ny, nz, slot, hasNormals, builder, cache, ctx)
            builder.addTriangle(ia, ib, ic)
            added++
        }
    }

    /**
     * 把 `v`、`v/vt`、`v//vn`、`v/vt/vn` 拆开并解析成下标。
     *
     * 负数索引按 OBJ 规则处理：`-1` 是"当前最后一个"，所以基数取**此刻已经读到的** v/vt/vn 个数
     * （OBJ 允许在面里引用"前面出现过的"顶点，正是为了这种相对写法）。
     */
    private fun corner(token: String, posCount: Int, uvCount: Int, normalCount: Int): Corner? {
        val slash1 = token.indexOf('/')
        val vPart: String
        var vtPart: String? = null
        var vnPart: String? = null
        if (slash1 < 0) {
            vPart = token
        } else {
            vPart = token.substring(0, slash1)
            val rest = token.substring(slash1 + 1)
            val slash2 = rest.indexOf('/')
            if (slash2 < 0) {
                vtPart = rest
            } else {
                vtPart = rest.substring(0, slash2)
                vnPart = rest.substring(slash2 + 1)
            }
        }

        val pos = resolve(vPart, posCount) ?: return null
        val uv = if (vtPart.isNullOrEmpty()) -1 else (resolve(vtPart, uvCount) ?: return null)
        val normal = if (vnPart.isNullOrEmpty()) -1 else (resolve(vnPart, normalCount) ?: return null)
        return Corner(pos, uv, normal)
    }

    /** 1 基 → 0 基；负数从末尾倒数。越界返回 null。 */
    private fun resolve(text: String, count: Int): Int? {
        val raw = text.trim().toIntOrNull() ?: return null
        val index = if (raw < 0) count + raw else raw - 1
        return if (index in 0 until count) index else null
    }

    /** 取（或新建）一个顶点：同 `(v, vt, vn)` 组合复用，省顶点也让平滑法线成立。 */
    private fun vertex(
        corner: Corner,
        positions: List<FloatArray>,
        uvs: List<FloatArray>,
        normals: List<FloatArray>,
        flatX: Float,
        flatY: Float,
        flatZ: Float,
        slot: Int,
        hasNormals: Boolean,
        builder: StudioMeshBuilder,
        cache: HashMap<CornerKey, Int>,
        ctx: StudioImportContext,
    ): Int {
        val key = CornerKey(corner.pos, corner.uv, if (hasNormals) corner.normal else slot)
        cache[key]?.let { return it }

        val p = positions[corner.pos]
        val uv = if (corner.uv >= 0) uvs[corner.uv] else ZERO_UV
        val n = if (hasNormals) normals[corner.normal] else null
        val u = uv[0]
        val v = if (ctx.flipV) 1f - uv[1] else uv[1]
        val index = builder.addVertex(
            StudioVertex(
                p[0], p[1], p[2],
                u, v,
                n?.get(0) ?: flatX, n?.get(1) ?: flatY, n?.get(2) ?: flatZ,
            )
        )
        cache[key] = index
        return index
    }

    // ────────────────────────── MTL ──────────────────────────

    /**
     * 解一份 MTL，把材质并进 [out]（同名后者覆盖前者，并提示一句）。
     *
     * 认的键：`newmtl` / `Kd` / `d` / `Tr` / `map_Kd`；其余标准键静默跳过，不认识的键报警告。
     * `map_Kd` 取**最后一个 token** —— 与 Forge 的 `ObjMaterialLibrary` 一致，
     * 这样 `map_Kd -o 1 1 1 tex.png` 这类带选项的写法也能对。
     */
    fun parseMtl(
        bytes: ByteArray,
        ctx: StudioImportContext,
        sink: StudioNoteSink,
        out: MutableMap<String, StudioImportedMaterial>,
    ) {
        var lineNo = 0
        var current: String? = null
        var kd: FloatArray? = null
        var opacity = 1f
        var texture: String? = null
        var definedAt = 0

        fun flush() {
            val name = current ?: return
            val material = StudioImportedMaterial(
                name = name,
                texture = texture,
                diffuseR = kd?.get(0) ?: 1f,
                diffuseG = kd?.get(1) ?: 1f,
                diffuseB = kd?.get(2) ?: 1f,
                opacity = opacity,
            )
            if (out.put(name, material) != null) {
                sink.warn(definedAt, "newmtl $name", "材质 \"$name\" 被重复定义，取后一份")
            }
        }

        for (raw in bytes.toString(Charsets.UTF_8).lineSequence()) {
            lineNo++
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val space = line.indexOfAny(charArrayOf(' ', '\t'))
            val head = if (space < 0) line else line.substring(0, space)
            val body = if (space < 0) "" else line.substring(space + 1).trim()

            when (head) {
                "newmtl" -> {
                    flush()
                    current = body.ifEmpty { null }
                    definedAt = lineNo
                    kd = null
                    opacity = 1f
                    texture = null
                    if (current == null) sink.warn(lineNo, line, "newmtl 后面没写材质名，忽略（它下面的属性无处可去）")
                }
                "Kd" -> {
                    val rgb = readFloats(body, 3, 3, lineNo, line, sink)
                    if (rgb == null) sink.warn(lineNo, line, "Kd 需要 3 个数字，忽略这行") else kd = rgb
                }
                "d" -> {
                    // 允许 `d -halo 0.5`：取最后一个 token
                    val value = body.split(WHITESPACE).lastOrNull { it.isNotEmpty() }?.toFloatOrNull()
                    if (value == null) sink.warn(lineNo, line, "d（透明度）不是数字，忽略这行") else opacity = value
                }
                "Tr" -> {
                    // 老导出器的反向透明度：Tr = 1 - d
                    val value = body.split(WHITESPACE).lastOrNull { it.isNotEmpty() }?.toFloatOrNull()
                    if (value == null) sink.warn(lineNo, line, "Tr 不是数字，忽略这行") else opacity = 1f - value
                }
                "map_Kd" -> {
                    if (current == null) {
                        sink.warn(lineNo, line, "map_Kd 出现在任何 newmtl 之前，忽略（它不知道该属于哪个材质）")
                    } else {
                        val value = body.split(WHITESPACE).lastOrNull { it.isNotEmpty() }
                        if (value == null) {
                            sink.warn(lineNo, line, "map_Kd 后面没写贴图，忽略这行")
                        } else {
                            val expanded = ctx.expandAlias(value)
                            if (expanded == null) {
                                sink.warn(
                                    lineNo, line,
                                    "map_Kd 用了别名 \"$value\"，但 JSON 的 source.textures 里没有它" +
                                        "（现有别名：${ctx.textureAliases.keys}）"
                                )
                            } else {
                                texture = expanded
                            }
                        }
                    }
                }
                in IGNORED_MTL_KEYS -> Unit
                else -> sink.warn(lineNo, line, "不认识的 MTL 键 \"$head\"，整行跳过")
            }
        }
        flush()
    }

    // ────────────────────────── 小工具 ──────────────────────────

    /**
     * 读数字。
     *
     * @param min  至少要几个（少于此数 → 这行作废）
     * @param want 结果数组的长度（多出来的分量丢掉：`v x y z w` 的 w、`vt u v w` 的 w；
     *             `vt u` 这种只给 1 个分量的写法会把剩下的补 0）
     */
    private fun readFloats(
        body: String,
        min: Int,
        want: Int,
        lineNo: Int,
        line: String,
        sink: StudioNoteSink,
    ): FloatArray? {
        val tokens = body.split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size < min) {
            sink.warn(lineNo, line, "这行需要至少 $min 个数字，只有 ${tokens.size} 个，跳过")
            return null
        }
        val out = FloatArray(want)
        for (i in 0 until minOf(tokens.size, want)) {
            val value = tokens[i].toFloatOrNull()
            if (value == null || !value.isFinite()) {
                sink.warn(lineNo, line, "第 ${i + 1} 个数字 \"${tokens[i]}\" 不是合法浮点数，跳过这行")
                return null
            }
            out[i] = value
        }
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-12f) return floatArrayOf(0f, 1f, 0f)
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    /** 面里的一个角点：三个下标，-1 表示"没给"。 */
    private data class Corner(val pos: Int, val uv: Int, val normal: Int)

    /**
     * 顶点去重用的键。
     *
     * [normalSlot] 在有 `vn` 时就是 `vn` 下标；没有 `vn` 时是一个**每个三角形都不同**的负数，
     * 这样平面着色的硬边不会被去重磨平成平滑法线。
     */
    private data class CornerKey(val pos: Int, val uv: Int, val normalSlot: Int)
}