package rain.gtetcore.gtet.util.lang

import com.electronwill.nightconfig.core.UnmodifiableConfig
import com.mojang.logging.LogUtils
import net.minecraftforge.common.ForgeConfigSpec
import rain.gtetcore.gtet.Gtetcore
import java.lang.reflect.Modifier
import java.util.function.BiConsumer
import java.util.regex.Pattern

/**
 * 配置翻译键的自动注册（Forge 版）。
 *
 * Forge 的配置界面按 **`<modid>.configuration.<选项路径>`** 取选项名
 * （路径就是 `ForgeConfigSpec.ConfigValue#getPath()` 用点号连起来的那串，
 * 例如 `multiblock.checkFailedWaitingTime`），选项说明（toml 里那段注释）
 * 挂在同名键的 `.tooltip` 后缀上。
 *
 * 本类在配置注册之后遍历规格里的所有选项：
 *
 * 1. 路径取自值表 [ForgeConfigSpec.getValues]；
 * 2. 说明文字取自定义表 [ForgeConfigSpec.getSpec] 里的 `ValueSpec#getComment()`，
 *    按字符集拆成中英两行：含中日韩字符的算中文，其余算英文；
 * 3. 短标签优先用字段上的 [Bilingual]（按配置路径对号），没有就退化成说明文字；
 * 4. 只有一边时，另一边用同一份文本兜底。
 *
 * 注册进 [LangUtil] 后由 [rain.gtetcore.gtet.data.lang.LangHandler] 写进
 * `en_us` / `zh_cn` 两个语言文件，因此必须在数据生成之前调用。
 *
 * **注意：**1.20.1 的 Forge 不带配置界面，这些键眼下没有界面消费；
 * 保留它们是为了以后换屏幕 / 升版本时直接可用（旧键 `config.gtetcore.option.*`
 * 是 `dev.toma.configuration` 的约定，见 `ConfigLangRegistry.java.bak`）。
 *
 * @author rain fox
 */
object ConfigLangRegistry {

    private val LOGGER = LogUtils.getLogger()

    /** Forge 配置界面的选项名前缀。 */
    const val KEY_PREFIX: String = Gtetcore.MODID + ".configuration."

    /** 选项说明（注释）的后缀。 */
    const val TOOLTIP_SUFFIX: String = ".tooltip"

    /** 中日韩字符（含全角标点），用来判断一行注释是不是中文。 */
    private val CJK: Pattern = Pattern.compile("[\\u3000-\\u303F\\u4E00-\\u9FFF\\uFF00-\\uFFEF]")

    /**
     * 注册某个配置规格下所有选项的翻译键。
     *
     * @param spec  `ForgeConfigSpec.Builder#build()` 出来的配置规格
     * @param owner 声明配置项的类，用它的 [Bilingual] 注解当短标签（可以为 `null`）
     * @return 实际注册的条目数
     */
    @JvmStatic
    fun register(spec: ForgeConfigSpec?, owner: Class<*>?): Int {
        if (spec == null) return 0

        // 1) 选项路径 —— 值表里每个 ConfigValue 都记着自己的完整路径
        val paths = ArrayList<String>()
        walk(spec.getValues(), "") { path, value ->
            if (value is ForgeConfigSpec.ConfigValue<*>) {
                paths.add(path)
            }
        }

        // 2) 选项注释 —— 定义表里每个 ValueSpec 都记着自己的注释
        val comments = LinkedHashMap<String, String>()
        walk(spec.getSpec(), "") { path, value ->
            if (value is ForgeConfigSpec.ValueSpec) {
                comments.putIfAbsent(path, value.comment)
            }
        }

        // 3) @Bilingual 短标签 —— 按配置路径和字段对上号
        val labels = bilingualLabels(owner)

        var count = 0
        for (path in paths) {
            val comment = splitLanguages(comments[path])
            val label = labels[path]

            var en = if (label != null && !isBlank(label.en)) label.en.trim() else comment.en
            var cn = if (label != null && !isBlank(label.cn)) label.cn.trim() else comment.cn

            // 4) 只有一边时互相兜底
            if (isBlank(en) && isBlank(cn)) continue
            if (isBlank(en)) en = cn
            if (isBlank(cn)) cn = en

            val key = KEY_PREFIX + path
            LangUtil.add(key, en!!, cn!!)   // 上面的兜底保证这里两边都非空
            LOGGER.debug("config lang key: {} = {} / {}", key, en, cn)
            count++

            // 注释整段当工具提示
            if (!isBlank(comment.en) || !isBlank(comment.cn)) {
                val tooltipEn = if (isBlank(comment.en)) comment.cn else comment.en
                val tooltipCn = if (isBlank(comment.cn)) comment.en else comment.cn
                LangUtil.add(key + TOOLTIP_SUFFIX, tooltipEn!!, tooltipCn!!)
                count++
            }
        }

        LOGGER.info("Registered {} config translation keys for {}", count, Gtetcore.MODID)
        return count
    }

    // ================================================================
    //  工具
    // ================================================================

    /**
     * 递归遍历配置树，把每个叶子交给 `visitor`。
     *
     * 键统一转成点号连接的字符串：NightConfig 的展平格式会把整条路径挤在一个 key 里
     * （`"multiblock.checkFailedWaitingTime"`），这里顺手把每段再拆开，
     * 展平与非展平两种形态得到的结果就一致了。
     */
    private fun walk(config: Any?, prefix: String, visitor: BiConsumer<String, Any?>) {
        val unmodifiable = config as? UnmodifiableConfig ?: return
        for (entry in unmodifiable.valueMap().entries) {
            val path = append(prefix, listOf(entry.key))
            val value: Any? = entry.value
            if (value is UnmodifiableConfig) {
                walk(value, path, visitor)
            } else {
                visitor.accept(path, value)
            }
        }
    }

    /** 把一段路径接到前缀后面（并拆开段内的点号）。 */
    private fun append(prefix: String, key: List<String?>): String {
        val out = StringBuilder(prefix)
        for (element in key) {
            if (element == null) continue
            for (part in element.split('.')) {
                if (part.isEmpty()) continue
                if (out.length > 0) out.append('.')
                out.append(part)
            }
        }
        return out.toString()
    }

    /** 把字段上的 [Bilingual] 按配置路径收集起来。 */
    private fun bilingualLabels(owner: Class<*>?): Map<String, Bilingual> {
        val labels = LinkedHashMap<String, Bilingual>()
        if (owner == null) return labels

        for (field in owner.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            if (!ForgeConfigSpec.ConfigValue::class.java.isAssignableFrom(field.type)) continue

            val annotations = field.getAnnotationsByType(Bilingual::class.java)
            if (annotations.isEmpty()) continue

            try {
                field.isAccessible = true
                val raw: Any? = field.get(null)
                if (raw is ForgeConfigSpec.ConfigValue<*>) {
                    labels[raw.path.joinToString(".")] = annotations[0]
                }
            } catch (e: Exception) {
                LOGGER.debug("Failed to read @Bilingual from field {}", field.name, e)
            }
        }
        return labels
    }

    /**
     * 拆注释里的中英两行：含中日韩字符的归中文，其余归英文。
     *
     * 同一种语言的多行用 `\n` 连起来（工具提示里会逐行显示）；
     * 某一侧没有就返回 `null`。
     */
    private fun splitLanguages(comment: String?): Text {
        if (isBlank(comment)) return Text(null, null)

        val en = StringBuilder()
        val cn = StringBuilder()
        // 上面已判空，这里可以安全断言
        for (line in comment!!.split(Regex("\\R"))) {
            if (line.isBlank()) continue
            appendLine(if (CJK.matcher(line).find()) cn else en, line.trim())
        }
        return Text(blankToNull(en), blankToNull(cn))
    }

    private fun appendLine(out: StringBuilder, line: String) {
        if (out.length > 0) out.append('\n')
        out.append(line)
    }

    private fun blankToNull(builder: StringBuilder): String? {
        return if (builder.length == 0) null else builder.toString()
    }

    private fun isBlank(value: String?): Boolean {
        return value == null || value.isBlank()
    }

    /** 一段注释拆出来的中英文本（各自可能为 `null`）。 */
    private data class Text(val en: String?, val cn: String?)
}