package rain.gtetcore.gtet.studio.interaction

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW
import org.slf4j.Logger
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.studio.editor.*
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents.currentRays
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents.drawGizmo
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents.feedbackText
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents.onMouseMove
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents.onMousePress
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents.onMouseRelease
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents.sendActionBar
import rain.gtetcore.gtet.studio.render.StudioFaceTransform
import kotlin.math.sqrt

/**
 * **世界内交互的输入/绘制总入口**（M2a 的 Axiom 式那块，M2b 改成"编辑器界面驱动"）。
 *
 * ## 每帧做的事（由 BER 调用，见 [drawGizmo]）
 * ```
 * 1. 摆到面坐标系（与模型渲染共用 StudioFaceTransform）→ 求逆
 * 2. 鼠标 → 世界射线（StudioCameraRay）→ 用逆矩阵压进局部空间
 * 3. 正在拖 → 用局部射线更新活值（**只改内存值**，渲染下一帧自然就变，不需要重烘 VBO）
 *    没在拖 → 拾手柄（先手柄、再机器）⇒ 悬停高亮
 * 4. 画 gizmo（always-on-top）
 * ```
 * 局部空间里三根轴恒等于单位向量，所以"轴的位移"直接就是 `anchor.offset` 的分量，
 * 不需要任何朝向补偿 —— 这是把 gizmo 和模型绑在同一套变换上的直接好处。
 *
 * ## 输入（★ M2b 换了驱动方）
 * M2a 是从 Forge 的 `InputEvent.MouseButton.Pre` / `InputEvent.Key` 收输入，
 * 但**原版没开屏幕时鼠标是锁死的**（光标钉在窗口中心、移动鼠标是转视角），
 * 唯一指针是准星 ⇒ 拿准星去戳一条 8 像素宽的轴实际上做不到。M2b 改成：
 * `/gtetstudio edit` 打开 [StudioEditorScreen]，由那个 Screen **独占输入**并回调本类的三个入口
 * —— [onMousePress] / [onMouseRelease] / [onMouseMove]。
 *
 * 于是：
 * - 两个 Forge 输入处理器（`InputEvent.MouseButton.Pre` / `InputEvent.Key`）**删掉了**：
 *   编辑器模式下它们是死代码（Screen 吃掉一切），留着只会让人以为"输入是从 Forge 来的"；
 * - M2a 那个"Esc 之后 200ms 内把 `PauseScreen` 关掉"的补丁**也删了**：
 *   它存在的唯一理由是 Forge 的 `InputEvent.Key` 发得比原版的 `pauseGame` 晚，
 *   而屏幕开着时原版那条 `pauseGame` 分支根本不执行（`KeyboardHandler.java:389/408`）；
 * - **拾取用的鼠标坐标一个字都没改**（仍是 `mc.mouseHandler.xpos()/ypos()`）——
 *   区别只在于光标解锁之后它就是**真实鼠标位置**，这正是"能戳中 8 像素轴"的前提。
 *
 * ## 判决在哪
 * "该不该起拖""Esc 是取消还是退出""Ctrl+Z 是不是撤销"这些**判决**全在
 * [StudioEditorInput]（纯函数，脱机自检可跑）；本类只负责"照着判决动手"。
 *
 * ## 缓存的矩阵（为什么鼠标事件能用上射线）
 * 投影矩阵只有渲染期才拿得到（渲染结束后 `RenderSystem.getProjectionMatrix()` 是 GUI 的正交矩阵），
 * 而鼠标事件发生在渲染之外。所以 [drawGizmo] 每帧把「局部↔相机相对世界」的逆矩阵、
 * 投影逆矩阵、视图旋转拷一份出来，供 [currentRays] 在任意时刻用**当前光标位置**重算射线。
 *
 * @author rain fox
 */
@Mod.EventBusSubscriber(modid = Gtetcore.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object StudioInteractionEvents {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** gizmo 轴长 = 相机距离 × 这个系数（屏幕上大小恒定）。 */
    private const val LENGTH_FACTOR = 0.16

    /** 轴长的上下限（太近别糊脸、太远别看不见）。 */
    private const val MIN_LENGTH = 0.35
    private const val MAX_LENGTH = 3.0

    /** 动作栏/面板反馈的保留时长（毫秒）。 */
    private const val FEEDBACK_MS = 4000L

    /** 正在进行的拖拽。 */
    private class ActiveDrag(
        val session: StudioDragSession,
        val holder: StudioAnchorOffset,
        val handle: StudioHandle,
        /** 目标全名（模型 id + 机器坐标）：进命令描述与日志。 */
        val label: String,
        /** 目标模型 id（`gtet:test_clock`）：**反馈行开头那段**用它（M2c 版式要求）。 */
        val modelId: String,
    )

    /** 一对射线：局部空间（拾取用）与世界空间（机器兜底拾取用）。 */
    private class Rays(val local: StudioRay, val world: StudioRay)

    private var drag: ActiveDrag? = null
    private var hover: StudioPick? = null

    /** 最近一帧的 gizmo（鼠标事件没有姿态栈，只能沿用上一帧渲染时的）。 */
    private var lastFrame: StudioGizmoFrame? = null

    // ── 最近一帧缓存的矩阵（见类注释"缓存的矩阵"）──
    private var lastLocalToCamRel: Matrix4f? = null
    private var lastCamRelToLocal: Matrix4f? = null
    private var lastProjection: Matrix4f? = null
    private var lastProjectionInverse: Matrix4f? = null
    private var lastViewRotation: Matrix3f? = null

    /** 最近一帧各手柄投影到屏幕的位置（像素判命中用，见 [StudioScreenPicker]）。 */
    private var lastScreenGeometry: StudioScreenGeometry? = null

    /**
     * 光标位置（**窗口像素**），由 [StudioEditorScreen] 每次鼠标事件递进来。
     *
     * ⚠️ 不直接用 `mc.mouseHandler.xpos()/ypos()`：Screen 打开时那个值不一定跟着更新
     * （它主要在"抓取光标"那条路上被刷新），而 Screen 每次回调都把**真实**坐标给了我们。
     */
    private var cursorWindowX: Double? = null
    private var cursorWindowY: Double? = null

    /** "左键没抓到手柄"那条诊断日志的限流时间戳。 */
    private var lastPickMissLoggedAt = 0L

    /** 面板上的即时反馈（屏幕开着时动作栏会被面板挡住，所以改喂面板）。 */
    @Volatile
    private var feedback: String? = null
    private var feedbackAt = 0L

    private var lastNoSourceWarned = false

    /** 本次编辑会话里是否已经打过"gizmo 首帧"那条诊断日志（退出编辑时复位）。 */
    private var loggedGizmoFrame = false

    // ────────────────────────── 每帧：由 BER 调用（与模型共用同一份姿态栈）──────────────────────────

    /**
     * 画 gizmo 并推进拖拽 —— **由 `integration/StudioDynamicRender` 在画完模型之后调用**。
     *
     * ## 为什么不在 `RenderLevelStageEvent` 里画（M2a 实机踩过这个坑）
     * 关卡阶段事件给的那份姿态栈，与 BER 里这份**不是同一个坐标系**：实测按
     * 「原点在相机、自己 translate(pos − camera)」推出来的位置，落在相机旁边三格处，
     * 连向量模长都不守恒。症状就是"编辑模式只有一行字，gizmo 看不见、也点不到"。
     *
     * 而 BER 这份姿态栈是**模型正在用的那一份**（模型能正确出现在机器上，已实机验证）。
     * 所以 gizmo 改从这里画 —— **两者共用同一个矩阵，就永远不可能不一致**，
     * 这也正是 [StudioFaceTransform] 被抽出来共用的初衷。
     *
     * @param poseStack    传进来的姿态栈**已由 BER 完成 `translate(blockPos − camera)`**，
     *                     这里再叠一次面坐标系即可（与 `StudioRenderer` 逐字同一套）。
     * @param viewRotation 视图旋转的 3×3 部分（**含视角摇晃**）：从 BER 进来前那份姿态栈取。
     *                     不用 `camera.rotation()` 是因为它不含摇晃，低头那一下拾取会与画面差出小半个手柄。
     */
    fun drawGizmo(
        poseStack: PoseStack,
        target: StudioEditTarget,
        viewRotation: Matrix3f,
    ) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return

        // ★ M2b：只挡**别人的**界面。编辑器自己的 Screen 开着时**必须照旧画、照旧拾取** ——
        //    写成原来的 `if (mc.screen != null) return` 的话，一进编辑 gizmo 立刻消失
        //    （这是 M2b 最容易踩的坑：界面开着 = 唯一的正常状态）。
        if (mc.screen != null && mc.screen !is StudioEditorScreen) return

        val offset = StudioEditor.liveOffset() ?: return

        // ── 局部 ↔ 相机相对世界 ──
        // **分两段拼**：① 面坐标系（局部 → 方块相对）；② 方块 → 相机那一段平移（`方块 − 相机`）。
        //
        // ⚠️ 第 ② 段绝不能漏 —— BER 那份姿态栈里**本来就含** `translate(方块 − 相机)`
        //    （`LevelRenderer.java:1271`），而我们这里是从零搭的。漏了它会同时坏三件事：
        //    · 距离按"方块原点"量 ⇒ 轴长被钳到下限（实机日志：`轴长 0.35`）；
        //    · 投影点全落在相机背后 ⇒ `w <= 0` 被跳过 ⇒ **轴段 0 个**（实机日志原话）；
        //    · 射线原点跑到方块角上 ⇒ 怎么拖都对不上。
        //    （M2b 实机就是靠这三个数字定位到这一处的。）
        val scratch = PoseStack()
        StudioFaceTransform.toFaceFrame(scratch, target.facing)
        val camPos = mc.gameRenderer.mainCamera.position
        val localToCamRel = Matrix4f()
            .translation(
                (target.pos.x - camPos.x).toFloat(),
                (target.pos.y - camPos.y).toFloat(),
                (target.pos.z - camPos.z).toFloat(),
            )
            .mul(scratch.last().pose())
        val camRelToLocal = Matrix4f(localToCamRel).invert()

        // ── 尺寸：按"相机到锚点的距离"缩放 ⇒ 屏幕上大小恒定（近处不糊脸、远处看得见）──
        val anchorCamRel = localToCamRel.transformPosition(
            Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat())
        )
        val distance = sqrt(
            (anchorCamRel.x * anchorCamRel.x + anchorCamRel.y * anchorCamRel.y + anchorCamRel.z * anchorCamRel.z).toDouble()
        )
        val length = (distance * LENGTH_FACTOR).coerceIn(MIN_LENGTH, MAX_LENGTH)
        val frame = StudioGizmoFrame(offset.vec(), length)

        // ★ 把这一帧的矩阵与 gizmo 缓存下来：鼠标事件（按下/松开/移动）发生在渲染之外，
        //   只有靠这份缓存才能用**当前**光标位置重算射线/投影，而不是上一帧的。
        val projection = Matrix4f(RenderSystem.getProjectionMatrix())
        lastFrame = frame
        lastLocalToCamRel = Matrix4f(localToCamRel)
        lastCamRelToLocal = camRelToLocal
        lastProjection = projection
        lastProjectionInverse = Matrix4f(projection).invert()
        lastViewRotation = Matrix3f(viewRotation)
        lastScreenGeometry = buildScreenGeometry(frame, projection)

        // 进编辑后的第一帧打一条**能直接看出画在哪**的日志：
        // 排查"看不见 gizmo"时第一眼要看的就是"世界坐标对不对、距相机多远、轴多长"。
        // 相机相对坐标 + 相机位置 = 世界坐标（BER 的矩阵正是"世界 − 相机"）。
        if (!loggedGizmoFrame) {
            loggedGizmoFrame = true
            LOGGER.info(
                "[studio] gizmo 首帧：机器 {}（pos {} 面 {}）offset {} → 世界坐标 ({}, {}, {})，距相机 {}，轴长 {}",
                target.label,
                "${target.pos.x},${target.pos.y},${target.pos.z}",
                target.facing,
                offset.format(),
                anchorCamRel.x + camPos.x,
                anchorCamRel.y + camPos.y,
                anchorCamRel.z + camPos.z,
                distance, length,
            )
        }

        val rays = currentRays()
        if (rays == null) {
            hover = null
            return
        }
        advance(rays)

        // ── 画：在 BER 的姿态栈上叠面坐标系（与模型逐字同一套）──
        poseStack.pushPose()
        try {
            StudioFaceTransform.toFaceFrame(poseStack, target.facing)
            StudioGizmoRenderer.draw(poseStack, frame, hoverHandle(), drag?.handle)
        } finally {
            poseStack.popPose()
        }
    }

    // ────────────────────────── 输入入口（由 StudioEditorScreen 调用）──────────────────────────

    /**
     * 鼠标按下。
     *
     * @return true = 这一下被编辑器吃了（屏幕已经挡住了原版，这里的返回值只用于上层判断）
     */
    @JvmStatic
    fun onMousePress(button: StudioMouseButton): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.player == null || !StudioEditor.isEditing()) return false

        // 命中判定用**当前**光标位置（由 Screen 递进来，见 [setCursorFromGui]）
        val handle = if (button == StudioMouseButton.LEFT) pickedHandle() else null
        if (button == StudioMouseButton.LEFT && handle == null && drag == null) logPickMiss()
        return when (StudioEditorInput.onPress(button, hitHandle = handle != null, dragging = drag != null)) {
            StudioEditorAction.BEGIN_DRAG -> beginDrag(handle)
            // 左键一律算被吃掉（界面上不该有别的左键行为）；右键交给屏幕去转视角
            StudioEditorAction.NONE -> button == StudioMouseButton.LEFT
            else -> false
        }
    }

    /** 鼠标松开：正在拖就收成**一条**命令。 */
    @JvmStatic
    fun onMouseRelease(button: StudioMouseButton): Boolean {
        if (!StudioEditor.isEditing()) return false
        return when (StudioEditorInput.onRelease(button, dragging = drag != null)) {
            StudioEditorAction.END_DRAG -> {
                finishDrag()
                true
            }

            else -> button == StudioMouseButton.LEFT
        }
    }

    /**
     * 鼠标移动：用当前光标位置重新推进一次（拖拽中刷新活值，否则刷新悬停高亮）。
     *
     * 与 [drawGizmo] 里每帧那一次是**同一套计算**，重复调用无副作用
     * （`StudioDragSession.update` 是"起始值 + 总位移"的纯函数，不是增量累加）。
     * 它真正的价值在于：拖拽时即使机器被视锥剔除（BER 不跑了），拖拽也不会卡住。
     */
    @JvmStatic
    fun onMouseMove() {
        if (!StudioEditor.isEditing()) return
        val rays = currentRays() ?: return
        advance(rays)
    }

    /** Esc 且正在拖拽：取消这次拖拽（回到按下前的值，不入撤销栈）。 */
    @JvmStatic
    fun cancelDragByEscape() {
        cancelDrag("按了 Esc")
    }

    /** 面板用：现在是不是在拖拽。 */
    @JvmStatic
    fun isDragging(): Boolean = drag != null

    /** 面板用：拖拽中的一行描述（把手柄 / 吸附规则 / 这一拖动了多少）；没拖拽时 null。 */
    @JvmStatic
    fun dragSummary(): String? {
        val active = drag ?: return null
        return "${active.handle}｜${currentSnapRule().label}｜Δ${active.session.lastDelta}"
    }

    /** 面板用：最近一次反馈（[sendActionBar] 在编辑器界面开着时改喂这里）；过期返回 null。 */
    @JvmStatic
    fun feedbackText(): String? {
        val text = feedback ?: return null
        return if (Util.getMillis() - feedbackAt <= FEEDBACK_MS) text else null
    }

    /**
     * **关闭编辑器界面 / 退出编辑时收状态**（由 `StudioEditorScreen.removed()` 调用）。
     *
     * 手里还按着左键就关屏的话，这次拖拽**作废**（回到按下前的值、不入撤销栈）——
     * 关屏是个"意图不明的中断"，不能替用户把半截位移提交上去。
     */
    @JvmStatic
    fun onEditorClosed() {
        drag?.let { active ->
            active.session.cancel()
            active.holder.setFrom(active.session.current)
            LOGGER.info("[studio] 关闭编辑器界面，作废未完成的拖拽（{}）", active.label)
        }
        drag = null
        hover = null
        lastFrame = null
        lastLocalToCamRel = null
        lastCamRelToLocal = null
        lastProjection = null
        lastProjectionInverse = null
        lastViewRotation = null
        lastScreenGeometry = null
        cursorWindowX = null
        cursorWindowY = null
        feedback = null
        loggedGizmoFrame = false
    }

    // ────────────────────────── 每 tick：状态提示 ──────────────────────────

    @JvmStatic
    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()

        // 不在编辑 ⇒ 收掉交互状态。
        // ⚠️ 必须在这里收：gizmo 现在由 BER 驱动（[drawGizmo]），退出编辑后就再没人调它了，
        //    不收的话下次进编辑会带着上次的拖拽/悬停状态。
        if (!StudioEditor.isEditing()) {
            drag = null
            hover = null
            lastFrame = null
            loggedGizmoFrame = false
        }

        // 目标没了（机器被拆/换维度）⇒ 自动退出编辑。
        // 关屏不在这里做：`StudioEditorScreen.tick()` 自己会发现 `!isEditing()` 并关掉自己，
        // 这样交互层完全不需要认识那个 Screen 类。
        StudioEditor.tick()?.let { sendChat("[$it]") }

        if (mc.player == null || mc.level == null) return
        // 编辑器界面开着时不刷动作栏：面板上已经有了（而且动作栏会被面板压住）
        if (mc.screen is StudioEditorScreen) return
        if (!StudioEditor.isEditing() && StudioEditor.dirtyModels().isEmpty()) return
        sendActionBar(statusText())
    }

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 把状态往前推一步：拖拽中 ⇒ 更新活值；否则 ⇒ 重新拾手柄（悬停高亮）。
     *
     * 两条纪律沿用 M2a：
     * 1. 拖拽期间**只改内存活值**，一条命令都不产生（松手才产生）；
     * 2. 松手检测**直接问 GLFW**（`mouseHandler.isLeftPressed()` 在事件被取消后仍会被原版更新，
     *    而 GLFW 的真实按键状态谁也改不了）—— 屏幕的 `mouseReleased` 是主路径，这是保险丝。
     */
    private fun advance(rays: Rays) {
        val frame = lastFrame ?: return
        val active = drag
        if (active != null) {
            active.holder.setFrom(active.session.update(rays.local, currentSnapRule()))
            if (!isLeftMouseDown(Minecraft.getInstance())) finishDrag()
        } else {
            hover = StudioPicker.pick(frame, rays.local) { machineDistance(rays.world) }
        }
    }

    /** 起拖：按下那一刻把参考系钉死（原点/抓取参数都是快照）。 */
    private fun beginDrag(handle: StudioHandle?): Boolean {
        val picked = handle ?: return false
        val frame = lastFrame ?: return false
        val rays = currentRays() ?: return false

        // 参考系自己漂移会表现为"越拖越加速"，所以按下时构造一次、整场不变
        val reference = when (picked) {
            is StudioHandle.Axis -> StudioDragReference.AxisLocked.of(frame.anchor, picked.axis, rays.local)
            is StudioHandle.Plane -> StudioDragReference.PlaneLocked.of(frame.anchor, picked.normal, rays.local)
        } ?: run {
            // 射线与参考退化（正对着轴/平面看过去）：这一下不算抓到手柄
            sendActionBar("射线与${picked}平行，这一下抓不住 —— 换个角度看再拖")
            return false
        }

        val holder = StudioEditor.liveOffset() ?: return false
        val target = StudioEditor.target() ?: return false
        drag = ActiveDrag(
            StudioDragSession(reference, holder),
            holder,
            picked,
            target.label,
            target.modelId.toString(),
        )
        hover = StudioPick.Handle(picked, 0.0)
        sendActionBar("开始拖动 $picked（${currentSnapRule().label}）")
        return true
    }

    private fun finishDrag() {
        val active = drag ?: return
        drag = null
        val command = active.session.toCommand(active.holder, active.label)
        if (command != null && StudioEditor.commit(command)) {
            // ★ 反馈行按用户要求排版：`模型id (x1,y1,z1) -> (x2,y2,z2)` 打头，"第几次/类型"跟在后面
            sendActionBar(
                stepText(active.modelId, command.beforeValue, command.afterValue) + "  已记录一步（Ctrl+Z 撤销）"
            )
        } else {
            sendActionBar("这次没有产生改动")
        }
    }

    /**
     * **命令框里敲了三个数（回车）**（M2c）：把三个数写进当前 `anchor.offset`。
     *
     * 走的**就是拖拽松手那条路**：一条 [TranslateAnchorCommand] 交给 [StudioEditor.commit]
     * 进撤销栈 —— 不另造一套写入逻辑，所以"输入写的值"和"拖出来的值"在撤销栈里没有区别，
     * Ctrl+Z 一样能撤。解析用纯函数 [StudioEditorInput.parseVec3]（脱机自检钉着）。
     *
     * @return true = 这一下输入处理完了（调用方据此收起命令框）；
     *         false = 没处理完（解析失败 / 已经不在编辑），命令框留着让人改
     */
    @JvmStatic
    fun applyOffsetInput(text: String): Boolean {
        val target = StudioEditor.target()
        val holder = StudioEditor.liveOffset()
        if (target == null || holder == null) {
            sendActionBar("已经不在编辑状态了")
            return false
        }

        return when (val parsed = StudioEditorInput.parseVec3(text)) {
            is StudioVectorParse.Bad -> {
                sendActionBar("坐标输入不合法：${parsed.reason}（要 x y z 三个数，逗号或空格分隔）")
                false
            }

            is StudioVectorParse.Ok -> {
                val command = TranslateAnchorCommand(
                    holder,
                    holder.copy(),
                    StudioAnchorOffset(parsed.x, parsed.y, parsed.z),
                    target.label,
                )
                if (StudioEditor.commit(command)) {
                    sendActionBar(
                        stepText(target.modelId.toString(), command.beforeValue, command.afterValue) +
                            "  已按输入写入（Ctrl+Z 撤销）"
                    )
                } else {
                    // 值没变：`StudioEditor.commit` 会把空动作丢掉，这里只回一句
                    sendActionBar("输入的值和现在一样，没有产生改动")
                }
                true // 收起来：这一下确实处理完了，只是可能没什么可做
            }
        }
    }

    private fun cancelDrag(reason: String) {
        val active = drag ?: return
        drag = null
        active.session.cancel()
        active.holder.setFrom(active.session.current)
        LOGGER.info("[studio] 取消拖拽（{}），{} 回到 {}", reason, active.label, active.holder.format())
        sendActionBar("已取消本次拖拽（$reason），回到按下前的值 ${active.holder.format()}")
    }

    /** 撤销一步（屏幕的 Ctrl+Z 与 `/gtetstudio undo` 共用）。 */
    @JvmStatic
    fun undo() {
        val done = StudioEditor.undo()
        if (done == null) {
            sendActionBar("没有可撤销的动作")
        } else {
            sendActionBar("撤销：${done.describe()}${if (StudioEditor.history.canUndo) "" else "（已到最早一步）"}")
        }
    }

    /** 重做一步（屏幕的 Ctrl+Y / Ctrl+Shift+Z 与 `/gtetstudio redo` 共用）。 */
    @JvmStatic
    fun redo() {
        val done = StudioEditor.redo()
        if (done == null) {
            sendActionBar("没有可重做的动作")
        } else {
            sendActionBar("重做：${done.describe()}")
        }
    }

    // ────────────────────────── 光标 → 射线 ──────────────────────────

    /**
     * 用**当前光标位置** + 上一帧缓存的矩阵算一对射线（局部 / 世界）。
     *
     * 与 M2a 在 [drawGizmo] 里那段逐字相同，区别只有两处：
     * 1. 矩阵来自缓存（渲染之外拿不到 `RenderSystem` 的那两份）；
     * 2. 光标解锁后 `mouseHandler.xpos()/ypos()` 就是真实鼠标位置 —— 这正是"能戳中 8 像素轴"的全部前提。
     */
    private fun currentRays(): Rays? {
        val camRelToLocal = lastCamRelToLocal ?: return null
        val projectionInverse = lastProjectionInverse ?: return null
        val viewRotation = lastViewRotation ?: return null

        val mc = Minecraft.getInstance()
        val window = mc.window
        if (window.screenWidth <= 0 || window.screenHeight <= 0) return null

        // ── 屏幕鼠标 → 视图方向 → 世界方向 ──
        // 光标优先用 Screen 递进来的（见 [setCursorFromGui]），拿不到才退回 mouseHandler。
        val cursorX = cursorWindowX ?: mc.mouseHandler.xpos()
        val cursorY = cursorWindowY ?: mc.mouseHandler.ypos()
        val ndcX = 2.0 * cursorX / window.screenWidth - 1.0
        val ndcY = 1.0 - 2.0 * cursorY / window.screenHeight
        val near = projectionInverse.transform(Vector4f(ndcX.toFloat(), ndcY.toFloat(), -1f, 1f))
        val far = projectionInverse.transform(Vector4f(ndcX.toFloat(), ndcY.toFloat(), 1f, 1f))
        if (near.w == 0f || far.w == 0f) return null
        near.div(near.w)
        far.div(far.w)
        val dirView = Vector3f(far.x - near.x, far.y - near.y, far.z - near.z).normalize()
        val dirWorld = Matrix3f(viewRotation).invert().transform(Vector3f(dirView))

        // ── 局部射线（原点 = 相机；在"相机相对世界"里相机就是原点）──
        val originLocal = camRelToLocal.transformPosition(Vector3f())
        val dirLocal = camRelToLocal.transformDirection(Vector3f(dirWorld))
        val local = StudioRay.of(v3(originLocal), v3(dirLocal)) ?: return null

        val camPos = mc.gameRenderer.mainCamera.position
        val world = StudioRay.of(
            StudioVec3(camPos.x, camPos.y, camPos.z),
            StudioVec3(dirWorld.x.toDouble(), dirWorld.y.toDouble(), dirWorld.z.toDouble()),
        ) ?: return null

        return Rays(local, world)
    }

    /**
     * 屏幕给的鼠标坐标（**GUI 缩放后的像素**）→ 窗口像素。
     *
     * ⚠️ 不要图省事用 `mc.mouseHandler.xpos()`：Screen 打开时那个值不一定跟着刷新，
     * 而 Screen 每次回调都把**真实**坐标递给我们 —— 这是"能戳中细轴"的前提之一。
     */
    @JvmStatic
    fun setCursorFromGui(mouseX: Double, mouseY: Double) {
        val scale = Minecraft.getInstance().window.guiScale.toDouble()
        cursorWindowX = mouseX * scale
        cursorWindowY = mouseY * scale
    }

    /**
     * 把 gizmo 各手柄投影到屏幕（窗口像素），供 [StudioScreenPicker] 按像素判命中。
     *
     * 投影链：**局部 →（面坐标系）相机相对世界 →（视图旋转）视图空间 →（投影矩阵）裁剪空间**。
     * `w <= 0` 说明该点在相机背后，直接跳过（否则会得到一个镜像的假坐标，反而更难点）。
     */
    private fun buildScreenGeometry(frame: StudioGizmoFrame, projection: Matrix4f): StudioScreenGeometry? {
        val localToCamRel = lastLocalToCamRel ?: return null
        val viewRotation = lastViewRotation ?: return null
        val window = Minecraft.getInstance().window
        if (window.screenWidth <= 0 || window.screenHeight <= 0) return null
        val width = window.screenWidth.toDouble()
        val height = window.screenHeight.toDouble()
        val view = Matrix3f(viewRotation)

        fun project(local: StudioVec3): StudioScreenPoint? {
            val camRel = localToCamRel.transformPosition(
                Vector3f(local.x.toFloat(), local.y.toFloat(), local.z.toFloat())
            )
            val v = view.transform(Vector3f(camRel))
            val clip = projection.transform(Vector4f(v.x, v.y, v.z, 1f))
            if (clip.w <= 1e-6f) return null
            return StudioScreenPoint(
                (clip.x / clip.w + 1.0) / 2.0 * width,
                (1.0 - clip.y / clip.w) / 2.0 * height,
            )
        }

        val segments = LinkedHashMap<StudioAxis, Pair<StudioScreenPoint, StudioScreenPoint>>()
        for (axis in StudioAxis.entries) {
            val from = project(frame.axisFrom(axis)) ?: continue
            val to = project(frame.axisTo(axis)) ?: continue
            segments[axis] = from to to
        }
        val planes = LinkedHashMap<StudioHandle.Plane, StudioScreenPoint>()
        for (normal in StudioAxis.entries) {
            val handle = StudioHandle.ofPlane(normal)
            val center = project(frame.planeCenter(handle.first, handle.second)) ?: continue
            planes[handle] = center
        }
        return StudioScreenGeometry(segments, planes)
    }

    /**
     * 左键没抓到任何手柄时打一条（限流 1 秒）。
     *
     * 排查"戳不中"时这条最有用：直接给出光标位置与"最近的那根轴在屏幕上离光标多少像素"——
     * 是阈值太小、还是坐标系不对，一眼就能分开。
     */
    private fun logPickMiss() {
        val now = Util.getMillis()
        if (now - lastPickMissLoggedAt < 1000L) return
        lastPickMissLoggedAt = now
        val cx = cursorWindowX
        val cy = cursorWindowY
        val geometry = lastScreenGeometry
        var nearest = Double.MAX_VALUE
        if (geometry != null && cx != null && cy != null) {
            val cursor = StudioScreenPoint(cx, cy)
            for ((_, segment) in geometry.axisSegments) {
                nearest = minOf(nearest, StudioScreenPicker.distanceToSegment(cursor, segment.first, segment.second))
            }
        }
        LOGGER.info(
            "[studio] 左键没抓到手柄：光标 ({}, {})，最近轴 {} px，轴段 {} 个，轴长 {}",
            cx, cy,
            if (nearest == Double.MAX_VALUE) "n/a" else nearest,
            geometry?.axisSegments?.size ?: 0,
            lastFrame?.length,
        )
    }

    /** 当前光标指着哪个手柄（用缓存矩阵重算，**不是**上一帧那份射线）。 */
    private fun pickedHandle(): StudioHandle? {
        val frame = lastFrame ?: return null

        // ① **屏幕空间优先**：按像素判，细轴才真的戳得中（见 StudioScreenPicker 的类注释）
        val geometry = lastScreenGeometry
        val cx = cursorWindowX
        val cy = cursorWindowY
        if (geometry != null && cx != null && cy != null) {
            val scale = Minecraft.getInstance().window.guiScale.toDouble()
            StudioScreenPicker.pick(geometry, StudioScreenPoint(cx, cy), scale)?.let { return it }
        }

        // ② 退回世界空间射线（斜看 / 贴脸时那种求交更准，也顺带兼容旧行为）
        val rays = currentRays() ?: return null
        return StudioPicker.pickHandle(frame, rays.local)
    }

    private fun v3(v: Vector3f): StudioVec3 = StudioVec3(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())

    /** 命中优先级里"再机器"那一步：只有没打到手柄时才会被调用。 */
    private fun machineDistance(worldRay: StudioRay): StudioMachineHit? {
        val source = StudioEditor.sourceOrNull()
        if (source == null) {
            if (!lastNoSourceWarned) {
                lastNoSourceWarned = true
                LOGGER.error("[studio] 编辑器还没接上宿主查询（StudioMachineRegistry.install），机器拾取不可用")
            }
            return null
        }
        val distance = source.pickMachine(worldRay) ?: return null
        return StudioMachineHit(distance)
    }

    private fun hoverHandle(): StudioHandle? = (hover as? StudioPick.Handle)?.handle

    /**
     * 当前修饰键 → 吸附规则（整格 / 精细 / 默认 0.25 格）。
     *
     * 两个修饰键现在都是**可绑定**的（默认 `Ctrl` / `Shift`，见 [StudioKeyMappings]）——
     * 玩家嫌 `Shift` 与飞行下降撞车，就在 选项→控制→按键绑定→「机器渲染工作室」里换掉，
     * 这里自动跟着变（不再写死 GLFW 键码）。
     */
    private fun currentSnapRule(): StudioSnapRule =
        StudioSnapRule.of(StudioKeyMappings.isStepDown(), StudioKeyMappings.isFineDown())

    /** **直接问 GLFW**：事件被我们取消也不影响真实按键状态。 */
    private fun isLeftMouseDown(mc: Minecraft): Boolean =
        GLFW.glfwGetMouseButton(mc.window.window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS

    /**
     * 持续状态走动作栏（屏幕下方那一行）。
     *
     * ⚠️ M2b：编辑器界面开着时动作栏会被面板压住 ⇒ 改成喂给面板
     * （[feedbackText] → `StudioEditorScreen` 右下角那一行）。
     */
    private fun sendActionBar(text: String) {
        val mc = Minecraft.getInstance()
        if (mc.screen is StudioEditorScreen) {
            feedback = text
            feedbackAt = Util.getMillis()
            return
        }
        mc.player?.displayClientMessage(Component.literal("[studio] $text"), true)
    }

    /** 需要留痕的（失败/自动退出）走聊天栏。 */
    private fun sendChat(text: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal(text), false)
    }

    /**
     * **动作反馈行的正文**（M2c 的面板版式要求，用户原话是"显示 xxx(x1,y1,z1) -> xxx(x2,y2,z2)"）：
     * ```
     * gtet:test_clock (0.00, 1.00, 0.00) -> (0.00, 1.25, 0.00)
     * ```
     * 拼串在 [StudioNumberFormat.stepLine]（纯 Kotlin、脱机自检逐字钉着），
     * 屏幕只负责"画在屏幕上方那一行"—— 免得它去解析/裁切别人给的字符串。
     */
    private fun stepText(modelId: String, before: StudioAnchorOffset, after: StudioAnchorOffset): String =
        StudioNumberFormat.stepLine(modelId, before, after)

    /** 动作栏文案（**只在编辑器界面没开时**用）：编辑哪个模型、哪个字段、有没有未保存改动。 */
    private fun statusText(): String {
        val target = StudioEditor.target()
        if (target == null) {
            return "未在编辑（还有 ${StudioEditor.dirtyModels().size} 个模型的改动没写回文件）"
        }
        val offset = StudioEditor.liveOffset()
        val dirty = if (StudioEditor.isCurrentDirty()) " ●未保存（/gtetstudio save 写回文件）" else " 已保存"
        return "编辑 ${target.label}｜anchor.offset ${offset?.format()}$dirty（/gtetstudio edit 打开编辑器界面）"
    }
}