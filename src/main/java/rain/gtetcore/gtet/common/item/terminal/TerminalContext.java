package rain.gtetcore.gtet.common.item.terminal;

import net.minecraft.world.item.ItemStack;

/**
 * 搭建期间的临时上下文。
 *
 * <p>GTMThings 的 {@code AutoBuildSetting#apply} 拿不到终端物品，
 * 而它是在 {@code useOn} 的调用栈里同步执行的，所以在 {@code useOn} 里记一下当前终端，
 * 供分级方块偏好查询使用。
 *
 * @author rain fox
 */
public final class TerminalContext {

    private static volatile ItemStack terminal = ItemStack.EMPTY;

    private TerminalContext() {}

    public static void set(ItemStack stack) {
        terminal = stack == null ? ItemStack.EMPTY : stack;
    }

    public static ItemStack terminal() {
        return terminal;
    }

    public static void clear() {
        terminal = ItemStack.EMPTY;
    }
}
