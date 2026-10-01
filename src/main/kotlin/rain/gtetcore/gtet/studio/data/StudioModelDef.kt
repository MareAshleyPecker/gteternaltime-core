package rain.gtetcore.gtet.studio.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.studio.api.StudioAnchor
import rain.gtetcore.gtet.studio.config.StudioConfig
import rain.gtetcore.gtet.studio.format.StudioFormats
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadException
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadFeature
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadFeatureJson

/**
 * JSON 数据模型（DTO）—— 只描述「文件里写了什么」，不做任何烘焙、不碰渲染。
 *
 * 与设计文档 §4 的对应关系：M0 只实现了子集；**M3a 起 `features`（CAD 参数化历史）真正生效**，
 * 仍未实现的字段（`kernel` / `sources[].scale` / `bounds` / `keys`）暂时不解析；
 * 解析遇到不认识的键**不报错**（前向兼容：以后加字段，老版本读新文件不至于炸）。
 *
 * @author rain fox
 */

/**
 * `source` 段：模型从哪来。
 *
 * ⚠️ **M3a 起 [StudioModelDef.source] 可以是 null**：写了 `features` 的模型走 CAD 路径，
 * 不需要任何来源文件。见 [StudioModelDef] 的 KDoc。
 */
class StudioSourceDef(
    /** 导入格式 id（`"obj"`）；**省略时按文件扩展名自动认领**（见 `format/StudioFormats`）。 */
    val format: String?,
    /** ① **资源路径**，形如 `gtetcore:models/obj/clock.obj`（对应 `assets/gtetcore/models/obj/clock.obj`）。 */
    val model: ResourceLocation?,
    /** ② **磁盘路径**（相对游戏目录，也可写绝对路径），例如 `config/gtetstudio/models/clock.obj`。 */
    val file: String?,
    /** OBJ/MTL 里可以用 `map_Kd #名字` 引用的贴图表（名字 → 资源路径或相对本文件的名字）。 */
    val textures: Map<String, String>,
    val automaticCulling: Boolean,
    val shadeQuads: Boolean,
    val flipV: Boolean,
    val emissiveAmbient: Boolean,
    val mtlOverride: String?,
    /**
     * 文件里**显式写了、但自写解析器不再使用**的字段名（M0 的 `forge:obj` 选项）。
     *
     * 留着它们是为了不让老 JSON（比如 M0 那份 clock.json）因为多写了几个键就报错；
     * 但**绝不能假装它们还生效** —— 载入时会照着这个清单提示一句。
     */
    val ignoredOptions: List<String>,
)

/**
 * `materials` 段的一项：材质名 → 渲染方式（以及 **M3a 起可选**的贴图）。
 *
 * `texture` 只在 **`features`（CAD）路径**下生效 —— 那条路没有 MTL，贴图必须写在 JSON 里。
 * OBJ 路径的贴图**仍然只来自 MTL 的 `map_Kd`**（写了 `texture` 会在载入时提示一句"不生效"），
 * 这样"两套逻辑互相打架"的情况不会出现。
 */
class StudioMaterialDef(val name: String, val render: String, val texture: String? = null)

/** `parts` 段的一项。 */
class StudioPartDef(val name: String)

/** `animation.tracks` 段的一项。 */
class StudioTrackDef(
    val part: String,
    val channel: String,
    val axis: Direction.Axis,
    val speed: Float,
    /** M4 预留：出现了就提示「M0 忽略」，不报错。 */
    val hasKeys: Boolean,
)

/** `animation` 段。 */
class StudioAnimationDef(val driver: String, val tracks: List<StudioTrackDef>)

/**
 * 一整份 JSON。
 *
 * ## `features` 与 `source` 的关系（★ M3a 定死，只有一套逻辑）
 * ```
 * features 非空  ⇒ 网格由 CAD 特征历史**生成**；source 段可省略，
 *                  就算写了 source.file / source.model 也**一律忽略**（载入时提示一句）
 * features 为空  ⇒ 走原来的 OBJ 路径；此时 source 段必填（file 或 model 二选一）
 * ```
 * 两条路**互斥**：不存在"先读 OBJ 再拿 features 改一改"这种中间态。
 */
class StudioModelDef(
    val version: Int,
    val id: ResourceLocation,
    /** **可以是 null**：写了 `features` 的 CAD 模型不需要来源文件。见类 KDoc。 */
    val source: StudioSourceDef?,
    val materials: Map<String, StudioMaterialDef>,
    val parts: List<StudioPartDef>,
    val anchor: StudioAnchor,
    val animation: StudioAnimationDef?,
    /**
     * 视距（格）。设计文档 §7.1：模型 JSON 里写了就用它，没写才用 `studio.json` 的
     * `defaultViewDistance`（常量默认 256）—— **JSON 覆盖 > 全局默认**。超出就不画。
     */
    val viewDistance: Int,
    /**
     * ★ **M3a：CAD 参数化历史**（设计文档 §4 :173 那一带）。
     *
     * 空列表 = 这份 JSON 不是 CAD 模型，走 `source`。schema 见
     * [rain.gtetcore.gtet.studio.kernel.cad.StudioCadFeatureJson] 的 KDoc（那是唯一权威）。
     */
    val features: List<StudioCadFeature>,
) {

    companion object {

        /** M0 认识的 `channel` 取值。 */
        const val CHANNEL_ROT = "rot"

        /** M0 认识的 `render` 取值（与 `studio/render/StudioRenderTypes` 一一对应）。 */
        @JvmField
        val KNOWN_RENDER_KINDS = setOf("cutout", "eyes", "translucent")

        /**
         * M0 传给 Forge `forge:obj` 的选项：**自写解析器之后不再使用**。
         *
         * 解析时照收（老 JSON 不报错），载入时若发现用户显式写了，就提示一句"这个键现在不生效"。
         * 唯一例外见 [StudioSourceDef.flipV] —— UV 的 V 轴翻转现在由我们自己实现，所以它仍然生效。
         */
        @JvmField
        val FORGE_ONLY_OPTIONS = listOf("automatic_culling", "shade_quads", "emissive_ambient")

        /**
         * 解析一份 [JsonObject]。
         *
         * @param json  JSON 根对象
         * @param where 出错时写进消息里的「这是哪份文件」，例如 `clock.json`
         */
        @JvmStatic
        fun parse(json: JsonObject, where: String): StudioModelDef {
            val version = json.optInt("version", 1)
            if (version > 1) {
                throw StudioLoadException(
                    "$where: version=$version 比本版认识的 1 还要新，拒绝猜测式加载（请升级 mod）"
                )
            }

            val idStr = json.reqString("id", where)
            val id = ResourceLocation.tryParse(idStr)
                ?: throw StudioLoadException("$where: id \"$idStr\" 不是合法的资源路径（要形如 gtet:test_clock）")

            // ★ M3a：features 先解析 —— 它决定后面 source 是不是必填（见类 KDoc 的两条互斥路径）
            val featuresEl = json.get("features")
            val features = when {
                featuresEl == null || featuresEl.isJsonNull -> emptyList()
                else -> try {
                    StudioCadFeatureJson.parse(featuresEl, where)
                } catch (e: StudioCadException) {
                    throw StudioLoadException(e.message ?: "$where: features 不合法", e)
                }
            }

            val source = parseSource(json.get("source"), where, requireOrigin = features.isEmpty())
            val materials = parseMaterials(json, where)
            val parts = parseParts(json, where)
            val anchor = parseAnchor(json, where)
            val animation = parseAnimation(json, where)
            // 视距：**模型 JSON 的 viewDistance 优先，其次是 studio.json 的 defaultViewDistance**
            // （§7.1 的优先级，方向别弄反；这条由 StudioConfig.resolveViewDistance 统一裁决，
            //  自检里有断言钉住它）
            val perModelViewDistance = json.optIntOrNull("viewDistance")
            if (perModelViewDistance != null && perModelViewDistance <= 0) {
                throw StudioLoadException("$where: viewDistance=$perModelViewDistance 必须为正数（单位：格）")
            }
            val viewDistance = StudioConfig.resolveViewDistance(perModelViewDistance)

            return StudioModelDef(
                version, id, source, materials, parts, anchor, animation, viewDistance, features,
            )
        }

        // ────────────────────────── 各段 ──────────────────────────

        /**
         * @param requireOrigin `source` 段是不是**必须有来源**（`file` 或 `model`）。
         *        写了 `features` 时为 false —— CAD 模型不需要任何来源文件，
         *        但 `source` 段本身仍可写（只为 `textures` 之类的通用选项留着位置）。
         */
        private fun parseSource(el: com.google.gson.JsonElement?, where: String, requireOrigin: Boolean): StudioSourceDef? {
            if (el == null || el.isJsonNull) {
                if (requireOrigin) {
                    throw StudioLoadException(
                        "$where: 缺字段 \"source\" —— 要么写一个来源文件（\"file\" 或 \"model\"），" +
                            "要么写 \"features\"（CAD 参数化历史）"
                    )
                }
                return null
            }
            if (!el.isJsonObject) throw StudioLoadException("$where: source 必须是对象")
            val o = el.asJsonObject
            // format 可以省略：那就按文件扩展名认领（见 StudioFormats.forFileName）
            val format = o.optStringOrNull("format")?.lowercase()
            if (format != null && StudioFormats.byId(format) == null) {
                throw StudioLoadException(
                    "$where: source.format=\"$format\" 没有对应的导入器；" +
                        "现在注册了的是 ${StudioFormats.ids()}（省略这个字段就按文件扩展名自动认领）"
                )
            }

            // 两种来源：file（磁盘，主推）优先于 model（资源路径）
            val file = o.optStringOrNull("file")
            val modelStr = o.optStringOrNull("model")
            if (requireOrigin && file == null && modelStr == null) {
                throw StudioLoadException(
                    "$where: source 里必须写一个来源 —— " +
                        "\"file\": \"config/gtetstudio/models/xxx.obj\"（磁盘，相对游戏目录）" +
                        " 或 \"model\": \"gtetcore:models/obj/xxx.obj\"（资源包）"
                )
            }
            if (file != null && modelStr != null) {
                throw StudioLoadException("$where: source 里 file 与 model 只能写一个（file 优先，但混着写只会让人困惑）")
            }
            val model = modelStr?.let {
                ResourceLocation.tryParse(it)
                    ?: throw StudioLoadException("$where: source.model \"$it\" 不是合法资源路径（要形如 gtetcore:models/obj/clock.obj）")
            }

            val textures = LinkedHashMap<String, String>()
            o.get("textures")?.let { texEl ->
                if (!texEl.isJsonObject) {
                    throw StudioLoadException("$where: source.textures 必须是对象（名字 → 贴图路径）")
                }
                for ((name, value) in texEl.asJsonObject.entrySet()) {
                    val s = value.asStringOrNull()
                        ?: throw StudioLoadException("$where: source.textures.$name 必须是字符串")
                    textures[name] = s
                }
            }

            // M0 的 forge:obj 选项：留着不报错，但会照实提示"已不再生效"
            val ignored = ArrayList<String>(3)
            for (key in FORGE_ONLY_OPTIONS) {
                if (o.has(key)) ignored += key
            }

            return StudioSourceDef(
                format = format,
                model = model,
                file = file,
                textures = textures,
                // 默认值与 Forge ObjLoader.read 的默认值保持一致（ObjLoader.java:55-59），
                // 便于老文件的行为不因为换了加载器而改变（其中只有 flip_v 现在还生效）
                automaticCulling = o.optBoolean("automatic_culling", true),
                shadeQuads = o.optBoolean("shade_quads", true),
                flipV = o.optBoolean("flip_v", false),
                emissiveAmbient = o.optBoolean("emissive_ambient", true),
                mtlOverride = o.optStringOrNull("mtl_override"),
                ignoredOptions = ignored,
            )
        }

        private fun parseMaterials(json: JsonObject, where: String): Map<String, StudioMaterialDef> {
            val out = LinkedHashMap<String, StudioMaterialDef>()
            json.get("materials")?.let { el ->
                if (!el.isJsonObject) throw StudioLoadException("$where: materials 必须是对象（材质名 → {render: ...}）")
                for ((name, value) in el.asJsonObject.entrySet()) {
                    if (!value.isJsonObject) {
                        throw StudioLoadException("$where: materials.$name 必须是对象，例如 {\"render\": \"cutout\"}")
                    }
                    val render = value.asJsonObject.optString("render", "cutout")
                    if (render !in KNOWN_RENDER_KINDS) {
                        throw StudioLoadException(
                            "$where: materials.$name.render=\"$render\" 不认识；" +
                                "M0 只支持 ${KNOWN_RENDER_KINDS.joinToString(" / ")}"
                        )
                    }
                    // ★ M3a：CAD（features）路径的贴图写在这里 —— 那条路没有 MTL。
                    //   OBJ 路径的贴图仍然只认 MTL 的 map_Kd，这里写了不会生效（载入时提示一句）。
                    out[name] = StudioMaterialDef(name, render, value.asJsonObject.optStringOrNull("texture"))
                }
            }
            return out
        }

        private fun parseParts(json: JsonObject, where: String): List<StudioPartDef> {
            val el = json.get("parts") ?: return emptyList()
            if (!el.isJsonArray) throw StudioLoadException("$where: parts 必须是数组")
            val out = ArrayList<StudioPartDef>()
            for ((i, item) in el.asJsonArray.withIndex()) {
                if (!item.isJsonObject) throw StudioLoadException("$where: parts[$i] 必须是对象，例如 {\"name\": \"body\"}")
                out += StudioPartDef(item.asJsonObject.reqString("name", "$where: parts[$i]"))
            }
            return out
        }

        private fun parseAnchor(json: JsonObject, where: String): StudioAnchor {
            val el = json.get("anchor") ?: return StudioAnchor.DEFAULT
            if (!el.isJsonObject) throw StudioLoadException("$where: anchor 必须是对象")
            val o = el.asJsonObject

            val mode = o.optString("mode", StudioAnchor.MODE_MACHINE)
            if (mode != StudioAnchor.MODE_MACHINE) {
                throw StudioLoadException("$where: anchor.mode=\"$mode\" 未实现；M0 只有 \"${StudioAnchor.MODE_MACHINE}\"")
            }
            val face = o.optString("face", StudioAnchor.FACE_FRONT)
            if (face != StudioAnchor.FACE_FRONT) {
                throw StudioLoadException("$where: anchor.face=\"$face\" 未实现；M0 只有 \"${StudioAnchor.FACE_FRONT}\"")
            }

            var ox = 0f
            var oy = 0f
            var oz = 0f
            o.get("offset")?.let { offEl ->
                if (!offEl.isJsonArray || offEl.asJsonArray.size() != 3) {
                    throw StudioLoadException("$where: anchor.offset 必须是长度 3 的数组，例如 [0, 1, 0]")
                }
                val a = offEl.asJsonArray
                ox = a[0].asFloatOrThrow("$where: anchor.offset[0]")
                oy = a[1].asFloatOrThrow("$where: anchor.offset[1]")
                oz = a[2].asFloatOrThrow("$where: anchor.offset[2]")
            }

            val scale = o.optFloat("scale", 1f)
            if (scale <= 0f) throw StudioLoadException("$where: anchor.scale=$scale 必须为正数")

            return StudioAnchor(mode, face, ox, oy, oz, scale)
        }

        private fun parseAnimation(json: JsonObject, where: String): StudioAnimationDef? {
            val el = json.get("animation") ?: return null
            if (!el.isJsonObject) throw StudioLoadException("$where: animation 必须是对象")
            val o = el.asJsonObject
            val driver = o.optString("driver", StudioClipDriver.DEFAULT)

            val tracks = ArrayList<StudioTrackDef>()
            o.get("tracks")?.let { tEl ->
                if (!tEl.isJsonArray) throw StudioLoadException("$where: animation.tracks 必须是数组")
                for ((i, item) in tEl.asJsonArray.withIndex()) {
                    val at = "$where: animation.tracks[$i]"
                    if (!item.isJsonObject) throw StudioLoadException("$at 必须是对象")
                    val t = item.asJsonObject

                    val part = t.reqString("part", at)
                    val channel = t.optString("channel", CHANNEL_ROT)
                    if (channel != CHANNEL_ROT) {
                        throw StudioLoadException(
                            "$at: channel=\"$channel\" 未实现；M0 只支持 \"$CHANNEL_ROT\"（匀速旋转），" +
                                "关键帧留给 M4"
                        )
                    }
                    val axisStr = t.optString("axis", "z")
                    val axis = when (axisStr.lowercase()) {
                        "x" -> Direction.Axis.X
                        "y" -> Direction.Axis.Y
                        "z" -> Direction.Axis.Z
                        else -> throw StudioLoadException("$at: axis=\"$axisStr\" 不认识；只支持 x / y / z")
                    }
                    val speed = t.optFloat("speed", 0f)
                    tracks += StudioTrackDef(
                        part = part,
                        channel = channel,
                        axis = axis,
                        speed = speed,
                        hasKeys = t.has("keys"),
                    )
                }
            }
            return StudioAnimationDef(driver, tracks)
        }
    }
}

/** `driver` 的已知取值（M0 只有 [gameTime] 真正实现）。 */
object StudioClipDriver {
    const val GAME_TIME = "gameTime"

    /** 设计文档 §4 里预留的那些，M0 解析得到但**不会动**，只在日志里提示。 */
    @JvmField
    val RESERVED = setOf("machine.progress", "machine.timeFlow", "machine.formed")

    const val DEFAULT = GAME_TIME
}

// ────────────────────────── JSON 取值小工具 ──────────────────────────
// 全部带「哪一段、哪个键、期望什么」的报错，杜绝 `asString()` 抛出的那句没有上下文的报错。

internal fun JsonObject.reqObject(name: String, where: String): JsonObject {
    val el = this.get(name) ?: throw StudioLoadException("$where: 缺字段 \"$name\"")
    if (!el.isJsonObject) throw StudioLoadException("$where: 字段 \"$name\" 必须是对象")
    return el.asJsonObject
}

internal fun JsonObject.reqString(name: String, where: String): String =
    this.get(name)?.asStringOrNull() ?: throw StudioLoadException("$where: 缺字段 \"$name\"（或它不是字符串）")

internal fun JsonObject.optString(name: String, def: String): String =
    this.get(name)?.asStringOrNull() ?: def

internal fun JsonObject.optStringOrNull(name: String): String? =
    this.get(name)?.asStringOrNull()

internal fun JsonObject.optBoolean(name: String, def: Boolean): Boolean {
    val el = this.get(name) ?: return def
    if (!el.isJsonPrimitive || !el.asJsonPrimitive.isBoolean) {
        throw StudioLoadException("字段 \"$name\" 必须是 true / false")
    }
    return el.asBoolean
}

internal fun JsonObject.optInt(name: String, def: Int): Int {
    val el = this.get(name) ?: return def
    if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
        throw StudioLoadException("字段 \"$name\" 必须是整数")
    }
    return el.asInt
}

/**
 * 取一个整数键，**没写返回 null**（写了但类型不对照样报错，不静默吞掉）。
 *
 * 为什么要区分"没写"和"写的 0"：视距的优先级是「模型 JSON > 全局默认」，
 * 得先知道模型到底写没写，才能决定要不要用全局默认（见 [StudioConfig.resolveViewDistance]）。
 */
internal fun JsonObject.optIntOrNull(name: String): Int? {
    val el = this.get(name) ?: return null
    if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
        throw StudioLoadException("字段 \"$name\" 必须是整数")
    }
    return el.asInt
}

internal fun JsonObject.optFloat(name: String, def: Float): Float {
    val el = this.get(name) ?: return def
    return el.asFloatOrThrow("字段 \"$name\"")
}

internal fun JsonElement.asStringOrNull(): String? =
    if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

internal fun JsonElement.asFloatOrThrow(what: String): Float {
    if (!isJsonPrimitive || !asJsonPrimitive.isNumber) {
        throw StudioLoadException("$what 必须是数字")
    }
    return asFloat
}