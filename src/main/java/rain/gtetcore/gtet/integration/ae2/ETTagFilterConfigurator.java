package rain.gtetcore.gtet.integration.ae2;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.widget.PhantomFluidWidget;
import com.gregtechceu.gtceu.api.gui.widget.PhantomSlotWidget;
import com.gregtechceu.gtceu.api.gui.widget.ToggleButtonWidget;
import com.gregtechceu.gtceu.api.transfer.fluid.CustomFluidTank;
import com.gregtechceu.gtceu.api.transfer.item.CustomItemStackHandler;

import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
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
 * 「标签过滤 + 定量拉取 + 多方块共享」面板：白名单输入框、黑名单输入框各配一个**幻影槽**，
 * 一个「每次拉 N 个」的数字框，再加一个「能不能被别的多方块共享」的开关。
 *
 * <h2>四行控件</h2>
 * <ol>
 * <li><b>白名单</b>：输入框（留空 = 不限制）+ 幻影槽；</li>
 * <li><b>黑名单</b>：输入框（留空 = 不限制）+ 幻影槽；</li>
 * <li><b>每次拉取量 N</b>：数字框在最左，两个 <b>-/+ 按钮在右侧</b>（与上面两行的幻影槽同一列），
 * 范围 0 ~ {@code BATCH_MAX}，<b>0 = 不限制</b>（默认，行为与 GTM 原版库存部件一致）。
 * N 必须 ≥ 本仓的「保底数量」（GTM 自带的 {@code min_item_count} / {@code min_fluid_count}），否则备出来的量
 * 永远达不到保底，本仓会一直空着 —— 这是定量模式与保底语义的固有冲突，提示行里写明了。</li>
 * <li><b>多方块共享开关</b>（{@link #Y_SHARE_ROW}）：复用 GTM 的 {@code ToggleButtonWidget}，
 * 右侧一行短状态文字。<b>默认关 = 隔离</b>（防串配方）；两个方向分别发生什么、
 * 以及「改动在结构重新检查后生效」全部写在这个开关的 tooltip 里（4 条，中英各一份，见
 * {@link #LANG_SHARE_TIP_0} ~ {@link #LANG_SHARE_TIP_3}）。
 * ⚠️ 一台机器只有**一个**开关：二合一件的流体侧那块面板不画这一行（{@code showShareSwitch=false}）。</li>
 * </ol>
 *
 * <h2>⚠️ 这一行为什么不用 GTM 的 {@code IntInputWidget}</h2>
 * {@code IntInputWidget} 继承的 {@code NumberInputWidget#buildUI()} 是 <b>private</b>，按钮位置与高度写死在里面：
 * 「-」按钮贴左边（x=0）、输入框居中、「+」按钮贴右边，而且两个按钮与输入框的高度硬编码 {@code 20}
 * —— <b>不管构造时传多高</b>。于是它必然把按钮摊在输入框两侧，并且比声明的框高出 4px、压到下一块内容上；
 * 子类无法重排。需求是「按钮挪到右边」，所以这里自己拼一行（输入框 + 两个按钮，全部 18 高、与框一致），
 * 步进手感沿用 GTM（1 / Shift 8 / Ctrl 64 / 两键 512），tooltip 直接借 GTM 自带的那条说明键。
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
    /** 多方块共享开关行标题。 */
    public static final String LANG_SHARE = "gtetcore.machine.et_tag_filter.share";
    /** 共享开关的「开」状态文字（紧跟开关右侧）。 */
    public static final String LANG_SHARE_ON = "gtetcore.machine.et_tag_filter.share.on";
    /** 共享开关的「关」状态文字。 */
    public static final String LANG_SHARE_OFF = "gtetcore.machine.et_tag_filter.share.off";
    /** 开关悬停说明 0：这个开关管什么。 */
    public static final String LANG_SHARE_TIP_0 = "gtetcore.machine.et_tag_filter.share.tip.0";
    /** 开关悬停说明 1：关（隔离）方向。 */
    public static final String LANG_SHARE_TIP_1 = "gtetcore.machine.et_tag_filter.share.tip.1";
    /** 开关悬停说明 2：开（允许共享）方向。 */
    public static final String LANG_SHARE_TIP_2 = "gtetcore.machine.et_tag_filter.share.tip.2";
    /** 开关悬停说明 3：时序（什么时候生效）。 */
    public static final String LANG_SHARE_TIP_3 = "gtetcore.machine.et_tag_filter.share.tip.3";
    /** 说明行 0：运算符。 */
    public static final String LANG_HINT_0 = "gtetcore.machine.et_tag_filter.hint.0";
    /** 说明行 1：{@code ,} 与 {@code #} 的便利写法。 */
    public static final String LANG_HINT_1 = "gtetcore.machine.et_tag_filter.hint.1";
    /** 说明行 2：幻影槽用法。 */
    public static final String LANG_HINT_2 = "gtetcore.machine.et_tag_filter.hint.2";
    /** 说明行 3：留空语义与定量/保底的冲突。 */
    public static final String LANG_HINT_3 = "gtetcore.machine.et_tag_filter.hint.3";

    /**
     * 面板尺寸。
     *
     * <p>
     * ⚠️ 宽度 150 是上限，别再加：fancy 侧栏展开的浮层是「内容宽 + 8」，而浮层是从主界面左边缘往右画的，
     * 再宽就会盖住主界面的配置槽区。
     *
     * <p>
     * ⚠️ 高度必须**等于内容底边**（带开关行时最后一行说明的底边正好是 186；不带开关行时是 150）。
     * fancy 浮层的高度是「内容高 + 24（图标行）+ 4」，多留的空白会变成浮层底部的一大块空区，少留则内容越界。
     * ⚠️ 高度是**往下长**的（浮层顶边 = 图标行处，见 {@code ConfiguratorPanel.Tab#expand}），
     * 所以加一行只会让浮层底部更靠下，不会压到别的东西；但超过屏幕高度时 GTM 会把浮层整体上移。
     */
    private static final int PANEL_WIDTH = 150;
    /** 带「多方块共享」开关行时的面板高 = 末行说明底边 176 + 10。 */
    private static final int PANEL_HEIGHT = 186;
    /** 不带开关行时（二合一件的流体侧那块）的面板高 = 末行说明底边 140 + 10，与原布局一致。 */
    private static final int PANEL_HEIGHT_NO_SHARE = 150;

    // 各行控件的 Y 与尺寸 —— 改布局只动这里，别在 addWidget 里散落魔数
    /** 白名单：标题 / 输入框（输入框右侧留 18px 给幻影槽）。 */
    private static final int Y_WHITE_LABEL = 2;
    private static final int Y_WHITE_FIELD = 14;
    /** 黑名单。 */
    private static final int Y_BLACK_LABEL = 38;
    private static final int Y_BLACK_FIELD = 50;
    /** 定量行：标题在 74，数字框与 -/+ 按钮同在 86。 */
    private static final int Y_BATCH_LABEL = 74;
    private static final int Y_BATCH_ROW = 86;
    /**
     * 多方块共享行：标题在 110，开关与状态文字同在 122（高 18，与上面几行的控件同高）。
     *
     * <p>
     * ⚠️ 这一行是**新加的一整行**（原布局里定量行的底边 104 之后直接是说明块 110）：
     * 加行就要把说明块整体下移 36px（见 {@link #Y_HINT}）并把面板高同步加上，
     * 否则说明块会与开关行压在一起（本面板上一版踩过的坑正是「控件实际高度比声明的高 4px → 叠字」）。
     */
    private static final int Y_SHARE_LABEL = 110;
    private static final int Y_SHARE_ROW = 122;
    /** 说明块首行 Y（带开关行时）与行距（4 行，末行底边 176 + 10 = 186 = 面板高）。 */
    private static final int Y_HINT = 146;
    /** 不带开关行时的说明块首行 Y（= 原布局的 110，末行底边 140 + 10 = 150 = 面板高）。 */
    private static final int Y_HINT_NO_SHARE = 110;
    private static final int HINT_LINE_HEIGHT = 10;

    /** 表达式输入框宽度：右边要留出 18px 的幻影槽。 */
    private static final int FIELD_WIDTH = 112;
    /** 第三行数字框宽度：右边要让出两个 18px 的按钮。 */
    private static final int BATCH_FIELD_WIDTH = 88;
    /** 幻影槽所在列（第三行「+」按钮与之同列，视觉上成一列）。 */
    private static final int PHANTOM_X = 120;
    /** 第三行「-」按钮的 X（紧接数字框右侧）。 */
    private static final int BATCH_MINUS_X = 98;
    /** 第三行与共享行控件统一高 18，与幻影槽同高。 */
    private static final int ROW_HEIGHT = 18;
    /** 共享开关的 X（与上面几行控件左对齐）。 */
    private static final int SHARE_TOGGLE_X = 4;
    /** 共享开关右侧状态文字的 X（开关 18 宽 + 4px 间隙）。 */
    private static final int SHARE_LABEL_X = 26;
    /** 表达式最长字符数，够自动填 16 个标签。 */
    private static final int MAX_EXPRESSION_LENGTH = 512;
    /** 数字框最长字符数：{@code BATCH_MAX} = 1000000 是 7 位。 */
    private static final int MAX_BATCH_LENGTH = 7;

    /** 步进按钮的悬停说明：直接用 GTM 自带的那条（中英都已有，不必新登记语言键）。 */
    private static final String TOOLTIP_STEP = "gui.widget.incrementButton.default_tooltip";

    private final IMEStockingHost machine;
    /** true = 流体部件（幻影槽收流体、N 的单位是 mB），false = 物品部件。 */
    private final boolean fluid;
    /**
     * 是否画出「多方块共享」开关行。
     *
     * <p>
     * ⚠️ 只有二合一件的流体侧那块面板会传 false（一台机器有两块面板、但只有一个开关，
     * 画两次会让玩家以为要拨两次）。不带这一行时说明块上移、面板高也同步缩短
     * （见 {@link #hintY()} / {@link #panelHeight()}），不留空尾。
     */
    private final boolean showShareSwitch;

    /** 物品 / 流体两件单独部件用的构造器：带开关行（每件一个开关）。 */
    public ETTagFilterConfigurator(IMEStockingHost machine, boolean fluid) {
        this(machine, fluid, true);
    }

    public ETTagFilterConfigurator(IMEStockingHost machine, boolean fluid, boolean showShareSwitch) {
        this.machine = machine;
        this.fluid = fluid;
        this.showShareSwitch = showShareSwitch;
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
        WidgetGroup group = new WidgetGroup(0, 0, PANEL_WIDTH, panelHeight());
        int hintY = hintY();

        // 白名单行
        group.addWidget(new LabelWidget(4, Y_WHITE_LABEL, LANG_WHITE));
        group.addWidget(
                new TextFieldWidget(4, Y_WHITE_FIELD, FIELD_WIDTH, 16, machine::getTagWhite, machine::setTagWhite)
                        .setMaxStringLength(MAX_EXPRESSION_LENGTH));
        group.addWidget(createPhantom(PHANTOM_X, Y_WHITE_FIELD, machine::setTagWhite));

        // 黑名单行
        group.addWidget(new LabelWidget(4, Y_BLACK_LABEL, LANG_BLACK));
        group.addWidget(
                new TextFieldWidget(4, Y_BLACK_FIELD, FIELD_WIDTH, 16, machine::getTagBlack, machine::setTagBlack)
                        .setMaxStringLength(MAX_EXPRESSION_LENGTH));
        group.addWidget(createPhantom(PHANTOM_X, Y_BLACK_FIELD, machine::setTagBlack));

        // 定量模式行：0 = 不限制。数字框在左，-/+ 按钮挪到右侧（与上面两行的幻影槽同一列）
        group.addWidget(new LabelWidget(4, Y_BATCH_LABEL, LANG_BATCH));
        group.addWidget(
                new TextFieldWidget(4, Y_BATCH_ROW, BATCH_FIELD_WIDTH, ROW_HEIGHT,
                        () -> Integer.toString(machine.getBatchSize()), this::setBatchSizeFromText)
                        .setNumbersOnly(BATCH_MIN, BATCH_MAX)
                        .setMaxStringLength(MAX_BATCH_LENGTH));
        group.addWidget(createStepButton(BATCH_MINUS_X, "-", -1));
        group.addWidget(createStepButton(PHANTOM_X, "+", 1));

        // 多方块共享行（二合一件的流体侧那块面板不画这一行）
        if (showShareSwitch) {
            group.addWidget(new LabelWidget(4, Y_SHARE_LABEL, LANG_SHARE));
            group.addWidget(createShareToggle());
            group.addWidget(createShareStateLabel());
        }

        // 说明
        group.addWidget(new LabelWidget(4, hintY, LANG_HINT_0));
        group.addWidget(new LabelWidget(4, hintY + HINT_LINE_HEIGHT, LANG_HINT_1));
        group.addWidget(new LabelWidget(4, hintY + HINT_LINE_HEIGHT * 2, LANG_HINT_2));
        group.addWidget(new LabelWidget(4, hintY + HINT_LINE_HEIGHT * 3, LANG_HINT_3));

        return group;
    }

    /** 说明块首行 Y：带开关行时是 {@link #Y_HINT}，不带时是 {@link #Y_HINT_NO_SHARE}。 */
    private int hintY() {
        return showShareSwitch ? Y_HINT : Y_HINT_NO_SHARE;
    }

    /**
     * 面板高：必须等于内容底边 —— 即「说明块首行 Y + 4 行 × 行距」，
     * 也就是 {@link #Y_HINT} + 40 = 186 与 {@link #Y_HINT_NO_SHARE} + 40 = 150，
     * 与 {@link #PANEL_HEIGHT} / {@link #PANEL_HEIGHT_NO_SHARE} 一一对应（改布局时两边要一起改）。
     */
    private int panelHeight() {
        return showShareSwitch ? PANEL_HEIGHT : PANEL_HEIGHT_NO_SHARE;
    }

    /**
     * 「能不能被别的多方块共享」开关：直接用 GTM 自己的 {@link ToggleButtonWidget}
     * （{@code SwitchWidget} 的子类），贴图借 GTM 的 `button_public_private.png`。
     *
     * <h2>为什么用 {@code BUTTON_PUBLIC_PRIVATE}、为什么不用 {@code setShouldUseBaseBackground()}</h2>
     * 那张 png 是 **18×36 = 上下两半各 18×18**（已核实文件尺寸），正好是 {@code ToggleButtonWidget}
     * 认的「未按下 / 已按下」两张贴图 —— 于是图标本身随状态变化（与 GTM 的
     * {@code SimpleItemFilter} 用 {@code BUTTON_BLACKLIST} 的用法一致）。
     * 刻意**不**加 {@code setShouldUseBaseBackground()}：那个会把「整张」png（两半叠在一起）
     * 再套一层底板画出来，图标会被压扁。上半 = 未按下（= 默认的「隔离」），下半 = 已按下（= 允许共享）。
     *
     * <h2>为什么放在这一行、这个尺寸</h2>
     * <ul>
     * <li>X = {@link #SHARE_TOGGLE_X}（4）：与上面三行的控件左对齐，成一条竖线；</li>
     * <li>Y = {@link #Y_SHARE_ROW}（122）：紧接定量行（底边 104）下方，自己在标题 110 之下；</li>
     * <li>18×18：与幻影槽 / 步进按钮同高（{@link #ROW_HEIGHT}），不会比声明的框高出几个像素而压到下一行。</li>
     * </ul>
     *
     * <h2>⚠️ 同步与方向</h2>
     * {@code SwitchWidget} 的点击是「客户端发 client action → 服务端 {@code handleClientAction} 回调」，
     * 所以 {@link IMEStockingHost#setCanBeShared(boolean)} 是在**服务端**调的（正确：该值服务端权威）；
     * 开关自身的按下状态由它自己的 {@code supplier} + {@code updateScreen()} 每 tick 拉回来。
     */
    private Widget createShareToggle() {
        return new ToggleButtonWidget(SHARE_TOGGLE_X, Y_SHARE_ROW, ROW_HEIGHT, ROW_HEIGHT,
                GuiTextures.BUTTON_PUBLIC_PRIVATE, machine::canBeShared, machine::setCanBeShared)
                .setHoverTooltips(Component.translatable(LANG_SHARE_TIP_0),
                        Component.translatable(LANG_SHARE_TIP_1),
                        Component.translatable(LANG_SHARE_TIP_2),
                        Component.translatable(LANG_SHARE_TIP_3));
    }

    /**
     * 开关右侧的状态文字：用「(读 supplier)」的 {@link LabelWidget}，值变了由 LDLib 自己推给客户端
     * （{@code LabelWidget#detectAndSendChanges} 比对字符串后 {@code writeUpdateInfo}），不必手动同步。
     *
     * <p>
     * ⚠️ supplier 返回的是**语言键本身**（不是 {@code Component.translatable(...).getString()}）：
     * {@code LabelWidget} 画的时候会自己过一遍 {@code LocalizationUtils.format}，
     * 与 GTM 自己写 {@code () -> isOnline ? "gtceu.gui.me_network.online" : ...} 的用法一致。
     *
     * <p>
     * ⚠️ 文案必须短：LDLib 的 {@code LabelWidget} 不换行，英文一旦写长（例如
     * "Allowed (click to isolate)"）就会画出面板右边缘。说明写在 tooltip 里。
     */
    private Widget createShareStateLabel() {
        return new LabelWidget(SHARE_LABEL_X, Y_SHARE_ROW + 4,
                () -> machine.canBeShared() ? LANG_SHARE_ON : LANG_SHARE_OFF);
    }

    /**
     * 定量行的步进按钮（自己拼的那个，见类注释「这一行为什么不用 GTM 的 IntInputWidget」）。
     *
     * @param x     按钮 X（- 在数字框右侧，+ 在幻影槽那一列）
     * @param label 按钮上的字（{@code -} / {@code +}）
     * @param sign  +1 加、-1 减
     */
    private Widget createStepButton(int x, String label, int sign) {
        return new ButtonWidget(x, Y_BATCH_ROW, ROW_HEIGHT, ROW_HEIGHT,
                new GuiTextureGroup(GuiTextures.VANILLA_BUTTON, new TextTexture(label)),
                clickData -> stepBatchSize(clickData, sign))
                .setHoverTooltips(TOOLTIP_STEP);
    }

    /**
     * 点一次步进按钮：步长沿用 GTM 的手感（1 / Shift 8 / Ctrl 64 / 两键 512）。
     *
     * <p>
     * ⚠️ 只在服务端改值（{@code isRemote} 就是客户端那次回调）：{@code batchSize} 是 {@code @Persisted} 字段，
     * 服务端改完由 LDLib 同步回客户端显示；客户端自己改会在下次同步时被覆盖，看起来像「点了没用」。
     * 上下限交给部件侧的 {@code setBatchSize}（内部 {@code Mth.clamp}），这里不重复夹。
     */
    private void stepBatchSize(ClickData clickData, int sign) {
        if (clickData.isRemote) return;
        int step = clickData.isCtrlClick ? (clickData.isShiftClick ? 512 : 64) :
                (clickData.isShiftClick ? 8 : 1);
        machine.setBatchSize(machine.getBatchSize() + sign * step);
    }

    /**
     * 数字框里手输的值。
     *
     * <p>
     * {@code setNumbersOnly} 已经把非法输入挡在门外（校验不过时 LDLib 会把框里的字改回去、也不会回调到这里），
     * 这里的 try/catch 只是兜底 —— 免得将来谁换个校验方式就让异常炸在 GUI tick 里。
     */
    private void setBatchSizeFromText(String text) {
        try {
            machine.setBatchSize(Integer.parseInt(text.trim()));
        } catch (NumberFormatException ignored) {
            // 空串 / 只有一个负号这类中间态：先不改值
        }
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
