package rain.gtetcore.gtet.studio.kernel.cad

import rain.gtetcore.gtet.studio.kernel.cad.StudioCadBoolean.MAX_ARRANGEMENT_SEGMENTS
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

/**
 * **2D 布尔**（`union` / `subtract` / `intersect`）—— M3a 里唯一"难"的一步。
 *
 * ## 为什么是 2D 平面布尔而不是 3D 网格布尔
 * 3D 网格布尔（BSP）在**薄壁**上必翻车（自交/碎面），而"镂空"全是薄壁；
 * 体素/SDF 稳但要拿分辨率换质量。时钟零件（表盘/辐条/齿轮/指针）**本质上全是平板件**，
 * 所以 M3a 走「2D 轮廓布尔 + 挤出」这条路 —— 圆轮廓能给到 64~256 段，边缘是平滑的，
 * 三角形数只有几千，代价是**球体与 3D 布尔做不了**（明确留给 M3b，不是"以后再说"）。
 *
 * ## 算法（排列 + 边界提取）
 * ```
 * ① 把 A、B 的全部边拿来**两两求交**，在每个交点处切开 → 一副平面排列（arrangement）
 * ② 每条（去重后的无向）边取中点，向两侧各偏 1e-6 采一个样，
 *    用"奇偶规则点在轮廓内"分别算 A/B 的内外，再套布尔算子 ⇒ 两侧结果不同 = 结果区域的边界
 * ③ 边界边统一定向成"**内侧在左手边**"
 * ④ 按 DCEL 的 next 规则串成闭环，去掉共线的中间点 → 结果 [Profile]
 * ```
 * 没有"看运气"的启发式：判据是布尔代数的真值表。结果是**精确多边形**
 * （顶点落在真实交点与真实顶点上），不是重采样出来的阶梯。
 *
 * ## 边界（写清楚，别当成 bug）
 * - 输入边数上限 [MAX_ARRANGEMENT_SEGMENTS]（求交是 O(n²)，超了**直接报错**而不是卡死）；
 * - 轮廓**不自交**是前提：`array` 的副本若互相重叠，重叠处按奇偶规则被异或掉
 *   （要合并请显式写 `union`）。
 *
 * @author rain fox
 */
object StudioCadBoolean {

    /** 布尔算子。 */
    enum class Op {
        UNION,

        /** `a - b`（把 b 从 a 上挖掉）。 */
        SUBTRACT,

        INTERSECT;

        fun apply(inA: Boolean, inB: Boolean): Boolean = when (this) {
            UNION -> inA || inB
            SUBTRACT -> inA && !inB
            INTERSECT -> inA && inB
        }

        /** JSON 里的名字（也是 `type` 字段的取值）。 */
        val jsonName: String get() = name.lowercase()
    }

    /**
     * 一副排列里最多允许多少条输入边。
     *
     * 求交是两两比较：4096 条边 = 840 万次，毫秒级；再往上就该让用户自己去减 `segments` 了。
     * 超限**报错**而不是"跑很久"——载入期必须立刻给结论（设计文档 §7.1）。
     */
    const val MAX_ARRANGEMENT_SEGMENTS: Int = 4096

    /** 顶点按这个网格归并（约 1e-8）：交点由两个不同的参数式算出，差在 1e-15 量级，必须能合到一起。 */
    private const val QUANT: Double = 1e8

    /** 归并时的容差（比 1e-8 网格宽一档，避免恰好落在格子边界的两点被拆开）。 */
    private const val SNAP_TOL: Double = 1e-7

    /** 两侧采样点离边的距离。比归并误差大一个数量级，又远小于任何真实特征的间距。 */
    private const val SIDE_EPS: Double = 1e-6

    /**
     * 跑一次布尔。
     *
     * @param where 出错时写进消息里的「这是哪个特征」，例如 `dial.json: features[4] (name="dial")`
     */
    @JvmStatic
    fun apply(op: Op, a: Profile, b: Profile, where: String): Profile {
        val arrangement = Arrangement(a, b, where)
        arrangement.collectAndSplit()
        arrangement.classify(op)
        return arrangement.traceResult()
    }

    // ────────────────────────── 排列 ──────────────────────────

    private class Segment(val p0: Vec2, val p1: Vec2) {
        val splits = ArrayList<Double>(2).apply { add(0.0); add(1.0) }
    }

    private class Arrangement(val a: Profile, val b: Profile, val where: String) {

        private val segments = ArrayList<Segment>()

        /** 去重后的顶点与其精确坐标 —— **取首次出现的位置**，不做平均（平均会把交点挪离真实位置）。 */
        private val positions = ArrayList<Vec2>()

        private val vertexKey = HashMap<Long, Int>()

        /** 有向边界边：`u → v`，内侧在左手边。 */
        private val outgoing = HashMap<Int, MutableList<Int>>()

        fun collectAndSplit() {
            for (c in a.contours) for (i in c.points.indices) {
                segments += Segment(c.points[i], c.points[(i + 1) % c.points.size])
            }
            for (c in b.contours) for (i in c.points.indices) {
                segments += Segment(c.points[i], c.points[(i + 1) % c.points.size])
            }
            if (segments.size > MAX_ARRANGEMENT_SEGMENTS) {
                throw StudioCadException(
                    "$where: 本次布尔要处理的输入边有 ${segments.size} 条，超过上限 $MAX_ARRANGEMENT_SEGMENTS —— " +
                        "求交是两两比较，再大就会卡住载入。请减少轮廓的 segments，或减少 array 的 count"
                )
            }

            // 两两求交，在交点处切开（含 T 形相接：一端落在另一条边的中间）
            val eps = 1e-12
            for (i in segments.indices) {
                val si = segments[i]
                for (j in i + 1 until segments.size) {
                    val sj = segments[j]
                    val hit = intersectParams(si.p0, si.p1, sj.p0, sj.p1) ?: continue
                    val (t, u) = hit
                    if (t > eps && t < 1 - eps && u > -eps && u < 1 + eps) si.splits.add(t)
                    if (u > eps && u < 1 - eps && t > -eps && t < 1 + eps) sj.splits.add(u)
                }
            }
        }

        /** 顶点登记：按网格归并，顺手查 3×3 邻格（免得恰好落在格子边界的两点被拆开）。 */
        private fun vertexIndex(p: Vec2): Int {
            val gx = round(p.x * QUANT).toLong()
            val gy = round(p.y * QUANT).toLong()
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val found = vertexKey[(gx + dx) * 31_415_927L + (gy + dy)] ?: continue
                    val q = positions[found]
                    if (abs(q.x - p.x) <= SNAP_TOL && abs(q.y - p.y) <= SNAP_TOL) return found
                }
            }
            val id = positions.size
            positions += p
            vertexKey[gx * 31_415_927L + gy] = id
            return id
        }

        /** 结果区域在 (x, y) 处算不算"在里"（奇偶规则 + 布尔算子）。 */
        private fun resultAt(op: Op, x: Double, y: Double): Boolean =
            op.apply(a.contains(x, y), b.contains(x, y))

        fun classify(op: Op) {
            val undirected = HashSet<Long>()
            for (s in segments) {
                val ts = s.splits.toDoubleArray()
                ts.sort()
                for (k in 0 until ts.size - 1) {
                    if (ts[k + 1] - ts[k] < 1e-12) continue
                    val i = vertexIndex(s.p0.lerp(s.p1, ts[k]))
                    val j = vertexIndex(s.p0.lerp(s.p1, ts[k + 1]))
                    if (i == j) continue
                    undirected.add(edgeKey(i, j))
                }
            }

            for (key in undirected) {
                val i = (key ushr 32).toInt()
                val j = (key and 0xFFFFFFFFL).toInt()
                val p = positions[i]
                val q = positions[j]
                val dx = q.x - p.x
                val dy = q.y - p.y
                val len = sqrt(dx * dx + dy * dy)
                if (len < 1e-9) continue
                // 左法线（把 p→q 逆时针转 90°）
                val nx = -dy / len
                val ny = dx / len
                val mx = (p.x + q.x) * 0.5
                val my = (p.y + q.y) * 0.5
                val step = kotlin.math.min(SIDE_EPS, len * 0.25)
                val insideLeft = resultAt(op, mx + nx * step, my + ny * step)
                val insideRight = resultAt(op, mx - nx * step, my - ny * step)
                if (insideLeft == insideRight) continue // 内部边：不是边界
                val from = if (insideLeft) i else j
                val to = if (insideLeft) j else i
                outgoing.getOrPut(from) { ArrayList(4) }.add(to)
            }
        }

        /**
         * 串环。
         *
         * DCEL 的 next 规则：走到 v 之后，取"从 v→u 方向**顺时针**转过最小角度"的那条出边 ——
         * 描出来的面恰好在这条有向边的**左侧**（外环逆时针、孔顺时针，正是我们要的朝向）。
         */
        fun traceResult(): Profile {
            val loops = ArrayList<Contour>()
            val used = HashSet<Long>()
            for ((u, targets) in outgoing) {
                for (v in targets) {
                    if (used.contains(edgeKey(u, v))) continue
                    val pts = ArrayList<Vec2>()
                    var cu = u
                    var cv = v
                    var closed = false
                    var steps = 0
                    while (steps++ < 1_000_000) {
                        used.add(edgeKey(cu, cv))
                        pts += positions[cu]
                        val next = nextEdge(cu, cv) ?: break
                        // 下一条有向边是 (cv, next)；它回到起点 (u, v) 才算闭合成环
                        if (cv == u && next == v) {
                            closed = true
                            break
                        }
                        if (used.contains(edgeKey(cv, next))) break
                        cu = cv
                        cv = next
                    }
                    if (!closed) continue
                    val simple = simplify(pts)
                    if (simple.size >= 3) {
                        val contour = Contour(simple)
                        if (abs(contour.signedArea()) > 1e-12) loops += contour
                    }
                }
            }
            return Profile(loops)
        }

        private fun nextEdge(u: Int, v: Int): Int? {
            val candidates = outgoing[v] ?: return null
            val pu = positions[u]
            val pv = positions[v]
            val base = atan2(pu.y - pv.y, pu.x - pv.x)
            var bestDelta = Double.MAX_VALUE
            var bestW = -1
            for (w in candidates) {
                val pw = positions[w]
                val ang = atan2(pw.y - pv.y, pw.x - pv.x)
                // 从 base 出发顺时针转多少才碰上这条出边（保证为正）
                var delta = base - ang
                while (delta <= 1e-12) delta += 2 * Math.PI
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestW = w
                }
            }
            return if (bestW < 0) null else bestW
        }
    }

    // ────────────────────────── 工具 ──────────────────────────

    /** 无向边的键（小下标在高 32 位，保证 (i,j) 与 (j,i) 同一个键）。 */
    private fun edgeKey(u: Int, v: Int): Long {
        val lo = kotlin.math.min(u, v).toLong()
        val hi = kotlin.math.max(u, v).toLong()
        return (lo shl 32) or hi
    }

    /**
     * 去掉环上**共线的中间点**。
     *
     * 排列切出来的环上必然有大量这种点（一条直边被切成好几段），留着只会让耳切法多出一堆退化三角形。
     * 判据用**相对**容差：`|叉积| <= 1e-9 · (1 + 两段长度之积)`。
     */
    internal fun simplify(points: List<Vec2>): List<Vec2> {
        val n = points.size
        if (n < 3) return points
        val out = ArrayList<Vec2>(n)
        for (i in 0 until n) {
            val prev = points[(i - 1 + n) % n]
            val cur = points[i]
            val next = points[(i + 1) % n]
            val v1x = cur.x - prev.x
            val v1y = cur.y - prev.y
            val v2x = next.x - cur.x
            val v2y = next.y - cur.y
            val len1 = sqrt(v1x * v1x + v1y * v1y)
            val len2 = sqrt(v2x * v2x + v2y * v2y)
            if (len1 < 1e-12 || len2 < 1e-12) continue // 重合点直接丢
            val cr = v1x * v2y - v1y * v2x
            val dot = v1x * v2x + v1y * v2y
            if (abs(cr) <= 1e-9 * (1.0 + len1 * len2) && dot > 0) continue
            out += cur
        }
        return if (out.size >= 3) out else points
    }
}