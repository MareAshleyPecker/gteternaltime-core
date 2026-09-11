package rain.gtetcore.gtet.common.item.recipe

import com.gregtechceu.gtceu.api.gui.widget.PhantomSlotWidget

import com.lowdragmc.lowdraglib.gui.widget.DialogWidget

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraftforge.items.IItemHandlerModifiable

import rain.gtetcore.gtet.util.lang.LangUtil

import java.util.function.Predicate

/**
 * 配方编辑器的**可改数量幽灵物品槽** —— 在 [PhantomSlotWidget] 的基础上加一条「鼠标中键弹出输入框设数量」。
 *
 * ## 左/右键行为完全不动
 * [mouseClicked] 只在中键（`button == 2`）且鼠标真的落在槽上时才自己处理，其余情况**原样**转给
 * 父类（[PhantomSlotWidget.mouseClicked]）。父类那三个分支一个都不能少：
 * 1. 拖着一坨物品时左键 → 把物品（连同数量）填进幽灵槽；
 * 2. 右键 → `clearSlotOnRightClick` 时清空（并 `writeClientAction(2)` 到服务端）；
 * 3. 其余 → 交回原版容器点击（`ModularUIGuiContainer.superMouseClicked`）。
 * 直接重写整段而不调 super 的话，拖入/清空/换物这些手感全会崩。
 *
 * ⚠️ 注意这**顶掉了中键原本的行为**：原先中键会走父类第 3 条分支，
 * 最终到 `slotClickPhantom(..., mouseButton = 2, ...)` → `fillPhantomSlot(slot, EMPTY, 2)`，
 * 也就是「中键清空该槽」。这是有意的取舍：右键已经能清空，中键腾出来给数量输入框更值。
 *
 * ## 空槽不弹框
 * 空槽没有「物品」就没有「该物品的最大堆叠」可作上限，也就没有数量可设 —— 中键落在空槽上直接吃掉这次点击。
 * 上限的兜底值见 [DEFAULT_MAX_STACK]（只在物品自己报不出上限时才用到）。
 *
 * ## 数量怎么存进草稿（客户端 → 服务端）
 * 对话框本身是**纯客户端**的（[DialogWidget] 的 `showStringEditorDialog` 内部会把整棵对话框控件树
 * `setClientSideWidget()`，它的按钮 `writeClientAction` 直接 return，不会发给服务端），
 * 所以「确认」这个动作**只在客户端跑**。真正写值必须自己走**槽控件**的 client action：
 *
 * ```
 * 客户端 确认 → applyCount(本地立即生效，供迷你预览/保存) + writeClientAction(3, 数量)
 * 服务端 handleClientAction(3) → applyCount(真正写进草稿) → 原版槽位同步把结果发回客户端
 * ```
 *
 * ⚠️ 和 [RecipeEditorBehavior] 里流体槽那段注释同一个坑：这个槽控件**绝对不能**设成
 * `setClientSideWidget()` —— `Widget.writeClientAction` 对客户端控件是直接 return 的，
 * 设了之后数量永远到不了服务端（草稿里存不下来，重开界面就打回原样）。
 * 反过来，不设客户端标记时，服务端改完槽内容会由原版 `AbstractContainerMenu` 的槽位同步发给客户端，
 * 客户端显示不会丢。
 *
 * ## 思路来源
 * - 【借鉴形状】LDLib 自己的 `DialogWidget#showStringEditorDialog(parent, title, initialText, validator, onConfirm)`
 *   —— 直接用这个现成的「输入框 + 确认/取消」工厂，而不是自己搭对话框框架。
 * - 【借鉴形状】GTM 的 `PhantomSlotWidget#fillPhantomSlot`（改数量的写回方式：`stack.copy()` 后
 *   `setCount(...)` 再 `slot.set(...)`）与 `SimpleItemFilter` 里对 `PhantomSlotWidget` 的匿名子类写法。
 * - 【自研】中键分发、「空槽不弹框」、以及把「对话框纯客户端 + 写值走槽自己的 client action」这条分工
 *   写清楚（LDLib 的对话框工厂只交代了它自己会 `setClientSideWidget`，没交代调用方该怎么把值同步回去）。
 *
 * @param handler        槽背后的物品容器（这里是 [RecipeDraft.inputs] / [RecipeDraft.outputs]）
 * @param slotIndex      槽下标
 * @param x              控件 x
 * @param y              控件 y
 * @param onCountChanged 数量真的变了之后的回调 —— 传 `touch()`，把草稿写回物品 NBT 并让代码预览失效
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

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // 不是中键、或者鼠标不在这个槽上 → 全权交给父类（理由见类注释）
        if (button != MIDDLE_BUTTON || !isMouseOverElement(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button)
        }
        // 空槽没有数量可设（见类注释）：吃掉这次点击，不弹框
        val stack = getItem()
        if (stack.isEmpty) return true

        // 对话框挂在槽所在的那一页上（`WidgetGroup.addWidget` 是**追加**到末尾，
        // 所以已有的控件下标一个都不动 —— LDLib 的控件同步就是按下标路由的，动一个就全串）。
        val parentGroup = parent ?: return true
        val cap = countCap(stack)

        DialogWidget.showStringEditorDialog(
            parentGroup,
            // 对话框是纯客户端的，所以这里取翻译后的字符串是安全的（专职服务器不会走到这一行）
            Component.translatable(LANG_TITLE, cap).string,
            stack.count.toString(),
            // 只挡「非数字」：允许中间态为空串（否则退格清空会被立刻驳回、没法整个重打）。
            // 上下限在确认时夹紧（见下面的 coerceIn）。
            // 为什么不用 LDLib 现成的 `TextFieldWidget#setNumbersOnly(min, max)`：
            // 它内部就是 `setValidator(...)`，而 `showStringEditorDialog` 会再 `setValidator` 一次
            // —— 同一个字段，后设置的覆盖先设置的，两者不能共存。
            Predicate { text -> text.length <= 9 && text.all { it.isDigit() } },
        ) { text ->
            val typed = text.toIntOrNull() ?: return@showStringEditorDialog
            val value = typed.coerceIn(1, cap)
            // 客户端先本地生效：迷你预览与 NBT 保存立刻跟上（服务端那份随后由 client action 写入）
            applyCount(value)
            writeClientAction(ACTION_SET_COUNT) { buffer -> buffer.writeVarInt(value) }
        }
        return true
    }

    /**
     * 服务端收到「设数量」的 client action：写进草稿。
     *
     * 只能处理自己新增的 [ACTION_SET_COUNT]（=3），其余（1 = 拖入物品、2 = 右键清空）转给父类，
     * 那条路是 LDLib/GTM 原有的实现。
     */
    override fun handleClientAction(id: Int, buffer: FriendlyByteBuf) {
        if (id == ACTION_SET_COUNT) {
            applyCount(buffer.readVarInt())
            return
        }
        super.handleClientAction(id, buffer)
    }

    /**
     * 把数量写进槽 —— 写回方式照着 GTM `PhantomSlotWidget#fillPhantomSlot` 的路子：
     * 复制一份、`setCount(...)`、再 `slot.set(copy)`（槽背后的 `WidgetSlotItemHandler.set` 会
     * `IItemHandlerModifiable.setStackInSlot(...)`，也就是真的落到 [RecipeDraft] 的容器里）。
     *
     * 直接改 `getItem().setCount(...)` 是**不行**的：`ItemStack` 在槽里是共享实例，
     * 就地改数字不会标记容器变更，原版槽位同步看不到变化。所以必须 copy + set。
     */
    private fun applyCount(count: Int) {
        val stack = getItem()
        if (stack.isEmpty) return // 期间被清空了（例如右键清空），放弃
        val updated = stack.copy()
        updated.count = count.coerceIn(1, countCap(updated))
        setItem(updated)
        onCountChanged()
    }

    /** 该物品的最大堆叠数；空槽时用 [DEFAULT_MAX_STACK] 兜底。 */
    private fun countCap(stack: ItemStack): Int =
        if (stack.isEmpty) DEFAULT_MAX_STACK else stack.maxStackSize.coerceAtLeast(1)

    companion object {

        /** 对话框标题的语言键：单个参数是数量上限 —— `设置数量（1~64）`。 */
        const val LANG_TITLE: String = "gtetcore.recipe_editor.count_title"

        /** 中键。`0` = 左、`1` = 右、`2` = 中（与 GLFW 的 `GLFW_MOUSE_BUTTON_MIDDLE` 一致）。 */
        private const val MIDDLE_BUTTON: Int = 2

        /**
         * 本控件自己的 client action id。
         *
         * 1、2 已经被 [PhantomSlotWidget] 占了（1 = 拖入物品、2 = 右键清空），所以从 3 起。
         */
        private const val ACTION_SET_COUNT: Int = 3

        /**
         * 空槽时的数量上限兜底值 —— 取 64 只是因为它是原版「普通物品」的默认堆叠上限。
         *
         * 实际上空槽根本不会弹输入框（见类注释），这个值只在 `ItemStack#getMaxStackSize` 给出
         * 非正数这种不可能的情况下才会被用到，写在这里是为了让 `countCap` 永远返回一个合法上限。
         */
        private const val DEFAULT_MAX_STACK: Int = 64

        /**
         * 登记对话框文案的中英双语。
         *
         * 和 `ThreadedRecipeStatus.initLang()` 一样必须挂在 `CommonProxy#kotlinInit()` 里、
         * 在**数据生成之前**执行：`LangUtil.CUSTOM_LANG` 是数据生成（`GatherDataEvent`）读取的，
         * 挂在界面控件类上会赶不上（控件要等玩家开界面才加载）。
         */
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
