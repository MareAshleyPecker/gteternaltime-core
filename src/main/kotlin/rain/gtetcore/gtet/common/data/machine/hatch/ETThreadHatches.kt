package rain.gtetcore.gtet.common.data.machine.hatch

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
import rain.gtetcore.gtet.common.data.machine.hatch.ETThreadHatches.VARIANTS
import rain.gtetcore.gtet.common.data.machine.hatch.ETThreadHatches.register
import rain.gtetcore.gtet.common.data.machine.hatch.ETThreadHatches.registerOne
import rain.gtetcore.gtet.common.machine.multiblock.part.ThreadHatchPartMachine
import rain.gtetcore.gtet.util.lang.LangUtil


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
 * ## 显示只保留「电压等级 + 名称」
 * 与 [ETOverclockHatches] 同一套约定：每个变体**只**生成名字语言键 `block.gtetcore.<id>`，
 * 中文名里直接带上电压等级与线程数（例如「MAX 线程仓（256 线程）」），英文名走 `.langValue(...)`。
 * 说明性的多行 tooltip 与部件面板的说明行都已删除（清单见 [registerOne] 的注释）。
 * ⚠️ 不采用运行期插值的文案口径（「同时处理至多 %1$s 种不同配方，每种配方至多 %2$s 个」），
 * 线程数直接写进方块**名字**（注册时已由变体表确定，不需要运行期字符串插值）。
 *
 * ## 接线位置
 * 本文件的 [register] 由 `rain.gtetcore.gtet.common.data.machine.ALLMmchine.init()`
 * 调用一次（结果存进 `ALLMmchine.THREAD_HATCHES`），走的是与 [ETOverclockHatches] 完全相同的那条路径
 * —— 那里也是机器表 `unfreeze()` / `freeze()` 的窗口所在，不要另找入口重复注册。
 *
 * ⚠️ GTO 那侧的线程调度实现在加密 native 里（`libs/gtolib-1.0.jar` 的 `native0/native/`），
 * 一行实现都拿不到，只对得上字段与签名，本文件不依赖它。
 *
 * @author rain fox
 */
object ETThreadHatches {

    /**
     * 「线程仓」变体定义。
     *
     * 一个变体 = 一个方块。**线程数就写在这一行里**（与 [OverclockHatchVariant] 同构：
     * 规格是表的显式参数，扫一眼表就知道每档多少线程），注册时由 [ETThreadHatches.registerOne]
     * 原样传给 [ThreadHatchPartMachine] —— 表里写 4 就真的是 4，
     * 不用再去别处翻一个公式算它。
     *
     * ⚠️ 线程数与 tier 必须自己对上（4↔ZPM、8↔UV、16↔UHV、32↔UEV、64↔UIV、128↔UXV、256↔OpV、512↔MAX，
     * 从 ZPM 起每档相对上一档翻倍）；加档时这一行要一起改，
     * 别只改一半。名字里的线程数与本档上限取自**同一个** `threads`，所以这两处不会打架。
     *
     * @param id      注册名（同时决定方块 id 与名字语言键 `block.gtetcore.<id>`；
     *                本 mod **不再**为线程仓生成 tooltip / 面板说明键，见 [ETThreadHatches.registerOne]）
     * @param threads 该档的线程数上限（= 同时能跑的线程条数），直接传给 [ThreadHatchPartMachine]
     * @param tier    电压等级，决定外壳贴图
     *
     * @author rain fox
     */
    data class ThreadHatchVariant(
        val id: String,
        val threads: Int,
        val tier: Int
    )

    /**
     * 线程仓正面覆盖层的来源命名空间。
     *
     * ⚠️ 这批贴图是 **GTOCore 的素材**（版权归 GTOCore 作者所有，LGPL-3.0），
     * 随本 mod 一起分发、只引用不修改；来源与授权原文见 `assets/gtocore/LICENSE.txt`。
     */
    private const val GTOCORE_NS = "gtocore"

    /** GTOCore 线程仓覆盖层目录前缀：完整路径 = 本前缀 + mk 编号（`..._mk1` … `..._mk7`）。 */
    private const val THREAD_OVERLAY_ROOT = "block/machines/thread_hatch/thread_hatch_mk"

    /**
     * 变体对应的 GTOCore 覆盖层目录（`createWorkableTieredHullMachineModel` 的 `overlayDir` 参数）。
     *
     * GTOCore 自己的编号规则是 `mk = tier - ZPM`，注册区间也是 UV..MAX，
     * 与本族 [ThreadHatchVariant] 表**逐档一一对应**（`thread_hatch_uv` → `mk1` … `thread_hatch_max` → `mk7`），
     * 所以这里不需要任何取整/夹取。
     *
     * 与超频仓那套相比：这几套目录里多了 `overlay_front_active`，运行时 IDLE 与 WORKING
     * 会是两张不同的正面贴图（缺 back/top/bottom/side 覆盖层的情况两族一样，见 [ETOverclockHatches]）。
     */
    private fun overlayFor(v: ThreadHatchVariant): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(GTOCORE_NS, THREAD_OVERLAY_ROOT + (v.tier - GTValues.ZPM))

    /**
     * 全部线程仓变体：ZPM 起每级翻倍，一路到 MAX。
     *
     * 下表「线程数」那一列就是 [ThreadHatchVariant.threads] 的字面值
     * ```kotlin
     * val VARIANTS: List<ThreadHatchVariant> = listOf(
     *      ThreadHatchVariant("thread_hatch_zpm", 4  , GTValues.ZPM),
     *      ThreadHatchVariant("thread_hatch_uv" , 8  , GTValues.UV),
     *      ThreadHatchVariant("thread_hatch_uhv", 16 , GTValues.UHV),
     *      ThreadHatchVariant("thread_hatch_uev", 32 , GTValues.UEV),
     *      ThreadHatchVariant("thread_hatch_uiv", 64 , GTValues.UIV),
     *      ThreadHatchVariant("thread_hatch_uxv", 128, GTValues.UXV),
     *      ThreadHatchVariant("thread_hatch_opv", 256, GTValues.OpV),
     *      ThreadHatchVariant("thread_hatch_max", 512, GTValues.MAX),
     * )
     * ```
     *
     * ⚠️ 顺序即注册顺序（影响物品栏与存档里的方块出现次序），加档请往末尾追加、
     * 不要重排既有行、也不要改既有 id。
     *
     * 从 ZPM 起步：最低档 4 线程，往后每级翻倍。
     * ⚠️ 「线程数」与并行仓的「并行数」不是同一个量 —— 线程 = 同时跑**几种不同配方**，
     * 并行 = 同一种配方**同时跑几次**，所以两族的档位不用对齐。
     */
    val VARIANTS: List<ThreadHatchVariant> = listOf(
        ThreadHatchVariant("thread_hatch_zpm", 4, GTValues.ZPM),
        ThreadHatchVariant("thread_hatch_uv", 8, GTValues.UV),
        ThreadHatchVariant("thread_hatch_uhv", 16, GTValues.UHV),
        ThreadHatchVariant("thread_hatch_uev", 32, GTValues.UEV),
        ThreadHatchVariant("thread_hatch_uiv", 64, GTValues.UIV),
        ThreadHatchVariant("thread_hatch_uxv", 128, GTValues.UXV),
        ThreadHatchVariant("thread_hatch_opv", 256, GTValues.OpV),
        ThreadHatchVariant("thread_hatch_max", 512, GTValues.MAX),
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
        return variants.map { registerOne(registrate, it) }
    }

    /**
     * 注册单个变体。
     *
     * 中英双语走 GTET 现有机制，**只登记名字这一条键**（与 [ETOverclockHatches] 同一套显示约定）：
     * - 英文名 → `.langValue(...)`，由 Registrate 写进 `en_us` 的 `block.gtetcore.<id>`；
     * - 中文名 → [LangUtil.BLOCK_LANG]，由 `LangHandler` 写进 `zh_cn` 的同名键。
     * 两个名字里都带「电压等级 + 线程数」，一眼就能分出七档，不需要额外的说明行。
     *
     * 删掉的键（原设计有、现已随显示简化一并移除，`runData` 后不再出现在 `src/generated` 里）：
     * `gtetcore.machine.<id>.tooltip.0` / `.tooltip.1` / `.tooltip.2`（三条说明性提示）与
     * `gtetcore.machine.<id>.config`（部件面板输入框下面那行「线程数（1 - N）…」）。
     * 面板现在是「名字 + 数值输入框」，线程数范围由输入框自己的 min/max 卡住（见 `ThreadHatchPartMachine`）。
     */
    private fun registerOne(registrate: GTRegistrate, v: ThreadHatchVariant): MachineDefinition {
        // 名字里的线程数与部件上限**同源**：都取变体表这一行的 threads
        val threads = v.threads
        val tierName = GTValues.VN[v.tier]

        // 中文名按「电压等级 + 名称（线程数）」写：例如 MAX 线程仓（256 线程）
        LangUtil.BLOCK_LANG[v.id] = "$tierName 线程仓（$threads 线程）"

        return registrate
            // tier 与线程数都是变体表给的显式值，部件不做任何推导
            .machine(v.id) { holder -> ThreadHatchPartMachine(holder, v.tier, threads) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Thread Hatch ($threads Threads)")
            .rotationState(RotationState.ALL)
            .abilities(ETPartAbility.THREAD_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            // 贴图用 GTOCore 的线程仓覆盖层（按 tier 取 mk 编号，规则见 [overlayFor]）。
            // helper 内部把父模型定成 `gtceu:block/casings/voltage/<tier>`（电压等级外壳），
            // 再把 overlayDir 下的 `overlay_front` / `overlay_front_active` 叠在正面，
            // 所以本仓外观与 GTOCore 自己的线程仓一致，不需要我们再画。
            // ⚠️ 素材版权归 GTOCore 作者所有（LGPL-3.0），见 `assets/gtocore/LICENSE.txt`；只引用不修改。
            // TODO 以后画 GTET 自己的线程仓贴图，把 [THREAD_OVERLAY_ROOT] 换成自己的目录即可
            .model(
                createWorkableTieredHullMachineModel(overlayFor(v))
                    .andThen { _, _, model ->
                        model.addReplaceableTextures("bottom", "top", "side")
                    }
            )
            // 提示只剩 GTM 自带的那条：部件的说明性文字已经并进名字，不再单独生成 tooltip 键
            .tooltips(
                Component.translatable("gtceu.part_sharing.disabled")
            )
            .register()
    }
}
