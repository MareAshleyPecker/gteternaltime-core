package rain.gtetcore.gtet.common.data.machine.hatch

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createWorkableTieredHullMachineModel
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.common.data.machine.hatch.ETParallelHatches.VARIANTS
import rain.gtetcore.gtet.common.machine.multiblock.part.ETParallelHatchPartMachine
import rain.gtetcore.gtet.util.lang.LangUtil


/**
 * GTET 自己的「并行仓」注册入口（IV ~ MAX 共 10 档，一套仓覆盖全部档位）。
 *
 * ⚠️ IV / LuV / ZPM / UV 这四档以前靠 GTM 自带的并行仓（`GCYMMachines.PARALLEL_HATCH`）承担，
 * 现在 GTM 那四档被整体拿掉（配方由 [rain.gtetcore.gtet.ETGTAddon.removeRecipes] 删除、
 * 创造页与 EMI/JEI 里隐藏），所以低四档必须由本表自己补上，否则 IV~UV 就没有并行仓可用了。
 *
 * 复用 GTM 的能力 [PartAbility.PARALLEL_HATCH]：多方块的结构谓词是拿
 * `Predicates.abilities(PartAbility.PARALLEL_HATCH)` 去匹配部件的，只有挂上同一个能力对象，
 * GTM / GCYM 那批「能插并行仓」的多方块才认得我们的仓。
 * ⚠️ 换成 GTET 自定义能力会让本仓在绝大多数 GT 多方块上插不进去。
 *
 * 并行数写死在 [VARIANTS] 表里、构造时注入部件，不做「由 tier 推公式」
 * —— 本 mod 的档位不是纯 ×4 数列（UEV 只有 UHV 的两倍），tier 反推不出来。
 *
 * @author rain fox
 */
object ETParallelHatches {
    /**
     * 「并行仓」变体定义：一个变体 = 一个方块。
     *
     * @param id          注册名（同时决定方块 id 与名字语言键 `block.gtetcore.<id>`）
     * @param tier        电压等级，决定外壳贴图
     * @param maxParallel 并行上限（同时是部件的默认值）
     *
     * @author rain fox
     */
    data class ParallelHatchVariant(
        val id: String,
        val tier: Int,
        val maxParallel: Int
    )


    /**
     * 全部并行仓变体（IV ~ MAX 十档），tier 与并行上限一一对应：
     * IV 32 / LuV 128 / ZPM 512 / UV 2048 / UHV 8192 / UEV 32768  / UIV 524288 /
     * UXV 2097152 / OpV 8388608 / MAX 33554432。
     */
    val VARIANTS: List<ParallelHatchVariant> = listOf(
        ParallelHatchVariant("parallel_hatch_iv", GTValues.IV, 32),
        ParallelHatchVariant("parallel_hatch_luv", GTValues.LuV, 128),
        ParallelHatchVariant("parallel_hatch_zpm", GTValues.ZPM, 512),
        ParallelHatchVariant("parallel_hatch_uv", GTValues.UV, 2048),
        ParallelHatchVariant("parallel_hatch_uhv", GTValues.UHV, 8192),
        ParallelHatchVariant("parallel_hatch_uev", GTValues.UEV, 32768),
        ParallelHatchVariant("parallel_hatch_uiv", GTValues.UIV, 524288),
        ParallelHatchVariant("parallel_hatch_uxv", GTValues.UXV, 2097152),
        ParallelHatchVariant("parallel_hatch_opv", GTValues.OpV, 8388608),
        ParallelHatchVariant("parallel_hatch_max", GTValues.MAX, 33554432),
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
        variants: List<ParallelHatchVariant> = VARIANTS
    ): List<MachineDefinition> {
        return variants.map { registerOne(registrate, it) }
    }

    /**
     * 注册单个变体：只登记名字这一条语言键（与超频仓 / 线程仓同一套显示约定，
     * tooltip 只剩 GTM 自带的部件不可共享提示）。
     */
    private fun registerOne(registrate: GTRegistrate, v: ParallelHatchVariant): MachineDefinition {
        val tierName = GTValues.VN[v.tier]

        // 中文名按「电压等级 + 名称（并行数）」写：例如 MAX 并行仓（4194304 并行）
        LangUtil.BLOCK_LANG[v.id] = "$tierName 并行仓（${v.maxParallel} 并行）"

        return registrate
            .machine(v.id) { holder -> ETParallelHatchPartMachine(holder, v.tier, v.maxParallel) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Parallel Hatch (${v.maxParallel} Parallel)")
            .rotationState(RotationState.ALL)
            // ⚠️ 必须是 GTM 的能力对象，不能换成 GTET 自定义能力（见类 KDoc）
            .abilities(PartAbility.PARALLEL_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            // 贴图暂时复用 GTM 的并行仓（mk4）—— 与超频仓 / 线程仓同一张占位贴图，
            // 这是有意的临时占位（用户已确认「材质先用现成的」），不是漏掉或写错。
            // TODO 以后画 GTET 自己的并行仓贴图，把这里换成 block/machines/parallel_hatch_*
            .model(
                createWorkableTieredHullMachineModel(GTCEu.id("block/machines/parallel_hatch_mk4"))
                    .andThen { _, _, model ->
                        model.addReplaceableTextures("bottom", "top", "side")
                    }
            )
            .tooltips(
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }
}
