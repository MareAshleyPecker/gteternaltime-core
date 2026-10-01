package rain.gtetcore.gtet.studio.kernel.cad

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import rain.gtetcore.gtet.studio.config.stripJsonComments
import rain.gtetcore.gtet.studio.kernel.StudioMesh
import rain.gtetcore.gtet.studio.kernel.StudioMeshBuilder
import rain.gtetcore.gtet.studio.kernel.StudioVertex
import java.io.File
import kotlin.math.*
import kotlin.system.exitProcess

/**
 * **M3a 的脱机自检** —— CAD 内核（轮廓 / 布尔 / 阵列 / 挤出）不需要启动游戏就能验。
 *
 * ## 为什么单独开一个自检类（而不是塞进 `StudioInteractionSelfCheck` 的 ⑪ 节）
 * 1. **依赖不同**：交互自检要 gson + 一大堆 `editor/` 常量；CAD 内核只碰 `kernel/`，
 *    它证明的是"**几何对不对**"，和手感/输入状态机没有关系；
 * 2. **分层纪律**：`kernel/` 是唯一承诺"能整包抽出去当独立库"的子包，
 *    它自己的自检就该和它自己在一起；
 * 3. **服务对象不同**：交互那套是"编辑器手感"的回归网，这套是
 *    "**改一个数字 → 形状真的跟着变**"的回归网。
 *
 * ## 怎么跑（在仓库根目录）
 * ```
 * .\gradlew classes
 * java "-Dstdout.encoding=UTF-8" -cp "build\classes\kotlin\main;<kotlin-stdlib.jar>;<gson.jar>" `
 *      rain.gtetcore.gtet.studio.kernel.cad.StudioCadSelfCheck
 * ```
 * ⚠️ **gson 必须进 classpath**：`features` 的 schema 解析在 `kernel/cad`（它唯一的外部依赖）。
 *
 * ## 它会顺手导出 OBJ
 * 写到 `build/studio-m3a/` 下的几个 `.obj`（`build/` 已被 gitignore）——
 * **不开游戏也能在 Blender 里打开看形状对不对**，这是本次最省事的验收手段。
 *
 * 断言失败以退出码 1 结束（能接进脚本）。**生产路径上没有任何调用点。**
 *
 * @author rain fox
 */
object StudioCadSelfCheck {

    private var passed = 0
    private val failures = ArrayList<String>()

    private const val OUT_DIR = "build/studio-m3a"

    private const val DIAL_JSON = "src/main/resources/gtetstudio/dial.json"

    /** 自检里的所有断言都用这个容差（几何量都在 10 格量级，1e-9 是"数值上应当相等"的量级）。 */
    private const val EPS = 1e-9

    @JvmStatic
    fun main(args: Array<String>) {
        println("═══ studio kernel/cad 自检（M3a：轮廓 → 布尔 → 挤出，不需要启动游戏）═══")
        println()

        checkPrimitives()
        checkBoolean()
        checkArray()
        checkExtrude()
        checkDeterminism()
        checkValidation()
        checkShippedDial()

        println()
        println("═══ 结果：$passed 项通过，${failures.size} 项失败 ═══")
        for (failure in failures) println("  ✗ $failure")
        if (failures.isNotEmpty()) exitProcess(1)
    }

    // ────────────────────────── ① 图元 ──────────────────────────

    private fun checkPrimitives() {
        section("① 2D 图元：面积 / 包围盒 / 环数（解析值 vs 求值结果）")

        // 圆的折线面积 = (1/2)·n·r²·sin(2π/n) —— 内接正 n 边形的解析式
        val circle = profile("c") { listOf(StudioCadFeature.Circle("c", 4.0, 64, Vec2(0.0, 0.0))) }
        val expectedCircle = polygonCircleArea(4.0, 64)
        println("  圆 r=4 seg=64：面积 ${circle.area()}，解析值 $expectedCircle，${circle.contours.size} 环")
        check(
            "圆 r=4 / 64 段：面积 = 解析值 0.5·n·r²·sin(2π/n)",
            near(circle.area(), expectedCircle, EPS),
            "实际 ${circle.area()} / 期望 $expectedCircle",
        )
        check(
            "圆：包围盒 = [-4,-4]~[4,4]（64 段恰好含 (0,±4)/(±4,0)）",
            boxIs(circle.bounds(), -4.0, -4.0, 4.0, 4.0, 1e-12),
            "实际 ${circle.bounds()}",
        )
        check(
            "圆：1 条外环且逆时针（归一化后）",
            circle.normalized().contours.size == 1 && circle.normalized().contours[0].signedArea() > 0,
        )

        val rect = profile("r") { listOf(StudioCadFeature.Rect("r", 10.0, 6.0, Vec2(0.0, 0.0))) }
        check(
            "矩形 10×6：面积 60、包围盒 [-5,-3]~[5,3]",
            near(rect.area(), 60.0, EPS) && boxIs(rect.bounds(), -5.0, -3.0, 5.0, 3.0, 1e-12),
            "面积 ${rect.area()}，包围盒 ${rect.bounds()}",
        )
        check(
            "矩形的 at 是**中心**（不是左下角）：at=[1,2] ⇒ 包围盒 [-4,-1]~[6,5]",
            boxIs(
                profile("r") { listOf(StudioCadFeature.Rect("r", 10.0, 6.0, Vec2(1.0, 2.0))) }.bounds(),
                -4.0, -1.0, 6.0, 5.0, 1e-12,
            ),
        )

        val tri = profile("p") {
            listOf(
                StudioCadFeature.Polygon(
                    "p",
                    listOf(Vec2(0.0, 0.0), Vec2(2.0, 0.0), Vec2(0.0, 2.0)),
                    Vec2(0.0, 0.0),
                )
            )
        }
        check(
            "多边形 (0,0)(2,0)(0,2)：面积 2、包围盒 [0,0]~[2,2]",
            near(tri.area(), 2.0, EPS) && boxIs(tri.bounds(), 0.0, 0.0, 2.0, 2.0, 1e-12),
            "面积 ${tri.area()}，包围盒 ${tri.bounds()}",
        )
        check(
            "多边形的 at 是整体偏移：at=[3,4] ⇒ 包围盒 [3,4]~[5,6]",
            boxIs(
                profile("p") {
                    listOf(
                        StudioCadFeature.Polygon(
                            "p",
                            listOf(Vec2(0.0, 0.0), Vec2(2.0, 0.0), Vec2(0.0, 2.0)),
                            Vec2(3.0, 4.0),
                        )
                    )
                }.bounds(),
                3.0, 4.0, 5.0, 6.0, 1e-12,
            ),
        )
        check(
            "多边形的点序**反着写也行**（归一化会统一朝向）",
            near(
                profile("p") {
                    listOf(
                        StudioCadFeature.Polygon(
                            "p",
                            listOf(Vec2(0.0, 2.0), Vec2(2.0, 0.0), Vec2(0.0, 0.0)),
                            Vec2(0.0, 0.0),
                        )
                    )
                }.area(),
                2.0, EPS,
            ),
        )

        val ring = profile("ring") { listOf(StudioCadFeature.Ring("ring", 4.0, 3.0, 64, Vec2(0.0, 0.0))) }
        val expectedRing = polygonCircleArea(4.0, 64) - polygonCircleArea(3.0, 64)
        println("  环 4/3 seg=64：面积 ${ring.area()}，解析值 $expectedRing，环数 ${ring.contours.size}")
        check(
            "圆环 4/3：面积 = 外 − 内（解析值），2 条环",
            near(ring.area(), expectedRing, EPS) && ring.contours.size == 2,
            "实际 ${ring.area()} / 期望 $expectedRing，环数 ${ring.contours.size}",
        )
        check(
            "圆环归一化：外环逆时针（正面积）、内环顺时针（负面积）",
            ring.normalized().contours.count { it.signedArea() > 0 } == 1 &&
                ring.normalized().contours.count { it.signedArea() < 0 } == 1,
        )
        check(
            "圆环的孔真的空：圆心不在区域内，r=3.5 在",
            !ring.contains(0.0, 0.0) && ring.contains(0.0, 3.5),
        )
        check(
            "圆环包围盒 = [-4,-4]~[4,4]（孔不撑大包围盒）",
            boxIs(ring.bounds(), -4.0, -4.0, 4.0, 4.0, 1e-12),
            "实际 ${ring.bounds()}",
        )
    }

    // ────────────────────────── ② 布尔 ──────────────────────────

    private fun checkBoolean() {
        section("② 布尔：减法**真的挖掉了** / 并 / 交 / 薄壁")

        val outer = StudioCadFeature.Circle("outer", 4.0, 64, Vec2(0.0, 0.0))
        val hole = StudioCadFeature.Circle("hole", 1.0, 16, Vec2(0.0, 0.0))
        val outerArea = polygonCircleArea(4.0, 64)
        val holeArea = polygonCircleArea(1.0, 16)

        val cut = profile("dial") {
            listOf(outer, hole, StudioCadFeature.Bool("dial", StudioCadBoolean.Op.SUBTRACT, "outer", "hole"))
        }
        println("  减：${outerArea} − ${holeArea} = ${outerArea - holeArea}，实际 ${cut.area()}，环数 ${cut.contours.size}")
        check(
            "圆 r4 减同心圆 r1：面积 = A(4,64) − A(1,16)（两侧都是内接正多边形 ⇒ 可精确对）",
            near(cut.area(), outerArea - holeArea, EPS),
            "实际 ${cut.area()} / 期望 ${outerArea - holeArea}",
        )
        check(
            "减法结果有 2 条环：外环 + 孔（孔的有向面积为负）",
            cut.normalized().contours.size == 2 && cut.normalized().contours.count { it.signedArea() < 0 } == 1,
            "环数 ${cut.normalized().contours.size}，有向面积 ${cut.normalized().contours.map { it.signedArea() }}",
        )
        check("★ 挖掉的位置**真的空**：孔心 (0,0) 不在结果里，(2.5,0) 还在", !cut.contains(0.0, 0.0) && cut.contains(2.5, 0.0))
        check("孔壁两侧都对：r=0.9 是空的，r=1.1 是实体", !cut.contains(0.9, 0.0) && cut.contains(1.1, 0.0))
        check(
            "减法结果的包围盒仍是最外圈 [-4,-4]~[4,4]",
            boxIs(cut.bounds(), -4.0, -4.0, 4.0, 4.0, 1e-12),
            "实际 ${cut.bounds()}",
        )

        val a = StudioCadFeature.Rect("a", 4.0, 4.0, Vec2(0.0, 0.0))
        val b = StudioCadFeature.Rect("b", 4.0, 4.0, Vec2(2.0, 0.0))

        val union = profile("u") { listOf(a, b, StudioCadFeature.Bool("u", StudioCadBoolean.Op.UNION, "a", "b")) }
        check("并：两个 4×4 错开 2 ⇒ 面积 32 − 8 = 24", near(union.area(), 24.0, EPS), "实际 ${union.area()}")
        check(
            "并的包围盒 = [-2,-2]~[4,2]，两侧的点都在；结果是 1 条环（重叠被合并）",
            boxIs(union.bounds(), -2.0, -2.0, 4.0, 2.0, 1e-12) &&
                union.contains(-1.5, 0.0) && union.contains(3.5, 0.0) &&
                union.normalized().contours.size == 1,
            "包围盒 ${union.bounds()}，环数 ${union.normalized().contours.size}",
        )

        val inter = profile("i") { listOf(a, b, StudioCadFeature.Bool("i", StudioCadBoolean.Op.INTERSECT, "a", "b")) }
        check(
            "交：重叠区 = 2×4 = 8，包围盒 [0,-2]~[2,2]",
            near(inter.area(), 8.0, EPS) && boxIs(inter.bounds(), 0.0, -2.0, 2.0, 2.0, 1e-12),
            "面积 ${inter.area()}，包围盒 ${inter.bounds()}",
        )
        check(
            "交：只在重叠区里有料（(1,0) 在，(-1,0) 与 (3,0) 都不在）",
            inter.contains(1.0, 0.0) && !inter.contains(-1.0, 0.0) && !inter.contains(3.0, 0.0),
        )

        val far = profile("both") {
            listOf(
                StudioCadFeature.Circle("c1", 1.0, 32, Vec2(-5.0, 0.0)),
                StudioCadFeature.Circle("c2", 1.0, 32, Vec2(5.0, 0.0)),
                StudioCadFeature.Bool("both", StudioCadBoolean.Op.UNION, "c1", "c2"),
            )
        }
        check(
            "并（两块互不相邻）：2 条外环、面积 = 两者之和、两块都在",
            far.normalized().contours.size == 2 &&
                near(far.area(), 2 * polygonCircleArea(1.0, 32), EPS) &&
                far.contains(-5.0, 0.0) && far.contains(5.0, 0.0),
            "环数 ${far.normalized().contours.size}，面积 ${far.area()}",
        )

        val gone = profile("gone") {
            listOf(outer, hole, StudioCadFeature.Bool("gone", StudioCadBoolean.Op.SUBTRACT, "hole", "outer"))
        }
        check("减光时给出**空轮廓**（不崩、也不留半块）", gone.isEmpty, "环数 ${gone.contours.size}")
        check("空轮廓的面积是 0", near(gone.area(), 0.0, EPS))

        // 2D 布尔的看家本领：薄壁不会翻车（3D 网格布尔在这里必炸）
        val thin = profile("shell") {
            listOf(
                outer,
                StudioCadFeature.Circle("thinHole", 3.98, 64, Vec2(0.0, 0.0)),
                StudioCadFeature.Bool("shell", StudioCadBoolean.Op.SUBTRACT, "outer", "thinHole"),
            )
        }
        val thinExpected = polygonCircleArea(4.0, 64) - polygonCircleArea(3.98, 64)
        println("  薄壁（壁厚 0.02 格，段长约 0.39）：面积 ${thin.area()}，期望 $thinExpected")
        check(
            "★ 薄壁（壁厚 0.02 格）仍然算得出来、仍是 2 条环、面积与解析值一致",
            thin.normalized().contours.size == 2 && near(thin.area(), thinExpected, EPS),
            "环数 ${thin.normalized().contours.size}，面积 ${thin.area()} / 期望 $thinExpected",
        )
    }

    // ────────────────────────── ③ 阵列 ──────────────────────────

    private fun checkArray() {
        section("③ 阵列：数量 / 均布角度 / sweepDeg 不满 360°")

        val spoke = StudioCadFeature.Circle("spoke", 0.4, 12, Vec2(0.0, 0.0))
        val full = profile("ring12") {
            listOf(spoke, StudioCadFeature.CircArray("ring12", "spoke", 12, 3.0, "z", 0.0, 360.0))
        }
        check("12 个副本 ⇒ 12 条环", full.contours.size == 12, "实际 ${full.contours.size}")

        val centers = full.contours.map { centroid(it) }
        check(
            "每个副本的中心都落在半径 3 的圆上",
            centers.all { near(sqrt(it.x * it.x + it.y * it.y), 3.0, 1e-9) },
            "半径 ${centers.map { round4(sqrt(it.x * it.x + it.y * it.y)) }}",
        )
        val angles = centers.map { normDeg(Math.toDegrees(atan2(it.y, it.x))) }.sorted()
        check(
            "12 个副本均布在 0/30/…/330°（右端不闭合 ⇒ 不会首尾重合）",
            angles.size == 12 && angles.indices.all { near(angles[it], it * 30.0, 1e-6) },
            "实际 ${angles.map { round4(it) }}",
        )
        check(
            "阵列面积 = 12 × 单个面积（副本不重叠时按奇偶规则就是相加）",
            near(full.area(), 12 * polygonCircleArea(0.4, 12), EPS),
            "实际 ${full.area()}",
        )

        val partial = profile("arc") {
            listOf(spoke, StudioCadFeature.CircArray("arc", "spoke", 3, 3.0, "z", 10.0, 180.0))
        }
        val partialAngles = partial.contours
            .map { normDeg(Math.toDegrees(atan2(centroid(it).y, centroid(it).x))) }
            .sorted()
        check(
            "sweepDeg=180 / count=3 / startDeg=10 ⇒ 步距 = 180÷3 = 60：10/70/130°",
            partialAngles.size == 3 && partialAngles.indices.all { near(partialAngles[it], 10.0 + 60.0 * it, 1e-6) },
            "实际 ${partialAngles.map { round4(it) }}",
        )

        val single = profile("one") {
            listOf(spoke, StudioCadFeature.CircArray("one", "spoke", 1, 0.0, "z", 45.0, 360.0))
        }
        check(
            "count=1 / radius=0 ⇒ 原样一份（面积与单圆一致）",
            single.contours.size == 1 && near(single.area(), polygonCircleArea(0.4, 12), EPS),
            "环数 ${single.contours.size}，面积 ${single.area()}",
        )

        val dial = profile("dial") {
            listOf(
                StudioCadFeature.Circle("plate", 4.0, 64, Vec2(0.0, 0.0)),
                spoke,
                StudioCadFeature.CircArray("holes", "spoke", 12, 3.0, "z", 0.0, 360.0),
                StudioCadFeature.Bool("dial", StudioCadBoolean.Op.SUBTRACT, "plate", "holes"),
            )
        }
        val expected = polygonCircleArea(4.0, 64) - 12 * polygonCircleArea(0.4, 12)
        println("  阵列减：期望 $expected，实际 ${dial.area()}，环数 ${dial.normalized().contours.size}")
        check(
            "★ 圆盘减 12 个阵列孔：面积 = A(盘) − 12·A(孔)，结果 13 条环（外 + 12 孔）",
            near(dial.area(), expected, EPS) && dial.normalized().contours.size == 13,
            "面积 ${dial.area()}（期望 $expected），环数 ${dial.normalized().contours.size}",
        )
        check("★ 12 个孔位**都真的空了**（逐个采样孔心）", centers.all { !dial.contains(it.x, it.y) })
        check(
            "孔与孔之间仍是实体（半径 3 上、两孔正中间那一点）",
            dial.contains(3.0 * cos(Math.toRadians(15.0)), 3.0 * sin(Math.toRadians(15.0))),
        )

        val axisX = runCatching {
            mesh(
                listOf(
                    spoke,
                    StudioCadFeature.CircArray("bad", "spoke", 3, 3.0, "x", 0.0, 360.0),
                    StudioCadFeature.Extrude("e", "bad", 1.0, true, null),
                )
            )
        }.exceptionOrNull()
        check(
            "axis=\"x\" 明确报「没实现」（不悄悄按 z 算）",
            axisX is StudioCadException,
            "实际 ${axisX?.let { it::class.simpleName + ": " + it.message }}",
        )
    }

    // ────────────────────────── ④ 挤出 ──────────────────────────

    private fun checkExtrude() {
        section("④ 挤出：厚度 / centerZ / **闭合体**（每条边恰好两个面）")

        val square = mesh(
            listOf(
                StudioCadFeature.Rect("sq", 4.0, 4.0, Vec2(0.0, 0.0)),
                StudioCadFeature.Extrude("body", "sq", 1.0, true, "frame"),
            )
        )
        println("  $square")
        check(
            "centerZ=true ⇒ Z ∈ [−0.5, +0.5]（以 Z=0 为中心）",
            near(square.bounds.minZ.toDouble(), -0.5, 1e-6) && near(square.bounds.maxZ.toDouble(), 0.5, 1e-6),
            "实际 [${square.bounds.minZ}, ${square.bounds.maxZ}]",
        )
        check(
            "厚度 = 1：sizeZ = 1，X/Y 仍是 4×4",
            near(square.bounds.sizeZ.toDouble(), 1.0, 1e-6) &&
                near(square.bounds.sizeX.toDouble(), 4.0, 1e-6) &&
                near(square.bounds.sizeY.toDouble(), 4.0, 1e-6),
            "实际 ${square.bounds}",
        )
        check("组名 = extrude 的 name（「齿轮单独转」就靠它）", square.groupNames == listOf("body"), "实际 ${square.groupNames}")
        check("材质写进了组", square.group("body")?.material == "frame", "实际 ${square.group("body")?.material}")
        checkClosed("立方体（4×4×1）", square)

        val flat = mesh(
            listOf(
                StudioCadFeature.Rect("sq", 4.0, 4.0, Vec2(0.0, 0.0)),
                StudioCadFeature.Extrude("body", "sq", 2.0, false, null),
            )
        )
        check(
            "centerZ=false ⇒ Z ∈ [0, 2]（从 Z=0 沿 +Z 长出去）",
            near(flat.bounds.minZ.toDouble(), 0.0, 1e-6) && near(flat.bounds.maxZ.toDouble(), 2.0, 1e-6),
            "实际 [${flat.bounds.minZ}, ${flat.bounds.maxZ}]",
        )
        checkClosed("立方体（centerZ=false）", flat)

        val centerHole = mesh(
            listOf(
                StudioCadFeature.Circle("plate", 4.0, 64, Vec2(0.0, 0.0)),
                StudioCadFeature.Circle("hole", 0.8, 32, Vec2(0.0, 0.0)),
                StudioCadFeature.Bool("hollowed", StudioCadBoolean.Op.SUBTRACT, "plate", "hole"),
                StudioCadFeature.Extrude("dial_body", "hollowed", 1.0, true, "frame"),
            )
        )
        println("  $centerHole")
        check(
            "镂空盘：组名 dial_body，三角形数在合理区间（几百~几千，不是几万）",
            centerHole.groupNames == listOf("dial_body") && centerHole.triangleCount in 200..8000,
            "实际 ${centerHole.triangleCount} 面，组 ${centerHole.groupNames}",
        )
        checkClosed("镂空盘（1 个中心孔）", centerHole)

        val spoked = mesh(
            listOf(
                StudioCadFeature.Circle("plate", 4.0, 64, Vec2(0.0, 0.0)),
                StudioCadFeature.Circle("spoke", 0.35, 12, Vec2(0.0, 0.0)),
                StudioCadFeature.CircArray("holes", "spoke", 12, 3.0, "z", 0.0, 360.0),
                StudioCadFeature.Bool("hollowed", StudioCadBoolean.Op.SUBTRACT, "plate", "holes"),
                StudioCadFeature.Extrude("dial_body", "hollowed", 1.0, true, "frame"),
            )
        )
        println("  $spoked")
        checkClosed("★ 辐条盘（12 个阵列孔，最接近真表盘的那个形状）", spoked)

        // 盖子的朝向：+Z 与 −Z 的投影面积必须相等，且都等于轮廓面积
        val caps = capAreas(spoked)
        val profileArea = polygonCircleArea(4.0, 64) - 12 * polygonCircleArea(0.35, 12)
        check(
            "顶盖（+Z）与底盖（−Z）的投影面积相等、都等于轮廓面积（朝向没搞反）",
            near(caps.up, profileArea, 0.05) && near(caps.down, profileArea, 0.05),
            "上 ${round4(caps.up)} / 下 ${round4(caps.down)} / 轮廓 $profileArea",
        )
        check(
            "上盖与下盖三角形数相同；盖 + 侧 = 总数（没有多出来或丢掉的片）",
            caps.upTriangles == caps.downTriangles &&
                caps.upTriangles + caps.downTriangles + sideTriangles(spoked) == spoked.triangleCount,
            "上盖 ${caps.upTriangles} / 下盖 ${caps.downTriangles} / 侧 ${sideTriangles(spoked)} / 总 ${spoked.triangleCount}",
        )

        val two = mesh(
            listOf(
                StudioCadFeature.Rect("a", 2.0, 2.0, Vec2(0.0, 0.0)),
                StudioCadFeature.Extrude("gear", "a", 1.0, true, null),
                StudioCadFeature.Rect("b", 2.0, 2.0, Vec2(5.0, 0.0)),
                StudioCadFeature.Extrude("hand", "b", 1.0, true, null),
            )
        )
        check(
            "两个 extrude ⇒ 两个组（按组名各自叠矩阵，零新机制）",
            two.groupNames == listOf("gear", "hand"),
            "实际 ${two.groupNames}",
        )
        checkClosed("两个不相邻的方块（两个组都在一张网格里）", two)

        val result = evaluate(
            listOf(
                StudioCadFeature.Rect("a", 2.0, 2.0, Vec2(0.0, 0.0)),
                StudioCadFeature.Circle("never_used", 1.0, 16, Vec2(0.0, 0.0)),
                StudioCadFeature.Extrude("body", "a", 1.0, true, null),
            )
        )
        check(
            "没被 extrude 用到的 2D 特征：不产几何，但出现在 unusedFeatures 里（可观测）",
            result.unusedFeatures == listOf("never_used") && result.groupNames == listOf("body"),
            "unused=${result.unusedFeatures}，组=${result.groupNames}",
        )
    }

    // ────────────────────────── ⑤ 确定性 ──────────────────────────

    private fun checkDeterminism() {
        section("⑤ 确定性：同一份输入两次求值，结果**逐位相同**（reload 会反复调用它）")

        val features = listOf(
            StudioCadFeature.Circle("plate", 4.0, 64, Vec2(0.0, 0.0)),
            StudioCadFeature.Circle("hole", 1.0, 16, Vec2(1.5, 0.5)),
            StudioCadFeature.Bool("cut", StudioCadBoolean.Op.SUBTRACT, "plate", "hole"),
            StudioCadFeature.Circle("pin", 0.3, 12, Vec2(0.0, 0.0)),
            StudioCadFeature.CircArray("pins", "pin", 7, 2.5, "z", 15.0, 300.0),
            StudioCadFeature.Bool("final", StudioCadBoolean.Op.UNION, "cut", "pins"),
            StudioCadFeature.Extrude("body", "final", 1.0, true, null),
        )
        val first = mesh(features)
        val second = mesh(features)
        check(
            "两次求值：三角形数、顶点表、索引数组完全相同",
            first.triangleCount == second.triangleCount &&
                first.vertices == second.vertices &&
                first.indices.contentEquals(second.indices),
            "面 ${first.triangleCount}/${second.triangleCount}，顶点 ${first.vertices.size}/${second.vertices.size}",
        )
        check("两次求值：包围盒逐位相同", first.bounds.toString() == second.bounds.toString())
        checkClosed("非对称轮廓（7 个引脚绕 300° 阵列）的闭合体", first)
    }

    // ────────────────────────── ⑥ 参数校验 ──────────────────────────

    private fun checkValidation() {
        section("⑥ 参数校验：非法输入**明确报错**，且不抛未捕获异常（载入期拒绝，不静默削面）")

        refuse("圆 r=0", """[{"type":"circle","name":"a","r":0}]""")
        refuse("圆 r=-3", """[{"type":"circle","name":"a","r":-3}]""")
        refuse("矩形 w=0", """[{"type":"rect","name":"a","w":0,"h":2}]""")
        refuse("矩形 h=-1", """[{"type":"rect","name":"a","w":2,"h":-1}]""")
        refuse("多边形只有 2 个点", """[{"type":"polygon","name":"a","points":[[0,0],[1,1]]}]""")
        refuse("圆环 rInner > rOuter", """[{"type":"ring","name":"a","rOuter":2,"rInner":3}]""")
        refuse("圆环 rInner = rOuter（零壁厚）", """[{"type":"ring","name":"a","rOuter":2,"rInner":2}]""")
        refuse("segments 太小（2）", """[{"type":"circle","name":"a","r":1,"segments":2}]""")
        refuse("segments 太大（9999，会撞三角形上限）", """[{"type":"circle","name":"a","r":1,"segments":9999}]""")
        refuse("array count=0", """[{"type":"circle","name":"s","r":1},{"type":"array","name":"a","of":"s","count":0,"radius":1},{"type":"extrude","name":"e","of":"a","thickness":1}]""")
        refuse("array count=-2", """[{"type":"circle","name":"s","r":1},{"type":"array","name":"a","of":"s","count":-2,"radius":1},{"type":"extrude","name":"e","of":"a","thickness":1}]""")
        refuse("array radius 为负", """[{"type":"circle","name":"s","r":1},{"type":"array","name":"a","of":"s","count":2,"radius":-1},{"type":"extrude","name":"e","of":"a","thickness":1}]""")
        refuse("array sweepDeg=0", """[{"type":"circle","name":"s","r":1},{"type":"array","name":"a","of":"s","count":2,"radius":1,"sweepDeg":0},{"type":"extrude","name":"e","of":"a","thickness":1}]""")
        refuse("extrude thickness=0", """[{"type":"circle","name":"a","r":1},{"type":"extrude","name":"e","of":"a","thickness":0}]""")
        refuse("extrude thickness=-1", """[{"type":"circle","name":"a","r":1},{"type":"extrude","name":"e","of":"a","thickness":-1}]""")

        refuse("引用不存在的名字", """[{"type":"circle","name":"a","r":1},{"type":"extrude","name":"e","of":"nope","thickness":1}]""")
        refuse("引用自己", """[{"type":"circle","name":"a","r":1},{"type":"extrude","name":"e","of":"e","thickness":1}]""")
        refuse("引用后面的特征（顺序错位）", """[{"type":"circle","name":"a","r":1},{"type":"extrude","name":"e","of":"later","thickness":1},{"type":"circle","name":"later","r":1}]""")
        refuse("成环引用 a→b→a", """[{"type":"subtract","name":"a","a":"b","b":"c"},{"type":"subtract","name":"b","a":"a","b":"c"},{"type":"circle","name":"c","r":1}]""")
        refuse("extrude 引用另一个 extrude（3D 布尔没做）", """[{"type":"circle","name":"a","r":1},{"type":"extrude","name":"e1","of":"a","thickness":1},{"type":"extrude","name":"e2","of":"e1","thickness":1}]""")
        refuse("布尔操作数是 3D 的", """[{"type":"circle","name":"a","r":1},{"type":"circle","name":"b","r":1},{"type":"extrude","name":"e1","of":"a","thickness":1},{"type":"subtract","name":"c","a":"e1","b":"b"}]""")
        refuse("特征名重复", """[{"type":"circle","name":"a","r":1},{"type":"circle","name":"a","r":2}]""")
        refuse("特征名是空的", """[{"type":"circle","name":"","r":1}]""")
        refuse("extrude 的是空轮廓（先减光再挤）", """[{"type":"circle","name":"a","r":1},{"type":"circle","name":"b","r":2},{"type":"subtract","name":"c","a":"a","b":"b"},{"type":"extrude","name":"e","of":"c","thickness":1}]""")
        refuse("一条 extrude 都没有（2D 轮廓不产几何）", """[{"type":"circle","name":"a","r":1}]""")

        refuseJson("type 不认识（sphere）", """[{"type":"sphere","name":"a","r":1}]""")
        refuseJson("缺 type", """[{"name":"a","r":1}]""")
        refuseJson("缺 name", """[{"type":"circle","r":1}]""")
        refuseJson("circle 缺 r", """[{"type":"circle","name":"a"}]""")
        refuseJson("extrude 缺 thickness", """[{"type":"circle","name":"a","r":1},{"type":"extrude","name":"e","of":"a"}]""")
        refuseJson("array 缺 count", """[{"type":"circle","name":"a","r":1},{"type":"array","name":"x","of":"a","radius":1}]""")
        refuseJson("r 是字符串（类型不对）", """[{"type":"circle","name":"a","r":"4"}]""")
        refuseJson("at 不是长度 2 的数组", """[{"type":"circle","name":"a","r":1,"at":[1,2,3]}]""")
        refuseJson("segments 不是整数", """[{"type":"circle","name":"a","r":1,"segments":"64"}]""")
        refuseJson("centerZ 不是布尔", """[{"type":"circle","name":"a","r":1},{"type":"extrude","name":"e","of":"a","thickness":1,"centerZ":"yes"}]""")
        refuseJson("features 不是数组", """{"type":"circle"}""")
        refuseJson("空数组", """[]""")
        refuseJson("features[i] 不是对象", """[42]""")
    }

    // ────────────────────────── ⑦ 随包发布的 dial.json ──────────────────────────

    private fun checkShippedDial() {
        section("⑦ 随包发布的 dial.json：整条链路（JSON 原文 → 特征 → 网格 → OBJ）")

        val file = File(DIAL_JSON)
        if (!file.isFile) {
            fail("找不到 $DIAL_JSON（在仓库根目录下跑）—— mod 要把这份文件释放到 config/gtetstudio/")
            return
        }
        val json = parseJsonFile(file)
        val featureArray = json.get("features")
        if (featureArray == null) {
            fail("dial.json 里没有 features 字段")
            return
        }
        println("  dial.json：id=${json.get("id")?.asString}，features ${featureArray.asJsonArray.size()} 条")

        val features = try {
            StudioCadFeatureJson.parse(featureArray, file.name)
        } catch (e: Exception) {
            fail("dial.json 的 features 解析失败：${e.message}")
            return
        }
        check("features 解析出 ${features.size} 条：${features.map { it.type }}", features.isNotEmpty())

        // ── 元数据自查：这些字段游戏里的载入器才读，但写错了当场就炸，所以在这里先钉住 ──
        check("id = gtet:test_dial", json.get("id")?.asString == "gtet:test_dial", "实际 ${json.get("id")}")
        check(
            "有 features 时**不写** source.file / source.model（写了会被忽略，只会让人困惑）",
            json.get("source")?.takeIf { it.isJsonObject }?.asJsonObject?.let { !it.has("file") && !it.has("model") } ?: true,
        )

        val builder = StudioMeshBuilder(file.name)
        val result = try {
            StudioCadEvaluator.evaluate(features, builder, file.name)
        } catch (e: Exception) {
            fail("dial.json 求值失败：$e")
            return
        }
        val mesh = builder.build()
        println("  $mesh")
        println("  各组：${result.trianglesByGroup.map { "${it.key} = ${it.value} 面" }}")

        check("只产出一个组，组名 = extrude 的 name", result.groupNames.size == 1, "实际 ${result.groupNames}")
        check(
            "三角形数在预算内（§7.1 上限 20 万，这里应当只有几千）",
            mesh.triangleCount in 100..20_000,
            "实际 ${mesh.triangleCount}",
        )
        checkClosed("dial.json 的实体", mesh)
        check(
            "dial.json 的尺寸：外圆 4.5 ⇒ 包围盒 9×9，厚度 1",
            near(mesh.bounds.sizeX.toDouble(), 9.0, 0.05) &&
                near(mesh.bounds.sizeY.toDouble(), 9.0, 0.05) &&
                near(mesh.bounds.sizeZ.toDouble(), 1.0, 1e-6),
            "实际 ${mesh.bounds}",
        )

        // ── 与 JSON 自述的 parts / materials / animation 对得上（写错了游戏里必报错）──
        val partNames = mesh.groupNames.toSet()
        json.getAsJsonArray("parts")?.forEach { el ->
            val name = el.asJsonObject.get("name").asString
            check("parts 里的「$name」在网格里真的存在", name in partNames, "可用组名 $partNames")
        }
        val declared = json.getAsJsonObject("materials")?.keySet() ?: emptySet()
        val used = mesh.groups.mapNotNull { it.material }.toSortedSet()
        check("extrude 用到的材质 $used 都在 materials 段里声明了", used.all { it in declared }, "已声明 $declared")
        json.getAsJsonObject("materials")?.entrySet()?.forEach { (name, el) ->
            val tex = el.asJsonObject.get("texture")?.asString
            check(
                "materials.$name.texture 是资源路径（CAD 路径没有来源文件，相对路径无从解析）",
                tex == null || tex.contains(':'),
                "实际 $tex",
            )
        }
        json.getAsJsonObject("animation")?.getAsJsonArray("tracks")?.forEach { el ->
            val part = el.asJsonObject.get("part").asString
            check("animation 的部件「$part」在网格里存在", part in partNames, "可用组名 $partNames")
        }

        // ── ★ 导出 OBJ：不开游戏也能在 Blender 里看形状 ──
        exportObj(mesh, "$OUT_DIR/dial.obj", "dial.json（镂空表盘：外圆 − 中心孔 − 12 辐条孔，挤出 1 格）")

        val bracket = mesh(
            listOf(
                StudioCadFeature.Rect("plate", 8.0, 6.0, Vec2(0.0, 0.0)),
                StudioCadFeature.Circle("roundHole", 1.5, 48, Vec2(-2.0, 0.0)),
                StudioCadFeature.Rect("slot", 3.0, 1.0, Vec2(2.0, 0.0)),
                StudioCadFeature.Bool("step1", StudioCadBoolean.Op.SUBTRACT, "plate", "roundHole"),
                StudioCadFeature.Bool("step2", StudioCadBoolean.Op.SUBTRACT, "step1", "slot"),
                StudioCadFeature.Extrude("bracket", "step2", 0.6, true, null),
            )
        )
        checkClosed("布尔减法示例（8×6 板 − r1.5 圆孔 − 3×1 腰形槽）", bracket)
        check(
            "减法示例的包围盒仍是 8×6×0.6（孔与槽都不撑大外包）",
            near(bracket.bounds.sizeX.toDouble(), 8.0, 1e-6) &&
                near(bracket.bounds.sizeY.toDouble(), 6.0, 1e-6) &&
                near(bracket.bounds.sizeZ.toDouble(), 0.6, 1e-6),
            "实际 ${bracket.bounds}",
        )
        exportObj(bracket, "$OUT_DIR/boolean_subtract.obj", "布尔减法示例：8×6 板 − r1.5 圆孔 − 3×1 腰形槽，挤出 0.6 格")

        // 纯 2D 布尔结果也导一份（Blender 里能直接看镂空对不对）
        val thinPlate = mesh(
            listOf(
                StudioCadFeature.Circle("plate", 4.0, 64, Vec2(0.0, 0.0)),
                StudioCadFeature.Circle("thinHole", 3.9, 64, Vec2(0.0, 0.0)),
                StudioCadFeature.Bool("ring", StudioCadBoolean.Op.SUBTRACT, "plate", "thinHole"),
                StudioCadFeature.Extrude("shell", "ring", 0.5, true, null),
            )
        )
        checkClosed("薄壁环（壁厚 0.1 格 = 段长的 1/4）也是闭合体 —— 3D 网格布尔最容易在这里炸", thinPlate)
        exportObj(thinPlate, "$OUT_DIR/thin_wall.obj", "薄壁环：r4 圆盘 − r3.9 圆孔，壁厚 0.1 格（M3a 的看家本领）")

        // ── ★ 改一个参数 ⇒ 形状真的跟着变（这就是"改 r / thickness → reload → 形状跟着变"的自检版）──
        val widened = mesh(features.map { f ->
            if (f is StudioCadFeature.Circle && f.name == "outline") f.copy(r = f.r * 1.5) else f
        })
        check(
            "★ outline.r × 1.5 ⇒ 包围盒按比例变大（改参数真的重建了形状）",
            near(widened.bounds.sizeX.toDouble() / mesh.bounds.sizeX.toDouble(), 1.5, 0.01),
            "原始 ${mesh.bounds.sizeX} → 改后 ${widened.bounds.sizeX}",
        )
        val thicker = mesh(features.map { f ->
            if (f is StudioCadFeature.Extrude) f.copy(thickness = 3.0) else f
        })
        check(
            "★ extrude.thickness → 3 ⇒ 厚度变成 3，X/Y 一点没动",
            near(thicker.bounds.sizeZ.toDouble(), 3.0, 1e-6) &&
                near(thicker.bounds.sizeX.toDouble(), mesh.bounds.sizeX.toDouble(), 1e-6),
            "厚度 ${thicker.bounds.sizeZ}，X ${thicker.bounds.sizeX}",
        )

        println()
        println("  ★ 导出目录：$OUT_DIR/（build/ 已被 gitignore，随便生成）")
        println("     · dial.obj              —— dial.json 的结果（镂空表盘）")
        println("     · boolean_subtract.obj  —— 纯布尔减法示例")
        println("     · thin_wall.obj         —— 薄壁环（3D 网格布尔的翻车点）")
        println("     用 Blender / 任意 OBJ 查看器打开即可，**不用开游戏**")
    }

    // ────────────────────────── 工具 ──────────────────────────

    /** 求"这批特征里最后一条 2D 特征"的轮廓（走生产路径同一套代码）。 */
    private fun profile(last2dName: String, build: () -> List<StudioCadFeature>): Profile =
        StudioCadEvaluator.profiles(build(), "selfcheck").getValue(last2dName)

    private fun mesh(features: List<StudioCadFeature>): StudioMesh {
        val builder = StudioMeshBuilder("selfcheck")
        StudioCadEvaluator.evaluate(features, builder, "selfcheck")
        return builder.build()
    }

    private fun evaluate(features: List<StudioCadFeature>): StudioCadEvaluator.Result {
        val builder = StudioMeshBuilder("selfcheck")
        return StudioCadEvaluator.evaluate(features, builder, "selfcheck")
    }

    /**
     * 闭合体自检：每条（按**位置**去重的）无向边恰好被两个三角形用到。
     *
     * 这是"这真是一个实体"的判据 —— 有洞的壳、T 形接缝、翻面的片子都会在这里现形。
     * 按位置而不是按下标取键，是因为同一个位置在不同面上允许各留一份顶点（法线/UV 不同）。
     */
    private fun checkClosed(what: String, mesh: StudioMesh) {
        val counts = HashMap<String, Int>()
        var degenerate = 0
        for (i in 0 until mesh.indices.size step 3) {
            val a = mesh.vertices[mesh.indices[i]]
            val b = mesh.vertices[mesh.indices[i + 1]]
            val c = mesh.vertices[mesh.indices[i + 2]]
            if (samePoint(a, b) || samePoint(b, c) || samePoint(c, a)) {
                degenerate++
                continue
            }
            counts.merge(edgeKey(a, b), 1, Int::plus)
            counts.merge(edgeKey(b, c), 1, Int::plus)
            counts.merge(edgeKey(c, a), 1, Int::plus)
        }
        val bad = counts.entries.filter { it.value != 2 }
        check(
            "$what：闭合体（每条边恰好两个面，无 T 形接缝 / 无退化三角形）",
            bad.isEmpty() && degenerate == 0,
            "不合规的边 ${bad.size} 条（例：${bad.take(2).map { "${it.key}×${it.value}" }}），退化三角形 $degenerate 个",
        )
    }

    /** 盖子三角形数与 +Z/−Z 投影面积。 */
    private fun capAreas(mesh: StudioMesh): CapInfo {
        var up = 0.0
        var down = 0.0
        var upCount = 0
        var downCount = 0
        for (i in 0 until mesh.indices.size step 3) {
            val a = mesh.vertices[mesh.indices[i]]
            val b = mesh.vertices[mesh.indices[i + 1]]
            val c = mesh.vertices[mesh.indices[i + 2]]
            when {
                a.nz > 0f -> {
                    upCount++
                    up += ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2.0
                }

                a.nz < 0f -> {
                    downCount++
                    // 底盖的绕序是反的（法线朝 −Z），投影面积取绝对值才是"面积"
                    down += -((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2.0
                }
            }
        }
        return CapInfo(up, down, upCount, downCount)
    }

    private class CapInfo(
        val up: Double,
        val down: Double,
        val upTriangles: Int,
        val downTriangles: Int,
    )

    /** 侧面三角形数（法线落在 XY 平面内，即 nz = 0）。 */
    private fun sideTriangles(mesh: StudioMesh): Int {
        var count = 0
        for (i in 0 until mesh.indices.size step 3) {
            if (mesh.vertices[mesh.indices[i]].nz == 0f) count++
        }
        return count
    }

    private fun samePoint(a: StudioVertex, b: StudioVertex): Boolean =
        a.x == b.x && a.y == b.y && a.z == b.z

    /** 无向边按位置取键（顶点表允许同一位置出现多次，接缝看的是几何而不是下标）。 */
    private fun edgeKey(a: StudioVertex, b: StudioVertex): String {
        val ka = "${a.x.toRawBits()},${a.y.toRawBits()},${a.z.toRawBits()}"
        val kb = "${b.x.toRawBits()},${b.y.toRawBits()},${b.z.toRawBits()}"
        return if (ka < kb) "$ka|$kb" else "$kb|$ka"
    }

    /**
     * 把一个网格导出成 OBJ（`v` / `vt` / `vn` / `g` / `f`）。
     *
     * 为什么要做：**形状对不对，看得见才算数**。写到 `build/`（已被 gitignore），
     * 不开游戏就能用 Blender 打开 —— 这是本次最省事的验收手段。
     */
    private fun exportObj(mesh: StudioMesh, path: String, comment: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val sb = StringBuilder(mesh.vertices.size * 64)
        sb.append("# studio M3a 导出自检\n# $comment\n")
        sb.append("# 组：${mesh.groupNames}，三角形 ${mesh.triangleCount}\n")
        sb.append("# 坐标系：表盘在 XY 平面、+Z 是正面、1 单位 = 1 格\n")
        for (v in mesh.vertices) sb.append("v ${v.x} ${v.y} ${v.z}\n")
        for (v in mesh.vertices) sb.append("vt ${v.u} ${v.v}\n")
        for (v in mesh.vertices) sb.append("vn ${v.nx} ${v.ny} ${v.nz}\n")
        for (group in mesh.groups) {
            sb.append("g ${group.name}\n")
            var i = group.firstIndex
            val end = group.firstIndex + group.indexCount
            while (i < end) {
                val a = mesh.indices[i] + 1
                val b = mesh.indices[i + 1] + 1
                val c = mesh.indices[i + 2] + 1
                sb.append("f $a/$a/$a $b/$b/$b $c/$c/$c\n")
                i += 3
            }
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun parseJsonFile(file: File): JsonObject {
        val text = stripJsonComments(file.readText(Charsets.UTF_8))
        val element = JsonParser.parseString(text)
        if (!element.isJsonObject) throw IllegalStateException("${file.name} 的根节点不是对象")
        return element.asJsonObject
    }

    /** 一条"应当被拒"的用例：schema **或**求值必须报 [StudioCadException]（不许别的异常、更不许静默通过）。 */
    private fun refuse(what: String, featuresJson: String) {
        val outcome = runCatching {
            mesh(StudioCadFeatureJson.parse(JsonParser.parseString(featuresJson), "selftest.json"))
        }
        val error = outcome.exceptionOrNull()
        check(
            "拒绝：$what",
            error is StudioCadException,
            if (error == null) "**没有报错**（静默通过了）" else "抛的是 ${error::class.simpleName}：${error.message}",
        )
    }

    /** 只验 schema 层（连求值都不该走到）。 */
    private fun refuseJson(what: String, featuresJson: String) {
        val outcome = runCatching {
            StudioCadFeatureJson.parse(JsonParser.parseString(featuresJson), "selftest.json")
        }
        val error = outcome.exceptionOrNull()
        check(
            "拒绝（schema）：$what",
            error is StudioCadException,
            if (error == null) "**没有报错**（静默通过了）" else "抛的是 ${error::class.simpleName}：${error.message}",
        )
    }

    /** 折线圆（内接正 n 边形）的解析面积。 */
    private fun polygonCircleArea(r: Double, n: Int): Double = 0.5 * n * r * r * sin(2 * Math.PI / n)

    private fun centroid(c: Contour): Vec2 {
        var x = 0.0
        var y = 0.0
        for (p in c.points) {
            x += p.x
            y += p.y
        }
        return Vec2(x / c.points.size, y / c.points.size)
    }

    private fun normDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0

    private fun near(a: Double, b: Double, eps: Double): Boolean = abs(a - b) <= eps

    private fun boxIs(b: Box2, minX: Double, minY: Double, maxX: Double, maxY: Double, eps: Double): Boolean =
        near(b.minX, minX, eps) && near(b.minY, minY, eps) && near(b.maxX, maxX, eps) && near(b.maxY, maxY, eps)

    private fun section(title: String) {
        println("── $title")
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            passed++
            println("  ✓ $name")
        } else {
            failures += "$name${if (detail.isEmpty()) "" else "（$detail）"}"
            println("  ✗ $name${if (detail.isEmpty()) "" else "  ← $detail"}")
        }
    }

    private fun fail(message: String) {
        failures += message
        println("  ✗ $message")
    }
}