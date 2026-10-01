package rain.gtetcore.gtet.client.terminal

import com.lowdragmc.lowdraglib.gui.widget.Widget
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.function.Supplier

/**
 * 「只看不摸」的物品图标控件：16×16 画一个物品，悬停显示完整名字。
 *
 * LDLib 没有这种控件（`SlotWidget` / `PhantomSlotWidget` 都能被玩家拖放），
 * 所以自己写一个 —— 不参与任何槽位交互。
 *
 * 画的物品由**服务端**求值后同步（客户端的物品 NBT 会慢一拍，直接读会闪旧值）。
 *
 * @author rain fox
 */
class ItemIconWidget(
    x: Int,
    y: Int,
    private val supplier: Supplier<ItemStack>,
) : Widget(x, y, 16, 16) {

    private var stack: ItemStack = ItemStack.EMPTY

    private fun resolve(): ItemStack {
        val value = supplier.get()
        return value ?: ItemStack.EMPTY
    }

    override fun writeInitialData(buffer: FriendlyByteBuf) {
        super.writeInitialData(buffer)
        stack = resolve()
        buffer.writeItem(stack)
        setHoverTooltips(if (stack.isEmpty) Component.empty() else stack.hoverName)
    }

    override fun readInitialData(buffer: FriendlyByteBuf) {
        super.readInitialData(buffer)
        stack = buffer.readItem()
        setHoverTooltips(if (stack.isEmpty) Component.empty() else stack.hoverName)
    }

    override fun detectAndSendChanges() {
        super.detectAndSendChanges()
        val now = resolve()
        if (!ItemStack.matches(now, stack)) {
            stack = now
            setHoverTooltips(if (now.isEmpty) Component.empty() else now.hoverName)
            writeUpdateInfo(UPDATE_STACK) { buffer -> buffer.writeItem(now) }
        }
    }

    override fun readUpdateInfo(id: Int, buffer: FriendlyByteBuf) {
        if (id == UPDATE_STACK) {
            stack = buffer.readItem()
            setHoverTooltips(if (stack.isEmpty) Component.empty() else stack.hoverName)
            return
        }
        super.readUpdateInfo(id, buffer)
    }

    @OnlyIn(Dist.CLIENT)
    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        if (!stack.isEmpty) {
            graphics.renderItem(stack, positionX, positionY)
        }
    }

    private companion object {
        private const val UPDATE_STACK = 1
    }
}