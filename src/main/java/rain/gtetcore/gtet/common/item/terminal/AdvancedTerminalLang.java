package rain.gtetcore.gtet.common.item.terminal;

import rain.gtetcore.gtet.util.lang.LangUtil;

/**
 * 高级终端的全部语言键（设置面板 / 两块分级面板 / AE 绑定提示）。
 *
 * <p>键前缀统一是 {@code item.gtetcore.advanced_terminal}，用 {@code …} 代替写在下表里。
 * 由 {@link rain.gtetcore.gtet.init.CommonProxy} 在 mod 构造阶段调用，早于数据生成。
 *
 * @author rain fox
 */
public final class AdvancedTerminalLang {

    /** 语言键前缀。 */
    public static final String PREFIX = "item.gtetcore.advanced_terminal";

    public static final String TITLE = PREFIX + ".setting.title";
    public static final String SETTING_1 = PREFIX + ".setting.1";
    public static final String SETTING_1_TIP = PREFIX + ".setting.1.tooltip";
    public static final String SETTING_2 = PREFIX + ".setting.2";
    public static final String SETTING_2_TIP = PREFIX + ".setting.2.tooltip";
    public static final String SETTING_3 = PREFIX + ".setting.3";
    public static final String SETTING_3_TIP = PREFIX + ".setting.3.tooltip";
    public static final String SETTING_4 = PREFIX + ".setting.4";
    public static final String SETTING_4_TIP = PREFIX + ".setting.4.tooltip";
    public static final String SETTING_5 = PREFIX + ".setting.5";
    public static final String SETTING_5_TIP = PREFIX + ".setting.5.tooltip";
    public static final String SETTING_6 = PREFIX + ".setting.6";
    public static final String SETTING_6_TIP = PREFIX + ".setting.6.tooltip";
    public static final String SETTING_7 = PREFIX + ".setting.7";
    public static final String SETTING_7_TIP = PREFIX + ".setting.7.tooltip";
    public static final String SETTING_8 = PREFIX + ".setting.8";
    public static final String SETTING_8_TIP = PREFIX + ".setting.8.tooltip";

    public static final String PANEL_CYCLE = PREFIX + ".panel.cycle";
    public static final String PANEL_CYCLE_TIP = PREFIX + ".panel.cycle.tooltip";
    public static final String PANEL_CHOOSE = PREFIX + ".panel.choose";
    public static final String PANEL_PICK_TIP = PREFIX + ".panel.pick.tooltip";
    public static final String PANEL_EMPTY = PREFIX + ".panel.empty";

    /** AE 绑定：成功 / 解除 / 失败（三种失败原因）。 */
    public static final String AE_BOUND = PREFIX + ".ae.bound";
    public static final String AE_UNBOUND = PREFIX + ".ae.unbound";
    public static final String AE_FAIL_INACTIVE = PREFIX + ".ae.fail.inactive";
    public static final String AE_FAIL_RANGE = PREFIX + ".ae.fail.range";
    public static final String AE_FAIL_OTHER = PREFIX + ".ae.fail.other";
    /** AE 绑定/取料相关的杂项提示。 */
    public static final String BUILD_TOO_MANY = PREFIX + ".build.too_many";

    private AdvancedTerminalLang() {}

    /** 幂等注册（同名键重复登记只是覆盖同一张表）。 */
    public static void init() {
        // ⚠️ 物品名（{@link #PREFIX} 本身）由 Registrate 的 itemAndLang 生成，这里**不要**再写一遍，
        // 否则数据生成会因为「重复的翻译键」直接失败。

        LangUtil.add(TITLE, "Advanced Terminal Setting", "高级终端设置");

        LangUtil.add(SETTING_1, "Coil level", "线圈等级");
        LangUtil.add(SETTING_1_TIP, "Set the priority level for automatic coil placement.",
                "设置优先自动放置的线圈等级。");
        LangUtil.add(SETTING_2, "Number of repetitions of the structure", "重复结构次数");
        LangUtil.add(SETTING_2_TIP,
                "Used to set the number of repetitions for the placement of repeating parts in structures like distillation towers, assembly lines, etc.",
                "用于设置可重复结构(蒸馏塔、装配线等)的重复部分放置次数");
        LangUtil.add(SETTING_3, "No Hatch mode", "无仓室模式");
        LangUtil.add(SETTING_3_TIP,
                "Whether to enable the no-Hatch mode. After enabling the no-chamber mode, various Hatch will not be placed when they are not unique.",
                "是否启用无仓室模式。启用无仓室模式后不会在非唯一时放置各种仓室。");
        LangUtil.add(SETTING_4, "Coil replace mode", "线圈替换模式");
        LangUtil.add(SETTING_4_TIP,
                "Whether to enable the coil-replace mode. After enabling the coil-replace mode, coil will be replaced by the Coil before.",
                "是否启用线圈替换模式。启用线圈替换模式会将所有线圈替换为线圈等级中指定的线圈。");
        LangUtil.add(SETTING_5, "Is use AE items", "使用AE物品");
        LangUtil.add(SETTING_5_TIP,
                "Whether to enable to use items in AE. After enabling this, you can autobuild with items in AE storage by an ME terminal.",
                "是否使用AE物品。使用AE物品开启后，会通过背包中的AE终端连接到相应的AE网络并使用其中的物品来进行建造。");
        LangUtil.add(SETTING_6, "Mirror build", "镜像搭建");
        LangUtil.add(SETTING_6_TIP,
                "Whether to enable the mirror build. After enabling this, the structure is placed mirrored (flipped left/right or front/back).",
                "是否启用镜像搭建。启用后结构按镜像摆放（左右或前后翻转）。");
        LangUtil.add(SETTING_7, "Module build", "模块搭建");
        LangUtil.add(SETTING_7_TIP,
                "Which structure to build: 0 = main structure, N = the N-th structure. If the controller only has the main structure, the main structure is built.",
                "选择要搭建的结构：0 = 主结构，N = 第 N 套结构。控制器只有主结构时按主结构搭建。");
        LangUtil.add(SETTING_8, "Demolition mode", "拆除模式");
        LangUtil.add(SETTING_8_TIP,
                "Whether to enable the demolition mode. After enabling this, blocks are no longer placed: the structure blocks at each position are removed instead. Blocks that do not belong to this structure are left untouched.",
                "是否启用拆除模式。启用后不再放置方块，而是按结构把当前位置的结构方块拆掉。不属于本结构的方块不会被拆除。");

        LangUtil.add(PANEL_CYCLE, "Tiered blocks (switch)", "分级方块（切换）");
        LangUtil.add(PANEL_CYCLE_TIP, "Switch to the next candidate block of this tier group.",
                "切换到本分级组的下一个候选方块。");
        LangUtil.add(PANEL_CHOOSE, "Tiered blocks (choose)", "分级方块（勾选）");
        LangUtil.add(PANEL_PICK_TIP, "Click to show this tier group in the panel below.",
                "点击后下方面板改为显示这一组的分级方块。");
        LangUtil.add(PANEL_EMPTY, "Shift+right-click a controller to scan the structure first",
                "先 Shift+右键控制器扫描结构");

        LangUtil.add(AE_BOUND, "AE network linked: %s", "已绑定 AE 网络：%s");
        LangUtil.add(AE_UNBOUND, "AE network link cleared", "已解除 AE 网络链接");
        LangUtil.add(AE_FAIL_INACTIVE, "Wireless access point is not active", "无线接入点未激活");
        LangUtil.add(AE_FAIL_RANGE, "Out of range of the access point / ME block", "超出接入点/ME 方块的覆盖范围");
        LangUtil.add(AE_FAIL_OTHER, "No AE network available at that position", "该位置没有可用的 AE 网络");
        LangUtil.add(BUILD_TOO_MANY, "Structure is too large for one build: %s blocks (limit %s)",
                "结构过大，一次搭建的方块上限：%s 格（上限 %s）");
    }
}
