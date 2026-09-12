package rain.gtetcore.gtet.mixin.GTM.Jade;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.integration.jade.provider.WorkableBlockProvider;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import rain.gtetcore.gtet.common.machine.IThreadedRecipeMachine;
import rain.gtetcore.gtet.integration.jade.provider.ThreadedRecipeLogicProvider;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 在 **GTET 自己的多线程多方块**上取消 GTM 那条「单配方进度条」（玩家看到的红条「4 / 5 s」）。
 *
 * <p>来源（GTM 7.5.3）：`WorkableBlockProvider#addTooltip` —— 文字是 {@code gtceu.jade.progress_sec}
 * （{@code "%s / %s s"}），颜色在同一方法里：
 * <pre>
 *   int color = capData.getBoolean("WorkingEnabled") ? 0xFF4CBB17 : 0xFFBB1C28;
 * </pre>
 * ⚠️ 这条在 GTM 里**永远是红的**：{@code WorkingEnabled} 是 {@code ControllableBlockProvider} 写在自己
 * 那份子 tag 里的，而 {@code CapabilityBlockProvider#appendServerData} 按 uid 各写一份子 tag，
 * 这个方法读不到它，于是永远走 0xFFBB1C28。数字则是基类那几个「单配方」字段（本内核只把最小下标那条
 * 线程镜像进去），多线程下没有意义 —— 改由 GTET 的 provider 逐配方组画绿色进度条。
 *
 * <p>生效范围：方块实体是 {@link MetaMachineBlockEntity}、机器实现了 {@link IThreadedRecipeMachine}、
 * 且线程上限 > 1（真的装了线程仓）。没装线程仓时本内核退化成单配方机器、GTET 那套也不显示，
 * 这时把 GTM 的进度条也去掉就什么进度都看不到了，所以按线程仓来分。
 * GTM / GCYM / 本 mod 的普通机器完全不受影响。
 *
 * <p>放 {@code client} 数组：被注入的方法只在客户端拼提示时执行，而 {@code WorkableBlockProvider}
 * 在专用服务器上也会被加载，放 client 数组则服务端根本不应用（与 {@code MixinParallelProviderTooltip} 同理）。
 * {@code method} 只写方法名：{@code remap = false} 下描述符里的 MC 类名会按原样匹配，而
 * {@code addTooltip} 没有同名重载；{@code remap = false} 是因为 GTM / Jade 都是 mod（方法名不混淆）。
 */
@Mixin(value = WorkableBlockProvider.class, remap = false)
public class MixinWorkableProgressBar {

    @Inject(method = "addTooltip", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtetcore$hideSingleRecipeProgressBar(CompoundTag capData, ITooltip tooltip, Player player,
                                                     BlockAccessor accessor, BlockEntity blockEntity,
                                                     IPluginConfig config, CallbackInfo ci) {
        // 线程上限取两条来源的「或」：服务端写在 Jade serverData 根上的值更可靠
        // （客户端部件表万一没同步到，现扫 getParts() 会误判成「没装线程仓」），
        // 现扫那条在本 provider 被玩家关掉时仍能兜住。
        boolean threadedByServer = accessor.getServerData()
                .getInt(ThreadedRecipeLogicProvider.NBT_LIMIT) > 1;
        boolean threadedOnClient = accessor.getBlockEntity() instanceof MetaMachineBlockEntity mbe &&
                mbe.getMetaMachine() instanceof IThreadedRecipeMachine machine &&
                machine.getThreadCount() > 1;
        if (threadedByServer || threadedOnClient) {
            ci.cancel();
        }
    }
}
