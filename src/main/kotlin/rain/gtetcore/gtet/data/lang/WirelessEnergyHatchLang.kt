package rain.gtetcore.gtet.data.lang
import rain.gtetcore.gtet.common.machine.multiblock.part.WirelessEnergyHatchPartMachine
import rain.gtetcore.gtet.data.lang.WirelessEnergyHatchLang.BOTTLE_UNBOUND
import rain.gtetcore.gtet.data.lang.WirelessEnergyHatchLang.BOUND
import rain.gtetcore.gtet.data.lang.WirelessEnergyHatchLang.TOOLTIP
import rain.gtetcore.gtet.data.lang.WirelessEnergyHatchLang.UNBOUND
import rain.gtetcore.gtet.data.lang.WirelessEnergyHatchLang.WRONG_DIMENSION
import rain.gtetcore.gtet.data.lang.WirelessEnergyHatchLang.init
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 无线能源仓用到的双语条目。
 *
 * 分三类：
 * 1. **[TOOLTIP]** —— 方块物品提示里那条「说明损耗与绑定方式」的行（110 个变体**共用一条键**：
 *    内容与档位无关，逐档各写一条只会让 `zh_cn.json` 白胖 110 行）；
 * 2. **绑定手势的聊天提示**（[BOUND] / [UNBOUND] / [BOTTLE_UNBOUND] / [WRONG_DIMENSION]）
 *    —— 运行时按坐标插值，只能在服务端求值后发给玩家；
 * 3. **控制器面板里的状态行**（`DISPLAY_*`）—— 由 `IMultiPart#addMultiText` 聚合显示，
 *    见 [WirelessEnergyHatchPartMachine.addMultiText]。
 *
 * 登记时机：[init] 由 `ETWirelessEnergyHatches.register` 调用一次 —— 那一步发生在机器注册期、
 * 一定早于数据生成（与 `TimeFlowHatchLang` / `MasterTowerLang` 同一套约定）。
 *
 * @author rain fox
 */
object WirelessEnergyHatchLang {

    private const val PREFIX = "gtetcore.wireless_energy_hatch."

    /**
     * 方块提示里那条共用说明行。
     *
     * ⚠️ 键名照本仓惯例写成 `gtetcore.machine.<id>.tooltip.0` 会变成 110 条重复文案，
     * 所以这里用**一个共用的键**（内容与档位无关），逐档的差异只体现在名字（电压 + 安培）里。
     */
    const val TOOLTIP: String = PREFIX + "tooltip.0"

    /** 绑定成功的聊天提示（带塔坐标）。 */
    const val BOUND: String = PREFIX + "bound"

    /** 解绑成功的聊天提示。 */
    const val UNBOUND: String = PREFIX + "unbound"

    /** 手里的瓶子还没绑塔时的提示。 */
    const val BOTTLE_UNBOUND: String = PREFIX + "bottle_unbound"

    /** 瓶子上绑的塔在别的维度时的提示（本期不做跨维度供电）。 */
    const val WRONG_DIMENSION: String = PREFIX + "wrong_dimension"

    /** 面板：缓冲 / 容量 / 本档每 tick 上限。 */
    const val DISPLAY_BUFFER: String = PREFIX + "display.buffer"

    /** 面板：已接上的塔坐标、距离与损耗明细。 */
    const val DISPLAY_LINK: String = PREFIX + "display.link"

    /** 面板：未接线。 */
    const val DISPLAY_UNBOUND: String = PREFIX + "display.unbound"

    /** 面板：塔在别的维度。 */
    const val DISPLAY_WRONG_DIMENSION: String = PREFIX + "display.wrong_dimension"

    /** 面板：塔不在 / 没成型。 */
    const val DISPLAY_NO_TOWER: String = PREFIX + "display.no_tower"

    /** 面板：没有取用权限。 */
    const val DISPLAY_NO_PERMISSION: String = PREFIX + "display.no_permission"

    /** 由 `ETWirelessEnergyHatches.register` 调用一次（必须在数据生成之前，见类注释）。 */
    @JvmStatic
    fun init() {
        LangUtil.add(
            TOOLTIP,
            "Converts time flow from the master tower into EU. Base loss is 8% at IV, dropping " +
                "linearly to 2% at MAX; the first 128 blocks (8 chunks) are loss-free, then up to +15% more by 4096 blocks. " +
                "Right-click with a time bottle bound to a master tower to bind this hatch. " +
                "Cross-dimension supply is not implemented yet (needs a link tower), so another dimension never supplies EU.",
            "把主控塔的时间流换成 EU。基础损耗按档位：IV 8% 起、逐档线性降到 MAX 2%；" +
                "同维度前 128 格（8 区块）免计距离税，之后到 4096 格封顶再加最多 +15%。" +
                "手持已绑定主控塔的时序之瓶右键本仓即可绑定（潜行右键解绑）。" +
                "跨维度供能尚未实现（需要连接塔），所以塔在别的维度时一律不供电。"
        )
        LangUtil.add(
            BOUND,
            "Wireless energy hatch bound to master tower at (%s, %s, %s)",
            "无线能源仓已绑定主控塔：(%s, %s, %s)"
        )
        LangUtil.add(UNBOUND, "Wireless energy hatch unbound", "无线能源仓已解除绑定")
        LangUtil.add(
            BOTTLE_UNBOUND,
            "Bind the time bottle to a master tower first",
            "请先把时序之瓶绑定到主控塔"
        )
        LangUtil.add(
            WRONG_DIMENSION,
            "That master tower is in another dimension - cross-dimension supply is not implemented yet",
            "那座主控塔在别的维度 —— 跨维度供能尚未实现（需要连接塔）"
        )
        LangUtil.add(
            DISPLAY_BUFFER,
            "Buffer: %s / %s EU (throughput %s EU/t at this tier)",
            "缓冲：%s / %s EU（本档每 tick 上限 %s EU）"
        )
        LangUtil.add(
            DISPLAY_LINK,
            "Master tower: (%s, %s, %s), %s blocks away, loss %s%% (base %s%% + distance %s%%)",
            "主控塔：(%s, %s, %s)，距离 %s 格，损耗 %s%%（基础 %s%% + 距离 %s%%）"
        )
        LangUtil.add(
            DISPLAY_UNBOUND,
            "Not wired: right-click with a time bottle bound to a master tower to bind this hatch",
            "未接线：手持已绑定主控塔的时序之瓶右键本仓完成绑定"
        )
        LangUtil.add(
            DISPLAY_WRONG_DIMENSION,
            "Not supplying: the master tower is in another dimension (cross-dimension needs a link tower, not implemented yet)",
            "不供电：主控塔在别的维度（跨维度供能需要连接塔，尚未实现）"
        )
        LangUtil.add(
            DISPLAY_NO_TOWER,
            "Not supplying: no formed master tower there (chunk not loaded, or that block is no longer a tower)",
            "不供电：该位置没有已成型的主控塔（区块未加载，或那一格已不是塔）"
        )
        LangUtil.add(
            DISPLAY_NO_PERMISSION,
            "Not supplying: the placer is not allowed to draw from that master tower",
            "不供电：本仓的放置者没有那座主控塔的取用权限"
        )
    }
}