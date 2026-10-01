package rain.gtetcore.gtet.common.item.terminal

import com.gregtechceu.gtceu.api.item.component.IItemUIFactory
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import rain.gtetcore.gtet.client.terminal.AdvancedTerminalUI
import rain.gtetcore.gtet.common.item.terminal.AdvancedTerminalBehavior.useOn
import rain.gtetcore.gtet.data.lang.AdvancedTerminalLang

/**
 * 高级终端的行为组件。
 *
 * 交互分派（与终端物品的约定一致）：
 * - 右键空气 / 右键方块（未潜行）→ 走 [IItemUIFactory] 的默认 `use`：打开界面；
 * - **潜行右键方块** → 本类的 [useOn]：目标是控制器就搭一次，否则当「AE 绑定」手势用；
 *   两者都**总是**返回「已消耗交互」；
 * - `onItemUseFirst` / 左键 / 生物交互 → 一律不消耗（用默认实现）。
 *
 * 搭建只在服务端做，客户端只负责把交互吃掉（避免两端各搭一次）。
 *
 * @author rain fox
 */
object AdvancedTerminalBehavior : IItemUIFactory {

    override fun createUI(holder: HeldItemUIFactory.HeldItemHolder, player: Player): ModularUI =
        AdvancedTerminalUI.create(holder, player)

    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        if (!player.isShiftKeyDown) return InteractionResult.PASS

        val level: Level = context.level
        if (!level.isClientSide) {
            val terminal = context.itemInHand
            val pos = context.clickedPos
            if (MetaMachine.getMachine(level, pos) is IMultiController) {
                AdvancedTerminalBuilder.run(player, terminal, pos)
            } else {
                notifyBind(player, pos, AdvancedTerminalBind.toggle(player, terminal, pos))
            }
        }
        // 潜行右键「任意」方块都返回已消耗：右键一个不是控制器的方块表现为「什么都没发生」
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    private fun notifyBind(player: Player, pos: BlockPos, status: AdvancedTerminalBind.Status) {
        val message: Component? = when (status) {
            AdvancedTerminalBind.Status.BOUND ->
                Component.translatable(AdvancedTerminalLang.AE_BOUND, describe(pos))
                    .withStyle(ChatFormatting.GREEN)

            AdvancedTerminalBind.Status.UNBOUND ->
                Component.translatable(AdvancedTerminalLang.AE_UNBOUND)
                    .withStyle(ChatFormatting.YELLOW)

            AdvancedTerminalBind.Status.INACTIVE ->
                Component.translatable(AdvancedTerminalLang.AE_FAIL_INACTIVE)
                    .withStyle(ChatFormatting.RED)

            AdvancedTerminalBind.Status.OUT_OF_RANGE ->
                Component.translatable(AdvancedTerminalLang.AE_FAIL_RANGE)
                    .withStyle(ChatFormatting.RED)

            // 不是 AE 方块：什么都不做，也不提示（保持「潜行右键随便一个方块 = 什么都没发生」的手感）
            AdvancedTerminalBind.Status.NOT_AE -> null
        }
        if (message != null) player.displayClientMessage(message, true)
    }

    private fun describe(pos: BlockPos): Component =
        Component.literal("${pos.x}, ${pos.y}, ${pos.z}")
}