package rain.gtetcore.gtet.util.lang;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import rain.gtetcore.gtet.Gtetcore;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * 配置翻译键的自动注册（Forge 版）。
 *
 * <p>Forge 的配置界面按 <b>{@code <modid>.configuration.<选项路径>}</b> 取选项名
 * （路径就是 {@code ForgeConfigSpec.ConfigValue#getPath()} 用点号连起来的那串，
 * 例如 {@code multiblock.checkFailedWaitingTime}），选项说明（toml 里那段注释）
 * 挂在同名键的 {@code .tooltip} 后缀上。
 *
 * <p>本类在配置注册之后遍历规格里的所有选项：
 *
 * <ol>
 *   <li>路径取自值表 {@link ForgeConfigSpec#getValues()}；</li>
 *   <li>说明文字取自定义表 {@link ForgeConfigSpec#getSpec()} 里的 {@code ValueSpec#getComment()}，
 *       按字符集拆成中英两行：含中日韩字符的算中文，其余算英文；</li>
 *   <li>短标签优先用字段上的 {@link Bilingual}（按配置路径对号），没有就退化成说明文字；</li>
 *   <li>只有一边时，另一边用同一份文本兜底。</li>
 * </ol>
 *
 * <p>注册进 {@link LangUtil} 后由 {@link rain.gtetcore.gtet.data.lang.LangHandler} 写进
 * {@code en_us} / {@code zh_cn} 两个语言文件，因此必须在数据生成之前调用。
 *
 * <p><b>注意：</b>1.20.1 的 Forge 不带配置界面，这些键眼下没有界面消费；
 * 保留它们是为了以后换屏幕 / 升版本时直接可用（旧键 {@code config.gtetcore.option.*}
 * 是 {@code dev.toma.configuration} 的约定，见 {@code ConfigLangRegistry.java.bak}）。
 *
 * @author rain fox
 */
public final class ConfigLangRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Forge 配置界面的选项名前缀。 */
    public static final String KEY_PREFIX = Gtetcore.MODID + ".configuration.";

    /** 选项说明（注释）的后缀。 */
    public static final String TOOLTIP_SUFFIX = ".tooltip";

    /** 中日韩字符（含全角标点），用来判断一行注释是不是中文。 */
    private static final Pattern CJK = Pattern.compile("[\\u3000-\\u303F\\u4E00-\\u9FFF\\uFF00-\\uFFEF]");

    private ConfigLangRegistry() {}

    /**
     * 注册某个配置规格下所有选项的翻译键。
     *
     * @param spec  {@code ForgeConfigSpec.Builder#build()} 出来的配置规格
     * @param owner 声明配置项的类，用它的 {@link Bilingual} 注解当短标签（可以为 {@code null}）
     * @return 实际注册的条目数
     */
    public static int register(ForgeConfigSpec spec, Class<?> owner) {
        if (spec == null) return 0;

        // 1) 选项路径 —— 值表里每个 ConfigValue 都记着自己的完整路径
        List<String> paths = new ArrayList<>();
        walk(spec.getValues(), "", (path, value) -> {
            if (value instanceof ForgeConfigSpec.ConfigValue<?>) {
                paths.add(path);
            }
        });

        // 2) 选项注释 —— 定义表里每个 ValueSpec 都记着自己的注释
        Map<String, String> comments = new LinkedHashMap<>();
        walk(spec.getSpec(), "", (path, value) -> {
            if (value instanceof ForgeConfigSpec.ValueSpec valueSpec) {
                comments.putIfAbsent(path, valueSpec.getComment());
            }
        });

        // 3) @Bilingual 短标签 —— 按配置路径和字段对上号
        Map<String, Bilingual> labels = bilingualLabels(owner);

        int count = 0;
        for (String path : paths) {
            Text comment = splitLanguages(comments.get(path));
            Bilingual label = labels.get(path);

            String en = label != null && !isBlank(label.en()) ? label.en().trim() : comment.en();
            String cn = label != null && !isBlank(label.cn()) ? label.cn().trim() : comment.cn();

            // 4) 只有一边时互相兜底
            if (isBlank(en) && isBlank(cn)) continue;
            if (isBlank(en)) en = cn;
            if (isBlank(cn)) cn = en;

            String key = KEY_PREFIX + path;
            LangUtil.add(key, en, cn);
            LOGGER.debug("config lang key: {} = {} / {}", key, en, cn);
            count++;

            // 注释整段当工具提示
            if (!isBlank(comment.en()) || !isBlank(comment.cn())) {
                String tooltipEn = isBlank(comment.en()) ? comment.cn() : comment.en();
                String tooltipCn = isBlank(comment.cn()) ? comment.en() : comment.cn();
                LangUtil.add(key + TOOLTIP_SUFFIX, tooltipEn, tooltipCn);
                count++;
            }
        }

        LOGGER.info("Registered {} config translation keys for {}", count, Gtetcore.MODID);
        return count;
    }

    // ================================================================
    //  工具
    // ================================================================

    /**
     * 递归遍历配置树，把每个叶子交给 {@code visitor}。
     *
     * <p>键统一转成点号连接的字符串：NightConfig 的展平格式会把整条路径挤在一个 key 里
     * （{@code "multiblock.checkFailedWaitingTime"}），这里顺手把每段再拆开，
     * 展平与非展平两种形态得到的结果就一致了。
     */
    @SuppressWarnings("all")
    private static void walk(Object config, String prefix, BiConsumer<String, Object> visitor) {
        if (!(config instanceof UnmodifiableConfig unmodifiable)) return;
        for (Map.Entry<String, Object> entry : unmodifiable.valueMap().entrySet()) {
            String path = append(prefix, Collections.singletonList(entry.getKey()));
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig nested) {
                walk(nested, path, visitor);
            } else {
                visitor.accept(path, value);
            }
        }
    }

    /** 把一段路径接到前缀后面（并拆开段内的点号）。 */
    private static @NotNull String append(String prefix, List<String> key) {
        StringBuilder out = new StringBuilder(prefix);
        for (String element : key) {
            if (element == null) continue;
            for (String part : element.split("\\.")) {
                if (part.isEmpty()) continue;
                if (out.length() > 0) out.append('.');
                out.append(part);
            }
        }
        return out.toString();
    }

    /** 把字段上的 {@link Bilingual} 按配置路径收集起来。 */
    private static Map<String, Bilingual> bilingualLabels(Class<?> owner) {
        Map<String, Bilingual> labels = new LinkedHashMap<>();
        if (owner == null) return labels;

        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!ForgeConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) continue;

            Bilingual[] annotations = field.getAnnotationsByType(Bilingual.class);
            if (annotations.length == 0) continue;

            try {
                field.setAccessible(true);
                if (field.get(null) instanceof ForgeConfigSpec.ConfigValue<?> value) {
                    labels.put(String.join(".", value.getPath()), annotations[0]);
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to read @Bilingual from field {}", field.getName(), e);
            }
        }
        return labels;
    }

    /**
     * 拆注释里的中英两行：含中日韩字符的归中文，其余归英文。
     *
     * <p>同一种语言的多行用 {@code \n} 连起来（工具提示里会逐行显示）；
     * 某一侧没有就返回 {@code null}。
     */
    private static Text splitLanguages(String comment) {
        if (isBlank(comment)) return new Text(null, null);

        StringBuilder en = new StringBuilder();
        StringBuilder cn = new StringBuilder();
        for (String line : comment.split("\\R")) {
            if (line.isBlank()) continue;
            appendLine(CJK.matcher(line).find() ? cn : en, line.trim());
        }
        return new Text(blankToNull(en), blankToNull(cn));
    }

    private static void appendLine(StringBuilder out, String line) {
        if (out.length() > 0) out.append('\n');
        out.append(line);
    }

    private static String blankToNull(StringBuilder builder) {
        return builder.length() == 0 ? null : builder.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 一段注释拆出来的中英文本（各自可能为 {@code null}）。 */
    private record Text(String en, String cn) {}
}
