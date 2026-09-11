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
import rain.gtetcore.gtet.common.machine.multiblock.part.OverclockHatchPartMachine
import rain.gtetcore.gtet.util.lang.LangUtil
import kotlin.math.floor

/**
 * 「超频仓」变体定义。
 *
 * 一个变体 = 一个方块。所有数值都只在这一张表里出现，加档只需要往下加一行。
 *
 * @param id           注册名（同时决定方块 id、lang 键 `block.gtetcore.<id>` 与
 *                     `gtetcore.machine.<id>.tooltip.<i>` / `gtetcore.machine.<id>.info`）
 * @param speed        速度倍率 S：每消耗 1 级超频，配方耗时 ÷S
 * @param energyFactor 能效系数 E：每消耗 1 级超频，EUt × `E × S`
 * @param tier         默认（也是唯一）电压等级，决定外壳贴图与配方等级上限
 *
 * @author rain fox
 */
data class OverclockHatchVariant(
    val id: String,
    val speed: Int,
    val energyFactor: Double,
    val tier: Int
) {

    /** 每级超频实际的 EUt 倍率 = `E × S`。 */
    val eutPerLevel: Double get() = energyFactor * speed
}

/**
 * 「超频仓」注册入口。
 *
 * 一个注册函数 + 一张变体表：遍历 [OverclockHatchVariant] 表逐个注册，
 * 注册链顺序：`.tier(...)` 打头 → 能力 → 模型 → 提示 → `register()`；
 * 分级 part 的模型用 `createWorkableTieredHullMachineModel(...)`，
 * 能力由 `MachineBuilder.abilities(...)` 登记。
 *
 * 与 GTM 原写法的两点差异：
 * 1. 用 **GTET 自己的** `ETRegistrate`（`OnlyETreg.ETRegistrate`）而不是 GTM 的
 *    `GTRegistration.REGISTRATE`，否则方块会注册进 `gtceu:` 命名空间；
 * 2. 变体表里每个变体自带唯一 tier，所以注册名不再像 `registerTieredMachines` 那样
 *    在前面拼 `GTValues.VN[tier].toLowerCase() + "_"`，直接用变体 id，
 *    这样 id 与 tier 是一对一、语言键也不会带额外前缀。
 *
 * ## 思路来源
 * - 【自研】变体表（S / E / tier 共六档，见 `VARIANTS` 与 `eutPerLevel = E × S` 的定义）—— GTM 没有「速度倍率 × 能效系数 × 电压等级」这种变体概念。
 * - 【自研】用 GTET 自己的 `ETRegistrate`（为了不把方块注册进 `gtceu:` 命名空间）与「变体 id 不再拼 `VN[tier]` 前缀」这两点偏离 —— 由 GTET 的注册约定决定。
 *
 * @author rain fox
 */
object ETOverclockHatches {

    /**
     * 全部超频仓变体。
     *
     * 每级超频的收益固定是「耗时 ÷S、EUt ×(E×S)」，所以表中 EUt/级 那一列的来历就是 `E × S`：
     *
     * | id | S | E | 耗时/级 | EUt/级 | tier | 定位 |
     * |---|---|---|---|---|---|---|
     * | `overclock_hatch_8x_lossy4`  | 8  | 4.0 | ÷8  | ×32 | IV  | 最便宜、最费电 |
     * | `overclock_hatch_8x_lossy2`  | 8  | 2.0 | ÷8  | ×16 | LuV | 折中 |
     * | `overclock_hatch_8x_perfect` | 8  | 1.0 | ÷8  | ×8  | ZPM | 不吃亏的 8 倍速 |
     * | `overclock_hatch_8x_saving`  | 8  | 0.5 | ÷8  | ×4  | UV  | 又提速又省电 |
     * | `overclock_hatch_16x_perfect`| 16 | 1.0 | ÷16 | ×16 | UEV | 不吃亏的 16 倍速 |
     * | `overclock_hatch_16x_saving` | 16 | 0.5 | ÷16 | ×8  | UIV | 16 倍速还省电 |
     *
     * 注意 UHV(9) 故意跳过：UHV 留给以后可能加的 8x/16x 中间档。
     */
    val VARIANTS: List<OverclockHatchVariant> = listOf(
        // ── 8× 家族：每级耗时 ÷8（相当于原版 3 级 perfect 超频的提速）──
        // E=4.0：越级提速的代价最高档，每级电 ×4×8=32
        OverclockHatchVariant("overclock_hatch_8x_lossy4", 8, 4.0, GTValues.IV),
        // E=2.0：电 ×2×8=16
        OverclockHatchVariant("overclock_hatch_8x_lossy2", 8, 2.0, GTValues.LuV),
        // E=1.0：perfect —— 只按提速倍数收电，×1×8=8
        OverclockHatchVariant("overclock_hatch_8x_perfect", 8, 1.0, GTValues.ZPM),
        // E=0.5：saving —— 每级电只 ×0.5×8=4，比原版超频（×4）持平但快 8 倍
        OverclockHatchVariant("overclock_hatch_8x_saving", 8, 0.5, GTValues.UV),
        // ── 16× 家族：每级耗时 ÷16 ──
        // E=1.0：perfect，每级电 ×1×16=16
        OverclockHatchVariant("overclock_hatch_16x_perfect", 16, 1.0, GTValues.UEV),
        // E=0.5：saving，每级电 ×0.5×16=8（比 8x_perfect 更快且同耗电）
        OverclockHatchVariant("overclock_hatch_16x_saving", 16, 0.5, GTValues.UIV),
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
        variants: List<OverclockHatchVariant> = VARIANTS
    ): List<MachineDefinition> {
        // 超频仓是多方块部件（part），进「机器」页；这里显式设一次，
        // 因为调用方 ALLMmchine 的 init 块把当前页设成了 MULTIBLOCK。
        registrate.creativeModeTab(GTETCreativeModeTabs.MACHINE)
        return variants.map { registerOne(registrate, it) }
    }

    /**
     * 注册单个变体 —— 链条结构与 `GTMachineUtils.registerTieredMachines` 一致。
     *
     * 中英双语走 GTET 现有机制：
     * - 英文名 → `.langValue(...)`，由 Registrate 写进 `en_us` 的 `block.gtetcore.<id>`；
     * - 中文名 → [LangUtil.BLOCK_LANG]，由 `LangHandler` 写进 `zh_cn` 的同名键；
     * - 提示/面板文字 → [LangUtil.add]，同时写进 `en_us` 与 `zh_cn`。
     */
    private fun registerOne(registrate: GTRegistrate, v: OverclockHatchVariant): MachineDefinition {
        val eut = num(v.eutPerLevel)
        val tierName = GTValues.VN[v.tier]

        LangUtil.BLOCK_LANG[v.id] = "超频仓（${v.speed}× / 能耗 ×$eut）"
        LangUtil.add(
            "gtetcore.machine.${v.id}.tooltip.0",
            "Each overclock level: duration ÷${v.speed}, total energy ×$eut",
            "每消耗 1 级超频：耗时 ÷${v.speed}，总能耗 ×$eut"
        )
        LangUtil.add(
            "gtetcore.machine.${v.id}.tooltip.1",
            "Replaces the multiblock's normal overclock while installed",
            "装在多方块上时，替换该多方块的普通超频"
        )
        // 机器 UI 的规格行（`LabelWidget` 传 lang 键，客户端按语言解析）
        LangUtil.add(
            "gtetcore.machine.${v.id}.info",
            "${v.speed}× Speed / ×$eut Energy per level",
            "${v.speed}× 速度 / 每级能耗 ×$eut"
        )

        return registrate
            .machine(v.id) { holder -> OverclockHatchPartMachine(holder, v.tier, v.speed, v.energyFactor) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Overclock Hatch (${v.speed}× Speed / ×$eut Energy)")
            .rotationState(RotationState.ALL)
            .abilities(ETPartAbility.OVERCLOCK_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            // 贴图暂时复用 GTM 的并行仓（mk4）：
            // 即下面那行 `gtceu:block/machines/parallel_hatch_mk4`，这是**有意的临时占位**
            // （已与用户确认「材质先用现成的」），不是漏掉或写错：
            // 换美术时只需要改这一处 id，其它地方不含贴图路径。
            // TODO 以后画 GTET 自己的超频仓贴图，把这里换成 block/machines/overclock_hatch_*
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
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }

    /** 把 32.0 打成 `32`、0.5 保持 `0.5` —— 只用于显示，不参与计算。 */
    private fun num(value: Double): String =
        if (value == floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
}
