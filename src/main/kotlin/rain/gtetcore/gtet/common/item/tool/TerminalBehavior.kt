package rain.gtetcore.gtet.common.item.tool

import com.gregtechceu.gtceu.api.item.component.IInteractionItem
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 多方块手动重检终端 — Shift+右键多方块控制器强制重新检测结构。
 */
object TerminalBehavior : IInteractionItem {

    private const val L_RECHECK = "gtetcore.terminal.recheck"

    init {
        LangUtil.add(L_RECHECK, "Structure recheck triggered: %s", "已触发多方块结构重检: %s")
    }

    override fun onItemUseFirst(itemStack: ItemStack, context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        val machine = MetaMachine.getMachine(level, context.clickedPos)
        if (machine is IMultiController) {
            if (player.isShiftKeyDown) {
                if (!level.isClientSide) {
                    machine.waitingTime = 0
                    machine.requestCheck()
                    player.displayClientMessage(
                        Component.translatable(L_RECHECK, machine.self().definition.name)
                            .withStyle(ChatFormatting.YELLOW),
                        true
                    )
                }
                return InteractionResult.sidedSuccess(level.isClientSide)
            }
        }
        return InteractionResult.PASS
    }

    override fun use(
        item: Item,
        level: Level,
        player: Player,
        usedHand: InteractionHand
    ): InteractionResultHolder<ItemStack> {
        return InteractionResultHolder.pass(player.getItemInHand(usedHand))
    }
}
