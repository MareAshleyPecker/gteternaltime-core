package rain.gtetcore.gtet.common.item.tool;

import com.gregtechceu.gtceu.api.item.component.IInteractionItem;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import rain.gtetcore.gtet.config.GTETConfig;
import rain.gtetcore.gtet.Gtetcore;
import rain.gtetcore.gtet.common.item.tool.StructureDetectBehavior;
import rain.gtetcore.gtet.common.item.tool.StructureWriteBehavior;
import rain.gtetcore.gtet.common.item.tool.TerminalBehavior;
import rain.gtetcore.gtet.util.lang.LangUtil;

/**
 * 结构工具（合并版）—— 一个物品承载四种工作模式，逻辑全部复用原有的四个实现。
 *
 * <p>模式：{@link WorkMode#EXPORT 选区导出} → {@link WorkMode#RECHECK 结构重检}
 * → {@link WorkMode#DETECT 结构检测} → {@link WorkMode#TERMINAL 高级终端}。
 *
 * <p>切换方式：<b>对着空气按住 Shift 滚轮</b>（客户端判定 + 网络包同步）；
 * 也可在界面里点「切换模式」。导出模式受配置 {@code tools.exportModeEnabled} 控制，
 * 关掉后循环会自动跳过。
 *
 * @author rain fox
 */
public class StructureToolBehavior implements IInteractionItem, IItemUIFactory {

    public static final StructureToolBehavior INSTANCE = new StructureToolBehavior();

    private static final String MODE_KEY = "work_mode";

    private static final String L_MODE = "gtetcore.tool.mode";
    private static final String L_MODE_SWITCH = "gtetcore.tool.mode_switch";
    private static final String L_MODE_HINT = "gtetcore.tool.mode_hint";

    static {
        LangUtil.add(L_MODE, "Mode: %s", "当前模式：%s");
        LangUtil.add(L_MODE_SWITCH, "Switch mode", "切换模式");
        LangUtil.add(L_MODE_HINT, "Aim at air + Shift + scroll to switch", "对着空气 Shift+滚轮 切换");
        LangUtil.add(WorkMode.EXPORT.langKey, "Area export", "选区导出");
        LangUtil.add(WorkMode.RECHECK.langKey, "Structure recheck", "结构重检");
        LangUtil.add(WorkMode.DETECT.langKey, "Structure detect", "结构检测");
    }

    protected StructureToolBehavior() {}

    /** 工作模式。 */
    public enum WorkMode {
        EXPORT("gtetcore.tool.mode.export"),
        RECHECK("gtetcore.tool.mode.recheck"),
        DETECT("gtetcore.tool.mode.detect");

        public final String langKey;

        WorkMode(String langKey) {
            this.langKey = langKey;
        }

        public Component displayName() {
            return Component.translatable(langKey);
        }

        /** 下一个可用模式（跳过被配置关掉的导出模式）。 */
        public WorkMode next() {
            WorkMode[] values = values();
            WorkMode candidate = values[(ordinal() + 1) % values.length];
            int guard = 0;
            while (candidate == EXPORT && !GTETConfig.exportModeEnabled() && guard++ < values.length) {
                candidate = values[(candidate.ordinal() + 1) % values.length];
            }
            return candidate;
        }

        /** 上一个可用模式。 */
        public WorkMode previous() {
            WorkMode[] values = values();
            WorkMode candidate = values[(ordinal() - 1 + values.length) % values.length];
            int guard = 0;
            while (candidate == EXPORT && !GTETConfig.exportModeEnabled() && guard++ < values.length) {
                candidate = values[(candidate.ordinal() - 1 + values.length) % values.length];
            }
            return candidate;
        }
    }

    // ======================== 模式存取 ========================

    public static WorkMode getMode(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(MODE_KEY)) return WorkMode.EXPORT;
        try {
            return WorkMode.valueOf(tag.getString(MODE_KEY));
        } catch (IllegalArgumentException e) {
            return WorkMode.EXPORT;
        }
    }

    public static void setMode(ItemStack stack, WorkMode mode) {
        stack.getOrCreateTag().putString(MODE_KEY, mode.name());
    }

    /** 切到下一个模式；返回切换后的模式。 */
    public static WorkMode cycleMode(ItemStack stack) {
        WorkMode next = getMode(stack).next();
        setMode(stack, next);
        return next;
    }

    /** 按方向切模式：direction >= 0 向后，< 0 向前。 */
    public static WorkMode cycleMode(ItemStack stack, int direction) {
        WorkMode mode = getMode(stack);
        WorkMode next = direction >= 0 ? mode.next() : mode.previous();
        setMode(stack, next);
        return next;
    }

    /** 是不是合并版结构工具（用注册名判定，避免和旧工具混淆）。 */
    public static boolean isStructureTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && Gtetcore.MODID.equals(key.getNamespace()) && "structure_tool".equals(key.getPath());
    }

    /** 当前模式是否可用（导出模式可能被配置关掉）。 */
    public static WorkMode sanitize(ItemStack stack) {
        WorkMode mode = getMode(stack);
        if (mode == WorkMode.EXPORT && !GTETConfig.exportModeEnabled()) return WorkMode.RECHECK;
        return mode;
    }

    // ======================== 分流 ========================

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return switch (sanitize(stack)) {
            case EXPORT -> StructureWriteBehavior.INSTANCE.onItemUseFirst(stack, context);
            case RECHECK -> TerminalBehavior.INSTANCE.onItemUseFirst(stack, context);
            case DETECT -> StructureDetectBehavior.INSTANCE.onItemUseFirst(stack, context);
        };
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return switch (sanitize(stack)) {
            case EXPORT -> StructureWriteBehavior.INSTANCE.use(item, level, player, hand);
            // 重检 / 检测靠 Shift+右键方块触发，空手右键不做事
            default -> InteractionResultHolder.pass(stack);
        };
    }

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        ItemStack stack = holder.getHeld();
        return switch (sanitize(stack)) {
            case EXPORT -> StructureWriteBehavior.INSTANCE.createUI(holder, player);
            default -> modePanel(holder, stack);
        };
    }

    /** 重检 / 检测模式的简易面板：显示当前模式 + 切换按钮。 */
    private static ModularUI modePanel(HeldItemUIFactory.HeldItemHolder holder, ItemStack stack) {
        var label = new LabelWidget(8, 8, () -> Component.translatable(L_MODE,
                sanitize(stack).displayName().getString()).getString());
        label.setColor(0xFAF9F6);

        var hint = new LabelWidget(8, 22, () -> Component.translatable(L_MODE_HINT).getString());
        hint.setColor(0x808080);

        return new ModularUI(176, 100, holder, holder.getPlayer())
                .background(com.gregtechceu.gtceu.api.gui.GuiTextures.BACKGROUND)
                .widget(label)
                .widget(hint)
                .widget(new ButtonWidget(8, 42, 160, 18,
                        new GuiTextureGroup(com.gregtechceu.gtceu.api.gui.GuiTextures.BUTTON,
                                new TextTexture(Component.translatable(L_MODE_SWITCH).getString())),
                        clickData -> cycleMode(stack)));
    }

    /** 滚轮切换后给玩家的提示。 */
    public static void notifyMode(Player player, WorkMode mode) {
        player.displayClientMessage(Component.translatable(L_MODE, mode.displayName().getString())
                .withStyle(ChatFormatting.AQUA), true);
    }
}
