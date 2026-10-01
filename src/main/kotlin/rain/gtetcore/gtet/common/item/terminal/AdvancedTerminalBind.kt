package rain.gtetcore.gtet.common.item.terminal

import appeng.api.implementations.blockentities.IWirelessAccessPoint
import appeng.api.networking.GridHelper
import appeng.api.networking.IGrid
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * 高级终端的 AE 绑定入口 —— 潜行右键无线接入点 / 直连的 ME 方块即绑定，再右键同一格即解绑。
 *
 * 这个手势在别处是空着的：潜行右键非控制器方块本来就什么都不做（搭建只在目标是
 * `IMultiController` 时触发），所以不会和搭建抢交互。
 *
 * 判定完全复用 [AeGridLink.resolveGrid] 自己的规则（无线接入点按覆盖范围、直连 ME 方块
 * 要求 16 格内、维度必须一致），不另写一份 —— 做法是先把链接临时写进物品 NBT、解析一次，
 * 解析不通过就还原。
 *
 * @author rain fox
 */
object AdvancedTerminalBind {

    /** 绑定结果。 */
    enum class Status {
        /** 绑定成功。 */
        BOUND,

        /** 解除了原来的绑定。 */
        UNBOUND,

        /** 这一格不是 AE 方块 —— 什么都不做，也不提示。 */
        NOT_AE,

        /** 是无线接入点但没激活。 */
        INACTIVE,

        /** 超出覆盖范围 / 维度不符。 */
        OUT_OF_RANGE,
    }

    /** 潜行右键某一格：绑定 / 解绑。 */
    @JvmStatic
    fun toggle(player: Player, terminal: ItemStack, pos: BlockPos): Status {
        val level: Level = player.level()
        val target = GlobalPos.of(level.dimension(), pos)
        val current = TerminalSettings.getAeLink(terminal)

        // 再点一次已经绑定的那一格 = 解绑
        if (target == current) {
            TerminalSettings.unlinkAe(terminal)
            return Status.UNBOUND
        }

        // 先判这一格像不像 AE 方块：不是就静默（保持「潜行右键随便一个方块 = 什么都没发生」的手感）
        val blockEntity = level.getBlockEntity(pos)
        val accessPoint = blockEntity is IWirelessAccessPoint
        val gridHost = GridHelper.getNodeHost(level, pos) != null
        if (!accessPoint && !gridHost) return Status.NOT_AE

        // 临时写入再解析：判定规则与真正取料时完全一致（没有第二份实现）
        TerminalSettings.linkAe(terminal, target)
        val grid: IGrid? = AeGridLink.resolveGrid(terminal, level, player)
        if (grid != null) return Status.BOUND

        if (current == null) {
            TerminalSettings.unlinkAe(terminal)
        } else {
            TerminalSettings.linkAe(terminal, current)
        }
        if (accessPoint && !(blockEntity as IWirelessAccessPoint).isActive) return Status.INACTIVE
        return Status.OUT_OF_RANGE
    }
}