package rain.gtetcore.gtet.util.lang


object ItemLangUtil {
    /** 物品中文名映射表 — key = 物品 ID（不含 modid 前缀），value = 中文名。 */
    @JvmStatic
    val ITEM_LANG: MutableMap<String, String> = LinkedHashMap()
}
