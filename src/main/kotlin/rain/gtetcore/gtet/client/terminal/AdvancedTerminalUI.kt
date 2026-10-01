package rain.gtetcore.gtet.client.terminal

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.api.gui.GuiTextures
import com.gregtechceu.gtceu.common.block.CoilBlock
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture
import com.lowdragmc.lowdraglib.gui.texture.TextTexture
import com.lowdragmc.lowdraglib.gui.widget.*
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.common.item.terminal.AdvancedTerminalSettings
import rain.gtetcore.gtet.common.item.terminal.StructureBuildPlanner
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings
import rain.gtetcore.gtet.data.lang.AdvancedTerminalLang
import java.util.function.Supplier

/**
 * 高级终端的界面。窗口 372 × 274，界面里**没有**玩家背包槽位。
 *
 * 布局（像素，原点在窗口左上角）：左设置面板 (4, 4) 160 × 266，8 行行顶
 * y = 28 / 54 / 88 / 114 / 140 / 166 / 192 / 218（标签 x = 10、y = 行顶 + 2，控件右边缘对齐面板内 x = 152）；
 * 右上列表 (170, 20) 198 × 114，右下列表 (170, 154) 198 × 114，行高 16，
 * 行内图标 (4, 行顶)、名字 (24, 行顶 + 4)、行尾控件 x = 174。
 *
 * @author rain fox
 */
object AdvancedTerminalUI {

    const val WIDTH: Int = 372
    const val HEIGHT: Int = 274
    private const val PANEL_WIDTH: Int = TierScrollPanel.PANEL_WIDTH
    private const val ROW_HEIGHT: Int = TierScrollPanel.ROW_HEIGHT

    /** 行名允许的最大像素宽度：名字从 x = 24 起画、行尾控件从 x = 174 起，中间留 6 像素空隙 → 144。
     *  按像素而不是字符数限制：中文名按字符数截断会被砍成半截。 */
    private const val NAME_MAX_WIDTH: Int = 144

    /** 未选中：空框；选中：空框 + 金黄色 ✔（带阴影，在深色按钮上约 8:1）。 */
    private val CHECKED: IGuiTexture = GuiTextureGroup(
        GuiTextures.BUTTON,
        TextTexture("\u2714").setDropShadow(true).setColor(0xFFAA00)
    )

    /** 构建界面。服务端与客户端各建一次，树结构必须完全一致。 */
    @JvmStatic
    fun create(holder: HeldItemUIFactory.HeldItemHolder, player: Player): ModularUI {
        val terminal: Supplier<ItemStack> = Supplier { holder.getPlayer().getItemInHand(holder.getHand()) }

        val ui = ModularUI(WIDTH, HEIGHT, holder, player)
        val root = WidgetGroup(0, 0, WIDTH, HEIGHT)
        root.setBackground(GuiTextures.BACKGROUND_INVERSE)
        ui.widget(root)

        // 标题：84 = 4 + 160 / 2，即设置面板的中线
        root.addWidget(
            SyncedLabelWidget(
                84, 8,
                Supplier { Component.translatable(AdvancedTerminalLang.TITLE) },
                true, SyncedLabelWidget.TEXT_COLOR, 0
            )
        )
        root.addWidget(
            ButtonWidget(354, 7, 12, 12, GuiTextures.CLOSE_ICON) { holder.getPlayer().closeContainer() }
        )

        val settings = WidgetGroup(4, 4, 160, 266)
        settings.setBackground(GuiTextures.DISPLAY)
        root.addWidget(settings)
        addSettings(settings, terminal, player)

        // 右上：分级方块（切换）
        root.addWidget(
            SyncedLabelWidget(
                174, 8,
                Supplier { Component.translatable(AdvancedTerminalLang.PANEL_CYCLE) },
                false, SyncedLabelWidget.TEXT_COLOR, 0
            )
        )
        val cycle = TierScrollPanel(170, 20, PANEL_WIDTH, 114)
        cycle.setBackground(GuiTextures.DISPLAY)
        root.addWidget(cycle)

        // 右下：分级方块（勾选）—— 每组建一个子容器，全都建出来
        root.addWidget(
            SyncedLabelWidget(
                174, 142,
                Supplier { Component.translatable(AdvancedTerminalLang.PANEL_CHOOSE) },
                false, SyncedLabelWidget.TEXT_COLOR, 0
            )
        )
        val groups = TerminalSettings.cachedGroups(terminal.get())
        val keys = ArrayList<String>()
        for (group in groups) keys.add(group.key)
        val choose = TierScrollPanel.Choose(170, 154, PANEL_WIDTH, 114, terminal, keys)
        choose.setBackground(GuiTextures.DISPLAY)
        root.addWidget(choose)

        fillCyclePanel(cycle, groups, terminal, player)
        fillChoosePanel(choose, groups, terminal, player)

        return ui
    }

    // ======================== 左侧设置面板 ========================

    private fun addSettings(panel: WidgetGroup, terminal: Supplier<ItemStack>, player: Player) {
        // 1 线圈等级：步进器 [◀] 数值 [▶]
        panel.addWidget(tippedLabel(10, 30, AdvancedTerminalLang.SETTING_1, coilTooltip()))
        panel.addWidget(
            ButtonWidget(110, 28, 12, 12, GuiTextures.BUTTON_LEFT) {
                bumpCoilTier(terminal.get(), player, -1)
            }
        )
        panel.addWidget(
            SyncedLabelWidget(
                130, 30,
                Supplier {
                    Component.literal(
                        AdvancedTerminalSettings.getInt(
                            terminal.get(), AdvancedTerminalSettings.COIL_TIER, 0
                        ).toString()
                    )
                },
                true, SyncedLabelWidget.TEXT_COLOR, 0
            )
        )
        panel.addWidget(
            ButtonWidget(140, 28, 12, 12, GuiTextures.BUTTON_RIGHT) {
                bumpCoilTier(terminal.get(), player, 1)
            }
        )

        // 2 重复结构次数
        panel.addWidget(tippedLabel(10, 56, AdvancedTerminalLang.SETTING_2, AdvancedTerminalLang.SETTING_2_TIP))
        panel.addWidget(
            numberField(
                terminal, AdvancedTerminalSettings.REPEAT_COUNT,
                AdvancedTerminalSettings.REPEAT_MIN, AdvancedTerminalSettings.REPEAT_MAX, 116, 55
            )
        )

        // 3 无仓室模式
        panel.addWidget(tippedLabel(10, 90, AdvancedTerminalLang.SETTING_3, AdvancedTerminalLang.SETTING_3_TIP))
        panel.addWidget(
            checkbox(
                138, 88, AdvancedTerminalSettings.NO_HATCH_MODE, true,
                AdvancedTerminalLang.SETTING_3_TIP, terminal, player
            )
        )

        // 4 线圈替换模式
        panel.addWidget(tippedLabel(10, 116, AdvancedTerminalLang.SETTING_4, AdvancedTerminalLang.SETTING_4_TIP))
        panel.addWidget(
            checkbox(
                138, 114, AdvancedTerminalSettings.REPLACE_COIL_MODE, false,
                AdvancedTerminalLang.SETTING_4_TIP, terminal, player
            )
        )

        // 5 使用 AE 物品
        panel.addWidget(tippedLabel(10, 142, AdvancedTerminalLang.SETTING_5, AdvancedTerminalLang.SETTING_5_TIP))
        panel.addWidget(
            checkbox(
                138, 140, AdvancedTerminalSettings.IS_USE_AE, false,
                AdvancedTerminalLang.SETTING_5_TIP, terminal, player
            )
        )

        // 6 镜像搭建
        panel.addWidget(tippedLabel(10, 168, AdvancedTerminalLang.SETTING_6, AdvancedTerminalLang.SETTING_6_TIP))
        panel.addWidget(
            checkbox(
                138, 166, AdvancedTerminalSettings.IS_FLIP, false,
                AdvancedTerminalLang.SETTING_6_TIP, terminal, player
            )
        )

        // 7 模块搭建
        panel.addWidget(tippedLabel(10, 194, AdvancedTerminalLang.SETTING_7, AdvancedTerminalLang.SETTING_7_TIP))
        panel.addWidget(
            numberField(
                terminal, AdvancedTerminalSettings.MODULE,
                AdvancedTerminalSettings.MODULE_MIN, AdvancedTerminalSettings.MODULE_MAX, 116, 193
            )
        )

        // 8 拆除模式
        panel.addWidget(tippedLabel(10, 220, AdvancedTerminalLang.SETTING_8, AdvancedTerminalLang.SETTING_8_TIP))
        panel.addWidget(
            checkbox(
                138, 218, AdvancedTerminalSettings.DEMOLITION, false,
                AdvancedTerminalLang.SETTING_8_TIP, terminal, player
            )
        )
    }

    private fun tippedLabel(x: Int, y: Int, key: String, tipKey: String): SyncedLabelWidget {
        return tippedLabel(x, y, key, Component.translatable(tipKey))
    }

    private fun tippedLabel(x: Int, y: Int, key: String, tip: Component): SyncedLabelWidget {
        val widget = SyncedLabelWidget(x, y, { Component.translatable(key) })
        widget.setHoverTooltips(tip)
        return widget
    }

    private fun checkbox(x: Int, y: Int, key: String, fallback: Boolean, tipKey: String,
                         terminal: Supplier<ItemStack>, player: Player): SwitchWidget {
        val widget = SwitchWidget(x, y, 14, 14) { _, pressed ->
            // 回调两侧都会跑：只在服务端写（客户端写自己背包物品的 NBT 不会同步回服务端）
            if (!player.level().isClientSide) {
                AdvancedTerminalSettings.setFlag(terminal.get(), key, pressed)
            }
        }
        widget.setTexture(GuiTextures.BUTTON, CHECKED)
        widget.setSupplier { AdvancedTerminalSettings.getFlag(terminal.get(), key, fallback) }
        widget.setHoverTooltips(Component.translatable(tipKey))
        return widget
    }

    /**
     * 数字输入框。
     *
     * ⚠️ 构造时把当前值夹一次并回写 —— 也就是「打开一次终端」就会把越界的值夹回范围（与原实现一致，保留）。
     */
    private fun numberField(terminal: Supplier<ItemStack>, key: String, min: Int, max: Int,
                            x: Int, y: Int): TextFieldWidget {
        val stack = terminal.get()
        val clamped = AdvancedTerminalSettings.clamp(AdvancedTerminalSettings.getInt(stack, key, min), min, max)
        AdvancedTerminalSettings.setInt(stack, key, clamped)

        val field = TextFieldWidget(
            x, y, 36, 14,
            { AdvancedTerminalSettings.getInt(terminal.get(), key, min).toString() },
            { raw ->
                val value: String? = raw
                if (!value.isNullOrBlank()) {
                    try {
                        val parsed = Integer.parseInt(value.trim())
                        AdvancedTerminalSettings.setInt(
                            terminal.get(), key,
                            AdvancedTerminalSettings.clamp(parsed, min, max)
                        )
                    } catch (ignored: NumberFormatException) {
                        // 输入途中的半截数字：等下一次回调
                    }
                }
            }
        )
        field.setNumbersOnly(min, max)
        return field
    }

    private fun bumpCoilTier(terminal: ItemStack, player: Player, delta: Int) {
        if (player.level().isClientSide) return
        val max = coilTypes().size
        val current = AdvancedTerminalSettings.getInt(terminal, AdvancedTerminalSettings.COIL_TIER, 0)
        AdvancedTerminalSettings.setInt(
            terminal, AdvancedTerminalSettings.COIL_TIER,
            AdvancedTerminalSettings.clamp(current + delta, 0, max)
        )
    }

    /** 「线圈等级」的 tooltip：第 1 行是文案，之后每档一行「档位序号:线圈显示名」。 */
    private fun coilTooltip(): Component {
        val tip = Component.translatable(AdvancedTerminalLang.SETTING_1_TIP)
        val coils = coilTypes()
        for (i in coils.indices) {
            tip.append("\n").append(Component.literal("${i + 1}:").append(coilName(coils[i])))
        }
        return tip
    }

    /** 全部加热线圈，按 `getTier()` 升序（稳定顺序）。 */
    private fun coilTypes(): List<ICoilType> {
        val types = ArrayList(GTCEuAPI.HEATING_COILS.keys)
        types.sortWith(Comparator.comparingInt<ICoilType> { coil -> coil.tier })
        return types
    }

    private fun coilName(type: ICoilType): Component {
        try {
            val supplier: Supplier<CoilBlock?> = GTCEuAPI.HEATING_COILS[type] ?: return Component.empty()
            val stack = ItemStack(supplier.get())
            return if (stack.isEmpty) Component.empty() else stack.hoverName
        } catch (ignored: Throwable) {
            return Component.empty()
        }
    }

    // ======================== 右上：分级方块（切换） ========================

    private fun fillCyclePanel(panel: TierScrollPanel, groups: List<TerminalSettings.GroupView>,
                               terminal: Supplier<ItemStack>, player: Player) {
        if (groups.isEmpty()) {
            panel.addWidget(TierScrollPanel.emptyHint())
            return
        }
        for (i in groups.indices) {
            panel.addWidget(cycleRow(groups[i], i * ROW_HEIGHT, groups, terminal, player))
        }
    }

    private fun cycleRow(group: TerminalSettings.GroupView, rowTop: Int,
                         all: List<TerminalSettings.GroupView>,
                         terminal: Supplier<ItemStack>, player: Player): Widget {
        val row = WidgetGroup(0, rowTop, PANEL_WIDTH, ROW_HEIGHT)

        // ⚠️ 整行透明按钮必须**先**加入容器：LDLib 的 WidgetGroup#mouseClicked 从后往前找第一个
        // 「吃掉点击」的子控件，图标与文字控件不吃点击，所以整行按钮放最前面既能覆盖整行、
        // 又不会抢走 [▶] 的点击。
        val whole = ButtonWidget(0, 0, 170, ROW_HEIGHT, IGuiTexture.EMPTY) {
            selectGroup(terminal.get(), player, group.key)
        }
        whole.setHoverTooltips(Component.translatable(AdvancedTerminalLang.PANEL_PICK_TIP))
        row.addWidget(whole)

        row.addWidget(ItemIconWidget(4, 0, Supplier { chosenStack(terminal.get(), group) }))
        row.addWidget(
            SyncedLabelWidget(
                24, 4,
                Supplier { rowName(terminal.get(), group, all) },
                false, SyncedLabelWidget.TEXT_COLOR, NAME_MAX_WIDTH
            )
        )

        val next = ButtonWidget(
            174, 1, 18, 14,
            GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_RIGHT)
        ) {
            cycleGroup(terminal.get(), player, group)
        }
        next.setHoverTooltips(Component.translatable(AdvancedTerminalLang.PANEL_CYCLE_TIP))
        row.addWidget(next)
        return row
    }

    /** 点整行 = 把这一组设为「右下正在显示的那一组」（只在服务端写）。 */
    private fun selectGroup(terminal: ItemStack, player: Player, key: String) {
        if (player.level().isClientSide) return
        TerminalSettings.setUiGroup(terminal, key)
    }

    /** 点 [▶] = 先写 ui_group，再把这一组循环到下一档。 */
    private fun cycleGroup(terminal: ItemStack, player: Player, group: TerminalSettings.GroupView) {
        selectGroup(terminal, player, group.key)
        if (player.level().isClientSide) return
        val current = findGroup(terminal, group.key)
        TerminalSettings.cycleChoice(terminal, current ?: group)
    }

    private fun rowName(terminal: ItemStack, group: TerminalSettings.GroupView,
                        all: List<TerminalSettings.GroupView>): Component {
        val current = group.key == currentGroupKey(terminal, all)
        // 行首标记：本行是「右下正在显示的组」时是 ▶，否则两个空格（名字列不左右跳）。
        // ▶ 单独给金黄色：和白色的名字分开，一眼能看出右下正在显示哪一组。
        val marker: MutableComponent = Component.literal(if (current) "\u25b6 " else "  ")
        if (current) marker.withStyle(ChatFormatting.GOLD)
        val stack = chosenStack(terminal, group)
        return marker.append(if (stack.isEmpty) Component.empty() else stack.hoverName)
    }

    private fun currentGroupKey(terminal: ItemStack, all: List<TerminalSettings.GroupView>): String? {
        if (all.isEmpty()) return null
        val ui = TerminalSettings.getUiGroup(terminal)
        for (group in all) {
            if (group.key == ui) return ui
        }
        return all[0].key
    }

    // ======================== 右下：分级方块（勾选） ========================

    private fun fillChoosePanel(panel: TierScrollPanel.Choose, groups: List<TerminalSettings.GroupView>,
                                terminal: Supplier<ItemStack>, player: Player) {
        if (groups.isEmpty()) {
            panel.addWidget(TierScrollPanel.emptyHint())
            panel.refresh()
            return
        }
        for (group in groups) {
            val holder = panel.holder(group.key) ?: continue
            holder.setSize(PANEL_WIDTH, group.candidates.size * ROW_HEIGHT)
            for (i in group.candidates.indices) {
                holder.addWidget(chooseRow(group, group.candidates[i], i * ROW_HEIGHT, terminal, player))
            }
        }
        panel.refresh()
    }

    private fun chooseRow(group: TerminalSettings.GroupView, itemId: String, rowTop: Int,
                          terminal: Supplier<ItemStack>, player: Player): Widget {
        val row = WidgetGroup(0, rowTop, PANEL_WIDTH, ROW_HEIGHT)
        val shown = StructureBuildPlanner.itemStackOf(itemId) ?: ItemStack.EMPTY

        row.addWidget(ItemIconWidget(4, 0) { shown })
        row.addWidget(
            SyncedLabelWidget(
                24, 4,
                Supplier { shown.hoverName },
                false, SyncedLabelWidget.TEXT_COLOR, NAME_MAX_WIDTH
            )
        )

        val check = SwitchWidget(174, 1, 14, 14) { _, _ ->
            // 点已选中的行不会取消选择：回调无条件写入同一档
            if (!player.level().isClientSide) {
                TerminalSettings.setPreference(terminal.get(), group.key, itemId)
            }
        }
        check.setTexture(GuiTextures.BUTTON, CHECKED)
        check.setSupplier { itemId == chosenId(terminal.get(), group) }
        row.addWidget(check)
        return row
    }

    // ======================== 偏好读取 ========================

    private fun findGroup(terminal: ItemStack, key: String): TerminalSettings.GroupView? {
        for (group in TerminalSettings.cachedGroups(terminal)) {
            if (group.key == key) return group
        }
        return null
    }

    private fun chosenId(terminal: ItemStack, group: TerminalSettings.GroupView): String? {
        val current = findGroup(terminal, group.key)
        return current?.chosen ?: group.chosen
    }

    private fun chosenStack(terminal: ItemStack, group: TerminalSettings.GroupView): ItemStack {
        val id = chosenId(terminal, group) ?: return ItemStack.EMPTY
        val stack = StructureBuildPlanner.itemStackOf(id)
        return stack ?: ItemStack.EMPTY
    }
}