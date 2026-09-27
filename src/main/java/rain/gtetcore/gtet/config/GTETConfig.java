package rain.gtetcore.gtet.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.antlr.v4.parse.GrammarTreeVisitor;
import rain.gtetcore.gtet.Gtetcore;
import rain.gtetcore.gtet.util.lang.Bilingual;
import rain.gtetcore.gtet.util.lang.ConfigLangRegistry;

/**
 * GTETCore 配置 —— 走 Forge 自带的 {@link ForgeConfigSpec}（{@link ModConfig.Type#COMMON}）。
 *
 * <p>生成 {@code config/gtetcore/gtetcore-common.toml} —— 放在本 mod 自己的子目录里，
 * 免得和整合包里其它 mod 的配置文件在 config 根目录混成一堆；
 * 读写、取值域校验，以及改完文件后的热重载全部由 Forge 负责（改完即时生效，无需重启）。
 *
 * <p><b>和旧实现的差别：</b>1.20.1 的 Forge 不带配置界面，Mods 列表里的 Config 按钮不会再弹窗。
 * 旧的 {@code dev.toma.configuration} 实现（含游戏内界面）原样留在
 * {@code GTETConfig.java.bak} / {@code ConfigLangRegistry.java.bak}，需要界面时可按那两个文件切回去。
 *
 * <p>注册入口是 {@link #init(ModLoadingContext)}，在 {@link rain.gtetcore.gtet.init.CommonProxy}
 * 构造阶段调用一次（Forge 要求 {@code registerConfig} 必须在这个阶段完成）。
 * 参数用的是 Forge 注入进 {@code Gtetcore} 构造器的 {@link ModLoadingContext}
 * （实际类型是 {@code FMLJavaModLoadingContext}），而不是已被标记「待删除」的
 * {@code ModLoadingContext.get()}。
 *
 * <p>GTM 的补丁类（{@code MultiblockControllerMachine}、{@code MultiblockWorldSavedData}）在运行时
 * 反射调用 {@link rain.gtetcore.gtet.util.MultiblockConfigHook}，再由它经下面几个静态读取方法取值；
 * 反射失败（例如本 mod 缺席）时 GTM 会回退到自带的默认值，
 * <b>因此这里的默认值必须与那些回退值保持一致</b>。
 *
 * @author rain fox
 */
@Mod.EventBusSubscriber(modid = Gtetcore.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GTETConfig {

    private GTETConfig(ModLoadingContext context) {}

    // ================================================================
    //  默认值 —— 与 GTM 补丁中反射失败时的回退值保持一致
    // ================================================================

    /** 结构检测失败后的重试间隔默认值（tick）。 */
    public static final int DEFAULT_CHECK_FAILED_WAITING_TIME = 10;

    /** 放置后首次结构检测的延迟默认值（tick）。 */
    public static final int DEFAULT_PLACEMENT_CHECK_DELAY = 20;

    /** 控制器卸载后的等待默认值（tick）。 */
    public static final int DEFAULT_UNLOAD_WAITING_TIME = 1;

    /** 异步检测线程调度间隔默认值（毫秒）。 */
    public static final int DEFAULT_ASYNC_CHECK_INTERVAL = 250;

    /** 是否发送成型失败信息的默认值。 */
    public static final boolean DEFAULT_SEND_FORM_ERROR_MESSAGE = true;

    /**
     * 部件是否可跨多方块共享的默认值：{@code false} = 保持隔离（与手写死的 {@code canShared() = false} 一致）。
     */
    public static final boolean DEFAULT_PARTS_SHAREABLE = false;

    public static final boolean DEFAULT_SEND_THREAD = false;

    /** 是否启用结构导出模式的默认值。 */
    public static final boolean DEFAULT_EXPORT_MODE_ENABLED = true;

    /** 结构导出目录的默认值（相对游戏目录）；两类导出统一收在 GtetExport/ 下。 */
    public static final String DEFAULT_EXPORT_DIRECTORY = "GtetExport/multiblock";

    /** 配方代码导出目录的默认值（相对游戏目录）。 */
    public static final String DEFAULT_RECIPE_EXPORT_DIRECTORY = "GtetExport/recipes";

    /** 结构工具「选区导出」覆盖层颜色默认值：绿色线框 + 淡绿填充（{@code R;G;B;线透明度;填充透明度}）。 */
    public static final String DEFAULT_WRITE_OVERLAY_COLOR = "0.2;0.9;0.2;1.0;0.15";

    /** 结构工具「结构检测」错误位置颜色默认值：蓝色线框（{@code R;G;B;透明度}）。 */
    public static final String DEFAULT_DETECT_OVERLAY_COLOR = "0.2;0.4;1.0;1.0";

    /** 结构检测错误框的停留时间默认值（秒）；{@code 0} 表示不自动消失。 */
    public static final int DEFAULT_DETECT_BOX_LIFETIME = 10;

    // ---- 时间流（TF）----

    /** 主控塔是否全服唯一的默认值。 */
    public static final boolean DEFAULT_MASTER_TOWER_UNIQUE = true;

    /** 时序潮汐半幅 {@code a} 的默认值：{@code 0} = 关闭潮汐。 */
    public static final double DEFAULT_TIDE_AMPLITUDE = 0.0;

    /**
     * 时序潮汐半幅 {@code a} 的硬上限。
     *
     * <p>反套利闭式解 {@code a <= (1 - eta) / (1 + eta)}；往返效率 {@code eta = 0.8} 时上限正好 11.11%。
     * 超过这个值就会出现「便宜的时候买、贵的时候卖」的套利通道。
     */
    public static final double MAX_TIDE_AMPLITUDE = 0.1111;

    /** 时序潮汐周期 {@code T} 的默认值（tick）：1 游戏日 = 24000 tick。 */
    public static final int DEFAULT_TIDE_PERIOD = 24000;

    /** 超频仓系数 {@code k} 的默认值：付一半能量价值（五折），鼓励建塔。 */
    public static final double DEFAULT_OVERCLOCK_FACTOR_K = 0.5;

    /** 时序之瓶系数 {@code K_hand} 的默认值。 */
    public static final double DEFAULT_TIME_BOTTLE_HAND_FACTOR = 4.0;

    /**
     * 主控塔「每段塔身容量」的**占位默认值**（TF）—— <b>待定稿，不是最终数值</b>。
     *
     * <p>容量 = 塔身段数 × 本值；段数上限见 {@link #DEFAULT_TOWER_MAX_SEGMENTS}，
     * 因此占位期的容量上限是 {@code 10 × 1,000,000 = 10,000,000 TF}。
     */
    public static final long DEFAULT_TOWER_SEGMENT_CAPACITY = 1_000_000L;

    /** 主控塔段数上限的默认值（结构最多往外接几段塔身）。 */
    public static final int DEFAULT_TOWER_MAX_SEGMENTS = 10;

    /** 主控塔白名单的默认值：空串 = 只有所有者能用。 */
    public static final String DEFAULT_TOWER_WHITELIST = "";

    // ================================================================
    //  配置定义
    // ================================================================

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ---- 多方块结构检测 ----

    /** 结构检测失败后，控制器再次尝试检测前的等待 tick 数。 */
    @Bilingual(en = "Check Retry Interval", cn = "检测重试间隔")
    public static final ForgeConfigSpec.IntValue CHECK_FAILED_WAITING_TIME;

    /** 多方块放置 / 加载后，首次结构检测的延迟 tick 数。 */
    @Bilingual(en = "Placement Check Delay", cn = "放置检测延迟")
    public static final ForgeConfigSpec.IntValue PLACEMENT_CHECK_DELAY;

    /** 控制器卸载（区块卸载 / 机器被移除）时的等待 tick 数。 */
    @Bilingual(en = "Unload Waiting Time", cn = "卸载等待时间")
    public static final ForgeConfigSpec.IntValue UNLOAD_WAITING_TIME;

    /** 异步结构检测线程的调度间隔（毫秒）。 */
    @Bilingual(en = "Async Check Interval", cn = "异步检测间隔")
    public static final ForgeConfigSpec.IntValue ASYNC_CHECK_INTERVAL;

    /** 成型失败时是否把错误信息发送给玩家。 */
    @Bilingual(en = "Send Form Error Message", cn = "发送成型错误信息")
    public static final ForgeConfigSpec.BooleanValue SEND_FORM_ERROR_MESSAGE;

    /**
     * 部件是否可以跨多方块共享 —— **全局主开关**，默认 {@code false} = 隔离。
     *
     * <p>本 mod 的部件原本都把 {@code canShared()} 写死成 {@code false}（防串配方），
     * ME 库存族则由玩家在面板上单独拨动；这个开关是那道闸门的兜底。
     *
     * <p><b>⚠️ 打开它有三条代价：</b>
     * <ol>
     * <li>同一格部件可被两个已成型结构同时占用 ⇒ 会**重新出现「串配方」风险**
     * （标签、定量、库存列表都是每件独立的，两个控制器会读到同一份，
     * 原因写在各部件类的 {@code canShared()} 注释里）；</li>
     * <li><b>只在下一次结构检测时生效</b>：{@code canShared()} 只在
     * {@code BlockPattern#checkPatternAt} 那一刻被读，所以改配置本身不会让已成型结构立刻复检；</li>
     * <li><b>多人服慎开。</b></li>
     * </ol>
     */
    @Bilingual(en = "Parts Shareable", cn = "部件可共享")
    public static final ForgeConfigSpec.BooleanValue PARTS_SHAREABLE;

    // ---- 开发者选项 ----

    /** 是否启用「结构导出」工作模式。 */
    @Bilingual(en = "Export Mode", cn = "结构导出模式")
    public static final ForgeConfigSpec.BooleanValue EXPORT_MODE_ENABLED;

    /** 导出文件的输出目录（相对游戏目录）。 */
    @Bilingual(en = "Export Directory", cn = "结构导出目录")
    public static final ForgeConfigSpec.ConfigValue<String> EXPORT_DIRECTORY;

    /** 配方编辑器导出代码的目录（相对游戏目录）。 */
    @Bilingual(en = "Recipe Export Directory", cn = "配方导出目录")
    public static final ForgeConfigSpec.ConfigValue<String> RECIPE_EXPORT_DIRECTORY;

    @Bilingual(en = "send thread diagnose", cn = "发送线程诊断")
    public static final  ForgeConfigSpec.BooleanValue SEND_THREAD;
    // ---- 结构工具覆盖层颜色（客户端渲染） ----

    /** 选区导出覆盖层颜色：{@code R;G;B}，可再跟「线透明度;填充透明度」。 */
    @Bilingual(en = "Export Overlay Color", cn = "导出选区颜色")
    public static final ForgeConfigSpec.ConfigValue<String> WRITE_OVERLAY_COLOR;

    /** 结构检测错误位置颜色：{@code R;G;B}，可再跟透明度。 */
    @Bilingual(en = "Detect Overlay Color", cn = "检测错误颜色")
    public static final ForgeConfigSpec.ConfigValue<String> DETECT_OVERLAY_COLOR;

    /** 结构检测标出的错误框显示多少秒后自动消失（{@code 0} = 不自动消失）。 */
    @Bilingual(en = "Detect Box Lifetime", cn = "检测框停留时间")
    public static final ForgeConfigSpec.IntValue DETECT_BOX_LIFETIME;

    // ---- 时间流（TF）----

    /** 主控塔是否全服唯一。 */
    @Bilingual(en = "Master Tower Unique", cn = "主控塔全服唯一")
    public static final ForgeConfigSpec.BooleanValue MASTER_TOWER_UNIQUE;

    /** 时序潮汐半幅 {@code a}：{@code 0} = 关闭。 */
    @Bilingual(en = "Tide Amplitude", cn = "潮汐半幅")
    public static final ForgeConfigSpec.DoubleValue TIDE_AMPLITUDE;

    /** 时序潮汐周期 {@code T}（tick）。 */
    @Bilingual(en = "Tide Period", cn = "潮汐周期")
    public static final ForgeConfigSpec.IntValue TIDE_PERIOD;

    /** 超频仓系数 {@code k}。 */
    @Bilingual(en = "Overclock Factor K", cn = "超频仓系数")
    public static final ForgeConfigSpec.DoubleValue OVERCLOCK_FACTOR_K;

    /** 时序之瓶系数 {@code K_hand}。 */
    @Bilingual(en = "Time Bottle Hand Factor", cn = "时序之瓶系数")
    public static final ForgeConfigSpec.DoubleValue TIME_BOTTLE_HAND_FACTOR;

    /** 主控塔每段塔身的容量（TF）。 */
    @Bilingual(en = "Tower Segment Capacity", cn = "塔身每段容量")
    public static final ForgeConfigSpec.LongValue TOWER_SEGMENT_CAPACITY;

    /** 主控塔的结构段数上限。 */
    @Bilingual(en = "Tower Max Segments", cn = "塔身段数上限")
    public static final ForgeConfigSpec.IntValue TOWER_MAX_SEGMENTS;

    /** 主控塔白名单（逗号分隔的玩家 UUID）。 */
    @Bilingual(en = "Tower Whitelist", cn = "主控塔白名单")
    public static final ForgeConfigSpec.ConfigValue<String> TOWER_WHITELIST;

    /** 配置规格；Forge 用它读写 {@code config/gtetcore/gtetcore-common.toml}。 */
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.comment("多方块结构检测相关（时间单位：tick）",
                        "Multiblock pattern checking (time unit: ticks)")
                .push("multiblock");

        CHECK_FAILED_WAITING_TIME = BUILDER
                .comment("结构检测失败后，控制器再次尝试检测前的等待 tick 数。",
                        "Ticks a controller waits before retrying a failed pattern check.")
                .defineInRange("checkFailedWaitingTime", DEFAULT_CHECK_FAILED_WAITING_TIME, 1, 1200);

        PLACEMENT_CHECK_DELAY = BUILDER
                .comment("多方块放置或区块加载后，首次结构检测的延迟 tick 数。",
                        "Ticks to delay the first pattern check after a multiblock is placed or loaded.")
                .defineInRange("placementCheckDelay", DEFAULT_PLACEMENT_CHECK_DELAY, 0, 1200);

        UNLOAD_WAITING_TIME = BUILDER
                .comment("控制器卸载时的等待 tick 数，用于避免区块反复加载导致的抖动。",
                        "Ticks to wait while unloading a controller, avoids thrashing on chunk reload.")
                .defineInRange("unloadWaitingTime", DEFAULT_UNLOAD_WAITING_TIME, 1, 1200);

        ASYNC_CHECK_INTERVAL = BUILDER
                .comment("异步结构检测线程的调度间隔（毫秒）。数值越小检测越及时，但越吃 CPU。",
                        "Interval in milliseconds between async multiblock check runs.",
                        "Smaller values react faster but cost more CPU.")
                .defineInRange("asyncCheckInterval", DEFAULT_ASYNC_CHECK_INTERVAL, 1, 10000);

        SEND_FORM_ERROR_MESSAGE = BUILDER
                .comment("多方块成型失败时，是否把错误信息发送给玩家。",
                        "Whether to send pattern failure messages to players.")
                .define("sendFormErrorMessage", DEFAULT_SEND_FORM_ERROR_MESSAGE);

        PARTS_SHAREABLE = BUILDER
                .comment("部件是否可以跨多方块共享：false = 隔离（默认，防串配方）；true = 允许共享。",
                        "⚠️ 打开后有三条代价：① 同一格部件可被两个已成型结构同时占用，会重新出现「串配方」风险；",
                        "② 只在下一次结构检测时生效（canShared() 只在结构检查那一刻被读，改配置不会立刻复检）；",
                        "③ 多人服慎开。",
                        "Whether parts may be shared among multiple multiblocks. false = isolated (default);",
                        "true = sharing allowed. WARNING, turning this on: (1) lets one part block be occupied by",
                        "two formed structures at once, which brings back recipe mixups; (2) only takes effect on the",
                        "NEXT pattern check, because canShared() is read only while a pattern is being checked;",
                        "(3) use with care on public servers.")
                .define("partsShareable", DEFAULT_PARTS_SHAREABLE);

        BUILDER.pop();

        String a;

        BUILDER.comment("开发者选项", "Developer options").push("dev");

        EXPORT_MODE_ENABLED = BUILDER
                .comment("是否启用结构导出工作模式；关闭后结构工具的循环里不再出现这一档。",
                        "Whether the area-export work mode is available in the structure tool.")
                .define("exportModeEnabled", DEFAULT_EXPORT_MODE_ENABLED);

        EXPORT_DIRECTORY = BUILDER
                .comment("结构导出文件的输出目录（相对游戏目录）。",
                        "Output directory for exported block patterns (relative to the game directory).")
                .define("exportDirectory", DEFAULT_EXPORT_DIRECTORY);

        RECIPE_EXPORT_DIRECTORY = BUILDER
                .comment("配方编辑器导出代码的目录（相对游戏目录）；每个配方一个 .kt 片段，重复导出直接覆盖。",
                        "Output directory for exported recipe code (relative to the game directory).",
                        "One .kt snippet per recipe; re-exporting overwrites it.")
                .define("recipeExportDirectory", DEFAULT_RECIPE_EXPORT_DIRECTORY);

        SEND_THREAD = BUILDER
                .comment("是否在日志里发送线程诊断。",
                        "Whether to send thread diagnostics in the log.")
                        .define("SendThreadDiagnosticlog", DEFAULT_SEND_THREAD);

        BUILDER.pop();

        String a01;

        BUILDER.comment("时间流（TF）：单位、时序潮汐与系数",
                        "Time flow (TF): unit pricing, tide and coefficients",
                        "1 TF = 1A IV x 1 tick = 8192 EU; 1 TF = 1 second (flow rate) => 1 hour = 3600 TF")
                .push("timeflow");

        MASTER_TOWER_UNIQUE = BUILDER
                .comment("主控塔是否全服唯一。true = 第二座不成型；false = 允许多座，每座各自独立记账。",
                        "从 false 改成 true 时已存在的多座塔不追溯，只是新塔不能再成型。",
                        "Whether the master tower is unique per server. true = a second tower will not form;",
                        "false = many towers are allowed, each keeping its own separate balance.",
                        "Switching false -> true does not retroactively remove existing towers.")
                .define("masterTowerUnique", DEFAULT_MASTER_TOWER_UNIQUE);

        TIDE_AMPLITUDE = BUILDER
                .comment("时序潮汐半幅 a：汇率 = 1 + a × sin(2π·t / T)，均值恒为 1；填 0 = 关闭潮汐。",
                        "上限 0.1111（11.11%）：往返效率 η = 0.8 时反套利闭式解 a ≤ (1-η)/(1+η) 的取值，",
                        "超过就会出现「便宜时买、贵时卖」的套利通道，所以配置层直接夹住。",
                        "Tide amplitude a: rate = 1 + a * sin(2*pi*t / T), mean is always 1; 0 disables the tide.",
                        "Capped at 0.1111 (11.11%) = (1-eta)/(1+eta) for a round-trip efficiency of 0.8.")
                .defineInRange("tideAmplitude", DEFAULT_TIDE_AMPLITUDE, 0.0, MAX_TIDE_AMPLITUDE);

        TIDE_PERIOD = BUILDER
                .comment("时序潮汐周期 T（tick），默认 24000 = 1 游戏日。",
                        "Tide period T in ticks; the default 24000 is one in-game day.")
                .defineInRange("tidePeriod", DEFAULT_TIDE_PERIOD, 1, 2400000);

        OVERCLOCK_FACTOR_K = BUILDER
                .comment("超频仓系数 k：每配方的时间流消耗 = (因超频多出的 EU ÷ 8192) × k，下限 1 TF。",
                        "默认 0.5 = 付一半能量价值（五折）。",
                        "Overclock hatch factor k: TF cost = (extra EU from overclocking / 8192) * k, floor 1 TF.",
                        "The default 0.5 charges half of the energy value.")
                .defineInRange("overclockFactorK", DEFAULT_OVERCLOCK_FACTOR_K, 0.0, 1000.0);

        TIME_BOTTLE_HAND_FACTOR = BUILDER
                .comment("时序之瓶系数 K_hand：手持加速的 TF 消耗 = (这次推进本该消耗的 EU ÷ 8192) × K_hand。",
                        "口径必须是 EU 而不是「秒」——按秒计时系数要抬到 80 才等价。默认 4 = 比走超频仓贵 8 倍。",
                        "Time bottle factor K_hand: TF cost = (EU the skipped progress would have consumed / 8192) * K_hand.",
                        "The unit must be EU, not seconds. The default 4 is 8x more expensive than an overclock hatch.")
                .defineInRange("timeBottleHandFactor", DEFAULT_TIME_BOTTLE_HAND_FACTOR, 0.0, 10000.0);

        TOWER_SEGMENT_CAPACITY = BUILDER
                .comment("主控塔【每段塔身】的容量（TF）。塔的总容量 = 塔身段数 × 本值，段数由结构决定。",
                        "⚠️ 默认值 1,000,000 是**占位值**，等最终平衡数值定稿后再改这里；不是定稿数值。",
                        "Capacity in TF of ONE tower segment. Total capacity = segment count * this value.",
                        "The default 1,000,000 is a PLACEHOLDER pending the final balance pass, not a final value.")
                .defineInRange("towerSegmentCapacity", DEFAULT_TOWER_SEGMENT_CAPACITY, 1L, Long.MAX_VALUE);

        TOWER_MAX_SEGMENTS = BUILDER
                .comment("主控塔最多能接多少段塔身（结构上限，同时决定总容量上限）。",
                        "Maximum number of tower segments the structure accepts.")
                .defineInRange("towerMaxSegments", DEFAULT_TOWER_MAX_SEGMENTS, 1, 64);

        TOWER_WHITELIST = BUILDER
                .comment("主控塔白名单：逗号分隔的玩家 UUID（例如 aaaa...,bbbb...）；留空表示只有所有者能取用。",
                        "所有者永远在白名单里，不用在这里重复写。",
                        "Tower whitelist: comma-separated player UUIDs. Empty means the owner only.",
                        "The owner is always allowed and does not need to be listed.")
                .define("towerWhitelist", DEFAULT_TOWER_WHITELIST);

        BUILDER.pop();

        BUILDER.comment("结构工具覆盖层：颜色与检测框停留时间（只在客户端渲染时用）",
                        "Structure tool overlay: colors and detect box lifetime (client-side rendering only)")
                .push("overlay");

        WRITE_OVERLAY_COLOR = BUILDER
                .comment("选区导出：线框 + 半透明填充的颜色。一行写完，格式 R;G;B，",
                        "后面可再按顺序跟「线透明度;填充透明度」，例如 0.2;0.9;0.2;1.0;0.15。",
                        "分量取值 0~1，用分号或逗号分隔；写错的颜色会退回默认值并在日志里提醒。",
                        "Export selection: wireframe + translucent fill color, one line as R;G;B",
                        "optionally followed by lineAlpha;fillAlpha (e.g. 0.2;0.9;0.2;1.0;0.15).")
                .define("writeColor", DEFAULT_WRITE_OVERLAY_COLOR);

        DETECT_OVERLAY_COLOR = BUILDER
                .comment("结构检测失败的位置：线框颜色。一行写完，格式 R;G;B，后面可再跟透明度，",
                        "例如 0.2;0.4;1.0;1.0。分量取值 0~1，用分号或逗号分隔。",
                        "Failed pattern positions: wireframe color, one line as R;G;B",
                        "optionally followed by alpha (e.g. 0.2;0.4;1.0;1.0).")
                .define("detectColor", DEFAULT_DETECT_OVERLAY_COLOR);

        DETECT_BOX_LIFETIME = BUILDER
                .comment("结构检测标出的错误位置：线框显示多少秒后自动消失（单位：秒）。",
                        "填 0 表示不自动消失，一直留到下一次检测为止。",
                        "Seconds the detected error boxes stay visible before disappearing.",
                        "0 keeps them until the next check.")
                .defineInRange("detectBoxLifetime", DEFAULT_DETECT_BOX_LIFETIME, 0, 3600);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    // ================================================================
    //  注册
    // ================================================================

    private static final Object LOCK = new Object();

    /**
     * 配置文件名，相对 {@code config/} 目录。
     *
     * <p>带一层本 mod 的子目录（{@code config/gtetcore/}）：Forge 会自己建目录，
     * 只要在这里写相对路径就行；热重载依旧有效（Forge 监听的是该文件所在目录）。
     */
    public static final String CONFIG_FILE_NAME = Gtetcore.MODID + "/" + Gtetcore.MODID + "-common.toml";

    private static boolean registered;

    /**
     * 把配置注册给 Forge；重复调用无副作用。
     *
     * @param context mod 构造器注入的 {@link ModLoadingContext}（实际是 {@code FMLJavaModLoadingContext}）；
     *                它知道自己属于哪个 mod，所以不需要（也不该）用已弃用的 {@code ModLoadingContext.get()}
     */
    public static void init(ModLoadingContext context) {
        synchronized (LOCK) {
            if (registered) return;
            registered = true;
            // COMMON：客户端 / 服务端各读各的，与旧实现一样不做服务端下发
            context.registerConfig(ModConfig.Type.COMMON, SPEC, CONFIG_FILE_NAME);
            // 顺手把每个选项的翻译键（gtetcore.configuration.*）登记进语言文件
            ConfigLangRegistry.register(SPEC, GTETConfig.class);
        }
    }

    // ================================================================
    //  静态读取入口 —— 配置未加载时回退到默认值，永不抛异常
    // ================================================================

    /** 结构检测失败后的重试间隔（tick）。 */
    public static int checkFailedWaitingTime() {return intValue(CHECK_FAILED_WAITING_TIME, DEFAULT_CHECK_FAILED_WAITING_TIME);}

    /** 放置后首次结构检测的延迟（tick）。 */
    public static int placementCheckDelay() {
        return intValue(PLACEMENT_CHECK_DELAY, DEFAULT_PLACEMENT_CHECK_DELAY);
    }

    /** 卸载等待（tick）。 */
    public static int unloadWaitingTime() {
        return intValue(UNLOAD_WAITING_TIME, DEFAULT_UNLOAD_WAITING_TIME);
    }

    /** 异步检测调度间隔（毫秒）。 */
    public static int asyncCheckInterval() {
        return intValue(ASYNC_CHECK_INTERVAL, DEFAULT_ASYNC_CHECK_INTERVAL);
    }

    /** 是否发送成型失败信息。 */
    public static boolean sendFormErrorMessage() { return booleanValue(SEND_FORM_ERROR_MESSAGE, DEFAULT_SEND_FORM_ERROR_MESSAGE);}

    /**
     * 部件是否可以跨多方块共享（默认 {@code false} = 隔离）。
     *
     * <p>被各部件类的 {@code canShared()} 读取：写死 {@code false} 的那几件直接返回本值，
     * ME 库存族则是「面板开关 OR 本值」。
     */
    public static boolean partsShareable() { return booleanValue(PARTS_SHAREABLE, DEFAULT_PARTS_SHAREABLE);}

    public static boolean SendThreadDiagnosticLog() {
        return booleanValue(SEND_THREAD,DEFAULT_SEND_THREAD);
    }

    /** 结构导出工作模式是否启用（以 dev 配置块里的开关为准）。 */
    public static boolean exportModeEnabled() {return booleanValue(EXPORT_MODE_ENABLED, DEFAULT_EXPORT_MODE_ENABLED);}

    /** 结构导出的输出目录。 */
    public static String exportDirectory() {
        return stringValue(EXPORT_DIRECTORY, DEFAULT_EXPORT_DIRECTORY);
    }

    /** 结构工具「选区导出」覆盖层颜色串（{@code R;G;B[;线透明度;填充透明度]}）。 */
    public static String writeOverlayColor() {
        return stringValue(WRITE_OVERLAY_COLOR, DEFAULT_WRITE_OVERLAY_COLOR);
    }

    /** 结构工具「结构检测」错误位置颜色串（{@code R;G;B[;透明度]}）。 */
    public static String detectOverlayColor() {
        return stringValue(DETECT_OVERLAY_COLOR, DEFAULT_DETECT_OVERLAY_COLOR);
    }

    /** 结构检测错误框的停留时间（秒）；{@code 0} 表示不自动消失。 */
    public static int detectBoxLifetime() {
        return intValue(DETECT_BOX_LIFETIME, DEFAULT_DETECT_BOX_LIFETIME);
    }

    /** 配方编辑器导出代码的目录。 */
    public static String recipeExportDirectory() {
        return stringValue(RECIPE_EXPORT_DIRECTORY, DEFAULT_RECIPE_EXPORT_DIRECTORY);
    }

    // ---- 时间流（TF）----

    /** 主控塔是否全服唯一（默认 {@code true}）。塔还没做，这里先把取值入口放好。 */
    public static boolean masterTowerUnique() {
        return booleanValue(MASTER_TOWER_UNIQUE, DEFAULT_MASTER_TOWER_UNIQUE);
    }

    /** 时序潮汐半幅 {@code a}；{@code 0} = 关闭潮汐（默认）。 */
    public static double tideAmplitude() {
        return doubleValue(TIDE_AMPLITUDE, DEFAULT_TIDE_AMPLITUDE);
    }

    /** 时序潮汐周期 {@code T}（tick），默认 24000。 */
    public static int tidePeriod() {
        return intValue(TIDE_PERIOD, DEFAULT_TIDE_PERIOD);
    }

    /** 超频仓系数 {@code k}，默认 0.5。 */
    public static double overclockFactorK() {
        return doubleValue(OVERCLOCK_FACTOR_K, DEFAULT_OVERCLOCK_FACTOR_K);
    }

    /** 时序之瓶系数 {@code K_hand}，默认 4。 */
    public static double timeBottleHandFactor() {
        return doubleValue(TIME_BOTTLE_HAND_FACTOR, DEFAULT_TIME_BOTTLE_HAND_FACTOR);
    }

    /** 主控塔每段塔身的容量（TF）；默认值是**占位值**，见常量注释。 */
    public static long towerSegmentCapacity() {
        return longValue(TOWER_SEGMENT_CAPACITY, DEFAULT_TOWER_SEGMENT_CAPACITY);
    }

    /** 主控塔结构段数上限，默认 10。 */
    public static int towerMaxSegments() {
        return intValue(TOWER_MAX_SEGMENTS, DEFAULT_TOWER_MAX_SEGMENTS);
    }

    /** 主控塔白名单原文（逗号分隔的 UUID 串）；解析见塔侧。 */
    public static String towerWhitelist() {
        return stringValue(TOWER_WHITELIST, DEFAULT_TOWER_WHITELIST);
    }

    // ================================================================
    //  读取辅助
    // ================================================================

    /**
     * 读值前先看配置有没有加载完（Forge 在 mod 构造之后读文件并派发加载事件）。
     *
     * <p>开发环境里 {@code ConfigValue#get()} 在配置未加载时会直接抛异常，
     * 所以每个读取入口都用 {@link ForgeConfigSpec#isLoaded()} 挡一道，退回默认值。
     */
    private static int intValue(ForgeConfigSpec.IntValue value, int fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    private static boolean booleanValue(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    private static long longValue(ForgeConfigSpec.LongValue value, long fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    private static double doubleValue(ForgeConfigSpec.DoubleValue value, double fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

    private static String stringValue(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }

}
