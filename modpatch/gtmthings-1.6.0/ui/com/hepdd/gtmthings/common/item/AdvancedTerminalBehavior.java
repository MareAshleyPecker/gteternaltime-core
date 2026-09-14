package com.hepdd.gtmthings.common.item;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.common.block.CoilBlock;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import com.hepdd.gtmthings.api.gui.widget.AlignLabelWidget;
import com.hepdd.gtmthings.api.gui.widget.TerminalInputWidget;
import com.hepdd.gtmthings.api.misc.Hatch;
import com.hepdd.gtmthings.api.pattern.AdvancedBlockPattern;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.hepdd.gtmthings.api.gui.widget.AlignLabelWidget.ALIGN_CENTER;
import static com.hepdd.gtmthings.api.pattern.AdvancedBlockPattern.getAdvancedBlockPattern;

/**
 * GTMThings 高级终端。
 *
 * <p>
 * 界面部分（{@link #createWidget}）改成模仿 GTOCore「高级终端设置」的样式：
 * 标题 + 右上角 X；左侧一列「标签在左、控件在右」的设置行（文本输入 / [◀] 值 [▶] 步进器 / ✓ 复选框）；
 * 右侧上下两块可滚动的分级方块列表：上为「一组一行：方块 + [▶]」，下为「一档一行：方块 + ✓」。
 *
 * <p>
 * <b>第四轮（本次）</b>把右侧两块改成<b>联动</b>：点右上某一行（或那一行的 [▶]）即选中该组，
 * 右下那块<b>只列当前选中的那一组的候选</b>（此前它把"所有组的所有候选"混在一起排成一长串）。
 * 选中的组记在终端 NBT 的 {@code gtet_terminal.ui_group} 里（服务端权威、随物品同步到客户端），
 * 布局与它的三条 LDLib 约束见 {@link TierListPanel}。
 *
 * <p>
 * <b>第二轮</b>把 GTO 高级终端里剩下的三项「搭建行为」设置补齐，这三个是<b>逻辑</b>改动，
 * 不只是画几行控件（放置逻辑见 {@code AdvancedBlockPattern#autoBuild}）：
 * <ul>
 * <li>{@link AutoBuildSetting#getModule() 模块搭建}（NBT {@code Module}）——
 * {@code 0} = 主结构（{@code controller.getPattern()}），{@code N>0} = 第 N 套结构；</li>
 * <li>{@link AutoBuildSetting#getIsFlip() 镜像搭建}（NBT {@code IsFlip}）—— 放置时按镜像坐标摆放；</li>
 * <li>{@link AutoBuildSetting#getDemolition() 拆除模式}（NBT {@code Demolition}）—— 反向操作：
 * 按结构把该位置的方块拆掉，不放置。</li>
 * </ul>
 * 语义与实现取舍（尤其是「GTM 7.5.3 没有 {@code getSubPattern}」这条）记在
 * {@code modpatch/gtmthings-1.6.0/NOTES.md} 第 6 节；面板那一轮的取舍记在第 10 节。
 */
public class AdvancedTerminalBehavior implements IItemUIFactory {

    // ==================== GTO 样式界面布局 ====================
    // 窗口 372x274：左侧 160 宽的设置面板（8 行设置，行距 26、第 2 行之后多留一行空档），
    // 右侧上下两块 198 宽的竖向列表（每块**可见 7 行**，更多的行靠滚动条）。
    // ⚠️ 尺寸 / 坐标一改，modpatch/gtmthings-1.6.0/NOTES.md 的 §2 §3 两张坐标表也要跟着改。
    private static final int UI_WIDTH = 372;
    private static final int UI_HEIGHT = 274;
    /** 列表行高（图标 16x16 一行）；设置行的行距见 {@link #SET_ROW_Y}。 */
    private static final int ROW_H = 16;
    /** 左侧设置面板的位置与宽度。 */
    private static final int PANEL_X = 4;
    private static final int PANEL_W = 160;
    /** 左侧 8 行设置的行顶 y（面板内坐标）：行距 26，第 2 行之后多留 8 像素的空档。 */
    private static final int[] SET_ROW_Y = { 28, 54, 88, 114, 140, 166, 192, 218 };
    private static final int LABEL_X = 10;
    /** 设置行控件统一右边缘（面板内坐标）。 */
    private static final int CTRL_RIGHT = 152;
    /** 右侧两块列表面板：x / 宽，以及各自的标题 y 与列表 y、高。 */
    private static final int LIST_X = 170;
    private static final int LIST_W = 198;
    private static final int LIST1_TITLE_Y = 8;
    private static final int LIST1_Y = 20;
    private static final int LIST1_H = 114;
    private static final int LIST2_TITLE_Y = 142;
    private static final int LIST2_Y = 154;
    private static final int LIST2_H = 114;
    /** 列表行里控件（▶ / ✓）的 x，名字最多占这么多字符，超长截断。 */
    private static final int LIST_CTRL_X = 174;
    private static final int NAME_MAX_CHARS = 14;

    private static final String TITLE = "item.gtmthings.advanced_terminal.setting.title";
    private static final String PANEL_CYCLE = "item.gtmthings.advanced_terminal.panel.cycle";
    private static final String PANEL_CYCLE_TIP = "item.gtmthings.advanced_terminal.panel.cycle.tooltip";
    private static final String PANEL_CHOOSE = "item.gtmthings.advanced_terminal.panel.choose";
    private static final String PANEL_PICK_TIP = "item.gtmthings.advanced_terminal.panel.pick.tooltip";
    private static final String PANEL_EMPTY = "item.gtmthings.advanced_terminal.panel.empty";

    public AdvancedTerminalBehavior() {}

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            Level level = context.getLevel();
            BlockPos blockPos = context.getClickedPos();
            if (context.getPlayer() != null && !level.isClientSide() &&
                    MetaMachine.getMachine(level, blockPos) instanceof IMultiController controller) {
                AutoBuildSetting autoBuildSetting = getAutoBuildSetting(context.getPlayer().getMainHandItem());
                AdvancedBlockPattern pattern = resolvePattern(controller, autoBuildSetting);

                if (pattern != null) {
                    if (autoBuildSetting.isDemolitionMode()) {
                        // 拆除模式：成型与否都要能拆，所以不走另外两条分支的「未成型 / 线圈替换」判断。
                        // 照 GTO：拆完只清一次缓存（GTO 的 cleanCache() —— GTMThings 编译期用的 GTM 7.5.2
                        // 没有这个方法，所以走 AdvancedBlockPattern.clearCache 那层版本适配），
                        // 不调 requestCheck() —— 拆到一半的结构不该被判成型。
                        pattern.autoBuild(context.getPlayer(), controller.getMultiblockState(), autoBuildSetting);
                        AdvancedBlockPattern.clearCache(controller.getMultiblockState());
                    } else if (!controller.isFormed()) {
                        pattern.autoBuild(context.getPlayer(), controller.getMultiblockState(), autoBuildSetting);
                    } else if (MetaMachine.getMachine(level, blockPos) instanceof WorkableMultiblockMachine workableMultiblockMachine && autoBuildSetting.isReplaceCoilMode()) {
                        pattern.autoBuild(context.getPlayer(), controller.getMultiblockState(), autoBuildSetting);
                        workableMultiblockMachine.onPartUnload();
                    }
                }

            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /**
     * 按「模块搭建」选出这次要搭的结构。
     *
     * <p>
     * 语义（与 GTO 对齐，GTO 的写法是
     * {@code autoBuildSetting.module > 0 && subPattern != null ? subPattern[min(len, module) - 1].get() : controller.getPattern()}）：
     * <ul>
     * <li>{@code module == 0} → <b>主结构</b>：{@code controller.getPattern()}。
     * 这就是本补丁对「主结构搭建」的落地方式 —— <b>不新开 NBT 键、不单独列 UI 行</b>，
     * 「主结构搭建」= {@code module} 的第 1 档（0）；</li>
     * <li>{@code module == N > 0} → 第 N 套结构（见 {@link #resolveSubPattern}）；</li>
     * <li>取不到第 N 套（下面两条路都走不通，或下标越界/抛异常）→ <b>退回主结构</b>，
     * 和 GTO 在 {@code subPattern == null} 时的行为一致（静默降级，不报错）。</li>
     * </ul>
     *
     * @return 转换后的 {@link AdvancedBlockPattern}；转换失败（反射拿不到字段）时为 {@code null}
     */
    private static AdvancedBlockPattern resolvePattern(IMultiController controller, AutoBuildSetting autoBuildSetting) {
        int module = autoBuildSetting.getModule();
        if (module > 0) {
            BlockPattern sub = resolveSubPattern(controller, module);
            if (sub != null) {
                AdvancedBlockPattern advanced = getAdvancedBlockPattern(sub);
                if (advanced != null) return advanced;
            }
        }
        return getAdvancedBlockPattern(controller.getPattern());
    }

    /**
     * 「第 N 套结构」的取法。⚠️ <b>GTM 7.5.3 没有 GTO 分叉里的 {@code IMultiController#getSubPattern()}</b>
     * （javap 实证：{@code IMultiController} 只有 {@code getPattern()}），所以这里按优先级探两条路：
     *
     * <ol>
     * <li>GTO 分叉/未来版本的 {@code getSubPattern()}：返回 {@code Supplier<BlockPattern>[]}，
     * 取 {@code subs[min(len, module) - 1]} 再 {@code get()}（下标算法照 GTO）。
     * 在 GTM 7.5.3 上这条永远取不到，留着是为了「别的整合包用分叉 GTM 时自动就能用」；</li>
     * <li>GTET 自己的多结构路子：{@code rain.gtetcore...ETModularMachine#patternOfTier(int)}
     * —— 它的「第 N 套结构」就是「第 N 档模块对应的结构」（GTET 的模块化多方块只有这一套多结构 API：
     * {@code getPattern()} = {@code patternOfTier(moduleTier)}，另外没有枚举/索引结构的方法）。
     * 用反射调是因为<b>补丁 jar 由 GTMThings 自己 build，编译期不能依赖 GTET</b>；
     * 只探 {@code rain.gtetcore.} 包下的类，避免误撞别的 mod 的同名方法。</li>
     * </ol>
     *
     * @return 第 N 套结构；取不到返回 {@code null}（调用方退回主结构）
     */
    @Nullable
    private static BlockPattern resolveSubPattern(IMultiController controller, int module) {
        // ① GTO 分叉的 getSubPattern()（GTM 7.5.3 不存在，见方法注释）
        try {
            Method method = controller.getClass().getMethod("getSubPattern");
            if (method.getReturnType().isArray()) {
                Object[] subs = (Object[]) method.invoke(controller);
                if (subs != null && subs.length > 0) {
                    Object entry = subs[Math.min(subs.length, module) - 1];
                    Object unwrapped = entry instanceof Supplier<?> supplier ? supplier.get() : entry;
                    if (unwrapped instanceof BlockPattern blockPattern) return blockPattern;
                }
            }
        } catch (Throwable ignored) {
            // 没有这个方法 / 不是数组 / 下标越界：走下一步
        }

        // ② GTET：ETModularMachine#patternOfTier(int)（Kotlin 的 protected，反射要 setAccessible）
        Class<?> type = controller.getClass();
        if (!type.getName().startsWith("rain.gtetcore.")) return null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod("patternOfTier", int.class);
                method.setAccessible(true);
                Object result = method.invoke(controller, module);
                return result instanceof BlockPattern blockPattern ? blockPattern : null;
            } catch (NoSuchMethodException ignored) {
                // 这个类没声明，往父类找
            } catch (Throwable ignored) {
                // 反射被拒 / 等级不受支持时内部抛异常：退回主结构
                return null;
            }
        }
        return null;
    }

    private AutoBuildSetting getAutoBuildSetting(ItemStack itemStack) {
        AutoBuildSetting autoBuildSetting = new AutoBuildSetting();
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty()) {
            autoBuildSetting.setCoilTier(tag.contains("CoilTier") ? tag.getInt("CoilTier") : 0);
            autoBuildSetting.setRepeatCount(tag.contains("CoilTier") ? tag.getInt("RepeatCount") : 0);
            autoBuildSetting.setNoHatchMode(tag.contains("CoilTier") ? tag.getInt("NoHatchMode") : 1);
            autoBuildSetting.setReplaceCoilMode(tag.contains("CoilTier") ? tag.getInt("ReplaceCoilMode") : 0);
            autoBuildSetting.setIsUseAE(tag.contains("CoilTier") ? tag.getInt("IsUseAE") : 0);
            autoBuildSetting.setIsFlip(tag.contains("CoilTier") ? tag.getInt("IsFlip") : 0);
            autoBuildSetting.setModule(tag.contains("CoilTier") ? tag.getInt("Module") : 0);
            autoBuildSetting.setDemolition(tag.contains("CoilTier") ? tag.getInt("Demolition") : 0);
        } else {
            autoBuildSetting.setCoilTier(0);
            autoBuildSetting.setRepeatCount(0);
            autoBuildSetting.setNoHatchMode(1);
            autoBuildSetting.setReplaceCoilMode(0);
            autoBuildSetting.setIsUseAE(0);
            autoBuildSetting.setIsFlip(0);
            autoBuildSetting.setModule(0);
            autoBuildSetting.setDemolition(0);
        }
        return autoBuildSetting;
    }

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player entityPlayer) {
        return new ModularUI(UI_WIDTH, UI_HEIGHT, holder, entityPlayer).widget(createWidget(entityPlayer));
    }

    // ==================== 界面 ====================

    private Widget createWidget(Player entityPlayer) {
        ItemStack handItem = entityPlayer.getMainHandItem();
        var group = new WidgetGroup(0, 0, UI_WIDTH, UI_HEIGHT);

        // 标题（居中于左侧设置面板）+ 右上角 X
        group.addWidget(new AlignLabelWidget(PANEL_X + PANEL_W / 2, 8, TITLE).setTextAlign(ALIGN_CENTER));
        group.addWidget(new ButtonWidget(UI_WIDTH - 18, 7, 12, 12, GuiTextures.CLOSE_ICON,
                // 按钮回调两侧都会跑：客户端的 closeContainer 是空实现，服务端关掉容器后客户端界面自然关闭
                clickData -> entityPlayer.closeContainer()));

        group.addWidget(createSettingPanel(entityPlayer, handItem));

        // 右侧上：方块 + [▶]（点行 = 选中该组，点 [▶] = 选中并切到下一档）
        group.addWidget(new AlignLabelWidget(LIST_X + 4, LIST1_TITLE_Y, PANEL_CYCLE));
        group.addWidget(createCycleList(entityPlayer, handItem, LIST1_Y, LIST1_H));
        // 右侧下：**只列右上当前选中的那一组**的候选，方块 + ✓（两块联动）
        group.addWidget(new AlignLabelWidget(LIST_X + 4, LIST2_TITLE_Y, PANEL_CHOOSE));
        group.addWidget(createChooseList(entityPlayer, handItem, LIST2_Y, LIST2_H));

        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    /** 左侧设置面板：一行一个设置项，标签在左、控件贴着右边缘。 */
    private WidgetGroup createSettingPanel(Player entityPlayer, ItemStack handItem) {
        var panel = new WidgetGroup(PANEL_X, 4, PANEL_W, UI_HEIGHT - 8);
        panel.setBackground(GuiTextures.DISPLAY);

        // 1 线圈等级：[◀] 当前值 [▶]
        List<Component> coilLines = new ArrayList<>(List.of());
        coilLines.add(Component.translatable("item.gtmthings.advanced_terminal.setting.1.tooltip"));
        GTCEuAPI.HEATING_COILS.entrySet().stream()
                .sorted(Comparator.comparingInt(value -> value.getKey().getTier()))
                .forEach(coil -> coilLines.add(Component.literal(String.valueOf(coil.getKey().getTier() + 1)).append(":").append(coil.getValue().get().getName())));
        int rowY = SET_ROW_Y[0];
        panel.addWidget(new AlignLabelWidget(LABEL_X, rowY + 2, "item.gtmthings.advanced_terminal.setting.1")
                .setHoverTooltips(coilLines));
        panel.addWidget(new ButtonWidget(CTRL_RIGHT - 42, rowY, 12, 12,
                new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_LEFT),
                clickData -> cycleCoilTier(entityPlayer, handItem, -1)));
        panel.addWidget(new AlignLabelWidget(CTRL_RIGHT - 22, rowY + 2, () -> String.valueOf(getCoilTier(handItem)))
                .setTextAlign(ALIGN_CENTER));
        panel.addWidget(new ButtonWidget(CTRL_RIGHT - 12, rowY, 12, 12,
                new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_RIGHT),
                clickData -> cycleCoilTier(entityPlayer, handItem, 1)));

        // 2 重复结构次数：文本输入框（上限对齐 GTO 的 1000，原来是 99）
        rowY = SET_ROW_Y[1];
        panel.addWidget(new AlignLabelWidget(LABEL_X, rowY + 2, "item.gtmthings.advanced_terminal.setting.2")
                .setHoverTooltips("item.gtmthings.advanced_terminal.setting.2.tooltip"));
        panel.addWidget(new TerminalInputWidget(CTRL_RIGHT - 36, rowY + 1, 36, 14, () -> getRepeatCount(handItem),
                (v) -> setRepeatCount(v, handItem)).setMin(0).setMax(1000));

        // 3~5 三个 0/1 开关：✓ 复选框
        addCheckRow(panel, entityPlayer, SET_ROW_Y[2], "item.gtmthings.advanced_terminal.setting.3",
                () -> getIsBuildHatches(handItem) == 1, v -> setIsBuildHatches(v ? 1 : 0, handItem));
        addCheckRow(panel, entityPlayer, SET_ROW_Y[3], "item.gtmthings.advanced_terminal.setting.4",
                () -> getReplaceCoilMode(handItem) == 1, v -> setReplaceCoilMode(v ? 1 : 0, handItem));
        addCheckRow(panel, entityPlayer, SET_ROW_Y[4], "item.gtmthings.advanced_terminal.setting.5",
                () -> getIsUseAE(handItem) == 1, v -> setIsUseAE(v ? 1 : 0, handItem));

        // 6 镜像搭建：0/1 开关（✓ 复选框）
        addCheckRow(panel, entityPlayer, SET_ROW_Y[5], "item.gtmthings.advanced_terminal.setting.6",
                () -> getIsFlip(handItem) == 1, v -> setIsFlip(v ? 1 : 0, handItem));

        // 7 模块搭建：0 = 主结构，N = 第 N 套结构。档数不定（取决于控制器），所以用文本框而不是步进器
        rowY = SET_ROW_Y[6];
        panel.addWidget(new AlignLabelWidget(LABEL_X, rowY + 2, "item.gtmthings.advanced_terminal.setting.7")
                .setHoverTooltips("item.gtmthings.advanced_terminal.setting.7.tooltip"));
        panel.addWidget(new TerminalInputWidget(CTRL_RIGHT - 36, rowY + 1, 36, 14, () -> getModule(handItem),
                (v) -> setModule(v, handItem)).setMin(0).setMax(100));

        // 8 拆除模式：0/1 开关（✓ 复选框）
        addCheckRow(panel, entityPlayer, SET_ROW_Y[7], "item.gtmthings.advanced_terminal.setting.8",
                () -> getDemolition(handItem) == 1, v -> setDemolition(v ? 1 : 0, handItem));

        return panel;
    }

    /** 一行「标签 + ✓ 复选框」；{@code rowY} 是这一行的行顶 y。 */
    private void addCheckRow(WidgetGroup panel, Player entityPlayer, int rowY, String langKey,
                             Supplier<Boolean> getter, Consumer<Boolean> setter) {
        panel.addWidget(new AlignLabelWidget(LABEL_X, rowY + 2, langKey).setHoverTooltips(langKey + ".tooltip"));
        panel.addWidget(new SwitchWidget(CTRL_RIGHT - 14, rowY, 14, 14, (clickData, value) -> {
            // SwitchWidget 的回调两侧都会跑，NBT 只在服务端写（客户端由服务端每 tick 同步）
            if (entityPlayer.level().isClientSide()) return;
            setter.accept(value);
        }).setTexture(checkboxTexture(false), checkboxTexture(true))
                .setSupplier(getter)
                .setPressed(getter.get())
                .setHoverTooltips(langKey + ".tooltip"));
    }

    /**
     * 右上「分级方块（切换）」：一组一行「图标 + 名字 + [▶]」。
     *
     * <p>
     * ⚠️ 行数由终端 NBT 决定，而 LDLib 是按控件路径同步数据的：服务端和客户端必须在同一份
     * NBT 上建树，所以两边都读「玩家主手物品」（服务端每 tick 会把改动同步给客户端）。
     *
     * <p>
     * 点**行**（图标/名字那一带）= 把这一组设为右下块显示的那一组；点 **[▶]** = 做同样的事再切到下一档。
     */
    private Widget createCycleList(Player entityPlayer, ItemStack handItem, int y, int height) {
        var list = new DraggableScrollableWidgetGroup(LIST_X, y, LIST_W, height);
        style(list);
        List<TierGroups.Group> groups = TierGroups.read(handItem);
        if (groups.isEmpty()) return emptyHint(list);
        int rowY = 0;
        for (TierGroups.Group group : groups) {
            addCycleRow(list, entityPlayer, handItem, groups, rowY, group);
            rowY += ROW_H;
        }
        return list;
    }

    /**
     * 右下「分级方块（勾选）」：**只列右上当前选中的那一组**的候选（两块联动）。
     *
     * <p>
     * ⚠️ 这里**不是**「按当前选中的组建树」，而是「每组建一个子容器、全都建出来」，再由
     * {@link TierListPanel} 按 NBT 里那个"当前组"决定谁可见、谁被挪出可视区 ——
     * 原因是 LDLib 的控件树两端各建一次、数据按控件路径同步，树结构任何时候都必须一致。
     * 三条 LDLib 事实与其后果写在 {@link TierListPanel} 的类注释里。
     */
    private Widget createChooseList(Player entityPlayer, ItemStack handItem, int y, int height) {
        List<TierGroups.Group> groups = TierGroups.read(handItem);
        var list = new TierListPanel(handItem, groups, y, height);
        if (groups.isEmpty()) return emptyHint(list);
        for (TierGroups.Group group : groups) {
            // 一个组一个子容器：容器高度 = 这一组的候选行数（控件树固定，只换可见性与位置）
            var container = new WidgetGroup(0, 0, LIST_W, group.candidates().size() * ROW_H);
            int rowY = 0;
            for (String candidate : group.candidates()) {
                addChooseRow(container, entityPlayer, handItem, rowY, group, candidate);
                rowY += ROW_H;
            }
            list.addGroup(container);
        }
        list.applyLayout();
        return list;
    }

    /** 两块列表面板共用的外壳样式：背景 + 2 像素细滚动条 + 不拖动 + 裁剪。 */
    private static void style(DraggableScrollableWidgetGroup list) {
        list.setBackground(GuiTextures.DISPLAY);
        list.setYScrollBarWidth(2);
        list.setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(1));
        list.setDraggable(false);
        list.setUseScissor(true);
    }

    /** 一组都没读到（NBT 里既没有静态组也没有扫描结果）时的提示。 */
    private static <T extends DraggableScrollableWidgetGroup> T emptyHint(T list) {
        var hint = new AlignLabelWidget(4, 4, PANEL_EMPTY);
        hint.setColor(0x808080);
        list.addWidget(hint);
        return list;
    }

    /** 点某一行 = 把这一组设为右下块显示的那一组。⚠️ 只在服务端写 NBT（客户端写了不会同步回去）。 */
    private static void selectGroup(Player entityPlayer, ItemStack handItem, TierGroups.Group group) {
        if (entityPlayer.level().isClientSide()) return;
        TierGroups.setActive(handItem, group.key());
    }

    /** 「图标 + 名字 + [▶]」一行；整行可点（选中该组），[▶] 另外还能切档。 */
    private void addCycleRow(DraggableScrollableWidgetGroup list, Player entityPlayer, ItemStack handItem,
                             List<TierGroups.Group> groups, int rowY, TierGroups.Group group) {
        // ⚠️ 这个整行按钮必须**先**加：LDLib 的 WidgetGroup#mouseClicked 是从后往前找
        // 第一个"吃掉"点击的控件（javap 实证），图标与文字控件不吃点击、[▶] 按钮要吃，
        // 所以放在最底层既能覆盖整行、又不会抢走 [▶] 的点击。
        list.addWidget(new ButtonWidget(0, rowY, LIST_CTRL_X - 4, ROW_H, IGuiTexture.EMPTY,
                clickData -> selectGroup(entityPlayer, handItem, group))
                .setHoverTooltips(PANEL_PICK_TIP));
        list.addWidget(new ItemIconWidget(4, rowY, () -> TierGroups.icon(TierGroups.chosen(handItem, group))));
        // 行首标出"右下正在显示的那一组"；没选中的补同样宽度的空格，名字列不会左右跳
        list.addWidget(new AlignLabelWidget(24, rowY + 4, () -> TierGroups.marker(handItem, groups, group) +
                TierGroups.name(TierGroups.chosen(handItem, group))));
        list.addWidget(new ButtonWidget(LIST_CTRL_X, rowY + 1, 18, 14,
                new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_RIGHT),
                clickData -> {
                    if (entityPlayer.level().isClientSide()) return;
                    // 按 ▶ 也把这一组设为"右下显示的那一组"：玩家的意图就是"我要调这一组"
                    TierGroups.setActive(handItem, group.key());
                    TierGroups.cycle(handItem, group);
                }).setHoverTooltips(PANEL_CYCLE_TIP));
    }

    /** 「图标 + 名字 + ✓」一行（勾上即把该档设为这一组的选择）。 */
    private void addChooseRow(WidgetGroup list, Player entityPlayer, ItemStack handItem,
                              int rowY, TierGroups.Group group, String candidate) {
        list.addWidget(new ItemIconWidget(4, rowY, () -> TierGroups.icon(candidate)));
        list.addWidget(new AlignLabelWidget(24, rowY + 4, () -> TierGroups.name(candidate)));
        list.addWidget(new SwitchWidget(LIST_CTRL_X, rowY + 1, 14, 14, (clickData, value) -> {
            if (entityPlayer.level().isClientSide()) return;
            TierGroups.setPreference(handItem, group.key(), candidate);
        }).setTexture(checkboxTexture(false), checkboxTexture(true))
                .setSupplier(() -> TierGroups.chosen(handItem, group).equals(candidate))
                .setPressed(TierGroups.chosen(handItem, group).equals(candidate)));
    }

    /**
     * 右下「勾选」块的外壳：**只显示右上选中的那一组**（两块联动的下半截）。
     *
     * <p>
     * 三条 LDLib 事实决定了这里为什么写成"全都建出来、只换可见性 + 位置"（都是打在**本项目实际编译用的**
     * ldlib deobf jar 上的 javap 实证，不是猜的）：
     *
     * <ol>
     * <li><b>控件树两端各建一次、数据按控件路径同步</b> ⇒ 树结构在界面存活期间必须一致。
     * 所以不能"按选中的组建树"：那样客户端换了组、服务端没换，两边的控件下标就对不上，
     * 所有 {@code writeUpdateInfo} 都会错位。这里改成「每组建一个子容器、全都建出来」，
     * 结构固定，只有 {@code setVisible}/位置在变。</li>
     * <li>{@code WidgetGroup#detectAndSendChanges} 只按 {@code isActive()} 过滤子控件、
     * <b>不看 {@code isVisible()}</b> ⇒ {@code setVisible(false)} 不会掐断被藏起来的那些行
     * 的数据同步（勾选态照旧更新）。</li>
     * <li>{@code DraggableScrollableWidgetGroup#computeMax} 对**所有**子控件取
     * {@code height + selfY + scrollYOffset} 的最大值，同样不看可见性 ⇒ 光隐藏不挪位置，
     * 滚动条仍按"所有组加起来"的高度给出一大段空白。所以藏起来的容器必须挪到 y 为负的位置
     * （见 {@link #HIDDEN_Y}），被选中那个留在 y = 0。</li>
     * </ol>
     *
     * <p>
     * "当前是哪一组"读的是终端 NBT（{@link TierGroups#activeIndex}），不是这个控件里的本地字段：
     * 界面两端各建一次，状态必须是两端共享的同一份（服务端权威、写完同步给客户端），
     * 本地字段做不到（服务端那份会一直停在第一组）。
     */
    private static final class TierListPanel extends DraggableScrollableWidgetGroup {

        /**
         * 被藏起来的组容器挪到这么远的"上面"。
         *
         * <p>
         * ⚠️ 不能只写 {@code setVisible(false)}：见类注释第 3 条，滚动区的最大高度是按**所有**子控件算的。
         * 挪到负 y 之后它们的 {@code height + selfY} 是负数，取 max 时自然被忽略，滚动范围就等于
         * 当前这一组的高度。
         */
        private static final int HIDDEN_Y = -10000;

        private final ItemStack terminal;
        private final List<TierGroups.Group> groups;
        private final List<WidgetGroup> containers = new ArrayList<>();
        /** 已经应用过的组下标（没变就不重复摆，免得每 tick 都动一次控件树）。 */
        private int applied = Integer.MIN_VALUE;
        /** 初始化之后有没有补算过一次滚动高度（见 {@link #applyLayout()} 里的 ⚠️）。 */
        private boolean maxComputed;

        private TierListPanel(ItemStack terminal, List<TierGroups.Group> groups, int y, int height) {
            super(LIST_X, y, LIST_W, height);
            this.terminal = terminal;
            this.groups = groups;
            style(this);
        }

        private void addGroup(WidgetGroup container) {
            containers.add(container);
            addWidget(container);
        }

        /** 服务端每 tick 都会走到（{@code ModularUIContainer} 那条链）。 */
        @Override
        public void detectAndSendChanges() {
            super.detectAndSendChanges();
            applyLayout();
        }

        /** 客户端每 tick 都会走到（{@code ModularUIGuiContainer#containerTick} → {@code mainGroup.updateScreen()}）。 */
        @Override
        @OnlyIn(Dist.CLIENT)
        public void updateScreen() {
            super.updateScreen();
            applyLayout();
        }

        /** 按 NBT 里那个"当前组"摆位置：选中的留在 y = 0，其余藏起来并挪出滚动范围。 */
        private void applyLayout() {
            int active = TierGroups.activeIndex(terminal, groups);
            boolean changed = active != applied;
            if (changed) {
                applied = active;
                for (int i = 0; i < containers.size(); i++) {
                    WidgetGroup container = containers.get(i);
                    boolean shown = i == active;
                    container.setVisible(shown);
                    container.setSelfPosition(0, shown ? 0 : HIDDEN_Y);
                }
                setScrollYOffset(0);   // 换了组就把滚动条拉回顶部，别停在上一个组的滚动位置上
            }
            // ⚠️ 这一下不能省：{@code computeMax} 只在"子控件尺寸/位置变化"时被自动调用，
            // 而这里第一次布局发生在 initWidget **之前**（那时被 isInitialized() 挡掉），
            // 之后又因为"没变化"直接返回 —— 不补算一次，滚动区最大高度会一直停在 0，
            // 候选一多就滚不动（下面的行看得见却够不着）。
            if (isInitialized() && (changed || !maxComputed)) {
                maxComputed = true;
                computeMax();
            }
        }
    }

    /** 勾选框贴图：空框 / 金黄色 ✓。 */
    private static IGuiTexture checkboxTexture(boolean checked) {
        return checked ? new GuiTextureGroup(GuiTextures.BUTTON,
                new TextTexture("✔", 0xFFAA00).setDropShadow(true)) : GuiTextures.BUTTON;
    }

    /** 步进器：只有服务端写 NBT（按钮回调两侧都会跑）。 */
    private void cycleCoilTier(Player entityPlayer, ItemStack handItem, int delta) {
        if (entityPlayer.level().isClientSide()) return;
        setCoilTier(Mth.clamp(getCoilTier(handItem) + delta, 0, GTCEuAPI.HEATING_COILS.size()), handItem);
    }

    private int getCoilTier(ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty() && tag.contains("CoilTier")) {
            return tag.getInt("CoilTier");
        } else {
            return 0;
        }
    }

    private void setCoilTier(int coilTier, ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("CoilTier", coilTier);
        itemStack.setTag(tag);
    }

    private int getRepeatCount(ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty() && tag.contains("RepeatCount")) {
            return tag.getInt("RepeatCount");
        } else {
            return 0;
        }
    }

    private void setRepeatCount(int repeatCount, ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("RepeatCount", repeatCount);
        itemStack.setTag(tag);
    }

    private int getIsBuildHatches(ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty() && tag.contains("NoHatchMode")) {
            return tag.getInt("NoHatchMode");
        } else {
            return 1;
        }
    }

    private void setIsBuildHatches(int isBuildHatches, ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("NoHatchMode", isBuildHatches);
        itemStack.setTag(tag);
    }

    private int getReplaceCoilMode(ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty() && tag.contains("ReplaceCoilMode")) {
            return tag.getInt("ReplaceCoilMode");
        } else {
            return 0;
        }
    }

    private void setReplaceCoilMode(int isReplaceCoil, ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("ReplaceCoilMode", isReplaceCoil);
        itemStack.setTag(tag);
    }

    private int getIsUseAE(ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty() && tag.contains("IsUseAE")) {
            return tag.getInt("IsUseAE");
        } else {
            return 0;
        }
    }

    private void setIsUseAE(int isUseAE, ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("IsUseAE", isUseAE);
        itemStack.setTag(tag);
    }

    private int getIsFlip(ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty() && tag.contains("CoilTier")) {
            return tag.getInt("IsFlip");
        } else {
            return 0;
        }
    }

    private void setIsFlip(int isFlip, ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("IsFlip", isFlip);
        itemStack.setTag(tag);
    }

    private int getModule(ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty() && tag.contains("CoilTier")) {
            return tag.getInt("Module");
        } else {
            return 0;
        }
    }

    private void setModule(int module, ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("Module", module);
        itemStack.setTag(tag);
    }

    private int getDemolition(ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag != null && !tag.isEmpty() && tag.contains("CoilTier")) {
            return tag.getInt("Demolition");
        } else {
            return 0;
        }
    }

    private void setDemolition(int demolition, ItemStack itemStack) {
        var tag = itemStack.getTag();
        if (tag == null) tag = new CompoundTag();
        tag.putInt("Demolition", demolition);
        itemStack.setTag(tag);
    }

    // ==================== 内部控件 / 数据 ====================

    /**
     * 只画一个方块图标的小控件（LDLib 没有「动态物品图标」控件）。
     *
     * <p>
     * 照 LabelWidget / SlotWidget 的办法自己同步：服务端每 tick 求值 supplier，
     * 变了就把物品发给客户端 —— 客户端的物品 NBT 会慢一拍，直接读会闪旧值。
     */
    private static final class ItemIconWidget extends Widget {

        private final Supplier<ItemStack> stackSupplier;
        private ItemStack lastItem = ItemStack.EMPTY;

        private ItemIconWidget(int x, int y, Supplier<ItemStack> stackSupplier) {
            super(x, y, 16, 16);
            this.stackSupplier = stackSupplier;
            this.lastItem = stackSupplier.get();
        }

        @Override
        public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer);
            buffer.writeItem(stackSupplier.get());
        }

        @Override
        public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer);
            this.lastItem = buffer.readItem();
        }

        @Override
        public void detectAndSendChanges() {
            super.detectAndSendChanges();
            ItemStack latest = stackSupplier.get();
            if (!ItemStack.matches(latest, lastItem)) {
                this.lastItem = latest;
                this.writeUpdateInfo(1, buffer -> buffer.writeItem(latest));
            }
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == 1) {
                this.lastItem = buffer.readItem();
            } else {
                super.readUpdateInfo(id, buffer);
            }
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            if (!lastItem.isEmpty()) {
                graphics.renderItem(lastItem, getPosition().x, getPosition().y);
            }
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            if (!lastItem.isEmpty() && this.gui != null && isMouseOverElement(mouseX, mouseY)) {
                this.gui.getModularUIGui().setHoverTooltip(List.of(lastItem.getHoverName()), lastItem, null, null);
            }
        }
    }

    /**
     * 分级方块数据（读写终端物品 NBT）。
     *
     * <p>
     * ⚠️ 这是 GTET（rain.gtetcore.gtet.common.item.terminal.TerminalSettings）的 NBT 契约，
     * 两边必须一致，改一边就要改另一边：
     * 
     * <pre>
     * gtet_terminal: {
     *   plan: { groups: [ { key: "组键", candidates: ["物品id", ...] } ] }   // 上次 Shift+右键扫描的结果
     *   group_prefs: [ { group: "组键", item: "物品id" } ]                    // 玩家的选择
     *   ui_group: "组键"                                                     // 右下「勾选」块当前显示哪一组
     * }
     * </pre>
     * 
     * 之所以在这里手写一遍而不引用 GTET 的类：补丁 jar 由 GTMThings 自己 build，
     * 编译期不能依赖 GTET；反过来说 GTET 编译期也不能依赖本补丁新增的类。
     */
    private static final class TierGroups {

        private static final String ROOT = "gtet_terminal";
        private static final String PLAN = "plan";
        private static final String PLAN_GROUPS = "groups";
        private static final String GROUP_KEY = "key";
        private static final String GROUP_CANDIDATES = "candidates";
        private static final String PREFS = "group_prefs";
        private static final String PREF_KEY = "group";
        private static final String PREF_ITEM = "item";
        /**
         * 右下「勾选」块当前显示哪一组（右上点了哪一行）。
         *
         * <p>
         * ⚠️ 这份状态**必须进终端 NBT**，不能做成界面里的本地字段：LDLib 的界面是服务端与客户端
         * <b>各建一次</b>、按控件路径同步数据的，本地字段只有点的那一端会变（服务端那份永远停在第一组），
         * 两块面板就会各自显示不同的组；写 NBT 则天然是"服务端权威 + 随物品同步到客户端"，
         * 顺带还能跨界面开关保留、不同终端各自独立。
         */
        private static final String UI_GROUP = "ui_group";

        /** 物品 id → 图标 / 截断后的名字（每 tick 每行都会问一次，缓存一下省得反复建对象）。 */
        private static final Map<String, ItemStack> ICONS = new HashMap<>();
        private static final Map<String, String> NAMES = new HashMap<>();

        private record Group(String key, List<String> candidates) {}

        /** 上次扫描出来的分级组（只有多档的才算）。 */
        private static List<Group> read(ItemStack terminal) {
            List<Group> groups = new ArrayList<>();
            CompoundTag root = rootTag(terminal);
            if (root == null || !root.contains(PLAN, Tag.TAG_COMPOUND)) return groups;
            CompoundTag plan = root.getCompound(PLAN);
            if (!plan.contains(PLAN_GROUPS, Tag.TAG_LIST)) return groups;
            ListTag list = plan.getList(PLAN_GROUPS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                ListTag ids = entry.getList(GROUP_CANDIDATES, Tag.TAG_STRING);
                List<String> candidates = new ArrayList<>();
                for (int j = 0; j < ids.size(); j++) candidates.add(ids.getString(j));
                if (candidates.size() > 1) groups.add(new Group(entry.getString(GROUP_KEY), candidates));
            }
            return groups;
        }

        /**
         * 「右下块正在显示的那一组」在 {@code groups} 里的下标（两块联动的状态）。
         *
         * <p>
         * 读的是终端 NBT 的 {@link #UI_GROUP}；NBT 里的键在当前组列表里找不到时（旧存档、
         * 上次扫描出来的组被后来那次扫描覆盖掉、或者根本还没点过）→ 退回第一组，不报错。
         *
         * @return 组的下标；一组都没有时返回 {@code -1}
         */
        private static int activeIndex(ItemStack terminal, List<Group> groups) {
            if (groups.isEmpty()) return -1;
            CompoundTag root = rootTag(terminal);
            String wanted = root == null ? "" : root.getString(UI_GROUP);
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).key().equals(wanted)) return i;
            }
            return 0;
        }

        /** 这一组是不是"右下块正在显示的那一组"。 */
        private static boolean isActive(ItemStack terminal, List<Group> groups, Group group) {
            int active = activeIndex(terminal, groups);
            return active >= 0 && groups.get(active).key().equals(group.key());
        }

        /** 行首标记：当前组是 {@code ▶}、其余留同样宽度的空格（换组时名字列不会左右跳）。 */
        private static String marker(ItemStack terminal, List<Group> groups, Group group) {
            return isActive(terminal, groups, group) ? "▶ " : "  ";
        }

        /** 把某一组设为右下块显示的那一组。⚠️ 只在服务端调用（客户端改自己背包的 NBT 同步不回去）。 */
        private static void setActive(ItemStack terminal, String groupKey) {
            CompoundTag tag = terminal.getTag();
            if (tag == null) {
                tag = new CompoundTag();
                terminal.setTag(tag);
            }
            if (!tag.contains(ROOT, Tag.TAG_COMPOUND)) tag.put(ROOT, new CompoundTag());
            tag.getCompound(ROOT).putString(UI_GROUP, groupKey);
        }

        /** 该组当前选中的那一档（没选过就是第一档）。下面每 tick 都会被问到，所以不走 prefs() 建整张表。 */
        private static String chosen(ItemStack terminal, Group group) {
            CompoundTag root = rootTag(terminal);
            if (root != null && root.contains(PREFS, Tag.TAG_LIST)) {
                ListTag list = root.getList(PREFS, Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag entry = list.getCompound(i);
                    if (entry.getString(PREF_KEY).equals(group.key())) {
                        String wanted = entry.getString(PREF_ITEM);
                        if (group.candidates().contains(wanted)) return wanted;
                        break;
                    }
                }
            }
            return group.candidates().get(0);
        }

        /** 循环到下一档。 */
        private static void cycle(ItemStack terminal, Group group) {
            List<String> candidates = group.candidates();
            String next = candidates.get((candidates.indexOf(chosen(terminal, group)) + 1) % candidates.size());
            setPreference(terminal, group.key(), next);
        }

        private static Map<String, String> prefs(ItemStack terminal) {
            Map<String, String> prefs = new LinkedHashMap<>();
            CompoundTag root = rootTag(terminal);
            if (root == null || !root.contains(PREFS, Tag.TAG_LIST)) return prefs;
            ListTag list = root.getList(PREFS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                prefs.put(entry.getString(PREF_KEY), entry.getString(PREF_ITEM));
            }
            return prefs;
        }

        private static void setPreference(ItemStack terminal, String groupKey, String itemId) {
            Map<String, String> prefs = prefs(terminal);
            prefs.put(groupKey, itemId);
            ListTag list = new ListTag();
            prefs.forEach((key, value) -> {
                CompoundTag entry = new CompoundTag();
                entry.putString(PREF_KEY, key);
                entry.putString(PREF_ITEM, value);
                list.add(entry);
            });
            CompoundTag tag = terminal.getTag();
            if (tag == null) {
                tag = new CompoundTag();
                terminal.setTag(tag);
            }
            if (!tag.contains(ROOT, Tag.TAG_COMPOUND)) tag.put(ROOT, new CompoundTag());
            tag.getCompound(ROOT).put(PREFS, list);
        }

        private static CompoundTag rootTag(ItemStack terminal) {
            CompoundTag tag = terminal.getTag();
            return tag != null && tag.contains(ROOT, Tag.TAG_COMPOUND) ? tag.getCompound(ROOT) : null;
        }

        private static ItemStack icon(String itemId) {
            return ICONS.computeIfAbsent(itemId, id -> {
                var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
                return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
            });
        }

        /** 方块名（列表很窄，超长就截断；完整名字在图标 tooltip 里）。 */
        private static String name(String itemId) {
            return NAMES.computeIfAbsent(itemId, id -> {
                ItemStack stack = icon(id);
                String name = stack.isEmpty() ? id : stack.getHoverName().getString();
                return name.length() > NAME_MAX_CHARS ? name.substring(0, NAME_MAX_CHARS) + "…" : name;
            });
        }
    }

    @Setter
    @Getter
    public static class AutoBuildSetting {

        private int coilTier, repeatCount, noHatchMode, replaceCoilMode, isUseAE, module, isFlip, demolition;

        public AutoBuildSetting() {
            this.coilTier = 0;
            this.repeatCount = 0;
            this.noHatchMode = 1;
            this.replaceCoilMode = 0;
            this.isUseAE = 0;
            this.module = 0;
            this.isFlip = 0;
            this.demolition = 0;
        }

        public List<ItemStack> apply(BlockInfo[] blockInfos) {
            List<ItemStack> candidates = new ArrayList<>();
            if (blockInfos != null) {
                if (Arrays.stream(blockInfos).anyMatch(
                        info -> info.getBlockState().getBlock() instanceof CoilBlock)) {
                    var tier = Math.min(coilTier - 1, blockInfos.length - 1);
                    if (tier == -1) {
                        for (int i = 0; i < blockInfos.length - 1; i++) {
                            candidates.add(blockInfos[i].getItemStackForm());
                        }
                    } else {
                        candidates.add(blockInfos[tier].getItemStackForm());
                    }
                    return candidates;
                }
                for (BlockInfo info : blockInfos) {
                    if (info.getBlockState().getBlock() != Blocks.AIR) candidates.add(info.getItemStackForm());
                }
            }
            return candidates;
        }

        public boolean isPlaceHatch(BlockInfo[] blockInfos) {
            if (this.noHatchMode == 0) return true;
            if (blockInfos != null && blockInfos.length > 0) {
                var blockInfo = blockInfos[0];
                return !(blockInfo.getBlockState().getBlock() instanceof MetaMachineBlock machineBlock) || !Hatch.Set.contains(machineBlock);
            }
            return true;
        }

        public boolean isReplaceCoilMode() {
            return replaceCoilMode == 1;
        }

        /** 镜像搭建（GTO：{@code autoBuildSetting.isFlip == 1}）。 */
        public boolean isFlipMode() {
            return isFlip == 1;
        }

        /**
         * 拆除模式（GTO：{@code autoBuildSetting.demolition == 1}）。
         *
         * <p>
         * 开启后 {@code autoBuild} 不再放置任何方块，而是把结构位置上「本来就该是结构方块」的方块拆掉，
         * 见 {@code AdvancedBlockPattern#autoBuild} 里的拆除分支。
         */
        public boolean isDemolitionMode() {
            return demolition == 1;
        }
    }
}
