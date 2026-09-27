package rain.gtetcore.gtet.common.item.terminal;

import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import rain.gtetcore.gtet.client.terminal.AdvancedTerminalUI;

/**
 * 高级终端的行为组件。
 *
 * <p>交互分派（与终端物品的约定一致）：
 * <ul>
 *   <li>右键空气 / 右键方块（未潜行）→ 走 {@link IItemUIFactory} 的默认 {@code use}：打开界面；</li>
 *   <li><b>潜行右键方块</b> → 本类的 {@link #useOn}：目标是控制器就搭一次，
 *       否则当「AE 绑定」手势用；两者都<b>总是</b>返回「已消耗交互」；</li>
 *   <li>{@code onItemUseFirst} / 左键 / 生物交互 → 一律不消耗（用默认实现）。</li>
 * </ul>
 *
 * <p>搭建只在服务端做，客户端只负责把交互吃掉（避免两端各搭一次）。
 *
 * @author rain fox
 */
public final class AdvancedTerminalBehavior implements IItemUIFactory {

    public static final AdvancedTerminalBehavior INSTANCE = new AdvancedTerminalBehavior();

    private AdvancedTerminalBehavior() {}

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        return AdvancedTerminalUI.create(holder, player);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;

        Level level = context.getLevel();
        if (!level.isClientSide) {
            ItemStack terminal = context.getItemInHand();
            BlockPos pos = context.getClickedPos();
            if (MetaMachine.getMachine(level, pos) instanceof IMultiController) {
                AdvancedTerminalBuilder.run(player, terminal, pos);
            } else {
                notifyBind(player, pos, AdvancedTerminalBind.toggle(player, terminal, pos));
            }
        }
        // 潜行右键「任意」方块都返回已消耗：右键一个不是控制器的方块表现为「什么都没发生」
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void notifyBind(Player player, BlockPos pos, AdvancedTerminalBind.Status status) {
        Component message = switch (status) {
            case BOUND -> Component.translatable(AdvancedTerminalLang.AE_BOUND, describe(pos))
                    .withStyle(ChatFormatting.GREEN);
            case UNBOUND -> Component.translatable(AdvancedTerminalLang.AE_UNBOUND)
                    .withStyle(ChatFormatting.YELLOW);
            case INACTIVE -> Component.translatable(AdvancedTerminalLang.AE_FAIL_INACTIVE)
                    .withStyle(ChatFormatting.RED);
            case OUT_OF_RANGE -> Component.translatable(AdvancedTerminalLang.AE_FAIL_RANGE)
                    .withStyle(ChatFormatting.RED);
            // 不是 AE 方块：什么都不做，也不提示（保持「潜行右键随便一个方块 = 什么都没发生」的手感）
            case NOT_AE -> null;
        };
        if (message != null) player.displayClientMessage(message, true);
    }

    private static Component describe(BlockPos pos) {
        return Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }
}
