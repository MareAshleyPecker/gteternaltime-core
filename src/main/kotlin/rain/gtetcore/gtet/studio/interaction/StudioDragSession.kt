package rain.gtetcore.gtet.studio.interaction

/**
 * **拖拽参考**：把"当前鼠标射线"映射成"相对按下那一刻的位移"，并**锁死允许变动的轴**。
 *
 * 两种参考对应两类手柄：
 * - [AxisLocked]：射线与「过 gizmo 原点、沿某根轴」的**直线**求最近点，参数差就是位移
 *   ⇒ 拖 X 只改 X（轴锁定）；
 * - [PlaneLocked]：射线与「过 gizmo 原点、法线为某根轴」的**平面**求交，交点差就是位移
 *   ⇒ 双轴同时动，第三根轴一动不动。
 *
 * ⚠️ 这里的 [AxisLocked.anchor] / [PlaneLocked.planePoint] 必须是**按下那一刻**的 gizmo 原点快照，
 * 而不是"每帧重算的当前原点"—— 否则拖动过程中原点跟着动，参考系自己漂移，
 * 表现为"越拖越加速"。所以按下时构造一次，整个拖拽期间不变。
 *
 * @author rain fox
 */
sealed class StudioDragReference {

    /** 允许变动的轴（只有这些分量会被写回）。 */
    abstract val axes: List<StudioAxis>

    /** 人类可读描述（动作栏反馈用）。 */
    abstract val describe: String

    /**
     * @return 相对按下点的位移（已按 [axes] 屏蔽）；null = 射线与参考**退化**
     *         （平行/背向），这一帧不动即可，不要瞎猜一个值
     */
    abstract fun deltaFrom(ray: StudioRay): StudioVec3?

    /** 沿一根轴的拖动。 */
    class AxisLocked(
        val anchor: StudioVec3,
        val axis: StudioAxis,
        /** 按下时求交得到的轴参数（= 从 anchor 沿轴走多远）。 */
        val grabParam: Double,
    ) : StudioDragReference() {

        override val axes: List<StudioAxis> get() = listOf(axis)

        override val describe: String get() = "轴锁定 ${axis.label}"

        override fun deltaFrom(ray: StudioRay): StudioVec3? {
            val approach = StudioRayMath.closestApproach(ray, anchor, axis.unit) ?: return null
            if (approach.rayParam < 0.0) return null // 手柄在相机背后
            return axis.unit * (approach.axisParam - grabParam)
        }

        companion object {

            /** 按下时构造；null = 射线与轴平行（正对着轴看），这种情况下这一下不算抓到手柄。 */
            @JvmStatic
            fun of(anchor: StudioVec3, axis: StudioAxis, ray: StudioRay): AxisLocked? {
                val approach = StudioRayMath.closestApproach(ray, anchor, axis.unit) ?: return null
                if (approach.rayParam < 0.0) return null
                return AxisLocked(anchor, axis, approach.axisParam)
            }
        }
    }

    /** 在一张平面里拖动（双轴）。 */
    class PlaneLocked(
        val planePoint: StudioVec3,
        val normal: StudioAxis,
        /** 按下时的交点。 */
        val grabPoint: StudioVec3,
    ) : StudioDragReference() {

        override val axes: List<StudioAxis> get() = StudioAxis.others(normal)

        override val describe: String get() = "平面 ${axes[0].label}${axes[1].label}"

        override fun deltaFrom(ray: StudioRay): StudioVec3? {
            val point = StudioRayMath.rayPlanePoint(ray, planePoint, normal.unit) ?: return null
            return (point - grabPoint).mask(axes)
        }

        companion object {

            /** 按下时构造；null = 射线与平面平行（贴着平面看过去），这一下不算抓到手柄。 */
            @JvmStatic
            fun of(anchor: StudioVec3, normal: StudioAxis, ray: StudioRay): PlaneLocked? {
                val point = StudioRayMath.rayPlanePoint(ray, anchor, normal.unit) ?: return null
                return PlaneLocked(anchor, normal, point)
            }
        }
    }
}

/**
 * **一次拖拽 = 一条命令**（设计文档 §6 第三条：连续拖拽合并成一条）。
 *
 * 生命周期：
 * ```
 * 按下   → StudioDragSession(start = 当前 offset)      ← 记下起始值快照
 * 每帧   → update(ray, 吸附规则)                       ← 只改内存活值，不产生命令
 * 松开   → toCommand()                                 ← 这里才产出**一条**命令
 * Esc    → cancel()                                    ← 回到按下前的值，一条命令都不产生
 * ```
 * 也就是说"同一次按下-移动-松开"在撤销栈里**只会出现一条**，而不是每帧一条。
 *
 * @param start 按下那一刻的偏移（会被 [copy] 成快照，活值之后的改动不影响它）
 */
class StudioDragSession(
    val reference: StudioDragReference,
    start: StudioAnchorOffset,
) {

    /** 按下那一刻的值（Esc 取消就是回到它）。 */
    private val startSnapshot: StudioAnchorOffset = start.copy()

    /** 当前值（每帧 [update] 之后就是它；渲染读的就是这份）。 */
    var current: StudioAnchorOffset = start.copy()
        private set

    /** 最近一次的位移（反馈里显示"这一拖动了多少"）。 */
    var lastDelta: StudioVec3 = StudioVec3.ZERO
        private set

    /** 射线退化的帧数（平行/背向 —— 这些帧不改值，只计数）。 */
    var degenerateFrames: Int = 0
        private set

    val axes: List<StudioAxis> get() = reference.axes

    /** 相对按下点动过没有（没动过就不该产生命令）。 */
    val moved: Boolean get() = !current.equalsValue(startSnapshot)

    val startValue: StudioAnchorOffset get() = startSnapshot

    /**
     * 用当前射线更新一次。
     *
     * @return 新的偏移（同时写进 [current]）
     */
    fun update(ray: StudioRay, rule: StudioSnapRule): StudioAnchorOffset {
        val delta = reference.deltaFrom(ray)
        if (delta == null) {
            degenerateFrames++
            return current
        }
        lastDelta = delta

        val base = startSnapshot.vec()
        val target = base + delta
        // ⚠️ 只吸附**被允许的轴**：其余分量原样保留文件里的值。
        //    否则拖 X 会把本来不是 0.25 倍数的 Y 一起吸走 —— 那是静默的数据损坏。
        val snapped = StudioVec3(
            if (StudioAxis.X in axes) rule.snap(target.x) else base.x,
            if (StudioAxis.Y in axes) rule.snap(target.y) else base.y,
            if (StudioAxis.Z in axes) rule.snap(target.z) else base.z,
        )
        current = StudioAnchorOffset(snapped)
        return current
    }

    /** Esc：回到按下前的值（**不产生命令**）。 */
    fun cancel(): StudioAnchorOffset {
        current = startSnapshot.copy()
        lastDelta = StudioVec3.ZERO
        return current
    }

    /**
     * 松开时把这次拖拽变成**一条**命令。
     *
     * @param holder 编辑中的活值对象（命令的 apply/revert 改它）
     * @param label  目标名字（进命令描述）
     * @return null = 值没变（点了一下没拖），不产生命令
     */
    fun toCommand(holder: StudioAnchorOffset, label: String): TranslateAnchorCommand? {
        if (!moved) return null
        return TranslateAnchorCommand(holder, startSnapshot.copy(), current.copy(), label)
    }

    override fun toString(): String =
        "拖拽[${reference.describe}，${startSnapshot.format()} → ${current.format()}" +
            "，退化帧 $degenerateFrames]"
}