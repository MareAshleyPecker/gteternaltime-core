package rain.gtetcore.gtet.common.data.machine

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createWorkableTieredHullMachineModel
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.api.capability.ETPartAbility
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.machine.multiblock.part.ThreadHatchPartMachine
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 「线程仓」变体定义。
 *
 * 一个变体 = 一个方块。**线程数不在这张表里**：它由 [ThreadHatchPartMachine.maxThreadsForTier]
 * 从 tier 直接算出来（`1 shl (tier - GTValues.LuV)`），所以这里只放 id 与 tier，
 * 避免「表里的数字」和「部件算出来的数字」两处打架。
 *
 * @param id   注册名（同时决定方块 id、lang 键 `block.gtetcore.<id>` 与
 *             `gtetcore.machine.<id>.tooltip.<i>` / `gtetcore.machine.<id>.config`）
 * @param tier 电压等级，决定外壳贴图与线程数上限
 *
 * @author rain fox
 */
data class ThreadHatchVariant(
    val id: String,
    val tier: Int
) {

    /** 该变体的线程数上限，与部件构造时算出来的那份是**同一个函数**。 */
    val threads: Int get() = ThreadHatchPartMachine.maxThreadsForTier(tier)
}

/**
 * 「线程仓」注册入口。
 *
 * 一个注册函数 + 一张变体表：遍历 [ThreadHatchVariant] 表逐个注册。
 *
 * 与 [ETOverclockHatches] 的两处同构约定：
 * 1. 用 **GTET 自己的** `ETRegistrate`（`OnlyETreg.ETRegistrate`）而不是 GTM 的 `GTRegistration.REGISTRATE`，
 *    否则方块会注册进 `gtceu:` 命名空间；
 * 2. 变体自带唯一 tier，所以注册名不再拼 `VN[tier]` 前缀，直接用变体 id
 *    （id 与 tier 一对一，语言键也不会带额外前缀）。
 *
 * ## 接线位置
 * 本文件的 [register] 由 `rain.gtetcore.gtet.common.data.machine.muiltmachine.ALLMmchine.init()`
 * 调用一次（结果存进 `ALLMmchine.THREAD_HATCHES`），走的是与 [ETOverclockHatches] 完全相同的那条路径
 * —— 那里也是机器表 `unfreeze()` / `freeze()` 的窗口所在，不要另找入口重复注册。
 *
 * ## 思路来源
 * - 【借鉴形状】GTOCore（`D:\java\GTOCore`）`common/data/GTOMachines.java:250-258` —— 借「`registerTieredMachines("thread_hatch", …)` 从 UV 一路分级到 MAX + 能力 `GTOPartAbility.THREAD_HATCH` + tooltip 里报出线程数」的形状；GTET 侧因为变体表自带唯一 tier，做成「变体表 + 逐个 register」而不是 GTM 那种「传 tier 数组」。
 * - 【借鉴形状】GTOCore `data/lang/MachineLang.java:26-28` 的文案「同时处理至多 %1$s 种不同配方，每种配方至多 %2$s 个」—— 借的是**文案口径**（先说能同时跑几种配方、再说每种能并行多少）；GTET 侧的 tooltip 直接把两个数字写死进各自变体的语言键里（线程数由 tier 定，注册时就已知），运行期不再做字符串插值。⚠️ GTO 的线程调度实现在加密 native 里（`libs/gtolib-1.0.jar` 里 `native0/native/` 那一堆 `.bin`），本文件不涉及它。
 * - 【自研】`ThreadHatchVariant` 只存 `id` + `tier`、线程数**由函数算**的决定 —— 变体表里再写一遍数字就会出现「表里写 4、部件算出 8」这种不一致；只留 tier，数字就只有一个来源。
 *
 * @author rain fox
 */
object ETThreadHatches {

    /**
     * 全部线程仓变体：UV 起每级翻倍，一路到 MAX。
     *
     * | id | tier | 线程数 |
     * |---|---|---|
     * | `thread_hatch_uv`  | UV (8)  | 4   |
     * | `thread_hatch_uhv` | UHV (9) | 8   |
     * | `thread_hatch_uev` | UEV (10)| 16  |
     * | `thread_hatch_uiv` | UIV (11)| 32  |
     * | `thread_hatch_uxv` | UXV (12)| 64  |
     * | `thread_hatch_opv` | OpV (13)| 128 |
     * | `thread_hatch_max` | MAX (14)| 256 |
     *
     * LuV/ZPM 不注册：那两级的并行仓本身就不大，线程仓从 UV 起步是为了让
     * 「线程数」始终是「并行数」量级往上的东西（UV 是 4 线程 vs 4 并行，同量级但语义不同）。
     */
    val VARIANTS: List<ThreadHatchVariant> = listOf(
        ThreadHatchVariant("thread_hatch_uv", GTValues.UV),
        ThreadHatchVariant("thread_hatch_uhv", GTValues.UHV),
        ThreadHatchVariant("thread_hatch_uev", GTValues.UEV),
        ThreadHatchVariant("thread_hatch_uiv", GTValues.UIV),
        ThreadHatchVariant("thread_hatch_uxv", GTValues.UXV),
        ThreadHatchVariant("thread_hatch_opv", GTValues.OpV),
        ThreadHatchVariant("thread_hatch_max", GTValues.MAX),
    )

    /**
     * 把整张变体表注册成方块。
     *
     * @param registrate GTET 的注册器（`OnlyETreg.ETRegistrate`）
     * @param variants   变体表，默认 [VARIANTS]
     * @return 按注册顺序排列的 [MachineDefinition]
     */
    @JvmStatic
    @JvmOverloads
    fun register(
        registrate: GTRegistrate,
        variants: List<ThreadHatchVariant> = VARIANTS
    ): List<MachineDefinition> {
        // 线程仓是多方块部件（part），进「机器」页；这里显式设一次，
        // 因为调用方 ALLMmchine 的 init 块把当前页设成了 MULTIBLOCK。
        registrate.creativeModeTab(GTETCreativeModeTabs.MACHINE)
        return variants.map { registerOne(registrate, it) }
    }

    /**
     * 注册单个变体。
     *
     * 中英双语走 GTET 现有机制：
     * - 英文名 → `.langValue(...)`，由 Registrate 写进 `en_us` 的 `block.gtetcore.<id>`；
     * - 中文名 → [LangUtil.BLOCK_LANG]，由 `LangHandler` 写进 `zh_cn` 的同名键；
     * - 提示/面板文字 → [LangUtil.add]，同时写进 `en_us` 与 `zh_cn`。
     */
    private fun registerOne(registrate: GTRegistrate, v: ThreadHatchVariant): MachineDefinition {
        val threads = v.threads
        val tierName = GTValues.VN[v.tier]

        LangUtil.BLOCK_LANG[v.id] = "线程仓（$threads 线程）"
        LangUtil.add(
            "gtetcore.machine.${v.id}.tooltip.0",
            "Processing up to $threads different recipes simultaneously",
            "同时处理至多 $threads 种不同配方"
        )
        LangUtil.add(
            "gtetcore.machine.${v.id}.tooltip.1",
            "Each thread keeps its own timer and gets the parallel hatch's multiplier",
            "每条线程独立计时，各自吃并行仓的并行倍率"
        )
        LangUtil.add(
            "gtetcore.machine.${v.id}.tooltip.2",
            "The same recipe never takes a second thread",
            "同一种配方不会重复开线程"
        )
        // 机器 UI 面板里输入框下面那行（`LabelWidget` 传 lang 键，客户端按语言解析）
        LangUtil.add(
            "gtetcore.machine.${v.id}.config",
            "Threads (1 - $threads); each thread runs its own recipe",
            "线程数（1 - $threads），每条线程各跑一种配方"
        )

        return registrate
            .machine(v.id) { holder -> ThreadHatchPartMachine(holder, v.tier) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Thread Hatch ($threads Threads)")
            .rotationState(RotationState.ALL)
            .abilities(ETPartAbility.THREAD_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            // 贴图暂时复用 GTM 的并行仓（mk4）—— 与超频仓用的是同一张占位贴图，
            // 这是**有意的临时占位**（用户已确认「材质先用现成的」），不是漏掉或写错：
            // 换美术时只需要改这一处 id，其它地方不含贴图路径。
            // TODO 以后画 GTET 自己的线程仓贴图，把这里换成 block/machines/thread_hatch_*
            .model(
                createWorkableTieredHullMachineModel(GTCEu.id("block/machines/parallel_hatch_mk4"))
                    .andThen(
                        MachineBuilder.ModelInitializer { _, _, model ->
                            model.addReplaceableTextures("bottom", "top", "side")
                        }
                    )
            )
            .tooltips(
                Component.translatable("gtetcore.machine.${v.id}.tooltip.0"),
                Component.translatable("gtetcore.machine.${v.id}.tooltip.1"),
                Component.translatable("gtetcore.machine.${v.id}.tooltip.2"),
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }
}
