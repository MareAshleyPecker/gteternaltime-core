package rain.gtetcore.gtet.util.lang

/** 双语翻译缓存，供 [rain.gtetcore.gtet.data.lang.LangHandler] 消费。 */
object LangUtil {
    val TAB_LANG: MutableMap<String, String> = LinkedHashMap()
    val ITEM_LANG: MutableMap<String, String> = LinkedHashMap()
    val BLOCK_LANG: MutableMap<String, String> = LinkedHashMap()
    /** 通用双语条目：key → (en, cn) */
    val CUSTOM_LANG: MutableMap<String, Pair<String, String>> = LinkedHashMap()

    /** 登记一条双语条目；`@JvmStatic` 让 Java 侧（如 [rain.gtetcore.gtet.util.lang.ConfigLangRegistry]）也能直接调用。 */
    @JvmStatic
    fun add(key: String, en: String, cn: String) {
        CUSTOM_LANG[key] = en to cn
    }
}
