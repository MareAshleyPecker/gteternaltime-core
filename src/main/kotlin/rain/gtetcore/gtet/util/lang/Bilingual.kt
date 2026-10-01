package rain.gtetcore.gtet.util.lang

/**
 * 快捷双语翻译注解。贴到配置字段上，自动生成 key。
 *
 * key 格式：`config.gtetcore.<类名小写>.<字段名>`
 *
 * ```
 * @Bilingual(en = "Max Export Block Count", cn = "导出最大方块数")
 * public int maxExportBlocks = 10000;
 * ```
 *
 * ⚠️ 使用点在 Java（`GTETConfig.java`），所以必须带 `@JvmRepeatable`：
 * 它会在字节码上补出 `@java.lang.annotation.Repeatable`，
 * 否则 Java 侧同一个字段贴两次就会编译不过。
 * 读取方是反射（`ConfigLangRegistry`），所以保留 `RUNTIME`。
 *
 * @author rain fox
 */
@JvmRepeatable(Bilingual.Container::class)
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Bilingual(val en: String, val cn: String) {

    /** 重复注解的容器（Java 反射按 `getAnnotationsByType` 取）。 */
    @Target(AnnotationTarget.FIELD)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Container(vararg val value: Bilingual)
}