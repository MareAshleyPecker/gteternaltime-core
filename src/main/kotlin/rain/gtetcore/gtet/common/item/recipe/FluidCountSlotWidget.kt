package rain.gtetcore.gtet.common.item.recipe

import com.gregtechceu.gtceu.api.gui.widget.PhantomFluidWidget
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.capability.IFluidHandler
import rain.gtetcore.gtet.common.item.recipe.PhantomCountSlotWidget.Companion.MAX_COUNT
import rain.gtetcore.gtet.util.lang.LangUtil
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * 配方编辑器的**可改数量的幽灵流体槽** —— [PhantomFluidWidget] + 「鼠标中键弹输入框设 mB」。
 *
 * ⚠️ 只在中键上分叉，其余按键原样交给父类（父类是「任何按键都用手里容器装填该槽」）。
 * ⚠️ 写值必须走槽控件自己的 client action —— 对话框本身是纯客户端的，服务端收不到它的确认。
 * ⚠️ 槽里没流体时不弹框，直接吃掉这次点击。
 *
 * 数量上限固定 [MAX_COUNT]（2147483647），单位 mB（1 桶 = 1000）。
 *
 * @param fluidGetter     读该槽当前流体（父类绘制液面也用它）
 * @param fluidSetter     写该槽流体（同一个容器 + 同一个下标）
 * @param onAmountChanged 传 `touch()`：写回物品 NBT 并刷新代码预览
 *
 * @author rain fox
 */
class FluidCountSlotWidget(
    fluidTank: IFluidHandler,
    tank: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val fluidGetter: Supplier<FluidStack>,
    private val fluidSetter: Consumer<FluidStack>,
    private val onAmountChanged: () -> Unit,
) : PhantomFluidWidget(fluidTank, tank, x, y, width, height, fluidGetter, fluidSetter) {

    init {
        // 量必须看得见：父类默认不画数量，只画液面高度。
        setShowAmount(true)
    }

    /** 中键 → 设量输入框；其余按键原样交给父类。 */
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != MIDDLE_BUTTON || !isMouseOverElement(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        val stack = fluidGetter.get()
        if (stack.isEmpty) return true

        val parentGroup = parent ?: return true
        DialogWidget.showStringEditorDialog(
            parentGroup,
            // 对话框是纯客户端的，这里取翻译后的字符串是安全的
            Component.translatable(LANG_TITLE, MAX_COUNT).string,
            stack.amount.toString(),
            // 只挡非数字（允许中间态空串），上下限在确认时夹紧
            { text -> text.length <= 10 && text.all { it.isDigit() } },
        ) { text ->
            // 取消按钮走的是 consumer.accept(null)，先判空再转数字
            val typed = text?.toIntOrNull() ?: return@showStringEditorDialog
            val value = typed.coerceIn(1, MAX_COUNT)
            applyAmount(value)
            writeClientAction(ACTION_SET_AMOUNT) { buffer -> buffer.writeVarInt(value) }
        }
        return true
    }

    /** 服务端收到「设量」：写进草稿；其余 id 转给父类（1 = 装填、2 = 落在槽上的流体、4 = 清空、5 = 同步）。 */
    override fun handleClientAction(id: Int, buffer: FriendlyByteBuf) {
        if (id == ACTION_SET_AMOUNT) {
            applyAmount(buffer.readVarInt())
            return
        }
        super.handleClientAction(id, buffer)
    }

    /** 只改量、不动流体本身（槽里的 `FluidStack` 是共享实例，必须 copy 后再写回）。 */
    private fun applyAmount(amount: Int) {
        val stack = fluidGetter.get()
        if (stack.isEmpty) return // 期间被清空了（例如右键清空），放弃
        val updated = stack.copy()
        updated.amount = amount.coerceIn(1, MAX_COUNT)
        fluidSetter.accept(updated)
        onAmountChanged()
    }

    companion object {

        /** 对话框标题的语言键：单个参数是数量上限。 */
        const val LANG_TITLE: String = "gtetcore.recipe_editor.fluid_amount_title"

        /** 中键（GLFW 的 `GLFW_MOUSE_BUTTON_MIDDLE`）。 */
        private const val MIDDLE_BUTTON: Int = 2

        /** 本控件自己的 client action id —— 避开父类已用的 1、2、4、5。 */
        private const val ACTION_SET_AMOUNT: Int = 6

        /** 登记对话框文案；必须在数据生成之前调（见 `CommonProxy#kotlinInit`）。 */
        @JvmStatic
        fun initLang() {
            LangUtil.add(
                LANG_TITLE,
                "Set fluid amount (1-%s mB)",
                "设置流体量（1~%s mB）"
            )
        }
    }
}
