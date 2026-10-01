package rain.gtetcore.gtet.util

import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.config.GTETConfig

/**
 * 「多方块共享」那一行 tooltip 的**共用出口**（Kotlin / Java 两侧都调这一处）。
 *
 * ## 为什么需要它
 *
 * 本 mod 的部件仓一共三类：
 * - **写死隔离**的几件（超频仓 / 线程仓 / 并行仓 / 时序仓 / ME 样板总成与镜像）——
 *   机器类的 `canShared()` 现在返回配置值 [GTETConfig.partsShareable]；
 * - **玩家可拨动**的 ME 库存族（标签库存总线 / 仓、二合一总成）——
 *   机器类返回 `shareEnabled || 配置`，面板上另有一个开关；
 * - 其余的 GTM 自带件不受本开关影响。
 *
 * 于是「这一行到底该写 Enabled 还是 Disabled」变成运行时才知道的事，
 * 原先各 hatch 注册处写死的 `Component.translatable("gtceu.part_sharing.disabled")`
 * 配置一打开就在骗玩家，所以统一改成经本对象取名。
 *
 * ## ⚠️ 这一行描述的是**默认状态**
 *
 * 两个键都是 GTM 自带的（`gtceu.part_sharing.disabled` = "Multiblock Sharing §4Disabled"、
 * `gtceu.part_sharing.enabled` = "…§aEnabled"，见 GTM `data/lang/LangHandler.java:894-895`），
 * 本 mod 只引用、不新增语言键。它显示的是**全局开关当前值**对应的状态：
 * - 配置 `partsShareable = false`（默认）：恒显示 Disabled —— 对写死那几件就是事实，
 *   对 ME 库存族则是「默认状态」（面板上还能单独拨开，那件事由紧邻的
 *   `gtetcore.machine.et_tag_filter.share.tooltip` 一行说明，两边不矛盾）；
 * - 配置 `partsShareable = true`：恒显示 Enabled。
 *
 * 所以 ME 库存族那几件的 tooltip 是「本行（默认状态）+ 面板开关说明」两行并列，
 * 与 [rain.gtetcore.gtet.common.data.machine.hatch.ETStockingInputHatches] 类注释里
 * 「`gtceu.part_sharing.disabled` 描述的是默认状态」那条约定一致。
 *
 * ## 为什么在 tooltip builder 里调用、而不是注册时算一次
 *
 * 调用点都写成 `MachineBuilder#tooltipBuilder` 的 lambda 体内，每帧渲染 tooltip 时才取值，
 * 因此配置热重载（Forge 改完 toml 即时生效）不需要重启、也不需要重新注册机器。
 *
 * ## ⚠️ 它只影响**显示**，不影响判定
 *
 * 真正决定结构能不能共享的是各部件类的 `canShared()`；而那个值只在
 * `BlockPattern#checkPatternAt` 那一刻被读，所以**改配置后已成型结构不会立刻复检**
 * （详见 [GTETConfig.partsShareable] 的注释）。
 */
object ETPartSharing {

    /** GTM 自带的「禁止共享」键（描述默认状态）。 */
    private const val DISABLED_KEY: String = "gtceu.part_sharing.disabled"

    /** GTM 自带的「允许共享」键。 */
    private const val ENABLED_KEY: String = "gtceu.part_sharing.enabled"

    /**
     * 按全局开关 [GTETConfig.partsShareable] 返回「多方块共享」那一个 [Component]。
     *
     * `@JvmStatic`：Java 侧的 hatch 注册文件（如 `ETMEPatternBufferHatches`）要能直接写
     * `ETPartSharing.line()`，不用经 `INSTANCE`。
     */
    @JvmStatic
    fun line(): Component = Component.translatable(
        if (GTETConfig.partsShareable()) ENABLED_KEY else DISABLED_KEY
    )
}