package rain.gtetcore.gtet.mixin.GTM.Jade;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.integration.jade.provider.ParallelProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import rain.gtetcore.gtet.common.machine.IThreadedRecipeMachine;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 在 **GTET 自己的多线程多方块**上取消 GTM 那几行「并行/批处理/超频倍率」的 Jade 提示。
 *
 * <h2>那几行到底由谁产生（证据）</h2>
 * GTM 7.5.3 的 {@code com.gregtechceu.gtceu.integration.jade.provider.ParallelProvider#appendTooltip}
 * 是唯一来源，一个方法里把四行全写出来（uid = {@code gtceu:parallel_info}）：
 *
 * <pre>
 *   gtceu.multiblock.total_runs         → 「同时处理 %d 个配方」        ← 用户看到的第 1 行
 *   gtceu.multiblock.parallel.exact     → 「- %dx 来自并行」            ← 第 2 行
 *   gtceu.multiblock.batch_enabled      → 「- %dx 来自批处理」          ← 第 3 行
 *   gtceu.multiblock.subtick_parallels  → 「- %dx 来自超频」            ← 第 4 行
 *   gtceu.multiblock.parallel           → 「同时处理至多 %d 个配方」    ← 「精确」分支拿不到时的退化写法
 * </pre>
 *
 * 数字本身是 {@code appendServerData} 写进 NBT 的
 * （{@code parallel} / {@code batch} / {@code subtickParallel} + {@code exact} 标志位），
 * 口径是 {@code recipeLogic.getLastRecipe()} 这**一个配方对象**的倍率乘积。
 * 同包的 {@code RecipeLogicProvider} 只报「耗电 / 电压 / 进度条」，不含倍率行，所以不在这里动它。
 *
 * <h2>为什么要取消</h2>
 * GTET 的多线程内核让一台机器同时跑 N 条线程、每条各有自己的配方对象，
 * 于是屏幕上会同时出现两套口径：GTM 那套（单个配方的倍率乘积）+ GTET 自己的
 * {@code gtetcore:threaded_recipe_logic} provider 那套（线程数 / 在用几条 / 整机合计 Σ 次配方运行）。
 * 前者结构上表达不了线程数，摆在一起既占空间又对不上号 —— 直接按用户要求去掉前者，只留我们自己那套。
 *
 * <h2>生效范围：只对本 mod 的多线程机器</h2>
 * 判定是「被看的方块实体是 {@link MetaMachineBlockEntity}、且它的机器实现了
 * {@link IThreadedRecipeMachine}」—— 只有 GTET 的多线程多方块控制器会命中，
 * GTM / GCYM / 本 mod 的普通机器**完全不受影响**，那几行倍率照样显示。
 * <b>想全局去掉（所有 GT 机器都不显示）只需放宽条件</b>：把下面那个 {@code if} 的判断整段删掉、
 * 无条件 {@code ci.cancel()} 即可。更轻的做法是让玩家自己去 Jade 的插件配置界面关掉
 * {@code gtceu:parallel_info} 这个 provider —— 但那是**全局开关**，会连原版 GT 机器的信息一起关掉，
 * 所以默认不做，留给用户自己选。
 *
 * <h2>为什么注册在 mixins.json 的 client 数组</h2>
 * 被注入的 {@code appendTooltip} 是纯客户端方法（Jade 只在客户端拼提示框），
 * 而 {@code ParallelProvider} **这个类本身在专用服务器上也会被加载** ——
 * GTM 在 {@code GTJadePlugin#register(IWailaCommonRegistration)} 里
 * {@code registerBlockDataProvider(new ParallelProvider(), ...)}，那是 common 注册路径，
 * 服务端同样执行。既然类在服务端也会加载，把 mixin 放进 {@code mixins}（common）数组
 * 就会在服务端也做一次无意义的改写、并强制服务端解析 {@code ITooltip} / 我们自己的
 * {@code IThreadedRecipeMachine} 这条判断链；放进 {@code client} 数组则服务端**根本不会应用**，
 * 零风险。⚠️ 反过来只有一点要注意：mixin 里的类引用必须客户端也存在（本文件全部满足）。
 *
 * <p>另外 {@code method} 写的是**完整描述符**而不是裸名字：{@code ParallelProvider} 上还有一个
 * {@code appendServerData(CompoundTag, BlockAccessor)}，写全描述符可以避免将来 GTM 加重载时选错。
 * {@code remap = false} 是因为 GTM / Jade 都是 mod（方法名不混淆），按运行时原名匹配。
 *
 * <p>## 思路来源
 * - 【自研】取消条件（按 {@code IThreadedRecipeMachine} 限定范围）与「只取消倍率行、保留我们自己的线程行」
 *   这个取舍 —— GTM 侧没有任何针对单个 provider 的过滤扩展点，只能靠 mixin。
 *
 * @author rain fox
 */
@Mixin(value = ParallelProvider.class, remap = false)
public class MixinParallelProviderTooltip {

    @Inject(
            // ParallelProvider#appendTooltip(ITooltip, BlockAccessor, IPluginConfig)
            method = "appendTooltip(Lsnownee/jade/api/ITooltip;Lsnownee/jade/api/BlockAccessor;Lsnownee/jade/api/config/IPluginConfig;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void gtetcore$hideGtmParallelLines(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config,
                                              CallbackInfo ci) {
        if (accessor.getBlockEntity() instanceof MetaMachineBlockEntity blockEntity &&
                blockEntity.getMetaMachine() instanceof IThreadedRecipeMachine) {
            ci.cancel();
        }
    }
}
