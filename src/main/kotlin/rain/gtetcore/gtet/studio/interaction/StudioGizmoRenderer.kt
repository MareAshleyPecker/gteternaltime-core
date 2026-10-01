package rain.gtetcore.gtet.studio.interaction

import com.lowdragmc.lowdraglib.client.shader.LDLibRenderTypes
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import org.joml.Matrix4f

/**
 * **画三轴 gizmo**（X 红 / Y 绿 / Z 蓝 + 端点小方块 + 三个平面手柄）。
 *
 * ## 画在哪、用什么画
 * 调用方（[StudioInteractionEvents]）已经把姿态栈摆到了**面坐标系**（与模型渲染同一套
 * `moveToFace` + `applyFaceFrame`），所以这里直接用局部坐标发射顶点即可，
 * 不需要再推任何朝向。
 *
 * 渲染类型用 LDLib 的现成品（`LDLibRenderTypes`，它 `javap` 出来的字节码构造是：
 * `noDepthLines` = `POSITION_COLOR_NORMAL / LINES / NO_DEPTH_TEST / 线宽 3.0`；
 * `positionColorNoDepth` = `POSITION_COLOR / TRIANGLES / NO_DEPTH_TEST`）：
 * - **always-on-top 是有意的取舍**：不写深度、不测深度 ⇒ 被方块挡住也能看见、能抓到。
 *   Axiom 也是这个路子。代价是"隔着墙也能点到手柄"（在机器旁边反而方便）；
 *   真正该做的是"被挡住时画半透明"，那需要深度纹理回读，留到后面再说。
 * - 这两个 `RenderType` 是**静态单例**，每帧只是取来用 —— 不会每帧 new 一堆对象。
 *
 * ## 高亮
 * 悬停/正在拖的轴**整体提亮并加白**（同一根轴的待机色只有 60% 亮度）：
 * 一眼能看出"这一下会抓到哪根"，不依赖颜色记忆。
 * 线宽是 LDLib 定死的 3.0，M2a 不为高亮再单独造一套 `RenderType`
 * （不划算，且要自己去拿 `RenderStateShard` 的 protected 字段）。
 *
 * @author rain fox
 */
object StudioGizmoRenderer {

    /** 待机透明度（0~1）：能看清又不至于糊住模型。 */
    private const val IDLE_ALPHA = 0.62f

    /** 高亮时朝白色靠拢的比例。 */
    private const val HIGHLIGHT_TO_WHITE = 0.55f

    /** 三根轴的颜色：X 红 / Y 绿 / Z 蓝。 */
    private fun baseColor(axis: StudioAxis): Triple<Float, Float, Float> = when (axis) {
        StudioAxis.X -> Triple(0.95f, 0.30f, 0.30f)
        StudioAxis.Y -> Triple(0.35f, 0.90f, 0.35f)
        StudioAxis.Z -> Triple(0.35f, 0.55f, 1.00f)
    }

    /**
     * 画一帧。
     *
     * @param frame  当前 gizmo（原点 = 活值偏移，轴长按距离缩放）
     * @param hover  拾取命中的手柄（悬停高亮）
     * @param active 正在拖拽的手柄（拖拽高亮）
     */
    fun draw(
        poseStack: PoseStack,
        frame: StudioGizmoFrame,
        hover: StudioHandle?,
        active: StudioHandle?,
    ) {
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        val matrix: Matrix4f = poseStack.last().pose()

        // ── ① 三根轴（线）──
        val lines: VertexConsumer = buffers.getBuffer(LDLibRenderTypes.noDepthLines())
        for (axis in StudioAxis.entries) {
            val color = colorFor(axis, highlight = isHighlighted(StudioHandle.Axis(axis), hover, active))
            line(lines, matrix, frame.axisFrom(axis), frame.axisTo(axis), color)
        }
        buffers.endBatch(LDLibRenderTypes.noDepthLines())

        // ── ② 端点小方块 + 平面手柄（实心小方块）──
        val solids: VertexConsumer = buffers.getBuffer(LDLibRenderTypes.positionColorNoDepth())
        for (axis in StudioAxis.entries) {
            val color = colorFor(axis, highlight = isHighlighted(StudioHandle.Axis(axis), hover, active))
            cube(solids, matrix, frame.tipCenter(axis), frame.tipHalf, color)
        }
        for (normal in StudioAxis.entries) {
            val handle = StudioHandle.ofPlane(normal)
            // 平面手柄用它的**法线轴**的颜色：一眼知道"这块是按哪个方向拖"
            val color = colorFor(normal, highlight = isHighlighted(handle, hover, active))
            cube(solids, matrix, frame.planeCenter(handle.first, handle.second), frame.planeHalf, color)
        }
        buffers.endBatch(LDLibRenderTypes.positionColorNoDepth())
    }

    private fun isHighlighted(candidate: StudioHandle, hover: StudioHandle?, active: StudioHandle?): Boolean =
        candidate == hover || candidate == active

    private fun colorFor(axis: StudioAxis, highlight: Boolean): FloatArray {
        val (r, g, b) = baseColor(axis)
        return if (highlight) {
            floatArrayOf(
                r + (1f - r) * HIGHLIGHT_TO_WHITE,
                g + (1f - g) * HIGHLIGHT_TO_WHITE,
                b + (1f - b) * HIGHLIGHT_TO_WHITE,
                1f,
            )
        } else {
            floatArrayOf(r, g, b, IDLE_ALPHA)
        }
    }

    /** 一条线段（`POSITION_COLOR_NORMAL`：法线在 LINES 着色器里就是"线自身的方向"）。 */
    private fun line(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        from: StudioVec3,
        to: StudioVec3,
        color: FloatArray,
    ) {
        val dir = (to - from).normalized() ?: StudioVec3(0.0, 1.0, 0.0)
        val nx = dir.x.toFloat()
        val ny = dir.y.toFloat()
        val nz = dir.z.toFloat()
        consumer.vertex(matrix, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .color(color[0], color[1], color[2], color[3]).normal(nx, ny, nz).endVertex()
        consumer.vertex(matrix, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .color(color[0], color[1], color[2], color[3]).normal(nx, ny, nz).endVertex()
    }

    /** 一个实心小方块（6 面 × 2 三角形）。 */
    private fun cube(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        center: StudioVec3,
        half: Double,
        color: FloatArray,
    ) {
        val x0 = (center.x - half).toFloat()
        val y0 = (center.y - half).toFloat()
        val z0 = (center.z - half).toFloat()
        val x1 = (center.x + half).toFloat()
        val y1 = (center.y + half).toFloat()
        val z1 = (center.z + half).toFloat()

        // 六个面，顶点顺序按外向法线的右手方向（本来不剔除，但顺手写对）
        quad(consumer, matrix, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color) // 南 +Z
        quad(consumer, matrix, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color) // 北 -Z
        quad(consumer, matrix, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color) // 东 +X
        quad(consumer, matrix, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color) // 西 -X
        quad(consumer, matrix, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, color) // 上 +Y
        quad(consumer, matrix, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color) // 下 -Y
    }

    private fun quad(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        color: FloatArray,
    ) {
        vertex(consumer, matrix, ax, ay, az, color)
        vertex(consumer, matrix, bx, by, bz, color)
        vertex(consumer, matrix, cx, cy, cz, color)
        vertex(consumer, matrix, dx, dy, dz, color)
    }

    private fun vertex(consumer: VertexConsumer, matrix: Matrix4f, x: Float, y: Float, z: Float, color: FloatArray) {
        consumer.vertex(matrix, x, y, z).color(color[0], color[1], color[2], color[3]).endVertex()
    }
}