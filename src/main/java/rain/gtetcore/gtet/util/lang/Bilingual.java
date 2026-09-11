package rain.gtetcore.gtet.util.lang;

import java.lang.annotation.*;

/**
 * 快捷双语翻译注解。贴到配置字段上，自动生成 key。
 *
 * <p>key 格式：{@code config.gtetcore.<ClassNameLower>.<fieldName>}
 *
 * <pre>
 * &#64;Bilingual(en = "Max Export Block Count", cn = "导出最大方块数")
 * public int maxExportBlocks = 10000;
 * </pre>
 */
@Repeatable(Bilingual.Container.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Bilingual {
    String en();
    String cn();

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Container {
        Bilingual[] value();
    }
}
