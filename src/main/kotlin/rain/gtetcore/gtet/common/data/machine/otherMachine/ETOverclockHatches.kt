package rain.gtetcore.gtet.common.data.machine.otherMachine

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createWorkableTieredHullMachineModel
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.api.capability.ETPartAbility
import rain.gtetcore.gtet.common.machine.multiblock.part.OverclockHatchPartMachine
import rain.gtetcore.gtet.util.lang.LangUtil
import kotlin.math.floor

/**
 * 「超频仓」变体定义。
 *
 * 一个变体 = 一个方块。所有数值都只在这一张表里出现，加档只需要往下加一行。
 *
 * @param id           注册名（同时决定方块 id 与名字语言键 `block.gtetcore.<id>`）
 * @param speed        速度倍率 S：每消耗 1 级超频，配方耗时 ÷S
 * @param energyFactor 能效系数 E：每消耗 1 级超频，EUt × `E × S`
 * @param tier         默认（也是唯一）电压等级，决定外壳贴图与配方等级上限
 * @param tooltip      可选说明行（中英成对）；`null` = 这一档不加说明行（默认，也是绝大多数档位的状态）
 *
 * @author rain fox
 */
data class OverclockHatchVariant(
    val id: String,
    val speed: Int,
    val energyFactor: Double,
    val tier: Int,
    val tooltip: HatchTooltip? = null
) {

    /** 每级超频实际的 EUt 倍率 = `E × S`。 */
    val eutPerLevel: Double get() = energyFactor * speed
}

/**
 * 一条物品提示（tooltip）文案，中英**必须成对**给。
 *
 * 中文写进 `zh_cn`、英文写进 `en_us`，键名由 [ETOverclockHatches.registerOne] 按项目惯例
 * 生成成 `gtetcore.machine.<id>.tooltip.0`（与 `ETTestMultiblocks` / `ETModularTestMachine` 同一套）。
 * 只给一个变体填 [OverclockHatchVariant.tooltip] 就等于「这一档多一行提示」，别的档位不受影响。
 */
data class HatchTooltip(val cn: String, val en: String)

/**
 * 超频仓正面覆盖层的来源命名空间。
 *
 * ⚠️ 这批贴图是 **GTOCore 的素材**（版权归 GTOCore 作者所有，LGPL-3.0），
 * 随本 mod 一起分发、只引用不修改；来源与授权原文见 `assets/gtocore/LICENSE.txt`。
 */
private const val GTOCORE_NS = "gtocore"

/** GTOCore 超频仓覆盖层目录前缀：完整路径 = 本前缀 + mk 编号（`..._mk1` … `..._mk7`）。 */
private const val OVERCLOCK_OVERLAY_ROOT = "block/machines/overclock_hatch/overclock_hatch_mk"

/** GTOCore 的 mk 编号区间：`mk1` ↔ UV，`mk7` ↔ MAX。 */
private const val OVERCLOCK_MK_MIN = 1
private const val OVERCLOCK_MK_MAX = 7

/**
 * 变体对应的 GTOCore 覆盖层目录（`createWorkableTieredHullMachineModel` 的 `overlayDir` 参数）。
 *
 * GTOCore 自己的编号规则是 `mk = tier - ZPM`（`mk1` 就是 UV），只覆盖 UV..MAX 七档；
 * 本族多出的 ZPM 档会算成 `mk0`（GTOCore 没有这一级），所以收进 `mk1`——
 * 这样 UV..MAX 这七档与 GTOCore 的对应关系逐档一致，只有 ZPM 与 UV 共用正面贴图
 * （两者的外壳本身还隔着电压等级 `gtceu:block/casings/voltage/<tier>`，不会认错）。
 *
 * GTOCore 这几套目录里**只有 `overlay_front`**，没有 back/top/bottom/side：
 * `WorkableOverlays.get` 对缺失的面直接判空、不写键，所以六个面里只有正面有覆盖层，
 * 与 GTOCore 原版表现一致，不需要硬凑别的面。
 */
private fun overlayFor(v: OverclockHatchVariant): ResourceLocation =
    ResourceLocation.fromNamespaceAndPath(
        GTOCORE_NS,
        OVERCLOCK_OVERLAY_ROOT + (v.tier - GTValues.ZPM).coerceIn(OVERCLOCK_MK_MIN, OVERCLOCK_MK_MAX)
    )

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
 * ## 显示 = 「电压等级 + 名称」，个别档位再多一行说明
 * 每个变体都生成名字语言键 `block.gtetcore.<id>`，中文名里直接带上电压等级与规格
 * （例如「ZPM 超频仓（4× Speed|×32 Energy）」），英文名走 `.langValue(...)`；
 * 这是**唯一必有的**显示键，规格都写在名字里，所以默认不再生成说明性 tooltip / 面板键。
 * 例外：[OverclockHatchVariant.tooltip] 非空的档位会额外生成一条
 * `gtetcore.machine.<id>.tooltip.0`（目前只有 `4x_lossy4` 与 `1024x_saving_max` 两档）。
 * 其余档位的提示只剩 GTM 自带的那条 `gtceu.part_sharing.disabled`
 * （所有 GTM 多方块部件都有，用来告诉玩家部件不可共享）。
 *
 * @author rain fox
 */
object ETOverclockHatches {

    /**
     * 全部超频仓变体（4 系列 × 4 档 + 1024× 一档 = 17 档）。
     *
     * 每级超频的收益固定是「耗时 ÷S、EUt ×(E×S)」，所以表中 EUt/级 那一列的来历就是 `E × S`
     * （例如最后一档 1024× / E=0.25 → 每级 256 倍 EUt）。
     * 最后一档的铭牌等级是 `GTValues.MAX`：它是本表速度与电压两端的终点，想更激进
     * （例如 S=2048）改这一行的第一、二个参数即可；id 里的 `_max` 后缀只是把「铭牌是 MAX」
     * 写进名字，写法与 [ETThreadHatches] 的 `thread_hatch_max` 一致。
     */
    val VARIANTS: List<OverclockHatchVariant> = listOf(
        // 4× 系列：ZPM / UV / UHV / UEV
        // ⚠️ 全表只有两档带说明行，这是第一档
        OverclockHatchVariant("overclock_hatch_4x_lossy4"       , 4  , 8.0, GTValues.ZPM,
            tooltip = HatchTooltip("看起来并不好用", "Looks pretty useless")),
        OverclockHatchVariant("overclock_hatch_4x_lossy2"       , 4  , 4.0, GTValues.UV ),
        OverclockHatchVariant("overclock_hatch_4x_perfect"      , 4  , 2.0, GTValues.UHV),
        OverclockHatchVariant("overclock_hatch_4x_saving"       , 4  , 1.0, GTValues.UEV),
        // 16× 系列：UV / UHV / UEV / UIV
        OverclockHatchVariant("overclock_hatch_16x_lossy4"      , 16 , 8.0, GTValues.UV  ),
        OverclockHatchVariant("overclock_hatch_16x_lossy2"      , 16 , 4.0, GTValues.UHV ),
        OverclockHatchVariant("overclock_hatch_16x_perfect"     , 16 , 2.0, GTValues.UEV ),
        OverclockHatchVariant("overclock_hatch_16x_saving"      , 16 , 1.0, GTValues.UIV ),
        // 64× 系列：UHV / UEV / UIV / UXV
        OverclockHatchVariant("overclock_hatch_64x_lossy4"      , 64 , 8.0, GTValues.UHV),
        OverclockHatchVariant("overclock_hatch_64x_lossy2"      , 64 , 4.0, GTValues.UEV),
        OverclockHatchVariant("overclock_hatch_64x_perfect"     , 64 , 2.0, GTValues.UIV),
        OverclockHatchVariant("overclock_hatch_64x_saving"      , 64 , 1.0, GTValues.UXV),
        // 256× 系列：UEV / UIV / UXV / OpV
        OverclockHatchVariant("overclock_hatch_256x_lossy4"     , 256, 4.0, GTValues.UEV ),
        OverclockHatchVariant("overclock_hatch_256x_lossy2"     , 256, 2.0, GTValues.UIV ),
        OverclockHatchVariant("overclock_hatch_256x_perfect"    , 256, 1.0, GTValues.UXV ),
        OverclockHatchVariant("overclock_hatch_256x_saving"     , 256, 0.5, GTValues.OpV ),
        // 1024× 档：本表终点，铭牌 MAX（⚠️ 全表只有两档带说明行，这是第二档）
        OverclockHatchVariant("overclock_hatch_1024x_saving_max", 1024,0.25, GTValues.MAX,
            tooltip = HatchTooltip("屌爆啦！！！", "Absolutely insane!!!"))
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
        return variants.map { registerOne(registrate, it) }
    }

    /**
     * 注册单个变体 —— 链条结构与 `GTMachineUtils.registerTieredMachines` 一致。
     *
     * 中英双语走 GTET 现有机制：
     * - 英文名 → `.langValue(...)`，由 Registrate 写进 `en_us` 的 `block.gtetcore.<id>`；
     * - 中文名 → [LangUtil.BLOCK_LANG]，由 `LangHandler` 写进 `zh_cn` 的同名键。
     * 说明行是**可选**的：只有变体表里填了 [OverclockHatchVariant.tooltip] 的档位才登记
     * `gtetcore.machine.<id>.tooltip.0`（中英成对，走 [LangUtil.add]），并把它挂到 `.tooltips(...)` 上；
     * 其余档位一个键都不多生成，提示里只剩 GTM 自带那条「部件不可共享」。
     */
    private fun registerOne(registrate: GTRegistrate, v: OverclockHatchVariant): MachineDefinition {
        val eut = num(v.eutPerLevel)
        val tierName = GTValues.VN[v.tier]

        // 中文名按「电压等级 + 名称（规格）」写：例如 ZPM 超频仓（4× Speed|×32 Energy）
        LangUtil.BLOCK_LANG[v.id] = "$tierName 超频仓（${v.speed}× Speed|×$eut Energy）"

        // 可选说明行：登记双语键并额外挂一条提示；没有 tooltip 的档位这里是空数组，等于不加
        val tooltipKey = "gtetcore.machine.${v.id}.tooltip.0"
        val extraTooltips: Array<Component> = v.tooltip?.let {
            LangUtil.add(tooltipKey, it.en, it.cn)
            arrayOf<Component>(Component.translatable(tooltipKey))
        } ?: emptyArray()

        return registrate
            .machine(v.id) { holder -> OverclockHatchPartMachine(holder, v.tier, v.speed, v.energyFactor) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Overclock Hatch (${v.speed}× Speed / ×$eut Energy)")
            .rotationState(RotationState.ALL)
            .abilities(ETPartAbility.OVERCLOCK_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            // 贴图用 GTOCore 的超频仓覆盖层（按 tier 取 mk 编号，规则见 [overlayFor]）。
            // helper 内部把父模型定成 `gtceu:block/casings/voltage/<tier>`（电压等级外壳），
            // 再把 overlayDir 下的 `overlay_front` 按 IDLE/WORKING/SUSPEND 叠在正面，
            // 所以本仓外观与 GTOCore 自己的超频仓一致，不需要我们再画。
            // ⚠️ 素材版权归 GTOCore 作者所有（LGPL-3.0），见 `assets/gtocore/LICENSE.txt`；只引用不修改。
            // TODO 以后画 GTET 自己的超频仓贴图，把 [OVERCLOCK_OVERLAY_ROOT] 换成自己的目录即可
            .model(
                createWorkableTieredHullMachineModel(overlayFor(v))
                    .andThen(
                        MachineBuilder.ModelInitializer { _, _, model ->
                            model.addReplaceableTextures("bottom", "top", "side")
                        }
                    )
            )
            // 提示 = GTM 自带的「部件不可共享」+ 变体表里可选的说明行（没填的档位 extraTooltips 为空）
            .tooltips(
                Component.translatable("gtceu.part_sharing.disabled"),
                *extraTooltips
            )
            .tooltips()
            .register()
    }

    /** 把 32.0 打成 `32`、0.5 保持 `0.5` —— 只用于显示，不参与计算。 */
    private fun num(value: Double): String =
        if (value == floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
}
