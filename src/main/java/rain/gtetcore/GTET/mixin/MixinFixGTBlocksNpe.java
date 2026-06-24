package rain.gtetcore.GTET.mixin;

import com.google.common.collect.ImmutableMap;
import com.gregtechceu.gtceu.common.data.GTBlocks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 GTCEu 7.5.x 在未安装 GCYM 时启动崩溃的问题。
 * <p>
 * GTBlocks 的静态初始化块在构建 {@code MATERIALS_TO_CASINGS} ImmutableMap 时
 * 引用了 GCYMBlocks 的字段（如 {@code CASING_NONCONDUCTING}、
 * {@code CASING_VIBRATION_SAFE} 等）。当 GCYM 未安装时这些字段为 null，
 * 导致 {@code ImmutableMap$Builder.put(null, ...)} 抛出空指针异常。
 * <p>
 * 此 Mixin 拦截 GTBlocks 静态初始化块中 <b>所有</b>
 * {@link ImmutableMap.Builder#put} 调用，静默跳过 null 的 key 或 value。
 */
@Mixin(value = GTBlocks.class)
public abstract class MixinFixGTBlocksNpe {

    /**
     * 拦截 {@code ImmutableMap$Builder.put(Object, Object)}，跳过 null 条目。
     * <p>
     * 作用于静态初始化块中的每一次 put() 调用，覆盖面刻意放宽——
     * 不仅保护 MATERIALS_TO_CASINGS，而是保护 GTBlocks 中所有
     * ImmutableMap builder 免受 null 条目影响。
     */
    @Redirect(
        method = "<clinit>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableMap$Builder;put(Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap$Builder;"
        ),
        remap = false,
        require = 1
    )
    private static ImmutableMap.Builder<Object, Object> redirectPut(
        ImmutableMap.Builder<Object, Object> builder,
        Object key,
        Object value
    ) {
        if (key != null && value != null) {
            return builder.put(key, value);
        }
        // GCYM 未安装时对应字段为 null，跳过此条目
        return builder;
    }
}
