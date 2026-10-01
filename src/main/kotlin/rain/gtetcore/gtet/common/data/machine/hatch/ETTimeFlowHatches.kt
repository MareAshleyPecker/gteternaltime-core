package rain.gtetcore.gtet.common.data.machine.hatch

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createWorkableTieredHullMachineModel
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.api.ETValues
import rain.gtetcore.gtet.api.capability.ETPartAbility
import rain.gtetcore.gtet.api.timeflow.ETTimeFlow
import rain.gtetcore.gtet.common.data.machine.hatch.ETTimeFlowHatches.VARIANTS
import rain.gtetcore.gtet.common.data.machine.hatch.ETTimeFlowHatches.overlayFor
import rain.gtetcore.gtet.common.data.machine.hatch.ETTimeFlowHatches.register
import rain.gtetcore.gtet.common.data.machine.hatch.ETTimeFlowHatches.registerOne
import rain.gtetcore.gtet.common.machine.multiblock.timeflow.TimeFlowHatchPartMachine
import rain.gtetcore.gtet.data.lang.TimeFlowHatchLang
import rain.gtetcore.gtet.util.ETPartSharing
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 「时序仓」（TF 供给仓）注册入口 —— 一档一个方块，与线程仓 / 超频仓族同构。
 *
 * 一个注册函数 + 一张变体表：遍历 [TimeFlowHatchVariant] 表逐个注册；
 * 与 [ETThreadHatches] / [ETOverclockHatches] 的三处同构约定：
 * 1. 用 **GTET 自己的** `ETRegistrate`（`OnlyETreg.ETRegistrate`），否则方块会注册进 `gtceu:` 命名空间；
 * 2. 变体的 id 唯一，所以注册名不拼 `VN[tier]` 前缀，直接用变体 id
 *    （档位本身**不保证唯一**：最高两档在 GTM 那侧同为 `MAX`，见下）；
 * 3. 每个变体**只**生成名字语言键 `block.gtetcore.<id>`，名字里直接带电压等级 + 小时数 + 容量，
 *    英文名走 `.langValue(...)`、中文名走 [LangUtil.BLOCK_LANG]，说明性文案只留一条
 *    （怎么绑定主控塔，见 [TimeFlowHatchLang.TOOLTIP]）。
 *
 * ## 档位：逻辑档位 vs 交给 GTM 的档位（⚠️ 别混）
 * 本族七档是 **UHV ~ ETV**，其中最高档 `ETV` 是**虚档位**（`= MAX + 1`，电压 `8589934592 EU/t`）。
 * GTM 的档位表只有 15 项（`0..MAX`），`GTValues.VN[15]` 直接数组越界，所以 `ETV` **不可能**作为
 * GTM 的档位索引存在 —— 变体表里存的是 `logicalTier`（我们自己的口径，文案与覆盖层用它），
 * 交给 GTM 的一律先过 `ETValues.gtmTierOf` 夹回 `MAX`（见 [TimeFlowHatchVariant.gtmTier]）。
 * 类注释细节见 [ETValues]。
 *
 * **后果（有意为之，不是 bug）**：`4096h=MAX` 与 `16384h=ETV` 两档**注册到 GTM 的档位都是 `MAX`**
 * —— 电压相同、外壳贴图相同，只有容量不同；唯一能把它们区分开的是按**逻辑档位**取的正面覆盖层
 * （见 [overlayFor]）。
 *
 * ## 接线位置与**注册顺序**（⚠️ 这条是硬要求）
 * 本文件的 [register] 由 `rain.gtetcore.gtet.common.data.machine.ALLSmachine.init()` 调用一次
 * （结果存进 `ALLSmachine.TIME_FLOW_HATCHES`）。
 * 必须是 `MachineRegister` 里 **`ALLSmachine.init()` 早于 `ALLMmachine.init()`** 的那个顺序：
 * `PartAbility#getAllBlocks()` 是**懒记忆化**的（`PartAbility` 内部的 `GTMemoizer`，首取即定、之后不再变），
 * 而 `Predicates.abilities(ETPartAbility.TF_HATCH)` 会在构造谓词那一刻就把它取出来
 * （`Predicates.abilities(...)` 内部是 `Arrays.stream(...).flatMap(getAllBlocks).toArray()`，立即求值）。
 * 这个快照今天**不会**提前发生（多方块的 `patternFactory` 是 `Supplier`、结构谓词到运行期才构造），
 * 但只要有人把图案改成「注册期就构造」，顺序错了就会静默地一个都插不进去。
 *
 * @author rain fox
 */
object ETTimeFlowHatches {

    /**
     * 「时序仓」变体定义。
     *
     * 一个变体 = 一个方块。**小时数与容量都写在这一行里**（与 [ThreadHatchVariant] 同构：
     * 规格是表的显式参数，扫一眼表就知道每档多少 TF），构造时由 [registerOne] 原样传给
     * [TimeFlowHatchPartMachine] —— 表里写什么就是什么，本类不做任何推导。
     *
     * ⚠️ **小时数与容量必须自己对上**（`容量 = 小时数 × 3600`，`1 小时 = 3600 TF`，设定 §2.2）；
     * 这一行要改就两列一起改。构造时的 `require` 会把写错的那一行直接炸出来，
     * 免得变成「名字写 16h、仓里其实装 1h」这种只在游戏里才发现的问题。
     *
     * ⚠️ 容量梯子**不是**纯 ×4 数列：`1h` 之后直接跳到 `16h`（×16），之后每档 ×4，到 `16384h` 封顶
     * （设定 §2.2 已定：明确不设 `4h` 与 `65536h`）。
     *
     * @param id          注册名（同时决定方块 id 与名字语言键 `block.gtetcore.<id>`）
     * @param hours       该档的小时数（只用于显示与校验，容量另给）
     * @param capacityTf  该档容量上限（TF），= `hours × ETTimeFlow.TF_PER_HOUR`
     * @param logicalTier **逻辑档位**（我们自己的口径，可能是虚档位 `ETV`）：文案、覆盖层用它；
     *                    交给 GTM 的那一份见 [gtmTier]
     * @param note        可选说明行（中英成对）；`null` = 这一档不加说明行
     *
     * @author rain fox
     */
    data class TimeFlowHatchVariant(
        val id: String,
        val hours: Int,
        val capacityTf: Long,
        val logicalTier: Int,
        val note: TimeFlowHatchNote? = null
    ) {
        /**
         * 交给 GTM 的档位 = [logicalTier] 过一遍 [ETValues.gtmTierOf]（虚档位夹回 `MAX`）。
         *
         * ⚠️ 两个档位**必须分开表达**：`MachineBuilder#tier` 与 `TieredPartMachine` 的构造参数
         * 都只能吃 `0..MAX`（GTM 的 `VN` 只有 15 项，见 [ETValues] 的类注释），而覆盖层编号
         * 是我们自己的字符串拼接、要用 [logicalTier] 算。
         */
        val gtmTier: Int get() = ETValues.gtmTierOf(logicalTier)

        init {
            require(logicalTier in 0..ETValues.MAX_TIER) {
                "TimeFlowHatchVariant '$id': logicalTier=$logicalTier 超出 0..${ETValues.MAX_TIER}"
            }
            require(capacityTf == hours.toLong() * ETTimeFlow.TF_PER_HOUR) {
                "TimeFlowHatchVariant '$id': capacityTf=$capacityTf 与 ${hours}h×${ETTimeFlow.TF_PER_HOUR} 对不上"
            }
        }
    }

    /**
     * 一条物品提示（tooltip）文案，中英**必须成对**给。
     *
     * 中文写进 `zh_cn`、英文写进 `en_us`，键名由 [registerOne] 生成成
     * `gtetcore.machine.<id>.tooltip.0`（与超频仓 / 测试多方块同一套）。
     * 只有填了 [TimeFlowHatchVariant.note] 的档位才生成这条键。
     */
    data class TimeFlowHatchNote(val cn: String, val en: String)

    /**
     * 时序仓正面覆盖层的来源命名空间。
     *
     * ⚠️ 这批贴图是 **GTOCore 的素材**（版权归 GTOCore 作者所有，LGPL-3.0），
     * 随本 mod 一起分发、只引用不修改；来源与授权原文见 `assets/gtocore/LICENSE.txt`。
     */
    private const val GTOCORE_NS = "gtocore"

    /**
     * 本族用的 GTOCore 覆盖层目录：**加速仓**（`accelerate_hatch`）。
     *
     * 时序仓没有自己的美术资源，这里按「材质先用现成的」的既有约定借一张语义最接近的
     * （加速 / 时间），只作占位。目录里有 `overlay_front` 与 `overlay_front_active`，
     * IDLE 与 WORKING 是两张不同的正面贴图（与线程仓那套一致）。
     */
    private const val OVERLAY_ROOT = "block/machines/accelerate_hatch/accelerate_hatch_mk"

    /**
     * 变体对应的 GTOCore 覆盖层目录（`createWorkableTieredHullMachineModel` 的 `overlayDir` 参数）。
     *
     * 与 [ETThreadHatches] 用同一条编号规则 `mk = 档位 - ZPM`：本族是 UHV..ETV 七档
     * ⇒ `mk2`..`mk8`，逐档一一对应，不需要取整、也不会两档撞同一张。
     * （GTOCore 的加速仓目录 `mk1`..`mk14` 都在本仓资源里，所以这七档一定存在。）
     *
     * ⚠️ 这里**故意用 [TimeFlowHatchVariant.logicalTier]**（不是夹过的 [TimeFlowHatchVariant.gtmTier]）：
     * 目录名是我们自己拼的字符串、不是 GTM 的档位索引，`ETV(15) - ZPM(7) = 8` 完全合法（`mk8` 存在），
     * 所以**不存在越界或取错**——原来的 `(tier - ZPM)` 写法碰到 `ETV` 也不会炸，只是没人核对过它取到谁。
     * 好处是 `MAX` 拿 `mk7`、虚档位 `ETV` 拿 `mk8`：这两档在 GTM 那侧同为 `MAX`（外壳贴图必然一样，
     * 见 [TimeFlowHatchVariant.gtmTier]），正面覆盖层是它们**唯一**能区分开的地方。
     * 若改用夹过的档位，两件方块会长得一模一样。
     */
    private fun overlayFor(v: TimeFlowHatchVariant): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(GTOCORE_NS, OVERLAY_ROOT + (v.logicalTier - GTValues.ZPM))

    /**
     * 全部时序仓变体（7 档，UHV ~ ETV），容量按设定 §2.2 定稿值写死：
     * `1h=3600 / 16h=57600 / 64h=230400 / 256h=921600 / 1024h=3686400 / 4096h=14745600 / 16384h=58982400`（TF）。
     *
     * ⚠️ 电压等级排布（UHV=9 … MAX=14、最高档 `ETV`=15）是本表定稿值：最高一档就是设定里的
     * **ETV**（`= MAX + 1`，电压 `8589934592 EU/t` = `GTValues.V[MAX] × 4`，见 `README/zh_CN/对照表.md`）。
     * `ETV` 是**虚档位** —— GTM 只有 15 档，所以表里存的是 `logicalTier`，注册时再由
     * [TimeFlowHatchVariant.gtmTier] 夹回 `MAX`；理由与后果见本文件类注释与 [ETValues]。
     *
     * ⚠️ **最高两档（`4096h=MAX` 与 `16384h=ETV`）交给 GTM 的档位都是 `MAX`**（=14）：
     * 电压相同、外壳贴图相同、只有容量不同。这是虚档位的**必然结果、不是 bug**。
     *
     * ⚠️ 顺序即注册顺序（影响物品栏与存档里的方块出现次序），加档请往末尾追加、
     * 不要重排既有行、也不要改既有 id。
     */
    val VARIANTS: List<TimeFlowHatchVariant> = listOf(
        TimeFlowHatchVariant("tf_hatch_1h", 1, 3_600L, GTValues.UHV),
        TimeFlowHatchVariant("tf_hatch_16h", 16, 57_600L, GTValues.UEV),
        TimeFlowHatchVariant("tf_hatch_64h", 64, 230_400L, GTValues.UIV),
        TimeFlowHatchVariant("tf_hatch_256h", 256, 921_600L, GTValues.UXV),
        TimeFlowHatchVariant("tf_hatch_1024h", 1024, 3_686_400L, GTValues.OpV),
        TimeFlowHatchVariant("tf_hatch_4096h", 4096, 14_745_600L, GTValues.MAX),
        // 最高档是虚档位：GTM 那侧只能显示 MAX，所以这里明写一行电压，免得玩家以为买到了 MAX 仓
        TimeFlowHatchVariant(
            "tf_hatch_16384h", 16384, 58_982_400L, ETValues.ETV,
            note = TimeFlowHatchNote(
                cn = "电压：ETV（${ETValues.ETV_VOLTAGE} EU/t，在 MAX 之上的一档）",
                en = "Voltage: ETV (${ETValues.ETV_VOLTAGE} EU/t, one tier above MAX)"
            )
        ),
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
        variants: List<TimeFlowHatchVariant> = VARIANTS
    ): List<MachineDefinition> {
        return variants.map { registerOne(registrate, it) }
    }

    /**
     * 注册单个变体。
     *
     * 中英双语走 GTET 现有机制，**名字是必有的一条键**（与 [ETThreadHatches] 同一套显示约定）：
     * 中文名里带「电压等级 + 时序仓（小时数 / 容量 TF）」，一眼分得出七档。
     * 提示默认只有两条：GTM 自带的「部件不可共享」+ 怎么绑定主控塔（[TimeFlowHatchLang.TOOLTIP]，中英共用一条键）；
     * 变体表里填了 [TimeFlowHatchVariant.note] 的档位（目前只有 `16384h`）会再多一条
     * `gtetcore.machine.<id>.tooltip.0`。
     *
     * ⚠️ 名字里的档位名走 [ETValues.nameOf]（**逻辑档位**）：`ETV` 那一档显示 `ETV`，
     * 而它在 GTM 那侧其实是 `MAX`（`GTValues.VN` 里没有 `ETV`，硬取会越界）。
     */
    private fun registerOne(registrate: GTRegistrate, v: TimeFlowHatchVariant): MachineDefinition {
        val tierName = ETValues.nameOf(v.logicalTier)
        val capacity = v.capacityTf

        // 名字里的容量与仓的上限**同源**：都取变体表这一行的 capacityTf
        LangUtil.BLOCK_LANG[v.id] = "$tierName 时序仓（${v.hours} 小时 / $capacity TF）"

        // 可选说明行：登记双语键并额外挂一条提示；没填 note 的档位这里是空数组，等于不加
        val tooltipKey = "gtetcore.machine.${v.id}.tooltip.0"
        val extraTooltips: Array<Component> = v.note?.let {
            LangUtil.add(tooltipKey, it.en, it.cn)
            arrayOf(Component.translatable(tooltipKey))
        } ?: emptyArray()

        return registrate
            // 部件拿的是**逻辑档位**（它自己会在 super 那一层夹），见 TimeFlowHatchPartMachine 的类注释
            .machine(v.id) { holder -> TimeFlowHatchPartMachine(holder, v.logicalTier, capacity) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            // ⚠️ 这里必须是**夹过的** GTM 档位（虚档位 ETV → MAX）：分级外壳贴图内部是
            //    `GTValues.VN[tier]`（GTMachineModels.getTieredHullTexture，无边界检查），传 15 直接越界
            .tier(v.gtmTier)
            .langValue("$tierName Time Flow Hatch (${v.hours}h / $capacity TF)")
            .rotationState(RotationState.ALL)
            // ⚠️ 本能力就是设定 §2.3「这台多方块支持 TF」的标记位，见 ETPartAbility.TF_HATCH
            .abilities(ETPartAbility.TF_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            // 贴图暂借 GTOCore 的加速仓覆盖层（按**逻辑档位**取 mk 编号，规则见 [overlayFor]）。
            // helper 内部把父模型定成 `gtceu:block/casings/voltage/<取 gtmTier>`（电压等级外壳；
            // 虚档位 ETV 因此落到 `voltage/max`，与 4096h 同款），再把 overlayDir 下的
            // overlay_front / overlay_front_active 叠在正面。
            // ⚠️ 素材版权归 GTOCore 作者所有（LGPL-3.0），见 `assets/gtocore/LICENSE.txt`；只引用不修改。
            // TODO 以后画 GTET 自己的时序仓贴图，把 [OVERLAY_ROOT] 换成自己的目录即可
            .model(
                createWorkableTieredHullMachineModel(overlayFor(v))
                    .andThen { _, _, model ->
                        model.addReplaceableTextures("bottom", "top", "side")
                    }
            )
            // 提示 = 「多方块共享」那一行（默认状态下是 GTM 的 `gtceu.part_sharing.disabled`）+ 本件的容量说明。
            // 走 tooltipBuilder 而不是 tooltips()：本件的 `canShared()` 读全局配置 `multiblock.partsShareable`，
            // 那一行要**渲染时**才决定取 `gtceu.part_sharing.enabled` 还是 `…disabled`（见 [ETPartSharing.line]）。
            // ⚠️ MachineBuilder 里 tooltips() 与 tooltipBuilder() 是**追加**关系、不是覆盖
            //    （GTM MachineBuilder.java:693-696：先 `components.addAll(tooltips)` 再跑 builder），
            //    所以这里把原有的两行原样搬进 lambda 只为保住顺序，一行都没丢。
            .tooltipBuilder { _, list ->
                list.add(ETPartSharing.line())
                list.add(Component.translatable(TimeFlowHatchLang.TOOLTIP))
                list.addAll(extraTooltips)
            }
            .register()
    }
}