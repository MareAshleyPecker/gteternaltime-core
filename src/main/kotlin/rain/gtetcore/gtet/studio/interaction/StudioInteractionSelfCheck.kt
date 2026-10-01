package rain.gtetcore.gtet.studio.interaction

import com.google.gson.JsonParser
import rain.gtetcore.gtet.studio.config.stripJsonComments
import rain.gtetcore.gtet.studio.data.StudioAnchorWriteException
import rain.gtetcore.gtet.studio.data.StudioAnchorWriter
import rain.gtetcore.gtet.studio.editor.*
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * **M2a 的脱机自检** —— 交互层的数学、拖拽语义、命令栈、以及"文本级写回 JSON"全都不需要启动游戏。
 *
 * 这是"手感交给用户、逻辑交给我"这条分工的落点：实机只有用户能验
 * （gizmo 好不好看、跟不跟手、拖起来别扭不别扭），但下面这些**必须机器可验**：
 * ```
 * ① 射线-轴 / 射线-平面 / 射线-包围盒求交（含退化：平行、背向、起点在盒内）
 * ② 吸附（0.25 / 1 格 / Shift 不吸附）与"轴锁定只改一个分量"（含"其余分量连吸附都不参与"）
 * ③ gizmo 拾取优先级：轴 → 平面 → 机器（手柄挡住机器时**不许**问机器）
 * ④ 拖拽：连续拖拽合并成**一条**命令；Esc 取消回到按下前的值
 * ⑤ 命令栈：apply/revert 对称、undo/redo 顺序、容量上限淘汰最旧
 * ⑥ 文本级 JSON 写回：**注释与其余字段一字不差**；结构不对 ⇒ 拒绝改写
 * ⑦ **M2b 编辑器模式的输入状态机**：按下了什么 / 该不该起拖 / Esc 该取消还是该退出 /
 *    滚轮调速（连"非有限输入不污染速度"这种边角也钉住）
 * ⑧ 屏幕空间拾取：像素阈值 / 轴优先于平面 / 退化线段
 * ⑨ **M2c 坐标输入框**：文本解析（能解析的给三个数、任何非法输入都给理由且不抛异常）、
 *    **按键归属**（输入框开着时 WASD / Ctrl+Z 一个都不许溜到编辑器，Esc 只关输入框）、
 *    以及**动作反馈行的格式**（`模型id (x1,y1,z1) -> (x2,y2,z2)` 逐字钉住）
 * ⑩ **M2d 详情框**：左上角几何**不越过屏幕中线**、预留区恰好包住输入框（一处算三处用）、
 *    点标题行 = 切收起且不产生编辑器动作、收起态命令框的归属、长「模型」行收窄
 * ```
 *
 * ## 怎么跑（在仓库根目录）
 * ```
 * .\gradlew classes
 * java "-Dstdout.encoding=UTF-8" -cp "build\classes\kotlin\main;<kotlin-stdlib.jar>;<gson.jar>" `
 *      rain.gtetcore.gtet.studio.interaction.StudioInteractionSelfCheck
 * ```
 * ⚠️ **kotlin-stdlib 与 gson 两个 jar 都要**：写回那一节用 gson 重新解析核对
 * （与 `format/StudioFormatSelfCheck` 同一个理由）。
 *
 * 断言失败以退出码 1 结束（能接进脚本）。**生产路径上没有任何调用点。**
 *
 * @author rain fox
 */
object StudioInteractionSelfCheck {

    private var passed = 0
    private val failures = ArrayList<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        println("═══ studio interaction 自检（不需要启动游戏）═══")
        println()

        checkRayMath()
        checkSnap()
        checkGizmoPick()
        checkDrag()
        checkHistory()
        checkJsonWriteBack()
        checkEditorInput()
        checkScreenPick()
        checkInputBox()
        checkEditorPanel()

        println()
        println("═══ 结果：$passed 项通过，${failures.size} 项失败 ═══")
        for (failure in failures) println("  ✗ $failure")
        if (failures.isNotEmpty()) exitProcess(1)
    }

    // ────────────────────────── ① 射线数学 ──────────────────────────

    private fun checkRayMath() {
        section("射线数学（含退化：平行 / 背向 / 起点在盒内）")

        val forward = ray(0.0, 0.0, 0.0, 0.0, 0.0, 1.0) // 从原点朝 +Z

        check(
            "射线与平面相交：平面 z=5 ⇒ t=5",
            near(StudioRayMath.rayPlaneT(forward, vec(0.0, 0.0, 5.0), vec(0.0, 0.0, 1.0)), 5.0),
            "实际：${StudioRayMath.rayPlaneT(forward, vec(0.0, 0.0, 5.0), vec(0.0, 0.0, 1.0))}",
        )
        check(
            "射线与平面**平行** ⇒ null（不许给一个瞎猜的交点）",
            StudioRayMath.rayPlaneT(forward, vec(0.0, 0.0, 5.0), vec(1.0, 0.0, 0.0)) == null,
        )
        check(
            "平面在**背后**（z=-5）⇒ t<0；forwardOnly 时拒绝",
            near(StudioRayMath.rayPlaneT(forward, vec(0.0, 0.0, -5.0), vec(0.0, 0.0, 1.0)), -5.0) &&
                StudioRayMath.rayPlanePoint(forward, vec(0.0, 0.0, -5.0), vec(0.0, 0.0, 1.0)) == null,
            "t=${StudioRayMath.rayPlaneT(forward, vec(0.0, 0.0, -5.0), vec(0.0, 0.0, 1.0))}",
        )
        check(
            "背后但允许时仍能算出来（自检用得到）",
            StudioRayMath.rayPlanePoint(forward, vec(0.0, 0.0, -5.0), vec(0.0, 0.0, 1.0), forwardOnly = false)
                ?.let { near(it.z, -5.0) } == true,
        )

        // 射线沿 +Z（从 (0,0,-10)），轴线是「过 (2,0,0)、方向 +X」⇒ 交在原点正上方
        val graze = ray(0.0, 0.0, -10.0, 0.0, 0.0, 1.0)
        val approach = StudioRayMath.closestApproach(graze, vec(2.0, 0.0, 0.0), vec(1.0, 0.0, 0.0))
        check(
            "射线↔轴最近点：射线参数 10、轴参数 -2（交点落在轴起点之前）、距离 0",
            approach != null && near(approach.rayParam, 10.0) && near(approach.axisParam, -2.0) &&
                near(approach.distance, 0.0),
            "实际：$approach",
        )
        check(
            "射线与轴**平行** ⇒ null（两条平行线没有唯一最近点）",
            StudioRayMath.closestApproach(forward, vec(2.0, 0.0, 0.0), vec(0.0, 0.0, 1.0)) == null,
        )
        // 轴在射线的**背后**：射线从 z=-10 朝 +Z，而轴躺在 z=-20
        val behind = StudioRayMath.closestApproach(forward, vec(0.0, 0.0, -20.0), vec(1.0, 0.0, 0.0))
        check(
            "轴在**背后** ⇒ 最近点的射线参数为负（拾取据此拒绝）",
            behind != null && behind.rayParam < 0.0,
            "实际：$behind",
        )

        val boxMin = vec(-1.0, -1.0, -1.0)
        val boxMax = vec(1.0, 1.0, 1.0)
        check(
            "射线打 AABB：从 (0,0,-5) 朝 +Z ⇒ t=4",
            near(StudioRayMath.aabbHit(ray(0.0, 0.0, -5.0, 0.0, 0.0, 1.0), boxMin, boxMax), 4.0),
        )
        check(
            "起点在盒内 ⇒ t=0（不是 null）",
            near(StudioRayMath.aabbHit(ray(0.0, 0.0, 0.0, 0.0, 0.0, 1.0), boxMin, boxMax), 0.0),
        )
        check("擦不到 ⇒ null", StudioRayMath.aabbHit(ray(5.0, 5.0, -5.0, 0.0, 0.0, 1.0), boxMin, boxMax) == null)
        check(
            "射线在某一维上不前进、且原点在板外 ⇒ null（slab 法的退化分支）",
            StudioRayMath.aabbHit(ray(5.0, 0.0, -5.0, 0.0, 0.0, 1.0), boxMin, boxMax) == null,
        )

        check(
            "点到线段距离：点 (0,2,0) 到 x 轴上的线段 ⇒ 2",
            near(StudioRayMath.distanceToSegment(vec(0.0, 2.0, 0.0), vec(0.0, 0.0, 0.0), vec(10.0, 0.0, 0.0)), 2.0),
        )
        check(
            "点在端点外侧 ⇒ 到端点的距离（线段不是直线）",
            near(StudioRayMath.distanceToSegment(vec(15.0, 0.0, 0.0), vec(0.0, 0.0, 0.0), vec(10.0, 0.0, 0.0)), 5.0),
        )
        check("零向量方向 ⇒ 建不出射线（返回 null）", StudioRay.of(vec(0.0, 0.0, 0.0), StudioVec3.ZERO) == null)
    }

    // ────────────────────────── ② 吸附 ──────────────────────────

    private fun checkSnap() {
        section("吸附（0.25 / 1 格 / Shift 不吸附）")

        check("默认 0.25 格：1.37 → 1.25", near(StudioSnapRule.QUARTER.snap(1.37), 1.25), "实际：${StudioSnapRule.QUARTER.snap(1.37)}")
        check("默认 0.25 格：-0.4 → -0.5", near(StudioSnapRule.QUARTER.snap(-0.4), -0.5), "实际：${StudioSnapRule.QUARTER.snap(-0.4)}")
        check("Ctrl 1 格：1.37 → 1", near(StudioSnapRule.FULL.snap(1.37), 1.0), "实际：${StudioSnapRule.FULL.snap(1.37)}")
        check("Shift 不吸附：1.37 原样", near(StudioSnapRule.OFF.snap(1.37), 1.37))
        check(
            "修饰键组合：无→0.25 / Ctrl→1 / Shift→不吸附 / 两个都按→不吸附",
            StudioSnapRule.of(false, false) === StudioSnapRule.QUARTER &&
                StudioSnapRule.of(true, false) === StudioSnapRule.FULL &&
                StudioSnapRule.of(false, true) === StudioSnapRule.OFF &&
                StudioSnapRule.of(true, true) === StudioSnapRule.OFF,
        )
        check(
            "吸附后的值真的落在网格上",
            StudioSnapRule.onGrid(StudioSnapRule.QUARTER.snap(0.31), 0.25) &&
                StudioSnapRule.onGrid(StudioSnapRule.FULL.snap(3.9), 1.0),
        )
        check(
            "Float 版不产生脏尾巴：0.25 网格上 0.1+0.15 也能吸回 0.25",
            StudioSnapRule.QUARTER.snap(0.2500001f) == 0.25f,
            "实际：${StudioSnapRule.QUARTER.snap(0.2500001f)}",
        )
    }

    // ────────────────────────── ③ gizmo 拾取 ──────────────────────────

    private fun checkGizmoPick() {
        section("gizmo 拾取（轴 → 平面 → 机器；退化情形拒绝）")

        val frame = StudioGizmoFrame(StudioVec3.ZERO, 2.0)

        // 从 (1, 0.5, 5) 朝 -Z 偏下打：正好穿过 X 轴上的 (1,0,0)
        val onXAxis = ray(1.0, 0.5, 5.0, 0.0, -0.5, -5.0)
        val axisHit = StudioPicker.pickHandle(frame, onXAxis)
        check("打到 X 轴手柄 ⇒ Axis(X)", axisHit == StudioHandle.Axis(StudioAxis.X), "实际：$axisHit")

        // 平面手柄（法线 Z）在 (0.9, 0.9, 0)：从 +Z 正面打过去，离三根轴都够远
        val onPlane = ray(0.9, 0.9, 5.0, 0.0, 0.0, -1.0)
        val planeHit = StudioPicker.pickHandle(frame, onPlane)
        check(
            "打到平面手柄 ⇒ Plane(X, Y, 法线 Z)",
            planeHit == StudioHandle.ofPlane(StudioAxis.Z),
            "实际：$planeHit",
        )

        // 轴手柄与平面手柄都在附近时，轴优先
        val both = ray(0.9, 0.02, 5.0, 0.0, 0.0, -1.0)
        check(
            "轴与平面都能命中时 ⇒ 轴优先（平面块大、离原点远，不优先就会老是抓错）",
            StudioPicker.pickHandle(frame, both) == StudioHandle.Axis(StudioAxis.X),
            "实际：${StudioPicker.pickHandle(frame, both)}",
        )

        check(
            "完全打偏 ⇒ 什么都没命中",
            StudioPicker.pickHandle(frame, ray(9.0, 9.0, 5.0, 0.0, 0.0, -1.0)) == null,
        )
        check(
            "手柄在相机背后 ⇒ 不算命中（否则转身时手柄会莫名粘住）",
            StudioPicker.pickHandle(frame, ray(1.0, 0.5, -5.0, 0.0, 0.0, -1.0)) == null,
        )

        // 命中优先级：手柄挡住机器时，机器回调**一次都不该被调用**
        var machineConsulted = 0
        val handleWins = StudioPicker.pick(frame, onXAxis) {
            machineConsulted++
            StudioMachineHit(3.0)
        }
        check(
            "手柄命中时**不穿透**到机器（机器回调没被调用）",
            handleWins is StudioPick.Handle && machineConsulted == 0,
            "实际：$handleWins，机器被问了 $machineConsulted 次",
        )

        val machineWins = StudioPicker.pick(frame, ray(9.0, 9.0, 5.0, 0.0, 0.0, -1.0)) {
            machineConsulted++
            StudioMachineHit(3.0)
        }
        check(
            "没打到手柄时落到机器上（先手柄、再机器）",
            machineWins is StudioPick.Machine && machineConsulted == 1,
            "实际：$machineWins",
        )
        check(
            "什么都没有 ⇒ null",
            StudioPicker.pick(frame, ray(9.0, 9.0, 5.0, 0.0, 0.0, -1.0)) { null } == null,
        )
    }

    // ────────────────────────── ④ 拖拽 ──────────────────────────

    private fun checkDrag() {
        section("拖拽（轴锁定 / 平面锁定 / 退化 / 一次拖拽 = 一条命令）")

        // ── 轴锁定：只改一个分量 ──
        val start = StudioAnchorOffset(0f, 1.1f, 0f)
        val holder = start.copy()
        val grabRay = ray(0.0, 1.1, 5.0, 0.0, 0.0, -1.0) // 打在 X 轴的 (0,1.1,...)? 见下：轴上取最近点
        val axisRef = StudioDragReference.AxisLocked.of(StudioVec3(0.0, 1.1, 0.0), StudioAxis.X, grabRay)
        check("按下时能建出轴参考", axisRef != null)
        val axisSession = StudioDragSession(axisRef!!, start)

        // 沿 +X 拖 2.3 格：射线从 (2.3, 1.1, 5) 朝 -Z
        axisSession.update(ray(2.3, 1.1, 5.0, 0.0, 0.0, -1.0), StudioSnapRule.QUARTER)
        val afterX = axisSession.current
        check(
            "拖 X 轴：X 变成 2.25（0.25 吸附），**Y 保持 1.1 原值**（其余分量连吸附都不参与）",
            near(afterX.x.toDouble(), 2.25) && near(afterX.y.toDouble(), 1.1) && near(afterX.z.toDouble(), 0.0),
            "实际：$afterX",
        )
        check(
            "轴锁定的允许轴只有一根",
            axisSession.axes == listOf(StudioAxis.X),
            "实际：${axisSession.axes}",
        )

        // ── 一次拖拽 = 一条命令（连续 update 不产生命令）──
        val dragHistory = StudioHistory()
        repeat(5) { i ->
            axisSession.update(ray(2.3 + i * 0.3, 1.1, 5.0, 0.0, 0.0, -1.0), StudioSnapRule.QUARTER)
            holder.setFrom(axisSession.current)
            check(
                "拖拽过程中不入栈（第 ${i + 1} 帧移动后仍是 0 条）",
                dragHistory.undoDepth == 0,
                "实际：${dragHistory.undoDepth}",
            )
        }
        val command = axisSession.toCommand(holder, "gtet:test_clock")
        check("松开时才产生命令", command != null)
        dragHistory.execute(command!!)
        check(
            "**连续拖拽合并成一条**：5 帧移动 + 1 次松开 ⇒ 撤销栈里 1 条",
            dragHistory.undoDepth == 1,
            "实际：${dragHistory.undoDepth}",
        )
        check(
            "撤销那一条 ⇒ 回到按下前的值（X=0, Y=1.1）",
            dragHistory.undo().let { holder.x == 0f && near(holder.y.toDouble(), 1.1) },
            "实际：$holder",
        )

        // ── Esc 取消 ──
        val cancelSession = StudioDragSession(
            StudioDragReference.AxisLocked.of(StudioVec3.ZERO, StudioAxis.X, ray(0.0, 0.0, 5.0, 0.0, 0.0, -1.0))!!,
            StudioAnchorOffset(0f, 0f, 0f),
        )
        cancelSession.update(ray(3.0, 0.0, 5.0, 0.0, 0.0, -1.0), StudioSnapRule.QUARTER)
        check("拖起来了（值变了）", cancelSession.moved && near(cancelSession.current.x.toDouble(), 3.0))
        cancelSession.cancel()
        check(
            "Esc 取消 ⇒ 回到按下前的值，且**不产生命令**",
            !cancelSession.moved && cancelSession.toCommand(StudioAnchorOffset(), "x") == null,
            "实际：${cancelSession.current}",
        )

        // ── 平面锁定：两根轴一起动，第三根不动 ──
        val planeRef = StudioDragReference.PlaneLocked.of(
            StudioVec3.ZERO, StudioAxis.Z, ray(0.0, 0.0, 5.0, 0.0, 0.0, -1.0),
        )
        check("按下时能建出平面参考", planeRef != null)
        val planeSession = StudioDragSession(planeRef!!, StudioAnchorOffset(0f, 0f, 0.2f))
        planeSession.update(ray(1.4, -0.6, 5.0, 0.0, 0.0, -1.0), StudioSnapRule.QUARTER)
        val afterPlane = planeSession.current
        check(
            "平面手柄：X/Y 动、**Z 一动不动**（0.2 原样保留）",
            near(afterPlane.x.toDouble(), 1.5) && near(afterPlane.y.toDouble(), -0.5) &&
                near(afterPlane.z.toDouble(), 0.2),
            "实际：$afterPlane",
        )
        check(
            "平面锁定的允许轴是另外两根",
            planeSession.axes.toSet() == setOf(StudioAxis.X, StudioAxis.Y),
            "实际：${planeSession.axes}",
        )

        // ── 退化：射线与轴平行 ⇒ 一帧都不动 ──
        // 按下时用一条**不平行**的射线（沿 -Y 朝 Z 轴打），之后喂一条与 Z 轴平行的射线
        val degenerate = StudioDragSession(
            StudioDragReference.AxisLocked.of(
                StudioVec3.ZERO, StudioAxis.Z, ray(1.0, 5.0, 0.0, 0.0, -1.0, 0.0),
            )!!,
            StudioAnchorOffset(0f, 0f, 0f),
        )
        degenerate.update(ray(0.0, 0.0, -5.0, 0.0, 0.0, -1.0), StudioSnapRule.QUARTER) // 与 Z 轴平行
        check(
            "射线与轴平行 ⇒ 值不变、退化帧计数 +1（不写 NaN）",
            degenerate.degenerateFrames == 1 && near(degenerate.current.z.toDouble(), 0.0),
            "实际：${degenerate.current}，退化 ${degenerate.degenerateFrames}",
        )
        check("退化且没动过 ⇒ 不产生命令", degenerate.toCommand(StudioAnchorOffset(), "x") == null)
    }

    // ────────────────────────── ⑤ 命令栈 ──────────────────────────

    private fun checkHistory() {
        section("命令栈（apply/revert 对称、undo/redo 顺序、容量淘汰最旧）")

        val holder = StudioAnchorOffset(0f, 0f, 0f)
        fun translate(from: Float, to: Float): TranslateAnchorCommand =
            TranslateAnchorCommand(
                holder,
                StudioAnchorOffset(from, 0f, 0f),
                StudioAnchorOffset(to, 0f, 0f),
                "gtet:test_clock",
            )

        val one = translate(0f, 1f)
        one.apply()
        check("apply ⇒ 变成 after", holder.x == 1f, "实际：$holder")
        one.revert()
        check("revert ⇒ 回到 before", holder.x == 0f, "实际：$holder")
        one.apply()
        check("再 apply ⇒ 又是 after（幂等，反复撤销重做不会漂）", holder.x == 1f)
        check(
            "describe() 写清了从多少到多少",
            one.describe().contains("(0, 0, 0) → (1, 0, 0)") && one.describe().contains("test_clock"),
            "实际：${one.describe()}",
        )

        val history = StudioHistory(capacity = 8)
        val c1 = translate(0f, 1f)
        val c2 = translate(1f, 2f)
        history.execute(c1)
        history.execute(c2)
        check("两条命令后栈深 2、值=2", history.undoDepth == 2 && holder.x == 2f, "实际：${history.undoDepth}, $holder")
        check("undo ⇒ c2 被撤销（值回到 1）", history.undo() === c2 && holder.x == 1f, "实际：$holder")
        check("再 undo ⇒ c1 被撤销（值回到 0）", history.undo() === c1 && holder.x == 0f, "实际：$holder")
        check("撤销到空 ⇒ 返回 null（不会抛）", history.undo() == null && !history.canUndo)
        check("redo ⇒ c1（值 1）", history.redo() === c1 && holder.x == 1f, "实际：$holder")
        check("redo ⇒ c2（值 2）", history.redo() === c2 && holder.x == 2f, "实际：$holder")
        check("重做到头 ⇒ 返回 null", history.redo() == null && !history.canRedo)
        check("peek 不改状态", history.peekUndo() === c2 && history.peekRedo() == null && history.undoDepth == 2)

        history.undo()
        check("undo 之后产生新动作 ⇒ redo 栈被清空（标准语义）", run {
            history.execute(translate(1f, 5f))
            !history.canRedo && holder.x == 5f
        }, "实际：redo=${history.redoDepth}, $holder")

        // 容量上限：只留最新的 3 条
        val small = StudioHistory(capacity = 3)
        val smallHolder = StudioAnchorOffset()
        repeat(5) { i ->
            small.execute(
                TranslateAnchorCommand(
                    smallHolder,
                    StudioAnchorOffset(i.toFloat(), 0f, 0f),
                    StudioAnchorOffset((i + 1).toFloat(), 0f, 0f),
                    "cap",
                )
            )
        }
        check(
            "容量 3、推 5 条 ⇒ 只剩 3 条，且淘汰的是**最旧的**",
            small.undoDepth == 3 && small.evicted == 2 && smallHolder.x == 5f,
            "实际：深度 ${small.undoDepth}，淘汰 ${small.evicted}，值 ${smallHolder.x}",
        )
        small.undo()
        small.undo()
        small.undo()
        check(
            "撤到底 ⇒ 只能退到第 3 条之前（值 2），最旧的两条确实没了",
            smallHolder.x == 2f && !small.canUndo,
            "实际：${smallHolder.x}",
        )

        val noopHistory = StudioHistory()
        val noopHolder = StudioAnchorOffset(1f, 2f, 3f)
        noopHistory.execute(TranslateAnchorCommand(noopHolder, StudioAnchorOffset(1f, 2f, 3f), StudioAnchorOffset(1f, 2f, 3f), "x"))
        check("值没变的命令也照样对称（真实链路由 StudioEditor.commit 拦掉）", noopHolder.equalsValue(StudioAnchorOffset(1f, 2f, 3f)))
    }

    // ────────────────────────── ⑥ 文本级写回 ──────────────────────────

    private fun checkJsonWriteBack() {
        section("文本级 JSON 写回（★ 注释与其余字段一字不差；结构不对一律拒绝）")

        // 一份缩微版 clock.json：注释、别的字段、数组、嵌套对象都有
        val original = """
            // 顶部说明：这份文件是给人读的，注释绝不能被写没
            {
              "version": 1,
              "id": "gtet:test_clock",   // 行尾注释
              "source": {
                // 磁盘来源（主推）
                "format": "obj",
                "model": "gtetcore:models/obj/clock.obj",
                "flip_v": true
              },
              "parts": [ { "name": "body" }, { "name": "hand_hour" } ],
              /* 块注释：anchor 的说明
                 "offset": [9, 9, 9]   ← 注释里的东西不算数 */
              "anchor": {
                "mode": "machine",
                "face": "front",
                "offset": [0, 1, 0],
                "scale": 0.5
              },
              "animation": { "driver": "gameTime", "tracks": [] }
            }
        """.trimIndent() + "\n"

        val commentLines = original.lines().filter { it.contains("//") || it.contains("/*") || it.contains("←") }

        val updated = try {
            StudioAnchorWriter.replaceOffset(original, 0f, 2.5f, -0.25f)
        } catch (e: Exception) {
            fail("正常输入却抛了异常：$e")
            return
        }

        check(
            "只改了 offset 那一行：新文本与原文仅在这一处不同",
            updated.lineSequence().count() == original.lineSequence().count() &&
                updated.lines().filter { it.contains("//") || it.contains("/*") || it.contains("←") } == commentLines,
            "注释行数：原文 ${commentLines.size} / 新文 ${updated.lines().count { it.contains("//") || it.contains("/*") }}",
        )
        check(
            "写进去的值格式干净：[0, 2.5, -0.25]（整数不带小数点、不留尾零）",
            updated.contains("\"offset\": [0, 2.5, -0.25]"),
            "实际那一行：" + updated.lines().firstOrNull { it.contains("offset") },
        )

        // 强断言：除 anchor.offset 外，**其余字段一字不差**
        val beforeJson = JsonParser.parseString(stripJsonComments(original)).asJsonObject
        val afterJson = JsonParser.parseString(stripJsonComments(updated)).asJsonObject
        val beforeCopy = beforeJson.deepCopy()
        val afterCopy = afterJson.deepCopy()
        beforeCopy.getAsJsonObject("anchor").remove("offset")
        afterCopy.getAsJsonObject("anchor").remove("offset")
        check(
            "除 anchor.offset 外其余 JSON 结构完全一致（深度比较）",
            beforeCopy == afterCopy,
            "原文：$beforeCopy\n新文：$afterCopy",
        )
        check(
            "anchor 段其它字段原样（mode/face/scale）",
            afterJson.getAsJsonObject("anchor").get("scale").asFloat == 0.5f &&
                afterJson.getAsJsonObject("anchor").get("face").asString == "front",
        )
        check(
            "写回后的文本仍然能解析，且 offset 正好是写的值",
            run {
                val arr = afterJson.getAsJsonObject("anchor").getAsJsonArray("offset")
                arr.size() == 3 && arr[0].asFloat == 0f && arr[1].asFloat == 2.5f && arr[2].asFloat == -0.25f
            },
        )
        check(
            "注释里的 \"offset\": [9, 9, 9] 没被误改（扫描器跳过了注释）",
            updated.contains("\"offset\": [9, 9, 9]"),
        )
        check(
            "注释行原文一字不差地还在",
            commentLines.all { updated.contains(it) },
        )

        // ── 结构不对 ⇒ 拒绝改写（抛异常，调用方原样保留文件）──
        expectRefuse("根节点不是对象", "[1, 2, 3]")
        expectRefuse("没有 anchor 段", """{ "id": "gtet:x", "version": 1 }""")
        expectRefuse("anchor 不是对象", """{ "anchor": [0, 1, 0] }""")
        expectRefuse("offset 不是数组", """{ "anchor": { "offset": "0,1,0" } }""")
        expectRefuse("括号不配对", """{ "anchor": { "offset": [0, 1, 0""")
        expectRefuse(
            "offset 数组里带注释（直接替换会吃掉注释）",
            """
            {
              "anchor": {
                "offset": [
                  0, // x
                  1, // y
                  0  // z
                ]
              }
            }
            """.trimIndent(),
        )

        // 被拒绝时必须**没有返回任何文本**（调用方拿不到新文本 ⇒ 不可能写盘）
        val broken = """{ "anchor": "nope" }"""
        val refusal = try {
            StudioAnchorWriter.replaceOffset(broken, 1f, 1f, 1f)
            null
        } catch (e: StudioAnchorWriteException) {
            e
        }
        check(
            "拒绝路径抛的是 StudioAnchorWriteException（调用方能区分「数据不对」与「IO 炸了」），且消息非空",
            refusal != null && !refusal.message.isNullOrBlank(),
            "实际：${refusal?.message}",
        )

        // ── offset 缺失 ⇒ 插入（带缩进），插入后仍合法 ──
        val noOffset = """
            {
              "id": "gtet:x",
              // anchor 段没有 offset
              "anchor": {
                "mode": "machine",
                "scale": 0.5
              }
            }
        """.trimIndent() + "\n"
        val inserted = try {
            StudioAnchorWriter.replaceOffset(noOffset, 0f, 1.25f, 0f)
        } catch (e: Exception) {
            fail("offset 缺失时插入失败：$e")
            return
        }
        val insertedJson = JsonParser.parseString(stripJsonComments(inserted)).asJsonObject
        check(
            "offset 缺失 ⇒ 插入一行，值正确、缩进与同级一致、能解析",
            insertedJson.getAsJsonObject("anchor").getAsJsonArray("offset")[1].asFloat == 1.25f &&
                inserted.contains("    \"offset\": [0, 1.25, 0],"),
            "实际：\n$inserted",
        )
        check(
            "插入后 anchor 的其它字段没动（也没有重复键）",
            insertedJson.getAsJsonObject("anchor").get("scale").asFloat == 0.5f &&
                inserted.split("\"offset\"").size == 2,
        )
        check("插入路径同样保留注释", inserted.contains("// anchor 段没有 offset"))

        // ── 空 anchor 对象 ──
        val emptyAnchor = "{ \"anchor\": {} }"
        val filled = try {
            StudioAnchorWriter.replaceOffset(emptyAnchor, 0.25f, 0f, 0f)
        } catch (e: Exception) {
            fail("空 anchor 对象插入失败：$e")
            return
        }
        check(
            "空 anchor 对象 ⇒ 变成 { offset: [...] } 且合法（不会有尾逗号）",
            JsonParser.parseString(filled).asJsonObject.getAsJsonObject("anchor")
                .getAsJsonArray("offset")[0].asFloat == 0.25f,
            "实际：$filled",
        )

        // ── 数字格式 ──
        check(
            "数字写法：1 → \"1\"、0.25 → \"0.25\"、-0.0 → \"0\"",
            StudioAnchorWriter.number(1f) == "1" && StudioAnchorWriter.number(0.25f) == "0.25" &&
                StudioAnchorWriter.number(-0f) == "0",
            "实际：${StudioAnchorWriter.number(1f)} / ${StudioAnchorWriter.number(0.25f)} / ${StudioAnchorWriter.number(-0f)}",
        )
        check(
            "StudioAnchorOffset.format() 与反馈里的写法一致",
            StudioAnchorOffset(0f, 1f, -0.25f).format() == "(0, 1, -0.25)",
            "实际：${StudioAnchorOffset(0f, 1f, -0.25f).format()}",
        )
    }

    // ────────────────────────── ⑦ M2b 编辑器输入状态机 ──────────────────────────

    /**
     * **M2b 的输入 → 状态机**（`editor/StudioEditorInput`，纯函数，不碰 MC）。
     *
     * M2b 之后输入由 `StudioEditorScreen` 独占，屏幕拿着"按下的是哪个键、有没有命中手柄、
     * 是不是正在拖"去问这套判决，然后照着做。判决错了的表现全都是实机才看得见的
     * （右键误起拖拽、Esc 退出编辑丢了这次拖拽、裸按 Z 把改动撤了……），所以这里逐条钉死。
     */
    private fun checkEditorInput() {
        section("M2b 编辑器输入状态机（按下了什么 / 是否起拖 / Esc 该取消还是该退出）")

        // ── 鼠标键分桶：屏幕那边用的是 GLFW 常量，纯逻辑这边不 import GLFW，
        //    两边的口径必须严丝合缝，不然"右键"可能被当成"左键"
        check(
            "GLFW 0/1/2/8 → LEFT/RIGHT/OTHER/OTHER（0 与 1 就是 GLFW 的 ABI 常量）",
            StudioMouseButton.of(0) == StudioMouseButton.LEFT &&
                StudioMouseButton.of(1) == StudioMouseButton.RIGHT &&
                StudioMouseButton.of(2) == StudioMouseButton.OTHER &&
                StudioMouseButton.of(8) == StudioMouseButton.OTHER,
        )

        // ── 左键按下 ──
        check(
            "左键按下 + 命中手柄 + 还没在拖 ⇒ **起拖**",
            StudioEditorInput.onPress(StudioMouseButton.LEFT, hitHandle = true, dragging = false) ==
                StudioEditorAction.BEGIN_DRAG,
        )
        check(
            "左键按下但**没命中手柄** ⇒ 什么都不做（不许误起一次拖拽）",
            StudioEditorInput.onPress(StudioMouseButton.LEFT, hitHandle = false, dragging = false) ==
                StudioEditorAction.NONE,
        )
        check(
            "已经在拖了再按左键 ⇒ 忽略（参考系是按下那一刻的快照，重开一次就会漂）",
            StudioEditorInput.onPress(StudioMouseButton.LEFT, hitHandle = true, dragging = true) ==
                StudioEditorAction.NONE,
        )
        check(
            "右键 / 中键按下 ⇒ 永不产生编辑器动作（转视角是屏幕自己的相机逻辑）",
            StudioEditorInput.onPress(StudioMouseButton.RIGHT, hitHandle = true, dragging = false) ==
                StudioEditorAction.NONE &&
                StudioEditorInput.onPress(StudioMouseButton.OTHER, hitHandle = true, dragging = false) ==
                StudioEditorAction.NONE,
        )

        // ── 鼠标松开 ──
        check(
            "左键松开且确实在拖 ⇒ 收尾（**松开这一刻**才产生一条命令）",
            StudioEditorInput.onRelease(StudioMouseButton.LEFT, dragging = true) == StudioEditorAction.END_DRAG,
        )
        check(
            "左键松开但没在拖 ⇒ 什么都不做",
            StudioEditorInput.onRelease(StudioMouseButton.LEFT, dragging = false) == StudioEditorAction.NONE,
        )
        check(
            "右键松开 ⇒ 不碰 gizmo 拖拽",
            StudioEditorInput.onRelease(StudioMouseButton.RIGHT, dragging = true) == StudioEditorAction.NONE,
        )

        // ── Esc：M2b §3 的核心那一条 ──
        check(
            "Esc 且**正在拖** ⇒ 取消这次拖拽（不退出编辑）",
            StudioEditorInput.onEscape(dragging = true) == StudioEditorAction.CANCEL_DRAG,
        )
        check(
            "Esc 且**没在拖** ⇒ 退出编辑器",
            StudioEditorInput.onEscape(dragging = false) == StudioEditorAction.EXIT_EDITOR,
        )
        check(
            "onKey(ESCAPE) 与 onEscape 是**同一条**判决（两条入口不许各说各话）",
            StudioEditorInput.onKey(StudioEditorKey.ESCAPE, ctrl = false, shift = false, dragging = true) ==
                StudioEditorAction.CANCEL_DRAG &&
                StudioEditorInput.onKey(StudioEditorKey.ESCAPE, ctrl = true, shift = true, dragging = false) ==
                StudioEditorAction.EXIT_EDITOR,
        )

        // ── 撤销 / 重做 ──
        check(
            "Ctrl+Z ⇒ 撤销",
            StudioEditorInput.onKey(StudioEditorKey.UNDO_KEY, ctrl = true, shift = false, dragging = false) ==
                StudioEditorAction.UNDO,
        )
        check(
            "**裸按 Z** ⇒ 什么都不做（撤销必须带 Ctrl，否则打字/快捷键会误撤销）",
            StudioEditorInput.onKey(StudioEditorKey.UNDO_KEY, ctrl = false, shift = false, dragging = false) ==
                StudioEditorAction.NONE,
        )
        check(
            "Ctrl+Shift+Z ⇒ 重做（很多人习惯的那一套）",
            StudioEditorInput.onKey(StudioEditorKey.UNDO_KEY, ctrl = true, shift = true, dragging = false) ==
                StudioEditorAction.REDO,
        )
        check(
            "Ctrl+Y ⇒ 重做；裸按 Y ⇒ 什么都不做",
            StudioEditorInput.onKey(StudioEditorKey.REDO_KEY, ctrl = true, shift = false, dragging = false) ==
                StudioEditorAction.REDO &&
                StudioEditorInput.onKey(StudioEditorKey.REDO_KEY, ctrl = false, shift = false, dragging = false) ==
                StudioEditorAction.NONE,
        )
        check(
            "别的键（含 WASD/空格，它们被屏幕吃掉只用于飞行）⇒ 判决层什么都不做",
            StudioEditorInput.onKey(StudioEditorKey.OTHER, ctrl = true, shift = true, dragging = true) ==
                StudioEditorAction.NONE,
        )

        // ── 滚轮调速 ──
        check(
            "滚轮上滚变快、下滚变慢（乘性步长）",
            StudioEditorInput.scrollSpeed(0.6, 1.0) > 0.6 && StudioEditorInput.scrollSpeed(0.6, -1.0) < 0.6,
            "实际：${StudioEditorInput.scrollSpeed(0.6, 1.0)} / ${StudioEditorInput.scrollSpeed(0.6, -1.0)}",
        )
        check(
            "滚一格 = ×${StudioEditorInput.SPEED_STEP}（0.6 → 0.75）",
            near(StudioEditorInput.scrollSpeed(0.6, 1.0), 0.75),
            "实际：${StudioEditorInput.scrollSpeed(0.6, 1.0)}",
        )
        check(
            "速度夹在 [${StudioEditorInput.MIN_SPEED}, ${StudioEditorInput.MAX_SPEED}] 内（滚到底不会飞没影）",
            StudioEditorInput.scrollSpeed(0.6, 100.0) == StudioEditorInput.MAX_SPEED &&
                StudioEditorInput.scrollSpeed(0.6, -100.0) == StudioEditorInput.MIN_SPEED,
        )
        check(
            "非有限输入原样返回（NaN 一旦混进速度，飞行会永久卡死 —— 这条是防那个的）",
            StudioEditorInput.scrollSpeed(0.6, Double.NaN) == 0.6 &&
                StudioEditorInput.scrollSpeed(0.6, Double.POSITIVE_INFINITY) == 0.6 &&
                StudioEditorInput.scrollSpeed(Double.NaN, 1.0).isNaN(),
        )
        check(
            "默认速度落在范围内",
            StudioEditorInput.DEFAULT_SPEED >= StudioEditorInput.MIN_SPEED &&
                StudioEditorInput.DEFAULT_SPEED <= StudioEditorInput.MAX_SPEED,
        )

        // ── ⑦ 与 ④ 的接缝：状态机判"取消"，落到真实会话上到底做了什么 ──
        //    （判决与执行是两处代码，中间断开就会"按了 Esc 值却回不去"）
        val start = StudioAnchorOffset(0f, 0f, 0f)
        val holder = start.copy()
        val session = StudioDragSession(
            StudioDragReference.AxisLocked.of(
                StudioVec3.ZERO, StudioAxis.X, ray(0.0, 0.0, 5.0, 0.0, 0.0, -1.0),
            )!!,
            start,
        )
        session.update(ray(2.0, 0.0, 5.0, 0.0, 0.0, -1.0), StudioSnapRule.QUARTER)
        holder.setFrom(session.current)
        val verdict = StudioEditorInput.onKey(
            StudioEditorKey.ESCAPE, ctrl = false, shift = false, dragging = true,
        )
        check(
            "拖到一半按 Esc：状态机判 CANCEL_DRAG，此刻值确实已经动了（X=2）",
            verdict == StudioEditorAction.CANCEL_DRAG && near(holder.x.toDouble(), 2.0),
            "实际：$verdict / $holder",
        )
        session.cancel()
        holder.setFrom(session.current)
        check(
            "照着判决执行 ⇒ 回到按下前的值 (0,0,0)，且**一条命令都不产生**（撤销栈干净）",
            holder.x == 0f && near(holder.y.toDouble(), 0.0) && near(holder.z.toDouble(), 0.0) &&
                session.toCommand(holder, "gtet:test_clock") == null,
            "实际：$holder",
        )
    }

    /** 断言"这种输入必须被拒绝"。 */
    private fun expectRefuse(what: String, text: String) {
        val threw = try {
            StudioAnchorWriter.replaceOffset(text, 1f, 2f, 3f)
            false
        } catch (_: StudioAnchorWriteException) {
            true
        } catch (e: Exception) {
            fail("$what：抛的是 ${e.javaClass.simpleName} 而不是 StudioAnchorWriteException")
            return
        }
        check("拒绝改写：$what", threw)
    }

    // ────────────────────────── 工具 ──────────────────────────

    private fun vec(x: Double, y: Double, z: Double): StudioVec3 = StudioVec3(x, y, z)

    // ────────────────────────── ⑧ 屏幕空间拾取 ──────────────────────────

    /**
     * **屏幕空间拾取**（`StudioScreenPicker`，纯数学）。
     *
     * 这节钉的是"**到底能不能戳中轴**"：M2a 用世界空间半径判命中，折算到屏幕上只有三四个像素，
     * 实机表现就是"三色轴拖不动"。改成按像素判之后，命中宽度与相机距离、斜看角度无关，
     * 所以要验的是"像素阈值生效、轴优先于平面、退化线段不炸"。
     */
    private fun checkScreenPick() {
        section("屏幕空间拾取（像素阈值 / 轴优先于平面 / 退化线段）")

        val origin = StudioScreenPoint(100.0, 100.0)
        val axisSegment = StudioScreenPoint(100.0, 100.0) to StudioScreenPoint(100.0, 40.0) // 竖直 60px
        val planeCenter = StudioScreenPoint(140.0, 60.0)
        val plane = StudioHandle.ofPlane(StudioAxis.Z)

        val geometry = StudioScreenGeometry(
            axisSegments = mapOf(StudioAxis.Y to axisSegment),
            planeCenters = mapOf(plane to planeCenter),
        )

        check(
            "点在轴上（垂直距离 0）",
            StudioScreenPicker.distanceToSegment(origin, axisSegment.first, axisSegment.second) == 0.0,
        )
        check(
            "点在轴旁 5px ⇒ 距离 5",
            abs(StudioScreenPicker.distanceToSegment(StudioScreenPoint(105.0, 70.0), axisSegment.first, axisSegment.second) - 5.0) < 1e-9,
        )
        check(
            "超出端点的延长线上 ⇒ 取到端点的距离 20（是线段不是直线）",
            abs(StudioScreenPicker.distanceToSegment(StudioScreenPoint(100.0, 120.0), axisSegment.first, axisSegment.second) - 20.0) < 1e-9,
        )
        check(
            "退化线段（两端重合）等价于点距，不产生 NaN",
            StudioScreenPicker.distanceToSegment(StudioScreenPoint(103.0, 104.0), origin, origin) == 5.0,
        )

        check(
            "缩放 1：离轴 5px ⇒ 命中轴 Y",
            StudioScreenPicker.pick(geometry, StudioScreenPoint(105.0, 70.0), 1.0) == StudioHandle.Axis(StudioAxis.Y),
        )
        check(
            "缩放 1：离轴 7px ⇒ 不命中（阈值 6 GUI 像素）",
            StudioScreenPicker.pick(geometry, StudioScreenPoint(107.0, 70.0), 1.0) == null,
        )
        check(
            "缩放 2：同样 7px ⇒ 命中（阈值随缩放放大到 12 窗口像素）",
            StudioScreenPicker.pick(geometry, StudioScreenPoint(107.0, 70.0), 2.0) == StudioHandle.Axis(StudioAxis.Y),
        )
        check(
            "光标压在平面手柄中心 ⇒ 命中该平面",
            StudioScreenPicker.pick(geometry, planeCenter, 1.0) == plane,
        )
        check(
            "两者都够得到时**轴优先**",
            StudioScreenPicker.pick(
                StudioScreenGeometry(
                    axisSegments = mapOf(StudioAxis.Y to axisSegment),
                    planeCenters = mapOf(plane to StudioScreenPoint(102.0, 70.0)),
                ),
                StudioScreenPoint(102.0, 70.0),
                1.0,
            ) == StudioHandle.Axis(StudioAxis.Y),
        )
        check(
            "什么都够不到 ⇒ null（调用方退回世界空间射线）",
            StudioScreenPicker.pick(geometry, StudioScreenPoint(500.0, 500.0), 1.0) == null,
        )
    }

    // ────────────────────────── ⑨ M2c 坐标输入框 ──────────────────────────

    /**
     * **M2c 的坐标输入框**（`editor/StudioEditorInput` 那两个纯函数）。
     *
     * 两类东西必须机器可验 —— 它们在实机上的坏法都很隐蔽：
     * 1. **文本解析**：用户敲进来的任何东西都必须有确定结果。能解析就给三个数；
     *    `NaN` / 无穷 / `1e999`（溢出成无穷）绝不能溜进 `anchor.offset`（那会同时弄坏
     *    渲染矩阵与写回 JSON）；而"敲错一个字母"更不能抛异常把界面打崩；
     * 2. **按键归属**：输入框开着时 `W/A/S/D`、`Ctrl+Z` **一个都不许**产生编辑器动作 ——
     *    漏一条的实机表现就是"一边打字一边把玩家开走"或"打字打到一半把位移撤了"；
     *    而 `Esc` 必须只是**关掉输入框**（既不是取消拖拽，也不是退出编辑）。
     */
    private fun checkInputBox() {
        section("M2c 坐标输入框（文本解析 / 按键归属 / Esc 只关输入框）")

        // ── ① 该解析成功的 ──
        checkParsed("空格分隔「1 2 3」", "1 2 3", 1f, 2f, 3f)
        checkParsed("逗号分隔", "1,2,3", 1f, 2f, 3f)
        checkParsed("逗号 + 空格混着写、两头带空白", " 1, 2 ,3 ", 1f, 2f, 3f)
        checkParsed("小数与负号", "-0.25 1.5 0", -0.25f, 1.5f, 0f)
        checkParsed("多位小数（0.25 网格以外的值也照收，拖出来的值本来就是这种）", "0.125 -1 -0.0625", 0.125f, -1f, -0.0625f)

        // ── ② 该被拒绝的：每一串都必须给出**理由**，且不许抛异常 ──
        checkRefused("少一个数", "1 2")
        checkRefused("多一个数", "1 2 3 4")
        checkRefused("根本不是数字", "a b c")
        checkRefused("空串", "")
        checkRefused("只有空白", "   ")
        checkRefused("混了一个 NaN", "1 2 NaN")
        checkRefused("溢出成无穷（1e999）", "1e999 0 0")
        checkRefused("无穷大", "-Infinity 0 0")
        checkRefused("冒号分隔（很多人会这么敲）", "1:2:3")

        // "1 2" 这种要给出**收到了几个**，用户才知道自己少了什么
        val short = StudioEditorInput.parseVec3("1 2")
        check(
            "少一个数时理由里带「收到 2 个」",
            short is StudioVectorParse.Bad && short.reason.contains("2"),
            "实际：$short",
        )

        // ── ③ 按键归属：输入框开着时，键盘只归输入框 ──
        check(
            "输入框开着：Esc ⇒ 只关输入框（**不是**取消拖拽、**不是**退出编辑）",
            StudioEditorInput.onKeyTarget(StudioEditorKey.ESCAPE, boxOpen = true) == StudioKeyTarget.CLOSE_INPUT,
            "实际：${StudioEditorInput.onKeyTarget(StudioEditorKey.ESCAPE, boxOpen = true)}",
        )
        check(
            "输入框关着：Esc 仍旧归编辑器（拖拽中 = 取消拖拽，否则 = 退出编辑，与 M2b 一字不差）",
            StudioEditorInput.onKeyTarget(StudioEditorKey.ESCAPE, boxOpen = false) == StudioKeyTarget.EDITOR &&
                StudioEditorInput.onKey(StudioEditorKey.ESCAPE, ctrl = false, shift = false, dragging = true) ==
                StudioEditorAction.CANCEL_DRAG &&
                StudioEditorInput.onKey(StudioEditorKey.ESCAPE, ctrl = false, shift = false, dragging = false) ==
                StudioEditorAction.EXIT_EDITOR,
        )
        check(
            "★ 「Esc 只关输入框」的硬证据：同一时刻（正在拖拽）两条路的归属不同 —— 开着时判给输入框，关着时才是取消拖拽",
            StudioEditorInput.onKeyTarget(StudioEditorKey.ESCAPE, boxOpen = true) != StudioKeyTarget.EDITOR &&
                StudioEditorInput.onKeyTarget(StudioEditorKey.ESCAPE, boxOpen = false) == StudioKeyTarget.EDITOR,
        )
        check(
            "输入框开着：Ctrl+Z / Ctrl+Y 判给输入框（对照：关着时它们真的撤销 / 重做）",
            StudioEditorInput.onKeyTarget(StudioEditorKey.UNDO_KEY, boxOpen = true) == StudioKeyTarget.INPUT_BOX &&
                StudioEditorInput.onKeyTarget(StudioEditorKey.REDO_KEY, boxOpen = true) == StudioKeyTarget.INPUT_BOX &&
                StudioEditorInput.onKey(StudioEditorKey.UNDO_KEY, ctrl = true, shift = false, dragging = false) ==
                StudioEditorAction.UNDO &&
                StudioEditorInput.onKey(StudioEditorKey.REDO_KEY, ctrl = true, shift = false, dragging = false) ==
                StudioEditorAction.REDO,
        )
        check(
            "输入框开着：W/A/S/D（判不出来一律 OTHER）判给输入框 ⇒ 不会变成飞行",
            StudioEditorInput.onKeyTarget(StudioEditorKey.OTHER, boxOpen = true) == StudioKeyTarget.INPUT_BOX,
        )
        check(
            "输入框**关着**时 OTHER 仍旧归编辑器（飞行轮询照常，不被本次改动误伤）",
            StudioEditorInput.onKeyTarget(StudioEditorKey.OTHER, boxOpen = false) == StudioKeyTarget.EDITOR,
        )
        check(
            "输入框开着：Tab ⇒ 收起；关着 ⇒ 归编辑器（屏幕那一步才把它变成「调出」）",
            StudioEditorInput.onKeyTarget(StudioEditorKey.TAB, boxOpen = true) == StudioKeyTarget.CLOSE_INPUT &&
                StudioEditorInput.onKeyTarget(StudioEditorKey.TAB, boxOpen = false) == StudioKeyTarget.EDITOR,
        )
        check(
            "输入框开着：回车 ⇒ 写入 anchor.offset",
            StudioEditorInput.onKeyTarget(StudioEditorKey.ENTER, boxOpen = true) == StudioKeyTarget.APPLY_INPUT,
        )
        check(
            "Tab / 回车本身**不是**编辑器动作（关着时也不会误触发撤销之类）",
            StudioEditorInput.onKey(StudioEditorKey.TAB, ctrl = false, shift = false, dragging = false) ==
                StudioEditorAction.NONE &&
                StudioEditorInput.onKey(StudioEditorKey.ENTER, ctrl = true, shift = true, dragging = true) ==
                StudioEditorAction.NONE,
        )

        // ── ④ 动作反馈行的格式（用户点名要的那一行，逐字钉住）──
        val line = StudioNumberFormat.stepLine(
            "gtet:test_clock",
            StudioAnchorOffset(0f, 1f, 0f),
            StudioAnchorOffset(0f, 1.25f, 0f),
        )
        check(
            "★ 反馈行逐字等于用户要的样子：gtet:test_clock (0.00, 1.00, 0.00) -> (0.00, 1.25, 0.00)",
            line == "gtet:test_clock (0.00, 1.00, 0.00) -> (0.00, 1.25, 0.00)",
            "实际：$line",
        )
        check(
            "反馈行以**模型 id 开头**（尾部的「已记录一步」之类都在这个后面接）",
            line.startsWith("gtet:test_clock "),
        )
        check(
            "箭头是 ASCII 的 `->`（不是 Unicode 的 →），数字**固定两位**（整数也要 .00）",
            line.contains(" -> ") && !line.contains("→") &&
                StudioNumberFormat.fixed(1f) == "1.00" &&
                StudioNumberFormat.fixed(1.25f) == "1.25" &&
                StudioNumberFormat.fixed(0f) == "0.00",
            "实际：${StudioNumberFormat.fixed(1f)} / ${StudioNumberFormat.fixed(1.25f)}",
        )
        check(
            "负零不写成 -0.00（面板上别冒出一个看着像 bug 的负号）",
            StudioNumberFormat.fixed(-0f) == "0.00",
            "实际：${StudioNumberFormat.fixed(-0f)}",
        )

        // ── ⑤ 命令框那一行交给谁（一个框同时认命令和坐标）──
        check(
            "三个数 ⇒ 当坐标（走写 anchor.offset 那条路）",
            StudioEditorInput.classifyCommandLine("1 2 3") == StudioCommandLine.Offset(1f, 2f, 3f),
            "实际：${StudioEditorInput.classifyCommandLine("1 2 3")}",
        )
        check(
            "命令 ⇒ 当命令，前后空白去掉；前导 `/` 也去掉（`/gtetstudio save` 与 `gtetstudio save` 同一件事）",
            StudioEditorInput.classifyCommandLine("  save  ") == StudioCommandLine.Command("save") &&
                StudioEditorInput.classifyCommandLine("/gtetstudio save") == StudioCommandLine.Command("gtetstudio save") &&
                StudioEditorInput.classifyCommandLine("gtetstudio edit off") ==
                StudioCommandLine.Command("gtetstudio edit off"),
        )
        check(
            "空行 / 只有空白 / 只有一个斜杠 ⇒ Empty（不报错、也不收框）",
            StudioEditorInput.classifyCommandLine("") == StudioCommandLine.Empty &&
                StudioEditorInput.classifyCommandLine("   ") == StudioCommandLine.Empty &&
                StudioEditorInput.classifyCommandLine("/") == StudioCommandLine.Empty,
        )
        check(
            "★ 看着像坐标但解析不出来 ⇒ 报**坐标**的原因（不是「不认识的命令」，那样对用户没用）",
            StudioEditorInput.classifyCommandLine("1 2").let { it is StudioCommandLine.BadOffset && it.reason.contains("2") } &&
                StudioEditorInput.classifyCommandLine("1 2 NaN") is StudioCommandLine.BadOffset &&
                StudioEditorInput.classifyCommandLine("1e999 0 0") is StudioCommandLine.BadOffset &&
                StudioEditorInput.classifyCommandLine("1 2 3 4") is StudioCommandLine.BadOffset &&
                StudioEditorInput.classifyCommandLine("5") is StudioCommandLine.BadOffset,
            "实际：${StudioEditorInput.classifyCommandLine("1 2")} / " +
                "${StudioEditorInput.classifyCommandLine("1 2 NaN")} / ${StudioEditorInput.classifyCommandLine("5")}",
        )
        check(
            "★ 反过来：命令名**不许**被坐标判据吞掉（`save` 全是字母，仍然走命令）",
            StudioEditorInput.classifyCommandLine("save") == StudioCommandLine.Command("save") &&
                StudioEditorInput.classifyCommandLine("1 2 3 4 save") == StudioCommandLine.Command("1 2 3 4 save"),
        )
        check(
            "单词命令（save / undo / redo / edit）与多词命令都原样交给命令表",
            StudioEditorInput.classifyCommandLine("undo") == StudioCommandLine.Command("undo") &&
                StudioEditorInput.classifyCommandLine("edit") == StudioCommandLine.Command("edit"),
        )
    }

    // ────────────────────────── ⑩ M2d 详情框（左上角 + 可收起） ──────────────────────────

    /**
     * **M2d 的详情框**（`editor/StudioEditorLayout` + `StudioDetailsState`）。
     *
     * 用户实机原话是"太挡视野 ⇒ 详细框搬到左上角 + 可收起"。这一节钉的是那句话里
     * **可以机器判**的部分：
     * - 面板几何**不越过屏幕中线**（"挡视野"的可度量那一半）；
     * - 预留区恰好包住 `EditBox`（"一处算、三处用"的结构保证，算两遍就会漂）；
     * - 点标题行 = 切收起，且这一下**不产生编辑器动作**；
     * - 收起态命令框的归属（连带收起 / 按 Tab 先展开 / 原来那一格不再吃点击）；
     * - 长「模型」行的收窄（紧凑写法 + 按宽度截断补「…」）。
     *
     * 剩下"收起后是不是真的不挡视野""点标题行手感对不对"只有用户实机能判 —— 那部分不在这里。
     */
    private fun checkEditorPanel() {
        section("M2d 详情框（左上角几何 / 点击归属 / 收起状态机 / 长文本收窄）")

        // 自检用的**假字体**：每个字符 6 像素（与 GUI 里 ASCII 字宽同量级，且完全确定 ⇒ 不依赖起游戏）
        val fakeFont: (String) -> Int = { it.length * 6 }

        // ── ① 几何：常见 GUI 尺寸下都不许越过屏幕中线 ──
        //     （用户投诉的"挡视野"里**可度量**的那一半就是它）
        val sizes = listOf(640 to 360, 960 to 540, 480 to 270, 320 to 180, 1920 to 1000)
        for ((w, h) in sizes) {
            val l = StudioEditorLayout.of(w, h, collapsed = false)
            check(
                "★ ${w}×${h}：展开态面板右边缘 ${l.cardRight} **不越过屏幕中线** ${l.midline}",
                l.cardRight <= l.midline,
                "实际：cardRight=${l.cardRight} midline=${l.midline}",
            )
            check(
                "${w}×${h}：EditBox 那一格**完全落在预留区里**（预留区画在哪、框就摆在哪）",
                l.boxX >= l.frameX && l.boxY >= l.frameY &&
                    l.boxX + l.boxW <= l.frameX + l.frameW &&
                    l.boxY + l.boxH <= l.frameY + l.frameH,
                "实际：box=(${l.boxX},${l.boxY},${l.boxW}×${l.boxH}) frame=(${l.frameX},${l.frameY},${l.frameW}×${l.frameH})",
            )
            check(
                "${w}×${h}：详情框**在动作反馈行下面**（不许压住顶栏 / 反馈行）",
                l.cardY >= PANEL_TOP_BAR_H + PANEL_FEEDBACK_H && l.frameY >= l.cardY + PANEL_CARD_H,
            )
        }
        check(
            "窗口窄到离谱（200 宽）时按可读下限 [PANEL_MIN_CARD_W] 处理 —— 明知会压中线，但总比看不见字强",
            StudioEditorLayout.of(200, 200, collapsed = false).cardW == PANEL_MIN_CARD_W,
            "实际：${StudioEditorLayout.of(200, 200, collapsed = false).cardW}",
        )

        val base = StudioEditorLayout.of(640, 360, collapsed = false)

        // ── ② 左上角版式：起点、顺序、与预留区的接缝 ──
        check(
            "详情框钉在**左上角**：左边距 ${base.cardX} = PANEL_PAD，顶边 ${base.cardY} 紧跟反馈行下沿",
            base.cardX == PANEL_PAD && base.cardY == PANEL_TOP_BAR_H + PANEL_FEEDBACK_H + 4,
            "实际：(${base.cardX}, ${base.cardY})",
        )
        check(
            "命令框预留区**紧跟卡片正下方**（它俩本来是一套，搬家也得一起走）",
            base.frameY == base.cardY + PANEL_CARD_H + PANEL_FRAME_GAP && base.frameX == base.cardX,
            "实际：frameY=${base.frameY} cardY=${base.cardY}",
        )
        check(
            "六行信息 + 标题行的高度是**常量**（${PANEL_CARD_H}，与文字宽度无关 ⇒ init 里也能算）",
            base.cardH == PANEL_CARD_H && base.cardH == 86,
            "实际：${base.cardH}",
        )

        // ── ③ 点击归属：标题行 / 预留区 / 别人 ──
        val titleX = (base.cardX + base.cardW / 2).toDouble()
        val titleY = (base.cardY + 2).toDouble()
        check(
            "★ 点标题行 ⇒ 切换收起（[StudioPanelClick.TITLE]；屏幕照判决非 NONE 一律吃掉，" +
                "所以这一下**不会漏成一次拖轴**）",
            StudioEditorLayout.onPanelClick(titleX, titleY, base) == StudioPanelClick.TITLE,
            "实际：${StudioEditorLayout.onPanelClick(titleX, titleY, base)}",
        )
        check(
            "点卡片里的信息行（标题行下方）⇒ 面板不管，照常交给编辑器",
            StudioEditorLayout.onPanelClick(titleX, (base.cardY + base.cardH - 4).toDouble(), base) ==
                StudioPanelClick.NONE,
        )
        check(
            "点命令框预留区 ⇒ 调出 / 收起命令框",
            StudioEditorLayout.onPanelClick(
                (base.frameX + 4).toDouble(),
                (base.frameY + base.frameH - 4).toDouble(),
                base,
            ) == StudioPanelClick.COMMAND_SLOT,
        )
        check(
            "点屏幕中央 / 右侧 ⇒ 面板不管（编辑器照常拖轴、右键转视角）",
            StudioEditorLayout.onPanelClick(base.midline.toDouble(), 200.0, base) == StudioPanelClick.NONE,
        )

        // ── ④ 收起态：只有一行；原来预留区那一格不再吃点击；命令框那一格几何不变 ──
        val folded = StudioEditorLayout.of(640, 360, collapsed = true)
        val oldSlot = (base.frameX + 4).toDouble() to (base.frameY + base.frameH - 4).toDouble()
        check(
            "★ 收起态只剩**一行**（高 ${folded.cardH}；展开态 ${base.cardH}）",
            folded.cardH <= PANEL_COLLAPSED_H && folded.cardH * 4 <= base.cardH,
            "实际：${folded.cardH} vs ${base.cardH}",
        )
        check(
            "★ 收起态**原来预留区的位置不再吃点击**（展开态同一点是 COMMAND_SLOT）" +
                " —— 否则屏幕左边会多出一块看不见的死区",
            !folded.inFrame(oldSlot.first, oldSlot.second) &&
                StudioEditorLayout.onPanelClick(oldSlot.first, oldSlot.second, folded) == StudioPanelClick.NONE &&
                StudioEditorLayout.onPanelClick(oldSlot.first, oldSlot.second, base) == StudioPanelClick.COMMAND_SLOT,
            "实际：folded=${StudioEditorLayout.onPanelClick(oldSlot.first, oldSlot.second, folded)}",
        )
        check(
            "收起态那一行**本身**还是可点的（⇒ 能再展开）",
            StudioEditorLayout.onPanelClick(titleX, titleY, folded) == StudioPanelClick.TITLE,
        )
        check(
            "★ 命令框那一格的几何**与开合状态无关**（${folded.boxX},${folded.boxY} ${folded.boxW}×${folded.boxH}）" +
                " ⇒ 展开 / 收起都不用重摆 EditBox（也就没有「摆成零尺寸后忘了摆回来」的坑）",
            folded.boxX == base.boxX && folded.boxY == base.boxY &&
                folded.boxW == base.boxW && folded.boxH == base.boxH,
        )

        // ── ⑤ 收起 / 展开的状态机（含与命令框的关系）──
        val expanded = StudioDetailsState.EXPANDED
        val foldedState = expanded.toggled()
        check(
            "默认=展开（下次进编辑是新 Screen 实例 ⇒ 不会「进去发现啥都没有，以为坏了」）",
            !expanded.collapsed && expanded.canShowBox,
        )
        check("切两次回到原样（切换是幂等的对称操作）", foldedState.toggled() == expanded)
        check(
            "★ 收起 ⇒ 命令框**连带收起**：收起态下 boxAllowed(true) 永远是 false" +
                "（不许出现「框还在、还吃键盘，但屏幕上没有它」）",
            !foldedState.canShowBox && !foldedState.boxAllowed(true) && !foldedState.boxAllowed(false),
        )
        check(
            "展开态命令框照旧可开可关（boxAllowed 只是「收起时不许」这一条约束）",
            expanded.boxAllowed(true) && !expanded.boxAllowed(false),
        )
        check(
            "★ 收起态按 `Tab` ⇒ **先展开再调出命令框**（命令框必须画在看得见的地方）",
            foldedState.expandedForInput() == expanded && foldedState.expandedForInput().canShowBox,
        )

        // ── ⑥ 热键 `H` 的判决（选了 H：与 Tab / Esc / 回车 / WASD / 空格 / Shift / Ctrl+Z / Ctrl+Y 都不冲突）──
        val detailsKeyActions = listOf(
            StudioEditorInput.onKey(StudioEditorKey.DETAILS_KEY, ctrl = false, shift = false, dragging = false),
            StudioEditorInput.onKey(StudioEditorKey.DETAILS_KEY, ctrl = false, shift = false, dragging = true),
            StudioEditorInput.onKey(StudioEditorKey.DETAILS_KEY, ctrl = true, shift = true, dragging = true),
        )
        check(
            "★ `H` 一律判成 [StudioEditorAction.TOGGLE_DETAILS]（与 Ctrl / Shift / 是否在拖拽都无关）",
            detailsKeyActions.all { it == StudioEditorAction.TOGGLE_DETAILS },
            "实际：$detailsKeyActions",
        )
        check(
            "★ `H` **不产生任何编辑器动作**：既不会取消拖拽、退出编辑，也不会误撤销 / 重做",
            detailsKeyActions.none {
                it == StudioEditorAction.CANCEL_DRAG || it == StudioEditorAction.EXIT_EDITOR ||
                    it == StudioEditorAction.UNDO || it == StudioEditorAction.REDO ||
                    it == StudioEditorAction.BEGIN_DRAG || it == StudioEditorAction.END_DRAG
            },
        )
        check(
            "★ 命令框开着时 `H` 判给命令框（= 往框里打一个 `h`），不会把面板收起来",
            StudioEditorInput.onKeyTarget(StudioEditorKey.DETAILS_KEY, boxOpen = true) == StudioKeyTarget.INPUT_BOX &&
                StudioEditorInput.onKeyTarget(StudioEditorKey.DETAILS_KEY, boxOpen = false) == StudioKeyTarget.EDITOR,
            "实际：${StudioEditorInput.onKeyTarget(StudioEditorKey.DETAILS_KEY, boxOpen = true)}",
        )
        check(
            "`H` 不撞车：Tab / 回车仍旧不是编辑器动作，Esc 的语义一行没变",
            StudioEditorInput.onKey(StudioEditorKey.TAB, ctrl = false, shift = false, dragging = false) ==
                StudioEditorAction.NONE &&
                StudioEditorInput.onKey(StudioEditorKey.ENTER, ctrl = false, shift = false, dragging = false) ==
                StudioEditorAction.NONE &&
                StudioEditorInput.onKey(StudioEditorKey.ESCAPE, ctrl = false, shift = false, dragging = true) ==
                StudioEditorAction.CANCEL_DRAG,
        )

        // ── ⑦ 长「模型」行的收窄（两招：紧凑写法 + 按宽度截断补「…」）──
        check(
            "紧凑写法：逗号后的空格去掉（`@ -10, -59, -13` ⇒ `@ -10,-59,-13`）",
            StudioEditorLayout.compactLabel("gtet:test_clock @ -10, -59, -13") == "gtet:test_clock @ -10,-59,-13",
            "实际：${StudioEditorLayout.compactLabel("gtet:test_clock @ -10, -59, -13")}",
        )
        check(
            "放得下就**原样返回**（不许无缘无故多一个省略号）",
            StudioEditorLayout.fitText("gtet:test_clock @ -10,-59,-13", 1000, fakeFont) ==
                "gtet:test_clock @ -10,-59,-13",
        )
        val longModel = StudioEditorLayout.compactLabel(
            "gtetcore:some_very_long_machine_model_id @ -1234, -59, -13",
        )
        val valueMax = base.valueWidth(24) // 24 = 「锁定轴」这种三字标签在假字体下的宽度
        val fitted = StudioEditorLayout.fitText(longModel, valueMax, fakeFont)
        check(
            "★ 超宽的长「模型」行被截断、以「…」收尾，且收窄后**确实不越界**（${fakeFont(fitted)} ≤ $valueMax）",
            fitted.endsWith("…") && fakeFont(fitted) <= valueMax && fitted.length < longModel.length,
            "实际：$fitted",
        )
        check(
            "★ 截断后的模型行右边缘仍在中线以内（面板本身就不越中线 ⇒ 整块都干净）",
            base.cardRight - PANEL_CARD_PAD <= base.midline,
        )
        check(
            "可放宽度为 0 / 连「…」都放不下 ⇒ 返回空串（不许画出半截字或溢出到中线外）",
            StudioEditorLayout.fitText("abc", 0, fakeFont).isEmpty() &&
                StudioEditorLayout.fitText("abc", 2, fakeFont).isEmpty(),
            "实际：${StudioEditorLayout.fitText("abc", 0, fakeFont)} / ${StudioEditorLayout.fitText("abc", 2, fakeFont)}",
        )
        check(
            "值列的可用宽度随标签列收窄（标签越宽，值越早截断）",
            base.valueWidth(12) > base.valueWidth(36),
        )
        // 排版（假字体下）逐行验证：面板里没有一行会顶出卡片
        val panelLines = listOf(
            "▼ 详情（点这里 / H 收起）",
            "模型",
            longModel,
            "可撤销 12 步 ｜ 可重做 3 步",
            "● 未保存（/gtetstudio save 写回）",
        )
        check(
            "★ 面板里每一行都放得下（按各自那一列的可用宽度收窄后）",
            panelLines.all { line ->
                val limit = if (line == "模型") base.valueWidth(24) else base.cardW - PANEL_CARD_PAD * 2
                fakeFont(StudioEditorLayout.fitText(line, limit, fakeFont)) <= limit
            },
        )

        // ── ⑧ 底部提示缩成一行之后，六条关键操作一条都不许丢 ──
        val hint = "左键拖轴｜右键转视角｜WASD 飞（空格/Shift 升降）｜滚轮调速｜" +
            "Tab 命令框｜H 收起详情｜Ctrl+Z 撤销｜Esc 关框·取消拖拽·退出"
        check(
            "★ 底部一行提示**保住了六条关键操作**（左键拖轴 / 右键转视角 / WASD 飞 / Tab 命令框 / Ctrl+Z 撤销 / Esc 退出）",
            listOf("左键拖轴", "右键转视角", "WASD", "Tab 命令框", "Ctrl+Z 撤销", "Esc").all { hint.contains(it) },
            "实际：$hint",
        )
        check(
            "提示行本身也按窗口宽度收窄（窄窗口不会溢出到屏幕外）",
            StudioEditorLayout.fitText(hint, 200, fakeFont).length < hint.length,
        )
        // 几何是**纯函数**：同一输入两次调用结果一致 ⇒ 自检跑得起来本身就证明它没偷偷依赖字体/渲染状态
        val layoutOf: (Int, Int, Boolean) -> StudioPanelLayout = { w, h, c -> StudioEditorLayout.of(w, h, c) }
        val once = layoutOf(640, 360, false)
        val twice = layoutOf(640, 360, false)
        check(
            "几何是**纯函数**：同一输入两次调用给同一结果（不依赖字体宽度 ⇒ init 里也能算）",
            once.boxY == twice.boxY && once.frameY == twice.frameY && once.cardH == twice.cardH,
            "实际：${once.boxY}/${twice.boxY}",
        )
        check(
            "开合状态只影响卡片高度（${once.cardH} → ${layoutOf(640, 360, true).cardH}），" +
                "**不影响命令框那一格**",
            once.cardH != layoutOf(640, 360, true).cardH &&
                once.boxY == layoutOf(640, 360, true).boxY &&
                once.boxX == layoutOf(640, 360, true).boxX,
        )
    }

    /** 断言"这串文本能解析成这三个数"。 */
    private fun checkParsed(what: String, text: String, x: Float, y: Float, z: Float) {
        val parsed = try {
            StudioEditorInput.parseVec3(text)
        } catch (e: Exception) {
            fail("解析「$what」抛了异常（不许抛）：$e")
            return
        }
        check(
            "解析成功：$what ⇒ ($x, $y, $z)",
            parsed is StudioVectorParse.Ok && near(parsed.x, x) && near(parsed.y, y) && near(parsed.z, z),
            "实际：$parsed",
        )
    }

    /** 断言"这串文本必须被拒绝，且给得出理由（不抛异常）"。 */
    private fun checkRefused(what: String, text: String) {
        val parsed = try {
            StudioEditorInput.parseVec3(text)
        } catch (e: Exception) {
            fail("解析「$what」抛了异常（不许抛）：$e")
            return
        }
        check(
            "拒绝并给出理由：$what",
            parsed is StudioVectorParse.Bad && parsed.reason.isNotBlank(),
            "实际：$parsed",
        )
    }

    /** 从 [ox],[oy],[oz] 朝 [dx],[dy],[dz]（内部会归一化）。 */
    private fun ray(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): StudioRay =
        StudioRay.of(vec(ox, oy, oz), vec(dx, dy, dz))
            ?: throw IllegalArgumentException("自检构造的射线方向非法：($dx, $dy, $dz)")

    private fun near(a: Double?, b: Double, eps: Double = 1e-6): Boolean = a != null && abs(a - b) <= eps

    private fun near(a: Float, b: Float, eps: Float = 1e-5f): Boolean = abs(a - b) <= eps

    private fun section(title: String) {
        println("── $title")
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            passed++
            println("  ✓ $name")
        } else {
            failures += "$name${if (detail.isEmpty()) "" else "（$detail）"}"
            println("  ✗ $name${if (detail.isEmpty()) "" else "  ← $detail"}")
        }
    }

    private fun fail(message: String) {
        failures += message
        println("  ✗ $message")
    }
}