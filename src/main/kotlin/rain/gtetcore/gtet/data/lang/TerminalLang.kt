package rain.gtetcore.gtet.data.lang
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 高级终端扩展用到的双语条目（独立成类，因为它原来挂在已删除的自建终端上）。
 *
 * 由 `rain.gtetcore.gtet.init.CommonProxy` 在 mod 构造阶段调用，早于数据生成。
 *
 * @author rain fox
 */
object TerminalLang {

    const val UI_GROUPS: String = "gtetcore.terminal.ui.groups"
    const val UI_NO_GROUPS: String = "gtetcore.terminal.ui.no_groups"
    const val UI_CYCLE: String = "gtetcore.terminal.ui.cycle"

    /** 幂等注册。 */
    @JvmStatic
    fun init() {
        LangUtil.add(UI_GROUPS, "Tiered blocks", "分级方块")
        LangUtil.add(UI_NO_GROUPS, "Shift+right-click a controller to scan first", "先 Shift+右键控制器扫描一次")
        LangUtil.add(UI_CYCLE, "Next", "切换")
    }
}