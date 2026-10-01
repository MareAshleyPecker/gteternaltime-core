package rain.gtetcore.gtet.studio.config
import rain.gtetcore.gtet.studio.config.StudioLimits.applyValues
import rain.gtetcore.gtet.studio.config.StudioLimits.describe
import rain.gtetcore.gtet.studio.config.StudioLimits.maxTrianglesPerModel

/**
 * 工作室的**渲染上限**（设计文档 §7.1）—— 默认常量 + **运行时可覆盖**。
 *
 * ## 为什么要有上限
 * 模型是**数据**：谁都能往 `config/gtetstudio/` 里丢一个 50 万面的 OBJ。而
 * `DynamicRender.render` 是**每台机器各调一次**的 —— 同屏 N 台就是 N 遍提交。
 * 没有上限时，一个巨型模型 + 十台机器足以拖死客户端，而且**看起来像"mod 有 bug"**。
 *
 * ## 现在的真实情况（2026-09-30 起）
 * 值有两个来源，按优先级：
 * 1. **`config/gtetstudio/studio.json`**（studio 自己的配置文件，首次运行由 mod 释放）——
 *    由 [StudioConfig] 解析，`data/StudioLibrary` 读盘后调 [applyValues] 灌进来；
 *    改完敲 `/gtetstudio reload` 就重读生效，不用重启游戏；
 * 2. **下面的默认常量** —— 文件缺失 / 坏值 / 没写这个键时用它（[StudioConfig.defaults]）。
 *
 * ⚠️ **不接 `ForgeConfigSpec`**（§7.1 第 3 条纪律，已拍板）：注册 config 要在 mod 构造阶段加一行
 * 宿主代码，而「配置属于 studio 包自己」—— 整包抽出去时不能把宿主的配置一起拖走。
 * 这份 JSON 复用 studio 已有的读盘 + 热重载管线，宿主那边一根线都不用接。
 *
 * ## 线程
 * `DynamicRender.getRenderBoundingBox` / `shouldRender` 可能被区块构建工作线程调到，而值是在渲染线程
 * （或 `/gtetstudio reload` 的命令线程）换的 ⇒ 每个字段都是 `@Volatile`：
 * **读到的永远是某一代完整的值，不会读到半个**（这些字段各自独立，没有跨字段一致性要求）。
 *
 * ## 两条纪律（§7.1）
 * 1. **载入期拒绝 ≠ 运行期丢帧**：[maxTrianglesPerModel] 是在**载入时**把坏模型挡在门外，
 *    报错要说清"哪个文件、多少面、上限多少"；同屏数量/总预算是运行期降级，属于 M5。
 * 2. **超限/设置必须可观测**：拒绝、跳过、以及**当前生效的上限值**都要能看见 ——
 *    光有开关看不出来等于没有，所以有 [describe]（`/gtetstudio limits` 打它）。
 *
 * @author rain fox
 */
object StudioLimits {

    // ────────────────────────── 默认值（配置文件缺失 / 坏值 / 没写这个键时用）──────────────────────────

    /** `enabled` 的默认值。 */
    const val DEFAULT_ENABLED: Boolean = true

    /** `maxTrianglesPerModel` 的默认值。 */
    const val DEFAULT_MAX_TRIANGLES_PER_MODEL: Int = 200_000

    /** `defaultViewDistance` 的默认值（格）。 */
    const val DEFAULT_VIEW_DISTANCE: Int = 256

    /** `maxVisibleModels` 的默认值（M5）。 */
    const val DEFAULT_MAX_VISIBLE_MODELS: Int = 16

    /** `maxTotalTriangles` 的默认值（M5）。 */
    const val DEFAULT_MAX_TOTAL_TRIANGLES: Int = 800_000

    /** `animationMaxDistance` 的默认值（M5）。 */
    const val DEFAULT_ANIMATION_MAX_DISTANCE: Int = 64

    // ────────────────────────── 运行时的值（当前生效的那一份）──────────────────────────

    /** 总开关：关掉后一台都不画（排查"是不是 studio 拖的"时用）。 */
    @Volatile
    @JvmField
    var enabled: Boolean = DEFAULT_ENABLED

    /**
     * **单模型**三角形上限，载入期校验（[rain.gtetcore.gtet.studio.data.StudioModelLoader]）。
     *
     * 超限**拒绝载入并明确报错**，不静默削面 —— 悄悄少画一半的模型比报错更难查。
     */
    @Volatile
    @JvmField
    var maxTrianglesPerModel: Int = DEFAULT_MAX_TRIANGLES_PER_MODEL

    /**
     * 视距默认值（格）。**模型 JSON 里的 `viewDistance` 优先于它**（见 [StudioConfig.resolveViewDistance]），
     * 它只对"自己没写 viewDistance"的模型生效。
     */
    @Volatile
    @JvmField
    var defaultViewDistance: Int = DEFAULT_VIEW_DISTANCE

    /**
     * M5 才生效：同屏模型数上限。
     *
     * ⚠️ **当前版本没有任何代码读它** —— 它只是被 `studio.json` 解析、被 [describe] 打印出来，
     * 键先留好，M5 做"按距离由近到远保留前 N 个"时再接。
     */
    @Volatile
    @JvmField
    var maxVisibleModels: Int = DEFAULT_MAX_VISIBLE_MODELS

    /** M5 才生效：三角形总预算。**当前版本没有任何代码读它**（同上）。 */
    @Volatile
    @JvmField
    var maxTotalTriangles: Int = DEFAULT_MAX_TOTAL_TRIANGLES

    /** M5 才生效：超过这个距离只画静态部件。**当前版本没有任何代码读它**（同上）。 */
    @Volatile
    @JvmField
    var animationMaxDistance: Int = DEFAULT_ANIMATION_MAX_DISTANCE

    /** 当前这些值是从哪来的（配置文件路径 or 内置默认值）—— 打日志/命令输出用，让人知道改哪个文件。 */
    @Volatile
    @JvmField
    var source: String = "(内置默认值：${StudioConfig.FILE_NAME} 还没读过)"

    /**
     * 把一份解析好的配置灌成当前生效值（`data/StudioLibrary` 读盘成功后调用）。
     *
     * 逐个字段赋值，不做整体替换 —— 这样即使有别的线程正在读，它读到的也是"某一代的值"。
     *
     * @param values 解析出来的值（坏字段已经在 [StudioConfig] 里回退成默认值了）
     * @param from   来处，写进日志与 `/gtetstudio limits`，例如 `config/gtetstudio/studio.json`
     */
    @JvmStatic
    fun applyValues(values: StudioConfigValues, from: String) {
        enabled = values.enabled
        maxTrianglesPerModel = values.maxTrianglesPerModel
        defaultViewDistance = values.defaultViewDistance
        maxVisibleModels = values.maxVisibleModels
        maxTotalTriangles = values.maxTotalTriangles
        animationMaxDistance = values.animationMaxDistance
        source = from
    }

    /** 退回内置默认值（配置文件读不出来时的兜底；自检也用它复位）。 */
    @JvmStatic
    fun resetToDefaults() {
        applyValues(StudioConfig.defaults(), "(内置默认值)")
    }

    /**
     * **当前生效值**的逐条人话描述 —— 给 `/gtetstudio limits` 与日志用（§7.1 纪律 2：必须可观测）。
     */
    @JvmStatic
    fun describe(): List<String> = listOf(
        "enabled = $enabled（false = 一台都不画）",
        "maxTrianglesPerModel = $maxTrianglesPerModel" +
            "（单模型，载入期拒绝：超限的模型不会被载入）",
        "defaultViewDistance = $defaultViewDistance" +
            "（格；模型 JSON 自己写了 viewDistance 的以模型那份为准）",
        "maxVisibleModels = $maxVisibleModels   ← M5 才生效，当前版本没有任何代码读它",
        "maxTotalTriangles = $maxTotalTriangles   ← M5 才生效，当前版本没有任何代码读它",
        "animationMaxDistance = $animationMaxDistance   ← M5 才生效，当前版本没有任何代码读它",
    )

    /** 一行摘要（日志里省地方用）。 */
    @JvmStatic
    fun summary(): String =
        "enabled=$enabled, maxTrianglesPerModel=$maxTrianglesPerModel, defaultViewDistance=$defaultViewDistance" +
            "（maxVisibleModels/maxTotalTriangles/animationMaxDistance 是 M5 预留，当前不生效）"
}