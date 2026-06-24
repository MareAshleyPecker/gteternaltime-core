package rain.gtetcore.gtet.api.annotation

/**
 * 标注在配置属性上，直接提供英文(en)和中文(zh)的显示名。
 *
 * 用法示例：
 * ```kotlin
 * @ConfigLang(en = "Coil Temperature", zh = "线圈温度")
 * @JvmStatic
 * val coilTemperature: ForgeConfigSpec.IntValue
 * ```
 *
 * [en] 英文翻译（必填）
 * [zh] 中文翻译（必填）
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ConfigLang(val en: String, val zh: String)
