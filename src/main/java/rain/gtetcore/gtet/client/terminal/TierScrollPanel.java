package rain.gtetcore.gtet.client.terminal;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings;

import java.util.*;

/**
 * 右侧那两块列表用的滚动面板：行高 16、滚动条宽 2、滑块白色圆角、不可拖动、开启裁剪。
 *
 * @author rain fox
 */
public class TierScrollPanel extends DraggableScrollableWidgetGroup {

    /** 行高。 */
    public static final int ROW_HEIGHT = 16;
    /** 一块列表的宽度。 */
    public static final int PANEL_WIDTH = 198;

    public TierScrollPanel(int x, int y, int width, int height) {
        super(x, y, width, height);
        setYScrollBarWidth(2);
        setYBarStyle(IGuiTexture.EMPTY, ColorPattern.T_WHITE.rectTexture().setRadius(1));
        setDraggable(false);
        setUseScissor(true);
    }

    @Override
    public void initWidget() {
        super.initWidget();
        // ⚠️ 滚动区最大高度的自动重算挂在「子控件尺寸 / 位置变化」上，而首次布局发生在控件初始化
        // 「之前」（那时会被"还没初始化"挡掉），之后又因为"没变化"直接返回 —— 不在这里补算一次，
        // 最大高度会一直停在 0：候选一多就看得见、够不着。
        computeMax();
    }

    /** 一块列表的空提示（列表内 (4, 4) 的一行灰字）。 */
    public static Widget emptyHint() {
        return new SyncedLabelWidget(4, 4,
                () -> net.minecraft.network.chat.Component.translatable(
                        rain.gtetcore.gtet.common.item.terminal.AdvancedTerminalLang.PANEL_EMPTY),
                false, SyncedLabelWidget.HINT_COLOR, 0);
    }

    /** 一组行（每行一个 {@link WidgetGroup}）放进一个整块容器。 */
    public static WidgetGroup column(int rows) {
        return new WidgetGroup(0, 0, PANEL_WIDTH, rows * ROW_HEIGHT);
    }

    /**
     * 右下那块：每组建一个子容器、<b>全都建出来</b>，再按 {@code ui_group} 决定谁可见。
     *
     * <p>⚠️ 不能「按当前选中的组去建树」：LDLib 的控件树两端各建一次、数据按控件路径同步，
     * 树结构在界面存活期间必须完全一致。
     *
     * <p>⚠️ 被藏起来的容器必须挪到很远的上方（{@code y = -10000}）：滚动区的最大高度是按所有
     * 子控件算的，只 {@code setVisible(false)} 不挪位置，滚动条会按「所有组加起来」的高度
     * 给出一大段空白。
     *
     * <p>⚠️ 当前显示哪一组是<b>服务端求值 + 同步下来</b>的（{@code ui_group} 存在物品 NBT 里，
     * 而界面开着时服务端写的 NBT 传不到客户端）。客户端绝不能自己读 NBT 去判断。
     */
    public static class Choose extends TierScrollPanel {

        /**
         * 服务端 → 客户端：「右下显示哪一组」变了。
         *
         * <p>⚠️ 只能取 3：{@code WidgetGroup.readUpdateInfo} 自己占用了 1（子控件更新的路由包）与
         * 2（动态添加的子控件的初始化），而父级正是用 id = 1 把**本类子控件**的更新转发进来的
         * （{@code WidgetGroupUIAccess.writeUpdateInfo} 写「路由 id 1 + 子控件索引 + 子控件自己的 id」）。
         * 本类一旦也用 1，就会把子控件那个包当成自己的，既吃掉子控件的同步、又读到一段垃圾字符串。
         * 普通 {@code Widget}（如 {@code SyncedLabelWidget}）用 1 是安全的，因为它的 id 是被父级写在
         * 路由包里面的，不会和路由 id 撞。
         */
        private static final int UPDATE_UI_GROUP = 3;

        private final java.util.function.Supplier<ItemStack> terminal;
        private final Map<String, WidgetGroup> holders = new LinkedHashMap<>();
        @Nullable
        private String applied;
        /** 服务端求值后同步下来的当前组键；客户端只认这个，不读 NBT。 */
        @Nullable
        private String uiGroup;

        public Choose(int x, int y, int width, int height, java.util.function.Supplier<ItemStack> terminal,
                      List<String> groupKeys) {
            super(x, y, width, height);
            this.terminal = terminal;
            for (String key : groupKeys) {
                WidgetGroup holder = new WidgetGroup(0, 0, PANEL_WIDTH, 0);
                holders.put(key, holder);
                addWidget(holder);
            }
            // 先按「第 1 组」铺一遍；真正的组键由服务端在 writeInitialData 里推下来
            apply(true);
        }

        @Nullable
        public WidgetGroup holder(String key) {
            return holders.get(key);
        }

        private void apply(boolean force) {
            String key = targetKey();
            if (!force && Objects.equals(key, applied)) return;
            applied = key;
            holders.forEach((groupKey, holder) -> {
                boolean visible = groupKey.equals(key);
                holder.setVisible(visible);
                holder.setSelfPosition(0, visible ? 0 : -10000);
            });
            // 换组时把滚动位置拉回顶部（否则会停在上一个组的滚动位置上）
            setScrollYOffset(0);
            computeMax();
        }

        /** 同步下来的组键 → 实际该显示的那一组；不在列表里（旧存档 / 组被后来的扫描覆盖）→ 退回第 1 组。 */
        @Nullable
        private String targetKey() {
            if (holders.isEmpty()) return null;
            return uiGroup != null && holders.containsKey(uiGroup) ? uiGroup : holders.keySet().iterator().next();
        }

        /** 填完行之后调一次：重新套用选中态并补算滚动高度。 */
        public void refresh() {
            apply(true);
        }

        @Override
        public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer);
            // 服务端求值：客户端那份物品 NBT 在界面开着时不会更新
            this.uiGroup = TerminalSettings.getUiGroup(this.terminal.get());
            buffer.writeUtf(this.uiGroup == null ? "" : this.uiGroup);
            apply(true);
        }

        @Override
        public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer);
            acceptUiGroup(buffer.readUtf());
        }

        @Override
        public void detectAndSendChanges() {
            super.detectAndSendChanges();
            String now = TerminalSettings.getUiGroup(this.terminal.get());
            if (!Objects.equals(now, this.uiGroup)) {
                this.uiGroup = now;
                writeUpdateInfo(UPDATE_UI_GROUP, buffer -> buffer.writeUtf(now == null ? "" : now));
                apply(true);
            }
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == UPDATE_UI_GROUP) {
                acceptUiGroup(buffer.readUtf());
                return;
            }
            super.readUpdateInfo(id, buffer);
        }

        /** 收到服务端推来的组键（空串 = 没设过）。 */
        private void acceptUiGroup(String raw) {
            this.uiGroup = raw.isEmpty() ? null : raw;
            apply(true);
        }
    }
}
