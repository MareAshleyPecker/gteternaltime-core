package rain.gtetcore.gtet.common.machine.multiblock.part.ae
import appeng.api.implementations.blockentities.PatternContainerGroup
import appeng.api.inventories.InternalInventory
import appeng.api.stacks.AEItemKey
import appeng.crafting.pattern.EncodedPatternItem
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.gui.GuiTextures
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget
import com.gregtechceu.gtceu.integration.ae2.gui.widget.slot.AEPatternViewSlotWidget
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.lowdragmc.lowdraglib.utils.Position
import net.minecraft.MethodsReturnNonnullByDefault
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.HEADER
import rain.gtetcore.gtet.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.MAX_BLOCKS
import rain.gtetcore.gtet.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.MAX_ROWS_PER_BLOCK
import rain.gtetcore.gtet.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.PAGE_CAPACITY
import rain.gtetcore.gtet.config.GTETConfig
import rain.gtetcore.gtet.integration.ae2.ETPatternBufferCapacities
import rain.gtetcore.gtet.mixin.GTM.IMEPatternBufferAccess
import java.util.*
import javax.annotation.ParametersAreNonnullByDefault

/**
 * 「多阶段 ME 样板总成」：容量大于 GTM 原生 27 的样板总成，四档各一件（LuV/UV/UEV/UXV）。
 *
 * 就是 GTM 的 [MEPatternBufferPartMachine]，只把样板槽位数从写死的 27 变成按档取值；
 * 四种能力、AE 终端交互、数据棒绑定镜像、取回、Jade 显示全部沿用父类。容量靠
 * `MixinMEPatternBufferCapacity` 在父类构造期把内联的 3 个 27 换成查表值
 * （父类字段初始化早于子类字段，继承 + 覆写拿不到），取舍见 [ETPatternBufferCapacities]。
 *
 * 本类只补父类剩下还写着 27 的三处：[getTerminalPatternInventory]（AE 终端能放几盘）、
 * [createUIWidget]（面板版式）、[getTerminalGroup]（未成型时的图标与名字）。
 *
 * ⚠️ 面板与格子数一律以**实际存储**为准（`getPatternInventory().getSlots()`）：mixin 万一
 * 没生效也只是面板变小，不会出现「面板 126 格、实际只能放 27 盘」这种错位
 * （[ETPatternBufferCapacities.verify] 会在日志里吵一次）。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class ETMEPatternBufferPartMachine(holder: IMachineBlockEntity, vararg args: Any?) :
    MEPatternBufferPartMachine(holder, IO.IN, *args) {

    /**
     * 当前翻到第几页（0 起）。
     *
     * 纯界面状态：不持久化、不同步。必须两端一致的只有控件树结构（= 页数，由容量推出，天然一致）；
     * 页码只决定"哪一页可见"、不影响槽位编号，而 `ButtonWidget` 的回调两端都会跑（javap 实证），
     * 两端各自翻到同一页就够，某一侧没跟上也不会串槽。放机器字段而不是控件局部量：fancy UI 换页会重建
     * 控件（`setupFancyUI`），字段能记住玩家翻到哪一页。
     */
    private var uiPage: Int = 0

    /**
     * AE 终端看到的样板库存视图（尺寸 = 真实格子数）。
     *
     * 父类那个匿名实现的 `size()` 也是内联的 27 且字段私有，只能整个覆写。三段逻辑与父类逐字
     * 对应（写槽 → 通知内容变化 → `onPatternChange` 更新 AE 索引表），少最后一段，第 28 格往后
     * 放进去的样板就不会被 `pushPattern` 认出来。
     */
    private val terminalPatternInventory: InternalInventory = object : InternalInventory {

        override fun size(): Int {
            return patternInventory.slots
        }

        override fun getStackInSlot(slotIndex: Int): ItemStack {
            return patternInventory.getStackInSlot(slotIndex)
        }

        override fun setItemDirect(slotIndex: Int, stack: ItemStack) {
            patternInventory.setStackInSlot(slotIndex, stack)
            patternInventory.onContentsChanged(slotIndex)
            access().`gtet$onPatternChange`(slotIndex)
        }
    }

    init {
        // 探针：mixin 没生效时（GTM 版本变化）在日志里吵一次，而不是静默做成 27 格
        ETPatternBufferCapacities.verify(this)
    }

    /** 本机的样板槽位数（**以实际存储为准**，供镜像读容量）。 */
    fun getPatternCapacity(): Int {
        return patternInventory.slots
    }

    /**
     * **仓室隔离**：一件总成不能被两个多方块同时占用（防串配方）。
     *
     * `IMultiPart#canShared()` 默认 true，GTM 只在 `BlockPattern#checkPatternAt` 里消费它：
     * 第二件控制器把这件总成收进部件表时该格判失败、结构成不了型（错误是
     * `multiblocked.pattern.error.share`）。本件必须隔离有两条理由：GTM 自己就假设「一件总成只属于
     * 一个控制器」（父类 `getTerminalGroup()` 直接取 `getControllers().first()`）；
     * 而 AE 推样板时原料落在**这一件自己的**库存里（`pushPattern` →
     * `pushInputsToExternalInventory`），共用时谁先跑谁吃掉。
     *
     * 「总成当宿主、镜像装在各机器里」这种主要用法不受影响 —— 那时总成不在任何成型结构里，
     * `isFormed()` 为 false，这道闸门不参与判断。与本项目其它 ME 库存件一样，
     * tooltip 要写「禁止共享」（见 `ETMEPatternBufferHatches`）。
     *
     * **闸门现在由配置 `multiblock.partsShareable` 兜底**（默认 false = 仍然隔离，
     * 与原先写死 false 完全一致）：配置打开后本件可被两个已成型结构同时占用，
     * 于是上面那条「AE 推样板时原料落在这件自己的库存里」的**串配方风险重新出现**。
     * ⚠️ 该值只在 `BlockPattern#checkPatternAt` 那一刻被读，改配置后要等下一次结构检测才生效。
     */
    override fun canShared(): Boolean {
        return GTETConfig.partsShareable()
    }

    override fun getTerminalPatternInventory(): InternalInventory {
        return terminalPatternInventory
    }

    override fun getTerminalGroup(): PatternContainerGroup {
        // 已成型：沿用父类（控制器名字 + 电路号，或自定义名）
        if (isFormed) return super.getTerminalGroup()
        // 未成型：父类这一支把图标与名字写死成 GTM 自己的 me_pattern_buffer。
        // 我们这四档常常就是「不组进多方块、只给一堆镜像当宿主」的用法，所以必须显示自己这一档。
        val definition = getDefinition()
        val customName = access().`gtet$getCustomName`()
        if (!customName.isEmpty()) {
            return PatternContainerGroup(
                AEItemKey.of(definition.item), Component.literal(customName),
                Collections.emptyList()
            )
        }
        return PatternContainerGroup(
            AEItemKey.of(definition.item),
            definition.item.description, Collections.emptyList()
        )
    }

    /**
     * 机器 UI：与 GTM 的 `IFancyUIMachine#createUI` 逐字对应，只把 `FancyMachineUIWidget`
     * 换成会"底边贴屏"的子类（见 [ETPatternBufferUIWidget]）。必须在这一层换：窗口的尺寸与屏幕
     * 定位由 `setupFancyUI` 决定，面板控件自己没有屏幕坐标。
     */
    override fun createUI(entityPlayer: Player): ModularUI {
        return ModularUI(176, 166, this, entityPlayer)
            .widget(ETPatternBufferUIWidget(this, 176, 166))
    }

    /**
     * 样板槽面板：9 列一块、最多并 2 块 = **一页 126 格**；容量超过一页就翻页，面板尺寸不变。
     *
     * ```
     * 一页的格数   = 9 × 7 × 2 = 126              （[PAGE_CAPACITY]）
     * 页数         = ⌈容量 / 126⌉                 （216 → 2 页、512 → 5 页）
     * 某一页的块数 = clamp(⌈这一页的格数 / 63⌉, 1, 2)；行数 = ⌈这一页的格数 / (9 × 块数)⌉
     * 面板(宽×高)  = 按"一页铺满"算 ⇒ 340 × 142，**翻页时一个像素都不变**
     *
     * 档位  容量  块数×行数   面板(宽×高)   整个窗口高 = 面板高 + 8(边框) + 86(玩家物品栏)
     * LuV    27   1 × 3      178 ×  70        164
     * UV     63   1 × 7      178 × 142        236
     * UEV   126   2 × 7      340 × 142        236
     * UXV   216   2 × 7      340 × 142        236（2 页）
     * ```
     * 8 是 `FancyMachineUIWidget#setupFancyUI` 的 `border*2`，86 是 LDLib
     * `PlayerInventoryWidget` 的默认高度（javap 本项目实际编译用的 ldlib jar 可复核）。
     *
     * 为什么是"翻页"：用户口径是**一屏看全、不滚动**。继续并块会被宽度卡住（页栏画在窗口左侧外面，
     * 见 [MAX_BLOCKS]）；继续长高更不行 —— 12 行那版（一页 216 格）窗口就已经 326px 高，
     * 1080p 缩放 4/自动放不下，靠底边贴屏会连状态行一起裁掉。页数只由容量推出，
     * 玩家以后自己加大档位（注册上限 4096 ⇒ 33 页）也不用改代码。
     *
     * 页怎么藏：每一页都**真的建出来**（控件树两端必须一致，不能按当前页建树），只把非当前页
     * `setVisible(false)`。LDLib 的绘制和 `mouseClicked` 都跳过 `isVisible()` 为假的
     * 子控件，所以藏起来的页连物品都画不出、点击也收不到；落槽再走原版 `AbstractContainerScreen`
     * 按坐标重扫 `menu.slots` 的那一步，即便各页格子坐标完全重合也不会串页。
     * ⚠️ 页容器**不**调 `setActive(false)`：LDLib 的 `detectAndSendChanges` 按 `isActive`
     * 过滤子控件，藏起来的页仍要同步（两端槽内容必须一致）。
     *
     * 翻页控件 `[◀] 当前页/总页数 [▶]` 摆在 [HEADER] 那行、右对齐贴着改名按钮 —— 不占网格
     * 高度。只有页数 &gt; 1 时才加这几个控件，所以 27 / 63 / 126 三档的面板与以前逐像素相同。
     *
     * 页内块内**先列后行**（`x = i%9, y = i/9`）：槽号先沿一块往下、再换右面一块、再翻页，
     * 与"总成里的第 N 盘样板"一一对应、不跳号。
     */
    override fun createUIWidget(): Widget {
        val inventory = patternInventory
        val capacity = inventory.slots
        val pageCount = 1.coerceAtLeast(ceilDiv(capacity, PAGE_CAPACITY))
        uiPage = Mth.clamp(uiPage, 0, pageCount - 1)   // 页码兜底（容量变了也不会指到不存在的一页）

        // 面板尺寸按"一页铺满"算（容量不足一页时就用容量本身）：翻页只换页里的内容，尺寸一个像素不动
        val filled = capacity.coerceAtMost(PAGE_CAPACITY)
        val panelBlocks = blocksOf(filled)
        val panelRows = rowsOf(filled, panelBlocks)
        val blockWidth = SLOT * BLOCK_COLUMNS
        val gridWidth = blockWidth * panelBlocks
        val gridHeight = SLOT * panelRows

        val pageWidth = gridWidth + PADDING_X * 2
        val pageHeight = HEADER + gridHeight + PADDING_BOTTOM
        val panel = WidgetGroup(0, 0, pageWidth, pageHeight)

        // 顶部：ME 网络状态 + 改名（固定在面板最上一行，不随网格动）
        panel.addWidget(
            LabelWidget(
                PADDING_X, 2
            ) { if (isOnline) "gtceu.gui.me_network.online" else "gtceu.gui.me_network.offline" }
        )
        panel.addWidget(
            AETextInputButtonWidget(pageWidth - PADDING_X - RENAME_WIDTH, 2, RENAME_WIDTH, 10)
                .setText(access().`gtet$getCustomName`())
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(Component.translatable("gui.gtceu.rename.desc"))
        )

        // 网格区：尺寸固定（= 一页），里面一页一个子容器，只有当前页可见（理由见方法注释）
        val grid = WidgetGroup(0, 0, gridWidth, gridHeight)
        val pageViews = ArrayList<WidgetGroup>(pageCount)
        for (p in 0 until pageCount) {
            val count = PAGE_CAPACITY.coerceAtMost(capacity - p * PAGE_CAPACITY)
            val blocks = blocksOf(count)
            val rows = rowsOf(count, blocks)
            val view = WidgetGroup(0, 0, gridWidth, gridHeight)
            var index = 0
            var b = 0
            while (b < blocks && index < count) {
                val block = WidgetGroup(b * blockWidth, 0, blockWidth, gridHeight)
                var y = 0
                while (y < rows && index < count) {
                    var x = 0
                    while (x < BLOCK_COLUMNS && index < count) {
                        val slotIndex = p * PAGE_CAPACITY + index++
                        block.addWidget(
                            AEPatternViewSlotWidget(inventory, slotIndex, x * SLOT, y * SLOT)
                                .setOccupiedTexture(GuiTextures.SLOT)
                                .setItemHook { stack ->
                                    // 编码样板显示成它的产物（与 GTM 的面板一致）
                                    if (!stack.isEmpty) {
                                        val item = stack.item
                                        if (item is EncodedPatternItem) {
                                            val out = item.getOutput(stack)
                                            if (!out.isEmpty) return@setItemHook out
                                        }
                                    }
                                    stack
                                }
                                .setChangeListener { access().`gtet$onPatternChange`(slotIndex) }
                                .setBackground(GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY)
                        )
                        x++
                    }
                    y++
                }
                view.addWidget(block)
                b++
            }
            view.isVisible = p == uiPage
            pageViews.add(view)
            grid.addWidget(view)
        }
        grid.selfPosition = Position(PADDING_X, HEADER)
        panel.addWidget(grid)

        // 翻页控件：只有一页时一个都不加（四档现有面板保持原样）
        if (pageCount > 1) {
            // 两个回调都会在两端各跑一次（见 uiPage 的注释）：两端各自把可见页切过去
            val applyPage = Runnable {
                for (i in pageViews.indices) pageViews[i].isVisible = i == uiPage
            }
            // 页号预留宽度按"最宽的可能值"（总页数写两遍 = 页数位数 × 2 + 1 个 '/'）算，不写死常量：
            // 写死会在页数少时把 [◀] 推到很左边（页号两边留一大段空），页数多时又不够用
            val labelWidth = (pageCount.toString().length * 2 + 1) * PAGE_CHAR
            val nextX = pageWidth - PADDING_X - RENAME_WIDTH - PAGE_RENAME_GAP - PAGE_BUTTON
            val labelX = nextX - PAGE_GAP - labelWidth
            val prevX = labelX - PAGE_GAP - PAGE_BUTTON
            panel.addWidget(
                ButtonWidget(
                    prevX, 1, PAGE_BUTTON, PAGE_BUTTON,
                    GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_LEFT)
                ) { _ ->
                    uiPage = (uiPage + pageCount - 1) % pageCount
                    applyPage.run()
                }
            )
            panel.addWidget(LabelWidget(labelX, 3) { (uiPage + 1).toString() + "/" + pageCount })
            panel.addWidget(
                ButtonWidget(
                    nextX, 1, PAGE_BUTTON, PAGE_BUTTON,
                    GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_RIGHT)
                ) { _ ->
                    uiPage = (uiPage + 1) % pageCount
                    applyPage.run()
                }
            )
        }
        return panel
    }

    /** ⌈a / b⌉（b &gt; 0）。 */
    private fun ceilDiv(a: Int, b: Int): Int {
        return (a + b - 1) / b
    }

    /** 这么多格子要并几块（1 ~ [MAX_BLOCKS]）。 */
    private fun blocksOf(count: Int): Int {
        return 1.coerceAtLeast(MAX_BLOCKS.coerceAtMost(ceilDiv(count, BLOCK_COLUMNS * MAX_ROWS_PER_BLOCK)))
    }

    /** 这么多格子每块摊几行。 */
    private fun rowsOf(count: Int, blocks: Int): Int {
        return ceilDiv(count, BLOCK_COLUMNS * blocks)
    }

    /**
     * 取父类私有成员的桥（`customName` 只有 setter；`onPatternChange` 是私有的
     * 唯一变更处理入口）。cast 走 `Object` 是因为编译期看不见 mixin 加在父类上的接口。
     */
    private fun access(): IMEPatternBufferAccess {
        return this as IMEPatternBufferAccess
    }

    companion object {

        /** 一格样板槽的像素边长（LDLib 标准格）。 */
        private const val SLOT = 18

        /** 一个网格块的列数（与 GTM 的面板一致：9 列）。 */
        private const val BLOCK_COLUMNS = 9

        /**
         * 一个网格块最多几行（7 行 = 126px）。
         *
         * 它同时是"要不要再并一块"的阈值：行数一多先横向铺第二块 9 列（最多 [MAX_BLOCKS] 块），
         * 再放不下就翻页（见 [PAGE_CAPACITY]）。取 7 而不是 12：12 行那版一页 216 格、窗口 326px 高，
         * 1080p 的缩放 4/自动（逻辑高 270）放不进屏幕。
         */
        private const val MAX_ROWS_PER_BLOCK = 7

        /**
         * 面板最多并几块（2 块 = 18 列 = 340px 宽）。
         *
         * 宽度上限来自 GTM：fancy UI 把页码侧栏画在窗口**左侧外面**（`VerticalTabsWidget` 在
         * x = -20），窗口一宽过屏幕，侧栏就被挤出去、连翻页都点不到。所以宽度卡住，高度那侧靠翻页解决。
         */
        private const val MAX_BLOCKS = 2

        /**
         * 一页几格 = 9 × 7 × 2 = **126**（面板 340 × 142px）。
         *
         * 容量不满一页时只渲染实际行数（27 → 1 块 × 3 行、63 → 1 块 × 7 行、126 → 2 块 × 7 行），
         * 超过就翻页、面板尺寸一个像素都不变（216 → 2 页、512 → 5 页）。
         *
         * 版式只由 [MAX_ROWS_PER_BLOCK] / [MAX_BLOCKS] 决定，页数、每页块数行数、
         * 面板尺寸全是从它们推出来的；改版式时顺带看翻页控件的横向位置（它按满页 340px 宽摆）。
         */
        private const val PAGE_CAPACITY = BLOCK_COLUMNS * MAX_ROWS_PER_BLOCK * MAX_BLOCKS

        /** 网格上方的状态行高度（ME 网络状态 + 改名按钮 + 翻页控件，固定在面板最上一行）。 */
        private const val HEADER = 14

        /** 面板左右各留 8px、底边留 2px（沿用 GTM 那一版的边距）。 */
        private const val PADDING_X = 8
        private const val PADDING_BOTTOM = 2

        /** 右上角改名按钮的宽度（翻页控件要贴着它左边摆，所以抽出来）。 */
        private const val RENAME_WIDTH = 70

        /** 翻页按钮边长 / 控件之间的间隙（都在 [HEADER] 那一行里）。 */
        private const val PAGE_BUTTON = 12
        private const val PAGE_GAP = 4

        /**
         * 页号一个字符占的宽度（MC 默认字体里数字与 `/` 都是 5px 字形 + 1px 间距）。
         *
         * 页号那把尺子是**估**的而不是量的：控件树两端各建一次，服务端没有字体可量。
         */
        private const val PAGE_CHAR = 6

        /** 翻页控件与改名按钮之间的间隙。 */
        private const val PAGE_RENAME_GAP = 6
    }
}