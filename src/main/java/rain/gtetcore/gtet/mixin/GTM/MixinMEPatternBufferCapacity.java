package rain.gtetcore.gtet.mixin.GTM;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import rain.gtetcore.gtet.integration.ae2.ETPatternBufferCapacities;

/**
 * 「多阶段 ME 样板总成」的容量注入点：把 GTM 样板总成构造器里**内联的 27** 换成按档取值。
 *
 * <h2>为什么只能这么改</h2>
 * GTM 的容量是 {@code protected static final int MAX_PATTERN_COUNT = 27} —— 编译期常量，
 * 于是它被**内联**进三个字段初始化表达式。用 {@code javap -p -c} 反汇编 7.5.3 的
 * {@code MEPatternBufferPartMachine.<init>} 可以看到恰好 3 处 {@code bipush 27}：
 * <pre>
 * 26: bipush 27 ; 28: invokespecial CustomItemStackHandler.&lt;init&gt;(I)V   // patternInventory
 * 35: bipush 27 ; 37: anewarray ...MEPatternBufferPartMachine$InternalSlot // internalInventory
 * 44: bipush 27 ; 46: invokestatic  HashBiMap.create(I)                   // detailsSlotMap
 * </pre>
 * 这三处都在**父类构造期**执行，子类此时任何字段都还没赋值，所以子类无论怎么写都还是 27。
 * 这里不复制那份 707 行的实现，而是把这 3 个常量改成「按本机器的方块定义查表」，
 * 于是三个字段一出生就是本档容量，父类后续的一切（构造后段的槽填充循环、
 * {@code InternalSlotRecipeHandler} 建表、{@code onLoad} 的样板解码、AE 终端读写、
 * {@code refundAll}/{@code mergeInternalSlots}）**自动**按真实容量工作，一行都不用改。
 *
 * <h2>为什么在构造期取值是安全的</h2>
 * {@code MetaMachine} 构造器第一件事就是赋值 {@code this.holder}，而
 * {@code IMachineBlockEntity#getDefinition()} 读的是方块状态上的方块（不是机器实例），
 * 且 {@code MetaMachineBlockEntity} 是先 {@code getDefinition().createMetaMachine(this)}
 * 再继续自己的构造 —— 所以此处 {@code getDefinition()} 已经可用。详见
 * {@link ETPatternBufferCapacities} 的类注释（附 GTM 源码行号）。
 *
 * <h2>对 GTM 自己的机器零影响</h2>
 * 查表命不中（例如 {@code gtceu:me_pattern_buffer}、别的附属的样板总成）一律返回
 * {@link ETPatternBufferCapacities#NATIVE}（27），与打 mixin 之前逐字节等价。
 *
 * <p>⚠️ {@code require = 3}：GTM 若改动这几个字段的初始化（常量个数变了、或不再内联），
 * mixin 会在类加载阶段**直接报错**，而不是静默退回 27。这是刻意的：容量是本功能的核心，
 * 宁可启动失败也不能悄悄做成 27 格。
 *
 * @author rain fox
 */
@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public class MixinMEPatternBufferCapacity {

    /**
     * 把构造器里内联的 {@code 27} 换成按档容量。
     *
     * @param original 原常量（27）
     * @return 本机器对应的样板槽位数；非本 mod 的定义为 27
     */
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 27), require = 3)
    private int gtet$patternCapacity(int original) {
        return ETPatternBufferCapacities.of(((MetaMachine) (Object) this).getDefinition());
    }
}
