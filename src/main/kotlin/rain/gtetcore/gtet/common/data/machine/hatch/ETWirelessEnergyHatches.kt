package rain.gtetcore.gtet.common.data.machine.hatch

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createOverlayTieredHullMachineModel
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.common.data.machine.hatch.ETWirelessEnergyHatches.AMPERAGES
import rain.gtetcore.gtet.common.data.machine.hatch.ETWirelessEnergyHatches.VARIANTS
import rain.gtetcore.gtet.common.data.machine.hatch.ETWirelessEnergyHatches.modelFor
import rain.gtetcore.gtet.common.data.machine.hatch.ETWirelessEnergyHatches.register
import rain.gtetcore.gtet.common.machine.multiblock.part.WirelessEnergyHatchPartMachine
import rain.gtetcore.gtet.data.lang.WirelessEnergyHatchLang
import rain.gtetcore.gtet.util.ETPartSharing
import rain.gtetcore.gtet.util.lang.LangUtil
import java.util.*

/**
 * 「无线能源仓」注册入口 —— **10 个电压档 × 11 个安培档 = 110 个方块定义**（设定 §2.1）。
 *
 * 与 [ETTimeFlowHatches] / [ETOverclockHatches] / [ETThreadHatches] 同构：一个注册函数 + 一张变体表，
 * 遍历表逐个注册；用 **GTET 自己的** `ETRegistrate`（否则方块会注册进 `gtceu:` 命名空间）。
 *
 * ## 档位与 id（设定 §2.1）
 * | 维度 | 取值 |
 * |---|---|
 * | 电压 | `IV / LuV / ZPM / UV / UHV / UEV / UIV / UXV / OpV / MAX`（10 档） |
 * | 安培 | 小仓 `1 / 16 / 64`；大仓 `256 / 1024 / 4096 / 16384 / 65536 / 262144 / 1048576 / 4194304`（8 档，每档 ×4） |
 *
 * id 规则 `wireless_energy_hatch_<安培>a_<电压名小写>`，例如 `wireless_energy_hatch_1a_iv`、
 * `wireless_energy_hatch_4194304a_max`（电压名走 `GTValues.VN`，与 GTM 自己的仓同一套拼法）。
 *
 * ⚠️ **本族没有虚档位**：电压最高只到 `MAX`（不像时序仓那样有 `ETV = MAX + 1`），
 * 所以 `MachineBuilder#tier` 与 `TieredPartMachine` 可以吃同一个档位，不需要 `ETValues.gtmTierOf` 夹取。
 *
 * ## 能力：用 GTM 现成的 `PartAbility.INPUT_ENERGY`，**不新建能力、也不加 mixin**
 * 「能力登记」与「结构能插」是两件事：前者是 `MachineBuilder#abilities(...)`，后者由**结构谓词**说了算。
 * 本族**刻意复用 GTM 的 `INPUT_ENERGY`**，于是它天然被任意 GTM / GCYM 多方块的通用谓词接受 ——
 * 静态证据（GTM 7.5.3 源码树）：
 * ```
 * Predicates.java:156-164   autoAbilities(...) 里 checkEnergyIn 那一支：
 *                             predicate = predicate.or(abilities(PartAbility.INPUT_ENERGY)
 *                                      .setMinGlobalLimited(1).setMaxGlobalLimited(2).setPreviewCount(1));
 * Predicates.java:134-137   abilities(...) = blocks(ability.getAllBlocks()) —— 立即求值
 * PartAbility.java:29       INPUT_ENERGY = new PartAbility("input_energy")
 * ```
 * ⇒ 任何用 `autoAbilities(...)` 且配方类型吃 EU 的多方块，都会给 `INPUT_ENERGY` 留槽，
 * 我们的方块就在那张方块表里 —— **不需要**动 `MixinPredicatesAutoAbilities`
 * （那个 mixin 是给 GTET 自建能力 `OVERCLOCK_HATCH` / `TF_HATCH` 补槽用的，本族用不上）。
 * 副作用一条：槽位带 `setMaxGlobalLimited(2)`，所以本族与 GTM 自家能源仓**共用那两个名额**，
 * 这是 GTM 的意思，不是我们加的。
 *
 * ## 贴图：直接借 GTM 的扁平覆盖层模型（按安培档选）
 * `overlayTieredHullModel` 的那条 helper 会**按 `tier` 自动套电压外壳**
 * （`GTMachineModels#createOverlayTieredHullMachineModel` → `tieredHullTextures(model, tier)`），
 * 所以电压档靠外壳区分、安培档靠正面覆盖层区分：
 *
 * | 安培 | 借用的 GTM 模型 | 正面用的贴图 |
 * |---|---|---|
 * | 1A | `gtceu:block/machine/part/energy_input_hatch` | `overlay_energy_1a_in`（+ `…_tinted` / `…_in_emissive`） |
 * | 16A | `gtceu:block/machine/part/energy_input_hatch_16a` | `overlay_energy_16a_*` |
 * | 64A | `gtceu:block/machine/part/energy_input_hatch_64a` | `overlay_energy_64a_*` |
 * | 256A ~ 4194304A（8 档共用一张） | `gtceu:block/machine/part/laser_target_hatch` | `overlay_laser_base` + `overlay_laser_target*` |
 *
 * 两条取舍写在明面上：
 * 1. GTM 那个 `energy_input_hatch` 模型**本来就是它 2A 仓用的**（`GTMachines.java:670-686`），
 *    而它的正面贴图实测是 `overlay_energy_1a_*`（模型 JSON 的 `textures.overlay_in`），
 *    所以 1A 档借它 = 正面就是 1A 的图；16A / 64A 各自有专属模型，逐档对得上。
 * 2. **大仓 8 档只能共用 laser 那一套**：GTM 没有 256A 以上的能源仓正面贴图
 *    （激光仓的安培区别体现在别处）。代价是 laser 模型**没有 tintindex 2 那层染色面**，
 *    大仓不会像小仓那样跟着电压变色 —— 想变色就得改用 energy 系模型，但那样安培就认不出来了，
 *    这里选了「安培认得出来」。
 * ⚠️ 贴图是 **GTM 自带素材**（`gtceu` 命名空间），本 mod 只引用、不新增也不修改任何图片文件。
 * TODO 以后画 GTET 自己的无线能源仓正面贴图，把 [modelFor] 换成自己的目录即可。
 *
 * ## 提示：默认两条
 * GTM 自带的「部件不可共享」那一行（走 [ETPartSharing.line]，因为本件 `canShared()` 读全局配置）
 * + 一条说明损耗与绑定方式的共用行（[WirelessEnergyHatchLang.TOOLTIP]，110 档共用一条键）。
 *
 * ## ⚠️ 注册顺序
 * 本文件的 [register] 由 `ALLSmachine.init()` 调用，而 `ALLSmachine.init()` 必须早于
 * `ALLMmachine.init()`：`PartAbility#getAllBlocks()` 是**首取即定的快照**
 * （`PartAbility.java:61-63` 的 `GTMemoizer`），`Predicates.abilities(...)` 在构造谓词那一刻就取值。
 * 详见 [ETTimeFlowHatches] 的类注释。
 *
 * @author rain fox
 */
object ETWirelessEnergyHatches {

    /**
     * 电压档（设定 §2.1）：**IV 起、MAX 止，共 10 档**。
     *
     * ⚠️ 这里全部是 `GTValues` 里**真实存在**的档位（不是虚档位），可以直接交给 GTM。
     */
    private val TIERS: List<Int> = listOf(
        GTValues.IV, GTValues.LuV, GTValues.ZPM, GTValues.UV, GTValues.UHV,
        GTValues.UEV, GTValues.UIV, GTValues.UXV, GTValues.OpV, GTValues.MAX
    )

    /**
     * 安培档（设定 §2.1）：小仓 3 档 + 大仓 8 档，共 11 档。
     *
     * 小仓 `1 / 16 / 64` 属于「普通能源仓范畴」；大仓 `256` 起**每档 ×4**，
     * 到 `4194304 = 2²²` 封顶（256 × 4⁷ 的第 8 档），属「激光仓范畴」。
     */
    private val AMPERAGES: List<Int> = listOf(
        1, 16, 64,
        256, 1024, 4096, 16384, 65536, 262144, 1048576, 4194304
    )

    /** 小仓与大仓的分界安培：`<= 64` 是小仓（普通能源仓正面），`>= 256` 是大仓（激光系列正面）。 */
    private const val SMALL_HATCH_MAX_AMP: Int = 64

    /** 本族应有的方块数（设定 §2.1：10 × 11 = 110）；表或档位改动时把这一条一起改。 */
    const val EXPECTED_COUNT: Int = 110

    /**
     * 「无线能源仓」变体定义。
     *
     * 一个变体 = 一个方块。**电压与安培都写在这一行里**，其余一切（id、名字）都由它们推出 ——
     * 表里写什么就是什么，本类不做任何额外推导。
     *
     * ⚠️ **容量不在这张表里**：它由机器类按 `V[tier] × 64 × amperage` 现算
     * （见 [WirelessEnergyHatchPartMachine.capacityEu]，设定 §7 第 8 条）——
     * 两处各写一份迟早会对不上，所以只留机器那一份当唯一真值。
     *
     * @param tier     电压档位（`GTValues.IV` ~ `GTValues.MAX`）
     * @param amperage 安培档（见 [AMPERAGES]）
     *
     * @author rain fox
     */
    data class WirelessEnergyHatchVariant(val tier: Int, val amperage: Int) {

        /** 中文 / 英文名里的电压名（`IV` / `LuV` / … / `MAX`），与 GTM 自己的仓同一套口径。 */
        val tierName: String get() = GTValues.VN[tier]

        /**
         * 注册名 / 方块 id（同时是名字语言键 `block.gtetcore.<id>` 的后半段）：
         * `wireless_energy_hatch_<安培>a_<电压名小写>`。
         *
         * 小写一律过 [Locale.ROOT]，免得在土耳其语环境里 `I` 被折成 `ı`（`iv` → `ıv`）。
         */
        val id: String get() = "wireless_energy_hatch_${amperage}a_${tierName.lowercase(Locale.ROOT)}"

        init {
            require(tier in GTValues.IV..GTValues.MAX) {
                "WirelessEnergyHatchVariant: tier=$tier 超出 IV..MAX（本族没有虚档位）"
            }
            require(amperage > 0) { "WirelessEnergyHatchVariant: amperage=$amperage 必须为正" }
        }
    }

    /**
     * 全部无线能源仓变体 = **电压档 × 安培档**（10 × 11 = 110）。
     *
     * 顺序 = 注册顺序 = 创造页里的出现次序：**外层电压（IV → MAX）、内层安培（1A → 4194304A）**，
     * 也就是「一个电压档一整套」。加档请往末尾追加，不要重排既有行、也不要改既有 id。
     */
    val VARIANTS: List<WirelessEnergyHatchVariant> = TIERS.flatMap { tier ->
        AMPERAGES.map { amp -> WirelessEnergyHatchVariant(tier, amp) }
    }.also { variants ->
        require(variants.size == EXPECTED_COUNT) {
            "无线能源仓变体数 ${variants.size} ≠ $EXPECTED_COUNT（设定 §2.1：10 档电压 × 11 档安培）"
        }
        require(variants.map { it.id }.toSet().size == variants.size) {
            "无线能源仓变体 id 有重复"
        }
    }

    /** GTM 自带素材所属的命名空间（只引用、不分发，见类注释的贴图一节）。 */
    private const val GTM_NS = "gtceu"

    /** 电压外壳 + 安培正面：模型名 → 完整资源路径的前缀。 */
    private const val GTM_PART_MODEL_PREFIX = "block/machine/part/"

    /**
     * 该安培档借用的 GTM 部件模型。
     *
     * 映射与理由见类注释的表格；一句话：**小仓三档各拿本安培的正面**，大仓 8 档共用激光系列。
     */
    private fun modelFor(amperage: Int): ResourceLocation {
        val name = when {
            amperage <= 1 -> "energy_input_hatch"       // 正面是 overlay_energy_1a_*
            amperage <= 16 -> "energy_input_hatch_16a"  // 正面是 overlay_energy_16a_*
            amperage <= SMALL_HATCH_MAX_AMP -> "energy_input_hatch_64a"
            else -> "laser_target_hatch"                // 256A ~ 4194304A 八档共用
        }
        return ResourceLocation.fromNamespaceAndPath(GTM_NS, GTM_PART_MODEL_PREFIX + name)
    }

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
        variants: List<WirelessEnergyHatchVariant> = VARIANTS
    ): List<MachineDefinition> {
        // 双语键在这里登记一次就够了：110 个变体共用同一条说明行（内容与档位无关），
        // 逐档各写一条只会让 zh_cn / en_us 白胖 110 行。
        // ⚠️ 这一步必须发生在数据生成之前 —— register() 由 ALLSmachine.init() 在机器注册期调用。
        WirelessEnergyHatchLang.init()
        return variants.map { registerOne(registrate, it) }
    }

    /**
     * 注册单个变体。
     *
     * 中英双语走 GTET 现有机制：
     * - 英文名 → `.langValue(...)`，由 Registrate 写进 `en_us` 的 `block.gtetcore.<id>`；
     * - 中文名 → [LangUtil.BLOCK_LANG]，由 `LangHandler` 写进 `zh_cn` 的同名键。
     *
     * 提示是**两条**：GTM 自带的「部件不可共享」那一行（走 [ETPartSharing.line]，
     * 因为本件的 `canShared()` 读全局配置、要在渲染时才能决定取 Enabled 还是 Disabled），
     * 加上说明损耗与绑定方式的共用行。
     */
    private fun registerOne(registrate: GTRegistrate, v: WirelessEnergyHatchVariant): MachineDefinition {
        val tierName = v.tierName
        val amp = v.amperage

        // 名称里带「电压等级 + 名称（安培）」，一眼分得出 110 档；容量写在 tooltip 行的语境里不必重复
        LangUtil.BLOCK_LANG[v.id] = "$tierName 无线能源仓（${amp}A）"

        return registrate
            .machine(v.id) { holder -> WirelessEnergyHatchPartMachine(holder, v.tier, amp) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Wireless Energy Hatch (${amp}A)")
            .rotationState(RotationState.ALL)
            // ⚠️ 复用 GTM 的 INPUT_ENERGY：这样其天然能被任意 GTM / GCYM 多方块的通用谓词接受，
            //    不需要动 MixinPredicatesAutoAbilities —— 静态证据见本文件类注释
            .abilities(PartAbility.INPUT_ENERGY)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            // 电压外壳由 helper 按 tier 自动套（`tieredHullTextures(model, tier)`），
            // 安培靠正面覆盖层区分（映射见 [modelFor]）。
            // ⚠️ 走 ResourceLocation 那个重载：String 重载用的是 `registrate.getModid()`，
            //    会去 gtetcore 命名空间找模型（本 mod 没有这套素材），必须显式指到 gtceu。
            .model(createOverlayTieredHullMachineModel(modelFor(amp)))
            .tooltipBuilder { _, list ->
                list.add(ETPartSharing.line())
                list.add(Component.translatable(WirelessEnergyHatchLang.TOOLTIP))
            }
            .register()
    }
}