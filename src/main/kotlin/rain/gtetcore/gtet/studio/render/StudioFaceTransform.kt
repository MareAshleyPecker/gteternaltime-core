package rain.gtetcore.gtet.studio.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.core.Direction
import com.mojang.math.Axis as MCAxis

/**
 * **"宿主方块 → 面坐标系"** 的那两步变换（`moveToFace` + `applyFaceFrame`）。
 *
 * ## 为什么单独抽出来
 * 它有两个用户，而且**必须逐字一致**：
 * 1. [StudioRenderer] 用它把模型摆到机器正面；
 * 2. `interaction/StudioGizmoRenderer` 用它把 gizmo 摆到同一个坐标系里，并求逆把鼠标射线
 *    反变换进局部空间。
 *
 * 如果 gizmo 那边自己再推一遍朝向（哪怕是"照着写一遍"），只要有一个符号写反，
 * 症状就是"gizmo 和模型差一个旋转/镜像"——这是最难查的一类 bug。
 * 抽成一份、两边共用，就不存在"两边不一致"这个可能。
 *
 * ## 摆完之后
 * **+Z = 面外法线**、**+Y = 世界上方（水平朝向时）/ 面内向上**、**+X = 面内向右**。
 * 模型就是照这个约定做的（表盘在 XY 平面，指针绕 Z 转）；
 * `anchor.offset` 与 M2a 的 gizmo 三根轴也都在这个坐标系里。
 *
 * @author rain fox
 */
object StudioFaceTransform {

    /**
     * 从「宿主方块最小角」挪到「该方块在 [face] 那一面的中心」。
     *
     * 与 GTM 的 `RenderUtil.moveToFace`（`RenderUtil.java:216-220`）逐字等价：
     * `translate(x + face.x*0.5, y + face.y*0.5, z + face.z*0.5)`，此处 x=y=z=0。
     */
    @JvmStatic
    fun moveToFace(poseStack: PoseStack, face: Direction) {
        poseStack.translate(face.stepX * 0.5f, face.stepY * 0.5f, face.stepZ * 0.5f)
    }

    /**
     * 把局部坐标系摆到 [face] 的外侧。
     *
     * ## 为什么不用 GTM 的 `RenderUtil.rotateToFace`（`RenderUtil.java:260-278`）
     * 那个是给**二维贴图覆盖层**用的：它内部有一句 `poseStack.scale(-1, -1, -1)`
     * —— 行列式 -1，是个**镜像**（点反射）。对贴图平面无所谓，但套在三维模型上会让整个模型
     * 变成手性相反的镜像体（指针转的方向也跟着反过来）。所以这里自己写一个纯旋转的版本，
     * 只借 `moveToFace` 做平移。
     */
    @JvmStatic
    fun applyFaceFrame(poseStack: PoseStack, face: Direction) {
        when (face) {
            // 世界 +Z 就是南：默认约定已经对上，不用转
            Direction.SOUTH -> return
            Direction.NORTH -> poseStack.mulPose(MCAxis.YP.rotationDegrees(180f))
            Direction.EAST -> poseStack.mulPose(MCAxis.YP.rotationDegrees(90f))
            Direction.WEST -> poseStack.mulPose(MCAxis.YP.rotationDegrees(-90f))
            Direction.UP -> poseStack.mulPose(MCAxis.XP.rotationDegrees(-90f))
            Direction.DOWN -> poseStack.mulPose(MCAxis.XP.rotationDegrees(90f))
        }
    }

    /** 两步一起（摆到"面向 [face]、原点在面中心"的坐标系）。 */
    @JvmStatic
    fun toFaceFrame(poseStack: PoseStack, face: Direction) {
        moveToFace(poseStack, face)
        applyFaceFrame(poseStack, face)
    }
}