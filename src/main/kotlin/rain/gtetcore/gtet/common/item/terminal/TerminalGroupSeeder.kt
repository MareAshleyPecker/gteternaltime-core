package rain.gtetcore.gtet.common.item.terminal

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.registries.ForgeRegistries
import rain.gtetcore.gtet.Gtetcore

/**
 * 把 [TerminalStaticGroups] 的 6 类静态组预置进玩家手上的高级终端 NBT。
 *
 * 目的：终端右侧那两块列表面板读的是终端 NBT 里的分级组
 * （`gtet_terminal.plan.groups`），只有「Shift+右键控制器扫描过」才有内容。
 * 这里在服务端每 tick 看一眼主手物品，是终端就把静态组补进去，于是**不扫描也能直接列出**
 * 线圈 / 能源仓 / 超频仓 / 线程仓 / 并行仓 / 维护仓这 6 类。
 *
 * ⚠️ **必须服务端写**：客户端改自己背包物品的 NBT 不会同步回服务端（写了等于没写），
 * 还会让服务端与客户端用两份不同的 NBT 去建同一棵树（LDLib 按控件路径同步界面）。
 * 之所以用「每 tick 检查主手」而不是「打开界面时补一次」：界面打开那一刻服务端才写 NBT 的话，
 * 客户端那次建树拿到的还是旧 NBT（第一次打开必然对不齐）；tick 里提前写好，
 * 客户端随物品同步自然拿到同一份数据。
 *
 * 写 NBT 的动作本身很轻：[TerminalSettings.installStaticGroups] 发现「该有的组都在」
 * 就直接返回 false、一个字节都不改（所以每 tick 调用不会反复触发物品同步）。
 * 玩家已有的 `group_prefs`（面板里选好的档）由那里保证**一个都不动**。
 *
 * ⚠️ Forge 注册订阅者时只认静态方法，所以处理器必须带 `@JvmStatic`。
 *
 * @author rain fox
 */
@Mod.EventBusSubscriber(modid = Gtetcore.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
object TerminalGroupSeeder {

    /** 自建高级终端的注册名。 */
    private const val TERMINAL_ID = "gtetcore:advanced_terminal"

    /** 懒解析 + 缓存的目标物品。 */
    private val TERMINALS: MutableList<Item> = ArrayList(1)

    private var resolved = false

    @JvmStatic
    @SubscribeEvent
    fun onPlayerTick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val player = event.player ?: return
        if (player.level().isClientSide()) return

        // 只看主手：界面读的也是主手物品，两边必须同一只手
        val held = player.mainHandItem
        if (held.isEmpty()) return
        for (terminal in terminals()) {
            if (held.item === terminal) {
                TerminalSettings.installStaticGroups(held)
                break
            }
        }
    }

    /** 按注册名查（不引用终端类），查不到就跳过；只在第一次调用时解析。 */
    private fun terminals(): List<Item> {
        if (!resolved) {
            resolved = true
            val location = ResourceLocation.tryParse(TERMINAL_ID)
            if (location != null) {
                val item = ForgeRegistries.ITEMS.getValue(location)
                if (item != null) TERMINALS.add(item)
            }
        }
        return TERMINALS
    }
}
