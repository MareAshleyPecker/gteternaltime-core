package rain.gtetcore.gtet.mixin.GTM;

import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rain.gtetcore.gtet.api.capability.ETPartAbility;

/**
 * 让「能插并行仓的多方块」也能插 GTET 的超频仓。
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
 * {@code .or(...)} 一条 GTET 超频仓能力（{@code ETPartAbility.OVERCLOCK_HATCH}），
 * 与并行仓同规格：{@code setMaxGlobalLimited(1)}、{@code setPreviewCount(1)}。
 * {@code TraceabilityPredicate.or(...)} 返回的是**新对象**
 * （内部把 common/limited 两个 list 复制过去），不会污染 GTM 原返回值以外的任何东西。
 *
 * <p><b>为什么只追加超频仓，线程仓不在这里追加</b>：多方块能插哪些部件只由它的结构谓词说了算
 * （结构检测是拿 {@code Predicates#abilities(...)} 这一支去匹配部件方块的），
 * 而部件自己的 {@code MachineBuilder#abilities(...)} 只负责「它属于哪个能力」，
 * <b>不会</b>让任何结构接受它。所以一条能力要么往这里追加（= 对**所有**调用
 * {@code autoAbilities(_, _, true)} 的多方块生效，GTM / GCYM 的机器也在内），
 * 要么由具体机器在自己的机壳谓词上显式加槽（= 只对那台机器生效）。
 * 超频仓是「全局可插」的设计，所以走这里；线程仓只在实现了
 * {@code IThreadedRecipeMachine} 的 GTET 多方块上才有意义，走这里会让它在 GTM / GCYM 的多方块上
 * 变成「能插但不生效」的装饰部件，因此 {@code ETPartAbility.THREAD_HATCH} 由 GTET 自己的多方块
 * 在机壳谓词上显式加槽（见 {@code ETTestMultiblocks} 的 {@code 'X'} 谓词）。
 *
 * <p>为什么只改这个三重载：其它重载（{@code autoAbilities(GTRecipeType...)} 等）压根不加
 * {@code PARALLEL_HATCH}，那些多方块本来也插不了并行仓，保持原样即可。
 *
 * <p>该方法签名里唯一的坑是它是 <b>static</b> 的，所以注入处理器必须也是 {@code private static}
 * （且 {@code remap = false} —— GTM 是 mod，方法名不混淆，但参数描述符要按运行时原名写全，
 * 因为有 {@code autoAbilities} 的多个重载）。
 * <p>⚠️ GTM 的 {@code autoAbilities} 里没有任何 hook / SPI 扩展点，追加这条能力只能靠 mixin 注入。
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
