package rain.gtetcore.gtet.studio.data

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import rain.gtetcore.gtet.studio.config.stripJsonComments
import java.util.*
import kotlin.math.floor

/**
 * **把 `anchor.offset` 文本级地写回模型 JSON**（M2a 的 `/gtetstudio save`）。
 *
 * ## 为什么不用 gson 整份序列化回写（★ 这条是硬要求）
 * 我们的模型 JSON 是**带大量注释的 JSONC**：`clock.json` 里几乎每一段都有说明
 * （"offset 是在面坐标系里施加的"、"speed 是度/秒"……）。用 `JsonObject.toString()` 回写，
 * **注释全部丢失**，文件从"能读的文档"退化成"一堆数字"，这等于毁掉数据。
 *
 * 所以做法是**文本级字段替换**：
 * ```
 * 1. 在文本里定位顶层 "anchor" 段（扫描时跳过字符串与注释，不受注释里的大括号干扰）
 * 2. 在 anchor 对象里定位 "offset"
 *    ├── 有 → 只替换 [ ... ] 方括号**之间**的那几个数字，缩进/换行/其余文本一字不动
 *    └── 没有 → 在 anchor 对象的 '{' 之后按它的同级缩进插入一行 "offset": [...]
 * 3. 把新文本**重新解析一遍核对数值**，核对不过就抛异常（调用方不写盘）
 * ```
 *
 * ## 拒绝写的情况（明确报错，绝不"尽力而为"地改）
 * - 没有顶层 `anchor` 段（说明这份文件的结构和我们认识的不一样）；
 * - `anchor` 不是对象、`offset` 不是数组；
 * - `offset` 数组里**带注释**（多行数组 `0, // x` 那种）—— 文本级替换会把注释吃掉；
 * - 语法本身残缺（括号不配对），扫不下去。
 *
 * ## 放哪、为什么不放 interaction/editor
 * 这是**序列化**的事，属于 `data/`（设计文档 §3：data = JSON 数据模型 + 序列化 + 热重载）。
 * 它**一行 Minecraft 都不 import**，所以能跟自检一起脱机跑。
 *
 * @author rain fox
 */
object StudioAnchorWriter {

    /** 关键字段名（只有这里定义）。 */
    private const val KEY_ANCHOR = "anchor"
    private const val KEY_OFFSET = "offset"

    /**
     * 把 [offsetX]/[offsetY]/[offsetZ] 写进 [text] 的 `anchor.offset`，返回**新文本**。
     *
     * @throws StudioAnchorWriteException 任何无法安全替换的情况（调用方必须原样保留文件）
     */
    @JvmStatic
    fun replaceOffset(text: String, offsetX: Float, offsetY: Float, offsetZ: Float): String {
        val scanner = JsonTextScanner(text)

        val rootStart = scanner.rootObjectStart()
            ?: throw StudioAnchorWriteException("文件根节点不是 JSON 对象（找不到最外层的 '{'）")

        val anchorValue = scanner.findMemberValue(rootStart, KEY_ANCHOR)
            ?: throw StudioAnchorWriteException(
                "文件里没有顶层 \"$KEY_ANCHOR\" 段 —— 文本级改写只认它，拒绝乱改本文件"
            )
        if (!scanner.isObject(anchorValue)) {
            throw StudioAnchorWriteException("\"$KEY_ANCHOR\" 的值不是对象（是个 ${scanner.describe(anchorValue)}）")
        }

        val replacement = "[${number(offsetX)}, ${number(offsetY)}, ${number(offsetZ)}]"
        val offsetValue = scanner.findMemberValue(anchorValue.first, KEY_OFFSET)

        val newText = if (offsetValue == null) {
            insertOffset(text, anchorValue.first, replacement, scanner)
        } else {
            replaceArray(text, offsetValue, replacement, scanner)
        }

        verify(newText, offsetX, offsetY, offsetZ)
        return newText
    }

    // ────────────────────────── 两条改写路径 ──────────────────────────

    /** `offset` 已存在：把 `[ ... ]` 整段换成新的（方括号以内的**其余文本一字不动**）。 */
    private fun replaceArray(text: String, value: IntRange, replacement: String, scanner: JsonTextScanner): String {
        // ⚠️ 值的区间是**半开**的（最后一位不含）：所以闭合方括号就在 value.last 上
        if (text[value.first] != '[' || text[value.last] != ']') {
            throw StudioAnchorWriteException(
                "\"$KEY_OFFSET\" 的值不是数组（是个 ${scanner.describe(value)}）—— 文本级改写只认数组形式"
            )
        }
        val innerStart = value.first + 1
        val innerEnd = value.last // 指向 ']'
        val inner = text.substring(innerStart, innerEnd)
        if (inner.contains("//") || inner.contains("/*")) {
            throw StudioAnchorWriteException(
                "\"$KEY_OFFSET\" 数组里带注释 —— 直接替换会把注释吃掉，所以拒绝改写；" +
                    "请把注释挪到数组外面再存盘"
            )
        }
        // replacement 自带方括号 ⇒ 连方括号一起换掉（value.last 就是 ']' 的位置）
        return text.substring(0, value.first) + replacement + text.substring(value.last + 1)
    }

    /** `offset` 不存在：在 anchor 对象的 '{' 之后按同级缩进插一行。 */
    private fun insertOffset(text: String, anchorObjStart: Int, replacement: String, scanner: JsonTextScanner): String {
        val braceIndent = lineIndent(text, anchorObjStart)
        val memberIndent = "$braceIndent  "
        val afterBrace = scanner.skipTrivia(anchorObjStart + 1)

        return if (afterBrace < text.length && text[afterBrace] == '}') {
            // 空对象 {} → 换成 { 换行 "offset": [...] 换行 }
            text.substring(0, anchorObjStart + 1) +
                "\n$memberIndent\"$KEY_OFFSET\": $replacement\n$braceIndent" +
                text.substring(afterBrace)
        } else {
            // 非空 → 直接插在 '{' 之后当第一个成员，记得带逗号
            text.substring(0, anchorObjStart + 1) +
                "\n$memberIndent\"$KEY_OFFSET\": $replacement," +
                text.substring(anchorObjStart + 1)
        }
    }

    // ────────────────────────── 安全网 ──────────────────────────

    /**
     * 把新文本重新解析一遍，核对 `anchor.offset` 真的等于要写的值。
     *
     * 这一步是"宁可不写"的最后一道闸：**只要核对不过就抛异常**，
     * 调用方拿不到新文本，也就不会写盘（文件保持原样）。
     */
    private fun verify(newText: String, x: Float, y: Float, z: Float) {
        val parsed = try {
            JsonParser.parseString(stripJsonComments(newText))
        } catch (e: JsonSyntaxException) {
            throw StudioAnchorWriteException("改写后的文本不是合法 JSON（${e.message}）—— 已放弃写回，文件保持原样", e)
        }
        if (!parsed.isJsonObject) {
            throw StudioAnchorWriteException("改写后的根节点不是对象 —— 已放弃写回，文件保持原样")
        }
        val anchor = parsed.asJsonObject.get(KEY_ANCHOR)
        if (anchor == null || !anchor.isJsonObject) {
            throw StudioAnchorWriteException("改写后 \"$KEY_ANCHOR\" 段丢了 —— 已放弃写回，文件保持原样")
        }
        val offset = anchor.asJsonObject.get(KEY_OFFSET)
        if (offset == null || !offset.isJsonArray || offset.asJsonArray.size() != 3) {
            throw StudioAnchorWriteException(
                "改写后 \"$KEY_OFFSET\" 不是长度 3 的数组 —— 已放弃写回，文件保持原样"
            )
        }
        val array = offset.asJsonArray
        val actual = floatArrayOf(array[0].asFloat, array[1].asFloat, array[2].asFloat)
        val expected = floatArrayOf(x, y, z)
        for (i in 0..2) {
            if (kotlin.math.abs(actual[i] - expected[i]) > 1e-4f) {
                throw StudioAnchorWriteException(
                    "核对失败：写进去的 [${actual.joinToString()}] 与期望的 [${expected.joinToString()}] 不一致" +
                        " —— 已放弃写回，文件保持原样"
                )
            }
        }
    }

    // ────────────────────────── 小工具 ──────────────────────────

    /**
     * 数字的写法：整数不带小数点（`1` 而不是 `1.0`），小数最多 4 位、不留尾零。
     *
     * 与 `interaction/StudioNumberFormat` 的规则一致（两份实现各自独立，
     * 且这边写完会重新解析核对，所以格式漂移不会变成静默损坏）。
     */
    internal fun number(value: Float): String {
        if (!value.isFinite()) {
            throw StudioAnchorWriteException("偏移里出现了非有限值 $value —— 拒绝写回")
        }
        val v = if (value == 0f) 0f else value
        if (v == floor(v.toDouble()).toFloat() && kotlin.math.abs(v) < 1e7f) {
            return v.toLong().toString()
        }
        var text = String.format(Locale.ROOT, "%.4f", v)
        if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
        return if (text.isEmpty() || text == "-") "0" else text
    }

    /** [index] 所在行的前导空白（插新行时照着它缩进）。 */
    private fun lineIndent(text: String, index: Int): String {
        var start = index
        while (start > 0 && text[start - 1] != '\n') start--
        val sb = StringBuilder()
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }
}

/**
 * 一个**只认结构、不建模型**的 JSONC 扫描器。
 *
 * 关键点：跳过字符串与注释（`//`、`/* */`）—— 我们的文件里注释里出现 `{`、`[`、`"offset"` 都可能有，
 * 不跳就一定会定位错，而定位错就意味着改坏文件。
 */
private class JsonTextScanner(private val text: String) {

    /** 跳过前导空白/注释后，根对象的 '{' 在哪；不是对象则 null。 */
    fun rootObjectStart(): Int? {
        var i = skipTrivia(0)
        if (i >= text.length || text[i] != '{') return null
        return i
    }

    fun skipTrivia(from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when (c) {
                ' ', '\t', '\r', '\n', '\uFEFF' -> i++
                '/' if i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                '/' if i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = if (i + 1 < text.length) i + 2 else text.length
                }
                else -> return i
            }
        }
        return i
    }

    /** 从 `"` 开始，返回闭合引号的下标；扫不到（文件残缺）抛异常。 */
    private fun stringEnd(quoteIndex: Int): Int {
        var i = quoteIndex + 1
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> return i
            }
            i++
        }
        throw StudioAnchorWriteException("字符串没有闭合引号（文件残缺）—— 拒绝改写")
    }

    /** 值的结束下标（exclusive）。 */
    private fun valueEnd(start: Int): Int {
        if (start >= text.length) throw StudioAnchorWriteException("文件在期待一个值的地方结束了")
        return when (val c = text[start]) {
            '"' -> stringEnd(start) + 1
            '{' -> matchingBracket(start, '{', '}') + 1
            '[' -> matchingBracket(start, '[', ']') + 1
            else -> {
                var i = start
                while (i < text.length) {
                    val d = text[i]
                    if (d == ',' || d == '}' || d == ']' || d == ' ' || d == '\t' || d == '\r' || d == '\n') break
                    if (d == '/' && i + 1 < text.length && (text[i + 1] == '/' || text[i + 1] == '*')) break
                    i++
                }
                if (i == start) throw StudioAnchorWriteException("在位置 $start 读到无法识别的字符 '$c'")
                i
            }
        }
    }

    /** 括号配对（跳过字符串与注释）。 */
    private fun matchingBracket(openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '"' -> i = stringEnd(i)
                '/' if i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                '/' if i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = if (i + 1 < text.length) i + 2 else text.length
                    continue
                }
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        throw StudioAnchorWriteException("括号 '$open' 没有配对的 '$close'（文件残缺）—— 拒绝改写")
    }

    fun isObject(value: IntRange): Boolean = value.first < text.length && text[value.first] == '{'

    /** 诊断用：告诉用户"这个值其实是什么"。 */
    fun describe(value: IntRange): String = when {
        value.first >= text.length -> "空"
        text[value.first] == '[' -> "数组"
        text[value.first] == '"' -> "字符串"
        text[value.first] == '{' -> "对象"
        else -> "字面量"
    }

    /**
     * 在 `objStart` 这个对象的**直接成员**里找 [key]，返回它的值区间（半开）。
     *
     * 只扫直接成员：值一律整块跳过，所以嵌套对象里的同名键不会被误认。
     */
    fun findMemberValue(objStart: Int, key: String): IntRange? {
        if (objStart >= text.length || text[objStart] != '{') {
            throw StudioAnchorWriteException("期待一个对象（'{'），实际位置 $objStart")
        }
        var i = objStart + 1
        while (true) {
            i = skipTrivia(i)
            if (i >= text.length) throw StudioAnchorWriteException("对象没有闭合的 '}'（文件残缺）—— 拒绝改写")
            when (text[i]) {
                '}' -> return null
                ',' -> {
                    i++
                    continue
                }
                '"' -> {
                    val keyEnd = stringEnd(i)
                    val name = text.substring(i + 1, keyEnd)
                    var j = skipTrivia(keyEnd + 1)
                    if (j >= text.length || text[j] != ':') {
                        throw StudioAnchorWriteException("键 \"$name\" 后面不是 ':'（文件残缺）—— 拒绝改写")
                    }
                    j = skipTrivia(j + 1)
                    val end = valueEnd(j)
                    if (name == key) return j until end
                    i = end
                }
                else -> throw StudioAnchorWriteException("在位置 $i 读到无法识别的字符 '${text[i]}'（文件残缺）—— 拒绝改写")
            }
        }
    }
}