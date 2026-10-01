package rain.gtetcore.gtet.common.item.terminal

import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.common.item.terminal.TerminalContext.clear

/**
 * 搭建期间的临时上下文。
 *
 * 自动搭建是在 `useOn` 的调用栈里同步执行的，那一层拿不到终端物品，
 * 所以在 `useOn` 里记一下当前终端，供分级方块偏好查询使用。
 *
 * @author rain fox
 */
object TerminalContext {

    @Volatile
    private var terminal: ItemStack = ItemStack.EMPTY

    /** 传 null 等价于 [clear]（保留原来那句 `stack == null` 的判断）。 */
    @JvmStatic
    fun set(stack: ItemStack?) {
        terminal = stack ?: ItemStack.EMPTY
    }

    @JvmStatic
    fun terminal(): ItemStack = terminal

    @JvmStatic
    fun clear() {
        terminal = ItemStack.EMPTY
    }
}