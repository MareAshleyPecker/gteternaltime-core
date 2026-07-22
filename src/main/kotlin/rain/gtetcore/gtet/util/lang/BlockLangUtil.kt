package rain.gtetcore.gtet.util.lang


object BlockLangUtil {
    /** 方块中文名映射表 — key = 方块 ID（不含 modid 前缀），value = 中文名。 */
    @JvmStatic
    val BLOCK_LANG: MutableMap<String, String> = LinkedHashMap()
}
