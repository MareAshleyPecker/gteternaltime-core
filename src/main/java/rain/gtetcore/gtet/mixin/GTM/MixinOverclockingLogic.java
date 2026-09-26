package rain.gtetcore.gtet.mixin.GTM;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.OverclockingLogic;
import com.gregtechceu.gtceu.api.recipe.modifier.ModifierFunction;
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers;
import com.gregtechceu.gtceu.common.machine.multiblock.electric.FusionReactorMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rain.gtetcore.gtet.api.capability.IOverclockHatch;
import rain.gtetcore.gtet.common.machine.overclock.OverclockHatchHelper;
import rain.gtetcore.gtet.common.machine.overclock.OverclockingLogics;

/**
 * 「超频仓」的核心挂钩：把多方块的普通超频换成 8×/16× 特殊超频。
 *
 * <h2>为什么最终选 @Redirect 而不是接口 mixin</h2>
 * 原本想直接注入 {@link OverclockingLogic} 接口 default 方法
 * {@code getModifier(MetaMachine, GTRecipe, long, boolean)}（那是所有超频的唯一汇合点），
 * 但 Mixin 0.8.5 的注解处理器直接报错：
 *
 * <pre>
 * MixinOverclockingLogic.java:66: 错误: Injector in interface is unsupported
 * </pre>
 *
 * 查过处理器源码：{@code AnnotatedMixinElementHandlerInjector#registerInjector} 一上来就
 * {@code if (mixin.isInterface()) printMessage(INJECTOR_IN_INTERFACE, ...)} —— 也就是说
 * **只要 mixin 本身是接口，任何注入器都不给过**（是 AP 层面的硬限制，不是找不到目标方法）。
 * 因此改用备选方案：@Redirect {@link GTRecipeModifiers} 里所有
 * {@code OverclockingLogic#getModifier(machine, recipe, maxVoltage)} 的调用点。
 *
 * <p>用 {@code javap -p -c} 把 GTM 7.5.3 的 {@code GTRecipeModifiers} 反汇编后确认，
 * 全类一共 5 处该调用（全 GTM 里除 {@code FusionReactorMachine} 自用的 2 处外也只剩这 5 处）：
 *
 * <ol>
 * <li>{@code lambda$static$0} —— {@code ELECTRIC_OVERCLOCK} 那个 memoize lambda 的内层，
 * 也就是 {@code OC_PERFECT / OC_NON_PERFECT / OC_PERFECT_SUBTICK / OC_NON_PERFECT_SUBTICK}
 * 四条快捷方式的公共路径（绝大多数多方块都走它）；</li>
 * <li>{@code crackerOverclock}（裂化机）；</li>
 * <li>{@code ebfOverclock}（高炉 / 合金炉等线圈机器）；</li>
 * <li>{@code pyrolyseOvenOverclock}（热解炉）；</li>
 * <li>{@code multiSmelterParallel}（多联熔炉）。</li>
 * </ol>
 *
 * <p>五处全部重定向，保证「结构里插得上超频仓」的多方块都真的吃得到特殊超频
 * （只重定向 {@code lambda$static$0} 的话，热解炉 / 裂化机 / 多联熔炉会出现
 * 「能插仓但没效果」的哑火）。被重定向的调用原本是 3 参数版
 * {@code getModifier(machine, recipe, maxVoltage)}，它内部以 {@code shouldParallel = true}
 * 转发到 4 参数版，所以回退分支这里也按 {@code true} 传。
 *
 * <p>两条不接管的例外：
 *
 * <ul>
 * <li>机器不是 {@link IMultiController}（单方块机器）→ 原样调用；</li>
 * <li>{@link FusionReactorMachine} → 原样调用：它的「配方」是启动耗电型，
 * EUt 走 {@code EUToStartCondition} 的特殊语义，套超频仓只会把数值搞乱。</li>
 * </ul>
 *
 * <p>注意：{@code OverclockHatchHelper.modify(...)} 内部<b>不会</b>再调
 * {@code logic.getModifier(...)}（它直接调 {@code logic.runOverclockingLogic(...)}），
 * 所以不存在递归；而本类回退分支里调 {@code logic.getModifier(...)} 是安全的 ——
 * 我们重定向的是调用点（{@code GTRecipeModifiers}），不是接口方法本身。
 * <p>⚠️ 挂钩点只能选调用点：Mixin 0.8.5 的注解处理器不允许往 {@link OverclockingLogic} 接口的
 * default 方法里注入（{@code Injector in interface is unsupported}），所以这 5 处必须全部重定向。
 *
 * @author rain fox
 */
@Mixin(value = GTRecipeModifiers.class, remap = false)
public class MixinOverclockingLogic {

    /** 3 参数版 {@code getModifier} 的完整签名，5 个调用点完全一致。 */
    @Unique
    private static final String GET_MODIFIER = "Lcom/gregtechceu/gtceu/api/recipe/OverclockingLogic;" +
            "getModifier(Lcom/gregtechceu/gtceu/api/machine/MetaMachine;" +
            "Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;J)" +
            "Lcom/gregtechceu/gtceu/api/recipe/modifier/ModifierFunction;";

    /** 两个入参、返回 ModifierFunction 的那几个静态方法（裂化/高炉/热解/多联熔炉）共用的描述符。 */
    @Unique
    private static final String SIMPLE_MODIFIER_DESC = "(Lcom/gregtechceu/gtceu/api/machine/MetaMachine;" +
            "Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)" +
            "Lcom/gregtechceu/gtceu/api/recipe/modifier/ModifierFunction;";

    // ======================== 1. ELECTRIC_OVERCLOCK 路径 ========================

    /**
     * {@code ELECTRIC_OVERCLOCK} 的 memoize lambda 内层：所有 {@code OC_*} 快捷方式的公共出口。
     *
     * <p><b>这里必须只写方法名、不写描述符</b>：javac 生成的 `lambda$static$0` 是 synthetic 方法，
     * Mixin 注解处理器用 javax.lang.model 读 classpath 上的类时看不到它，写全描述符会报
     *
     * <pre>
     * Cannot find target method "lambda$static$0(...)..." for @Redirect.method=... in GTRecipeModifiers
     * </pre>
     *
     * 而翻 {@code AnnotatedMixinElementHandlerInjector#registerInjectorTarget} 的字节码可以看到：
     * {@code if (selector.getDesc() == null) return;} —— **只要不给描述符，处理器就整段跳过目标校验**，
     * 于是既不会误报，也不会往 refmap 里写东西（本来 {@code remap = false} 也不需要）。
     * 运行时是 ASM 直接读 class 文件，synthetic 方法找得到，重定向照常生效。
     * 名字唯一性没问题：`lambda$static$0` 在 {@code GTRecipeModifiers} 里只有一个。
     *
     * <p><b>⚠ 显式警示：这里是有意绕过 AP 校验的，改动需谨慎。</b>本 selector <b>故意不写描述符</b>，
     * 正是为了绕过 Mixin AP 对 synthetic 方法的校验（处理器读不到 {@code lambda$static$0}，
     * 写全描述符反而会被判成找不到目标）。代价是：AP 不再替我们核对目标是否存在 ——
     * 如果将来 GTM 改动 `GTRecipeModifiers`，使编译器给 lambda 编出的编号或名字变化
     * （例如不再是 {@code lambda$static$0}），本 {@code @Redirect} 会在<b>加载期直接抛注入失败</b>
     * （{@code require} 默认为 1，属于**响亮失败**，不会静默失效），届时的修法是：
     * 拿新名字替换本 selector（方法名即可，仍然不要写描述符）；拿名字的办法与当初一样 ——
     * 用 {@code javap -p -c} 反汇编 {@code GTRecipeModifiers}，找里面调用
     * {@code OverclockingLogic.getModifier(...)} 的那个 synthetic lambda 方法名。
     */
    @Redirect(
            method = "lambda$static$0",
            at = @At(value = "INVOKE", target = GET_MODIFIER, remap = false),
            remap = false)
    private static ModifierFunction gtetcore$electricOverclock(OverclockingLogic logic, MetaMachine machine,
                                                               GTRecipe recipe, long maxVoltage) {
        return gtetcore$withOverclockHatch(logic, machine, recipe, maxVoltage);
    }

    // ======================== 2~5. 各类自定义超频 ========================

    /** 裂化机：{@code NON_PERFECT_OVERCLOCK_SUBTICK} 之后再叠线圈折扣。 */
    @Redirect(
            method = "crackerOverclock" + SIMPLE_MODIFIER_DESC,
            at = @At(value = "INVOKE", target = GET_MODIFIER, remap = false),
            remap = false)
    private static ModifierFunction gtetcore$crackerOverclock(OverclockingLogic logic, MetaMachine machine,
                                                               GTRecipe recipe, long maxVoltage) {
        return gtetcore$withOverclockHatch(logic, machine, recipe, maxVoltage);
    }

    /** 高炉 / 线圈机器：{@code heatingCoilOC}。 */
    @Redirect(
            method = "ebfOverclock" + SIMPLE_MODIFIER_DESC,
            at = @At(value = "INVOKE", target = GET_MODIFIER, remap = false),
            remap = false)
    private static ModifierFunction gtetcore$ebfOverclock(OverclockingLogic logic, MetaMachine machine,
                                                           GTRecipe recipe, long maxVoltage) {
        return gtetcore$withOverclockHatch(logic, machine, recipe, maxVoltage);
    }

    /** 热解炉：{@code NON_PERFECT_OVERCLOCK_SUBTICK} 之后再乘耗时系数。 */
    @Redirect(
            method = "pyrolyseOvenOverclock" + SIMPLE_MODIFIER_DESC,
            at = @At(value = "INVOKE", target = GET_MODIFIER, remap = false),
            remap = false)
    private static ModifierFunction gtetcore$pyrolyseOvenOverclock(OverclockingLogic logic, MetaMachine machine,
                                                                    GTRecipe recipe, long maxVoltage) {
        return gtetcore$withOverclockHatch(logic, machine, recipe, maxVoltage);
    }

    /** 多联熔炉：在把并行并进去之前，对那份临时配方做 {@code NON_PERFECT_OVERCLOCK}。 */
    @Redirect(
            method = "multiSmelterParallel" + SIMPLE_MODIFIER_DESC,
            at = @At(value = "INVOKE", target = GET_MODIFIER, remap = false),
            remap = false)
    private static ModifierFunction gtetcore$multiSmelterParallel(OverclockingLogic logic, MetaMachine machine,
                                                                   GTRecipe recipe, long maxVoltage) {
        return gtetcore$withOverclockHatch(logic, machine, recipe, maxVoltage);
    }

    // ======================== 公共逻辑 ========================

    /**
     * 有超频仓就换成特殊超频，否则原样走 GTM 原本的 {@code logic.getModifier(...)}。
     *
     * @param logic      被重定向的原逻辑（分裂机的线圈逻辑、高炉的加热线圈逻辑等）
     * @param machine    正在算配方的机器
     * @param recipe     原始配方
     * @param maxVoltage 控制器可承受的最高电压
     */
    @Unique
    private static ModifierFunction gtetcore$withOverclockHatch(OverclockingLogic logic, MetaMachine machine,
                                                                GTRecipe recipe, long maxVoltage) {
        // 超频仓只装在多方块上
        if (!(machine instanceof IMultiController controller)) {
            return logic.getModifier(machine, recipe, maxVoltage);
        }
        // 聚变堆不参与（EUt 语义特殊）
        if (machine instanceof FusionReactorMachine) {
            return logic.getModifier(machine, recipe, maxVoltage);
        }

        for (IMultiPart part : controller.getParts()) {
            if (part instanceof IOverclockHatch hatch) {
                // shouldParallel 传 true：与原 3 参数版 getModifier 的转发行为保持一致
                return OverclockHatchHelper.modify(machine, recipe, maxVoltage, true,
                        OverclockingLogics.create(hatch.getOverclockSpeed(), hatch.getOverclockEnergyFactor()));
            }
        }

        return logic.getModifier(machine, recipe, maxVoltage);
    }
}
