package rain.gtetcore.gtet.client.terminal

import com.lowdragmc.lowdraglib.gui.widget.Widget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.function.Supplier

/**
 * 「服务端求值 + 同步给客户端」的标签控件。
 *
 * 文本要在服务端求值（读物品 NBT、拿物品名），显示要在客户端做：构造时给一个提供者，
 * 内容变化由服务端推给客户端。推的是 [Component] 而不是本地化好的字符串，语言键在客户端绘制时才落地。
 *
 * ⚠️ 提供者**只在服务端**被调用（`writeInitialData` / `detectAndSendChanges`）。
 * 界面开着时 `ServerPlayer#tick()` 只广播当前打开的那个容器，服务端写进主手物品 NBT 的东西
 * 传不到客户端 —— 在客户端读 NBT 只会拿到打开界面那一刻的旧值。
 *
 * 宽度限制按**像素**：过宽先等比缩小，缩到下限仍放不下才截断补省略号。
 *
 * @author rain fox
 */
class SyncedLabelWidget @JvmOverloads constructor(
    x: Int,
    y: Int,
    private val supplier: Supplier<Component>,
    private val centered: Boolean = false,
    private val color: Int = DEFAULT_COLOR,
    /** 允许的最大像素宽度；<= 0 表示不限制。 */
    private val maxWidth: Int = 0,
) : Widget(x, y, 0, 0) {

    private var text: Component = Component.empty()

    private fun resolve(): Component {
        val value = supplier.get()
        return value ?: Component.empty()
    }

    override fun writeInitialData(buffer: FriendlyByteBuf) {
        super.writeInitialData(buffer)
        text = resolve()
        buffer.writeUtf(Component.Serializer.toJson(text))
    }

    override fun readInitialData(buffer: FriendlyByteBuf) {
        super.readInitialData(buffer)
        text = parse(buffer.readUtf())
    }

    override fun detectAndSendChanges() {
        super.detectAndSendChanges()
        val now = resolve()
        if (now != text) {
            text = now
            writeUpdateInfo(UPDATE_TEXT) { buffer -> buffer.writeUtf(Component.Serializer.toJson(now)) }
        }
    }

    override fun readUpdateInfo(id: Int, buffer: FriendlyByteBuf) {
        if (id == UPDATE_TEXT) {
            text = parse(buffer.readUtf())
            return
        }
        super.readUpdateInfo(id, buffer)
    }

    @OnlyIn(Dist.CLIENT)
    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        val font = Minecraft.getInstance().font
        var shown = text
        var scale = 1.0F

        if (maxWidth > 0) {
            val width = font.width(shown)
            if (width > maxWidth) {
                scale = MIN_SCALE.coerceAtLeast(maxWidth.toFloat() / width)
                // 缩到下限仍放不下：按缩小后的可用宽度反推能留几个字。
                // 留 0.5px 容差：scale 正好取 maxWidth/width 时，width*scale 可能因浮点舍入略超 maxWidth，
                // 那会把「刚好放得下」的名字误截断成带省略号，反而更难看。
                if (width * scale > maxWidth + 0.5F) {
                    shown = truncate(font, shown, maxWidth / scale)
                }
            }
        }

        var x = positionX
        if (centered) x -= Math.round(font.width(shown) * scale / 2.0F)

        // 阴影一律打开：深色面板上光靠浅色不够，描边才压得住背景
        if (scale >= 1.0F) {
            graphics.drawString(font, shown, x, positionY, color, true)
            return
        }
        graphics.pose().pushPose()
        graphics.pose().translate(x.toFloat(), positionY.toFloat(), 0.0F)
        graphics.pose().scale(scale, scale, 1.0F)
        graphics.drawString(font, shown, 0, 0, color, true)
        graphics.pose().popPose()
    }

    companion object {

        private const val UPDATE_TEXT = 1

        /** 默认字色：浅灰。宿主面板底是 `GuiTextures.DISPLAY`（实测内部像素 RGB(27,35,37)），
         *  MC 默认字色 0x404040 画上去对比度仅约 1.5:1，等于看不见；这个色约 11:1。 */
        const val DEFAULT_COLOR = 0xD8D8D8

        /** 主要内容（标题、列表行名）：纯白，和浅灰的次要文字拉开层次。 */
        const val TEXT_COLOR = 0xFFFFFF

        /** 次要提示（空列表提示）：比正文暗一档，在这个深底上约 6.7:1。 */
        const val HINT_COLOR = 0xA8A8A8

        /** 缩放下限。LDLib 1.0.50 没有字号 API（`TextTexture#setWidth` 是横向拉伸、会变形），
         *  只能 pose 等比缩放；MC 字形是位图，非整数倍缩放会略糊，压到 0.6 以下更糊，不如截断。 */
        private const val MIN_SCALE = 0.6F

        private const val ELLIPSIS = "\u2026"

        private fun parse(json: String): Component {
            try {
                val parsed = Component.Serializer.fromJson(json)
                return parsed ?: Component.empty()
            } catch (ignored: Exception) {
                return Component.empty()
            }
        }

        /** 按像素宽度截断：留到「再加一个字就超宽」为止，然后补省略号。 */
        @OnlyIn(Dist.CLIENT)
        private fun truncate(font: Font, text: Component, allowedWidth: Float): Component {
            val plain = text.string
            var keep = plain.length
            while (keep > 1 && font.width(plain.substring(0, keep) + ELLIPSIS) > allowedWidth) {
                keep--
            }
            return Component.literal(plain.substring(0, keep) + ELLIPSIS)
        }
    }
}