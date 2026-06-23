package rain.gtetcore.GTET.mixin;

import com.google.common.collect.ImmutableMap;
import com.gregtechceu.gtceu.common.data.GTBlocks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fix GTCEu 7.5.x crash when GCYM (GregTech Community Yellow Modern) is not installed.
 * <p>
 * GTBlocks' static initializer builds {@code MATERIALS_TO_CASINGS} ImmutableMap,
 * which references GCYMBlocks fields (e.g. {@code CASING_NONCONDUCTING},
 * {@code CASING_VIBRATION_SAFE}, etc.). When GCYM mod is absent, these fields
 * are null, causing {@code ImmutableMap$Builder.put(null, ...)} → NPE.
 * <p>
 * This mixin redirects ALL {@link ImmutableMap.Builder#put} calls in GTBlocks'
 * static initializer to silently skip entries with null keys or values.
 */
@Mixin(value = GTBlocks.class)
public abstract class MixinGTBlocks {

    /**
     * Redirect {@code ImmutableMap$Builder.put(Object, Object)} to skip null entries.
     * <p>
     * Applied to every put() call in the static initializer. This is intentionally
     * broad: it protects ALL ImmutableMap builders in GTBlocks from null entries,
     * not just the MATERIALS_TO_CASINGS one.
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
        Object key, Object value
    ) { if (key != null && value != null) {
            return builder.put(key, value);
        }
        // Skip entries where key or value is null
        // (GCYMBlocks fields are null when GCYM is not installed)
        return builder;
    }
}
