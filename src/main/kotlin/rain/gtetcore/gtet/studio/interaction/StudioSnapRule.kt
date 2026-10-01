package rain.gtetcore.gtet.studio.interaction

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * **吸附规则**（设计文档 §6「拖拽语义，手感全在这里」）。
 *
 * | 修饰键 | 步长 |
 * |---|---|
 * | 无 | **0.25 格**（默认，能用又不会太黏） |
 * | Ctrl | **1 格**（对齐方块网格） |
 * | Shift | **不吸附**（精细模式，跟手） |
 *
 * 两条纪律：
 * 1. **吸附的是"绝对偏移"，不是"每帧增量"** —— 增量吸附会在拖动过程中累积舍入误差，
 *    手感上表现为"越拖越偏"。这里每帧都是 `snap(起始值 + 总增量)`，所以松手时一定落在网格上。
 * 2. **只吸附被允许的轴**（[StudioDragSession] 负责屏蔽其余分量）——
 *    否则拖 X 会把文件里原本不是 0.25 倍数的 Y 悄悄改掉，那是静默的数据损坏。
 *
 * @author rain fox
 */
class StudioSnapRule private constructor(
    /** 步长（格）；null = 不吸附。 */
    val step: Double?,
) {

    /** 反馈里显示的名字。 */
    val label: String get() = when (step) {
        null -> "不吸附（精细）"
        QUARTER_STEP -> "0.25 格"
        FULL_STEP -> "1 格"
        else -> "${StudioNumberFormat.trim(step)} 格"
    }

    fun snap(value: Double): Double {
        val s = step ?: return value
        if (s <= 0.0 || !value.isFinite()) return value
        return (value / s).roundToLong().toDouble() * s
    }

    /** 浮点版（偏移是 Float）：0.25/1 这两种步长在 Float 上是精确的，不会产生脏尾巴。 */
    fun snap(value: Float): Float = snap(value.toDouble()).toFloat()

    override fun toString(): String = label

    companion object {

        const val QUARTER_STEP: Double = 0.25
        const val FULL_STEP: Double = 1.0

        /** 默认：0.25 格。 */
        @JvmField
        val QUARTER = StudioSnapRule(QUARTER_STEP)

        /** Ctrl：1 格。 */
        @JvmField
        val FULL = StudioSnapRule(FULL_STEP)

        /** Shift：不吸附。 */
        @JvmField
        val OFF = StudioSnapRule(null)

        /**
         * 按修饰键挑规则。
         *
         * **两个都按住时以 Shift（不吸附）为准** —— 精细操作是更强的意图，
         * 而且 0.25 格吸附下按 Ctrl 想切 1 格、手滑按住 Shift 的代价只是"这次没吸附"，
         * 反过来（按住 Shift 却被吸到 1 格）会让人以为工具坏了。
         */
        @JvmStatic
        fun of(ctrl: Boolean, shift: Boolean): StudioSnapRule = when {
            shift -> OFF
            ctrl -> FULL
            else -> QUARTER
        }

        /** 这个值是不是已经落在网格上（自检与反馈用）。 */
        @JvmStatic
        fun onGrid(value: Double, step: Double): Boolean =
            abs(value - (value / step).roundToLong() * step) < 1e-6
    }
}