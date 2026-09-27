package rain.gtetcore.gtet.client.terminal;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.block.ICoilType;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.common.block.CoilBlock;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import rain.gtetcore.gtet.common.item.terminal.AdvancedTerminalLang;
import rain.gtetcore.gtet.common.item.terminal.AdvancedTerminalSettings;
import rain.gtetcore.gtet.common.item.terminal.StructureBuildPlanner;
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 高级终端的界面。窗口 372 × 274，界面里<b>没有</b>玩家背包槽位。
 *
 * <p>布局（像素，原点在窗口左上角）：左设置面板 (4, 4) 160 × 266，8 行行顶
 * y = 28 / 54 / 88 / 114 / 140 / 166 / 192 / 218（标签 x = 10、y = 行顶 + 2，控件右边缘对齐面板内 x = 152）；
 * 右上列表 (170, 20) 198 × 114，右下列表 (170, 154) 198 × 114，行高 16，
 * 行内图标 (4, 行顶)、名字 (24, 行顶 + 4)、行尾控件 x = 174。
 *
 * @author rain fox
 */
public final class AdvancedTerminalUI {

    public static final int WIDTH = 372;
    public static final int HEIGHT = 274;
    private static final int PANEL_WIDTH = TierScrollPanel.PANEL_WIDTH;
    private static final int ROW_HEIGHT = TierScrollPanel.ROW_HEIGHT;
    /** 行名允许的最大像素宽度：名字从 x = 24 起画、行尾控件从 x = 174 起，中间留 6 像素空隙 → 144。
     *  按像素而不是字符数限制：中文名按字符数截断会被砍成半截。 */
    private static final int NAME_MAX_WIDTH = 144;

    /** 未选中：空框；选中：空框 + 金黄色 ✔（带阴影，在深色按钮上约 8:1）。 */
    private static final IGuiTexture CHECKED = new GuiTextureGroup(
            GuiTextures.BUTTON,
            new TextTexture("\u2714").setDropShadow(true).setColor(0xFFAA00));

    private AdvancedTerminalUI() {}

    /** 构建界面。服务端与客户端各建一次，树结构必须完全一致。 */
    public static ModularUI create(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        Supplier<ItemStack> terminal = () -> holder.getPlayer().getItemInHand(holder.getHand());

        ModularUI ui = new ModularUI(WIDTH, HEIGHT, holder, player);
        WidgetGroup root = new WidgetGroup(0, 0, WIDTH, HEIGHT);
        root.setBackground(GuiTextures.BACKGROUND_INVERSE);
        ui.widget(root);

        // 标题：84 = 4 + 160 / 2，即设置面板的中线
        root.addWidget(new SyncedLabelWidget(84, 8,
                () -> Component.translatable(AdvancedTerminalLang.TITLE), true, SyncedLabelWidget.TEXT_COLOR, 0));
        root.addWidget(new ButtonWidget(354, 7, 12, 12, GuiTextures.CLOSE_ICON,
                data -> holder.getPlayer().closeContainer()));

        WidgetGroup settings = new WidgetGroup(4, 4, 160, 266);
        settings.setBackground(GuiTextures.DISPLAY);
        root.addWidget(settings);
        addSettings(settings, terminal, player);

        // 右上：分级方块（切换）
        root.addWidget(new SyncedLabelWidget(174, 8,
                () -> Component.translatable(AdvancedTerminalLang.PANEL_CYCLE),
                false, SyncedLabelWidget.TEXT_COLOR, 0));
        TierScrollPanel cycle = new TierScrollPanel(170, 20, PANEL_WIDTH, 114);
        cycle.setBackground(GuiTextures.DISPLAY);
        root.addWidget(cycle);

        // 右下：分级方块（勾选）—— 每组建一个子容器，全都建出来
        root.addWidget(new SyncedLabelWidget(174, 142,
                () -> Component.translatable(AdvancedTerminalLang.PANEL_CHOOSE),
                false, SyncedLabelWidget.TEXT_COLOR, 0));
        List<TerminalSettings.GroupView> groups = TerminalSettings.cachedGroups(terminal.get());
        List<String> keys = new ArrayList<>();
        for (TerminalSettings.GroupView group : groups) keys.add(group.key());
        TierScrollPanel.Choose choose = new TierScrollPanel.Choose(170, 154, PANEL_WIDTH, 114, terminal, keys);
        choose.setBackground(GuiTextures.DISPLAY);
        root.addWidget(choose);

        fillCyclePanel(cycle, groups, terminal, player);
        fillChoosePanel(choose, groups, terminal, player);

        return ui;
    }

    // ======================== 左侧设置面板 ========================

    private static void addSettings(WidgetGroup panel, Supplier<ItemStack> terminal, Player player) {
        // 1 线圈等级：步进器 [◀] 数值 [▶]
        panel.addWidget(tippedLabel(10, 30, AdvancedTerminalLang.SETTING_1, coilTooltip()));
        panel.addWidget(new ButtonWidget(110, 28, 12, 12, GuiTextures.BUTTON_LEFT,
                data -> bumpCoilTier(terminal.get(), player, -1)));
        panel.addWidget(new SyncedLabelWidget(130, 30, () -> Component.literal(String.valueOf(
                AdvancedTerminalSettings.getInt(terminal.get(), AdvancedTerminalSettings.COIL_TIER, 0))),
                true, SyncedLabelWidget.TEXT_COLOR, 0));
        panel.addWidget(new ButtonWidget(140, 28, 12, 12, GuiTextures.BUTTON_RIGHT,
                data -> bumpCoilTier(terminal.get(), player, 1)));

        // 2 重复结构次数
        panel.addWidget(tippedLabel(10, 56, AdvancedTerminalLang.SETTING_2, AdvancedTerminalLang.SETTING_2_TIP));
        panel.addWidget(numberField(terminal, AdvancedTerminalSettings.REPEAT_COUNT,
                AdvancedTerminalSettings.REPEAT_MIN, AdvancedTerminalSettings.REPEAT_MAX, 116, 55));

        // 3 无仓室模式
        panel.addWidget(tippedLabel(10, 90, AdvancedTerminalLang.SETTING_3, AdvancedTerminalLang.SETTING_3_TIP));
        panel.addWidget(checkbox(138, 88, AdvancedTerminalSettings.NO_HATCH_MODE, true,
                AdvancedTerminalLang.SETTING_3_TIP, terminal, player));

        // 4 线圈替换模式
        panel.addWidget(tippedLabel(10, 116, AdvancedTerminalLang.SETTING_4, AdvancedTerminalLang.SETTING_4_TIP));
        panel.addWidget(checkbox(138, 114, AdvancedTerminalSettings.REPLACE_COIL_MODE, false,
                AdvancedTerminalLang.SETTING_4_TIP, terminal, player));

        // 5 使用 AE 物品
        panel.addWidget(tippedLabel(10, 142, AdvancedTerminalLang.SETTING_5, AdvancedTerminalLang.SETTING_5_TIP));
        panel.addWidget(checkbox(138, 140, AdvancedTerminalSettings.IS_USE_AE, false,
                AdvancedTerminalLang.SETTING_5_TIP, terminal, player));

        // 6 镜像搭建
        panel.addWidget(tippedLabel(10, 168, AdvancedTerminalLang.SETTING_6, AdvancedTerminalLang.SETTING_6_TIP));
        panel.addWidget(checkbox(138, 166, AdvancedTerminalSettings.IS_FLIP, false,
                AdvancedTerminalLang.SETTING_6_TIP, terminal, player));

        // 7 模块搭建
        panel.addWidget(tippedLabel(10, 194, AdvancedTerminalLang.SETTING_7, AdvancedTerminalLang.SETTING_7_TIP));
        panel.addWidget(numberField(terminal, AdvancedTerminalSettings.MODULE,
                AdvancedTerminalSettings.MODULE_MIN, AdvancedTerminalSettings.MODULE_MAX, 116, 193));

        // 8 拆除模式
        panel.addWidget(tippedLabel(10, 220, AdvancedTerminalLang.SETTING_8, AdvancedTerminalLang.SETTING_8_TIP));
        panel.addWidget(checkbox(138, 218, AdvancedTerminalSettings.DEMOLITION, false,
                AdvancedTerminalLang.SETTING_8_TIP, terminal, player));
    }

    private static SyncedLabelWidget tippedLabel(int x, int y, String key, String tipKey) {
        return tippedLabel(x, y, key, Component.translatable(tipKey));
    }

    private static SyncedLabelWidget tippedLabel(int x, int y, String key, Component tip) {
        SyncedLabelWidget widget = new SyncedLabelWidget(x, y, () -> Component.translatable(key));
        widget.setHoverTooltips(tip);
        return widget;
    }

    private static SwitchWidget checkbox(int x, int y, String key, boolean fallback, String tipKey,
                                         Supplier<ItemStack> terminal, Player player) {
        SwitchWidget widget = new SwitchWidget(x, y, 14, 14, (data, pressed) -> {
            // 回调两侧都会跑：只在服务端写（客户端写自己背包物品的 NBT 不会同步回服务端）
            if (player.level().isClientSide) return;
            AdvancedTerminalSettings.setFlag(terminal.get(), key, pressed);
        });
        widget.setTexture(GuiTextures.BUTTON, CHECKED);
        widget.setSupplier(() -> AdvancedTerminalSettings.getFlag(terminal.get(), key, fallback));
        widget.setHoverTooltips(Component.translatable(tipKey));
        return widget;
    }

    /**
     * 数字输入框。
     *
     * <p>⚠️ 构造时把当前值夹一次并回写 —— 也就是「打开一次终端」就会把越界的值夹回范围（与原实现一致，保留）。
     */
    private static TextFieldWidget numberField(Supplier<ItemStack> terminal, String key, int min, int max,
                                               int x, int y) {
        ItemStack stack = terminal.get();
        int clamped = AdvancedTerminalSettings.clamp(AdvancedTerminalSettings.getInt(stack, key, min), min, max);
        AdvancedTerminalSettings.setInt(stack, key, clamped);

        TextFieldWidget field = new TextFieldWidget(x, y, 36, 14,
                () -> String.valueOf(AdvancedTerminalSettings.getInt(terminal.get(), key, min)),
                value -> {
                    if (value == null || value.isBlank()) return;
                    try {
                        int parsed = Integer.parseInt(value.trim());
                        AdvancedTerminalSettings.setInt(terminal.get(), key,
                                AdvancedTerminalSettings.clamp(parsed, min, max));
                    } catch (NumberFormatException ignored) {
                        // 输入途中的半截数字：等下一次回调
                    }
                });
        field.setNumbersOnly(min, max);
        return field;
    }

    private static void bumpCoilTier(ItemStack terminal, Player player, int delta) {
        if (player.level().isClientSide) return;
        int max = coilTypes().size();
        int current = AdvancedTerminalSettings.getInt(terminal, AdvancedTerminalSettings.COIL_TIER, 0);
        AdvancedTerminalSettings.setInt(terminal, AdvancedTerminalSettings.COIL_TIER,
                AdvancedTerminalSettings.clamp(current + delta, 0, max));
    }

    /** 「线圈等级」的 tooltip：第 1 行是文案，之后每档一行「档位序号:线圈显示名」。 */
    private static Component coilTooltip() {
        MutableComponent tip = Component.translatable(AdvancedTerminalLang.SETTING_1_TIP);
        List<ICoilType> coils = coilTypes();
        for (int i = 0; i < coils.size(); i++) {
            tip.append("\n").append(Component.literal((i + 1) + ":").append(coilName(coils.get(i))));
        }
        return tip;
    }

    /** 全部加热线圈，按 {@code getTier()} 升序（稳定顺序）。 */
    private static List<ICoilType> coilTypes() {
        List<ICoilType> types = new ArrayList<>(GTCEuAPI.HEATING_COILS.keySet());
        types.sort(Comparator.comparingInt(ICoilType::getTier));
        return types;
    }

    private static Component coilName(ICoilType type) {
        try {
            Supplier<CoilBlock> supplier = GTCEuAPI.HEATING_COILS.get(type);
            if (supplier == null) return Component.empty();
            ItemStack stack = new ItemStack(supplier.get());
            return stack.isEmpty() ? Component.empty() : stack.getHoverName();
        } catch (Throwable ignored) {
            return Component.empty();
        }
    }

    // ======================== 右上：分级方块（切换） ========================

    private static void fillCyclePanel(TierScrollPanel panel, List<TerminalSettings.GroupView> groups,
                                       Supplier<ItemStack> terminal, Player player) {
        if (groups.isEmpty()) {
            panel.addWidget(TierScrollPanel.emptyHint());
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            panel.addWidget(cycleRow(groups.get(i), i * ROW_HEIGHT, groups, terminal, player));
        }
    }

    private static Widget cycleRow(TerminalSettings.GroupView group, int rowTop,
                                   List<TerminalSettings.GroupView> all,
                                   Supplier<ItemStack> terminal, Player player) {
        WidgetGroup row = new WidgetGroup(0, rowTop, PANEL_WIDTH, ROW_HEIGHT);

        // ⚠️ 整行透明按钮必须**先**加入容器：LDLib 的 WidgetGroup#mouseClicked 从后往前找第一个
        // 「吃掉点击」的子控件，图标与文字控件不吃点击，所以整行按钮放最前面既能覆盖整行、
        // 又不会抢走 [▶] 的点击。
        ButtonWidget whole = new ButtonWidget(0, 0, 170, ROW_HEIGHT, IGuiTexture.EMPTY,
                data -> selectGroup(terminal.get(), player, group.key()));
        whole.setHoverTooltips(Component.translatable(AdvancedTerminalLang.PANEL_PICK_TIP));
        row.addWidget(whole);

        row.addWidget(new ItemIconWidget(4, 0, () -> chosenStack(terminal.get(), group)));
        row.addWidget(new SyncedLabelWidget(24, 4, () -> rowName(terminal.get(), group, all),
                false, SyncedLabelWidget.TEXT_COLOR, NAME_MAX_WIDTH));

        ButtonWidget next = new ButtonWidget(174, 1, 18, 14,
                new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_RIGHT),
                data -> cycleGroup(terminal.get(), player, group));
        next.setHoverTooltips(Component.translatable(AdvancedTerminalLang.PANEL_CYCLE_TIP));
        row.addWidget(next);
        return row;
    }

    /** 点整行 = 把这一组设为「右下正在显示的那一组」（只在服务端写）。 */
    private static void selectGroup(ItemStack terminal, Player player, String key) {
        if (player.level().isClientSide) return;
        TerminalSettings.setUiGroup(terminal, key);
    }

    /** 点 [▶] = 先写 ui_group，再把这一组循环到下一档。 */
    private static void cycleGroup(ItemStack terminal, Player player, TerminalSettings.GroupView group) {
        selectGroup(terminal, player, group.key());
        if (player.level().isClientSide) return;
        TerminalSettings.GroupView current = findGroup(terminal, group.key());
        TerminalSettings.cycleChoice(terminal, current == null ? group : current);
    }

    private static Component rowName(ItemStack terminal, TerminalSettings.GroupView group,
                                     List<TerminalSettings.GroupView> all) {
        boolean current = group.key().equals(currentGroupKey(terminal, all));
        // 行首标记：本行是「右下正在显示的组」时是 ▶，否则两个空格（名字列不左右跳）。
        // ▶ 单独给金黄色：和白色的名字分开，一眼能看出右下正在显示哪一组。
        MutableComponent marker = Component.literal(current ? "\u25b6 " : "  ");
        if (current) marker.withStyle(ChatFormatting.GOLD);
        ItemStack stack = chosenStack(terminal, group);
        return marker.append(stack.isEmpty() ? Component.empty() : stack.getHoverName());
    }

    @Nullable
    private static String currentGroupKey(ItemStack terminal, List<TerminalSettings.GroupView> all) {
        if (all.isEmpty()) return null;
        String ui = TerminalSettings.getUiGroup(terminal);
        for (TerminalSettings.GroupView group : all) {
            if (group.key().equals(ui)) return ui;
        }
        return all.get(0).key();
    }

    // ======================== 右下：分级方块（勾选） ========================

    private static void fillChoosePanel(TierScrollPanel.Choose panel, List<TerminalSettings.GroupView> groups,
                                        Supplier<ItemStack> terminal, Player player) {
        if (groups.isEmpty()) {
            panel.addWidget(TierScrollPanel.emptyHint());
            panel.refresh();
            return;
        }
        for (TerminalSettings.GroupView group : groups) {
            WidgetGroup holder = panel.holder(group.key());
            if (holder == null) continue;
            holder.setSize(PANEL_WIDTH, group.candidates().size() * ROW_HEIGHT);
            for (int i = 0; i < group.candidates().size(); i++) {
                holder.addWidget(chooseRow(group, group.candidates().get(i), i * ROW_HEIGHT, terminal, player));
            }
        }
        panel.refresh();
    }

    private static Widget chooseRow(TerminalSettings.GroupView group, String itemId, int rowTop,
                                    Supplier<ItemStack> terminal, Player player) {
        WidgetGroup row = new WidgetGroup(0, rowTop, PANEL_WIDTH, ROW_HEIGHT);
        ItemStack stack = StructureBuildPlanner.itemStackOf(itemId);
        ItemStack shown = stack == null ? ItemStack.EMPTY : stack;

        row.addWidget(new ItemIconWidget(4, 0, () -> shown));
        row.addWidget(new SyncedLabelWidget(24, 4, () -> shown.getHoverName(),
                false, SyncedLabelWidget.TEXT_COLOR, NAME_MAX_WIDTH));

        SwitchWidget check = new SwitchWidget(174, 1, 14, 14, (data, pressed) -> {
            // 点已选中的行不会取消选择：回调无条件写入同一档
            if (player.level().isClientSide) return;
            TerminalSettings.setPreference(terminal.get(), group.key(), itemId);
        });
        check.setTexture(GuiTextures.BUTTON, CHECKED);
        check.setSupplier(() -> itemId.equals(chosenId(terminal.get(), group)));
        row.addWidget(check);
        return row;
    }

    // ======================== 偏好读取 ========================

    @Nullable
    private static TerminalSettings.GroupView findGroup(ItemStack terminal, String key) {
        for (TerminalSettings.GroupView group : TerminalSettings.cachedGroups(terminal)) {
            if (group.key().equals(key)) return group;
        }
        return null;
    }

    @Nullable
    private static String chosenId(ItemStack terminal, TerminalSettings.GroupView group) {
        TerminalSettings.GroupView current = findGroup(terminal, group.key());
        return current == null ? group.chosen() : current.chosen();
    }

    private static ItemStack chosenStack(ItemStack terminal, TerminalSettings.GroupView group) {
        String id = chosenId(terminal, group);
        if (id == null) return ItemStack.EMPTY;
        ItemStack stack = StructureBuildPlanner.itemStackOf(id);
        return stack == null ? ItemStack.EMPTY : stack;
    }
}
