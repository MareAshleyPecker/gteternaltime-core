package rain.gtetcore.gtet.common.item.timeflow

import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 时序之瓶的双语条目。
 *
 * 与 [rain.gtetcore.gtet.util.TooltipsExt] 那套 `.tooltips(...)` 的**静态**行分开：
 * 静态行是 `item.gtetcore.time_bottle.tooltip.<序号>`，这里的是**带数字的运行时行**
 * （汇率、相位、存量都会变，所以只能 `Component.translatable(键, 参数...)`）。
 *
 * 必须在数据生成之前登记 —— [rain.gtetcore.gtet.common.data.item.ETItems] 的对象初始化会调 [init]，
 * 而它在 `CommonProxy` 的 `kotlinInit()` 里早于 `GatherDataEvent`。
 */
object TimeBottleLang {

    private const val PREFIX = "item.gtetcore.time_bottle.tip."

    /** 档位与容量上限：`档位 %s / 容量上限 %s TF`。 */
    const val TIER: String = PREFIX + "tier"

    /** 瓶内 TF 与其折算 EU：`瓶内 %s TF（= %s EU）`。 */
    const val CONTENT: String = PREFIX + "content"

    /** 单位定义。 */
    const val UNIT: String = PREFIX + "unit"

    /** 当前汇率与相位。 */
    const val RATE: String = PREFIX + "rate"

    /** 不在世界内（物品栏预览 / JEI）时的汇率行。 */
    const val RATE_UNKNOWN: String = PREFIX + "rate_unknown"

    /** 潮汐已关闭。 */
    const val TIDE_OFF: String = PREFIX + "tide_off"

    /** 距峰值 / 距谷值的秒数。 */
    const val TIDE_NEXT: String = PREFIX + "tide_next"

    /** 当前往返损耗。 */
    const val LOSS: String = PREFIX + "loss"

    /** 绑定成功的聊天提示。 */
    const val BOUND: String = PREFIX + "bound"

    /** 解绑成功的聊天提示。 */
    const val UNBOUND: String = PREFIX + "unbound"

    /** 绑上了但没有取用权限时的聊天提示（比 [BOUND] 多一句说明）。 */
    const val BOUND_DENIED: String = PREFIX + "bound_denied"

    /** tooltip 里的「已绑定」行。 */
    const val BOUND_TIP: String = PREFIX + "bound_tip"

    /** tooltip 里的「未绑定」行。 */
    const val UNBOUND_TIP: String = PREFIX + "unbound_tip"

    /** 由 [rain.gtetcore.gtet.common.data.item.ETItems] 的对象初始化调用一次。 */
    @JvmStatic
    fun init() {
        LangUtil.add(TIER, "Tier %s / capacity %s TF", "档位 %s / 容量上限 %s TF")
        LangUtil.add(CONTENT, "Stored %s TF (= %s EU)", "瓶内 %s TF（= %s EU）")
        LangUtil.add(
            UNIT,
            "1 TF = 8,192 EU (= 1A IV x 1 tick)",
            "1 TF = 8,192 EU（= 1A IV × 1 tick）"
        )
        LangUtil.add(RATE, "Tide rate %sx (phase %s)", "潮汐汇率 %s×（相位 %s）")
        LangUtil.add(
            RATE_UNKNOWN,
            "Tide rate: n/a outside a level",
            "潮汐汇率：不在世界内，无法计算"
        )
        LangUtil.add(
            TIDE_OFF,
            "Tide disabled (a = 0, rate is always 1.00x)",
            "潮汐已关闭（a = 0，汇率恒为 1.00×）"
        )
        LangUtil.add(
            TIDE_NEXT,
            "Next peak in %s s, next trough in %s s",
            "距峰值 %s 秒，距谷值 %s 秒"
        )
        LangUtil.add(
            LOSS,
            "Round-trip loss %s%% (charge 100%%, discharge 80%%)",
            "往返损耗 %s%%（充入 100%% / 取出 80%%）"
        )
        LangUtil.add(
            BOUND,
            "Time bottle bound to master tower at (%s, %s, %s)",
            "时序之瓶已绑定主控塔：(%s, %s, %s)"
        )
        LangUtil.add(UNBOUND, "Time bottle unbound", "时序之瓶已解除绑定")
        LangUtil.add(
            BOUND_DENIED,
            "Bound to the master tower at (%s, %s, %s), but you are not its owner / on its whitelist - payments will fall back to the TF inside the bottle",
            "已绑定主控塔：(%s, %s, %s)，但你不在它的所有者 / 白名单里 —— 扣费会回落到瓶内 TF"
        )
        LangUtil.add(BOUND_TIP, "Bound tower: %s", "已绑定主控塔：%s")
        LangUtil.add(UNBOUND_TIP, "Not bound to any master tower", "未绑定主控塔")
    }
}
