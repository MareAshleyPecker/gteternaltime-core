package rain.gtetcore.gtet.data.lang

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.mojang.logging.LogUtils
import com.tterrag.registrate.providers.RegistrateLangProvider
import net.minecraft.data.PackOutput
import net.minecraftforge.common.data.LanguageProvider
import org.slf4j.Logger
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.util.lang.LangUtil
import java.lang.reflect.Field

/**
 * 集中式语言数据生成器：本 mod 的全部语言键在这里集中登记，再统一写进数据生成器。
 *
 * 材料物品翻译由 `material.<name>` + `tagprefix.<name>` 运行时组合，无需逐个前缀生成。
 */
object LangHandler {

    private val LOGGER: Logger = LogUtils.getLogger()

    // ======================== 前缀格式 ========================

    @JvmStatic
    val ZH_PREFIX_FORMATS: Map<TagPrefix, String> = mapOf(
        // 基础材料
        TagPrefix.ingot to "%s锭",
        TagPrefix.ingotHot to "热%s锭",
        TagPrefix.dust to "%s粉",
        TagPrefix.dustSmall to "小撮%s粉",
        TagPrefix.dustTiny to "小堆%s粉",
        TagPrefix.dustImpure to "含杂%s粉",
        TagPrefix.dustPure to "纯净%s粉",
        TagPrefix.nugget to "%s粒",
        TagPrefix.plate to "%s板",
        TagPrefix.plateDense to "致密%s板",
        TagPrefix.plateDouble to "双%s板",
        TagPrefix.rod to "%s杆",
        TagPrefix.rodLong to "长%s杆",
        TagPrefix.gear to "%s齿轮",
        TagPrefix.gearSmall to "小%s齿轮",
        TagPrefix.screw to "%s螺丝",
        TagPrefix.bolt to "%s螺栓",
        TagPrefix.ring to "%s环",
        TagPrefix.spring to "%s弹簧",
        TagPrefix.springSmall to "小%s弹簧",
        TagPrefix.wireFine to "细%s线",
        TagPrefix.rotor to "%s转子",
        TagPrefix.foil to "%s箔",
        TagPrefix.round to "%s圆",
        TagPrefix.lens to "%s透镜",
        TagPrefix.dye to "%s染料",
        // 宝石
        TagPrefix.gem to "%s",
        TagPrefix.gemChipped to "碎裂%s",
        TagPrefix.gemFlawed to "有瑕%s",
        TagPrefix.gemFlawless to "无瑕%s",
        TagPrefix.gemExquisite to "精致%s",
        // 矿石
        TagPrefix.ore to "%s矿石",
        TagPrefix.oreGranite to "花岗岩%s矿石",
        TagPrefix.oreDiorite to "闪长岩%s矿石",
        TagPrefix.oreAndesite to "安山岩%s矿石",
        TagPrefix.oreRedGranite to "红色花岗岩%s矿石",
        TagPrefix.oreMarble to "大理石%s矿石",
        TagPrefix.oreDeepslate to "深板岩%s矿石",
        TagPrefix.oreTuff to "凝灰岩%s矿石",
        TagPrefix.oreSand to "沙子%s矿石",
        TagPrefix.oreRedSand to "红沙%s矿石",
        TagPrefix.oreGravel to "沙砾%s矿石",
        TagPrefix.oreBasalt to "玄武岩%s矿石",
        TagPrefix.oreNetherrack to "下界%s矿石",
        TagPrefix.oreBlackstone to "黑石%s矿石",
        TagPrefix.oreEndstone to "末地%s矿石",
        TagPrefix.rawOre to "粗%s",
        TagPrefix.rawOreBlock to "粗%s块",
        TagPrefix.crushedRefined to "精炼%s矿石",
        TagPrefix.crushedPurified to "纯化%s矿石",
        TagPrefix.crushed to "粉碎%s矿石",
        // 工具头
        TagPrefix.toolHeadBuzzSaw to "%s圆锯刃",
        TagPrefix.toolHeadScrewdriver to "%s螺丝刀头",
        TagPrefix.toolHeadDrill to "%s钻头",
        TagPrefix.toolHeadChainsaw to "%s链锯头",
        TagPrefix.toolHeadWrench to "%s扳手头",
        TagPrefix.toolHeadWireCutter to "%s剪线头",
        TagPrefix.turbineBlade to "%s涡轮叶片",
        // 线缆
        TagPrefix.wireGtSingle to "1x %s线",
        TagPrefix.wireGtDouble to "2x %s线",
        TagPrefix.wireGtQuadruple to "4x %s线",
        TagPrefix.wireGtOctal to "8x %s线",
        TagPrefix.wireGtHex to "16x %s线",
        TagPrefix.cableGtSingle to "1x %s线缆",
        TagPrefix.cableGtDouble to "2x %s线缆",
        TagPrefix.cableGtQuadruple to "4x %s线缆",
        TagPrefix.cableGtOctal to "8x %s线缆",
        TagPrefix.cableGtHex to "16x %s线缆",
        // 管道
        TagPrefix.pipeTinyFluid to "微型%s流体管道",
        TagPrefix.pipeSmallFluid to "小型%s流体管道",
        TagPrefix.pipeNormalFluid to "普通%s流体管道",
        TagPrefix.pipeLargeFluid to "大型%s流体管道",
        TagPrefix.pipeHugeFluid to "巨型%s流体管道",
        TagPrefix.pipeQuadrupleFluid to "四重%s流体管道",
        TagPrefix.pipeNonupleFluid to "九重%s流体管道",
        TagPrefix.pipeSmallItem to "小型%s物品管道",
        TagPrefix.pipeNormalItem to "普通%s物品管道",
        TagPrefix.pipeLargeItem to "大型%s物品管道",
        TagPrefix.pipeHugeItem to "巨型%s物品管道",
        TagPrefix.pipeSmallRestrictive to "小型限制%s物品管道",
        TagPrefix.pipeNormalRestrictive to "普通限制%s物品管道",
        TagPrefix.pipeLargeRestrictive to "大型限制%s物品管道",
        TagPrefix.pipeHugeRestrictive to "巨型限制%s物品管道",
        // 建筑
        TagPrefix.block to "%s方块",
        TagPrefix.frameGt to "%s框架",
        TagPrefix.log to "%s原木",
        TagPrefix.planks to "%s木板",
        TagPrefix.slab to "%s台阶",
        TagPrefix.stairs to "%s楼梯",
        TagPrefix.fence to "%s栅栏",
        TagPrefix.fenceGate to "%s栅栏门",
        TagPrefix.door to "%s门",
        TagPrefix.rock to "%s岩石",
        TagPrefix.surfaceRock to "%s地表岩石",
    )

    // ======================== 入口 ========================

    /** Registrate LANG 回调，合并到 Registrate 的 en_us 数据（而非覆盖）。 */
    @JvmStatic
    fun init(provider: RegistrateLangProvider) {
        generate(provider, "en_us")
    }

    class ZhCNProvider(packOutput: PackOutput) : LanguageProvider(packOutput, Gtetcore.MODID, "zh_cn") {
        override fun addTranslations() {
            // 清除 GTCEu 写入的 en_us tagprefix 串值
            try {
                val dataField = LanguageProvider::class.java.getDeclaredField("data")
                dataField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val map = dataField.get(this) as MutableMap<String, String>
                map.keys.removeIf { it.startsWith("tagprefix.") }
            } catch (_: Exception) {
            }
            generate(this, "zh_cn")
        }
    }

    // ======================== 生成 ========================

    private fun generate(provider: LanguageProvider, locale: String) {
        var count = 0
        Lang.init(provider, locale)
        count += autoGenMaterialLang(provider, locale)
        count += autoGenTagPrefixLang(provider, locale)
        count += autoGenUtilLang(provider, locale)
        count += autoGenCustomLang(provider, locale)
        LOGGER.info("LangHandler[{}] wrote {} entries", locale, count)
    }

    // ======================== 材料名 ========================

    private fun autoGenMaterialLang(provider: LanguageProvider, locale: String): Int {
        var count = 0
        val selfMaterials = GTCEuAPI.materialManager.registries
            .filter { it.modid == Gtetcore.MODID }
            .flatMap { it.allMaterials }
        for (material in selfMaterials) {
            val key = material.unlocalizedName
            when (locale) {
                // en_us 材料名由 GTCEu MaterialLangGenerator 自动生成，无需重复添加
                "zh_cn" -> {
                    ZH_MATERIAL_NAMES[material.name]?.let {
                        provider.add(key, it)
                        count++
                    }
                }
            }
        }
        return count
    }

    @JvmStatic
    val ZH_MATERIAL_NAMES: MutableMap<String, String> = LinkedHashMap()

    @JvmStatic
    fun addMaterialName(materialName: String, cnName: String): LangHandler {
        ZH_MATERIAL_NAMES[materialName] = cnName
        return this
    }

    // ======================== TagPrefix 格式 ========================

    private fun autoGenTagPrefixLang(provider: LanguageProvider, locale: String): Int {
        var count = 0
        for (tagPrefix in TagPrefix.values()) {
            val key = tagPrefix.unlocalizedName
            when (locale) {
                "en_us" -> {
                    (CUSTOM_EN_PREFIX_FORMATS[tagPrefix.name] ?: tagPrefix.langValue)?.let {
                        provider.add(key, it); count++
                    }
                }

                "zh_cn" -> {
                    // 用 replace 强制覆盖 GTCEu 写入的英文 tagprefix
                    val cn = ZH_PREFIX_FORMATS[tagPrefix] ?: "%s"
                    replace(provider, key, cn); count++
                }
            }
        }
        return count
    }

    @JvmStatic
    val CUSTOM_EN_PREFIX_FORMATS: MutableMap<String, String> = LinkedHashMap()

    // ======================== 物品/方块中文名 ========================

    /**
     * 消费 [LangUtil.ITEM_LANG] / [LangUtil.BLOCK_LANG] / [LangUtil.TAB_LANG]，
     * 生成 `item.gtetcore.<name>` / `block.gtetcore.<name>` / `itemGroup.gtetcore.<name>` 中文条目。
     */
    private fun autoGenUtilLang(provider: LanguageProvider, locale: String): Int {
        if (locale != "zh_cn") return 0
        var count = 0
        LangUtil.ITEM_LANG.forEach { (name, cn) -> provider.add("item.${Gtetcore.MODID}.$name", cn); count++ }
        LangUtil.BLOCK_LANG.forEach { (name, cn) -> provider.add("block.${Gtetcore.MODID}.$name", cn); count++ }
        LangUtil.TAB_LANG.forEach { (name, cn) -> provider.add("itemGroup.${Gtetcore.MODID}.$name", cn); count++ }
        return count
    }

    /** 消费 [LangUtil.CUSTOM_LANG] — toolsHelper 工具提示等自定义双语条目。 */
    private fun autoGenCustomLang(provider: LanguageProvider, locale: String): Int {
        var count = 0
        LangUtil.CUSTOM_LANG.forEach { (key, pair) ->
            val (en, cn) = pair
            provider.add(key, if (locale == "zh_cn") cn else en)
            count++
        }
        return count
    }

    // ======================== 辅助 ========================

    /** 替换已有条目（反射写入底层 map，绕过 `provider.add` 的去重）。 */
    @JvmStatic
    fun replace(provider: LanguageProvider, key: String, value: String) {
        try {
            val dataField: Field = LanguageProvider::class.java.getDeclaredField("data")
            dataField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = dataField.get(provider) as MutableMap<String, String>
            map[key] = value
        } catch (e: Exception) {
            LOGGER.error("LangHandler.replace failed for key: {}", key, e)
        }
    }

    @JvmStatic
    fun multilineLang(provider: LanguageProvider, key: String, vararg lines: String) {
        provider.add(key, lines.joinToString("\n"))
    }

    @JvmStatic
    fun multiLang(provider: LanguageProvider, key: String, vararg lines: String) {
        provider.add(key, lines.joinToString("\n\n"))
    }

    @JvmStatic
    fun add(provider: LanguageProvider, locale: String, key: String, zh: String, en: String) {
        provider.add(key, if (locale == "zh_cn") zh else en)
    }

    @JvmStatic
    fun addZh(provider: LanguageProvider, locale: String, key: String, zh: String) {
        if (locale == "zh_cn") provider.add(key, zh)
    }

    @JvmStatic
    fun addEn(provider: LanguageProvider, locale: String, key: String, en: String) {
        if (locale == "en_us") provider.add(key, en)
    }
}
