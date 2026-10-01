package rain.gtetcore.gtet.studio.interaction

import net.minecraft.client.Minecraft
import net.minecraftforge.client.event.RenderLevelStageEvent
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * **屏幕鼠标 → 世界射线**（NDC → `InvProjMat` / `InvViewRotMat` 反投影）。
 *
 * ## 为什么不拿 `mc.hitResult` 当拾取结果
 * `hitResult` 是**方块**拾取（原版给的信息只有"打到哪个方块/哪一面"），
 * 它既不知道 gizmo 手柄，也不知道模型伸出方块的那部分。所以自己算射线：
 * ```
 * 鼠标像素 → NDC(-1~1)
 *   → InvProjMat 反投影，得到近/远两个**视图空间**点
 *   → 相减得方向，再用 InvViewRotMat 把方向转进世界空间
 *   → 方向归一化，射线原点 = 相机位置
 * ```
 *
 * ## 两个容易错的地方
 * 1. **鼠标坐标的坐标系**：`MouseHandler.xpos()/ypos()` 用的是**窗口**像素，
 *    所以 NDC 要除 `screenWidth/screenHeight`（不是 framebuffer 的 `width/height`）；
 * 2. **视图矩阵**：直接用事件里那份 `poseStack.last().pose()` —— 它就是本帧真正用的
 *    视图旋转（相机在原点、含视角摇晃）。用它而不是 `camera.rotation()`，是为了让
 *    **拾取与画面严格一致**（尤其低头/摇晃时，两者能差出小半个手柄）。
 *
 * ## 光标锁定时是什么行为
 * 原版把光标锁进窗口时，`xpos/ypos` 恒等于**屏幕中心** ⇒ 射线就是准星那条。
 * 也就是说：锁定时光标即准星、解锁时（开了界面以外的情况）用真实鼠标位置，
 * 两条路都不需要额外代码。M2a **不做**"拖拽时解锁光标"那套（Axiom 的自由光标留到后面）。
 *
 * @author rain fox
 */
object StudioCameraRay {

    /**
     * 用本帧的渲染信息算一条世界射线。
     *
     * @return null = 矩阵求逆失败/鼠标位置离谱（都会导致 NaN，宁可不拾取）
     */
    @JvmStatic
    fun fromEvent(event: RenderLevelStageEvent): StudioRay? {
        val mc = Minecraft.getInstance()
        val window = mc.window
        val mouse = mc.mouseHandler
        if (window.screenWidth <= 0 || window.screenHeight <= 0) return null

        // 鼠标像素 → NDC（y 轴翻转：GL 的 +y 朝上）
        val ndcX = 2.0 * mouse.xpos() / window.screenWidth - 1.0
        val ndcY = 1.0 - 2.0 * mouse.ypos() / window.screenHeight

        val invProjection = Matrix4f(event.projectionMatrix).invert()
        val invViewRotation = Matrix4f(event.poseStack.last().pose()).invert()

        // NDC 的近/远点（z = -1 / +1）反投影到视图空间
        val near = invProjection.transform(Vector4f(ndcX.toFloat(), ndcY.toFloat(), -1f, 1f))
        val far = invProjection.transform(Vector4f(ndcX.toFloat(), ndcY.toFloat(), 1f, 1f))
        if (near.w == 0f || far.w == 0f) return null
        near.div(near.w)
        far.div(far.w)

        val dir = Vector3f(far.x - near.x, far.y - near.y, far.z - near.z)
        invViewRotation.transformDirection(dir) // 视图空间方向 → 世界方向（只转不平移）

        val camera = event.camera.position
        return StudioRay.of(
            StudioVec3(camera.x, camera.y, camera.z),
            StudioVec3(dir.x.toDouble(), dir.y.toDouble(), dir.z.toDouble()),
        )
    }
}