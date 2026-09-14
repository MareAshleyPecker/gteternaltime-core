package rain.gtetcore.gtet.common.item.terminal;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import rain.gtetcore.gtet.Gtetcore;

import org.jetbrains.annotations.Nullable;

/**
 * 把 {@link TerminalStaticGroups} 的 6 类静态组预置进玩家手上的高级终端 NBT。
 *
 * <p>目的：GTMThings 高级终端右侧那两块列表面板读的是终端 NBT 里的分级组
 * （{@code gtet_terminal.plan.groups}），原来只有「Shift+右键控制器扫描过」才有内容。
 * 这里在服务端每 tick 看一眼主手物品，是高级终端就把静态组补进去，于是<b>不扫描也能直接列出</b>
 * 线圈 / 能源仓 / 超频仓 / 线程仓 / 并行仓 / 维护仓这 6 类。
 *
 * <p>⚠️ <b>必须服务端写</b>：客户端改自己背包物品的 NBT 不会同步回服务端（写了等于没写），
 * 还会让服务端与客户端用两份不同的 NBT 去建同一棵树（LDLib 按控件路径同步界面）。
 * 之所以用「每 tick 检查主手」而不是「打开界面时补一次」：界面打开那一刻服务端才写 NBT 的话，
 * 客户端那次建树拿到的还是旧 NBT（第一次打开必然对不齐）；tick 里提前写好，
 * 客户端随物品同步自然拿到同一份数据。
 *
 * <p>写 NBT 的动作本身很轻：{@link TerminalSettings#installStaticGroups} 发现「该有的组都在」
 * 就直接返回 false、一个字节都不改（所以每 tick 调用不会反复触发物品同步）。
 * 玩家已有的 {@code group_prefs}（面板里选好的档）由那里保证<b>一个都不动</b>。
 *
 * @author rain fox
 */
@Mod.EventBusSubscriber(modid = Gtetcore.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TerminalGroupSeeder {

    /** GTMThings 高级终端的注册名（写死；版本由随包的补丁 jar 固定）。 */
    private static final String TERMINAL_ID = "gtmthings:advanced_terminal";

    /**
     * 懒解析 + 缓存的目标物品。
     *
     * <p>⚠️ 之所以按注册名查、而不是引用 GTMThings 的 {@code CustomItems.ADVANCED_TERMINAL}：
     * 那个类的静态初始化就是「注册物品」，从 tick 里碰它容易踩到注册时机问题；
     * 走注册表查名字既不会有这个顾虑，也不需要在本 mod 的类里硬绑一个补丁类的引用。
     */
    @Nullable
    private static Item terminalItem;

    private TerminalGroupSeeder() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player == null || player.level().isClientSide()) return;

        // 只看主手：补丁那边的界面读的也是 entityPlayer.getMainHandItem()，两边必须同一只手
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || held.getItem() != terminal()) return;
        TerminalSettings.installStaticGroups(held);
    }

    @Nullable
    private static Item terminal() {
        if (terminalItem == null) {
            ResourceLocation id = ResourceLocation.tryParse(TERMINAL_ID);
            if (id == null) return null;
            terminalItem = ForgeRegistries.ITEMS.getValue(id);
        }
        return terminalItem;
    }
}
