package rain.gtetcore.gtet.studio.editor

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import org.slf4j.Logger
import rain.gtetcore.gtet.studio.editor.StudioEditor.onLibraryReloaded
import rain.gtetcore.gtet.studio.editor.StudioEditor.pending
import rain.gtetcore.gtet.studio.editor.StudioEditor.previewOffsetLength
import rain.gtetcore.gtet.studio.interaction.StudioAnchorOffset
import rain.gtetcore.gtet.studio.interaction.StudioCommand
import rain.gtetcore.gtet.studio.interaction.StudioHistory
import rain.gtetcore.gtet.studio.interaction.TranslateAnchorCommand

/**
 * **编辑模式的状态机 + "当前在编辑什么"**（M2a 的 `editor/` 层）。
 *
 * ## 状态
 * ```
 * 未编辑 ──/gtetstudio edit──▶ 已选中某实例（编辑中）──/gtetstudio edit off 或 Esc──▶ 未编辑
 * ```
 * **同时只允许编辑一个实例**（M2a 不做多选）。切换目标时旧目标的"未保存预览值"**留在内存里**
 * （见下），撤销栈清空（命令绑在旧目标的偏移上，留着只会撤出莫名其妙的结果）。
 *
 * ## 未保存的改动为什么"退出后还留着"
 * 退出编辑不丢内存里的值 —— 而是把它记成 [pending] 里的一条"未落盘的覆盖"：
 * - 渲染仍然用它（所以你退出后看到的还是你调过的位置，不会突然弹回文件里的值）；
 * - 重新 `edit` 同一台模型时会带着它继续调；
 * - `/gtetstudio save` 把**所有**未保存的覆盖写回文件；
 * - **`/gtetstudio reload` 与 F3+T 会全部清掉**（见 [onLibraryReloaded]）——
 *   这是防"拿旧的内存值覆盖新读进来的文件"的那道闸，也是"改了文件先重载"这条纪律的落点。
 *
 * ## 活值 / 快照 / 命令
 * 编辑中的"当前值"是 [PendingEdit.live] 这个**可变对象**：渲染每帧读它，
 * 拖拽每帧改它，[TranslateAnchorCommand] 的 apply/revert 也改它（快照在命令里）。
 * 于是"撤销之后实时预览跟着变"是自动的 —— **不需要任何刷新通知**。
 *
 * ## 线程
 * 主线程（渲染/输入/命令）为主；两个例外要当心，所以读写都上锁（[Synchronized]）：
 * - `DynamicRender.getRenderBoundingBox` 可能被区块构建工作线程调到 ⇒ [previewOffsetLength] 会从别的线程读；
 * - 资源重载（F3+T）的回调不保证在主线程 ⇒ [onLibraryReloaded] 会从别的线程写。
 * ⚠️ 因此本类的方法里**不许再调 `StudioLibrary`**（那边自己有锁，交叉拿锁会埋死锁）。
 * 文件里的偏移一律由 [StudioEditTarget.fileOffset] 快照进来，不在这里现读。
 *
 * @author rain fox
 */
object StudioEditor {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** 一次编辑会话的"活值 + 文件里的值"。 */
    private class PendingEdit(val live: StudioAnchorOffset, var onDisk: StudioAnchorOffset)

    /** `modelId → 未落盘的编辑值`。退出编辑**不清**（见类注释），reload 才清。 */
    private val pending = LinkedHashMap<ResourceLocation, PendingEdit>()

    /** 撤销/重做栈（命令模式；容量上限见 [StudioHistory.DEFAULT_CAPACITY]）。 */
    @JvmField
    val history: StudioHistory = StudioHistory()

    private var current: StudioEditTarget? = null

    private var source: StudioTargetSource? = null

    // ────────────────────────── 装配（由 integration 调用）──────────────────────────

    /** 装上宿主查询（`integration/StudioMachineRegistry`）。**必须在任何命令之前调用。** */
    @JvmStatic
    @Synchronized
    fun installSource(source: StudioTargetSource) {
        this.source = source
        LOGGER.debug("[studio] 编辑器已接上宿主查询 {}", source.javaClass.simpleName)
    }

    /** 宿主查询装上了没有（命令里用它给出"为什么不能编辑"的确切原因）。 */
    @JvmStatic
    @Synchronized
    fun sourceOrNull(): StudioTargetSource? = source

    // ────────────────────────── 状态查询 ──────────────────────────

    /** 当前编辑目标；null = 未编辑。 */
    @JvmStatic
    @Synchronized
    fun target(): StudioEditTarget? = current

    @JvmStatic
    @Synchronized
    fun isEditing(): Boolean = current != null

    /** 当前编辑目标的**活值对象**（拖拽/命令直接改它；渲染直接读它）。 */
    @JvmStatic
    @Synchronized
    fun liveOffset(): StudioAnchorOffset? = current?.let { pending[it.modelId]?.live }

    /**
     * 渲染要用的覆盖值（**任何**带未保存改动的模型都算，不只是正在编辑那个）。
     *
     * 这一条就是"实时预览"的全部机关：`StudioDynamicRender` 每帧问一次，
     * 有覆盖就把 anchor 的偏移换掉再画 —— `anchor` 只进渲染矩阵、**不进 VBO**
     * （见 `StudioRenderer` 里那段 transform 与 `StudioRenderCache.build` 只烘几何），
     * 所以**不需要重烘顶点缓冲**。
     */
    @JvmStatic
    @Synchronized
    fun previewOffset(modelId: ResourceLocation): StudioAnchorOffset? = pending[modelId]?.live

    /**
     * 覆盖值的长度（格）—— **只给渲染剔除用**。
     *
     * `getRenderBoundingBox` 用文件里那份 offset 算的包围盒，编辑中偏移可能跑得更远，
     * 不补这一下，"拖远一点模型就被视锥剔除切掉"。可能被工作线程读 ⇒ 与写同一把锁。
     */
    @JvmStatic
    @Synchronized
    fun previewOffsetLength(modelId: ResourceLocation): Double? = pending[modelId]?.live?.length()

    /** 这个模型有没有未保存的改动。 */
    @JvmStatic
    @Synchronized
    fun isDirty(modelId: ResourceLocation): Boolean = pending[modelId]?.let { !it.live.equalsValue(it.onDisk) } ?: false

    /** 当前编辑目标有没有未保存的改动（动作栏上那个 ●）。 */
    @JvmStatic
    @Synchronized
    fun isCurrentDirty(): Boolean = current?.let { isDirty(it.modelId) } ?: false

    /** 所有未保存的模型（`/gtetstudio save` 遍历它）。 */
    @JvmStatic
    @Synchronized
    fun dirtyModels(): List<ResourceLocation> =
        pending.entries.filter { !it.value.live.equalsValue(it.value.onDisk) }.map { it.key }

    // ────────────────────────── 进出编辑 ──────────────────────────

    /**
     * 进入编辑（切换目标时自动退掉旧的）。
     *
     * @return 给聊天栏的一句话
     */
    @JvmStatic
    @Synchronized
    fun enter(target: StudioEditTarget): String {
        val previous = current
        val swapped = previous != null && previous.modelId != target.modelId
        if (swapped) {
            // 旧目标的未保存值留在 pending 里（退出编辑不丢），但撤销栈必须清：
            // 命令是绑在某个目标偏移对象上的，混着用会撤出莫名其妙的结果。
            history.clear()
        }

        val entry = pending.getOrPut(target.modelId) {
            // 第一次编辑这个模型：活值从文件里那份开始
            PendingEdit(target.fileOffset.copy(), target.fileOffset.copy())
        }
        // 重新进入同一个模型时，文件里的值可能已经变了（外部改文件 + reload 已清 pending，
        // 所以这里只在"没动过"的情况下对齐一次，避免把用户调好的值冲掉）
        if (entry.live.equalsValue(entry.onDisk)) {
            entry.onDisk = target.fileOffset.copy()
            entry.live.setFrom(target.fileOffset)
        }

        current = target
        LOGGER.info("[studio] 进入编辑：{} （当前偏移 {}，未保存 {}）", target.label, entry.live, isDirty(target.modelId))
        return "已进入编辑：${target.label}（${entry.live.format()}）" +
            if (swapped) "；已退出上一个目标（${previous.label}，它的改动还在内存里，save 会一起写回）" else ""
    }

    /**
     * 退出编辑。
     *
     * **不丢弃内存里的值**（见类注释）：只是不再接受拖拽/不再画 gizmo。
     *
     * @return 给聊天栏的一句话
     */
    @JvmStatic
    @Synchronized
    fun exit(): String {
        val target = current ?: return "现在没有在编辑任何模型（敲 /gtetstudio edit 进入编辑）"
        val dirty = isDirty(target.modelId)
        current = null
        history.clear()
        LOGGER.info("[studio] 退出编辑：{}（未保存 {}）", target.label, dirty)
        return if (dirty) {
            "已退出编辑：${target.label}。⚠️ 有未保存改动（${pending[target.modelId]?.live?.format()}）——" +
                "改动还在内存里，敲 /gtetstudio save 才会写回文件"
        } else {
            "已退出编辑：${target.label}"
        }
    }

    /**
     * 每 tick 检查目标还在不在（机器被拆、区块卸载、换维度 ⇒ 自动退出）。
     *
     * @return 需要告诉玩家的话；null = 什么都不用说
     */
    @JvmStatic
    @Synchronized
    fun tick(): String? {
        val target = current ?: return null
        if (target.isValid()) return null
        val line = exit()
        return "编辑目标不见了（机器被拆掉/离开视野）—— $line"
    }

    // ────────────────────────── 撤销 / 重做 / 落盘 ──────────────────────────

    /**
     * 提交一条命令（拖拽松手时由 `interaction/` 调用）。
     *
     * @param command null 或"值没变"的命令会被丢弃 —— 撤销栈里不放空动作
     * @return true = 真的入栈了
     */
    @JvmStatic
    @Synchronized
    fun commit(command: StudioCommand?): Boolean {
        if (command == null) return false
        if (command is TranslateAnchorCommand && command.isNoop) return false
        history.execute(command)
        LOGGER.info("[studio] 记录编辑动作：{}（可撤销 {} 步）", command.describe(), history.undoDepth)
        return true
    }

    /** 撤销一步。 */
    @JvmStatic
    @Synchronized
    fun undo(): StudioCommand? {
        val done = history.undo() ?: return null
        LOGGER.info("[studio] 撤销：{}（剩 {} 步可撤销）", done.describe(), history.undoDepth)
        return done
    }

    /** 重做一步。 */
    @JvmStatic
    @Synchronized
    fun redo(): StudioCommand? {
        val done = history.redo() ?: return null
        LOGGER.info("[studio] 重做：{}", done.describe())
        return done
    }

    /** 落盘成功后调用：从现在起"内存 == 文件"，未保存标记清零。 */
    @JvmStatic
    @Synchronized
    fun markSaved(modelId: ResourceLocation) {
        pending[modelId]?.let { it.onDisk = it.live.copy() }
    }

    /**
     * **`/gtetstudio reload` 与 F3+T（资源重载）之后必须调它**。
     *
     * 三件事一起做，理由只有一条：**别拿旧的内存值去覆盖刚读进来的文件**。
     * 1. 退出编辑（旧目标的 `StudioInstance` 已经是上一代了）；
     * 2. 清空全部未保存覆盖（内存回退到文件里的值）；
     * 3. 清空撤销栈（命令绑在那些被清掉的偏移对象上）。
     */
    @JvmStatic
    @Synchronized
    fun onLibraryReloaded() {
        val had = pending.isNotEmpty() || current != null
        val discarded = dirtyModels()
        current = null
        pending.clear()
        history.clear()
        if (had) {
            LOGGER.info("[studio] 重载：已退出编辑并清空撤销栈（丢弃的未保存改动：{}）", discarded.ifEmpty { listOf("无") })
        }
    }

    /** 诊断用：当前状态的一行摘要（日志/命令）。 */
    @JvmStatic
    @Synchronized
    fun summary(): String {
        val target = current
        return if (target == null) {
            "未编辑（内存里还有 ${dirtyModels().size} 个未保存的模型）"
        } else {
            "${target.label} 偏移 ${pending[target.modelId]?.live?.format()}" +
                (if (isDirty(target.modelId)) " ●未保存" else " 已保存") +
                "（可撤销 ${history.undoDepth} 步）"
        }
    }
}