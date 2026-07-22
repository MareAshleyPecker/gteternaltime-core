package rain.gtetcore.gtet.data.lang

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.mojang.logging.LogUtils
import com.tterrag.registrate.providers.RegistrateLangProvider
import net.minecraft.data.PackOutput
import net.minecraftforge.common.data.LanguageProvider
import org.slf4j.Logger
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.data.lang.initlang.BlockLang
import rain.gtetcore.gtet.data.lang.initlang.ItemLang
import rain.gtetcore.gtet.data.lang.initlang.Lang
import rain.gtetcore.gtet.data.lang.initlang.TipsLang
import rain.gtetcore.gtet.util.lang.BlockLangUtil
import rain.gtetcore.gtet.util.lang.ItemLangUtil
import rain.gtetcore.gtet.util.lang.TabLangUtil
import java.lang.reflect.Field

/**
 * 集中式语言数据生成器，参照 GTCEu [com.gregtechceu.gtceu.data.lang.LangHandler]。
 *
 * 材料物品翻译由 `material.<name>` + `tagprefix.<name>` 运行时组合，无需逐个前缀生成。
 */
object LangHandler {

    private val LOGGER: Logger = LogUtils.getLogger()

    // ======================== 前缀格式 ========================

    @JvmStatic
    val ZH_PREFIX_FORMATS: Map<TagPrefix, String> = mapOf(
        TagPrefix.ingot        to "%s锭",
        TagPrefix.dust         to "%s粉",
        TagPrefix.nugget       to "%s粒",
        TagPrefix.plate        to "%s板",
        TagPrefix.rod          to "%s杆",
        TagPrefix.gear         to "%s齿轮",
        TagPrefix.screw        to "%s螺丝",
        TagPrefix.bolt         to "%s螺栓",
        TagPrefix.ring         to "%s环",
        TagPrefix.spring       to "%s弹簧",
        TagPrefix.wireGtSingle to "%s线",
        TagPrefix.foil         to "%s箔",
        TagPrefix.block        to "%s方块",
    )

    // ======================== 入口 ========================

    /** Registrate LANG 回调，合并到 Registrate 的 en_us 数据（而非覆盖）。 */
    @JvmStatic
    fun init(provider: RegistrateLangProvider) {
        generate(provider, "en_us")
    }

    class ZhCNProvider(packOutput: PackOutput) : LanguageProvider(packOutput, Gtetcore.MODID, "zh_cn") {
        override fun addTranslations() { generate(this, "zh_cn") }
    }

    // ======================== 生成 ========================

    private fun generate(provider: LanguageProvider, locale: String) {
        var count = 0
        BlockLang.init(provider, locale)
        ItemLang.init(provider, locale)
        TipsLang.init(provider, locale)
        Lang.init(provider, locale)
        count += autoGenMaterialLang(provider, locale)
        count += autoGenTagPrefixLang(provider, locale)
        count += autoGenUtilLang(provider, locale)
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
            when (locale) {
                "en_us" -> {
                    (CUSTOM_EN_PREFIX_FORMATS[tagPrefix.name] ?: tagPrefix.langValue)?.let {
                        provider.add(tagPrefix.unlocalizedName, it)
                        count++
                    }
                }
                "zh_cn" -> {
                    ZH_PREFIX_FORMATS[tagPrefix]?.let {
                        provider.add(tagPrefix.unlocalizedName, it)
                        count++
                    }
                }
            }
        }
        return count
    }

    @JvmStatic
    val CUSTOM_EN_PREFIX_FORMATS: MutableMap<String, String> = LinkedHashMap()

    // ======================== 物品/方块中文名 ========================

    /**
     * 消费 [ItemLangUtil.ITEM_LANG] / [BlockLangUtil.BLOCK_LANG] / [TabLangUtil.TAB_LANG]，
     * 生成 `item.gtetcore.<name>` / `block.gtetcore.<name>` / `itemGroup.gtetcore.<name>` 中文条目。
     */
    private fun autoGenUtilLang(provider: LanguageProvider, locale: String): Int {
        if (locale != "zh_cn") return 0
        var count = 0
        ItemLangUtil.ITEM_LANG.forEach { (name, cn) ->
            provider.add("item.${Gtetcore.MODID}.$name", cn); count++
        }
        BlockLangUtil.BLOCK_LANG.forEach { (name, cn) ->
            provider.add("block.${Gtetcore.MODID}.$name", cn); count++
        }
        TabLangUtil.TAB_LANG.forEach { (name, cn) ->
            provider.add("itemGroup.${Gtetcore.MODID}.$name", cn); count++
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
