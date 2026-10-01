package rain.gtetcore.gtet.client.terminal

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings
import rain.gtetcore.gtet.data.lang.AdvancedTerminalLang
import java.util.function.Supplier

/**
 * 右侧那两块列表用的滚动面板：行高 16、滚动条宽 2、滑块白色圆角、不可拖动、开启裁剪。
 *
 * @author rain fox
 */
open class TierScrollPanel(x: Int, y: Int, width: Int, height: Int) :
    DraggableScrollableWidgetGroup(x, y, width, height) {

    init {
        setYScrollBarWidth(2)
        setYBarStyle(IGuiTexture.EMPTY, ColorPattern.T_WHITE.rectTexture().setRadius(1.0F))
        isDraggable = false
        isUseScissor = true
    }

    override fun initWidget() {
        super.initWidget()
        // ⚠️ 滚动区最大高度的自动重算挂在「子控件尺寸 / 位置变化」上，而首次布局发生在控件初始化
        // 「之前」（那时会被"还没初始化"挡掉），之后又因为"没变化"直接返回 —— 不在这里补算一次，
        // 最大高度会一直停在 0：候选一多就看得见、够不着。
        computeMax()
    }

    /**
     * 右下那块：每组建一个子容器、**全都建出来**，再按 `ui_group` 决定谁可见。
     *
     * ⚠️ 不能「按当前选中的组去建树」：LDLib 的控件树两端各建一次、数据按控件路径同步，
     * 树结构在界面存活期间必须完全一致。
     *
     * ⚠️ 被藏起来的容器必须挪到很远的上方（`y = -10000`）：滚动区的最大高度是按所有
     * 子控件算的，只 `setVisible(false)` 不挪位置，滚动条会按「所有组加起来」的高度
     * 给出一大段空白。
     *
     * ⚠️ 当前显示哪一组是**服务端求值 + 同步下来**的（`ui_group` 存在物品 NBT 里，
     * 而界面开着时服务端写的 NBT 传不到客户端）。客户端绝不能自己读 NBT 去判断。
     */
    class Choose(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        private val terminal: Supplier<ItemStack>,
        groupKeys: List<String>,
    ) : TierScrollPanel(x, y, width, height) {

        private val holders: MutableMap<String, WidgetGroup> = LinkedHashMap()

        private var applied: String? = null

        /** 服务端求值后同步下来的当前组键；客户端只认这个，不读 NBT。 */
        private var uiGroup: String? = null

        init {
            for (key in groupKeys) {
                val holder = WidgetGroup(0, 0, PANEL_WIDTH, 0)
                holders[key] = holder
                addWidget(holder)
            }
            // 先按「第 1 组」铺一遍；真正的组键由服务端在 writeInitialData 里推下来
            apply(true)
        }

        fun holder(key: String): WidgetGroup? = holders[key]

        private fun apply(force: Boolean) {
            val key = targetKey()
            if (!force && key == applied) return
            applied = key
            holders.forEach { (groupKey, groupHolder) ->
                val visible = groupKey == key
                groupHolder.isVisible = visible
                groupHolder.setSelfPosition(0, if (visible) 0 else -10000)
            }
            // 换组时把滚动位置拉回顶部（否则会停在上一个组的滚动位置上）
            setScrollYOffset(0)
            computeMax()
        }

        /** 同步下来的组键 → 实际该显示的那一组；不在列表里（旧存档 / 组被后来的扫描覆盖）→ 退回第 1 组。 */
        private fun targetKey(): String? {
            if (holders.isEmpty()) return null
            val group = uiGroup
            return if (group != null && holders.containsKey(group)) group else holders.keys.iterator().next()
        }

        /** 填完行之后调一次：重新套用选中态并补算滚动高度。 */
        fun refresh() {
            apply(true)
        }

        override fun writeInitialData(buffer: FriendlyByteBuf) {
            super.writeInitialData(buffer)
            // 服务端求值：客户端那份物品 NBT 在界面开着时不会更新
            this.uiGroup = TerminalSettings.getUiGroup(this.terminal.get())
            buffer.writeUtf(this.uiGroup ?: "")
            apply(true)
        }

        override fun readInitialData(buffer: FriendlyByteBuf) {
            super.readInitialData(buffer)
            acceptUiGroup(buffer.readUtf())
        }

        override fun detectAndSendChanges() {
            super.detectAndSendChanges()
            val now = TerminalSettings.getUiGroup(this.terminal.get())
            if (now != this.uiGroup) {
                this.uiGroup = now
                writeUpdateInfo(UPDATE_UI_GROUP) { buffer -> buffer.writeUtf(now ?: "") }
                apply(true)
            }
        }

        override fun readUpdateInfo(id: Int, buffer: FriendlyByteBuf) {
            if (id == UPDATE_UI_GROUP) {
                acceptUiGroup(buffer.readUtf())
                return
            }
            super.readUpdateInfo(id, buffer)
        }

        /** 收到服务端推来的组键（空串 = 没设过）。 */
        private fun acceptUiGroup(raw: String) {
            this.uiGroup = raw.ifEmpty { null }
            apply(true)
        }

        companion object {

            /**
             * 服务端 → 客户端：「右下显示哪一组」变了。
             *
             * ⚠️ 只能取 3：`WidgetGroup.readUpdateInfo` 自己占用了 1（子控件更新的路由包）与
             * 2（动态添加的子控件的初始化），而父级正是用 id = 1 把**本类子控件**的更新转发进来的
             * （`WidgetGroupUIAccess.writeUpdateInfo` 写「路由 id 1 + 子控件索引 + 子控件自己的 id」）。
             * 本类一旦也用 1，就会把子控件那个包当成自己的，既吃掉子控件的同步、又读到一段垃圾字符串。
             * 普通 `Widget`（如 `SyncedLabelWidget`）用 1 是安全的，因为它的 id 是被父级写在
             * 路由包里面的，不会和路由 id 撞。
             */
            private const val UPDATE_UI_GROUP = 3
        }
    }

    companion object {

        /** 行高。 */
        const val ROW_HEIGHT: Int = 16

        /** 一块列表的宽度。 */
        const val PANEL_WIDTH: Int = 198

        /** 一块列表的空提示（列表内 (4, 4) 的一行灰字）。 */
        @JvmStatic
        fun emptyHint(): Widget {
            return SyncedLabelWidget(
                4, 4,
                { Component.translatable(AdvancedTerminalLang.PANEL_EMPTY) },
                false, SyncedLabelWidget.HINT_COLOR, 0
            )
        }

        /** 一组行（每行一个 [WidgetGroup]）放进一个整块容器。 */
        @JvmStatic
        fun column(rows: Int): WidgetGroup {
            return WidgetGroup(0, 0, PANEL_WIDTH, rows * ROW_HEIGHT)
        }
    }
}