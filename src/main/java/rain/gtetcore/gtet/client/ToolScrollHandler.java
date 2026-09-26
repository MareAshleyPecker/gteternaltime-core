package rain.gtetcore.gtet.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import rain.gtetcore.gtet.common.item.tool.StructureToolBehavior;
import rain.gtetcore.gtet.common.item.tool.ToolNetwork;

/**
 * 对着空气按住 Shift 滚轮 → 切换结构工具的工作模式。
 *
 * <p>只在「看向空气（没有对准方块/实体）」时接管滚轮，避免和快捷栏切换打架。
 *
 * @author rain fox
 */
@OnlyIn(Dist.CLIENT)
public final class ToolScrollHandler {

    private ToolScrollHandler() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ToolScrollHandler.class);
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        if (!minecraft.player.isShiftKeyDown()) return;

        // 必须对着空气：命中方块或实体时让滚轮照常切快捷栏
        HitResult hit = minecraft.hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS) return;

        ItemStack stack = minecraft.player.getMainHandItem();
        if (!StructureToolBehavior.isStructureTool(stack)) return;

        event.setCanceled(true);
        ToolNetwork.sendCycle(event.getScrollDelta() > 0 ? 1 : -1);
    }
}
