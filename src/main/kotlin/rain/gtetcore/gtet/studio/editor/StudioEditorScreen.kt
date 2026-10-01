package rain.gtetcore.gtet.studio.editor

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import org.lwjgl.glfw.GLFW
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents
import rain.gtetcore.gtet.studio.interaction.StudioKeyMappings
import rain.gtetcore.gtet.studio.interaction.StudioSnapRule
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **Studio 编辑器模式（M2b 独占输入 · M2c 命令框 · M2d 详情框搬左上角 + 可收起）**。
 *
 * ## 为什么必须是"一个 Screen"（M2a 的"拖不动"就是这么来的）
 * M2a 里 gizmo 看得见了却**拖不动**，根因不在拾取数学（那部分是对的），而在**输入模式**：
 * 原版没开屏幕时鼠标是**锁死**的（光标钉在窗口中心，移动鼠标 = 转视角），
 * 场上唯一的指针是准星。而轴手柄的命中半径 = `0.07 × 轴长`，
 * 样例轴长 ≈ 0.59 格 ⇒ 半径 ≈ 0.041 格，在 3.7 格距离、1080p 下约合**屏幕上 8 像素**。
 * 用一个随转头一起移动的准星去戳 8 像素宽的轴，实际上做不到。
 *
 * 打开一个 [Screen] 则是另一套世界（全部有源码为证，坐标是 Forge 1.20.1-47.4.23 反编译源）：
 * - `Minecraft.setScreen` 非空分支会 `mouseHandler.releaseMouse()`（`Minecraft.java:1026-1029`）
 *   ⇒ **光标解锁**，`mouseHandler.xpos()/ypos()` 从此就是**真实鼠标位置**
 *   —— 这正是"能戳中 8 像素轴"的前提，也是纯逻辑（[StudioInteractionEvents]）一行都不用改的原因；
 * - `MouseHandler.onPress` 里 `this.minecraft.screen != null` 之后，
 *   原版的 `KeyMapping.click(...)`（= 挖方块 / 用物品）走的是 `screen == null` 那条分支
 *   （`MouseHandler.java:83-130`）⇒ **攻击/使用全被挡住**；
 * - `KeyboardHandler.keyPress` 里 `KeyMapping.set/click` 整段同样在 `if (screen == null)` 之内
 *   （`KeyboardHandler.java:389-430`）⇒ **物品栏切换、玩家移动、以及 `pauseGame`（Esc 弹暂停菜单）
 *   全部不会发生**；
 * - 鼠标滚轮落到 `screen.mouseScrolled(...)`（`MouseHandler.java:148-155`）⇒ 不会切物品栏。
 *
 * ## 为什么 `isPauseScreen() = false` 是**关键**
 * `Minecraft.runTick` 末尾：`pause = hasSingleplayerServer() && screen.isPauseScreen() && ...`
 * （`Minecraft.java:1207`）。一旦返回 true，单人游戏会**暂停世界**：世界里的一切都不 tick、不渲染，
 * BER 不再跑 ⇒ gizmo 直接消失、`Screen.tick()` 也不再被调用（玩家飞不动）。
 * 返回 false 时世界照常跑、模型照常画、gizmo 照常在，只是画面上多了一层我们自己的面板。
 *
 * ## 键盘被吃掉了，所以移动要自己实现
 * 屏幕开着时原版一个 KeyMapping 都不更新，玩家动不了。这里每 tick **直接轮询 GLFW 真实按键状态**
 * 算出速度写进 `player.setDeltaMovement(...)`，并保证 `abilities.flying`。
 * 方向换算用的是原版 `LivingEntity.getInputVector` 那一套（只吃 yaw）——
 * 所以"低头按 W"是平飞而不是俯冲，与创造飞行手感一致。
 * ⚠️ 正因为是**轮询 GLFW**，命令框聚焦时必须把这段停掉（见下），不然打字会带着玩家飞。
 *
 * ## 版式（M2d，用户实机诉求："太挡视野 ⇒ 详情框搬到左上角 + 可收起"）
 * ```
 * ┌ 顶栏：▣ Studio 编辑器 ｜ 工具：平移 ｜ 相机速度 …                          ┐  ← 保留
 * ├ 动作反馈行：gtet:test_clock (0.00, 1.00, 0.00) -> (0.00, 1.25, 0.00) …      ┤  ← ★ 一个字没动
 * │ ┌ 详情框（左上角，默认展开）─────────────────┐                              │
 * │ │ ▼ 详情（点这里 / H 收起）                  │ ← ★ 点这一行 = 收起           │
 * │ │ 模型    gtet:test_clock @ -10,-59,-13      │ ← ★ 从右边缘搬过来            │
 * │ │ 偏移 / 吸附 / 锁定轴 / 改动 / 历史         │                              │
 * │ ├ 命令框（Tab 调出）────────────────────────┤ ← ★ 跟着信息块走（一套）      │
 * │ │ [ save / undo / 1 2 3 ]                   │ ← ★ EditBox 精确落在这一格    │
 * │ └ 回显（画在预留区下方）────────────────────┘                              │
 * └ 底部**一行**操作提示（M2d 从两行缩成一行，信息没丢）                        ┘
 * ```
 * 收起态只剩一行：`▸ 详情  gtet:test_clock @ -10,-59,-13 ｜ 偏移 (0, 1, 0)` ——
 * 高度最小、不挡视野，但仍然一眼看得到"在编辑哪台机、偏移多少"。
 *
 * ### 几何是**一处算、三处用**（这条不许破）
 * 画面板（[drawPanel]）、摆 `EditBox`（[init]）、判点击（[mouseClicked]）都读同一个
 * [StudioEditorLayout.of] 算出来的 [StudioPanelLayout]。**不要把几何算两遍**：
 * 算两遍的必然结局是"预留区画在一处、输入框出现在另一处"。
 * 几何只依赖窗口像素尺寸与常量（不依赖字体宽度），所以 [init] 能直接用。
 *
 * ## 收起 / 展开（M2d）
 * | 交互 | 行为 |
 * |---|---|
 * | **鼠标点标题行**（展开态的「▼ 详情…」那一行 / 收起态的「▸ 详情…」那一行） | 切换收起 / 展开 |
 * | **`H`** | 同上（热键；选它的理由见 [StudioEditorKey.DETAILS_KEY]） |
 * | 收起时**命令框正开着** | **连带收起**：先收起命令框，再收起面板（[StudioDetailsState.boxAllowed]） |
 * | 收起态按 `Tab` | 先把面板展开，再调出命令框（命令框必须画在看得见的地方） |
 * | 收起时点原来预留区的位置 | **不吃点击**，照常给编辑器（[StudioPanelLayout.inFrame] 收起态恒 false） |
 *
 * 为什么"连带收起"：预留区本来就画在详情框里面，收起之后它不再画；留着 `EditBox`
 * 就会变成"框还在（还拿着焦点、还吃 WASD）但屏幕上根本没有它"的怪状态。
 * 因为这条保证，**收起态永远不会出现可见的输入框**，所以命令框那一格的几何可以
 * 照展开态算死、展开/收起都不用重摆控件。
 *
 * 收起状态**在会话内保持**（改窗口大小重建控件也不重置），但下次进编辑是新 `Screen` 实例
 * ⇒ 恢复成展开（默认展开，免得进去发现啥都没有、以为坏了）。
 *
 * ## 命令框（M2c）
 * 用户要的是**命令命令框**（不是数值框），所以这一格敲的是命令，由 [StudioCommandBox] 执行：
 * | 输入 | 结果 |
 * |---|---|
 * | `save` / `undo` / `redo` / `edit` / `edit off` / `reload` / `list` / `limits` | 执行对应的客户端命令（省略根名也行） |
 * | `/gtetstudio save` | 同上（前导 `/` 会自动去掉） |
 * | `1 2 3`（三个数） | 坐标快捷方式：直接写 `anchor.offset`（与拖拽同一条路，可 Ctrl+Z） |
 * | `1 2` / `1 2 NaN` | 按坐标报错（"要 3 个数字，收到 2 个"），不当成"不认识的命令" |
 *
 * 命令回执**不进聊天栏**：编辑模式开着时 `hideGui = true`，而原版聊天栏也在 `!hideGui` 里画
 * （`Gui.java:252` 那段、`this.chat.render(...)` 在 `:340`）⇒ 发出去等于看不见。
 * 所以 [StudioCommandBox] 把命令来源的消息出口换成一个收集器，回执画在命令框下方。
 *
 * | 键 | 行为 |
 * |---|---|
 * | `Tab` | 调出 / 收起命令框（点预留区也一样；收起态会先把面板展开） |
 * | `H` | 收起 / 展开详情框（M2d；命令框开着时它是"打字"，见 [keyPressed]） |
 * | 回车 | 执行那一行；失败**不收框**，留着让人改 |
 * | `Esc` | **只**收起命令框（不取消拖拽、**不退出编辑**）；框关着时才是老的语义 |
 * | 打字时 | WASD 不会带着玩家飞，Ctrl+Z 也不会误撤销（见 [keyPressed] 的第一顺位） |
 *
 * 判决（"这一行算什么""这一下按键归谁"）**全在 [StudioEditorInput] 里**（纯函数，脱机自检可跑），
 * 这个类只做三件事：**独占输入 / 驱动相机 / 画面板**。
 * 类里**一行 GTM / LDLib 都没有**（M2c 不引入新依赖，命令框用的就是原版 [EditBox]）。
 *
 * ## 退出（每一条路都收敛到 [removed]）
 * | 触发 | 动作 |
 * |---|---|
 * | `Esc` 且没在拖拽、命令框也没开 | `StudioEditor.exit()` + 关屏（**不走原版 `popGuiLayer`**） |
 * | `Esc` 且在拖拽 | 只取消这次拖拽，**不退出编辑** |
 * | `Esc` 且命令框开着 | 只收起命令框（两条都不做） |
 * | 命令框里敲 `edit off` / `/gtetstudio reload` | 命令自己把编辑状态退掉，下一 tick 关屏 |
 * | `/gtetstudio edit off`、`/gtetstudio reload`、目标失效自动退出 | 下一 tick 自己发现 `!isEditing()` 并关屏 |
 * | 关屏（任何路径） | [removed] 收状态 + 还原飞行能力 |
 *
 * ⚠️ 这里**没有** M2a 那个"Esc 后 200ms 内把 `PauseScreen` 关掉"的补丁，也不需要：
 * 那个补丁的存在理由是"Forge 的 `InputEvent.Key` 发得比原版的 `pauseGame` 晚"，
 * 而屏幕开着时原版那条 `pauseGame` 分支根本不执行（`KeyboardHandler.java:389/408`）。
 *
 * @author rain fox
 */
@OnlyIn(Dist.CLIENT)
class StudioEditorScreen : Screen(Component.literal("Studio 编辑器")) {

    /** 相机移动速度（格 / tick），滚轮调。 */
    private var speed: Double = StudioEditorInput.DEFAULT_SPEED

    /** 右键按住 = 正在转视角（右键拖动才转，避免和左键拖轴打架）。 */
    private var looking = false

    /** 进编辑器之前的飞行能力（退出时还原；null = 还没动过）。 */
    private var savedMayfly: Boolean? = null
    private var savedFlying: Boolean? = null

    /** 飞行能力改动有没有同步给服务端（只发一次，别每 tick 刷包）。 */
    private var flightSynced = false

    /** 防止"Esc 退出"与"状态结束自动关屏"两条路各说一次话。 */
    private var closing = false

    /** 有没有由我们关掉原版 HUD（`hideGui`），以及进编辑器之前那个值。 */
    private var hidHud = false
    private var savedHideGui = false

    /** 命令框（由 [init] 建、`visible` 控制显隐；**位置就是预留区**）。 */
    private var inputBox: EditBox? = null

    /** 命令框开着没有。单独存一份而不是读 `inputBox.visible`：`init()` 重建控件时要先读到旧状态。 */
    private var inputOpen = false

    /**
     * 详情框（六行信息 + 命令框）收起没有（M2d）。
     *
     * 状态住在**这个 Screen 实例**上 ⇒ 一次编辑期间保持（改窗口大小只重建控件、不换实例），
     * 而下次进编辑是 `open()` 新建的实例 ⇒ 恢复默认的展开。连带关系见 [StudioDetailsState]。
     */
    private var details = StudioDetailsState.EXPANDED

    /**
     * 命令回显：面板上"刚才那条命令说了什么"。
     *
     * 为什么非得自己收：编辑模式开着时 `options.hideGui = true`，而原版**聊天栏也在
     * `!hideGui` 里面画**（`Gui.java:252` 起那一段，`this.chat.render(...)` 在 `:340`）
     * ⇒ 命令回执进聊天栏等于**看不见**。收口在 [StudioCommandBox]（换掉命令来源的消息出口），
     * 这里只负责画。
     *
     * ⚠️ 回显区画在**命令框下方**，不参与命令框的几何 ⇒ "预留区 = 命令框真实位置"这条不受它影响。
     */
    private var echoLines: List<String> = emptyList()
    private var echoOk = true

    /**
     * `Tab` / `Esc` / `H` 是不是"已经按下过、还没松开"（按**按键码**记账）。
     *
     * ⚠️ 这几个键是**切状态**的，而 GLFW 的按住连发也会走到 [keyPressed]
     * （`KeyboardHandler.java:379` 把 PRESS 与 REPEAT 一起送进来）。不过滤的后果是实打实的：
     * 命令框开着时按住 `Esc`，第一下关掉命令框、连发第二下就落到"退出编辑"上 ——
     * 正好破坏"命令框开着时 Esc 只关命令框"这条约定；按住 `H` 则会让面板抖个不停。
     *
     * 用集合而不是三个布尔字段：新增切状态键（M2d 的 `H`）时**不会多一处漏改的地方**。
     * 键码只在按下时登记、松开时移除，所以"按着不放"期间的 REPEAT 一律判成连发。
     */
    private val toggleKeysDown = HashSet<Int>()

    companion object {

        /**
         * **进编辑器模式的唯一入口** —— `/gtetstudio edit ...` 成功后调它
         * （命令实现见 [StudioEditorCommands]）。
         *
         * 已经开着就不重复开（例如连着敲两次 `edit`）：`setScreen` 会走一遍
         * `removed()`/`init()`，重复开等于把相机速度、右键状态全清一遍。
         */
        @JvmStatic
        fun open() {
            val mc = Minecraft.getInstance()
            if (mc.screen is StudioEditorScreen) return
            mc.setScreen(StudioEditorScreen())
        }

        // ── 面板配色（版式常量全在 editor/StudioEditorLayout.kt，一处定义）──
        /** 顶栏 / 底部提示条的底色。 */
        private const val BAR_BG = 0xC0000000.toInt()

        /** 动作反馈行的底色（用户点名要的那一条，颜色也没动）。 */
        private const val FEEDBACK_BG = 0xA0000000.toInt()

        /** 详情框底色（M2d 降了一档不透明度：40% 黑，文字带阴影仍然读得清）。 */
        private const val PANEL_BG = 0x66000000

        /** 鼠标悬在标题行上时的底色（标题行是可点的，得让它看得出来）。 */
        private const val PANEL_BG_HOVER = 0x8A000000.toInt()
        private const val ACCENT = 0xFFFFD479.toInt()
        private const val TEXT = 0xFFE6E6E6.toInt()
        private const val LABEL = 0xFF9CC7FF.toInt()
        private const val DIM = 0xFFA8A8A8.toInt()
        private const val WARN = 0xFFFF8A65.toInt()
        private const val OK = 0xFF8FE08F.toInt()

        /** 顶栏 / 反馈行里文字的基线（只有这几行用它，版式常量里没有）。 */
        private const val TEXT_Y = 7

        /** 命令框最多能敲多少字符（`gtetstudio edit gtetcore:some_long_model_id` 也放得下）。 */
        private const val INPUT_MAX_LEN = 96
    }

    /**
     * 详情框这一帧的几何（见 [StudioPanelLayout]）。
     *
     * **一处算、三处用**：画它（[drawPanel]）、摆 `EditBox`（[init]）、判"点的是不是面板"
     * （[mouseClicked]）读的都是这一个函数的返回值。之所以敢在 [init] 里直接调用：
     * 它只依赖窗口尺寸、开合状态与常量，**不依赖字体宽度**。
     */
    private fun layout(): StudioPanelLayout =
        StudioEditorLayout.of(this.width, this.height, details.collapsed)

    // ────────────────────────── 生命周期 ──────────────────────────

    /** **必须 false**：true 会让单人游戏暂停世界，gizmo 与世界一起停掉。理由见类注释。 */
    override fun isPauseScreen(): Boolean = false

    /**
     * 建控件。原版在**首次** `init(Minecraft,…)` 与**每次**窗口尺寸变化时都会走到这里
     * （`Screen.java:329-346` / `repositionElements` → `rebuildWidgets` → `clearWidgets` + `init`），
     * 所以 EditBox 在这里建最省事。
     */
    override fun init() {
        super.init()
        // 尺寸变化会把这个 Screen 的控件清空重建（`Screen.java:348-355`），
        // 但字段里那只旧的还在 —— 借它把"开着"和已经敲了一半的文本续上。
        val previous = inputBox
        val wasOpen = inputOpen

        val col = layout()
        val box = EditBox(this.font, col.boxX, col.boxY, col.boxW, col.boxH, Component.literal("命令框"))
        box.setMaxLength(INPUT_MAX_LEN)
        box.visible = false
        if (wasOpen && previous != null) {
            box.setValue(previous.value)
            box.visible = true
            box.moveCursorToEnd()
        }
        inputBox = box
        addRenderableWidget(box)

        if (wasOpen) setFocused(box)
    }

    /**
     * Esc 的兜底入口。
     *
     * 正常情况下 `keyPressed` 已经处理掉 Esc 了，这里只防"别的东西直接调 `onClose()`"。
     * ⚠️ 原版 `Screen.onClose()` 是 `minecraft.popGuiLayer()`（Forge 版本，不是 `setScreen(null)`），
     * **不会**弹暂停菜单 —— 暂停菜单只在"完全没有屏幕时按 Esc"才出现（`KeyboardHandler.java:408`）。
     * 所以 M2b 明确**删掉**了 M2a 那段"把 PauseScreen 关掉"的补丁。
     */
    override fun onClose() {
        exitEditor()
    }

    /** 关屏（任何路径）都收敛到这里：收交互状态、还原玩家能力、还原 HUD。 */
    override fun removed() {
        super.removed()
        looking = false
        inputBox = null
        inputOpen = false
        toggleKeysDown.clear()
        restoreFlight()
        // 还原原版 HUD（准星/物品栏/血量）——按进入前的值还原，免得把玩家的 F1 状态也改掉
        if (hidHud) {
            hidHud = false
            Minecraft.getInstance().options.hideGui = savedHideGui
        }
        StudioInteractionEvents.onEditorClosed()
    }

    override fun tick() {
        val mc = this.minecraft ?: return

        // 编辑状态没了（`edit off` / `reload` / 目标被拆掉的自动退出）⇒ 自己关屏。
        // 放在这里而不是让 interaction 层来关，是为了**交互层不需要认识这个 Screen 类**。
        if (!StudioEditor.isEditing()) {
            closing = true
            mc.setScreen(null)
            return
        }

        // **关掉原版 HUD**：准星、物品栏、血量条都是它画的 —— 编辑器要有自己的界面，
        // 不该同时看见游戏那一套（用户实机反馈"还会出现游戏自己的准星和物品显示"）。
        // 用的就是原版 F1 那个开关，退出时按进入前的值还原。
        if (!hidHud) {
            hidHud = true
            savedHideGui = mc.options.hideGui
            mc.options.hideGui = true
        }

        val player = mc.player ?: return
        if (mc.level == null) return
        ensureFlight(player)

        // ★ 命令框开着时**停掉飞行轮询**：那时 WASD 是"往框里打字"的意思，
        //   而 [applyMovement] 是直接问 GLFW 的（[axis]），不停的话打字会带着玩家到处飞。
        // ★ **拖拽 gizmo 时同样停掉**：拖东西的时候按 WASD/Shift 本来就不该把玩家开走 ——
        //   而 `Shift` 正好是"精细拖动（不吸附）"的默认修饰键，不停的话
        //   **"想精细拖"会变成"一边拖一边往下掉"**（用户实机反馈的正是这一条）。
        if (inputOpen || StudioInteractionEvents.isDragging()) {
            player.deltaMovement = Vec3.ZERO
        } else {
            applyMovement(player)
        }
    }

    // ────────────────────────── 命令框 ──────────────────────────

    /**
     * 调出命令框：**清空**并让它拿到焦点。
     *
     * 为什么不像坐标框那样预填当前 `anchor.offset`：这是个**命令**框，天天敲的是
     * `save` / `undo` / `edit`，预填一串坐标只会让人每次先删干净。
     * 当前偏移本来就一直显示在详情框的「偏移」那一行（收起时显示在那一行摘要里）。
     */
    private fun openInput() {
        val box = inputBox ?: return
        // ★ 收起态按 Tab：先把详情框展开 —— 命令框必须画在看得见的地方
        //   （详见 [StudioDetailsState.expandedForInput]）。几何不随开合变，所以不用重摆控件。
        details = details.expandedForInput()
        inputOpen = true
        box.visible = true
        box.active = true
        box.setValue("")
        box.moveCursorToEnd()
        // ⚠️ 必须走 Screen 这一层的 setFocused：它除了给控件设焦点，还把控件登记成
        //    Screen 的 focused 子节点 —— 原版 `charTyped` 正是靠它转发的
        //    （`ContainerEventHandler.charTyped` → `getFocused().charTyped(...)`，
        //    `KeyboardHandler.java:437-451`）。只调 `box.setFocused(true)` 会打不出字。
        setFocused(box)
    }

    /** 收起命令框（框里的字直接丢掉，命令没执行就是没执行）。 */
    private fun closeInput() {
        val box = inputBox ?: return
        inputOpen = false
        box.visible = false
        box.setFocused(false)
        setFocused(null)
    }

    /**
     * **收起 / 展开详情框**（M2d；点标题行或按 `H`）。
     *
     * ★ 收起时**连带收起命令框**（这是 M2d 选定的一种做法，另一种"保持命令框可见"没选）：
     * 命令框的预留区就画在详情框里面，收起之后它不再画；若把 `EditBox` 留着，
     * 就会变成"框还在（还拿着焦点、打字 WASD 都不飞）但屏幕上根本没有它"的怪状态。
     * 反过来也没有死角：收起态按 `Tab` 会先把面板展开（[openInput]）。
     *
     * 展开时**不**自动把命令框调回来：用户收起是为了看清画面，展开只是想看信息，
     * 顺手弹出一个抢焦点的输入框反而烦（要敲命令按 Tab 就是了）。
     */
    private fun toggleDetails() {
        details = details.toggled()
        if (!details.boxAllowed(inputOpen)) closeInput()
    }

    /**
     * **回车**：把框里那一行交给 [StudioEditorInput.classifyCommandLine] 分流。
     *
     * @return true = 这一下处理完了（收起命令框）；false = 留着框让人接着改
     */
    private fun submitCommand(text: String): Boolean = when (val line = StudioEditorInput.classifyCommandLine(text)) {
        // 空行：不收框（清空了想重敲，收框反而烦）
        StudioCommandLine.Empty -> {
            echoLines = listOf("> （空行）", "· 敲命令（save / undo / redo / edit / reload / list / limits）或三个坐标")
            echoOk = false
            false
        }

        // 看着像坐标但解析不出来：直接把坐标的原因说出来（比"不认识的命令"有用得多）
        is StudioCommandLine.BadOffset -> {
            echoLines = listOf("> $text", "· ${line.reason}")
            echoOk = false
            false
        }

        // 三个数：走拖拽那条路写 anchor.offset
        is StudioCommandLine.Offset -> {
            val applied = StudioInteractionEvents.applyOffsetInput(text)
            echoLines = listOf(
                "> $text",
                if (applied) "· 已写入 anchor.offset（可 Ctrl+Z 撤销，见上方反馈行）" else "· 没写进去（原因见上方反馈行）",
            )
            echoOk = applied
            applied
        }

        // 其余：当客户端命令执行（回执收在 [StudioCommandBox] 里，画在下面）
        is StudioCommandLine.Command -> {
            val result = StudioCommandBox.run(line.text)
            echoLines = listOf("> ${line.text}") + result.lines
            echoOk = result.ok
            // 失败就**留着框**让人改（打错一个字母不必重开一次）
            result.ok
        }
    }

    // ────────────────────────── 鼠标 ──────────────────────────

    /**
     * 单击。**顺序写死在这里**（这是最容易坏的地方）：
     * 1. 命令框开着且点在框内 ⇒ 给它（定位光标），**不再往下走**；
     * 2. 点在面板自己那一块（标题行 = 收起/展开；命令框预留区 = 调出/收起），
     *    **不再往下走** —— 面板吃掉的点击一个都不许漏进编辑器
     *    （不然点一下标题行会顺手拖一次轴）；
     * 3. 命令框开着但点在别处 ⇒ 先收起（否则键盘还锁在框里，"点了轴却打不了字也飞不动"），
     *    然后这一下**照常**给编辑器（拖 gizmo / 右键转视角）；
     * 4. 其余情况 ⇒ 全给编辑器。
     */
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val box = inputBox
        val col = layout()

        // ① ★ 命令框优先：它只吃自己那块矩形（`EditBox` 不覆盖 `mouseClicked`，
        //    走的是 `AbstractWidget.mouseClicked` → 命中矩形才返回 true，`AbstractWidget.java:184-199`）
        if (box != null && box.visible && box.mouseClicked(mouseX, mouseY, button)) {
            // 焦点要自己设：原版是 `ContainerEventHandler.mouseClicked` 在子控件吃掉事件后补的
            // （`ContainerEventHandler.java:51-64`），而我们绕过了它那条默认分发。
            setFocused(box)
            return true
        }

        // ② ★ 面板自己那一块（判决是纯函数，见 [StudioEditorLayout.onPanelClick]）：
        //    标题行 = 收起 / 展开；预留区 = 调出 / 收起命令框。两者都**吃掉**这一下。
        when (StudioEditorLayout.onPanelClick(mouseX, mouseY, col)) {
            StudioPanelClick.TITLE -> {
                toggleDetails()
                return true
            }

            StudioPanelClick.COMMAND_SLOT -> {
                if (inputOpen) closeInput() else openInput()
                return true
            }

            StudioPanelClick.NONE -> Unit
        }

        // ③ 点在别处 ⇒ 先收起，再把这一下交给编辑器
        if (inputOpen) closeInput()

        // ★ 把**真实**光标位置喂给交互层：拾取用它算射线（见 setCursorFromGui 的注释）
        StudioInteractionEvents.setCursorFromGui(mouseX, mouseY)
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            looking = true
            return true
        }
        return StudioInteractionEvents.onMousePress(StudioMouseButton.of(button))
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        StudioInteractionEvents.setCursorFromGui(mouseX, mouseY)
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            looking = false
            return true
        }
        return StudioInteractionEvents.onMouseRelease(StudioMouseButton.of(button))
    }

    /** 拖动：右键转视角；左键把"鼠标动了"告诉交互层（拖拽本来就是每帧按当前光标位置算的）。 */
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        StudioInteractionEvents.setCursorFromGui(mouseX, mouseY)
        return when (button) {
            GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                turnCamera(dragX, dragY)
                true
            }

            GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                StudioInteractionEvents.onMouseMove()
                true
            }

            else -> false
        }
    }

    /**
     * 不按键也要更新悬停高亮（每帧 BER 也会算一次，这里让它跟得更紧）。
     *
     * 命令框开着时**不喂光标**：那时光标在框里，喂过去会让 gizmo 的悬停高亮跟着乱跳
     * （点一下命令框就把某根轴点亮，看着像 bug）。
     */
    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        if (inputOpen) return
        StudioInteractionEvents.setCursorFromGui(mouseX, mouseY)
        StudioInteractionEvents.onMouseMove()
    }

    /** 滚轮 = 相机速度（原版那条"切物品栏"的分支在 `screen == null` 里，已经够不着了）。 */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        speed = StudioEditorInput.scrollSpeed(speed, delta)
        return true
    }

    // ────────────────────────── 键盘 ──────────────────────────

    /**
     * 按键。**第一件事是问"这一下归谁"**（[StudioEditorInput.onKeyTarget]），然后照着走：
     * ```
     * 命令框开着 → Tab/Esc = 收起 · 回车 = 写入 · 其余一律吞掉（WASD / Ctrl+Z / H 都别想溜到编辑器）
     * 命令框关着 → Tab = 调出 · H = 收起/展开详情框 · 其余照旧走编辑器判决（[StudioEditorInput.onKey]）
     * ```
     * ⚠️ 第二段的"其余一律吞掉"是**故意的**：原版 `EditBox` 只吃自己认得的那几个键
     * （`EditBox.java:311-386`），别的键它会返回 false，不拦就会掉进下面的编辑器逻辑里。
     */
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val pressed = InputConstants.getKey(keyCode, scanCode)
        val key = when {
            keyCode == GLFW.GLFW_KEY_ESCAPE -> StudioEditorKey.ESCAPE
            keyCode == GLFW.GLFW_KEY_TAB -> StudioEditorKey.TAB
            keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER -> StudioEditorKey.ENTER
            // 用键位表判，玩家在控制界面改过键位之后照样管用
            StudioKeyMappings.UNDO.isActiveAndMatches(pressed) -> StudioEditorKey.UNDO_KEY
            StudioKeyMappings.REDO.isActiveAndMatches(pressed) -> StudioEditorKey.REDO_KEY
            // M2d：H = 收起 / 展开详情框。**排在键位表之后**：万一玩家把撤销/重做改绑到 H，
            //      以玩家的键位为准（默认键位是 Z / Y，与 H 不冲突）。
            keyCode == GLFW.GLFW_KEY_H -> StudioEditorKey.DETAILS_KEY
            else -> StudioEditorKey.OTHER
        }

        // 按住连发（GLFW REPEAT 也会走到这里）只认第一次按下，理由见 toggleKeysDown 的注释
        val repeatPress = toggleKeysDown.add(keyCode).not()

        val box = inputBox
        when (StudioEditorInput.onKeyTarget(key, boxOpen = inputOpen)) {
            // Tab / Esc：**只**收起命令框（不取消拖拽、不退出编辑）
            StudioKeyTarget.CLOSE_INPUT -> if (!repeatPress) closeInput()

            // 回车：执行框里那一行（命令 / 坐标）。失败就把框留着让人改
            StudioKeyTarget.APPLY_INPUT -> if (box != null && submitCommand(box.value)) {
                closeInput()
            }

            // 命令框自己：退格 / 方向 / Home / End / Ctrl+A C V X 交给它，别的一律吞掉
            StudioKeyTarget.INPUT_BOX -> box?.keyPressed(keyCode, scanCode, modifiers)

            StudioKeyTarget.EDITOR -> return onEditorKey(key, repeatPress)
        }
        return true
    }

    /** 松开 = 允许下一次"第一次按下"（连发过滤见 [keyPressed]）。 */
    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        toggleKeysDown.remove(keyCode)
        return false // 编辑器没有"松开某个键"的行为，不吃这个事件
    }

    /** 命令框关着时的按键处理（M2b 的老逻辑 + Tab 调出命令框 + M2d 的 H 收起详情框）。 */
    private fun onEditorKey(key: StudioEditorKey, repeatPress: Boolean): Boolean {
        if (key == StudioEditorKey.TAB) {
            if (!repeatPress) openInput()
            return true
        }
        // 按住 Esc 的连发不再往下走：第一次按下已经把该做的做了（取消拖拽 / 退出编辑）
        if (key == StudioEditorKey.ESCAPE && repeatPress) return true

        // 修饰键**直接问 GLFW**（`Screen.hasControlDown()` 内部就是 `InputConstants.isKeyDown`），
        // 与 `StudioInteractionEvents.currentSnapRule()` 的口径一致。
        val action = StudioEditorInput.onKey(
            key = key,
            ctrl = hasControlDown(),
            shift = hasShiftDown(),
            dragging = StudioInteractionEvents.isDragging(),
        )

        return when (action) {
            // ★ M2d：H = 收起 / 展开详情框。按住连发只认第一下（不然面板会抖）。
            StudioEditorAction.TOGGLE_DETAILS -> {
                if (!repeatPress) toggleDetails()
                true
            }

            StudioEditorAction.UNDO -> {
                StudioInteractionEvents.undo()
                true
            }

            StudioEditorAction.REDO -> {
                StudioInteractionEvents.redo()
                true
            }

            StudioEditorAction.CANCEL_DRAG -> {
                StudioInteractionEvents.cancelDragByEscape()
                true
            }

            // Esc 且不在拖拽中 ⇒ 退出编辑并关屏。
            // ⚠️ 刻意**不调 `super.keyPressed`**：原版那一条会走 `onClose()`，
            //    而我们要顺带 `StudioEditor.exit()`（让 gizmo 停掉）并还原飞行状态。
            StudioEditorAction.EXIT_EDITOR -> {
                exitEditor()
                true
            }

            StudioEditorAction.BEGIN_DRAG,
            StudioEditorAction.END_DRAG,
            StudioEditorAction.NONE,
            -> false
        }
    }

    // ────────────────────────── 相机 ──────────────────────────

    /**
     * 右键拖动转视角。
     *
     * 灵敏度曲线**照抄原版** `MouseHandler.turnPlayer`（`d = (sens*0.6+0.2)^3 * 8`），
     * 所以同一个人在编辑器里和平时转视角的手感一致；`player.turn` 还会一起挪 `xRotO/yRotO`，
     * 避免第一人称插值抖动。
     */
    private fun turnCamera(dragX: Double, dragY: Double) {
        val mc = this.minecraft ?: return
        val player = mc.player ?: return
        val f = mc.options.sensitivity().get() * 0.6 + 0.2
        val scale = f * f * f * 8.0
        val invert = if (mc.options.invertYMouse().get()) -1.0 else 1.0
        player.turn(dragX * scale, dragY * scale * invert)
    }

    // ────────────────────────── 飞行 ──────────────────────────

    /**
     * 保证"在飞"。
     *
     * 为什么要动 `abilities`：编辑器要的是"自由飞着看"，而原版只有在 `mayfly` 为真时
     * `abilities.flying` 才有意义。落地时原版会自动把 `flying` 关掉
     * （`LocalPlayer.aiStep` 末尾），所以这里**每 tick 再确认一次**，编辑器期间永远在飞。
     *
     * 同步给服务端只发**一次**：`onUpdateAbilities()` 是一个网络包，
     * 每 tick 都发会把连接刷爆（而且落地那几 tick 会反复触发）。
     */
    private fun ensureFlight(player: LocalPlayer) {
        val abilities = player.abilities
        if (savedMayfly == null) {
            savedMayfly = abilities.mayfly
            savedFlying = abilities.flying
        }
        if (!abilities.mayfly) abilities.mayfly = true
        if (!abilities.flying) abilities.flying = true
        if (!flightSynced && (savedMayfly == false || savedFlying == false)) {
            flightSynced = true
            player.onUpdateAbilities()
        }
    }

    /** 退出时把进编辑器之前的飞行能力还回去（没动过就什么都不做）。 */
    private fun restoreFlight() {
        val mayfly = savedMayfly ?: return
        val flying = savedFlying ?: return
        savedMayfly = null
        savedFlying = null

        val player = Minecraft.getInstance().player ?: return
        val abilities = player.abilities
        abilities.mayfly = mayfly
        abilities.flying = flying
        if (flightSynced) {
            flightSynced = false
            player.onUpdateAbilities()
        }
    }

    /**
     * 把 WASD / 空格 / Shift 变成速度。
     *
     * **直接写 `deltaMovement`**：屏幕开着时 `input`（`KeyboardInput`）拿不到任何按键，
     * 与其去骗 KeyMapping，不如自己算。位移量就等于这里写的值
     * （`LivingEntity.travel` 先 `move()` 再谈摩擦，所以每 tick 覆盖一次就是恒定速度）。
     *
     * ⚠️ 只在命令框**没开**时被调用（见 [tick]）：它问的是 GLFW 的真实按键状态，
     * 分不清"在打字"和"在飞"。
     */
    private fun applyMovement(player: LocalPlayer) {
        val forward = axis(GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S)
        val left = axis(GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_D)
        val vertical = axis(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)

        if (forward == 0 && left == 0 && vertical == 0) {
            player.deltaMovement = Vec3.ZERO // 松手就悬停，编辑器里别飘
            return
        }

        // 与原版 `LivingEntity.getInputVector` 同一套：只吃 yaw ⇒ "低头按 W" 是平飞不是俯冲
        val yaw = Math.toRadians(player.yRot.toDouble())
        val s = sin(yaw)
        val c = cos(yaw)
        val vx = left * c - forward * s
        val vz = forward * c + left * s
        val vy = vertical.toDouble()

        val len = sqrt(vx * vx + vy * vy + vz * vz)
        if (len < 1e-9) {
            player.deltaMovement = Vec3.ZERO
            return
        }
        val k = speed / len
        player.setDeltaMovement(vx * k, vy * k, vz * k)
        player.resetFallDistance()
    }

    /** 按住的**真实按键**（屏幕开着时原版一个 KeyMapping 都不更新，只能问 GLFW）。 */
    private fun axis(positive: Int, vararg negative: Int): Int {
        val window = this.minecraft?.window?.window ?: return 0
        val up = InputConstants.isKeyDown(window, positive)
        val down = negative.any { InputConstants.isKeyDown(window, it) }
        return (if (up) 1 else 0) - (if (down) 1 else 0)
    }

    // ────────────────────────── 画面板 ──────────────────────────

    /**
     * **只画面板，不画 gizmo** —— gizmo 在世界里，由 BER 用模型那份姿态栈画
     * （`integration/StudioDynamicRender` → `StudioInteractionEvents.drawGizmo`），
     * 那边才能与模型严格对齐；GUI 这一层画出来的会错位。
     */
    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 刻意不调 `renderBackground()`：那是半透明黑罩，会把世界看暗，编辑器里要看得清
        drawPanel(guiGraphics, mouseX, mouseY)
        // ★ 命令框是 widget，由 `Screen.render` 统一遍历 renderables 画（它自己先判 visible）。
        //   这份源码里 `Screen.render` **只**遍历 renderables、不碰背景（`Screen.java:124-129`），
        //   所以调 super 不会把世界变暗，上面那句"不调 renderBackground"依然成立。
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    private fun drawPanel(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        val font = this.font
        val w = this.width
        val h = this.height
        val col = layout()
        val mx = mouseX.toDouble()
        val my = mouseY.toDouble()
        // 可点的标题行（收起 / 展开）；悬停时给底色，让人知道它能点
        val hoverTitle = col.inTitleRow(mx, my)
        val hoveringInput = col.inFrame(mx, my)
        // 收窄文本要用字体宽度，包成 lambda 传进纯函数 [StudioEditorLayout.fitText]
        val measure = { s: String -> font.width(s) }

        // ── 顶栏（保留）：模式 + 工具 + 当前相机速度 ──
        g.fill(0, 0, w, PANEL_TOP_BAR_H, BAR_BG)
        val title = "▣ Studio 编辑器"
        g.drawString(font, title, PANEL_PAD, TEXT_Y, ACCENT, true)
        g.drawString(font, "工具：平移（旋转 / 缩放 预留）", PANEL_PAD + font.width(title) + 14, TEXT_Y, TEXT, true)
        val speedText = "相机速度 ${String.format(Locale.ROOT, "%.2f", speed)} 格/tick（滚轮调）"
        g.drawString(font, speedText, (w - font.width(speedText) - PANEL_PAD).coerceAtLeast(PANEL_PAD), TEXT_Y, DIM, true)

        // ── ★ 动作反馈行：顶栏正下方（**M2d 一个字、一个像素都没动**）──
        //    文本由 `interaction/` 拼好（`模型id (x1,y1,z1) -> (x2,y2,z2)`），这里只管画在哪。
        g.fill(0, PANEL_TOP_BAR_H, w, PANEL_TOP_BAR_H + PANEL_FEEDBACK_H, FEEDBACK_BG)
        val feedback = StudioInteractionEvents.feedbackText()
        val feedbackY = PANEL_TOP_BAR_H + (PANEL_FEEDBACK_H - 8) / 2
        if (feedback != null) {
            g.drawString(font, feedback, PANEL_PAD, feedbackY, ACCENT, true)
        } else {
            // 没动作时也把这行画出来（暗色占位）：让人一眼知道反馈会出现在哪、长什么样
            g.drawString(font, "动作反馈：模型id (x1,y1,z1) -> (x2,y2,z2)（还没有动作）", PANEL_PAD, feedbackY, DIM, true)
        }

        // ── ★ 详情框（M2d：六行信息 + 命令框整体搬到左上角，紧跟在反馈行下面）──
        val target = StudioEditor.target()
        val dirty = StudioEditor.isCurrentDirty()
        // 吸附规则与拖拽时**必须同源**：两边都读可绑定的修饰键（默认 Ctrl / Shift），
        // 否则玩家改了绑定，面板显示的与实际生效的会对不上。
        val snap = StudioSnapRule.of(StudioKeyMappings.isStepDown(), StudioKeyMappings.isFineDown())
        val rows = ArrayList<Triple<String, String, Int>>(PANEL_INFO_ROWS)
        if (target != null) {
            // ★ 长「模型」行两步处理：① 紧凑写法去掉逗号后的空格；② 仍放不下时按可用宽度截断补「…」
            rows += Triple("模型", StudioEditorLayout.compactLabel(target.label), TEXT)
            rows += Triple("偏移", StudioEditor.liveOffset()?.format() ?: "—", TEXT)
        } else {
            rows += Triple("状态", "编辑已结束，正在关闭界面…", WARN)
        }
        rows += Triple("吸附", snap.label, TEXT)
        rows += Triple("锁定轴", StudioInteractionEvents.dragSummary() ?: "—（未在拖拽）", TEXT)
        rows += Triple(
            "改动",
            if (dirty) "● 未保存（/gtetstudio save 写回）" else "已保存",
            if (dirty) WARN else OK,
        )
        rows += Triple(
            "历史",
            "可撤销 ${StudioEditor.history.undoDepth} 步 ｜ 可重做 ${StudioEditor.history.redoDepth} 步",
            DIM,
        )

        if (details.collapsed) {
            drawCollapsedDetails(g, col, target, measure, hoverTitle)
        } else {
            drawExpandedDetails(g, col, rows, measure, hoverTitle, hoveringInput)
        }

        // ── 底部：操作提示（M2d 缩成**一行**；六条关键操作一个都没少）──
        g.fill(0, h - PANEL_HINT_H, w, h, BAR_BG)
        val hint = StudioEditorLayout.fitText(
            "左键拖轴｜右键转视角｜WASD 飞（空格/Shift 升降）｜滚轮调速｜" +
                "Tab 命令框｜H 收起详情｜Ctrl+Z 撤销｜Esc 关框·取消拖拽·退出",
            w - PANEL_PAD * 2,
            measure,
        )
        g.drawString(font, hint, PANEL_PAD, h - PANEL_HINT_H + 3, DIM, true)
    }

    /**
     * **展开态**：标题行（可点 = 收起）+ 六行信息 + 命令框预留区 + 命令回显。
     *
     * 预留区的矩形来自 [layout]（与 [init] 摆 `EditBox` 用的是同一份）——
     * **预留区画在哪，`EditBox` 就摆在哪**。
     */
    private fun drawExpandedDetails(
        g: GuiGraphics,
        col: StudioPanelLayout,
        rows: List<Triple<String, String, Int>>,
        measure: (String) -> Int,
        hoverTitle: Boolean,
        hoveringInput: Boolean,
    ) {
        val font = this.font

        // 卡片底色（M2d 降到 40% 黑：文字带阴影，仍读得清，但没那么挡视野）
        g.fill(col.cardX, col.cardY, col.cardRight, col.cardY + col.cardH, if (hoverTitle) PANEL_BG_HOVER else PANEL_BG)

        // ★ 标题行 = 收起开关（鼠标点它就收起；悬停变亮，让人知道它能点）
        val titleText = StudioEditorLayout.fitText(
            "▼ 详情（点这里 / H 收起）",
            col.cardW - PANEL_CARD_PAD * 2,
            measure,
        )
        g.drawString(
            font, titleText, col.cardX + PANEL_CARD_PAD, col.cardY + 3,
            if (hoverTitle) ACCENT else LABEL, true,
        )

        // 六行：标签列左对齐、数值列右对齐（整块宽度固定 ⇒ 数值超出就截断，不会顶到中线）
        val labelW = rows.maxOf { font.width(it.first) }
        val valueMax = col.valueWidth(labelW)
        val innerLeft = col.cardX + PANEL_CARD_PAD
        val innerRight = col.cardRight - PANEL_CARD_PAD
        var rowY = col.cardY + PANEL_TITLE_H + 4
        for ((label, value, color) in rows) {
            g.drawString(font, label, innerLeft, rowY, LABEL, true)
            val text = StudioEditorLayout.fitText(value, valueMax, measure)
            g.drawString(font, text, innerRight - font.width(text), rowY, color, true)
            rowY += PANEL_LINE_H
        }

        // ── ★ 命令框预留区：卡片正下方；**开着时 EditBox 就精确落在框内那一格** ──
        val frameColor = if (inputOpen || hoveringInput) ACCENT else DIM
        drawBorder(g, col.frameX, col.frameY, col.frameX + col.frameW, col.frameY + col.frameH, frameColor)
        val frameText = if (inputOpen) "命令框（Tab / Esc 收起）" else "命令框（Tab 调出）"
        g.drawString(
            font,
            StudioEditorLayout.fitText(frameText, col.frameW - PANEL_INPUT_INSET * 2, measure),
            col.frameX + PANEL_INPUT_INSET,
            col.frameY + 2,
            frameColor,
            true,
        )
        if (!inputOpen) {
            // 关着的时候：把那一格画成"空槽"，让人知道框会出现在哪、能敲什么
            g.fill(col.boxX, col.boxY, col.boxX + col.boxW, col.boxY + col.boxH, PANEL_BG)
            g.drawString(font, "点这里敲命令", col.boxX + 5, col.boxY + 6, DIM, true)
        }
        g.drawString(
            font,
            if (inputOpen) "回车执行 · Esc 放弃" else "例：save / undo / 1 2 3",
            col.frameX + PANEL_INPUT_INSET,
            col.boxY + col.boxH + 1,
            DIM,
            true,
        )

        // ── ★ 命令回显：命令框**下方**（不占命令框的位置）；窗口太矮时自动少画几行 ──
        if (echoLines.isNotEmpty() && col.echoMaxLines > 0) {
            val shown = if (echoLines.size > col.echoMaxLines) {
                listOf("…（共 ${echoLines.size} 行）") + echoLines.takeLast(col.echoMaxLines - 1)
            } else {
                echoLines
            }
            // 回显是"刚才那条命令说了什么"，宁可宽一点都不许丢字：
            // 可用宽度给到**屏幕中线**为止（面板本身比它窄），但仍然不越过中线。
            val maxW = col.midline - PANEL_PAD - col.cardX - 2
            var echoY = col.echoY
            for (line in shown) {
                val color = when {
                    !echoOk -> WARN
                    // 回显里我们自己的提示行（以 "· " 开头）比命令原始输出暗一档
                    line.startsWith("· ") -> DIM
                    else -> TEXT
                }
                val text = StudioEditorLayout.fitText(line, maxW, measure)
                g.drawString(font, text, col.cardX, echoY, color, true)
                echoY += PANEL_ECHO_LINE_H
            }
        }
    }

    /**
     * **收起态**：只有一行（`▸ 详情  gtet:test_clock @ -10,-59,-13 ｜ 偏移 (0, 1, 0)`）。
     *
     * 高度最小、颜色最淡 —— 用户抱怨的"挡视野"在这里基本消失，但仍然一眼看得到
     * "在编辑哪台机、偏移多少"。点这一行（或按 `H`）就展开。
     *
     * 收起态**不画**命令框预留区与回显：那时命令框必然已经收起（见 [toggleDetails]），
     * 画一个空槽只会白白占地方。
     */
    private fun drawCollapsedDetails(
        g: GuiGraphics,
        col: StudioPanelLayout,
        target: StudioEditTarget?,
        measure: (String) -> Int,
        hoverTitle: Boolean,
    ) {
        val font = this.font
        g.fill(col.cardX, col.cardY, col.cardRight, col.cardY + col.cardH, if (hoverTitle) PANEL_BG_HOVER else PANEL_BG)

        val head = "▸ 详情"
        g.drawString(font, head, col.cardX + PANEL_CARD_PAD, col.cardY + 3, if (hoverTitle) ACCENT else LABEL, true)

        // 摘要 = 模型 id（紧凑写法）+ 当前偏移；放不下就截断（它只是"一眼看到"，不要求全）
        val summary = if (target == null) {
            "编辑已结束"
        } else {
            val offset = StudioEditor.liveOffset()?.format() ?: "—"
            "${StudioEditorLayout.compactLabel(target.label)} ｜ 偏移 $offset"
        }
        val restX = col.cardX + PANEL_CARD_PAD + font.width(head) + 6
        val restMax = col.cardRight - PANEL_CARD_PAD - restX
        g.drawString(font, StudioEditorLayout.fitText(summary, restMax, measure), restX, col.cardY + 3, DIM, true)
    }

    /** 1 像素描边（原版 [GuiGraphics] 只有实心 `fill`，四条边拼一下）。 */
    private fun drawBorder(g: GuiGraphics, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        g.fill(x1, y1, x2, y1 + 1, color)
        g.fill(x1, y2 - 1, x2, y2, color)
        g.fill(x1, y1, x1 + 1, y2, color)
        g.fill(x2 - 1, y1, x2, y2, color)
    }

    // ────────────────────────── 退出 ──────────────────────────

    /** `Esc`（不在拖拽中、命令框也没开）：退出编辑 + 关屏。 */
    private fun exitEditor() {
        if (closing) return
        closing = true
        val message = StudioEditor.exit()
        Minecraft.getInstance().player
            ?.displayClientMessage(Component.literal("[gtetstudio] $message"), false)
        // 关屏会让 `removed()` 跑一遍（收状态 + 还原飞行），所以这里不再重复做
        this.minecraft?.setScreen(null)
    }
}