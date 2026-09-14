package rain.gtetcore.gtet.integration.ae2;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.gregtechceu.gtceu.api.gui.widget.PhantomFluidWidget;
import com.gregtechceu.gtceu.api.gui.widget.PhantomSlotWidget;
import com.gregtechceu.gtceu.api.transfer.fluid.CustomFluidTank;
import com.gregtechceu.gtceu.api.transfer.item.CustomItemStackHandler;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;

import java.util.function.Consumer;

/**
 * 「标签过滤 + 定量拉取」面板：白名单输入框、黑名单输入框各配一个**幻影槽**，再加一个「每次拉 N 个」的数字框。
 *
 * <h2>三行控件</h2>
 * <ol>
 * <li><b>白名单</b>：输入框（留空 = 不限制）+ 幻影槽；</li>
 * <li><b>黑名单</b>：输入框（留空 = 不限制）+ 幻影槽；</li>
 * <li><b>每次拉取量 N</b>：数字框，范围 0 ~ {@code BATCH_MAX}，<b>0 = 不限制</b>（默认，行为与 GTM 原版库存部件一致）。
 * N 必须 ≥ 本仓的「保底数量」（GTM 自带的 {@code min_item_count} / {@code min_fluid_count}），否则备出来的量
 * 永远达不到保底，本仓会一直空着 —— 这是定量模式与保底语义的固有冲突，提示行里写明了。</li>
 * </ol>
 *
 * <h2>幻影槽怎么工作</h2>
 * 往幻影槽里放一个物品（流体部件则是流体，可以从 JEI/EMI 拖，也可以点一下手持的桶）之后，
 * 取该样本的全部标签、按字母序用 {@code |} 连接写进本行的表达式 —— 于是「命中其中任意一个标签」的都放行，
 * 这正是「先拉一类东西」的起点。玩家随后可以随便改这条表达式（删掉多余的标签、换成 {@code &} / {@code !}）。
 * <ul>
 * <li>物品幻影槽：<b>右键清空样本</b>（{@code setClearSlotOnRightClick(true)}）；</li>
 * <li>流体幻影槽：<b>空手右键清空</b>（{@code PhantomFluidWidget} 的点击语义就是「把手上那份量放进 / 放出」）；</li>
 * <li>⚠️ 样本**一个标签都没有**时表达式保持不变（不清空、不报错）—— 免得把玩家手写的一大串表达式顺手抹掉。</li>
 * </ul>
 *
 * <h2>为什么不是 GTOCore 那种「标签列表可点」的面板</h2>
 * GTOCore（LGPL-3.0）的 {@code ITagFilterPartMachine.FilterIFancyConfigurator} 底下会摊开一个可点击的标签列表
 * （左键把该标签写进输入框、右键复制到剪贴板），靠的是它自己 GTM 分叉里的
 * {@code TagFilter.StackHandlerWidget} / {@code TagItemFilter.PhantomSlot} / {@code TagFluidFilter.TankSlot}
 * —— <b>这三个类在官方 GTM 7.5.3 里不存在</b>（本文件用到的 {@code TagItemFilter} / {@code TagFluidFilter}
 * 只有 {@code loadFilter} 与 {@code test}，没有任何嵌套 Widget）。官方件上要做同样的交互，
 * 得自己写一个「点击后 {@code writeClientAction} / 服务端 {@code handleClientAction}」的自定义 Widget，
 * 而 GTET 这边**用幻影槽直接把表达式填出来**已经满足需求，就不再多背一个自定义同步 Widget 的风险。
 *
 * <h2>输入框的同步</h2>
 * 走 LDLib {@code TextFieldWidget} 自带的那套：构造时给 (读 supplier, 写 consumer) 一对，
 * 服务端侧 {@code detectAndSendChanges} 把值 push 给客户端显示、客户端输入通过 client action 回到服务端。
 * 所以幻影槽在服务端改了表达式之后，输入框下一 tick 自己就会刷新，不需要我们手动同步。
 *
 * @author rain fox
 */
public class ETTagFilterConfigurator implements IFancyConfigurator {

    /** 面板标题键（中英在注册文件里用 {@code LangUtil.add} 登记）。 */
    public static final String LANG_TITLE = "gtetcore.machine.et_tag_filter.title";
    /** 白名单行标题。 */
    public static final String LANG_WHITE = "gtetcore.machine.et_tag_filter.white";
    /** 黑名单行标题。 */
    public static final String LANG_BLACK = "gtetcore.machine.et_tag_filter.black";
    /** 定量模式行标题。 */
    public static final String LANG_BATCH = "gtetcore.machine.et_tag_filter.batch";
    /** 说明行 0：运算符。 */
    public static final String LANG_HINT_0 = "gtetcore.machine.et_tag_filter.hint.0";
    /** 说明行 1：{@code ,} 与 {@code #} 的便利写法。 */
    public static final String LANG_HINT_1 = "gtetcore.machine.et_tag_filter.hint.1";
    /** 说明行 2：幻影槽用法。 */
    public static final String LANG_HINT_2 = "gtetcore.machine.et_tag_filter.hint.2";
    /** 说明行 3：留空语义与定量/保底的冲突。 */
    public static final String LANG_HINT_3 = "gtetcore.machine.et_tag_filter.hint.3";

    /** 面板尺寸（与 GTOCore 的面板同宽，别再加宽：fancy 侧栏放不下）。 */
    private static final int PANEL_WIDTH = 150;
    private static final int PANEL_HEIGHT = 150;
    /** 输入框宽度：右边要留出 18px 的幻影槽。 */
    private static final int FIELD_WIDTH = 112;
    /** 表达式最长字符数，够自动填 16 个标签。 */
    private static final int MAX_EXPRESSION_LENGTH = 512;

    private final IMEStockingHost machine;
    /** true = 流体部件（幻影槽收流体、N 的单位是 mB），false = 物品部件。 */
    private final boolean fluid;

    public ETTagFilterConfigurator(IMEStockingHost machine, boolean fluid) {
        this.machine = machine;
        this.fluid = fluid;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(LANG_TITLE);
    }

    /** 图标直接借 GTM 自带的黑白名单按钮贴图。 */
    @Override
    public IGuiTexture getIcon() {
        return GuiTextures.BUTTON_BLACKLIST.getSubTexture(0, 0, 1, 0.5);
    }

    @Override
    public Widget createConfigurator() {
        WidgetGroup group = new WidgetGroup(0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        // 白名单行
        group.addWidget(new LabelWidget(4, 2, LANG_WHITE));
        group.addWidget(new TextFieldWidget(4, 14, FIELD_WIDTH, 16, machine::getTagWhite, machine::setTagWhite)
                .setMaxStringLength(MAX_EXPRESSION_LENGTH));
        group.addWidget(createPhantom(120, 14, machine::setTagWhite));

        // 黑名单行
        group.addWidget(new LabelWidget(4, 38, LANG_BLACK));
        group.addWidget(new TextFieldWidget(4, 50, FIELD_WIDTH, 16, machine::getTagBlack, machine::setTagBlack)
                .setMaxStringLength(MAX_EXPRESSION_LENGTH));
        group.addWidget(createPhantom(120, 50, machine::setTagBlack));

        // 定量模式行：0 = 不限制
        group.addWidget(new LabelWidget(4, 74, LANG_BATCH));
        group.addWidget(new IntInputWidget(4, 86, FIELD_WIDTH, 16, machine::getBatchSize, machine::setBatchSize)
                .setMin(BATCH_MIN)
                .setMax(BATCH_MAX));

        // 说明
        group.addWidget(new LabelWidget(4, 108, LANG_HINT_0));
        group.addWidget(new LabelWidget(4, 118, LANG_HINT_1));
        group.addWidget(new LabelWidget(4, 128, LANG_HINT_2));
        group.addWidget(new LabelWidget(4, 138, LANG_HINT_3));

        return group;
    }

    /** 按部件类型造物品版或流体版幻影槽。 */
    private Widget createPhantom(int x, int y, Consumer<String> expressionSink) {
        return fluid ? createFluidPhantom(x, y, expressionSink) : createItemPhantom(x, y, expressionSink);
    }

    /**
     * 物品幻影槽。
     *
     * ⚠️ 反馈时机：{@code setChangeListener} 是 GTM 自己 {@code SlotWidget} 里的钩子
     * （GTM 的 {@code SimpleItemFilter} 就是用它的），槽内容真的变了才回调，不是每 tick 都跑。
     */
    private Widget createItemPhantom(int x, int y, Consumer<String> expressionSink) {
        CustomItemStackHandler handler = new CustomItemStackHandler(1);
        PhantomSlotWidget slot = new PhantomSlotWidget(handler, 0, x, y);
        slot.setClearSlotOnRightClick(true);
        slot.setBackground(GuiTextures.SLOT);
        slot.setChangeListener(() -> {
            ItemStack stack = handler.getStackInSlot(0);
            if (stack.isEmpty()) return;
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return;
            String expression = ETTagFilter.expressionOf(key);
            // 样本没有标签 → 保持原表达式不动
            if (!expression.isEmpty()) expressionSink.accept(expression);
        });
        return slot;
    }

    /**
     * 流体幻影槽。
     *
     * ⚠️ 反馈时机用 {@code CustomFluidTank#setOnContentsChanged}：{@code PhantomFluidWidget} 把「拖进来 / 点一下」
     * 的结果都汇到 {@code phantomFluidSetter} → {@code CustomFluidTank#setFluid}，而 GTM 的
     * {@code CustomFluidTank} 覆写了 {@code setFluid} 并在末尾触发 {@code onContentsChanged}
     * （已核实源码，不是靠基类的行为）。
     */
    private Widget createFluidPhantom(int x, int y, Consumer<String> expressionSink) {
        CustomFluidTank tank = new CustomFluidTank(FluidType.BUCKET_VOLUME);
        tank.setOnContentsChanged(() -> {
            FluidStack stack = tank.getFluid();
            if (stack.isEmpty()) return;
            AEFluidKey key = AEFluidKey.of(stack);
            if (key == null) return;
            String expression = ETTagFilter.expressionOf(key);
            if (!expression.isEmpty()) expressionSink.accept(expression);
        });
        return new PhantomFluidWidget(tank, 0, x, y, 18, 18, tank::getFluid, tank::setFluid);
    }

    /** 数字框上下限与部件侧的夹取保持一致（部件那边还会再夹一次，这里只影响 UI）。 */
    private static final int BATCH_MIN = 0;
    private static final int BATCH_MAX = 1_000_000;
}
