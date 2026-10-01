package rain.gtetcore.gtet.studio.interaction

import rain.gtetcore.gtet.studio.interaction.StudioNumberFormat.trim
import java.util.*

/**
 * 把数字打成**给人看**的样子（聊天栏/动作栏反馈用），纯 Kotlin。
 *
 * 规则与写回 JSON 时那套（`data/StudioAnchorWriter` 内部私有实现）**故意一致**：
 * 整数不带小数点（`1` 而不是 `1.0`），小数最多 4 位且不留尾零（`0.25`）。
 * 两份实现没有互相依赖，是为了让它们各自能被独立验证 —— 而写回那一份
 * **写完之后会把文件重新解析一遍核对数值**，所以格式漂移不会变成静默的数据损坏。
 *
 * @author rain fox
 */
object StudioNumberFormat {

    /** 最多保留的小数位。0.25 / 1 格吸附都远在这个精度内。 */
    private const val MAX_DECIMALS = 4

    @JvmStatic
    fun trim(value: Double): String {
        if (!value.isFinite()) return value.toString()
        // -0.0 打成 "0"：否则动作栏里会出现 "-0"，看着像 bug
        val v = if (value == 0.0) 0.0 else value
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e7) {
            return v.toLong().toString()
        }
        var text = String.format(Locale.ROOT, "%.${MAX_DECIMALS}f", v)
        if (text.contains('.')) {
            text = text.trimEnd('0').trimEnd('.')
        }
        return if (text.isEmpty() || text == "-") "0" else text
    }

    @JvmStatic
    fun trim(value: Float): String = trim(value.toDouble())

    /**
     * **固定小数位**（与 [trim] 相反：`1` 打成 `1.00`，不留尾零）。
     *
     * 动作反馈行要的是"三个分量一眼可比"，所以那边固定两位；`-0.00` 打成 `0.00`
     * （否则面板上会冒出一个看着像 bug 的负零）。
     */
    @JvmStatic
    fun fixed(value: Float, digits: Int = 2): String {
        val v = if (value == 0f) 0f else value
        return String.format(Locale.ROOT, "%.${digits}f", v)
    }

    /**
     * **动作反馈行的正文**（M2c 的面板版式，用户点名的格式）：
     * ```
     * gtet:test_clock (0.00, 1.00, 0.00) -> (0.00, 1.25, 0.00)
     * ```
     * - 模型 id 在**开头**，后面紧跟两个三元组，箭头是 **ASCII `->`**、数字固定两位小数；
     * - 放在这个纯 Kotlin 文件里（而不是拼在屏幕类里）有两个理由：
     *   ① 屏幕只管"画在屏幕上方那一行"，不必去解析/裁切别人给的字符串；
     *   ② 它是**用户看得见的输出**，放这儿就能被脱机自检逐字钉住（见 ⑨ 那一节）。
     */
    @JvmStatic
    fun stepLine(modelId: String, before: StudioAnchorOffset, after: StudioAnchorOffset): String =
        "$modelId (${fixed(before.x)}, ${fixed(before.y)}, ${fixed(before.z)})" +
            " -> (${fixed(after.x)}, ${fixed(after.y)}, ${fixed(after.z)})"
}