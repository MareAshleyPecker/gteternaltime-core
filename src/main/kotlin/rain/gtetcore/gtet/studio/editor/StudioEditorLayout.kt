package rain.gtetcore.gtet.studio.editor
import rain.gtetcore.gtet.studio.editor.StudioDetailsState.Companion.EXPANDED

// ────────────────────────── 版式常量（一处定义：画面与几何共用） ──────────────────────────

/** 顶栏高度（模式 / 工具 / 相机速度）。 */
const val PANEL_TOP_BAR_H = 22

/**
 * 动作反馈行的带宽（顶栏正下方那一条）。
 *
 * ⚠️ 这一行的**位置与格式**是用户点名要的（`模型id (x1,y1,z1) -> (x2,y2,z2)`，见
 * [rain.gtetcore.gtet.studio.interaction.StudioNumberFormat.stepLine]）—— M2d 一个字都没动。
 */
const val PANEL_FEEDBACK_H = 16

/** 底部操作提示条的高度（M2d 从两行缩成一行）。 */
const val PANEL_HINT_H = 14

/** 屏幕四周的通用边距。 */
const val PANEL_PAD = 8

/** 信息块一行的行高。 */
const val PANEL_LINE_H = 11

/** 卡片内部左右留白。 */
const val PANEL_CARD_PAD = 6

/**
 * 信息块固定按 6 行留位（模型 / 偏移 / 吸附 / 锁定轴 / 改动 / 历史）。
 *
 * 写成常量而不是"按实际行数算"：这样整块几何**与字体宽度、内容无关**，
 * `init()` 里（还没有渲染过、量不到文字宽度）就能算出命令框该摆哪 —— 一处算、三处用。
 */
const val PANEL_INFO_ROWS = 6

/** 展开态：卡片顶部的标题行（**点它 = 收起**）。 */
const val PANEL_TITLE_H = 12

/** 收起态：只剩这么一行。 */
const val PANEL_COLLAPSED_H = 12

/** 展开态卡片高度 = 标题行 + 上下留白 + 六行。 */
const val PANEL_CARD_H = PANEL_TITLE_H + 4 + PANEL_INFO_ROWS * PANEL_LINE_H + 4

/** 详情框上边缘：顶栏 + 反馈行之下（反馈行**必须**留在顶栏正下方）。 */
const val PANEL_INFO_TOP = PANEL_TOP_BAR_H + PANEL_FEEDBACK_H + 4

/** 信息卡与命令框预留区之间的缝（它俩是一套，紧挨着）。 */
const val PANEL_FRAME_GAP = 6

/** 命令框预留区内部：外框到标签的留白。 */
const val PANEL_INPUT_INSET = 3

const val PANEL_INPUT_LABEL_H = 10
const val PANEL_INPUT_BOX_H = 20
const val PANEL_INPUT_HINT_H = 10

/** 预留区总高（= [PANEL_INPUT_INSET] + 标题 + 框 + 提示）。 */
const val PANEL_FRAME_H =
    PANEL_INPUT_INSET * 2 + PANEL_INPUT_LABEL_H + PANEL_INPUT_BOX_H + PANEL_INPUT_HINT_H

/** 期望的面板宽度（放得下「▼ 详情（点这里 / H 收起）」与六行）。 */
const val PANEL_PREFERRED_CARD_W = 260

/** 窗口窄到离谱时的下限（宁可压中线，也不能窄到看不见字）。 */
const val PANEL_MIN_CARD_W = 96

/**
 * 面板右边缘与屏幕中线之间**至少**要留这么宽。
 *
 * 左上角这块面板最要紧的纪律：**不许顶到屏幕中线**（用户抱怨的就是"挡视野"）。
 * 长「模型」行就按这个可用宽度截断（见 [StudioEditorLayout.fitText]）。
 */
const val PANEL_MIDLINE_GAP = 8

/** 命令回显最多画几行（真的放不下时按窗口高度再缩）。 */
const val PANEL_MAX_ECHO_LINES = 4

const val PANEL_ECHO_LINE_H = 10

// ────────────────────────── 收起 / 展开状态 ──────────────────────────

/**
 * **详情框（六行信息 + 命令框）的开合状态**（M2d）。
 *
 * 两条纪律都落在这里，而不是散在屏幕类里 —— 因为它俩都得能被脱机自检钉住：
 * 1. **收起时命令框必须一起关掉**（[boxAllowed]）：预留区就画在详情框里面，收起之后它不再画；
 *    若把 `EditBox` 留着，就成了"框还在（还拿着焦点、还吃 WASD）但屏幕上根本没有它"的怪状态；
 * 2. **按 Tab 调出命令框时先把详情框展开**（[expandedForInput]）：命令框必须画在看得见的地方。
 *
 * 状态活在 [StudioEditorScreen] 这个实例里 ⇒ 一次编辑期间（含改窗口大小触发的控件重建）保持，
 * 而下次进编辑是**新实例** ⇒ 恢复成 [EXPANDED]（免得进去发现啥都没有，以为坏了）。
 */
data class StudioDetailsState(val collapsed: Boolean) {

    /** 点标题行 / 按热键。 */
    fun toggled(): StudioDetailsState = if (collapsed) EXPANDED else COLLAPSED

    /** 此刻命令框能不能开着。 */
    val canShowBox: Boolean get() = !collapsed

    /** "想让命令框开着"到底能不能开着（收起态一律不能，理由见类注释）。 */
    fun boxAllowed(requested: Boolean): Boolean = requested && canShowBox

    /** 要调出命令框 ⇒ 先把详情框展开。 */
    fun expandedForInput(): StudioDetailsState = EXPANDED

    /** 要收起 / 展开详情框 ⇒ 命令框该是什么状态。 */
    fun boxOpenAfterToggle(requested: Boolean): Boolean = boxAllowed(requested)

    companion object {
        /** 默认：进去就是展开的（用户要的"下次进编辑恢复成展开"）。 */
        val EXPANDED: StudioDetailsState = StudioDetailsState(collapsed = false)

        val COLLAPSED: StudioDetailsState = StudioDetailsState(collapsed = true)
    }
}

/** 面板吃掉的鼠标点击（[NONE] = 面板不管，原样交给编辑器）。 */
enum class StudioPanelClick {

    /** 点的是标题行（收起态就是那一行）⇒ 切换收起 / 展开，**不产生任何编辑器动作**。 */
    TITLE,

    /** 点的是命令框预留区 ⇒ 调出 / 收起命令框。 */
    COMMAND_SLOT,

    /** 点在面板之外 ⇒ 编辑器照常处理（拖轴 / 转视角）。 */
    NONE,
}

// ────────────────────────── 几何 ──────────────────────────

/**
 * **详情框在某一帧的几何** —— 一处算、三处用：
 * 画（[StudioEditorScreen.drawPanel]）、摆 `EditBox`（`init`）、判点击（`mouseClicked`）。
 *
 * 只依赖窗口像素尺寸与常量，**不依赖字体宽度**，所以：
 * - `init()`（还没渲染过、量不到文字）能直接算出来 ⇒ 预留区画在哪、`EditBox` 就摆在哪；
 * - 脱机自检能把它整条钉住（见 `StudioInteractionSelfCheck` ⑩）。
 */
class StudioPanelLayout(
    /** 详情框此刻是不是收起的（收起态预留区不吃点击，见 [inFrame]）。 */
    val collapsed: Boolean,
    /** 窗口像素宽（用来判"有没有越过中线"）。 */
    val screenW: Int,
    /** 卡片（信息块）左上角与尺寸。 */
    val cardX: Int,
    val cardY: Int,
    val cardW: Int,
    /** 卡片**画出来的**高度：展开 86、收起 12。 */
    val cardH: Int,
    /** 可点的标题带高度（展开 = [PANEL_TITLE_H]，收起 = [PANEL_COLLAPSED_H]）。 */
    val titleH: Int,
    // ── 命令框预留区（★ 这块矩形 = EditBox 真正出现的位置）──
    val frameX: Int,
    val frameY: Int,
    val frameW: Int,
    val frameH: Int,
    val boxX: Int,
    val boxY: Int,
    val boxW: Int,
    val boxH: Int,
    /** 命令回显区顶部（在预留区**下方**；不参与预留区几何）。 */
    val echoY: Int,
    val echoMaxLines: Int,
) {
    /** 卡片右边缘。 */
    val cardRight: Int get() = cardX + cardW

    /** 屏幕中线 —— 面板**不许**越过它。 */
    val midline: Int get() = screenW / 2

    /** 值列可用宽度（标签占掉的那部分要让出来）。 */
    fun valueWidth(labelWidth: Int): Int = cardW - PANEL_CARD_PAD * 2 - labelWidth - 10

    /** 点在标题行 / 收起态那一行里（两种状态都用来切换收起展开）。 */
    fun inTitleRow(mouseX: Double, mouseY: Double): Boolean =
        inside(mouseX, mouseY, cardX, cardY, cardW, titleH)

    /**
     * 点在命令框预留区里。
     *
     * **收起态恒为 false**：那时预留区根本没画，点在那儿就是点在世界/编辑器上
     * （否则屏幕中间会凭空多出一块吃点击的死区）。
     */
    fun inFrame(mouseX: Double, mouseY: Double): Boolean =
        !collapsed && inside(mouseX, mouseY, frameX, frameY, frameW, frameH)

    /** 点在命令框（`EditBox`）那一格里。 */
    fun inBox(mouseX: Double, mouseY: Double): Boolean =
        !collapsed && inside(mouseX, mouseY, boxX, boxY, boxW, boxH)

    private fun inside(mx: Double, my: Double, x: Int, y: Int, w: Int, h: Int): Boolean =
        mx >= x && mx < x + w && my >= y && my < y + h
}

/**
 * **M2d 面板版式与判决**（纯函数：一行 MC / GLFW / LDLib 都不 import）。
 *
 * ## 为什么单独抽出来
 * "画在哪 / `EditBox` 摆在哪 / 点哪儿算谁"必须是**同一份几何**算出来的三个用途：
 * 算两遍的必然结局是预留区画在一处、输入框出现在另一处。抽出来之后还有第二个好处 ——
 * 几何只依赖像素尺寸与常量，于是自检能把"面板不越过屏幕中线""预留区恰好包住输入框"
 * "点标题行 = 切换收起且不产生编辑器动作""收起态预留区不再吃点击"逐条钉住。
 *
 * ## 版式（M2d：从右边缘搬到左上角）
 * ```
 * ┌ 顶栏 ────────────────────────────────────────────────────────┐
 * ├ 动作反馈行（选中/拖拽那一行，保留在顶栏正下方）───────────────┤
 * │ ┌ 详情框（左上角，展开态）───────────┐                        │
 * │ │ ▼ 详情（点这里 / H 收起）          │ ← 标题行：点它 = 收起   │
 * │ │ 模型   gtet:test_clock @ -10,-59,-13│                       │
 * │ │ 偏移 / 吸附 / 锁定轴 / 改动 / 历史  │                       │
 * │ ├ 命令框（Tab 调出）─────────────────┤ ← 预留区（跟卡片走）    │
 * │ │ [ save / undo / 1 2 3 ]            │ ← ★ EditBox 就在这里    │
 * │ │ 例：save / undo / 1 2 3            │                        │
 * │ └ 回显（画在预留区下方）──────────────┘                       │
 * └ 底部**一行**操作提示 ─────────────────────────────────────────┘
 * ```
 * 收起态只剩一行（`▸ 详情 …`）：卡片变矮，**命令框那一格的位置不动**（那时它必然不可见）。
 *
 * @author rain fox
 */
object StudioEditorLayout {

    /**
     * 当前窗口尺寸 + 开合状态 ⇒ 这一帧的几何。
     *
     * ⚠️ **命令框那一格的位置与开合状态无关**（按展开态算）：收起时它必然不可见
     * （见 [StudioDetailsState]），所以展开 / 收起**不需要重摆** `EditBox` ——
     * 也就没有"收起时把框摆成零尺寸、展开后忘了摆回来"这类坑。
     */
    @JvmStatic
    fun of(width: Int, height: Int, collapsed: Boolean): StudioPanelLayout {
        // 面板不许顶到屏幕中线：可用宽度就是"到中线还差多少"
        val halfLimit = (width / 2 - PANEL_PAD - PANEL_MIDLINE_GAP).coerceAtLeast(PANEL_MIN_CARD_W)
        val cardW = PANEL_PREFERRED_CARD_W.coerceAtMost(halfLimit)
        val cardH = if (collapsed) PANEL_COLLAPSED_H else PANEL_CARD_H

        val frameTop = PANEL_INFO_TOP + PANEL_CARD_H + PANEL_FRAME_GAP
        val frameBottom = frameTop + PANEL_FRAME_H
        // 窗口特别矮时整体上移，尽量别压到底部提示条上（挪不开就叠着 —— 面板本来就要求
        // 一个不太小的 GUI 高度；1080p 下任何 GUI 缩放都远在安全区里）。
        val minTop = PANEL_TOP_BAR_H + PANEL_FEEDBACK_H + 2
        val overflow = frameBottom - (height - PANEL_HINT_H - 4)
        val shift = if (overflow > 0) overflow.coerceAtMost((PANEL_INFO_TOP - minTop).coerceAtLeast(0)) else 0

        val cardY = PANEL_INFO_TOP - shift
        val frameY = frameTop - shift
        // 回显区在预留区**下方**：它再怎么变都不会动到命令框那一格
        val echoY = frameY + PANEL_FRAME_H + 4
        return StudioPanelLayout(
            collapsed = collapsed,
            screenW = width,
            cardX = PANEL_PAD,
            cardY = cardY,
            cardW = cardW,
            cardH = cardH,
            titleH = if (collapsed) PANEL_COLLAPSED_H else PANEL_TITLE_H,
            frameX = PANEL_PAD,
            frameY = frameY,
            frameW = cardW,
            frameH = PANEL_FRAME_H,
            boxX = PANEL_PAD + 2,
            boxY = frameY + PANEL_INPUT_INSET + PANEL_INPUT_LABEL_H,
            boxW = cardW - 4,
            boxH = PANEL_INPUT_BOX_H,
            echoY = echoY,
            echoMaxLines = ((height - PANEL_HINT_H - 6 - echoY) / PANEL_ECHO_LINE_H)
                .coerceIn(0, PANEL_MAX_ECHO_LINES),
        )
    }

    /**
     * **这一下点归谁**（面板 vs 编辑器）。
     *
     * 屏幕的 `mouseClicked` 在"命令框自己那一格"之后立刻问这里，答案是
     * [StudioPanelClick.NONE] 之外的一律 `return true` —— 也就是**面板吃掉的点击
     * 一个都不会漏进编辑器**（否则点一下标题行就会顺手拖一次轴）。
     *
     * 顺序上标题行与预留区**不会重叠**（预留区在卡片下方），所以不必定优先级；
     * 唯一要当心的是收起态：那时预留区不参与命中（见 [StudioPanelLayout.inFrame]）。
     */
    @JvmStatic
    fun onPanelClick(mouseX: Double, mouseY: Double, layout: StudioPanelLayout): StudioPanelClick = when {
        layout.inTitleRow(mouseX, mouseY) -> StudioPanelClick.TITLE
        layout.inFrame(mouseX, mouseY) -> StudioPanelClick.COMMAND_SLOT
        else -> StudioPanelClick.NONE
    }

    /**
     * 按可用宽度收窄文本：放得下原样返回，放不下就截断并补一个「…」。
     *
     * 为什么要它：面板搬到左上角之后，**不许顶到屏幕中线**，于是"模型"那一行
     * （`gtet:test_clock @ -10, -59, -13` 这种，id 一长就更长）必须有确定的收窄行为。
     * 宽度由调用方给的 [measure] 决定（GUI 里就是 `font.width`），所以这个函数是纯的 ——
     * 自检用假 measure 就能把边界钉住，不需要起游戏。
     */
    @JvmStatic
    fun fitText(text: String, maxWidth: Int, measure: (String) -> Int): String {
        if (maxWidth <= 0) return ""
        if (measure(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisW = measure(ellipsis)
        if (ellipsisW > maxWidth) return ""
        var end = text.length
        while (end > 0 && measure(text.substring(0, end)) + ellipsisW > maxWidth) end--
        // 别把代理对（emoji 那类）劈成两半：半个代理渲染出来是个方块
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + ellipsis
    }

    /**
     * 「模型」那一行的**紧凑写法**：`gtet:test_clock @ -10, -59, -13` ⇒ `gtet:test_clock @ -10,-59,-13`。
     *
     * 坐标是整数且多半带负号，把逗号后面的空格去掉能省下十来个像素 ——
     * 在 260 宽的面板里这刚好是"要不要截断"的差别。
     */
    @JvmStatic
    fun compactLabel(label: String): String = label.replace(", ", ",")
}