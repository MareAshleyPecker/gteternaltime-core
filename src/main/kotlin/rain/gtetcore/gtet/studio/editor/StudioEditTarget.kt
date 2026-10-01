package rain.gtetcore.gtet.studio.editor

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.studio.interaction.StudioAnchorOffset
import rain.gtetcore.gtet.studio.interaction.StudioRay

/**
 * **编辑器眼里的"一台带 studio 模型的机器"** —— 宿主信息的窄接口。
 *
 * ## 为什么要有它（分层纪律）
 * "当前世界里哪些机器带着 studio 模型、它的实例是谁、机器朝哪、方块在哪" 这些只有
 * `integration/` 知道（那是全包唯一能 import GTM 的地方）。但 `editor/` **一行 GTM 都不许 import**，
 * 所以宿主信息一律通过这个接口递进来：`integration/StudioMachineRegistry` 实现它，
 * 编辑模式与拖拽逻辑只认这个接口。
 *
 * ⇒ 结果：以后换一台机器（ETV 那台）挂 studio 模型，**这里一行都不用改**。
 *
 * @author rain fox
 */
interface StudioEditTarget {
    /** 模型 id（就是 `config/gtetstudio/` 下那份 json 里的 `id` 字段）。 */
    val modelId: ResourceLocation

    /** 人类可读名字（模型 id + 机器坐标），反馈与命令描述里用。 */
    val label: String

    /** 机器方块坐标（渲染姿态的原点）。 */
    val pos: BlockPos

    /** 机器正面朝向 —— gizmo 的面坐标系就是靠它摆的（与模型渲染同一套变换）。 */
    val facing: Direction

    /** 模型统一缩放（`anchor.scale`）。 */
    val scale: Float

    /** 模型离锚点的最大距离（格）—— 画 gizmo、放大 AABB 都要用。 */
    val reachRadius: Double

    /**
     * **文件里那份**偏移（进入编辑那一刻读到的值）。
     *
     * 用它判断"有没有未保存改动"：活值 != 它 ⇒ 动作栏上显示 ●。
     *
     * ⚠️ 必须是 [StudioEditor] **现读**（实现方给 getter，不许做构造期快照）。
     * 实机踩过：`save` → `reload` → 再次 `edit` 时，注册表里缓存的 target 还留着**最早那份**偏移，
     * 活值于是被重置回原始位置、模型当场跳回去。同类的 `scale` / `reachRadius` 一直是现读的，
     * 只有它曾经是例外。
     */
    val fileOffset: StudioAnchorOffset

    /** 这台机器还在不在（被拆掉/换维度/区块卸载 ⇒ false，编辑器会自动退出，不让用户白拖）。 */
    fun isValid(): Boolean
}

/**
 * **"去哪找带 studio 模型的机器"** —— 由 `integration/` 实现并 [StudioEditor.installSource] 装进来。
 *
 * 两个查询各自对应一条进入编辑的路径：
 * - [byCrosshair] → `/gtetstudio edit`（准星指着哪台就是哪台）；
 * - [nearest] → `/gtetstudio edit <modelId>`（准星不好对准时的兜底：取最近的那台）。
 *
 * 外加 [pickMachine] 给"先手柄、再机器"的命中优先级用（手柄挡住机器时不许穿透）。
 */
interface StudioTargetSource {

    /** 准星（方块 `hitResult`，退而求其次用射线打模型的包围盒）指到的实例；找不到返回 null。 */
    fun byCrosshair(): StudioEditTarget?

    /** 离玩家最近的、该 id 的实例；找不到返回 null。 */
    fun nearest(modelId: ResourceLocation): StudioEditTarget?

    /**
     * 射线打到哪台机器（世界射线，**相机相对或绝对都行**，只要与 [StudioMachineHit] 一致）。
     *
     * ⚠️ 只有"没命中任何手柄"时才会被调用（见 `StudioPicker.pick`）——
     * 这就是设计文档 §6 要求的命中优先级。
     */
    fun pickMachine(ray: StudioRay): Double?
}