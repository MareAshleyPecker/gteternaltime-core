package rain.gtetcore.gtet.data.lang
import rain.gtetcore.gtet.data.lang.TimeFlowHatchLang.BOTTLE_UNBOUND
import rain.gtetcore.gtet.data.lang.TimeFlowHatchLang.BOUND
import rain.gtetcore.gtet.data.lang.TimeFlowHatchLang.RECIPE_CAPABILITY_NAME
import rain.gtetcore.gtet.data.lang.TimeFlowHatchLang.TOOLTIP
import rain.gtetcore.gtet.data.lang.TimeFlowHatchLang.UNBOUND
import rain.gtetcore.gtet.data.lang.TimeFlowHatchLang.WRONG_DIMENSION
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 时序仓（TF 供给仓）用到的双语条目。
 *
 * 分两类：
 * 1. **配方能力名** [RECIPE_CAPABILITY_NAME] —— 不是本仓自己拼的键，而是 GTM 的
 *    `RecipeCapability#getName()` 用 `"recipe.capability.%s.name".formatted(name)` 拼出来的
 *    （javap 实证：`RecipeCapability` 的 `getName()` 里就是这个格式串），
 *    `name` 就是我们注册 TF 能力时用的 `time_flow`（见 `ETTimeFlowCapability.NAME`）。
 *    GTM 自带的 5 个能力（eu / fluid / item / cwu / block_state）在 GTM 自己的 `LangHandler` 里有译文，
 *    **addon 能力没有** ⇒ 不登记这条键时，TF 不足的报错会直接把原始 key
 *    （`recipe.capability.time_flow.name`）显示给玩家。这里补上。
 * 2. **绑定手势与提示**（[BOUND] / [UNBOUND] / [BOTTLE_UNBOUND] / [WRONG_DIMENSION] / [TOOLTIP]）
 *    —— 运行时按坐标插值，只能在服务端求值后发给玩家。
 *
 * 登记时机：由 `CommonProxy#initLang` 调用一次 —— 必须在**数据生成之前**，
 * 与 `TimeBottleLang` / `MasterTowerLang` 同一套约定。
 */
object TimeFlowHatchLang {

    /** TF 的配方能力显示名（键由 GTM 的 `RecipeCapability#getName()` 拼出，见类注释）。 */
    const val RECIPE_CAPABILITY_NAME: String = "recipe.capability.time_flow.name"

    private const val PREFIX = "gtetcore.time_flow_hatch."

    /** 方块 tooltip：怎么把仓绑到主控塔。 */
    const val TOOLTIP: String = PREFIX + "tooltip.0"

    /** 绑定成功的聊天提示（带塔坐标）。 */
    const val BOUND: String = PREFIX + "bound"

    /** 解绑成功的聊天提示。 */
    const val UNBOUND: String = PREFIX + "unbound"

    /** 手里的瓶子还没绑塔时的提示。 */
    const val BOTTLE_UNBOUND: String = PREFIX + "bottle_unbound"

    /** 瓶子上绑的塔在别的维度时的提示（本期不做跨维度供能）。 */
    const val WRONG_DIMENSION: String = PREFIX + "wrong_dimension"

    /** 由 `CommonProxy#initLang` 调用一次。 */
    @JvmStatic
    fun init() {
        // ① 配方能力名：TF 不足时报错文案里的那个名字
        LangUtil.add(RECIPE_CAPABILITY_NAME, "Time Flow", "时间流")

        // ② 绑定手势与 tooltip
        LangUtil.add(
            TOOLTIP,
            "Right-click with a time bottle that is bound to a master tower to bind this hatch",
            "手持已绑定主控塔的时序之瓶右键本仓即可绑定（潜行右键解绑）"
        )
        LangUtil.add(
            BOUND,
            "Time flow hatch bound to master tower at (%s, %s, %s)",
            "时序仓已绑定主控塔：(%s, %s, %s)"
        )
        LangUtil.add(UNBOUND, "Time flow hatch unbound", "时序仓已解除绑定")
        LangUtil.add(
            BOTTLE_UNBOUND,
            "Bind the time bottle to a master tower first",
            "请先把时序之瓶绑定到主控塔"
        )
        LangUtil.add(
            WRONG_DIMENSION,
            "That master tower is in another dimension - a time flow hatch only draws from a tower in its own dimension",
            "那座主控塔在别的维度 —— 时序仓只从同维度的塔取用"
        )
    }
}