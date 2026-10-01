package rain.gtetcore.gtet.studio.format

import rain.gtetcore.gtet.studio.kernel.StudioMesh

/**
 * 一次导入的**上下文** —— 让 format 层既能拿到该拿的信息、又不必碰 IO / Minecraft。
 *
 * @param where          出错时写进消息里的「这是哪份文件」，例如 `clock.json → config/gtetstudio/models/clock.obj`
 * @param textureAliases MTL 里 `map_Kd #名字` 的别名表（来自 JSON 的 `source.textures`）
 * @param flipV          是否翻转 UV 的 V 轴。**默认 true**：OBJ 的 `vt` 原点在左下、贴图原点在左上，
 *                       两边约定相反。M0 的 JSON 里写的是 `flip_v: true`，与这里一致。
 * @param openSibling    读同目录的兄弟文件（MTL）。**由 `data/` 层注入**：
 *                       资源包来源按 `assets/<ns>/<目录>/<名字>` 找，磁盘来源按 OBJ 同级目录找。
 *                       返回 null 表示找不到（不抛异常，让上层给一条清楚的日志）。
 * @param mtlOverride    强制指定 MTL（JSON 的 `source.mtl_override`），**替代** OBJ 里的 `mtllib`
 *
 * @author rain fox
 */
class StudioImportContext(
    val where: String,
    val textureAliases: Map<String, String> = emptyMap(),
    val flipV: Boolean = true,
    val openSibling: (String) -> ByteArray? = { null },
    val mtlOverride: String? = null,
) {

    /** 展开 `#别名`；表里没有就返回 null（调用方负责报"别名没定义"）。 */
    fun expandAlias(value: String): String? =
        if (value.startsWith("#")) textureAliases[value.substring(1)] else value
}

/** 一条问题记录的严重程度。 */
enum class StudioNoteSeverity {
    /** 只是说明（例如"这个模型没有 vn，法线按面法线算"）。 */
    INFO,

    /** 坏行被跳过了，或某个字段没人认领 —— 模型还能出来，但用户该知道。 */
    WARN,

    /** 明确的坏数据（索引越界、别名没定义），相关的那一部分被丢弃。 */
    ERROR,
}

/** 一条问题记录：第几行、原文、人话。 */
class StudioImportNote(
    val severity: StudioNoteSeverity,
    val line: Int,
    val text: String,
    val message: String,
) {
    override fun toString(): String = "[$severity 第${line}行] $message  ← `${text.trim()}`"
}

/**
 * 收问题的小篮子：**计数一定有，明细最多存 [limit] 条**。
 *
 * 为什么要有上限：一个坏掉的导出文件可能几万行全是坏行，全存下来只为了刷屏没意义；
 * 但"到底坏了几处"必须准确（这是用户判断"要不要去修模型"的唯一依据）。
 */
class StudioNoteSink(private val limit: Int = 50) {

    private val kept = ArrayList<StudioImportNote>()

    var infoCount: Int = 0
        private set
    var warnCount: Int = 0
        private set
    var errorCount: Int = 0
        private set

    fun info(line: Int, text: String, message: String) = add(StudioNoteSeverity.INFO, line, text, message)

    fun warn(line: Int, text: String, message: String) = add(StudioNoteSeverity.WARN, line, text, message)

    fun error(line: Int, text: String, message: String) = add(StudioNoteSeverity.ERROR, line, text, message)

    fun add(severity: StudioNoteSeverity, line: Int, text: String, message: String) {
        when (severity) {
            StudioNoteSeverity.INFO -> infoCount++
            StudioNoteSeverity.WARN -> warnCount++
            StudioNoteSeverity.ERROR -> errorCount++
        }
        if (kept.size < limit) kept += StudioImportNote(severity, line, text, message)
    }

    /** 明细（不含被限流丢掉的那些）。 */
    fun notes(): List<StudioImportNote> = kept

    /** 一条汇总（日志与 `/gtetstudio reload` 的聊天栏都用它）。 */
    fun summary(): String = buildString {
        append("问题 ")
        append(errorCount + warnCount)
        append(" 处（错误 ")
        append(errorCount)
        append(" / 警告 ")
        append(warnCount)
        append("）")
        if (infoCount > 0) {
            append("，另有提示 ")
            append(infoCount)
            append(" 条")
        }
        if (kept.size < errorCount + warnCount) {
            append("；明细只报了前 ")
            append(kept.size)
            append(" 条")
        }
    }
}

/**
 * MTL 里的一个材质（`newmtl`）。
 *
 * @param name     材质名（JSON 的 `materials` 段就是按这个名字声明的）
 * @param texture  `map_Kd` 的取值，`#别名` 已展开成真实值；没写就是 null
 * @param diffuseR/G/B  `Kd`（默认 1,1,1）
 * @param opacity  `d`（默认 1）
 * @param declared 这个材质是不是真的在 MTL 里出现过（false = OBJ 用了它、MTL 没定义）
 */
class StudioImportedMaterial(
    val name: String,
    val texture: String?,
    val diffuseR: Float = 1f,
    val diffuseG: Float = 1f,
    val diffuseB: Float = 1f,
    val opacity: Float = 1f,
    val declared: Boolean = true,
)

/**
 * 一次导入的产物：**网格 + 材质表 + 问题汇总**。
 *
 * @param mesh      解析出来的网格（含按 `g`/`o` 分的组）
 * @param materials MTL 里的材质表（**按出现顺序**）
 * @param mtlLibs   OBJ 里 `mtllib` 指向的文件名（可能多个）
 * @param notes     问题明细（计数见 [noteSink]）
 * @param noteSink  计数器本体（明细被限流时，计数仍然准确）
 */
class StudioImportResult(
    val mesh: StudioMesh,
    val materials: List<StudioImportedMaterial>,
    val mtlLibs: List<String>,
    val noteSink: StudioNoteSink,
) {

    val notes: List<StudioImportNote> get() = noteSink.notes()

    fun material(name: String): StudioImportedMaterial? = materials.firstOrNull { it.name == name }
}