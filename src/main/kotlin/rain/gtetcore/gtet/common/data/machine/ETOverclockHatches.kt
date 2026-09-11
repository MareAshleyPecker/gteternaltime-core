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
 * @param id           注册名（同时决定方块 id 与名字语言键 `block.gtetcore.<id>`；
 *                     本 mod **不再**为超频仓生成说明性 tooltip / 面板键，见 [ETOverclockHatches.VARIANTS] 的说明）
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
 * 1. 变体表里每个变体自带唯一 tier，所以注册名不再像 `registerTieredMachines` 那样
 *    在前面拼 `GTValues.VN[tier].toLowerCase() + "_"`，直接用变体 id，
 *    这样 id 与 tier 是一对一、语言键也不会带额外前缀。
 *
 * ## 显示只保留「电压等级 + 名称」
 * 本部件**只**生成名字语言键 `block.gtetcore.<id>`，中文名里直接带上电压等级与规格
 * （例如「UV 超频仓（8×/×4）」），英文名走 `.langValue(...)`。
 * 再挂两行解释只会把提示撑长。tooltip 只剩 GTM 自带的那条 `gtceu.part_sharing.disabled`
 * （所有 GTM 多方块部件都有，用来告诉玩家部件不可共享）。
 *
 * @author rain fox
 */
object ETOverclockHatches {

    /**
     * 全部超频仓变体。
     *
     * 每级超频的收益固定是「耗时 ÷S、EUt ×(E×S)」，所以表中 EUt/级 那一列的来历就是 `E × S`：
     * 最后一行（MAX 档）的数值与 `overclock_hatch_16x_saving` **完全相同**（16× / E=0.5），
     * 差的只是铭牌等级（`GTValues.MAX`）与外壳贴图 —— 有意如此：MAX 档在这个表里是
     * 「量级上的终点」，不是又一次数值跃迁；真要更激进（例如 S=32），改这一行的第一、二个参数即可。
     * id 里 `_max` 后缀是**tier 判别位**：前六档一个 tier 一档，只有这一档与 `16x_saving` 撞数，
     * 必须靠后缀区分（`_max` 的写法与 [ETThreadHatches] 的 `thread_hatch_max` 一致）。
     */
    val VARIANTS: List<OverclockHatchVariant> = listOf(
        OverclockHatchVariant("overclock_hatch_8x_lossy4"       , 8 , 4.0, GTValues.ZPM),
        OverclockHatchVariant("overclock_hatch_8x_lossy2"       , 8 , 2.0, GTValues.UV ),
        OverclockHatchVariant("overclock_hatch_8x_perfect"      , 8 , 1.0, GTValues.UHV),
        OverclockHatchVariant("overclock_hatch_8x_saving"       , 8 , 0.5, GTValues.UEV),

        OverclockHatchVariant("overclock_hatch_16x_lossy4"      , 16, 4.0, GTValues.UIV ),
        OverclockHatchVariant("overclock_hatch_16x_lossy2"      , 16, 2.0, GTValues.UXV ),
        OverclockHatchVariant("overclock_hatch_16x_perfect"     , 16, 1.0, GTValues.OpV ),
        OverclockHatchVariant("overclock_hatch_16x_saving_max"  , 16, 0.5, GTValues.MAX ),


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
     * 中英双语走 GTET 现有机制，**只登记名字这一条键**（见类 KDoc「显示只保留电压等级 + 名称」）：
     * - 英文名 → `.langValue(...)`，由 Registrate 写进 `en_us` 的 `block.gtetcore.<id>`；
     * - 中文名 → [LangUtil.BLOCK_LANG]，由 `LangHandler` 写进 `zh_cn` 的同名键。
     * 两个名字里都带「电压等级 + 速度 + 能效」，玩家不用看 tooltip 也能分清七档。
     */
    private fun registerOne(registrate: GTRegistrate, v: OverclockHatchVariant): MachineDefinition {
        val eut = num(v.eutPerLevel)
        val tierName = GTValues.VN[v.tier]

        // 中文名按「电压等级 + 名称（规格）」写：例如 UV 超频仓（8×/×4）
        LangUtil.BLOCK_LANG[v.id] = "$tierName 超频仓（${v.speed}×/EUt×$eut）"

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
            // 提示只剩 GTM 自带的那条：部件的说明性文字已经并进名字，不再单独生成 tooltip 键
            .tooltips(
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }

    /** 把 32.0 打成 `32`、0.5 保持 `0.5` —— 只用于显示，不参与计算。 */
    private fun num(value: Double): String =
        if (value == floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
}
