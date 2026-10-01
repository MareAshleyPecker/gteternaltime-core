package rain.gtetcore.gtet.studio.interaction

/**
 * **一条可逆的编辑动作**（设计文档 §6 第三条：「必须一开始就用命令模式」）。
 *
 * 契约（写死在这里，别在实现里打折扣）：
 * 1. `apply()` 与 `revert()` **必须互为逆操作**：`apply → revert` 之后世界回到原样，
 *    且反复交替调用结果不变（幂等）；
 * 2. 命令**只改内存里的值**，不碰文件 —— 落盘是 `/gtetstudio save` 的事。
 *    所以撤销之后下一帧渲染自然是新值（渲染每帧读活值 + [rain.gtetcore.gtet.studio.api.StudioAnchor] 只进矩阵）；
 * 3. [describe] 是**给人看的**（聊天栏/动作栏），要写清"动了什么、从多少到多少"。
 *
 * @author rain fox
 */
interface StudioCommand {

    /** 执行（重做也走它）。 */
    fun apply()

    /** 撤销。 */
    fun revert()

    /** 一行中文描述（`撤销：gtet:test_clock anchor.offset (0, 1, 0) → (0, 2, 0)`）。 */
    fun describe(): String
}

/**
 * **平移 anchor 偏移** —— M2a 唯一的具体命令。
 *
 * 三个字段都是"引用 + 两份快照"：
 * - [holder] 是编辑中的**活值对象**（渲染直接读它，所以 apply/revert 立刻可见）；
 * - [before]/[after] 是按下/松开那一刻 [StudioAnchorOffset.copy] 出来的快照，
 *   **不可变**（谁都不能改它们），所以命令被反复 apply/revert 也永远对称。
 *
 * @param label 目标名字（模型 id + 机器坐标），只进 [describe]
 */
class TranslateAnchorCommand(
    private val holder: StudioAnchorOffset,
    private val before: StudioAnchorOffset,
    private val after: StudioAnchorOffset,
    private val label: String,
) : StudioCommand {

    /** 按下前的值（自检与反馈要看）。 */
    val beforeValue: StudioAnchorOffset get() = before

    /** 松开后的值。 */
    val afterValue: StudioAnchorOffset get() = after

    override fun apply() {
        holder.setFrom(after)
    }

    override fun revert() {
        holder.setFrom(before)
    }

    /** 值没变就别入栈（`/gtetstudio undo` 撤销一条"什么都没做"的命令最让人困惑）。 */
    val isNoop: Boolean get() = before.equalsValue(after)

    override fun describe(): String =
        "$label anchor.offset ${before.format()} → ${after.format()}"

    override fun toString(): String = "TranslateAnchorCommand(${describe()})"
}

/**
 * **撤销/重做栈**（命令模式的那一半）。
 *
 * - `undo` 栈顶 = 最近一次动作；`redo` 在新动作发生时清空（标准语义）；
 * - **容量上限**：超过就从 undo 栈**底部**淘汰最旧的一条（不然长时间编辑会一直吃内存，
 *   而且用户根本不会撤到 100 步以前）—— 淘汰是静默的，但会在日志里记一句（[evicted]）；
 * - **连续拖拽合并成一条**这件事**不在这里**做，而是在 [StudioDragSession]：
 *   一次"按下-移动-松开"从头到尾只 [execute] 一次，所以栈里天然只有一条
 *   （如果做成"每帧一条命令"，用户按一次 Ctrl+Z 只会退回一帧，手感等于没有撤销）。
 *
 * @param capacity 最多保留多少步
 */
class StudioHistory(
    val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(capacity > 0) { "撤销栈容量必须为正，收到 $capacity" }
    }

    private val undoStack = ArrayDeque<StudioCommand>()
    private val redoStack = ArrayDeque<StudioCommand>()

    /** 被容量上限淘汰掉的总条数（诊断用）。 */
    var evicted: Int = 0
        private set

    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 执行一条新命令：**先 apply 再入栈**（顺序不能反，否则中途抛异常会留下"记了但没做"的状态）。 */
    fun execute(command: StudioCommand) {
        command.apply()
        undoStack.addLast(command)
        redoStack.clear()
        while (undoStack.size > capacity) {
            undoStack.removeFirst()
            evicted++
        }
    }

    /** @return 被撤销的命令；null = 没得撤 */
    fun undo(): StudioCommand? {
        val command = undoStack.removeLastOrNull() ?: return null
        command.revert()
        redoStack.addLast(command)
        return command
    }

    /** @return 被重做的命令；null = 没得做 */
    fun redo(): StudioCommand? {
        val command = redoStack.removeLastOrNull() ?: return null
        command.apply()
        undoStack.addLast(command)
        return command
    }

    /** 即将被撤销/重做的那条（反馈里预告用），不会改动任何状态。 */
    fun peekUndo(): StudioCommand? = undoStack.lastOrNull()

    fun peekRedo(): StudioCommand? = redoStack.lastOrNull()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    override fun toString(): String = "StudioHistory(可撤销 ${undoStack.size} 步，可重做 ${redoStack.size} 步)"

    companion object {

        /** 默认容量：够用且不会让人"撤着撤着发现早期步骤没了"。 */
        const val DEFAULT_CAPACITY: Int = 64
    }
}