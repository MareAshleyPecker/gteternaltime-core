package rain.gtetcore.gtet.studio.api

import rain.gtetcore.gtet.studio.api.StudioAnchor.Companion.FACE_FRONT
import rain.gtetcore.gtet.studio.api.StudioAnchor.Companion.MODE_MACHINE
import kotlin.math.sqrt

/**
 * 模型挂到宿主上的定位规则（对应 JSON 的 `anchor` 段）。
 *
 * ## 坐标系
 * 走完 [rain.gtetcore.gtet.studio.render.StudioRenderer] 的「面坐标系」之后，局部坐标系是：
 * - **+Z** = 宿主正面朝外（面法线）；
 * - **+Y** = 世界上方（水平朝向时）；
 * - **+X** = 面内向右。
 *
 * [offset] 就是在这个面坐标系里施加的，所以 `offset = [0, 1, 0]` 恒等于「沿面往上抬一格」，
 * 与机器朝南朝北无关。
 *
 * M0 只实现 `mode = "machine"` + `face = "front"`（其余取值会拒绝并报错，不做静默降级）。
 *
 * @author rain fox
 */
class StudioAnchor(
    /** 锚定模式；M0 只有 [MODE_MACHINE]。 */
    val mode: String = MODE_MACHINE,
    /** 贴哪一面；M0 只有 [FACE_FRONT]（= 宿主正面）。 */
    val face: String = FACE_FRONT,
    /** 面坐标系里的偏移（格）。 */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
    /** 统一缩放。 */
    val scale: Float = 1f,
) {

    /** 偏移向量的长度（格）——算渲染包围盒时用得到。 */
    val offsetLength: Double = sqrt(
        (offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ).toDouble()
    )

    companion object {
        const val MODE_MACHINE = "machine"
        const val FACE_FRONT = "front"

        @JvmField
        val DEFAULT = StudioAnchor()
    }
}