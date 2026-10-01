package rain.gtetcore.gtet.studio.kernel.cad

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * `features` 字段的 **真实 schema**（M3a 定稿）—— 设计文档 §4 :173 那一带的落地版。
 *
 * ## 为什么解析器在 kernel 里（这是刻意的，不是没分层）
 * `data/StudioModelDef` 是可以读 JSON 的，但它**一行都跑不了脱机自检**（它 import 了 Minecraft）。
 * 而"改一个数字 → 整块重建"这件事的正确性**必须机器可验**：schema、拓扑序、布尔、三角化、
 * 闭合性，一条都不该只在游戏里用眼睛看。把 schema 与求值器放一起，
 * 整条链路（`dial.json` 原文 → 网格 → OBJ）就能在 `StudioCadSelfCheck` 里一次跑完。
 *
 * 代价是 `kernel/cad` 多一个 **gson** 依赖（纯 Java，**没有** MC/Forge/GTM）——
 * `kernel` 原来的纪律（不许 import MC/Forge/GTM、必须能脱机单测）**没有被破坏**，
 * 但"kernel 只有 kotlin.math"这句话从 M3a 起不再成立，记在这里。
 *
 * ## schema
 * ```jsonc
 * "features": [
 *   // ── 2D 图元（都产出"轮廓"，不产出面）──────────────────────────
 *   { "type": "circle",  "name": "outline", "r": 4.5, "segments": 64, "at": [0, 0] },
 *   { "type": "rect",    "name": "plate",   "w": 10, "h": 6, "at": [0, 0] },
 *   { "type": "polygon", "name": "tri",     "points": [[0,0],[2,0],[0,2]], "at": [0, 0] },
 *   { "type": "ring",    "name": "rim",     "rOuter": 4.5, "rInner": 3.8, "segments": 64 },
 *   // ── 布尔：a / b 按名字引用**前面**定义过的 2D 特征 ──────────────
 *   { "type": "union",     "name": "u", "a": "x", "b": "y" },
 *   { "type": "subtract",  "name": "d", "a": "x", "b": "y" },
 *   { "type": "intersect", "name": "i", "a": "x", "b": "y" },
 *   // ── 圆周阵列：of 也是 2D 特征的名字 ────────────────────────────
 *   { "type": "array", "name": "spokes", "of": "spoke", "count": 12, "radius": 3.0,
 *     "axis": "z", "startDeg": 0, "sweepDeg": 360 },
 *   // ── 挤出成体：**唯一产出几何的特征**，组名 = 它的 name ──────────
 *   { "type": "extrude", "name": "dial", "of": "d", "thickness": 1.0,
 *     "centerZ": true, "material": "frame" }
 * ]
 * ```
 *
 * | 键 | 适用 | 默认 | 说明 |
 * |---|---|---|---|
 * | `type` | 全部 | —— | 上表八个取值之一 |
 * | `name` | 全部 | —— | **同时是组名**，必须唯一、非空 |
 * | `at` | circle/rect/polygon/ring | `[0,0]` | 局部原点：圆=圆心、矩形=**中心**、多边形=坐标偏移 |
 * | `r` | circle | —— | `> 0` |
 * | `segments` | circle/ring | `64` | `3..512`（折线逼近的段数） |
 * | `w` / `h` | rect | —— | `> 0` |
 * | `points` | polygon | —— | `[[x,y], ...]`，至少 3 个 |
 * | `rOuter` / `rInner` | ring | —— | `rOuter > rInner > 0` |
 * | `a` / `b` | 布尔 | —— | 前面定义过的 2D 特征名 |
 * | `of` | array / extrude | —— | 前面定义过的 2D 特征名 |
 * | `count` | array | —— | `>= 1` |
 * | `radius` | array | —— | `>= 0`，副本到原点的距离 |
 * | `axis` | array | `"z"` | **只支持 `z`**；写 x/y 会明确报"没实现" |
 * | `startDeg` / `sweepDeg` | array | `0` / `360` | 起始角与**总张角**（步距 = 张角 ÷ 个数，右端不闭合） |
 * | `thickness` | extrude | —— | `> 0`，沿 +Z |
 * | `centerZ` | extrude | `true` | `true` = 以 Z=0 为中心 |
 * | `material` | extrude | `null` | 要写进网格的材质名（必须在该 JSON 的 `materials` 段里声明） |
 *
 * **认不出的键一律忽略**（与 `data/StudioModelDef` 的前向兼容口径一致）：
 * 以后加字段，老版本读新文件不该炸。
 *
 * @author rain fox
 */
object StudioCadFeatureJson {

    /** `type` 的全部取值（报错时列给用户看）。 */
    @JvmField
    val KNOWN_TYPES: List<String> = listOf(
        StudioCadFeature.Circle.TYPE,
        StudioCadFeature.Rect.TYPE,
        StudioCadFeature.Polygon.TYPE,
        StudioCadFeature.Ring.TYPE,
        StudioCadBoolean.Op.UNION.jsonName,
        StudioCadBoolean.Op.SUBTRACT.jsonName,
        StudioCadBoolean.Op.INTERSECT.jsonName,
        StudioCadFeature.CircArray.TYPE,
        StudioCadFeature.Extrude.TYPE,
    )

    /**
     * 解析 `features` 数组。
     *
     * @param element `features` 那个 JSON 节点（必须是数组）
     * @param where   出错时写进消息里的「这是哪份数据」，例如 `dial.json`
     * @throws StudioCadException 结构或取值不合法（**载入期拒绝**，不静默跳过）
     */
    @JvmStatic
    fun parse(element: JsonElement, where: String): List<StudioCadFeature> {
        if (!element.isJsonArray) {
            throw StudioCadException("$where: features 必须是数组（每条一个特征对象）")
        }
        val array = element.asJsonArray
        val out = ArrayList<StudioCadFeature>(array.size())
        for (i in 0 until array.size()) {
            val item = array[i]
            if (!item.isJsonObject) {
                throw StudioCadException("$where: features[$i] 必须是对象，例如 {\"type\": \"circle\", \"name\": \"dial\", \"r\": 4}")
            }
            out += parseOne(item.asJsonObject, "$where: features[$i]")
        }
        if (out.isEmpty()) {
            throw StudioCadException("$where: features 是空数组 —— 要么写几条特征，要么整个字段去掉走 source")
        }
        return out
    }

    // ────────────────────────── 一条 ──────────────────────────

    private fun parseOne(o: JsonObject, at: String): StudioCadFeature {
        val type = o.string("type", at)?.lowercase()
            ?: throw StudioCadException("$at: 缺字段 \"type\"；可用的是 $KNOWN_TYPES")
        val name = o.string("name", at)
            ?: throw StudioCadException("$at: 缺字段 \"name\"（它同时是组名，extrude 的组就叫这个）")

        return when (type) {
            StudioCadFeature.Circle.TYPE -> StudioCadFeature.Circle(
                name = name,
                r = o.number("r", at) ?: missing("r", at, type),
                segments = o.int("segments", StudioCadFeature.Circle.DEFAULT_SEGMENTS, at),
                at = o.point("at", at),
            )

            StudioCadFeature.Rect.TYPE -> StudioCadFeature.Rect(
                name = name,
                w = o.number("w", at) ?: missing("w", at, type),
                h = o.number("h", at) ?: missing("h", at, type),
                at = o.point("at", at),
            )

            StudioCadFeature.Polygon.TYPE -> StudioCadFeature.Polygon(
                name = name,
                points = o.points("points", at) ?: missing("points", at, type),
                at = o.point("at", at),
            )

            StudioCadFeature.Ring.TYPE -> StudioCadFeature.Ring(
                name = name,
                rOuter = o.number("rOuter", at) ?: missing("rOuter", at, type),
                rInner = o.number("rInner", at) ?: missing("rInner", at, type),
                segments = o.int("segments", StudioCadFeature.Circle.DEFAULT_SEGMENTS, at),
                at = o.point("at", at),
            )

            StudioCadBoolean.Op.UNION.jsonName,
            StudioCadBoolean.Op.SUBTRACT.jsonName,
            StudioCadBoolean.Op.INTERSECT.jsonName,
            -> StudioCadFeature.Bool(
                name = name,
                op = when (type) {
                    StudioCadBoolean.Op.UNION.jsonName -> StudioCadBoolean.Op.UNION
                    StudioCadBoolean.Op.SUBTRACT.jsonName -> StudioCadBoolean.Op.SUBTRACT
                    else -> StudioCadBoolean.Op.INTERSECT
                },
                a = o.string("a", at) ?: missing("a", at, type),
                b = o.string("b", at) ?: missing("b", at, type),
            )

            StudioCadFeature.CircArray.TYPE -> StudioCadFeature.CircArray(
                name = name,
                of = o.string("of", at) ?: missing("of", at, type),
                count = o.intReq("count", at, type) ?: missing("count", at, type),
                radius = o.number("radius", at) ?: missing("radius", at, type),
                axis = (o.string("axis", at) ?: StudioCadFeature.CircArray.AXIS_Z).lowercase(),
                startDeg = o.number("startDeg", at) ?: 0.0,
                sweepDeg = o.number("sweepDeg", at) ?: 360.0,
            )

            StudioCadFeature.Extrude.TYPE -> StudioCadFeature.Extrude(
                name = name,
                of = o.string("of", at) ?: missing("of", at, type),
                thickness = o.number("thickness", at) ?: missing("thickness", at, type),
                centerZ = o.bool("centerZ", true, at),
                material = o.string("material", at),
            )

            else -> throw StudioCadException(
                "$at: type=\"$type\" 不认识；M3a 支持的是 $KNOWN_TYPES"
            )
        }
    }

    private fun missing(key: String, at: String, type: String): Nothing =
        throw StudioCadException("$at: type=\"$type\" 缺必填字段 \"$key\"")

    // ────────────────────────── JSON 取值 ──────────────────────────
    // 全部带「哪一条、哪个键、期望什么」的报错；类型不对**当场拒绝**，不静默取默认值。

    private fun JsonObject.string(key: String, at: String): String? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) {
            throw StudioCadException("$at: 字段 \"$key\" 必须是字符串")
        }
        return el.asString
    }

    private fun JsonObject.number(key: String, at: String): Double? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
            throw StudioCadException("$at: 字段 \"$key\" 必须是数字")
        }
        val v = el.asDouble
        if (!v.isFinite()) throw StudioCadException("$at: 字段 \"$key\" = $v 不是有限数")
        return v
    }

    private fun JsonObject.int(key: String, def: Int, at: String): Int {
        val el = get(key) ?: return def
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
            throw StudioCadException("$at: 字段 \"$key\" 必须是整数")
        }
        return el.asInt
    }

    /** 必填整数：没写返回 null，写了但类型不对照样当场报错。 */
    private fun JsonObject.intReq(key: String, at: String, type: String): Int? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
            throw StudioCadException("$at: type=\"$type\" 的字段 \"$key\" 必须是整数")
        }
        return el.asInt
    }

    private fun JsonObject.bool(key: String, def: Boolean, at: String): Boolean {
        val el = get(key) ?: return def
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isBoolean) {
            throw StudioCadException("$at: 字段 \"$key\" 必须是 true / false")
        }
        return el.asBoolean
    }

    /** `[x, y]`（省略时是原点）。 */
    private fun JsonObject.point(key: String, at: String): Vec2 {
        val el = get(key) ?: return Vec2(0.0, 0.0)
        if (!el.isJsonArray || el.asJsonArray.size() != 2) {
            throw StudioCadException("$at: 字段 \"$key\" 必须是长度 2 的数组，例如 [0, 0]")
        }
        val a: JsonArray = el.asJsonArray
        return Vec2(a[0].asNumber("$at: $key[0]"), a[1].asNumber("$at: $key[1]"))
    }

    /** `[[x,y], ...]`。 */
    private fun JsonObject.points(key: String, at: String): List<Vec2>? {
        val el = get(key) ?: return null
        if (!el.isJsonArray) {
            throw StudioCadException("$at: 字段 \"$key\" 必须是数组，例如 [[0,0],[4,0],[4,2]]")
        }
        val out = ArrayList<Vec2>(el.asJsonArray.size())
        for (i in 0 until el.asJsonArray.size()) {
            val p = el.asJsonArray[i]
            if (!p.isJsonArray || p.asJsonArray.size() != 2) {
                throw StudioCadException("$at: $key[$i] 必须是长度 2 的数组，例如 [0, 0]")
            }
            out += Vec2(p.asJsonArray[0].asNumber("$at: $key[$i][0]"), p.asJsonArray[1].asNumber("$at: $key[$i][1]"))
        }
        return out
    }

    private fun JsonElement.asNumber(what: String): Double {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) {
            throw StudioCadException("$what 必须是数字")
        }
        val v = asDouble
        if (!v.isFinite()) throw StudioCadException("$what = $v 不是有限数")
        return v
    }
}