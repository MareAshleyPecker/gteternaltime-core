package rain.gtetcore.gtet.integration.ae2

import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.AEItemKey
import com.gregtechceu.gtceu.api.gui.GuiTextures
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator
import com.gregtechceu.gtceu.api.gui.widget.PhantomFluidWidget
import com.gregtechceu.gtceu.api.gui.widget.PhantomSlotWidget
import com.gregtechceu.gtceu.api.gui.widget.ToggleButtonWidget
import com.gregtechceu.gtceu.api.transfer.fluid.CustomFluidTank
import com.gregtechceu.gtceu.api.transfer.item.CustomItemStackHandler
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture
import com.lowdragmc.lowdraglib.gui.texture.TextTexture
import com.lowdragmc.lowdraglib.gui.util.ClickData
import com.lowdragmc.lowdraglib.gui.widget.*
import net.minecraft.network.chat.Component
import net.minecraftforge.fluids.FluidType
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.LANG_SHARE_TIP_0
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.LANG_SHARE_TIP_3
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.PANEL_HEIGHT
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.PANEL_HEIGHT_NO_SHARE
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.ROW_HEIGHT
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.SHARE_TOGGLE_X
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.Y_HINT
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.Y_HINT_NO_SHARE
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator.Companion.Y_SHARE_ROW
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * 「标签过滤 + 定量拉取 + 多方块共享」面板：白名单输入框、黑名单输入框各配一个**幻影槽**，
 * 一个「每次拉 N 个」的数字框，再加一个「能不能被别的多方块共享」的开关。
 *
 * ## 四行控件
 *
 * 1. **白名单**：输入框（留空 = 不限制）+ 幻影槽；
 * 2. **黑名单**：输入框（留空 = 不限制）+ 幻影槽；
 * 3. **每次拉取量 N**：数字框在最左，两个 **-/+ 按钮在右侧**（与上面两行的幻影槽同一列），
 *    范围 0 ~ `BATCH_MAX`，**0 = 不限制**（默认，行为与 GTM 原版库存部件一致）。
 *    N 必须 ≥ 本仓的「保底数量」（GTM 自带的 `min_item_count` / `min_fluid_count`），否则备出来的量
 *    永远达不到保底，本仓会一直空着 —— 这是定量模式与保底语义的固有冲突，提示行里写明了。
 * 4. **多方块共享开关**（[Y_SHARE_ROW]）：复用 GTM 的 `ToggleButtonWidget`，
 *    右侧一行短状态文字。**默认关 = 隔离**（防串配方）；两个方向分别发生什么、
 *    以及「改动在结构重新检查后生效」全部写在这个开关的 tooltip 里（4 条，中英各一份，见
 *    [LANG_SHARE_TIP_0] ~ [LANG_SHARE_TIP_3]）。
 *    ⚠️ 一台机器只有**一个**开关：二合一件的流体侧那块面板不画这一行（`showShareSwitch=false`）。
 *
 * ## ⚠️ 这一行为什么不用 GTM 的 `IntInputWidget`
 *
 * `IntInputWidget` 继承的 `NumberInputWidget#buildUI()` 是 **private**，按钮位置与高度写死在里面：
 * 「-」按钮贴左边（x=0）、输入框居中、「+」按钮贴右边，而且两个按钮与输入框的高度硬编码 `20`
 * —— **不管构造时传多高**。于是它必然把按钮摊在输入框两侧，并且比声明的框高出 4px、压到下一块内容上；
 * 子类无法重排。需求是「按钮挪到右边」，所以这里自己拼一行（输入框 + 两个按钮，全部 18 高、与框一致），
 * 步进手感沿用 GTM（1 / Shift 8 / Ctrl 64 / 两键 512），tooltip 直接借 GTM 自带的那条说明键。
 *
 * ## 幻影槽怎么工作
 *
 * 往幻影槽里放一个物品（流体部件则是流体，可以从 JEI/EMI 拖，也可以点一下手持的桶）之后，
 * 取该样本的全部标签、按字母序用 `|` 连接写进本行的表达式 —— 于是「命中其中任意一个标签」的都放行，
 * 这正是「先拉一类东西」的起点。玩家随后可以随便改这条表达式（删掉多余的标签、换成 `&` / `!`）。
 *
 * - 物品幻影槽：**右键清空样本**（`setClearSlotOnRightClick(true)`）；
 * - 流体幻影槽：**空手右键清空**（`PhantomFluidWidget` 的点击语义就是「把手上那份量放进 / 放出」）；
 * - ⚠️ 样本**一个标签都没有**时表达式保持不变（不清空、不报错）—— 免得把玩家手写的一大串表达式顺手抹掉。
 *
 * ## 为什么不是 GTOCore 那种「标签列表可点」的面板
 *
 * GTOCore（LGPL-3.0）的 `ITagFilterPartMachine.FilterIFancyConfigurator` 底下会摊开一个可点击的标签列表
 * （左键把该标签写进输入框、右键复制到剪贴板），靠的是它自己 GTM 分叉里的
 * `TagFilter.StackHandlerWidget` / `TagItemFilter.PhantomSlot` / `TagFluidFilter.TankSlot`
 * —— **这三个类在官方 GTM 7.5.3 里不存在**（本文件用到的 `TagItemFilter` / `TagFluidFilter`
 * 只有 `loadFilter` 与 `test`，没有任何嵌套 Widget）。官方件上要做同样的交互，
 * 得自己写一个「点击后 `writeClientAction` / 服务端 `handleClientAction`」的自定义 Widget，
 * 而 GTET 这边**用幻影槽直接把表达式填出来**已经满足需求，就不再多背一个自定义同步 Widget 的风险。
 *
 * ## 输入框的同步
 *
 * 走 LDLib `TextFieldWidget` 自带的那套：构造时给 (读 supplier, 写 consumer) 一对，
 * 服务端侧 `detectAndSendChanges` 把值 push 给客户端显示、客户端输入通过 client action 回到服务端。
 * 所以幻影槽在服务端改了表达式之后，输入框下一 tick 自己就会刷新，不需要我们手动同步。
 *
 * @author rain fox
 */
open class ETTagFilterConfigurator @JvmOverloads constructor(
    private val machine: IMEStockingHost,
    /** true = 流体部件（幻影槽收流体、N 的单位是 mB），false = 物品部件。 */
    private val fluid: Boolean,
    /**
     * 是否画出「多方块共享」开关行。
     *
     * ⚠️ 只有二合一件的流体侧那块面板会传 false（一台机器有两块面板、但只有一个开关，
     * 画两次会让玩家以为要拨两次）。不带这一行时说明块上移、面板高也同步缩短
     * （见 [hintY] / [panelHeight]），不留空尾。
     */
    private val showShareSwitch: Boolean = true,
) : IFancyConfigurator {

    /** 图标直接借 GTM 自带的黑白名单按钮贴图。 */
    override fun getIcon(): IGuiTexture {
        return GuiTextures.BUTTON_BLACKLIST.getSubTexture(0.0, 0.0, 1.0, 0.5)
    }

    /** 标题；子类 `SideConfigurator` 会覆写它，所以保持 `open`。 */
    open override fun getTitle(): Component {
        return Component.translatable(LANG_TITLE)
    }

    override fun createConfigurator(): Widget {
        val group = WidgetGroup(0, 0, PANEL_WIDTH, panelHeight())
        val hintY = hintY()

        // 白名单行
        group.addWidget(LabelWidget(4, Y_WHITE_LABEL, LANG_WHITE))
        group.addWidget(
            TextFieldWidget(4, Y_WHITE_FIELD, FIELD_WIDTH, 16, machine::getTagWhite, machine::setTagWhite)
                .setMaxStringLength(MAX_EXPRESSION_LENGTH)
        )
        group.addWidget(createPhantom(PHANTOM_X, Y_WHITE_FIELD, machine::setTagWhite))

        // 黑名单行
        group.addWidget(LabelWidget(4, Y_BLACK_LABEL, LANG_BLACK))
        group.addWidget(
            TextFieldWidget(4, Y_BLACK_FIELD, FIELD_WIDTH, 16, machine::getTagBlack, machine::setTagBlack)
                .setMaxStringLength(MAX_EXPRESSION_LENGTH)
        )
        group.addWidget(createPhantom(PHANTOM_X, Y_BLACK_FIELD, machine::setTagBlack))

        // 定量模式行：0 = 不限制。数字框在左，-/+ 按钮挪到右侧（与上面两行的幻影槽同一列）
        group.addWidget(LabelWidget(4, Y_BATCH_LABEL, LANG_BATCH))
        group.addWidget(
            TextFieldWidget(
                4, Y_BATCH_ROW, BATCH_FIELD_WIDTH, ROW_HEIGHT,
                Supplier { machine.getBatchSize().toString() }, this::setBatchSizeFromText
            )
                .setNumbersOnly(BATCH_MIN, BATCH_MAX)
                .setMaxStringLength(MAX_BATCH_LENGTH)
        )
        group.addWidget(createStepButton(BATCH_MINUS_X, "-", -1))
        group.addWidget(createStepButton(PHANTOM_X, "+", 1))

        // 多方块共享行（二合一件的流体侧那块面板不画这一行）
        if (showShareSwitch) {
            group.addWidget(LabelWidget(4, Y_SHARE_LABEL, LANG_SHARE))
            group.addWidget(createShareToggle())
            group.addWidget(createShareStateLabel())
        }

        // 说明
        group.addWidget(LabelWidget(4, hintY, LANG_HINT_0))
        group.addWidget(LabelWidget(4, hintY + HINT_LINE_HEIGHT, LANG_HINT_1))
        group.addWidget(LabelWidget(4, hintY + HINT_LINE_HEIGHT * 2, LANG_HINT_2))
        group.addWidget(LabelWidget(4, hintY + HINT_LINE_HEIGHT * 3, LANG_HINT_3))

        return group
    }

    /** 说明块首行 Y：带开关行时是 [Y_HINT]，不带时是 [Y_HINT_NO_SHARE]。 */
    private fun hintY(): Int {
        return if (showShareSwitch) Y_HINT else Y_HINT_NO_SHARE
    }

    /**
     * 面板高：必须等于内容底边 —— 即「说明块首行 Y + 4 行 × 行距」，
     * 也就是 [Y_HINT] + 40 = 186 与 [Y_HINT_NO_SHARE] + 40 = 150，
     * 与 [PANEL_HEIGHT] / [PANEL_HEIGHT_NO_SHARE] 一一对应（改布局时两边要一起改）。
     */
    private fun panelHeight(): Int {
        return if (showShareSwitch) PANEL_HEIGHT else PANEL_HEIGHT_NO_SHARE
    }

    /**
     * 「能不能被别的多方块共享」开关：直接用 GTM 自己的 [ToggleButtonWidget]
     * （`SwitchWidget` 的子类），贴图借 GTM 的 `button_public_private.png`。
     *
     * ## 为什么用 `BUTTON_PUBLIC_PRIVATE`、为什么不用 `setShouldUseBaseBackground()`
     *
     * 那张 png 是 **18×36 = 上下两半各 18×18**（已核实文件尺寸），正好是 `ToggleButtonWidget`
     * 认的「未按下 / 已按下」两张贴图 —— 于是图标本身随状态变化（与 GTM 的
     * `SimpleItemFilter` 用 `BUTTON_BLACKLIST` 的用法一致）。
     * 刻意**不**加 `setShouldUseBaseBackground()`：那个会把「整张」png（两半叠在一起）
     * 再套一层底板画出来，图标会被压扁。上半 = 未按下（= 默认的「隔离」），下半 = 已按下（= 允许共享）。
     *
     * ## 为什么放在这一行、这个尺寸
     *
     * - X = [SHARE_TOGGLE_X]（4）：与上面三行的控件左对齐，成一条竖线；
     * - Y = [Y_SHARE_ROW]（122）：紧接定量行（底边 104）下方，自己在标题 110 之下；
     * - 18×18：与幻影槽 / 步进按钮同高（[ROW_HEIGHT]），不会比声明的框高出几个像素而压到下一行。
     *
     * ## ⚠️ 同步与方向
     *
     * `SwitchWidget` 的点击是「客户端发 client action → 服务端 `handleClientAction` 回调」，
     * 所以 [IMEStockingHost.setCanBeShared] 是在**服务端**调的（正确：该值服务端权威）；
     * 开关自身的按下状态由它自己的 `supplier` + `updateScreen()` 每 tick 拉回来。
     */
    private fun createShareToggle(): Widget {
        return ToggleButtonWidget(
            SHARE_TOGGLE_X, Y_SHARE_ROW, ROW_HEIGHT, ROW_HEIGHT,
            GuiTextures.BUTTON_PUBLIC_PRIVATE, machine::canBeShared, machine::setCanBeShared
        )
            .setHoverTooltips(
                Component.translatable(LANG_SHARE_TIP_0),
                Component.translatable(LANG_SHARE_TIP_1),
                Component.translatable(LANG_SHARE_TIP_2),
                Component.translatable(LANG_SHARE_TIP_3)
            )
    }

    /**
     * 开关右侧的状态文字：用「(读 supplier)」的 [LabelWidget]，值变了由 LDLib 自己推给客户端
     * （`LabelWidget#detectAndSendChanges` 比对字符串后 `writeUpdateInfo`），不必手动同步。
     *
     * ⚠️ supplier 返回的是**语言键本身**（不是 `Component.translatable(...).getString()`）：
     * `LabelWidget` 画的时候会自己过一遍 `LocalizationUtils.format`，
     * 与 GTM 自己写 `() -> isOnline ? "gtceu.gui.me_network.online" : ...` 的用法一致。
     *
     * ⚠️ 文案必须短：LDLib 的 `LabelWidget` 不换行，英文一旦写长（例如
     * "Allowed (click to isolate)"）就会画出面板右边缘。说明写在 tooltip 里。
     */
    private fun createShareStateLabel(): Widget {
        return LabelWidget(
            SHARE_LABEL_X, Y_SHARE_ROW + 4,
            Supplier { if (machine.canBeShared()) LANG_SHARE_ON else LANG_SHARE_OFF }
        )
    }

    /**
     * 定量行的步进按钮（自己拼的那个，见类注释「这一行为什么不用 GTM 的 IntInputWidget」）。
     *
     * @param x     按钮 X（- 在数字框右侧，+ 在幻影槽那一列）
     * @param label 按钮上的字（`-` / `+`）
     * @param sign  +1 加、-1 减
     */
    private fun createStepButton(x: Int, label: String, sign: Int): Widget {
        return ButtonWidget(
            x, Y_BATCH_ROW, ROW_HEIGHT, ROW_HEIGHT,
            GuiTextureGroup(GuiTextures.VANILLA_BUTTON, TextTexture(label))
        ) { clickData -> stepBatchSize(clickData, sign) }
            .setHoverTooltips(TOOLTIP_STEP)
    }

    /**
     * 点一次步进按钮：步长沿用 GTM 的手感（1 / Shift 8 / Ctrl 64 / 两键 512）。
     *
     * ⚠️ 只在服务端改值（`isRemote` 就是客户端那次回调）：`batchSize` 是 `@Persisted` 字段，
     * 服务端改完由 LDLib 同步回客户端显示；客户端自己改会在下次同步时被覆盖，看起来像「点了没用」。
     * 上下限交给部件侧的 `setBatchSize`（内部 `Mth.clamp`），这里不重复夹。
     */
    private fun stepBatchSize(clickData: ClickData, sign: Int) {
        if (clickData.isRemote) return
        val step = if (clickData.isCtrlClick) {
            if (clickData.isShiftClick) 512 else 64
        } else {
            if (clickData.isShiftClick) 8 else 1
        }
        machine.setBatchSize(machine.getBatchSize() + sign * step)
    }

    /**
     * 数字框里手输的值。
     *
     * `setNumbersOnly` 已经把非法输入挡在门外（校验不过时 LDLib 会把框里的字改回去、也不会回调到这里），
     * 这里的 try/catch 只是兜底 —— 免得将来谁换个校验方式就让异常炸在 GUI tick 里。
     */
    private fun setBatchSizeFromText(text: String) {
        try {
            machine.setBatchSize(text.trim().toInt())
        } catch (ignored: NumberFormatException) {
            // 空串 / 只有一个负号这类中间态：先不改值
        }
    }

    /** 按部件类型造物品版或流体版幻影槽。 */
    private fun createPhantom(x: Int, y: Int, expressionSink: Consumer<String>): Widget {
        return if (fluid) createFluidPhantom(x, y, expressionSink) else createItemPhantom(x, y, expressionSink)
    }

    /**
     * 物品幻影槽。
     *
     * ⚠️ 反馈时机：`setChangeListener` 是 GTM 自己 `SlotWidget` 里的钩子
     * （GTM 的 `SimpleItemFilter` 就是用它的），槽内容真的变了才回调，不是每 tick 都跑。
     */
    private fun createItemPhantom(x: Int, y: Int, expressionSink: Consumer<String>): Widget {
        val handler = CustomItemStackHandler(1)
        val slot = PhantomSlotWidget(handler, 0, x, y)
        slot.setClearSlotOnRightClick(true)
        slot.setBackground(GuiTextures.SLOT)
        slot.setChangeListener {
            val stack = handler.getStackInSlot(0)
            if (stack.isEmpty) return@setChangeListener
            val key = AEItemKey.of(stack) ?: return@setChangeListener
            val expression = ETTagFilter.expressionOf(key)
            // 样本没有标签 → 保持原表达式不动
            if (expression.isNotEmpty()) expressionSink.accept(expression)
        }
        return slot
    }

    /**
     * 流体幻影槽。
     *
     * ⚠️ 反馈时机用 `CustomFluidTank#setOnContentsChanged`：`PhantomFluidWidget` 把「拖进来 / 点一下」
     * 的结果都汇到 `phantomFluidSetter` → `CustomFluidTank#setFluid`，而 GTM 的
     * `CustomFluidTank` 覆写了 `setFluid` 并在末尾触发 `onContentsChanged`
     * （已核实源码，不是靠基类的行为）。
     */
    private fun createFluidPhantom(x: Int, y: Int, expressionSink: Consumer<String>): Widget {
        val tank = CustomFluidTank(FluidType.BUCKET_VOLUME)
        tank.setOnContentsChanged {
            val stack = tank.getFluid()
            if (stack.isEmpty) return@setOnContentsChanged
            val key = AEFluidKey.of(stack) ?: return@setOnContentsChanged
            val expression = ETTagFilter.expressionOf(key)
            if (expression.isNotEmpty()) expressionSink.accept(expression)
        }
        return PhantomFluidWidget(tank, 0, x, y, 18, 18, tank::getFluid, tank::setFluid)
    }

    companion object {

        /** 面板标题键（中英在注册文件里用 `LangUtil.add` 登记）。 */
        const val LANG_TITLE: String = "gtetcore.machine.et_tag_filter.title"

        /** 白名单行标题。 */
        const val LANG_WHITE: String = "gtetcore.machine.et_tag_filter.white"

        /** 黑名单行标题。 */
        const val LANG_BLACK: String = "gtetcore.machine.et_tag_filter.black"

        /** 定量模式行标题。 */
        const val LANG_BATCH: String = "gtetcore.machine.et_tag_filter.batch"

        /** 多方块共享开关行标题。 */
        const val LANG_SHARE: String = "gtetcore.machine.et_tag_filter.share"

        /** 共享开关的「开」状态文字（紧跟开关右侧）。 */
        const val LANG_SHARE_ON: String = "gtetcore.machine.et_tag_filter.share.on"

        /** 共享开关的「关」状态文字。 */
        const val LANG_SHARE_OFF: String = "gtetcore.machine.et_tag_filter.share.off"

        /** 开关悬停说明 0：这个开关管什么。 */
        const val LANG_SHARE_TIP_0: String = "gtetcore.machine.et_tag_filter.share.tip.0"

        /** 开关悬停说明 1：关（隔离）方向。 */
        const val LANG_SHARE_TIP_1: String = "gtetcore.machine.et_tag_filter.share.tip.1"

        /** 开关悬停说明 2：开（允许共享）方向。 */
        const val LANG_SHARE_TIP_2: String = "gtetcore.machine.et_tag_filter.share.tip.2"

        /** 开关悬停说明 3：时序（什么时候生效）。 */
        const val LANG_SHARE_TIP_3: String = "gtetcore.machine.et_tag_filter.share.tip.3"

        /** 说明行 0：运算符。 */
        const val LANG_HINT_0: String = "gtetcore.machine.et_tag_filter.hint.0"

        /** 说明行 1：`,` 与 `#` 的便利写法。 */
        const val LANG_HINT_1: String = "gtetcore.machine.et_tag_filter.hint.1"

        /** 说明行 2：幻影槽用法。 */
        const val LANG_HINT_2: String = "gtetcore.machine.et_tag_filter.hint.2"

        /** 说明行 3：留空语义与定量/保底的冲突。 */
        const val LANG_HINT_3: String = "gtetcore.machine.et_tag_filter.hint.3"

        /**
         * 面板尺寸。
         *
         * ⚠️ 宽度 150 是上限，别再加：fancy 侧栏展开的浮层是「内容宽 + 8」，而浮层是从主界面左边缘往右画的，
         * 再宽就会盖住主界面的配置槽区。
         *
         * ⚠️ 高度必须**等于内容底边**（带开关行时最后一行说明的底边正好是 186；不带开关行时是 150）。
         * fancy 浮层的高度是「内容高 + 24（图标行）+ 4」，多留的空白会变成浮层底部的一大块空区，少留则内容越界。
         * ⚠️ 高度是**往下长**的（浮层顶边 = 图标行处，见 `ConfiguratorPanel.Tab#expand`），
         * 所以加一行只会让浮层底部更靠下，不会压到别的东西；但超过屏幕高度时 GTM 会把浮层整体上移。
         */
        private const val PANEL_WIDTH = 150

        /** 带「多方块共享」开关行时的面板高 = 末行说明底边 176 + 10。 */
        private const val PANEL_HEIGHT = 186

        /** 不带开关行时（二合一件的流体侧那块）的面板高 = 末行说明底边 140 + 10，与原布局一致。 */
        private const val PANEL_HEIGHT_NO_SHARE = 150

        // 各行控件的 Y 与尺寸 —— 改布局只动这里，别在 addWidget 里散落魔数

        /** 白名单：标题 / 输入框（输入框右侧留 18px 给幻影槽）。 */
        private const val Y_WHITE_LABEL = 2
        private const val Y_WHITE_FIELD = 14

        /** 黑名单。 */
        private const val Y_BLACK_LABEL = 38
        private const val Y_BLACK_FIELD = 50

        /** 定量行：标题在 74，数字框与 -/+ 按钮同在 86。 */
        private const val Y_BATCH_LABEL = 74
        private const val Y_BATCH_ROW = 86

        /**
         * 多方块共享行：标题在 110，开关与状态文字同在 122（高 18，与上面几行的控件同高）。
         *
         * ⚠️ 这一行是**新加的一整行**（原布局里定量行的底边 104 之后直接是说明块 110）：
         * 加行就要把说明块整体下移 36px（见 [Y_HINT]）并把面板高同步加上，
         * 否则说明块会与开关行压在一起（本面板上一版踩过的坑正是「控件实际高度比声明的高 4px → 叠字」）。
         */
        private const val Y_SHARE_LABEL = 110
        private const val Y_SHARE_ROW = 122

        /** 说明块首行 Y（带开关行时）与行距（4 行，末行底边 176 + 10 = 186 = 面板高）。 */
        private const val Y_HINT = 146

        /** 不带开关行时的说明块首行 Y（= 原布局的 110，末行底边 140 + 10 = 150 = 面板高）。 */
        private const val Y_HINT_NO_SHARE = 110
        private const val HINT_LINE_HEIGHT = 10

        /** 表达式输入框宽度：右边要留出 18px 的幻影槽。 */
        private const val FIELD_WIDTH = 112

        /** 第三行数字框宽度：右边要让出两个 18px 的按钮。 */
        private const val BATCH_FIELD_WIDTH = 88

        /** 幻影槽所在列（第三行「+」按钮与之同列，视觉上成一列）。 */
        private const val PHANTOM_X = 120

        /** 第三行「-」按钮的 X（紧接数字框右侧）。 */
        private const val BATCH_MINUS_X = 98

        /** 第三行与共享行控件统一高 18，与幻影槽同高。 */
        private const val ROW_HEIGHT = 18

        /** 共享开关的 X（与上面几行控件左对齐）。 */
        private const val SHARE_TOGGLE_X = 4

        /** 共享开关右侧状态文字的 X（开关 18 宽 + 4px 间隙）。 */
        private const val SHARE_LABEL_X = 26

        /** 表达式最长字符数，够自动填 16 个标签。 */
        private const val MAX_EXPRESSION_LENGTH = 512

        /** 数字框最长字符数：`BATCH_MAX` = 1000000 是 7 位。 */
        private const val MAX_BATCH_LENGTH = 7

        /** 步进按钮的悬停说明：直接用 GTM 自带的那条（中英都已有，不必新登记语言键）。 */
        private const val TOOLTIP_STEP = "gui.widget.incrementButton.default_tooltip"

        /** 数字框上下限与部件侧的夹取保持一致（部件那边还会再夹一次，这里只影响 UI）。 */
        private const val BATCH_MIN = 0
        private const val BATCH_MAX = 1_000_000
    }
}