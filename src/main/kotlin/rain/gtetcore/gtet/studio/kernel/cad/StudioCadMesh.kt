package rain.gtetcore.gtet.studio.kernel.cad

import rain.gtetcore.gtet.studio.kernel.StudioMeshBuilder
import rain.gtetcore.gtet.studio.kernel.StudioVertex
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadMesh.Y_MERGE
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadMesh.extrude
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadMesh.snapY
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * **2D 轮廓 → 三维实体**：沿 Y 切成梯形（slab 分解）+ 沿 Z 挤出。
 *
 * ## 为什么不用耳切法 + 孔桥接
 * 那条路（Bridging + ear clipping）在**"一个盘子上打 12 个孔"**这种最典型的钟表零件上就翻车了：
 * 每个孔都要用一条**零宽度的缝**接进外环，缝的两侧完全重合，耳切法在这种弱简单多边形上
 * 会走进"找不到耳朵"的死角（实测：232 个点切到第 199 轮卡住，`StudioCadSelfCheck` 里那条
 * 12 孔阵列的用例就是它）。梯形分解**没有这个自由度**：
 *
 * ```
 * ① 把所有顶点的 y 取出来排序，就是层的边界（层内没有任何顶点，边界不会换序）
 * ② 每层取中线 ymid，把所有跨过它的轮廓边按 x 排序 → 用"奇偶规则"判每一段的里外 → 得到区间
 * ③ 每个区间 = 一个梯形（左右两条斜边 + 上下两条水平边）→ 扇形三角化
 * ④ 侧面 = 每条"层内边"挤出一个矩形；水平边按同一批切点切开
 * ```
 * 全程只有**排序、求交、采样**三种动作，没有"猜一个耳朵"这种启发式。
 *
 * ## 产出的实体是**闭合**的（自检里逐边钉住）
 * 顶盖与底盖用的都是这套梯形，边界边与侧面的上下边**逐点相同**；
 * 层与层之间的水平切缝按"该 y 上出现过的所有 x"统一切开，所以两侧切点完全一致
 * ⇒ **每条边恰好被两个面共用**（`StudioCadSelfCheck` 的「闭合体」断言就是数这个）。
 *
 * ## 与"球形/3D 布尔"的边界（不粉饰）
 * 这是 **2.5D**：只有"平板件"这条线。**球体、3D 布尔、旋转体都没有** —— 留给 M3b/M7。
 * 另外梯形分解的代价是**三角形偏多**（每个 y 层都要出一圈梯形）：一个 96 段的镂空表盘
 * 大约几千面，比耳切法多两三倍，但仍在 §7.1 的预算内（20 万）—— 这是**用面数换确定性**，刻意的。
 *
 * ## 坐标系与单位
 * 与 OBJ 约定逐字一致（见 [StudioVertex]）：**表盘在 XY 平面、+Z 是正面、1 单位 = 1 格**。
 * `centerZ = true`（默认）以 Z=0 为中心，否则从 Z=0 沿 +Z 长出。
 *
 * ## UV
 * 面板件没有"天生的" UV，这里给一套**确定的平面映射**：盖子按轮廓包围盒映到 [0,1]²（V 轴翻转，
 * 与"UV 原点在贴图左上角"一致）；侧面按该环的弧长归一到 [0,1]，V 从顶到底。
 *
 * @author rain fox
 */
object StudioCadMesh {

    private const val EPS = 1e-12

    /**
     * 把 y 相差不到这个值的顶点并到一起（见 [snapY]）。
     *
     * 取 1e-9：比"格"这个量级小 9 个数量级，只吃掉浮点噪声，不会动真实尺寸。
     */
    private const val Y_MERGE = 1e-9

    private val EMPTY_PROFILE = Profile(emptyList())

    /**
     * 把一个轮廓挤出成实体，写进 [builder] 的 [group] 组。
     *
     * @param thickness 沿 +Z 的厚度（格），必须 > 0
     * @param centerZ   `true` = 以 Z=0 为中心；`false` = 从 Z=0 沿 +Z 长出去
     * @param where     出错时写进消息里的「这是哪个特征」
     */
    @JvmStatic
    fun extrude(
        builder: StudioMeshBuilder,
        group: String,
        material: String?,
        profile: Profile,
        thickness: Double,
        centerZ: Boolean,
        where: String,
    ) {
        if (!thickness.isFinite() || thickness <= 0.0) {
            throw StudioCadException("$where: thickness=$thickness 必须为正数（沿 +Z 的厚度，单位：格）")
        }

        // ① 先把轮廓"洗"一遍：用排列把可能自交 / 朝向乱的输入变成**互不相交、外逆内顺**的闭环。
        //    这一步让后面所有几何都只需要面对一种形状（array 的副本互相重叠时尤其重要）。
        val clean = try {
            StudioCadBoolean.apply(StudioCadBoolean.Op.UNION, profile, EMPTY_PROFILE, where).normalized()
        } catch (e: StudioCadException) {
            throw StudioCadException("$where: 轮廓预处理失败（${e.message}）")
        }
        if (clean.isEmpty || abs(clean.signedAreaSum()) <= EPS) {
            throw StudioCadException(
                "$where: 要挤出的轮廓是空的（面积 0）—— 布尔是不是把整块都挖掉了？" +
                    "检查一下 subtract 的 a / b 是不是写反了"
            )
        }

        // ①′ **把 y 极其接近的顶点并到一起**（阈值 [Y_MERGE]，远小于任何真实尺寸）。
        //     为什么非做不可：圆上 `sin(kπ)` 这类点算出来是 1.1e-16 / 5.0e-16 这种"几乎但不等于 0"的值，
        //     它们会在 y 轴上切出一堆 **1e-16 厚的层**；层太薄就没法在里面求出区间，盖子会缺一条缝
        //     （实测：单个中心孔的圆盘会因此多出 16 条"只被一个面用到"的边）。
        val plate = snapY(clean)

        val half = thickness * 0.5
        val zBot = if (centerZ) -half else 0.0
        val zTop = if (centerZ) half else thickness

        builder.selectGroup(group)
        builder.useMaterial(material)
        val em = Emitter(builder)
        val box = plate.bounds()
        val spanX = max(box.sizeX, 1e-9)
        val spanY = max(box.sizeY, 1e-9)

        // ② 层的边界 = 所有轮廓顶点的 y（去重）
        val ys = sortedDistinctY(plate)
        if (ys.size < 2) {
            throw StudioCadException("$where: 轮廓在 Y 方向上没有展开（所有顶点同一个 y），挤不出体")
        }

        // ③ 切边：非水平的按层切开；水平的单独留着（它们本身就是一层之间的"台阶"）
        val horizontal = ArrayList<SlabEdge>()
        val bySlab: Array<MutableList<SlabEdge>> = Array(ys.size - 1) { ArrayList() }
        splitContours(plate, ys, horizontal, bySlab)

        // ④ 每层解区间 → 梯形；同时记录"每个 y 上出现过的 x"（用来切水平边，保证逐点对齐）
        val traps = ArrayList<Trap>()
        val xAtY = HashMap<Double, TreeX>()
        for (k in 0 until ys.size - 1) {
            collectSlabIntervals(plate, box, ys[k], ys[k + 1], bySlab[k], traps, xAtY)
        }
        if (traps.isEmpty()) {
            throw StudioCadException("$where: 轮廓在 Y 分层之后一个区间都没有（形状退化？）")
        }

        // ⑤ 盖子：每个梯形 → 扇形三角化。顶盖逆时针（法线 +Z）、底盖反向（法线 −Z）。
        for (trap in traps) {
            val poly = trapezoidPolygon(trap, xAtY)
            if (poly.size < 3) continue
            for (i in 1 until poly.size - 1) {
                val a = poly[0]
                val b = poly[i]
                val c = poly[i + 1]
                builder.addTriangle(
                    em.capVertex(a, zTop, box, spanX, spanY, 1f),
                    em.capVertex(b, zTop, box, spanX, spanY, 1f),
                    em.capVertex(c, zTop, box, spanX, spanY, 1f),
                )
                builder.addTriangle(
                    em.capVertex(a, zBot, box, spanX, spanY, -1f),
                    em.capVertex(c, zBot, box, spanX, spanY, -1f),
                    em.capVertex(b, zBot, box, spanX, spanY, -1f),
                )
            }
        }

        // ⑥ 侧面：每条层内边一个矩形；水平边按同一批切点切开（否则会出现 T 形接缝）
        for (k in bySlab.indices) {
            for (edge in bySlab[k]) emitWall(builder, em, edge, zBot, zTop)
        }
        for (edge in horizontal) {
            // 水平边落在某一层的边界上，它自己就是那一层的"台阶"。
            // 按同一批切点切开，才能和盖子的水平边逐点对齐（否则就是 T 形接缝）。
            val cuts = xAtY[edge.start.y]
            val forward = edge.end.x >= edge.start.x
            val xLo = kotlin.math.min(edge.start.x, edge.end.x)
            val xHi = kotlin.math.max(edge.start.x, edge.end.x)
            val stations = ArrayList<Double>(4)
            stations += xLo
            cuts?.forEach { x -> if (x > xLo + EPS && x < xHi - EPS) stations += x }
            stations += xHi
            if (!forward) stations.reverse()
            val dx = edge.end.x - edge.start.x
            for (i in 0 until stations.size - 1) {
                val x0 = stations[i]
                val x1 = stations[i + 1]
                if (abs(x1 - x0) <= EPS) continue
                val f0 = if (abs(dx) <= EPS) 0.0 else (x0 - edge.start.x) / dx
                val f1 = if (abs(dx) <= EPS) 1.0 else (x1 - edge.start.x) / dx
                emitWall(
                    builder, em,
                    SlabEdge(
                        Vec2(x0, edge.start.y), Vec2(x1, edge.start.y), edge.start.y, edge.start.y,
                        edge.u0 + (edge.u1 - edge.u0) * f0,
                        edge.u0 + (edge.u1 - edge.u0) * f1,
                    ),
                    zBot, zTop,
                )
            }
        }
    }

    // ────────────────────────── 切分 ──────────────────────────

    /** 所有轮廓顶点的 y（升序去重）。 */
    private fun sortedDistinctY(profile: Profile): DoubleArray {
        val set = HashSet<Double>()
        for (c in profile.contours) for (p in c.points) set += p.y
        val out = set.toDoubleArray()
        out.sort()
        return out
    }

    /**
     * 把 y 相差不到 [Y_MERGE] 的顶点并到同一个 y 上（x 不动）。
     *
     * 这不是"美化"，是**正确性**：见 [extrude] 里的说明 —— 不并的话圆上那些
     * `sin(kπ) = 1.1e-16` 的点会切出 1e-16 厚的层，层里解不出区间，盖子上就留缝。
     * [Y_MERGE] 取 1e-9：比任何有意义的尺寸小 6 个数量级，只并"数值上应当相等"的那些。
     */
    private fun snapY(profile: Profile): Profile {
        val raw = sortedDistinctY(profile)
        if (raw.size < 2) return profile
        val rep = HashMap<Double, Double>(raw.size * 2)
        var groupStart = raw[0]
        for (y in raw) {
            if (y - groupStart > Y_MERGE) groupStart = y
            rep[y] = groupStart
        }
        return Profile(
            profile.contours.map { contour ->
                Contour(contour.points.map { p -> Vec2(p.x, rep[p.y] ?: p.y) })
            }
        )
    }

    /**
     * 把每条轮廓边切成"层内边"。
     *
     * **保留轮廓方向**（内侧在左手边）：外墙的法线就是靠它定的，方向错了整个实体会翻面。
     */
    private fun splitContours(
        profile: Profile,
        ys: DoubleArray,
        horizontal: MutableList<SlabEdge>,
        bySlab: Array<MutableList<SlabEdge>>,
    ) {
        for (c in profile.contours) {
            val n = c.points.size
            // 侧面 UV 的 u 轴 = 沿这一环的弧长（归一到 [0,1]，每个环各占一整张图）
            val perimeter = max(c.perimeter(), 1e-9)
            var walked = 0.0
            for (i in 0 until n) {
                val p = c.points[i]
                val q = c.points[(i + 1) % n]
                val len = p.distanceTo(q)
                if (len <= EPS) continue
                if (p.y == q.y) {
                    // 水平边：它自己就是一层之间的"台阶"，不跨任何一层
                    horizontal += SlabEdge(
                        p, q, p.y, p.y, walked / perimeter, (walked + len) / perimeter,
                    )
                    walked += len
                    continue
                }
                val upward = q.y > p.y
                val dy = q.y - p.y
                val lo = if (upward) p.y else q.y
                val hi = if (upward) q.y else p.y
                val stations = ArrayList<Double>(4)
                stations += lo
                for (y in ys) if (y > lo && y < hi) stations += y
                stations += hi
                for (k in 0 until stations.size - 1) {
                    val y0 = stations[k]
                    val y1 = stations[k + 1]
                    if (y1 - y0 <= EPS) continue
                    val a = Vec2(xOn(p, q, y0), y0)
                    val b = Vec2(xOn(p, q, y1), y1)
                    val u0 = (walked + (y0 - p.y) / dy * len) / perimeter
                    val u1 = (walked + (y1 - p.y) / dy * len) / perimeter
                    // start/end 一定要顺着轮廓方向：外墙法线 = 方向右转 90°
                    val edge = if (upward) SlabEdge(a, b, y0, y1, u0, u1) else SlabEdge(b, a, y0, y1, u1, u0)
                    val idx = lowerBound(ys, y0)
                    if (idx in bySlab.indices) bySlab[idx].add(edge)
                }
                walked += len
            }
        }
    }

    /** 边 `p→q` 上 y 对应的 x（端点精确返回，避免插值误差破坏逐点对齐）。 */
    private fun xOn(p: Vec2, q: Vec2, y: Double): Double {
        if (y == p.y) return p.x
        if (y == q.y) return q.x
        return p.x + (y - p.y) / (q.y - p.y) * (q.x - p.x)
    }

    /**
     * 解一层里的所有区间。
     *
     * 取该层中线 `ymid`（层内没有任何顶点 ⇒ 不会有顶点落在中线上），把跨过它的层内边按 x 排序，
     * 在相邻交点**正中间**采样，用轮廓的奇偶规则判里外 ⇒ 每段"里"的区间就是一个梯形。
     */
    private fun collectSlabIntervals(
        profile: Profile,
        box: Box2,
        yLo: Double,
        yHi: Double,
        edges: List<SlabEdge>,
        traps: MutableList<Trap>,
        xAtY: HashMap<Double, TreeX>,
    ) {
        if (edges.isEmpty()) return
        val ymid = (yLo + yHi) * 0.5
        val sorted = edges.map { it to it.xAt(ymid) }.sortedBy { it.second }

        // 交点去重（两条边在中线处几乎重合时只留一条，免得采样点落在零宽区间里）
        val xs = DoubleArray(sorted.size)
        val owners = arrayOfNulls<SlabEdge>(sorted.size)
        var m = 0
        for ((edge, x) in sorted) {
            if (m > 0 && abs(x - xs[m - 1]) <= EPS) continue
            xs[m] = x
            owners[m] = edge
            m++
        }

        val inside = BooleanArray(m + 1)
        for (i in 0..m) {
            val sx = when (i) {
                0 -> box.minX - 1.0
                m -> box.maxX + 1.0
                else -> (xs[i - 1] + xs[i]) * 0.5
            }
            inside[i] = profile.contains(sx, ymid)
        }

        var i = 1
        while (i <= m - 1) {
            if (!inside[i]) {
                i++
                continue
            }
            var j = i
            while (j <= m - 1 && inside[j]) j++
            // 采样点 i..j-1 落在同一段"里"：区间 = (xs[i-1], xs[j-1])
            val left = owners[i - 1]!!
            val right = owners[j - 1]!!
            val lx0 = left.xAt(yLo)
            val lx1 = left.xAt(yHi)
            val rx0 = right.xAt(yLo)
            val rx1 = right.xAt(yHi)
            if (rx0 - lx0 > EPS || rx1 - lx1 > EPS) {
                traps += Trap(yLo, yHi, lx0, lx1, rx0, rx1)
                xAtY.getOrPut(yLo) { TreeX() }.add(lx0)
                xAtY.getOrPut(yLo) { TreeX() }.add(rx0)
                xAtY.getOrPut(yHi) { TreeX() }.add(lx1)
                xAtY.getOrPut(yHi) { TreeX() }.add(rx1)
            }
            i = j
        }
    }

    /**
     * 一个梯形（+ 该 y 上所有出现过的 x 作为切点）→ 逆时针多边形。
     *
     * **为什么必须按 [xAtY] 切开**：相邻两层的区间端点一般不同（形状在那里拐了），
     * 不统一切点就会留下 T 形接缝 —— 那正是"壳/裂缝"的来源。切完两层的水平边逐点一致。
     */
    private fun trapezoidPolygon(trap: Trap, xAtY: HashMap<Double, TreeX>): List<Vec2> {
        val bottom = ArrayList<Vec2>(4)
        bottom += Vec2(trap.lx0, trap.yLo)
        xAtY[trap.yLo]?.let { cuts ->
            for (x in cuts) if (x > trap.lx0 + EPS && x < trap.rx0 - EPS) bottom += Vec2(x, trap.yLo)
        }
        bottom += Vec2(trap.rx0, trap.yLo)

        val top = ArrayList<Vec2>(4)
        top += Vec2(trap.rx1, trap.yHi)
        // ⚠️ 上边是**从右往左**走的，所以切点必须按**降序**追加 ——
        //    照升序追加会把这个梯形拧成一个锯齿状的回路（切出来的三角形全是错的）。
        xAtY[trap.yHi]?.descendingSet()?.let { cuts ->
            for (x in cuts) if (x > trap.lx1 + EPS && x < trap.rx1 - EPS) top += Vec2(x, trap.yHi)
        }
        top += Vec2(trap.lx1, trap.yHi)

        // 下边从左到右、上边从右到左 ⇒ 整体逆时针
        // ⚠️ **不许在这里去掉共线点**：那些"多余"的切点正是与邻层、与侧面逐点对齐的凭据，
        //    去掉就会重新长出 T 形接缝。只去掉**完全重合**的点（见 [dedupeRing]）。
        val out = ArrayList<Vec2>(bottom.size + top.size)
        out += bottom
        out += top
        return dedupeRing(out)
    }

    /**
     * 去掉环上**重合的相邻点**（首尾相接也算）。
     *
     * 为什么会有重合点：圆的最上/最下/最左/最右那一点上，左右两条边界边**共用同一个端点**，
     * 于是梯形的那条水平边两端取到同一个 x（`lx == rx`）—— 那其实是个**三角形**，
     * 留着那个重复点只会切出零面积的三角形（自检里"退化三角形 = 0"那条就是钉它的）。
     * 去掉之后那条"边"退化成一个点，上下两层的顶点仍然重合，配对面数不受影响。
     */
    private fun dedupeRing(points: List<Vec2>): List<Vec2> {
        val out = ArrayList<Vec2>(points.size)
        for (p in points) {
            val last = out.lastOrNull()
            if (last != null && last.x == p.x && last.y == p.y) continue
            out += p
        }
        while (out.size >= 2) {
            val first = out[0]
            val last = out[out.size - 1]
            if (first.x == last.x && first.y == last.y) out.removeAt(out.size - 1) else break
        }
        return out
    }

    /** 一条层内边挤出的竖直矩形（外法线 = 方向右转 90°，所以方向必须是"内侧在左"的轮廓方向）。 */
    private fun emitWall(
        builder: StudioMeshBuilder,
        em: Emitter,
        edge: SlabEdge,
        zBot: Double,
        zTop: Double,
    ) {
        val s = edge.start
        val e = edge.end
        val dx = e.x - s.x
        val dy = e.y - s.y
        val len = sqrt(dx * dx + dy * dy)
        if (len <= EPS) return
        val nx = (dy / len).toFloat()
        val ny = (-dx / len).toFloat()
        val pb = em.vertex(s.x, s.y, zBot, edge.u0.toFloat(), 1f, nx, ny, 0f)
        val qb = em.vertex(e.x, e.y, zBot, edge.u1.toFloat(), 1f, nx, ny, 0f)
        val qt = em.vertex(e.x, e.y, zTop, edge.u1.toFloat(), 0f, nx, ny, 0f)
        val pt = em.vertex(s.x, s.y, zTop, edge.u0.toFloat(), 0f, nx, ny, 0f)
        builder.addTriangle(pb, qb, qt)
        builder.addTriangle(pb, qt, pt)
    }

    // ────────────────────────── 小结构 ──────────────────────────

    /**
     * 一条**层内边**（只属于一层）。
     *
     * @param start/end 顺着**轮廓方向**（内侧在左手边），法线由它推出来
     * @param yLo/yHi   这一层的上下边界（`yLo < yHi`）
     * @param u0/u1     沿该环的**累计弧长**（侧面 UV 用）
     */
    private class SlabEdge(
        val start: Vec2,
        val end: Vec2,
        val yLo: Double,
        val yHi: Double,
        val u0: Double,
        val u1: Double,
    ) {
        /** 给定 y 取这条边上的 x（端点精确返回，避免插值误差破坏逐点对齐）。 */
        fun xAt(y: Double): Double {
            if (y == start.y) return start.x
            if (y == end.y) return end.x
            return start.x + (y - start.y) / (end.y - start.y) * (end.x - start.x)
        }
    }

    /** 一层里的一个区间：下边 `[lx0, rx0]`、上边 `[lx1, rx1]`。 */
    private class Trap(
        val yLo: Double,
        val yHi: Double,
        val lx0: Double,
        val lx1: Double,
        val rx0: Double,
        val rx1: Double,
    )

    /** 有序的 x 集合（切点表）。用 [java.util.TreeSet] 保证遍历顺序确定。 */
    private class TreeX : java.util.TreeSet<Double>()

    /** 二分找 `ys` 里等于 [y] 的下标（切分时用；找不到返回负数）。 */
    private fun lowerBound(ys: DoubleArray, y: Double): Int {
        var lo = 0
        var hi = ys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                ys[mid] < y -> lo = mid + 1
                ys[mid] > y -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    // ────────────────────────── 顶点池 ──────────────────────────

    /** 顶点按**完整 8 个分量**去重（位置相同但法线/UV 不同的点必须各留一份，否则光照会切错）。 */
    private class Emitter(private val builder: StudioMeshBuilder) {

        private val pool = HashMap<List<Int>, Int>()

        fun vertex(
            x: Double,
            y: Double,
            z: Double,
            u: Float,
            v: Float,
            nx: Float,
            ny: Float,
            nz: Float,
        ): Int {
            val fx = x.toFloat()
            val fy = y.toFloat()
            val fz = z.toFloat()
            val key = listOf(
                fx.toRawBits(), fy.toRawBits(), fz.toRawBits(),
                u.toRawBits(), v.toRawBits(),
                nx.toRawBits(), ny.toRawBits(), nz.toRawBits(),
            )
            pool[key]?.let { return it }
            val id = builder.addVertex(StudioVertex(fx, fy, fz, u, v, nx, ny, nz))
            pool[key] = id
            return id
        }

        /** 盖子上的点：平面 UV + 指定 Z 法线。 */
        fun capVertex(p: Vec2, z: Double, box: Box2, spanX: Double, spanY: Double, nz: Float): Int =
            vertex(
                p.x, p.y, z,
                ((p.x - box.minX) / spanX).toFloat(),
                ((box.maxY - p.y) / spanY).toFloat(),
                0f, 0f, nz,
            )
    }
}