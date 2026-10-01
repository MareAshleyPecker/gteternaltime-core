package rain.gtetcore.gtet.studio.kernel.cad

import rain.gtetcore.gtet.studio.kernel.StudioMeshBuilder
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadEvaluator.evaluate
import kotlin.math.cos
import kotlin.math.sin

/**
 * **一条 CAD 特征**（参数化历史里的一步）—— M3a 的全部图元与运算。
 *
 * ## 这套东西是什么、不是什么（写清楚，别含糊）
 * 这是 **2.5D**：2D 轮廓（圆/矩形/多边形/圆环）→ 布尔 → **沿 Z 挤出**。
 * 时钟零件（表盘、辐条、齿轮、指针）本质上全是平板件，所以这条路**够用且稳**；
 * 但它是**不完整的 CAD**：**球体、3D 布尔、旋转体、草图约束一个都没有**，明确留给 M3b/M7。
 *
 * 为什么不做 3D 网格布尔：薄壁（= 全部镂空结构）正是它最容易翻车的地方（自交/碎面）；
 * 体素/SDF 稳但要拿分辨率换质量（10 格直径的钟要 100³ 体素、表面 6 万面才不显阶梯）。
 *
 * ## 拓扑序
 * `features` 是**按顺序重放的配方**：一个特征只能引用**排在它前面**的特征。
 * 于是"引用不存在的名字"「引用自己」「成环」都会在载入期被明确拒绝，
 * 而不是留到求值时变成一个看不懂的 NPE。
 *
 * ## 尺寸与坐标
 * 单位与 OBJ 约定一致（1 单位 = 1 格），轮廓在 **XY 平面**、**+Z 是正面**。
 * 所有图元的 `at: [x, y]` 是这个图元的**局部原点**：圆 = 圆心、矩形 = 矩形中心、
 * 多边形 = `points` 坐标的偏移量。省略 = `[0, 0]`。
 *
 * @author rain fox
 */
sealed class StudioCadFeature {

    /** 特征名 —— 也是它**产出的组名**（`extrude` 才有产出）。 */
    abstract val name: String

    /** JSON 里 `type` 的取值。 */
    abstract val type: String

    /** 产出的是三维实体吗（只有 `extrude` 是）。 */
    open val producesSolid: Boolean get() = false

    /** 它引用了哪些别的特征（校验拓扑序用）。 */
    open val references: List<String> get() = emptyList()

    /** 写进网格的材质名 —— 只有 `extrude` 有意义（2D 特征没有面，自然没有材质）。 */
    open val material: String? get() = null

    override fun toString(): String = "$type(name=$name)"

    // ────────────────────────── 2D 图元 ──────────────────────────

    /** 圆（`segments` 段折线逼近，默认 64）。 */
    data class Circle(
        override val name: String,
        val r: Double,
        val segments: Int,
        val at: Vec2,
    ) : StudioCadFeature() {
        override val type: String get() = TYPE
        companion object {
            const val TYPE = "circle"
            const val DEFAULT_SEGMENTS = 64
        }
    }

    /** 矩形（**以 `at` 为中心**，宽 [w]、高 [h]）。 */
    data class Rect(
        override val name: String,
        val w: Double,
        val h: Double,
        val at: Vec2,
    ) : StudioCadFeature() {
        override val type: String get() = TYPE
        companion object { const val TYPE = "rect" }
    }

    /** 任意多边形（`points` 至少 3 个点）。 */
    data class Polygon(
        override val name: String,
        val points: List<Vec2>,
        val at: Vec2,
    ) : StudioCadFeature() {
        override val type: String get() = TYPE
        companion object { const val TYPE = "polygon" }
    }

    /** 圆环 = 外圆 + 内圆（**奇偶规则天然成立，不需要跑布尔**，给用户省一次手写）。 */
    data class Ring(
        override val name: String,
        val rOuter: Double,
        val rInner: Double,
        val segments: Int,
        val at: Vec2,
    ) : StudioCadFeature() {
        override val type: String get() = TYPE
        companion object { const val TYPE = "ring" }
    }

    // ────────────────────────── 布尔 ──────────────────────────

    /** 布尔运算（`union` / `subtract` / `intersect`，操作数按名字引用）。 */
    data class Bool(
        override val name: String,
        val op: StudioCadBoolean.Op,
        val a: String,
        val b: String,
    ) : StudioCadFeature() {
        override val type: String get() = op.jsonName
        override val references: List<String> get() = listOf(a, b)
    }

    // ────────────────────────── 阵列 / 成体 ──────────────────────────

    /**
     * 圆周阵列。
     *
     * ```
     * 第 i 个副本 = Rot_z(startDeg + i·sweepDeg/count) ∘ Translate(radius, 0) ∘ of
     * ```
     * `i` 取 `0 .. count-1`：`sweepDeg` 是**总张角**而不是步距，右端**不闭合** ——
     * 所以 `sweepDeg=360` 时正好得到 `count` 个互不重合的副本（这是默认，也是"绕圈均布"的含义）；
     * `sweepDeg=180, count=3` 得到 0° / 60° / 120° 三个（步距 = 总张角 ÷ 个数）。
     *
     * `axis` 目前**只支持 `"z"`**（在 XY 平面内绕原点复制）。写 `x` / `y` 会明说"没实现"
     * 而不是悄悄按 z 算 —— 绕 x/y 会让副本离开平面，那是 M3b 的 3D 阵列。
     */
    data class CircArray(
        override val name: String,
        val of: String,
        val count: Int,
        val radius: Double,
        val axis: String,
        val startDeg: Double,
        val sweepDeg: Double,
    ) : StudioCadFeature() {
        override val type: String get() = TYPE
        override val references: List<String> get() = listOf(of)
        companion object {
            const val TYPE = "array"
            const val AXIS_Z = "z"
        }
    }

    /**
     * 沿 **+Z** 挤出成体。
     *
     * @param centerZ `true`（默认）= 以 Z=0 为中心，实体落在 `[-t/2, +t/2]`；
     *                `false` = 从 Z=0 向 +Z 长出，落在 `[0, t]`
     */
    data class Extrude(
        override val name: String,
        val of: String,
        val thickness: Double,
        val centerZ: Boolean,
        override val material: String?,
    ) : StudioCadFeature() {
        override val type: String get() = TYPE
        override val producesSolid: Boolean get() = true
        override val references: List<String> get() = listOf(of)
        companion object { const val TYPE = "extrude" }
    }
}

/**
 * **特征历史的求值器**：按拓扑序重放 [StudioCadFeature]，产出 `kernel.StudioMesh` 的组。
 *
 * 只做三件事：校验（拓扑序 / 维度 / 参数）、求轮廓、把 `extrude` 写进 [StudioMeshBuilder]。
 * **一个 `extrude` 恰好产出一个组，组名 = 特征名** —— 于是"齿轮单独转"直接沿用现有的
 * 按组名叠矩阵机制，**不需要任何新机制**（设计文档 §7 第 2 条）。
 *
 * @author rain fox
 */
object StudioCadEvaluator {

    /**
     * 圆/环的 `segments` 上限。
     *
     * 上限来自"**三角形预算**"而不是随便定的：一个 512 段的环挤出后 ≈ 512×2（侧面）+ 约 1020（盖子）
     * ≈ 2000 个三角形，已经很精细；再往上只会在 §7.1 的 `maxTrianglesPerModel` 那里被拒。
     */
    const val MAX_SEGMENTS: Int = 512

    const val MIN_SEGMENTS: Int = 3

    /** 一次求值的结果（给日志用：产出了什么、哪些白定义了）。 */
    class Result(
        /** 产出的组名（= `extrude` 的名字，按定义顺序）。 */
        val groupNames: List<String>,
        /** 定义了、但没有被任何 `extrude` 用到的 2D 特征名（无害，但值得提示一句）。 */
        val unusedFeatures: List<String>,
        /** 每个组的三角形数。 */
        val trianglesByGroup: Map<String, Int>,
        /** 每条 2D 特征算出来的**轮廓**（诊断与脱机自检用；生产路径只读 [groupNames]）。 */
        val profiles: Map<String, Profile>,
    ) {
        val triangleCount: Int get() = trianglesByGroup.values.sum()
    }

    /**
     * 求值 + 写进 [builder]。
     *
     * @param where 出错时写进消息里的「这是哪份数据」，例如 `dial.json`
     * @throws StudioCadException 参数非法 / 引用不成立 / 布尔或三角化失败（**载入期拒绝**）
     */
    @JvmStatic
    fun evaluate(
        features: List<StudioCadFeature>,
        builder: StudioMeshBuilder,
        where: String,
    ): Result {
        val session = Session(features, where)
        if (features.none { it.producesSolid }) {
            throw StudioCadException(
                "$where: features 里一条 extrude 都没有 —— 2D 轮廓本身不产生任何几何，" +
                    "至少要有一条 extrude 才有东西可画"
            )
        }

        // 只算"真的被挤出用到"的那些轮廓（省掉白定义特征的重算）；
        // 被跳过的 2D 特征照样**过了参数校验**吗？没有 —— 所以它们一律列进 unusedFeatures 提示用户。
        val needed = session.reachableFromSolids()
        val groupNames = ArrayList<String>()
        val triangles = LinkedHashMap<String, Int>()
        for ((i, f) in features.withIndex()) {
            if (i !in needed) continue
            val at = session.describe(i)
            val profile = session.profile(i)
            if (f is StudioCadFeature.Extrude) {
                val before = builder.triangleCount
                StudioCadMesh.extrude(
                    builder = builder,
                    group = f.name,
                    material = f.material,
                    profile = profile,
                    thickness = f.thickness,
                    centerZ = f.centerZ,
                    where = at,
                )
                val added = builder.triangleCount - before
                if (added <= 0) {
                    throw StudioCadException("$at: 挤出之后一个三角形都没有 —— 轮廓是不是退化了？")
                }
                groupNames += f.name
                triangles[f.name] = added
            }
        }

        val unused = features.filterIndexed { i, _ -> i !in needed }.map { it.name }
        return Result(groupNames, unused, triangles, session.computedProfiles())
    }

    /**
     * **只算轮廓，不产出网格**（诊断与脱机自检用）。
     *
     * 与 [evaluate] 共用同一套校验与同一段求值代码 —— 所以自检验的就是生产路径那条路，
     * 不是另抄一份算一遍。
     *
     * @return `特征名 → 轮廓`（只含 2D 特征）
     */
    @JvmStatic
    fun profiles(features: List<StudioCadFeature>, where: String): Map<String, Profile> {
        val session = Session(features, where)
        for ((i, f) in features.withIndex()) {
            if (!f.producesSolid) session.profile(i)
        }
        return session.computedProfiles()
    }

    // ────────────────────────── 一次求值的内部状态 ──────────────────────────

    /**
     * 一次求值的状态：名字表、拓扑序校验、轮廓缓存。
     *
     * 单开一个类是为了让"生产求值"与"只算轮廓"共用**完全相同**的校验与计算路径 ——
     * 自检里那句"面积等于解析值"才真的能代表游戏里跑的那段代码。
     */
    private class Session(val features: List<StudioCadFeature>, val where: String) {

        private val index = LinkedHashMap<String, Int>(features.size * 2)
        private val cache = arrayOfNulls<Profile>(features.size)

        init {
            if (features.isEmpty()) {
                throw StudioCadException("$where: features 是空的（要么写几条特征，要么去掉这个字段走 source）")
            }
            // ① 名字表：重名 = 后面那条永远引用不到，必须当场拒绝
            for ((i, f) in features.withIndex()) {
                if (f.name.isBlank()) {
                    throw StudioCadException("$where: features[$i] 的 name 是空的（组名要靠它，不能省）")
                }
                val previous = index.put(f.name, i)
                if (previous != null) {
                    throw StudioCadException(
                        "$where: features[$i] 的名字「${f.name}」和 features[$previous] 重名 —— " +
                            "特征名同时是组名，必须唯一"
                    )
                }
            }
            // ② 拓扑序：只许引用排在它前面的特征
            for ((i, f) in features.withIndex()) {
                for (ref in f.references) {
                    val at = describe(i)
                    val target = index[ref]
                        ?: throw StudioCadException("$at: 引用了不存在的特征「$ref」；已定义的是 ${index.keys}")
                    if (target == i) {
                        throw StudioCadException("$at: 引用了自己（「$ref」）—— 特征只许引用排在它前面的")
                    }
                    if (target > i) {
                        throw StudioCadException(
                            "$at: 引用了排在它后面的特征「$ref」（features[$target]）—— " +
                                "features 是**按顺序重放的配方**，被引用的必须先定义" +
                                "（自引用与成环引用都会被这一条挡住）"
                        )
                    }
                    if (features[target].producesSolid) {
                        throw StudioCadException(
                            if (f.producesSolid) {
                                "$at: 要挤出的「$ref」本身已经是一个挤出体了 —— " +
                                    "extrude 的 of 必须指向一条 **2D 轮廓**特征"
                            } else {
                                "$at: 引用的是三维特征「$ref」（${features[target].type}），" +
                                    "但 ${f.type} 只能作用于 2D 轮廓。想把两个挤出体组合起来，" +
                                    "M3a 做不到 —— 那是 M3b 的 3D 布尔"
                            }
                        )
                    }
                }
            }
        }

        fun describe(i: Int): String = "$where: features[$i] (name=\"${features[i].name}\", type=${features[i].type})"

        /** 从所有 `extrude` 出发反向可达的特征下标（= 真的要算的那些）。 */
        fun reachableFromSolids(): Set<Int> {
            val needed = HashSet<Int>()
            val stack = ArrayList<Int>()
            for ((i, f) in features.withIndex()) {
                if (f.producesSolid) {
                    needed += i
                    stack += i
                }
            }
            while (stack.isNotEmpty()) {
                val i = stack.removeAt(stack.size - 1)
                for (ref in features[i].references) {
                    val t = index[ref] ?: continue
                    if (needed.add(t)) stack += t
                }
            }
            return needed
        }

        fun computedProfiles(): Map<String, Profile> {
            val out = LinkedHashMap<String, Profile>()
            for ((i, f) in features.withIndex()) {
                if (f.producesSolid) continue
                cache[i]?.let { out[f.name] = it }
            }
            return out
        }

        /** 求第 [i] 条特征的轮廓（带缓存；只对 2D 特征有意义）。 */
        fun profile(i: Int): Profile {
            cache[i]?.let { return it }
            val f = features[i]
            val at = describe(i)
            val computed = computeProfile(f, at)
            cache[i] = computed
            return computed
        }

        private fun refProfile(name: String, at: String): Profile {
            val idx = index[name]
                ?: throw StudioCadException("$at: 引用了不存在的特征「$name」")
            return profile(idx)
        }

        // ────────────────────────── 单个特征的轮廓 ──────────────────────────

        private fun computeProfile(
            f: StudioCadFeature,
            at: String,
        ): Profile = when (f) {
            is StudioCadFeature.Circle -> {
                checkPositive("r", f.r, at)
                checkSegments(f.segments, at)
                Profile(listOf(circleContour(f.at, f.r, f.segments)))
            }

            is StudioCadFeature.Rect -> {
                checkPositive("w", f.w, at)
                checkPositive("h", f.h, at)
                val hw = f.w * 0.5
                val hh = f.h * 0.5
                val c = listOf(
                    Vec2(f.at.x - hw, f.at.y - hh),
                    Vec2(f.at.x + hw, f.at.y - hh),
                    Vec2(f.at.x + hw, f.at.y + hh),
                    Vec2(f.at.x - hw, f.at.y + hh),
                )
                Profile(listOf(Contour(c))).normalized()
            }

            is StudioCadFeature.Polygon -> {
                if (f.points.size < 3) {
                    throw StudioCadException("$at: points 只有 ${f.points.size} 个点，多边形至少要 3 个")
                }
                val pts = f.points.map { Vec2(it.x + f.at.x, it.y + f.at.y) }
                Profile(listOf(Contour(pts))).normalized()
            }

            is StudioCadFeature.Ring -> {
                checkPositive("rOuter", f.rOuter, at)
                checkPositive("rInner", f.rInner, at)
                if (f.rInner >= f.rOuter) {
                    throw StudioCadException(
                        "$at: rInner=${f.rInner} 不小于 rOuter=${f.rOuter} —— 环要有一个正的壁厚"
                    )
                }
                checkSegments(f.segments, at)
                Profile(
                    listOf(
                        circleContour(f.at, f.rOuter, f.segments),
                        circleContour(f.at, f.rInner, f.segments),
                    )
                ).normalized()
            }

            is StudioCadFeature.Bool -> {
                val pa = refProfile(f.a, at)
                val pb = refProfile(f.b, at)
                StudioCadBoolean.apply(f.op, pa, pb, at)
            }

            is StudioCadFeature.CircArray -> {
                if (f.axis != StudioCadFeature.CircArray.AXIS_Z) {
                    throw StudioCadException(
                        "$at: axis=\"${f.axis}\" 没实现 —— M3a 的 array 只在 XY 平面内绕原点复制（axis=\"z\"）；" +
                            "绕 x / y 会让副本离开平面，那是 M3b 的 3D 阵列"
                    )
                }
                if (f.count <= 0) {
                    throw StudioCadException("$at: count=${f.count} 必须为正整数")
                }
                if (!f.radius.isFinite() || f.radius < 0) {
                    throw StudioCadException("$at: radius=${f.radius} 不能是负数")
                }
                if (f.sweepDeg == 0.0) {
                    throw StudioCadException("$at: sweepDeg=0 —— 所有副本会叠在一起，没有意义")
                }
                val source = refProfile(f.of, at)
                if (source.isEmpty) {
                    throw StudioCadException("$at: 要阵列的特征「${f.of}」求出来是空的（面积 0）")
                }
                val step = f.sweepDeg / f.count
                val contours = ArrayList<Contour>(source.contours.size * f.count)
                for (k in 0 until f.count) {
                    val rad = Math.toRadians(f.startDeg + step * k)
                    val cs = cos(rad)
                    val sn = sin(rad)
                    for (contour in source.contours) {
                        val mapped = contour.points.map { p ->
                            // 先沿 +X 推到 radius（副本的"环上位置"），再绕原点转 θ（副本朝向跟着转）
                            val px = p.x + f.radius
                            Vec2(px * cs - p.y * sn, px * sn + p.y * cs)
                        }
                        contours += Contour(mapped)
                    }
                }
                Profile(contours)
            }

            is StudioCadFeature.Extrude -> {
                // 挤出本身不产轮廓，它的"轮廓"就是它引用的那个 2D 特征
                refProfile(f.of, at)
            }
        }
    }

    private fun circleContour(center: Vec2, r: Double, segments: Int): Contour {
        val pts = ArrayList<Vec2>(segments)
        for (k in 0 until segments) {
            val a = 2.0 * Math.PI * k / segments
            pts += Vec2(center.x + r * cos(a), center.y + r * sin(a))
        }
        return Contour(pts) // 角度递增 = 逆时针
    }

    // ────────────────────────── 校验 ──────────────────────────

    private fun checkPositive(what: String, value: Double, at: String) {
        if (!value.isFinite() || value <= 0.0) {
            throw StudioCadException("$at: $what=$value 必须为正数（单位：格）")
        }
    }

    private fun checkSegments(segments: Int, at: String) {
        if (segments < MIN_SEGMENTS || segments > MAX_SEGMENTS) {
            throw StudioCadException(
                "$at: segments=$segments 超出范围 $MIN_SEGMENTS..$MAX_SEGMENTS —— " +
                    "太少会看出多边形，太多会撞 §7.1 的单模型三角形上限"
            )
        }
    }
}