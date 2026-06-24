package rain.gtetcore.gtet.api.annotation

/**
 * 从标注了 [ConfigLang] 的类中提取中英双语翻译。
 *
 * 使用 Java 反射扫描类的所有字段，查找 [ConfigLang] 注解。
 *
 * 典型用法：
 * ```kotlin
 * // 获取所有翻译
 * val translations = ConfigLangHelper.extractTranslations(ETConfig)
 * translations.forEach { (key, en, zh) ->
 *     println("$key → EN:$en  ZH:$zh")
 * }
 *
 * // 按语言获取 map
 * val enMap = ConfigLangHelper.enMap(ETConfig)
 * val zhMap = ConfigLangHelper.zhMap(ETConfig)
 * ```
 */
object ConfigLangHelper {

    /**
     * 翻译条目
     * @param key 配置键名（字段名，用于生成 translation key）
     * @param en  英文
     * @param zh  中文
     */
    data class Translation(
        val key: String,
        val en: String,
        val zh: String
    )

    /**
     * 扫描对象的所有字段，提取标注了 [ConfigLang] 的翻译。
     *
     * 使用 Java 反射，兼容所有 Kotlin 版本和 Forge 环境。
     *
     * @param instance 配置对象实例（如 `ETConfig`）
     * @return 翻译条目列表
     */
    @JvmStatic
    fun extractTranslations(instance: Any): List<Translation> {
        val result = mutableListOf<Translation>()

        // 遍历整个类层次结构（包括 object/companion 的宿主类）
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                val ann = field.getAnnotation(ConfigLang::class.java) ?: continue
                result.add(Translation(field.name, ann.en, ann.zh))
            }
            clazz = clazz.superclass
        }

        return result
    }

    /**
     * 将翻译提取为指定语言的 Map<key, translation>。
     *
     * @param instance 配置对象实例
     * @param lang     语言代码：`"en"` 或 `"zh"`
     */
    @JvmStatic
    fun toLanguageMap(instance: Any, lang: String): Map<String, String> {
        return extractTranslations(instance).associate { t ->
            val value = when (lang.lowercase()) {
                "zh", "zh_cn", "zh-cn", "chinese" -> t.zh
                else -> t.en
            }
            t.key to value
        }
    }

    /**
     * 快速提取英文翻译
     */
    @JvmStatic
    fun enMap(instance: Any): Map<String, String> =
        toLanguageMap(instance, "en")

    /**
     * 快速提取中文翻译
     */
    @JvmStatic
    fun zhMap(instance: Any): Map<String, String> =
        toLanguageMap(instance, "zh")
}