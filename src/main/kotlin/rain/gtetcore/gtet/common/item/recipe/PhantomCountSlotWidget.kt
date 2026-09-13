package rain.gtetcore.gtet.common.item.recipe

import com.gregtechceu.gtceu.api.gui.widget.PhantomSlotWidget
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.Clearable
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.items.IItemHandlerModifiable
import rain.gtetcore.gtet.common.item.recipe.PhantomCountSlotWidget.Companion.ACTION_SET_COUNT
import rain.gtetcore.gtet.common.item.recipe.PhantomCountSlotWidget.Companion.MAX_COUNT
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 配方编辑器的**可改数量幽灵物品槽** —— [PhantomSlotWidget] + 「鼠标中键弹输入框设数量」。
 *
 * ⚠️ 只在中键上分叉，其余按键原样转给父类（拖入 / 右键清空都靠父类那几条分支）。
 * ⚠️ 本控件**不能**设成 `setClientSideWidget()`：那样 `writeClientAction` 直接 return，数量到不了服务端
 *   ——对话框本身是纯客户端的，所以「确认」只能靠槽控件自己的 client action 写值。
 * ⚠️ 数量上限固定 [MAX_COUNT]（2147483647），**不按物品自己的堆叠上限**走（`getMaxStackSize` 普通物品是 64）。
 * ⚠️ 空槽不弹框：没有物品就没有数量可设，中键直接吃掉这次点击。
 *
 * 数量显示由本控件接管：父类只在 `count != 1` 时画，而「1 个」也是配方数据，所以绘制期间
 * [getRealStack] 把数量改成 1 掐掉它那一次，再由本控件显式画一遍（每帧读槽里的真值）。
 *
 * @param onCountChanged 传 `touch()`：写回物品 NBT 并刷新代码预览
 *
 * @author rain fox
 */
class PhantomCountSlotWidget(
    handler: IItemHandlerModifiable,
    slotIndex: Int,
    x: Int,
    y: Int,
    private val onCountChanged: () -> Unit,
) : PhantomSlotWidget(handler, slotIndex, x, y) {

    /** 是否正处在「本控件自己画物品」的那一小段里（见 [getRealStack]）。 */
    private var drawingItem: Boolean = false

    init {
        // 任何一次槽内容变更都会走这里（理由见类注释）：拖入 / 右键 / 左键调数量都要能立刻落盘。
        setChangeListener { onCountChanged() }
    }

    /** 画底纹与图标（super），再自己把数量画上去；文字必须与图标同 z（见 [ITEM_Z]）才不被盖住。 */
    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        drawingItem = true
        try {
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks)
        } finally {
            drawingItem = false
        }

        val stack = item
        if (stack.isEmpty) return

        val pos = position
        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, ITEM_Z)
        graphics.renderItemDecorations(
            Minecraft.getInstance().font,
            stack,
            pos.x + 1,
            pos.y + 1,
            stack.count.toString(),
        )
        graphics.pose().popPose()
    }


    fun getRealStack(fluid: FluidStack): FluidStack =
        if (drawingItem && !fluid.isEmpty) fluid.copy().also { it.amount = 1 } else fluid

    /**
     * ⚠️ 只在「本控件自己画」的那一小段里把数量改成 1（LDLib 拿到 1 就不画数字了），掐掉重复绘制；
     * 改的是副本，槽里真值不动；窗口外（悬停提示、JEI 取样）原样返回。
     */
    override fun getRealStack(stack: ItemStack): ItemStack =
        if (drawingItem && !stack.isEmpty) stack.copy().also { it.count = 1 } else stack

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 不是中键、或者鼠标不在这个槽上 → 全权交给父类（理由见类注释）
        if (button != MIDDLE_BUTTON || !isMouseOverElement(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        // 空槽没有数量可设（见类注释）：吃掉这次点击，不弹框
        val stack = item
        if (stack.isEmpty) return true

        // 对话框挂在槽所在的那一页上（`WidgetGroup.addWidget` 是**追加**到末尾，
        // 所以已有的控件下标一个都不动 —— LDLib 的控件同步就是按下标路由的，动一个就全串）。
        val parentGroup = parent ?: return true
        val cap = MAX_COUNT

        DialogWidget.showStringEditorDialog(
            parentGroup,
            // 对话框是纯客户端的，所以这里取翻译后的字符串是安全的（专职服务器不会走到这一行）
            Component.translatable(LANG_TITLE, cap).string,
            stack.count.toString(),
            // 只挡非数字（允许中间态空串），上限 10 位 = 2147483647，上下限在确认时夹紧。
            // 不用 LDLib 的 `TextFieldWidget#setNumbersOnly`：它内部也是 `setValidator`，
            // 而 `showStringEditorDialog` 会再设一次 —— 后设的覆盖先设的，两者不能共存。
            { text -> text.length <= 10 && text.all { it.isDigit() } },
        ) { text ->
            // ⚠️ 那个现成对话框的「取消」按钮是 `consumer.accept(null)`（和「确认」共用同一个 Consumer），
            // 所以 text 可能是 null：先判空再转数字，否则按一次取消就是一次 NPE。
            val typed = text?.toIntOrNull() ?: return@showStringEditorDialog
            val value = typed.coerceIn(1, cap)
            // 客户端先本地生效：迷你预览与 NBT 保存立刻跟上（服务端那份随后由 client action 写入）
            applyCount(value)
            writeClientAction(ACTION_SET_COUNT) { buffer -> buffer.writeVarInt(value) }
        }
        return true
    }
    /** 只处理自己的 [ACTION_SET_COUNT]；其余（1 = 拖入、2 = 右键清空）转给父类。 */
    override fun handleClientAction(id: Int, buffer: FriendlyByteBuf) {
        if (id == ACTION_SET_COUNT) {
            applyCount(buffer.readVarInt())
            return
        }
        super.handleClientAction(id, buffer)
    }

    /** 写回槽：⚠️ 必须 copy 后再 `item = copy` —— 就地改数量不标记容器变更，槽位同步看不到。 */
    private fun applyCount(count: Int) {
        val stack = item
        if (stack.isEmpty) return // 期间被清空了（例如右键清空），放弃
        val updated = stack.copy()
        updated.count = count.coerceIn(1, MAX_COUNT)
        item = updated
        onCountChanged()
    }

    companion object {

        /** 对话框标题的语言键：单个参数是数量上限 —— `设置数量（1~2147483647）`。 */
        const val LANG_TITLE: String = "gtetcore.recipe_editor.count_title"

        /** 中键。`0` = 左、`1` = 右、`2` = 中（与 GLFW 的 `GLFW_MOUSE_BUTTON_MIDDLE` 一致）。 */
        private const val MIDDLE_BUTTON: Int = 2

        /** 画物品的 z 偏移：LDLib 画物品用 `translate(0, 0, 232)`，数量文字同层才不被图标盖住。 */
        private const val ITEM_Z: Float = 232f

        /** 本控件自己的 client action id（1、2 被 [PhantomSlotWidget] 占了）。 */
        private const val ACTION_SET_COUNT: Int = 3

        /** 数量上限 2147483647；与 `ItemStack#getMaxStackSize` 无关（见类注释）。 */
        const val MAX_COUNT: Int = Int.MAX_VALUE

        /** 登记对话框文案；必须在数据生成之前调（见 `CommonProxy#kotlinInit`）。 */
        @JvmStatic
        fun initLang() {
            LangUtil.add(
                LANG_TITLE,
                "Set count (1-%s)",
                "设置数量（1~%s）"
            )
        }
    }
}
