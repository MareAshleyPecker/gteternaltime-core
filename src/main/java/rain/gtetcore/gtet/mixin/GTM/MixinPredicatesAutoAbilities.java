package rain.gtetcore.gtet.mixin.GTM;

import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import rain.gtetcore.gtet.api.capability.ETPartAbility;

/**
 * 让「能插并行仓的多方块」也能插超频仓。
 *
 * <p>{@code Predicates.autoAbilities(boolean checkMaintenance, boolean checkMuffler, boolean checkParallel)}
 * 是 GTM 里给多方块结构配「通用部件槽」的工厂方法，其中 {@code checkParallel == true} 的那一支
 * 会追加 {@code abilities(PartAbility.PARALLEL_HATCH)}（见 GTM 源码）：
 *
 * <pre>
 *     if (checkParallel) {
 *         predicate = predicate.or(abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1).setPreviewCount(1));
 *     }
 * </pre>
 *
 * <p>这里在 {@code @At("RETURN")} 处，当 {@code checkParallel == true} 时往返回值上再
 * {@code .or(...)} 一条超频仓能力。{@code TraceabilityPredicate.or(...)} 返回的是**新对象**
 * （内部把 common/limited 两个 list 复制过去），不会污染 GTM 原返回值以外的任何东西。
 *
 * <p>为什么只改这个三重载：其它重载（{@code autoAbilities(GTRecipeType...)} 等）压根不加
 * {@code PARALLEL_HATCH}，那些多方块本来也插不了并行仓，保持原样即可。
 *
 * <p>该方法签名里唯一的坑是它是 <b>static</b> 的，所以注入处理器必须也是 {@code private static}
 * （且 {@code remap = false} —— GTM 是 mod，方法名不混淆，但参数描述符要按运行时原名写全，
 * 因为有 {@code autoAbilities} 的多个重载）。
 * <p>
 * ## 思路来源
 * - 【照抄】GTCEu `com.gregtechceu.gtceu.api.pattern.Predicates#autoAbilities` 里给并行仓留槽的那一行写法 —— 原文 `.or(abilities(PartAbility.PARALLEL_HATCH).setMaxGlobalLimited(1).setPreviewCount(1))` 整行照搬，只把能力换成 GTET 自己的 `ETPartAbility.OVERCLOCK_HATCH`（全局最多 1 个、JEI 预览 1 个的参数也照抄）。
 * - 【借鉴形状】「往原返回值上再追加一条能力 → 让原本只能插并行仓的多方块也能插超频仓」这个做法 —— 借的是「在 GTM 原谓词上追加一条 `.or(...)`」的形状（`or` 返回新对象，不动原谓词）；追加时机与条件（`@At("RETURN")` + `checkParallel`）是自己定的。
 * - 【自研】追加逻辑本身 —— GTM 的 `autoAbilities` 里没有任何 hook / SPI 扩展点，要加这条能力只能靠 mixin 注入，抄不到现成写法。
 *
 * @author rain fox
 */
@Mixin(value = Predicates.class, remap = false)
public class MixinPredicatesAutoAbilities {

    /** {@code autoAbilities(ZZZ)} 的完整描述符（三个 boolean，返回 TraceabilityPredicate）。 */
    @Inject(
            method = "autoAbilities(ZZZ)Lcom/gregtechceu/gtceu/api/pattern/TraceabilityPredicate;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false)
    private static void gtetcore$addOverclockHatch(boolean checkMaintenance, boolean checkMuffler,
                                                   boolean checkParallel,
                                                   CallbackInfoReturnable<TraceabilityPredicate> cir) {
        if (!checkParallel) return;

        TraceabilityPredicate original = cir.getReturnValue();
        if (original == null) return;

        // 与并行仓同规格：全局最多 1 个、JEI 预览 1 个
        TraceabilityPredicate overclockHatch = Predicates.abilities(ETPartAbility.OVERCLOCK_HATCH)
                .setMaxGlobalLimited(1)
                .setPreviewCount(1);

        cir.setReturnValue(original.or(overclockHatch));
    }
}
