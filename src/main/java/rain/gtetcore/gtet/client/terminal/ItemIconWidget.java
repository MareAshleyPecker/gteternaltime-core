package rain.gtetcore.gtet.client.terminal;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.Supplier;

/**
 * 「只看不摸」的物品图标控件：16×16 画一个物品，悬停显示完整名字。
 *
 * <p>LDLib 没有这种控件（{@code SlotWidget} / {@code PhantomSlotWidget} 都能被玩家拖放），
 * 所以自己写一个 —— 不参与任何槽位交互。
 *
 * <p>画的物品由<b>服务端</b>求值后同步（客户端的物品 NBT 会慢一拍，直接读会闪旧值）。
 *
 * @author rain fox
 */
public class ItemIconWidget extends Widget {

    private static final int UPDATE_STACK = 1;

    private final Supplier<ItemStack> supplier;
    private ItemStack stack = ItemStack.EMPTY;

    public ItemIconWidget(int x, int y, Supplier<ItemStack> supplier) {
        super(x, y, 16, 16);
        this.supplier = supplier;
    }

    private ItemStack resolve() {
        ItemStack value = supplier.get();
        return value == null ? ItemStack.EMPTY : value;
    }

    @Override
    public void writeInitialData(FriendlyByteBuf buffer) {
        super.writeInitialData(buffer);
        this.stack = resolve();
        buffer.writeItem(this.stack);
        setHoverTooltips(this.stack.isEmpty() ? net.minecraft.network.chat.Component.empty()
                : this.stack.getHoverName());
    }

    @Override
    public void readInitialData(FriendlyByteBuf buffer) {
        super.readInitialData(buffer);
        this.stack = buffer.readItem();
        setHoverTooltips(this.stack.isEmpty() ? net.minecraft.network.chat.Component.empty()
                : this.stack.getHoverName());
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        ItemStack now = resolve();
        if (!ItemStack.matches(now, this.stack)) {
            this.stack = now;
            setHoverTooltips(now.isEmpty() ? net.minecraft.network.chat.Component.empty() : now.getHoverName());
            writeUpdateInfo(UPDATE_STACK, buffer -> buffer.writeItem(now));
        }
    }

    @Override
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        if (id == UPDATE_STACK) {
            this.stack = buffer.readItem();
            setHoverTooltips(this.stack.isEmpty() ? net.minecraft.network.chat.Component.empty()
                    : this.stack.getHoverName());
            return;
        }
        super.readUpdateInfo(id, buffer);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        if (!this.stack.isEmpty()) {
            graphics.renderItem(this.stack, getPositionX(), getPositionY());
        }
    }
}
