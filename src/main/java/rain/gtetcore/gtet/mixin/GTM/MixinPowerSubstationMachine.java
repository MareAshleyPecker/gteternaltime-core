package rain.gtetcore.gtet.mixin.GTM;

import com.gregtechceu.gtceu.common.machine.multiblock.electric.PowerSubstationMachine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 蓄能变电站（Power Substation）改成「无 EU 消耗」。
 *
 * <p>GTCEu 7.5.x 的变电站每 tick 都会抽掉一笔被动损耗，见 {@code transferEnergyTick()}：
 *
 * <pre>
 *     long energyPassiveDrained = energyBank.drain(getPassiveDrain());
 * </pre>
 *
 * 这笔损耗 = 电池容量 / {@code PASSIVE_DRAIN_DIVISOR}（20*60*60*24*100，即约「每天损失容量的 1%」）
 * 再加上每层超额电容的 {@code PASSIVE_DRAIN_MAX_PER_STORAGE}（100k EU/t）；
 * 开了维护系统时，{@code getPassiveDrain()} 还会再乘上「1 + 故障数」和时长系数，损耗更狠。
 *
 * <p>这里直接在 {@code getPassiveDrain()} 的 HEAD 返回 0：
 * 抽能那句拿到 0（{@code drain(0)} 不掉任何电），状态界面里那行「被动消耗」也同步显示 0，
 * 不用去动 {@code transferEnergyTick}、也不用清洗存档里已存的电量。
 *
 * <p>只影响被动损耗；输入仓进电、输出仓出电、容量与层数规则全部照旧。
 *
 * @author rain fox
 */
@Mixin(value = PowerSubstationMachine.class, remap = false)
public abstract class MixinPowerSubstationMachine {

    /** 被动损耗恒为 0（无 EU 消耗）。 */
    @Inject(method = "getPassiveDrain", at = @At("HEAD"), cancellable = true)
    private void gtetcore$noPassiveDrain(CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(0L);
    }
}
