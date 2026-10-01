package rain.gtetcore.gtet.studio.editor

import rain.gtetcore.gtet.studio.editor.StudioEditorInput.onEscape
import kotlin.math.pow

/**
 * **编辑器模式（M2b）的"输入 → 状态机"纯逻辑** —— 一行 MC / GLFW / Forge 都不 import。
 *
 * ## 为什么单独抽出来
 * M2b 之后输入改由 [StudioEditorScreen] 独占（原版在所有屏幕打开时都会把鼠标/键盘事件
 * 全部转给屏幕，见 `MouseHandler.onPress` 的 `minecraft.screen != null` 分支与
 * `KeyboardHandler` 里那段 `if (screen == null)` 的 KeyMapping 更新）。
 * 于是"这一下按下该不该起拖""Esc 该取消拖拽还是该退出编辑"变成了**屏幕与交互层之间的判决**，
 * 而这种判决**只有实机能试手感、没有实机就完全没法验**。把判决抽成纯函数之后
 * [rain.gtetcore.gtet.studio.interaction.StudioInteractionSelfCheck] 就能脱机钉住它：
 * 手感留给用户，逻辑留给自检。
 *
 * ## 判决表
 * | 事件 | 条件 | 动作 |
 * |---|---|---|
 * | 左键按下 | 命中手柄且没在拖 | [StudioEditorAction.BEGIN_DRAG] |
 * | 左键按下 | 没命中手柄 / 已经在拖 | [StudioEditorAction.NONE]（屏幕上不挖方块，因为 Screen 已经吃掉了） |
 * | 左键松开 | 确实在拖 | [StudioEditorAction.END_DRAG]（松手才产生**一条**命令） |
 * | Esc | 在拖 | [StudioEditorAction.CANCEL_DRAG]（回到按下前的值，**不入撤销栈**） |
 * | Esc | 没在拖 | [StudioEditorAction.EXIT_EDITOR] |
 * | Ctrl+Z / Ctrl+Shift+Z | —— | 撤销 / 重做 |
 * | `H`（M2d） | 命令框关着 | [StudioEditorAction.TOGGLE_DETAILS]（收起 / 展开详情框） |
 *
 * ## 命令框（M2c）
 * 命令框开着时**先**过 [onKeyTarget] 这一关：Tab / Esc = 收起、回车 = 写入，
 * 其余按键（含 WASD 与 Ctrl+Z）一律判给命令框，编辑器什么都收不到。
 * "字符串 → 三个数"由 [parseVec3] 负责（同样是纯函数，失败给中文原因、绝不抛异常）。
 *
 * 右键**不在这里**：转视角是屏幕自己的相机逻辑（`mouseDragged` 里直接转），
 * 与 gizmo 的状态机无关 —— 这里对它一律返回 [StudioEditorAction.NONE]，
 * 自检里用这条钉住"右键永远不会误起一次拖拽"。
 *
 * @author rain fox
 */

/** 编辑器认的鼠标键（其余中键/侧键一律 [OTHER]，不参与任何编辑器动作）。 */
enum class StudioMouseButton {
    LEFT,
    RIGHT,
    OTHER;

    companion object {

        /**
         * GLFW 键码 → 三个桶。
         *
         * ⚠️ 这里的 `0` / `1` 就是 `GLFW_MOUSE_BUTTON_LEFT` / `_RIGHT`（GLFW 的 ABI 常量，不会变）。
         * 刻意写成裸数字而**不 import GLFW**：本文件要能在只有 kotlin-stdlib 的脱机自检里跑。
         * 屏幕那边照旧用 `GLFW.GLFW_MOUSE_BUTTON_*` 常量，两边不会各说各话 ——
         * 自检里有一条 `buttonOf(0/1/2)` 的断言把这两个口径焊死。
         */
        @JvmStatic
        fun of(glfwButton: Int): StudioMouseButton = when (glfwButton) {
            0 -> LEFT
            1 -> RIGHT
            else -> OTHER
        }
    }
}

/**
 * 键盘上"编辑器在乎的键"。
 *
 * [UNDO_KEY] / [REDO_KEY] 是**键位表里的那个键**（默认 Z / Y），不是裸键码 ——
 * 屏幕用 `StudioKeyMappings.UNDO.isActiveAndMatches(...)` 判出来，
 * 所以玩家在控制界面改过键位之后照样管用。
 *
 * [TAB] / [ENTER]（M2c 起）是**命令框**的键：Tab 调出/收起、回车写入。
 * 它们进这个枚举是为了让"这一下该给命令框还是给编辑器"也能被脱机自检钉住
 * （见 [StudioEditorInput.onKeyTarget]）—— 否则那套判决只活在屏幕类里，没实机就验不了。
 *
 * [DETAILS_KEY]（M2d 起）是**详情框的开合热键**（`H`：详情 / hide 两个意思都说得通，
 * 且与 Tab / Esc / 回车 / WASD / 空格 / Shift / Ctrl+Z / Ctrl+Y **都不冲突**）。
 */
enum class StudioEditorKey {
    ESCAPE,
    TAB,
    ENTER,
    DETAILS_KEY,
    UNDO_KEY,
    REDO_KEY,
    OTHER,
}

/**
 * **一次按键归谁处理**（M2c 的命令框 vs 编辑器）。
 *
 * 屏幕的 `keyPressed` 第一件事就是问 [StudioEditorInput.onKeyTarget]，然后照着走 ——
 * 这条判决必须是纯的，因为"打字时玩家会不会跟着飞""命令框开着按 Esc 会不会把编辑退掉"
 * 这类问题只有实机才看得见，而判决本身可以脱机验。
 */
enum class StudioKeyTarget {

    /** 编辑器：照旧走 [StudioEditorInput.onKey]（撤销 / 取消拖拽 / 退出编辑）。 */
    EDITOR,

    /** 命令框自己吃（打字、退格、方向键、Ctrl+A/C/V）—— **不许**漏给编辑器。 */
    INPUT_BOX,

    /** Tab / Esc：**只**收起命令框（不取消拖拽、不退出编辑）。 */
    CLOSE_INPUT,

    /** 回车：把命令框里的坐标写进 `anchor.offset`。 */
    APPLY_INPUT,
}

/**
 * 命令框里那串文本的解析结果（**坐标**那一支）。
 *
 * 失败时**给出中文原因**（直接显示给用户），且**绝不抛异常** —— 用户敲进来的任何东西
 * 都是合法输入，不是异常。
 */
sealed class StudioVectorParse {

    /** 解析成功：三个有限数。 */
    data class Ok(val x: Float, val y: Float, val z: Float) : StudioVectorParse()

    /** 解析失败：[reason] 是一句能直接给用户看的话。 */
    data class Bad(val reason: String) : StudioVectorParse()
}

/**
 * **命令框里那一行到底算什么**（纯函数）。
 *
 * 一个框同时认两种东西，靠这个判决分流：
 * - 三个数 ⇒ 当**坐标**用（写 `anchor.offset`，和拖拽同一条路）；
 * - 其余 ⇒ 当**命令**用（交给客户端命令表执行，见 [StudioCommandBox]）。
 *
 * 边界必须是纯判决：敲进去的东西五花八门（`save`、`/gtetstudio save`、`1 2 3`、`1 2`、空行……），
 * 每一种的去向都要能被脱机自检钉住。
 */
sealed class StudioCommandLine {

    /** 空行（或只有一个 `/`）：什么都不做，也不收框。 */
    object Empty : StudioCommandLine()

    /** 裸坐标三元组 ⇒ 走 [StudioEditorInput.parseVec3] 那条路写 `anchor.offset`。 */
    data class Offset(val x: Float, val y: Float, val z: Float) : StudioCommandLine()

    /**
     * **看着像坐标、但解析不出来**（`"1 2"`、`"1 2 NaN"`、`"1e999 0 0"`）。
     *
     * 单列一类是为了不把它当命令去报"不认识的命令"—— 那对用户毫无帮助；
     * 这里直接把坐标解析失败的原因说出来（比如"要 3 个数字，收到 2 个"）。
     */
    data class BadOffset(val reason: String) : StudioCommandLine()

    /** 一条命令，**已经去掉前导 `/`**（`/gtetstudio save` → `gtetstudio save`）。 */
    data class Command(val text: String) : StudioCommandLine()
}

/** 一次输入判决出来的结果（屏幕/交互层照着执行）。 */
enum class StudioEditorAction {

    /** 什么都不做。 */
    NONE,

    /** 左键命中手柄：起拖。 */
    BEGIN_DRAG,

    /** 左键松开：把这次拖拽收成**一条**命令。 */
    END_DRAG,

    /** Esc 且正在拖：取消这次拖拽（回到按下前的值，不入撤销栈）。 */
    CANCEL_DRAG,

    /** Esc 且没在拖：退出编辑器模式并关屏。 */
    EXIT_EDITOR,

    /**
     * `H`（M2d）：收起 / 展开左上角那块详情框（六行信息 + 命令框）。
     *
     * 它是**纯面板动作** —— 不碰拖拽、不碰撤销栈、不碰相机，所以放行条件最松：
     * 拖拽中也照切（面板挡不挡视野与手上正在拖什么无关）。
     */
    TOGGLE_DETAILS,

    UNDO,
    REDO,
}

object StudioEditorInput {

    /** 相机移动速度的上下限（格 / tick；1 格/tick = 20 格/秒）。 */
    const val MIN_SPEED: Double = 0.05
    const val MAX_SPEED: Double = 4.0

    /** 进编辑器的默认速度：0.6 格/tick = 12 格/秒（比原版创造飞行快一点，好绕机器看）。 */
    const val DEFAULT_SPEED: Double = 0.6

    /** 滚轮一格的速度倍率（乘性：快慢两头都好调）。 */
    const val SPEED_STEP: Double = 1.25

    /** 左键按下。 */
    @JvmStatic
    fun onPress(button: StudioMouseButton, hitHandle: Boolean, dragging: Boolean): StudioEditorAction = when {
        button != StudioMouseButton.LEFT -> StudioEditorAction.NONE
        dragging -> StudioEditorAction.NONE
        hitHandle -> StudioEditorAction.BEGIN_DRAG
        else -> StudioEditorAction.NONE
    }

    /** 鼠标键松开。 */
    @JvmStatic
    fun onRelease(button: StudioMouseButton, dragging: Boolean): StudioEditorAction = when {
        button != StudioMouseButton.LEFT -> StudioEditorAction.NONE
        dragging -> StudioEditorAction.END_DRAG
        else -> StudioEditorAction.NONE
    }

    /**
     * Esc 的判决 —— **M2b §3 的核心那一条**。
     *
     * 拖拽中 ⇒ 只取消这一次拖拽（值回到按下前，撤销栈一条不加），**不退出编辑**；
     * 没在拖 ⇒ 退出编辑器模式。
     */
    @JvmStatic
    fun onEscape(dragging: Boolean): StudioEditorAction =
        if (dragging) StudioEditorAction.CANCEL_DRAG else StudioEditorAction.EXIT_EDITOR

    /** 键盘。Esc 走 [onEscape]；撤销/重做一律要求 Ctrl（裸按 Z 不当撤销）。 */
    @JvmStatic
    fun onKey(key: StudioEditorKey, ctrl: Boolean, shift: Boolean, dragging: Boolean): StudioEditorAction =
        when (key) {
            StudioEditorKey.ESCAPE -> onEscape(dragging)
            // Tab / 回车是**命令框**的键（见 [onKeyTarget]）：命令框没开时它们也不该产生编辑器动作
            StudioEditorKey.TAB, StudioEditorKey.ENTER -> StudioEditorAction.NONE
            // H（M2d）= 详情框开合：**与拖拽、修饰键都无关**（它只动面板，不碰值）
            StudioEditorKey.DETAILS_KEY -> StudioEditorAction.TOGGLE_DETAILS
            // Ctrl+Y 是主键位；Ctrl+Shift+Z 是很多人习惯的那套，一起认
            StudioEditorKey.UNDO_KEY -> when {
                !ctrl -> StudioEditorAction.NONE
                shift -> StudioEditorAction.REDO
                else -> StudioEditorAction.UNDO
            }

            StudioEditorKey.REDO_KEY -> if (ctrl) StudioEditorAction.REDO else StudioEditorAction.NONE
            StudioEditorKey.OTHER -> StudioEditorAction.NONE
        }

    /**
     * **这一下按键归谁** —— M2c 命令框的第一条纪律，屏幕 `keyPressed` 的第一件事。
     *
     * ## 为什么必须先判它（这是本次改动最容易坏的地方）
     * 命令框开着时键盘仍然整条送到屏幕的 `keyPressed`（`KeyboardHandler.java:379`，
     * 屏幕先拿到，控件在后）。如果按键顺手往下走，就会同时发生：
     * - `W/A/S/D` 被 `applyMovement` 轮询到 ⇒ **一边打字一边把玩家开走**；
     * - `Ctrl+Z` 命中撤销 ⇒ 打字打到一半把模型的位移撤了。
     *
     * 所以：**命令框开着时，除 Tab / Esc / 回车之外一律 [StudioKeyTarget.INPUT_BOX]**，
     * 也就是"既不给编辑器，也不许它自己乱吃"。
     *
     * ⚠️ M2d 的 `H`（详情框开合）也在这条纪律里面：命令框开着时按 H 是**往框里打一个 h**，
     * 不是收起面板 —— 否则敲命令敲到一半面板会自己跳一下。
     *
     * ## Esc 的语义（用户明确要求分清）
     * | 命令框 | Esc 的结果 |
     * |---|---|
     * | 开着 | [StudioKeyTarget.CLOSE_INPUT]：**只**收起命令框 |
     * | 关着 | [StudioKeyTarget.EDITOR] ⇒ 走 [onEscape]：拖拽中取消拖拽，否则退出编辑 |
     *
     * @param boxOpen 命令框此刻开着没有
     */
    @JvmStatic
    fun onKeyTarget(key: StudioEditorKey, boxOpen: Boolean): StudioKeyTarget = when {
        !boxOpen -> StudioKeyTarget.EDITOR
        key == StudioEditorKey.TAB || key == StudioEditorKey.ESCAPE -> StudioKeyTarget.CLOSE_INPUT
        key == StudioEditorKey.ENTER -> StudioKeyTarget.APPLY_INPUT
        else -> StudioKeyTarget.INPUT_BOX
    }

    /** 命令框认的分隔符：逗号与空白都算（`"1,2,3"` / `"1 2 3"` / `"1, 2,3"` 都行）。 */
    private val SEPARATOR = Regex("[,\\s]+")

    /**
     * **命令框文本 → 三个坐标**（M2c，纯函数）。
     *
     * 约定：
     * - 分隔符：逗号或空白，可以混用、可以多写（`"1, 2 ,3"` 照样过）；
     * - 必须是**三个**有限数，多一个少一个都拒绝；
     * - `NaN` / `Infinity` / `1e999`（溢出成无穷）一律拒绝 —— 这种值一旦写进 `anchor.offset`，
     *   渲染矩阵和写回 JSON 都会跟着坏，而且用户看不出来是自己敲的那串东西造成的；
     * - **任何输入都不抛异常**，失败一律 [StudioVectorParse.Bad] 带一句中文原因。
     */
    @JvmStatic
    fun parseVec3(text: String): StudioVectorParse {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return StudioVectorParse.Bad("这里是空的")

        val tokens = trimmed.split(SEPARATOR).filter { it.isNotEmpty() }
        if (tokens.size != 3) return StudioVectorParse.Bad("要 3 个数字，收到 ${tokens.size} 个")

        val values = FloatArray(3)
        for (i in 0..2) {
            val token = tokens[i]
            val value = token.toFloatOrNull() ?: return StudioVectorParse.Bad("「$token」不是数字")
            // toFloatOrNull 不会因溢出返回 null（"1e999" → Infinity），所以这里必须自己卡一道
            if (!value.isFinite()) return StudioVectorParse.Bad("「$token」不是有限数")
            values[i] = value
        }
        return StudioVectorParse.Ok(values[0], values[1], values[2])
    }

    /** 一个"像数字"的词（`1` / `-0.25` / `.5` / `1e999`）。 */
    private val NUMERIC_TOKEN = Regex("^[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?$")

    /** 数字字面量里的两个特殊词（它们**不是**命令名，敲出来就是想当坐标用）。 */
    private val NUMERIC_LITERAL = Regex("^(nan|infinity)$", RegexOption.IGNORE_CASE)

    /**
     * **这一行是不是"想敲坐标、但敲坏了"**：每个词都得是数字（或 `NaN` / `Infinity`）。
     *
     * ⚠️ 判据要比"只含数字与分隔符"更宽一点：`"1 2 NaN"` 里有字母，按字符集判会掉进
     * "当命令处理"⇒ 用户收到一句毫无帮助的"不认识的命令"。
     * 反过来也**不能**宽到把 `save` 吞进来 —— `save` 不是数字词，仍然是命令。
     */
    private fun looksLikeCoords(text: String): Boolean {
        val tokens = text.split(SEPARATOR).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return tokens.all { NUMERIC_TOKEN.matches(it) || NUMERIC_LITERAL.matches(it) }
    }

    /**
     * **命令框里那一行交给谁**（M2c，纯函数）。
     *
     * | 输入 | 结果 | 为什么 |
     * |---|---|---|
     * | `""` / `"   "` / `"/"` | [StudioCommandLine.Empty] | 空行不该报错，也不该收框 |
     * | `"1 2 3"` | [StudioCommandLine.Offset] | 三个数 ⇒ 坐标快捷方式（与拖拽同一条路写 offset） |
     * | `"1 2"` / `"1 2 NaN"` | [StudioCommandLine.BadOffset] | **看着像坐标**但解析不了 ⇒ 直接说坐标的原因 |
     * | `"save"` / `"/gtetstudio save"` | [StudioCommandLine.Command] | 其余都当命令，前导 `/` 去掉 |
     *
     * "看着像坐标"这条判据很重要：`"1 2"` 当命令报"不认识的命令"对用户毫无帮助，
     * 而"要 3 个数字，收到 2 个"一眼就知道自己少了什么。
     */
    @JvmStatic
    fun classifyCommandLine(text: String): StudioCommandLine {
        val stripped = text.trim().removePrefix("/").trim()
        if (stripped.isEmpty()) return StudioCommandLine.Empty

        when (val parsed = parseVec3(stripped)) {
            is StudioVectorParse.Ok -> return StudioCommandLine.Offset(parsed.x, parsed.y, parsed.z)
            is StudioVectorParse.Bad ->
                if (looksLikeCoords(stripped)) return StudioCommandLine.BadOffset(parsed.reason)
        }
        return StudioCommandLine.Command(stripped)
    }

    /**
     * 滚轮 → 新的相机速度。
     *
     * 两头都夹住；**非有限输入原样返回**（滚轮事件理论上不会给 NaN，
     * 但 `pow` 一旦吃进 NaN 就会把速度永久污染成 NaN，飞行会直接卡死 —— 这条断言就是这么来的）。
     */
    @JvmStatic
    fun scrollSpeed(current: Double, notches: Double): Double {
        if (!current.isFinite() || !notches.isFinite()) return current
        return (current * SPEED_STEP.pow(notches)).coerceIn(MIN_SPEED, MAX_SPEED)
    }
}