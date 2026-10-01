package rain.gtetcore.gtet.studio.render

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexBuffer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import org.joml.Matrix4f
import rain.gtetcore.gtet.studio.api.StudioAnchor
import rain.gtetcore.gtet.studio.api.StudioHost
import rain.gtetcore.gtet.studio.data.StudioInstance
import rain.gtetcore.gtet.studio.render.StudioRenderer.render

/**
 * 工作室的**运行时绘制** —— 给一个姿态栈、一个时刻，把模型画出来。
 *
 * ## M1a 的画法（相对 M0）
 * M0 走 Forge 的 `CompositeRenderable`：每帧把顶点写进 `MultiBufferSource`，
 * 由 Forge 按 OBJ 组名叠矩阵。M1a 换成**自有 VBO**：
 * ```
 * 每帧只做两件事
 *   1. 摆位：宿主方块最小角 → 正面中心 → 面坐标系 → anchor 的偏移与缩放
 *   2. 每张 VBO：setupRenderState → 设矩阵 → bind/draw → clearRenderState
 * 顶点一个都不动（几何在载入时就烘进 VertexBuffer(Usage.STATIC) 了）
 * ```
 * 这就是设计文档 §7 第 2 条与第 7 条要求的形态（照 GTM 的
 * `MultiblockInWorldPreviewRenderer`：`:76` 建 `VertexBuffer(Usage.STATIC)`、
 * `:299-368` 每帧 setupRenderState + 手设 shader uniform + bind/draw）。
 *
 * ## 为什么不用 `buffer: MultiBufferSource`
 * 自有 VBO 是**直接画**的，不经过 `MultiBufferSource` 的批处理。所以 [render] 收下这个参数
 * 只是为了保持调用方（BER）的签名整洁与以后要用它画动态几何时不用改签名 —— 现在它没被用到。
 *
 * @author rain fox
 */
object StudioRenderer {

    /**
     * 画一个模型。
     *
     * @param anchor      用哪份定位规则。**默认是模型文件里那份**，但编辑中会被换成
     *                    "带实时预览偏移"的那一份（`StudioEditor.previewOffset`）。
     *                    ⚠️ 关键点：`anchor` **只参与下面这几行矩阵**，一个顶点都不进 VBO
     *                    （[StudioRenderCache.build] 只烘几何 + 光照）⇒
     *                    **拖拽时不需要重烘顶点缓冲**，改个值下一帧就是新位置。
     * @param poseStack   进来时原点 = **宿主方块的最小角**且已扣相机
     *                    （`LevelRenderer.java:1271` 起的那段，GTM 的 BER 派发就按这个约定）
     * @param packedLight ⚠️ **只对宿主那一格有效**（`BlockEntityRenderDispatcher.java:80`）。
     *                    这个时钟横跨十几格，理论上该逐顶点 `LevelRenderer.getLightColor(level, pos)` 采样再
     *                    `.uv2(...)`；**沿用 M0 的取舍**：整块模型统一用控制器那一格的光
     *                    （烘进顶点缓冲，光变了会自动重烘，见 [StudioRenderCache]）。
     *                    后果是"贴着时钟的另一侧有光源时，时钟不会跟着变亮" —— 看起来不像 bug，
     *                    但这是有意为之（设计文档 §7 第 4 条给了正确做法，留给后面补）。
     */
    @JvmStatic
    fun render(
        host: StudioHost,
        instance: StudioInstance,
        anchor: StudioAnchor,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        if (!host.isActive) return

        val built = StudioRenderCache.obtain(instance, packedLight, packedOverlay) ?: return
        val transforms = instance.clip.evaluate(host.timeSeconds())

        poseStack.pushPose()
        // 到宿主正面的中心，再摆正面坐标系（与 interaction 的 gizmo 共用同一份实现，
        // 免得"两边各推一遍朝向"推岔了）。等价于 GTM 的 `RenderUtil.moveToFace`。
        StudioFaceTransform.toFaceFrame(poseStack, host.facing)
        poseStack.translate(anchor.offsetX, anchor.offsetY, anchor.offsetZ)
        val scale = anchor.scale
        poseStack.scale(scale, scale, scale)

        for (part in built.parts) {
            val transform = part.part?.let { transforms[it] }
            drawPart(poseStack, part, transform)
        }
        poseStack.popPose()
    }

    /**
     * 画一张 VBO。
     *
     * 这一段是照 GTM `MultiblockInWorldPreviewRenderer.java:299-368` 写的：`RenderType.setupRenderState()`
     * 只会把 shader 选好，**矩阵/雾/颜色这些 uniform 得自己塞**（因为我们绕过了
     * `BufferUploader.drawWithShader`，没有第二个人帮忙设 uniform）。
     */
    private fun drawPart(poseStack: PoseStack, part: StudioRenderCache.PartBuffer, transform: Matrix4f?) {
        val renderType = part.renderType
        renderType.setupRenderState()
        val shader = RenderSystem.getShader() ?: run {
            renderType.clearRenderState()
            return
        }

        for (i in 0 until 12) {
            shader.setSampler("Sampler$i", RenderSystem.getShaderTexture(i))
        }

        poseStack.pushPose()
        if (transform != null) {
            // 部件矩阵叠在 anchor 之上（与 M0 的 Transforms.of 同义：组名 → 局部矩阵）
            poseStack.last().pose().mul(transform)
        }

        shader.MODEL_VIEW_MATRIX?.set(poseStack.last().pose())
        shader.PROJECTION_MATRIX?.set(RenderSystem.getProjectionMatrix())
        shader.COLOR_MODULATOR?.set(RenderSystem.getShaderColor())
        shader.FOG_START?.set(Float.MAX_VALUE)
        shader.FOG_END?.set(RenderSystem.getShaderFogEnd())
        shader.FOG_COLOR?.set(RenderSystem.getShaderFogColor())
        shader.FOG_SHAPE?.set(RenderSystem.getShaderFogShape().index)
        shader.TEXTURE_MATRIX?.set(RenderSystem.getTextureMatrix())
        shader.GAME_TIME?.set(RenderSystem.getShaderGameTime())

        RenderSystem.setupShaderLights(shader)
        shader.apply()

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        if (renderType == RenderType.translucent()) {
            RenderSystem.enableBlend()
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA)
            RenderSystem.depthMask(false)
        } else {
            RenderSystem.enableDepthTest()
            RenderSystem.disableBlend()
            RenderSystem.depthMask(true)
        }

        part.buffer.bind()
        part.buffer.draw()
        VertexBuffer.unbind()

        poseStack.popPose()

        shader.clear()
        renderType.clearRenderState()
    }
}