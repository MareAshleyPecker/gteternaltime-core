package rain.gtetcore.gtet.studio.format

import rain.gtetcore.gtet.studio.config.StudioConfig
import rain.gtetcore.gtet.studio.config.StudioLimits
import rain.gtetcore.gtet.studio.kernel.StudioMesh
import rain.gtetcore.gtet.studio.kernel.StudioMeshBuilder
import rain.gtetcore.gtet.studio.kernel.StudioMeshException
import rain.gtetcore.gtet.studio.kernel.StudioVertex
import java.io.File
import kotlin.system.exitProcess

/**
 * **脱离游戏的解析自检** —— `kernel/` 与 `format/` 是纯 Kotlin，不需要启动 Minecraft 就能验。
 *
 * 这是 M1a 那句"kernel 必须能单测"的**证据**，也是换掉 Forge 加载器之后唯一能自动验证的东西：
 * 实机渲染（模型出来没有、指针转不转）只能由人开游戏看，但"解析对不对"必须机器可验。
 *
 * ## 怎么跑
 * 项目里**没有 JUnit 依赖**（`scripts/dependencies.gradle` 里没有，而这个任务不动它），
 * 所以这里用一个 `main` 函数 + 手写断言，跑法（在仓库根目录）：
 * ```
 * .\gradlew classes
 * java -cp "build\classes\kotlin\main;<kotlin-stdlib.jar>;<gson.jar>" `
 *      rain.gtetcore.gtet.studio.format.StudioFormatSelfCheck
 * ```
 * ⚠️ **gson 必须进 classpath**：2026-09-30 起这里还顺手验 `config/` 的配置解析
 * （[StudioConfig] 用 gson 读 JSON），少了它会在那一节报 `NoClassDefFoundError`。
 * 参数可选：第一个参数是 clock.obj 的路径（默认取仓库里的 `src/main/resources/assets/gtetcore/models/obj/clock.obj`）。
 *
 * 断言失败会以退出码 1 结束（能接进脚本）。它**不在游戏里跑**，生产路径上没有任何调用点。
 *
 * @author rain fox
 */
object StudioFormatSelfCheck {

    private var passed = 0
    private val failures = ArrayList<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        val objPath = args.firstOrNull()
            ?: "src/main/resources/assets/gtetcore/models/obj/clock.obj"

        println("═══ studio kernel/format 自检（不需要启动游戏）═══")
        println("模型文件：$objPath")
        println()

        checkRegistry()
        checkClockObj(objPath)
        checkBrokenObj()
        checkFlipV()
        checkMeshValidation()
        checkConfig()

        println()
        println("═══ 结果：$passed 项通过，${failures.size} 项失败 ═══")
        for (failure in failures) println("  ✗ $failure")
        if (failures.isNotEmpty()) exitProcess(1)
    }

    // ────────────────────────── 1. 注册表 ──────────────────────────

    private fun checkRegistry() {
        section("注册表（可插拔导入器）")
        check("StudioFormats.byId(\"obj\") 是 ObjFormat", StudioFormats.byId("obj") === ObjFormat)
        check("按扩展名认领，大小写不敏感（x.OBJ → obj）", StudioFormats.forFileName("a/b/x.OBJ")?.id == "obj")
        check("未注册的扩展名返回 null（glTF 还没做，这是预期的）", StudioFormats.forFileName("x.gltf") == null)
        check("已注册的格式只有 obj", StudioFormats.ids() == setOf("obj"), "实际：${StudioFormats.ids()}")
    }

    // ────────────────────────── 2. 仓库里现成的 clock.obj ──────────────────────────

    private fun checkClockObj(objPath: String) {
        section("解析 M0 的 clock.obj（组名 / 三角形 / 包围盒 / 材质）")

        val file = File(objPath)
        if (!file.isFile) {
            fail("找不到 $objPath（在仓库根目录下跑，或用参数指定路径）")
            return
        }

        val result = try {
            ObjFormat.parse(file.readBytes(), contextFor(file, alias = emptyMap(), flipV = true))
        } catch (e: Exception) {
            fail("解析 clock.obj 抛了异常：$e")
            return
        }

        val mesh = result.mesh
        println("  读到：组名 ${mesh.groupNames}")
        println("        顶点 ${mesh.vertices.size}（去重后）/ 三角形 ${mesh.triangleCount}")
        println("        包围盒 ${mesh.bounds}")
        println("        材质 ${result.materials.map { "${it.name}→${it.texture}" }}")
        println("        问题：${result.noteSink.summary()}")
        for (note in result.notes) println("          · $note")
        println()

        check(
            "组名 = [body, hand_hour, hand_minute]",
            mesh.groupNames == listOf("body", "hand_hour", "hand_minute"),
            "实际：${mesh.groupNames}",
        )
        // M0 记录的是 v=242 f=340。**f 全是四边形**（每行 4 个顶点引用）⇒ 扇形拆分出 340×2=680 个三角形；
        // 其中 256 个是**面积为 0 的退化三角形**（该 OBJ 用 `f a b b a` 这种重复顶点的写法凑数，
        // Forge 那边同样画不出像素），丢掉之后**真正进 GPU 的是 424 个**。
        check("三角形 = 424（340 个四边形 → 680，丢掉 256 个退化）", mesh.triangleCount == 424, "实际：${mesh.triangleCount}")
        check(
            "退化三角形计数 = 256（解析器自己报出来的）",
            result.noteSink.notes().any { it.message.contains("256 个**面积为 0**") },
            "实际提示：${result.notes.map { it.message.take(40) }}",
        )
        check(
            "引用到的顶点数 = OBJ 里的 242 个（无孤立顶点）",
            referencedPositionCount(file) == 242,
            "实际：${referencedPositionCount(file)}",
        )
        check(
            "这份模型的 UV 让每个角点都不同 ⇒ 顶点表 = 三角形×3（没有共享顶点）",
            mesh.vertices.size == mesh.triangleCount * 3,
            "实际：顶点 ${mesh.vertices.size} / 三角形×3 = ${mesh.triangleCount * 3}",
        )
        check("每个组都归好了材质", mesh.group("body")?.material == "frame" &&
            mesh.group("hand_hour")?.material == "glow" &&
            mesh.group("hand_minute")?.material == "glow",
            "实际：${mesh.groups.map { "${it.name}→${it.material}" }}")

        val b = mesh.bounds
        check(
            "包围盒 = (-4.64, -4.64, -0.5) ~ (4.64, 4.64, 0.75)",
            near(b.minX, -4.64f) && near(b.minY, -4.64f) && near(b.minZ, -0.5f) &&
                near(b.maxX, 4.64f) && near(b.maxY, 4.64f) && near(b.maxZ, 0.75f),
            "实际：$b",
        )

        check(
            "MTL 解析出 frame / glow 两个材质，且都带 map_Kd",
            result.materials.size == 2 && result.materials.all { it.declared && it.texture != null },
            "实际：${result.materials.map { "${it.name}=${it.texture}" }}",
        )
        check(
            "MTL 的 mtllib 被读到（clock.mtl）",
            result.mtlLibs == listOf("clock.mtl"),
            "实际：${result.mtlLibs}",
        )
        check(
            "干净的 OBJ 不该有任何警告/错误",
            result.noteSink.warnCount == 0 && result.noteSink.errorCount == 0,
            "实际：${result.noteSink.summary()}",
        )
    }

    // ────────────────────────── 3. 故意写坏的 OBJ ──────────────────────────

    private fun checkBrokenObj() {
        section("解析故意写坏的 OBJ（坏行 / 越界 / 负索引 / 缺 MTL）")

        // 每一条坏行都对应下面一个断言：
        //   ① foobar        —— 不认识的指令
        //   ② v 1 2         —— 数字不够
        //   ③ f 1 2         —— 顶点不够（不是面）
        //   ④ f 1 2 9999    —— 索引越界
        //   ⑤ mtllib 缺失   —— 兄弟文件读不到（openSibling 返回 null）
        //   ★ f -3 -2 -1    —— **负索引，必须被正常解析**（不是错误）
        val broken = """
            # 自检用：故意写坏
            mtllib not_here.mtl
            v 0 0 0
            v 1 0 0
            v 0 1 0
            v 1 1 0
            vt 0 0
            vt 1 0
            vt 1 1
            foobar 1 2 3
            v 1 2
            f 1 2
            f 1 2 9999
            f -3 -2 -1
            g tail
            f 1/1 2/2 3/3
        """.trimIndent()

        val result = try {
            ObjFormat.parse(
                broken.toByteArray(),
                StudioImportContext(where = "broken.obj", flipV = true, openSibling = { null }),
            )
        } catch (e: Exception) {
            fail("坏 OBJ 把解析器炸了：$e（容错要求：坏行跳过 + 计数，不整体抛异常）")
            return
        }

        println("  问题汇总：${result.noteSink.summary()}")
        for (note in result.notes) println("    · $note")
        println()

        check("坏行不抛异常，照样出网格", result.mesh.triangleCount == 2, "三角形 ${result.mesh.triangleCount}")
        check(
            "问题计数：5 处警告 / 0 处错误",
            result.noteSink.warnCount == 5 && result.noteSink.errorCount == 0,
            "实际：${result.noteSink.summary()}",
        )
        check(
            "组名 = [default, tail]（没有 g 的面落在 default）",
            result.mesh.groupNames == listOf(StudioMesh.DEFAULT_GROUP, "tail"),
            "实际：${result.mesh.groupNames}",
        )

        // 负索引 `f -3 -2 -1` 在当时已读到 4 个 v ⇒ 解析成第 2、3、4 个顶点
        val t0 = result.mesh.vertices[result.mesh.indices[0]]
        val t1 = result.mesh.vertices[result.mesh.indices[1]]
        val t2 = result.mesh.vertices[result.mesh.indices[2]]
        check(
            "负索引 -3/-2/-1 解成 (1,0,0) (0,1,0) (1,1,0)",
            near(t0.x, 1f) && near(t0.y, 0f) && near(t1.x, 0f) && near(t1.y, 1f) &&
                near(t2.x, 1f) && near(t2.y, 1f),
            "实际：(${t0.x},${t0.y}) (${t1.x},${t1.y}) (${t2.x},${t2.y})",
        )
        check("坏行没污染几何：顶点表恰好 6 个（两个三角形各 3 个）", result.mesh.vertices.size == 6, "实际：${result.mesh.vertices.size}")
    }

    // ────────────────────────── 4. flip_v ──────────────────────────

    private fun checkFlipV() {
        section("flip_v（OBJ 的 vt 原点在左下、贴图在左上）")

        val obj = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            vt 0.25 0.75
            f 1/1 2/1 3/1
        """.trimIndent().toByteArray()

        val flipped = ObjFormat.parse(obj, StudioImportContext(where = "flip.obj", flipV = true))
        val raw = ObjFormat.parse(obj, StudioImportContext(where = "flip.obj", flipV = false))

        check("flip_v=true → v = 1 - 0.75 = 0.25", near(flipped.mesh.vertices[0].v, 0.25f), "实际：${flipped.mesh.vertices[0].v}")
        check("flip_v=false → v = 0.75（原样）", near(raw.mesh.vertices[0].v, 0.75f), "实际：${raw.mesh.vertices[0].v}")
        check("u 不受影响", near(flipped.mesh.vertices[0].u, 0.25f) && near(raw.mesh.vertices[0].u, 0.25f))
    }

    // ────────────────────────── 5. 内核校验 ──────────────────────────

    private fun checkMeshValidation() {
        section("kernel 的构造校验（坏数据必须当场拒绝，不能进渲染器）")

        val v = { StudioVertex(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f) }

        check("索引越界 → StudioMeshException", throwsMesh {
            StudioMesh.create(listOf(v()), intArrayOf(0, 1, 2), emptyList(), "test")
        })
        check("NaN 顶点 → StudioMeshException", throwsMesh {
            StudioMesh.create(
                listOf(StudioVertex(Float.NaN, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
                intArrayOf(0, 0, 0), emptyList(), "test",
            )
        })
        check("索引数不是 3 的倍数 → StudioMeshException", throwsMesh {
            StudioMesh.create(listOf(v()), intArrayOf(0, 0), emptyList(), "test")
        })

        // 正路：builder 从零攒一个三角形，必须成功
        val builder = StudioMeshBuilder("selftest")
        builder.selectGroup("only")
        val a = builder.addVertex(StudioVertex(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        val b = builder.addVertex(StudioVertex(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f))
        val c = builder.addVertex(StudioVertex(0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f))
        builder.addTriangle(a, b, c)
        val mesh = builder.build()
        check("StudioMeshBuilder 攒出来的三角形可用", mesh.triangleCount == 1 && mesh.groupNames == listOf("only"))
    }

    // ────────────────────────── 6. 渲染上限配置（§7.1）──────────────────────────

    /**
     * `config/gtetstudio/studio.json` 的解析自检。
     *
     * 为什么这节能脱机跑：`config/StudioConfig` **一行 Minecraft/Forge 都不 import**
     * （这正是"配置走 studio 自己的 JSON"而不是 `ForgeConfigSpec` 换来的好处之一）。
     */
    private fun checkConfig() {
        section("渲染上限配置 studio.json（§7.1：坏值回退默认，不许炸）")

        val where = "studio.json"

        // ① 内置默认值 = StudioLimits 的常量（两处定义不许漂移）
        val def = StudioConfig.defaults()
        check(
            "内置默认值 = §7.1 那三个 + M5 预留三个",
            def.enabled && def.maxTrianglesPerModel == 200_000 && def.defaultViewDistance == 256 &&
                def.maxVisibleModels == 16 && def.maxTotalTriangles == 800_000 && def.animationMaxDistance == 64,
            "实际：$def",
        )
        check(
            "默认值与 StudioLimits 的常量一致",
            def.maxTrianglesPerModel == StudioLimits.DEFAULT_MAX_TRIANGLES_PER_MODEL &&
                    def.defaultViewDistance == StudioLimits.DEFAULT_VIEW_DISTANCE && def.enabled,
        )

        // ② 正常值 + 注释（行注释与块注释都要能被剥掉）
        val good = StudioConfig.parseText(
            """
            {
              // 行注释：这一行不该影响解析
              "version": 1,
              /* 块注释也一样
                 "enabled": false,   ← 注释里的内容不能生效 */
              "enabled": true,
              "maxTrianglesPerModel": 12345,
              "defaultViewDistance": 64,
              "maxVisibleModels": 4,
              "maxTotalTriangles": 90000,
              "animationMaxDistance": 32
            }
            """.trimIndent(),
            where,
        )
        println("  正常值：${good.values}（问题 ${good.problems.size} 条）")
        check(
            "正常值全部读到，且带注释也不出错",
            good.problems.isEmpty() && good.values.maxTrianglesPerModel == 12345 &&
                good.values.defaultViewDistance == 64 && good.values.enabled &&
                good.values.maxVisibleModels == 4 && good.values.maxTotalTriangles == 90000 &&
                good.values.animationMaxDistance == 32,
            "值：${good.values}，问题：${good.problems.map { it.message }}",
        )

        // ③ 缺字段（空对象）⇒ 全默认，且**不算问题**（用户只写想改的那个键是正常用法）
        val empty = StudioConfig.parseText("{}", where)
        check(
            "空对象 ⇒ 全默认值且无问题",
            empty.problems.isEmpty() && empty.values == def,
            "值：${empty.values}，问题：${empty.problems.map { it.message }}",
        )

        // ④ 类型不对 ⇒ 回退默认 + 明确报错（不许炸）
        val wrongType = StudioConfig.parseText(
            """{ "enabled": "yes", "maxTrianglesPerModel": "big", "defaultViewDistance": true }""",
            where,
        )
        println("  类型不对：${wrongType.problems.map { it.message }}")
        check(
            "类型不对 ⇒ 三个字段都回退默认值，并各报一条 error",
            wrongType.values == def && wrongType.problems.size == 3 && wrongType.problems.all { it.error },
            "值：${wrongType.values}，问题：${wrongType.problems.size} 条",
        )

        // ⑤ 值非法（0 / 负数）⇒ 回退默认 + error
        val badValue = StudioConfig.parseText(
            """{ "maxTrianglesPerModel": 0, "defaultViewDistance": -5 }""",
            where,
        )
        check(
            "0 / 负数 ⇒ 回退默认值并报 error",
            badValue.values.maxTrianglesPerModel == StudioLimits.DEFAULT_MAX_TRIANGLES_PER_MODEL &&
                badValue.values.defaultViewDistance == StudioLimits.DEFAULT_VIEW_DISTANCE &&
                badValue.problems.count { it.error } == 2,
            "值：${badValue.values}，问题：${badValue.problems.map { it.message }}",
        )

        // ⑥ 整份不是 JSON ⇒ 全默认 + error，绝不抛异常
        val broken = try {
            StudioConfig.parseText("{ 这不是 JSON", where)
        } catch (e: Exception) {
            fail("坏配置把解析器炸了：$e（要求：回退默认值 + 一条 error，不抛）")
            return
        }
        check(
            "坏 JSON ⇒ 全默认值 + 一条 error（不抛异常）",
            broken.values == def && broken.problems.size == 1 && broken.problems[0].error,
            "值：${broken.values}，问题：${broken.problems.map { it.message }}",
        )

        // ⑦ 认不出的键 ⇒ 只警告、不改行为（键名拼错必须能被看见）
        val unknownKey = StudioConfig.parseText("""{ "maxTriangles": 10 }""", where)
        check(
            "认不出的键 ⇒ 一条非 error 的提醒，值仍按默认",
            unknownKey.values == def && unknownKey.problems.size == 1 && !unknownKey.problems[0].error &&
                unknownKey.problems[0].message.contains("maxTriangles"),
            "问题：${unknownKey.problems.map { it.message }}",
        )

        // ⑧ enabled=false 要真的落到运行时值上（渲染层读的就是它）
        StudioLimits.applyValues(good.values, "$where（自检）")
        check(
            "enabled=true 生效：StudioLimits.enabled=true，单模型上限=12345",
            StudioLimits.enabled && StudioLimits.maxTrianglesPerModel == 12345,
            "实际：enabled=${StudioLimits.enabled}, max=${StudioLimits.maxTrianglesPerModel}",
        )
        val off = StudioConfig.parseText("""{ "enabled": false }""", where)
        StudioLimits.applyValues(off.values, "$where（自检）")
        check(
            "enabled=false 生效（渲染层 [StudioRenderCache.obtain] 直接不画）",
            !StudioLimits.enabled,
        )
        StudioLimits.resetToDefaults()
        check(
            "resetToDefaults 复位回内置默认（自检不留副作用）",
            StudioLimits.enabled && StudioLimits.maxTrianglesPerModel == 200_000 &&
                StudioLimits.defaultViewDistance == 256,
            "实际：${StudioLimits.summary()}",
        )

        // ⑨ 视距优先级：**模型 JSON 覆盖 > 全局默认**（弄反了这条会红）
        StudioConfig.parseText("""{ "defaultViewDistance": 64 }""", where).let {
            StudioLimits.applyValues(it.values, "$where（自检）")
        }
        check(
            "全局默认改成 64 后：模型没写 viewDistance ⇒ 用 64",
            StudioConfig.resolveViewDistance(null) == 64,
            "实际：${StudioConfig.resolveViewDistance(null)}",
        )
        check(
            "模型写了 viewDistance=128 ⇒ 以 128 为准（JSON 覆盖 > 全局默认，方向没反）",
            StudioConfig.resolveViewDistance(128) == 128,
            "实际：${StudioConfig.resolveViewDistance(128)}",
        )
        StudioLimits.resetToDefaults()

        // ⑩ 仓库里那份**真正要发布**的 studio.json：必须解析干净（没有一条问题）
        val shipped = File("src/main/resources/gtetstudio/studio.json")
        if (!shipped.isFile) {
            fail("找不到 ${shipped.path}（在仓库根目录下跑）—— mod 要把这份文件释放到 config/gtetstudio/")
        } else {
            val parsed = StudioConfig.parseText(shipped.readText(Charsets.UTF_8), shipped.path)
            println("  随包发布的那份：${parsed.values}")
            for (problem in parsed.problems) println("    · ${problem.message}")
            check(
                "发布的 studio.json 解析零问题（注释/键名都是对的）",
                parsed.problems.isEmpty(),
                "问题：${parsed.problems.map { it.message }}",
            )
            check(
                "发布的 studio.json 覆盖 §7.1 的 M1a 三项 + M5 预留三项",
                parsed.values.enabled && parsed.values.maxTrianglesPerModel == 200_000 &&
                    parsed.values.defaultViewDistance == 256 && parsed.values.maxVisibleModels == 16 &&
                    parsed.values.maxTotalTriangles == 800_000 && parsed.values.animationMaxDistance == 64,
                "实际：${parsed.values}",
            )
        }

        // ⑪ 可观测（§7.1 纪律 2）：/gtetstudio limits 打的就是这个
        check(
            "describe() 打出六项，且 M5 那三项标明当前不生效",
            StudioLimits.describe().size == 6 &&
                StudioLimits.describe().count { it.contains("M5 才生效，当前版本没有任何代码读它") } == 3,
            "实际：${StudioLimits.describe()}",
        )
    }

    // ────────────────────────── 工具 ──────────────────────────

    /** 自检用的上下文：把 `openSibling` 接到**同目录**的真实文件上（模拟磁盘来源）。 */
    private fun contextFor(file: File, alias: Map<String, String>, flipV: Boolean): StudioImportContext {
        val dir = file.parentFile
        return StudioImportContext(
            where = file.name,
            textureAliases = alias,
            flipV = flipV,
            openSibling = { ref -> File(dir, ref).takeIf { it.isFile }?.readBytes() },
        )
    }

    /** OBJ 里 `v` 行的条数（用来核对"读到的顶点"与"去重后的顶点"）。 */
    private fun referencedPositionCount(file: File): Int =
        file.readLines().count { it.trimStart().startsWith("v ") }

    private inline fun throwsMesh(body: () -> Unit): Boolean = try {
        body()
        false
    } catch (_: StudioMeshException) {
        true
    } catch (_: Exception) {
        false
    }

    private fun near(a: Float, b: Float, eps: Float = 1e-4f): Boolean = kotlin.math.abs(a - b) <= eps

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