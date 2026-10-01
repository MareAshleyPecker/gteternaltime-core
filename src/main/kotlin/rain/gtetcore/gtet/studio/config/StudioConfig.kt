package rain.gtetcore.gtet.studio.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import rain.gtetcore.gtet.studio.config.StudioConfig.defaults

/**
 * `config/gtetstudio/studio.json` 的**解析器**（设计文档 §7.1「渲染上限与降级」）。
 *
 * ## 为什么是 studio 自己的 JSON，不是宿主的 `ForgeConfigSpec`（已拍板，别再改成它）
 * 1. 注册 `ForgeConfigSpec` 要在 mod 构造阶段加一行**宿主**代码 —— studio 不该往宿主里插线；
 * 2. §7.1 第 3 条纪律：**配置属于 studio 包自己**，整包抽出去时不能把宿主的配置一起拖走；
 * 3. studio 本来就有「读 JSON + `/gtetstudio reload` 热重载」这套管线 ⇒ 复用它 = **零宿主接线**，
 *    而且改完 reload 就生效、不用重启游戏。
 *
 * ⇒ 所以这个文件**一行 Minecraft / Forge 都不 import**：「文本 → 值」是纯函数，
 * 于是它能跟着 `format/StudioFormatSelfCheck` 一起**脱离游戏单测**（`data/StudioLibrary` 负责读盘与落日志）。
 *
 * ## 容错口径（"不许炸"）
 * 缺字段、类型不对、值非法、甚至整份文件不是 JSON：**一律回退默认值 + 记一条问题**，
 * 由调用方打成日志。配置写坏不该让客户端起不来 —— 那正是它要防的那类灾难。
 * 唯一例外是**认不出的顶层键**：只警告、不改行为（前向兼容），因为「键名拼错 ⇒ 上限静默失效」才是真危险。
 *
 * @author rain fox
 */

/** 一份配置解析出来的全部值（默认值见 [StudioLimits] 的常量）。 */
data class StudioConfigValues(
    /** 配置格式版本。 */
    val version: Int,
    /** 总开关：false = 一台都不画。 */
    val enabled: Boolean,
    /** 单模型三角形上限（载入期拒绝）。 */
    val maxTrianglesPerModel: Int,
    /** 视距默认值（格）；模型 JSON 的 `viewDistance` 优先于它。 */
    val defaultViewDistance: Int,
    /** M5 才生效：同屏模型数上限。 */
    val maxVisibleModels: Int,
    /** M5 才生效：三角形总预算。 */
    val maxTotalTriangles: Int,
    /** M5 才生效：超过这个距离只画静态部件。 */
    val animationMaxDistance: Int,
)

/**
 * 解析中发现的**一处问题**。
 *
 * @param error true = 这份配置没被采纳（值回退了默认）；false = 提醒，值照用
 */
class StudioConfigProblem(val message: String, val error: Boolean = false)

/**
 * 解析结果：**值 + 问题清单**（问题不抛异常，交给调用方决定打成什么级别的日志）。
 */
class StudioConfigParsed(val values: StudioConfigValues, val problems: List<StudioConfigProblem>)

object StudioConfig {

    /** 配置文件名（放在 `config/gtetstudio/` 下，与模型 JSON 同目录）。 */
    const val FILE_NAME = "studio.json"

    /** 本版认识的文件版本。比它新的照样读认识的键，只提醒一句。 */
    const val VERSION = 1

    /** 本版认识的**全部**顶层键（不在这张表里的键会警告）。 */
    @JvmField
    val KNOWN_KEYS: Set<String> = setOf(
        "version",
        "enabled",
        "maxTrianglesPerModel",
        "defaultViewDistance",
        // 下面三个是 M5 的键：现在**只是解析与展示**，没有任何代码按它们改行为
        "maxVisibleModels",
        "maxTotalTriangles",
        "animationMaxDistance",
    )

    /** 全部内置默认值（配置缺失 / 坏值 / 没写这个键时用）。 */
    @JvmStatic
    fun defaults(): StudioConfigValues = StudioConfigValues(
        version = VERSION,
        enabled = StudioLimits.DEFAULT_ENABLED,
        maxTrianglesPerModel = StudioLimits.DEFAULT_MAX_TRIANGLES_PER_MODEL,
        defaultViewDistance = StudioLimits.DEFAULT_VIEW_DISTANCE,
        maxVisibleModels = StudioLimits.DEFAULT_MAX_VISIBLE_MODELS,
        maxTotalTriangles = StudioLimits.DEFAULT_MAX_TOTAL_TRIANGLES,
        animationMaxDistance = StudioLimits.DEFAULT_ANIMATION_MAX_DISTANCE,
    )

    /**
     * 解析配置文件文本（允许 `//` 行注释与块注释，见 [stripJsonComments]）。
     *
     * **任何输入都不会抛异常**：坏到底就用 [defaults] 并返回一条 error 级问题。
     *
     * @param where 出错时写进消息里的「这是哪份文件」，例如 `config/gtetstudio/studio.json`
     */
    @JvmStatic
    fun parseText(text: String, where: String): StudioConfigParsed {
        val element = try {
            JsonParser.parseString(stripJsonComments(text))
        } catch (e: JsonSyntaxException) {
            return StudioConfigParsed(
                defaults(),
                listOf(StudioConfigProblem("$where: 不是合法 JSON —— ${e.message}；整份配置按内置默认值走", error = true)),
            )
        }
        if (!element.isJsonObject) {
            return StudioConfigParsed(
                defaults(),
                listOf(StudioConfigProblem("$where: 根节点必须是 JSON 对象；整份配置按内置默认值走", error = true)),
            )
        }
        return parse(element.asJsonObject, where)
    }

    /**
     * 解析一份已经读进来的 JSON 对象。
     *
     * @param where 出错时写进消息里的「这是哪份文件」
     */
    @JvmStatic
    fun parse(json: JsonObject, where: String): StudioConfigParsed {
        val problems = ArrayList<StudioConfigProblem>(4)

        val version = intAtLeast(json, "version", VERSION, 0, where, problems)
        val enabled = booleanAt(json, "enabled", StudioLimits.DEFAULT_ENABLED, where, problems)
        val maxTriangles = positiveIntAt(
            json, "maxTrianglesPerModel", StudioLimits.DEFAULT_MAX_TRIANGLES_PER_MODEL, where, problems,
        )
        val viewDistance = positiveIntAt(
            json, "defaultViewDistance", StudioLimits.DEFAULT_VIEW_DISTANCE, where, problems,
        )
        val maxVisible = positiveIntAt(
            json, "maxVisibleModels", StudioLimits.DEFAULT_MAX_VISIBLE_MODELS, where, problems,
        )
        val maxTotal = positiveIntAt(
            json, "maxTotalTriangles", StudioLimits.DEFAULT_MAX_TOTAL_TRIANGLES, where, problems,
        )
        val animDistance = positiveIntAt(
            json, "animationMaxDistance", StudioLimits.DEFAULT_ANIMATION_MAX_DISTANCE, where, problems,
        )

        if (version > VERSION) {
            problems += StudioConfigProblem(
                "$where: version=$version 比本版认识的 $VERSION 新；只读认识的键，多余部分忽略（要新语义请升级 mod）",
            )
        }

        // 认不出的键：**一定要说**。写错一个键名（比如 maxTriangles）不会有任何报错，
        // 但上限就是不生效 —— 按 §7.1 的纪律"设置必须可观测"，这种情况必须自己冒出来。
        val unknown = json.keySet().filter { it !in KNOWN_KEYS }
        if (unknown.isNotEmpty()) {
            problems += StudioConfigProblem(
                "$where: 认不出的键 $unknown 被忽略（认识的是 $KNOWN_KEYS）；" +
                    "键名拼错时对应的上限不会生效，所以这里一定会报一声",
            )
        }

        return StudioConfigParsed(
            StudioConfigValues(
                version = version,
                enabled = enabled,
                maxTrianglesPerModel = maxTriangles,
                defaultViewDistance = viewDistance,
                maxVisibleModels = maxVisible,
                maxTotalTriangles = maxTotal,
                animationMaxDistance = animDistance,
            ),
            problems,
        )
    }

    /**
     * **视距优先级**（设计文档 §7.1）：模型 JSON 的 `viewDistance`（[perModel]）**优先于**
     * `studio.json` 里的 `defaultViewDistance`；两个都没有才落回常量默认。
     *
     * 单独抽成一个函数，是为了让"优先级没写反"这件事能被自检断言钉住，
     * 而不是埋在 `StudioModelDef.parse` 的一行 `optInt` 默认参数里。
     *
     * @param perModel 模型 JSON 里显式写的视距；没写就传 null
     */
    @JvmStatic
    fun resolveViewDistance(perModel: Int?): Int = perModel ?: StudioLimits.defaultViewDistance

    // ────────────────────────── 取值小工具（坏值一律回退 + 记问题）──────────────────────────

    private fun booleanAt(
        o: JsonObject,
        key: String,
        def: Boolean,
        where: String,
        problems: MutableList<StudioConfigProblem>,
    ): Boolean {
        val el = o.get(key) ?: return def
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isBoolean) {
            problems += StudioConfigProblem("$where: \"$key\" 必须是 true / false，实际是 $el —— 按默认值 $def 走", error = true)
            return def
        }
        return el.asBoolean
    }

    private fun intAtLeast(
        o: JsonObject,
        key: String,
        def: Int,
        min: Int,
        where: String,
        problems: MutableList<StudioConfigProblem>,
    ): Int {
        val value = intAt(o, key, def, where, problems) ?: return def
        if (value < min) {
            problems += StudioConfigProblem("$where: \"$key\"=$value 不能小于 $min —— 按默认值 $def 走", error = true)
            return def
        }
        return value
    }

    private fun positiveIntAt(
        o: JsonObject,
        key: String,
        def: Int,
        where: String,
        problems: MutableList<StudioConfigProblem>,
    ): Int = intAtLeast(o, key, def, 1, where, problems)

    /** 取一个整数键；缺失返回 null（调用方给默认值），类型不对回退 + 记问题。 */
    private fun intAt(
        o: JsonObject,
        key: String,
        def: Int,
        where: String,
        problems: MutableList<StudioConfigProblem>,
    ): Int? {
        val el = o.get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
            problems += StudioConfigProblem("$where: \"$key\" 必须是整数，实际是 $el —— 按默认值 $def 走", error = true)
            return null
        }
        return el.asInt
    }
}

/**
 * 去掉 `//` 行注释与块注释（**字符串内的斜杠不动**）。
 *
 * 为什么自己剥：Gson 不吃注释，而这份配置是给**人手改**的 —— 注释比字段本身还重要
 * （它要说明哪几个键是 M5 才生效的）。剥完再交给 Gson，行为完全可控。
 *
 * ⚠️ 放在 `config/` 而不是 `data/StudioLibrary`：它是纯文本处理，
 * 只有这样 `studio.json` 与模型 JSON 才能用**同一个**口径，而自检又不必拖进 Minecraft。
 */
internal fun stripJsonComments(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    var inString = false
    var escaped = false
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            sb.append(c)
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            i++
            continue
        }
        when (c) {
            '"' -> {
                inString = true
                sb.append(c)
                i++
            }
            '/' if i + 1 < text.length && text[i + 1] == '/' -> {
                while (i < text.length && text[i] != '\n') i++
            }
            '/' if i + 1 < text.length && text[i + 1] == '*' -> {
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i = if (i + 1 < text.length) i + 2 else text.length
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}