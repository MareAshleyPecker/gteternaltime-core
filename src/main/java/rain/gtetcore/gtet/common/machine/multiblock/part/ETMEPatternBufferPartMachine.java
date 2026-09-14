package rain.gtetcore.gtet.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.slot.AEPatternViewSlotWidget;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.EncodedPatternItem;

import java.util.Collections;

import javax.annotation.ParametersAreNonnullByDefault;

import rain.gtetcore.gtet.integration.ae2.ETPatternBufferCapacities;
import rain.gtetcore.gtet.mixin.GTM.IMEPatternBufferAccess;

/**
 * 「多阶段 ME 样板总成」：容量大于 GTM 原生 27 的样板总成，四个阶段各一件（LuV/UV/UEV/UXV）。
 *
 * <h2>它是什么</h2>
 * 就是 GTM 的 {@link MEPatternBufferPartMachine}（AE2 集成式样板供应器：一盘样板 + 一块共享库存
 * + 一块共享流体仓），**只把样板槽位数从写死的 27 变成按档取值**。四种能力
 * （物品/流体 进/出）、AE 终端交互、数据棒绑定镜像、取回(退款)、Jade 显示等全部沿用父类。
 *
 * <h2>容量怎么变大的（本类只有"收尾"，主戏在 mixin）</h2>
 * 父类的容量是 {@code protected static final int MAX_PATTERN_COUNT = 27}，且被**内联**进父类自己的
 * 三个字段初始化表达式（{@code patternInventory}/{@code internalInventory}/{@code detailsSlotMap}），
 * 那发生在父类构造期 —— 子类此刻还没有任何字段，所以「继承 + 覆写」拿不到更大的容量。
 * 本项目**不复制**父类那 707 行，而是让 {@code MixinMEPatternBufferCapacity} 把构造器里内联的 3 个 27
 * 换成「按本机器的方块定义查表」，三个字段一出生就是本档容量；父类后续一切
 * （构造后段的槽实例填充、{@code InternalSlotRecipeHandler} 建表、{@code onLoad} 的样板解码、
 * 取回、Jade 汇总）自动按真实容量工作。取舍与证据见 {@link ETPatternBufferCapacities}。
 *
 * <h2>本类要补的三件事（父类里唯一还写着 27 的地方）</h2>
 * <ol>
 * <li>{@link #getTerminalPatternInventory()}：父类返回的那个匿名 {@code InternalInventory}
 * 的 {@code size()} 也是内联的 27（AE 终端据此决定能放几盘样板），换成按真实格子数；</li>
 * <li>{@link #createUIWidget()}：父类的面板写死 9×3，换成「9 列一块、需要时并排多块」的网格
 * （**不滚动**，四档全部一眼看全，见该方法的注释与 {@link #createUI(Player)}）；</li>
 * <li>{@link #getTerminalGroup()}：父类在「未成型」分支把图标与名字写死成 GTM 自己的
 * {@code me_pattern_buffer}，换成我们自己这一档的定义。</li>
 * </ol>
 *
 * <p>⚠️ 面板与实际格子数以**实际存储**（{@code getPatternInventory().getSlots()}）为准，
 * 不再相信任何常量：mixin 万一没生效也只是面板变小，不会出现「面板 216 格、实际只能放 27 盘」
 * 这种错位（{@link ETPatternBufferCapacities#verify} 会在日志里吵一次）。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ETMEPatternBufferPartMachine extends MEPatternBufferPartMachine {

    /** 一格样板槽的像素边长（LDLib 标准格）。 */
    private static final int SLOT = 18;

    /** 一个网格块的列数（与 GTM 的面板一致：9 列）。 */
    private static final int BLOCK_COLUMNS = 9;

    /**
     * 一个网格块最多几行（12 行 = 216px）。
     *
     * <p>这个数不是"可见行数上限"（**不再滚动**），而是"要不要再并一块"的阈值：
     * 行数一多就横向再铺一块 9 列的块，而不是让面板无限变高。
     * 取 12 的依据见 {@link #createUIWidget()} 里的高度账。
     */
    private static final int MAX_ROWS_PER_BLOCK = 12;

    /**
     * 面板最多并几块（2 块 = 18 列 = 340px 宽）。
     *
     * <p>宽度也要有上限：GTM 的 fancy UI 把页码侧栏画在窗口**左侧外面**
     * （{@code FancyMachineUIWidget} 的 {@code VerticalTabsWidget} 在 x=-20），窗口一旦宽过屏幕，
     * 侧栏会被挤出屏幕、玩家连翻页都点不到。所以宁可让兜底去管高度（见
     * {@link ETPatternBufferUIWidget} 的底边贴屏），也不让宽度无限长。
     */
    private static final int MAX_BLOCKS = 2;

    /** 网格上方的状态行高度（ME 网络状态 + 改名按钮，固定在面板最上一行）。 */
    private static final int HEADER = 14;

    /** 面板左右各留 8px、底边留 2px（沿用 GTM 那一版的边距）。 */
    private static final int PADDING_X = 8;
    private static final int PADDING_BOTTOM = 2;

    /**
     * AE 终端看到的样板库存视图（尺寸 = 真实格子数）。
     *
     * <p>⚠️ 只能覆写 {@link #getTerminalPatternInventory()}：父类那个匿名实现是私有字段、
     * 而且它的 {@code size()} 返回的是内联常量 27，绕不过去。这里的三段逻辑与父类逐字对应
     * （写槽 → 通知内容变化 → {@code onPatternChange} 更新 AE 索引表），
     * 少了最后一段，第 28 格往后放进去的样板就不会被 {@code pushPattern} 认出来。
     */
    private final InternalInventory terminalPatternInventory = new InternalInventory() {

        @Override
        public int size() {
            return getPatternInventory().getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slotIndex) {
            return getPatternInventory().getStackInSlot(slotIndex);
        }

        @Override
        public void setItemDirect(int slotIndex, ItemStack stack) {
            getPatternInventory().setStackInSlot(slotIndex, stack);
            getPatternInventory().onContentsChanged(slotIndex);
            access().gtet$onPatternChange(slotIndex);
        }
    };

    /**
     * @param holder 方块实体
     * @param args   透传给 {@code MEBusPartMachine}（本档不需要额外参数：容量由方块定义决定）
     */
    public ETMEPatternBufferPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, IO.IN, args);
        // 探针：mixin 没生效时（GTM 版本变化）在日志里吵一次，而不是静默做成 27 格
        ETPatternBufferCapacities.verify(this);
    }

    /** 本机的样板槽位数（**以实际存储为准**，供镜像读容量）。 */
    public int getPatternCapacity() {
        return getPatternInventory().getSlots();
    }

    /**
     * <b>仓室隔离</b>：禁止这一件样板总成被两个多方块同时占用（防串配方）。
     *
     * <h2>为什么本件必须隔离（与上一批给 ME 库存件加的是同一条闸门）</h2>
     * {@code IMultiPart#canShared()} 默认返回 {@code true}，全 GTM 只有一个消费点：
     * {@code BlockPattern#checkPatternAt} 逐格匹配时，
     * {@code if (part.isFormed() && !part.canShared() && !part.hasController(worldState.controllerPos))}
     * → 该格判失败并把错误设成 {@code PatternStringError("multiblocked.pattern.error.share")}，
     * 于是<b>第二个多方块结构成不了型</b>。语义与代价见
     * {@link ETTagFilterStockBusPartMachine#canShared()} 的类注释。
     *
     * <h2>样板总成为什么也在这一列（用户口径 + GTM 自己的假设）</h2>
     * <ul>
     * <li><b>GTM 自己就假设「一件总成只属于一个控制器」</b>：父类
     * {@code MEPatternBufferPartMachine#getTerminalGroup()} 直接取
     * {@code getControllers().first()}（GTM 7.5.3 源码实测）—— 被两个控制器共享时，
     * AE 终端里这块总成的分组名会变成"任取一个控制器"，玩家看不出自己那盘样板归谁；</li>
     * <li><b>推入的原料是"这一件自己的"库存</b>：AE 合成推样板走
     * {@code pushPattern} → {@code patternDetails.pushInputsToExternalInventory(inputHolder, this::add)}，
     * 原料落在这一件自己的 {@code InternalSlot} 库存里，再由多方块的配方逻辑
     * （{@code handleItemInternal} / {@code handleFluidInternal}）从这里取。</li>
     * </ul>
     * 两件控制器都把这件总成收进自己的部件表之后，控制器 A 的合成原料会摆在控制器 B 也能取用的同一个
     * 库存里 —— 谁先跑谁吃掉，这正是「串配方」。
     *
     * <h2>不影响正常用法</h2>
     * <ul>
     * <li><b>「总成当宿主、镜像装在各机器里」</b>（本族的主要用法）不受影响：那时总成压根不在任何
     * 成型的多方块里，{@code isFormed()} 为 false，这道闸门不参与判断；</li>
     * <li>它只挡「同一格方块同时属于两个<b>已成型</b>结构」，同一结构里放两件各自独立的总成照旧允许。</li>
     * </ul>
     *
     * <p>⚠️ 与本项目自己的 ME 库存件一样，这一条同时意味着 tooltip 必须写「禁止共享」
     * （见 {@code ETMEPatternBufferHatches} 里换成 {@code gtceu.part_sharing.disabled}）。
     */
    @Override
    public boolean canShared() {
        return false;
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return terminalPatternInventory;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        // 已成型：沿用父类（控制器名字 + 电路号，或自定义名）
        if (isFormed()) return super.getTerminalGroup();
        // 未成型：父类这一支把图标与名字写死成 GTM 自己的 me_pattern_buffer。
        // 我们这四档常常就是「不组进多方块、只给一堆镜像当宿主」的用法，所以必须显示自己这一档。
        var definition = getDefinition();
        var customName = access().gtet$getCustomName();
        if (!customName.isEmpty()) {
            return new PatternContainerGroup(AEItemKey.of(definition.getItem()), Component.literal(customName),
                    Collections.emptyList());
        }
        return new PatternContainerGroup(AEItemKey.of(definition.getItem()),
                definition.getItem().getDescription(), Collections.emptyList());
    }

    /**
     * 机器 UI：一行与 GTM 的 {@code IFancyUIMachine#createUI} **逐字对应**，只把
     * {@code FancyMachineUIWidget} 换成会"底边贴屏"的子类（见 {@link ETPatternBufferUIWidget}）。
     *
     * <p>为什么必须在这里换、而不能只在 {@code createUIWidget()} 里做：窗口的尺寸与屏幕定位由
     * {@code FancyMachineUIWidget#setupFancyUI} 决定（它按内容算尺寸、再 {@code getGui().setSize()}），
     * 面板控件自己没有屏幕坐标。用户的要求是"面板从物品栏分割线向上/左/右扩"，而"往上长"这件事
     * 在 LDLib 里是**窗口居中**的表现，窗口一高就会上下一起出屏 —— 所以要在窗口这一层兜底。
     * 层级关系（GTM {@code IFancyUIMachine}）：{@code ModularUI.mainGroup} ← 本类（FancyMachineUIWidget）
     * ← {@code pageContainer} ← {@code createUIWidget()} 返回的面板。
     */
    @Override
    public ModularUI createUI(Player entityPlayer) {
        return new ModularUI(176, 166, this, entityPlayer)
                .widget(new ETPatternBufferUIWidget(this, 176, 166));
    }

    /**
     * 样板槽面板：**9 列一块，需要几块就并排几块；不套滚动区，四档全部一眼看全**。
     *
     * <h2>布局规则与四档的实际尺寸</h2>
     * <pre>
     * 每块 = BLOCK_COLUMNS(9) 列 × rows 行；rows = ⌈容量 / (9 × 块数)⌉
     * 块数 = clamp(⌈容量 / (9 × 12)⌉, 1, 2)
     *
     * 档位  容量  块数×行数   面板(宽×高)   整个窗口高 = 面板高 + 8(边框) + 86(玩家物品栏)
     * LuV    27   1 × 3      178 ×  70        164
     * UV     63   1 × 7      178 × 142        236
     * UEV   126   2 × 7      340 × 142        236
     * UXV   216   2 × 12     340 × 232        326
     * </pre>
     * ⚠️ 玩家物品栏那 86 是 LDLib {@code PlayerInventoryWidget} 的**默认尺寸 172×86**
     * （javap 本项目实际编译用的 ldlib deobf jar：构造器里 {@code super(0,0,172,86)}）；
     * 8 是 {@code FancyMachineUIWidget#setupFancyUI} 的 {@code border*2}（border 默认 4）。
     *
     * <h2>为什么这样摆（以及当年为什么会去滚动）</h2>
     * 上一轮把槽位按 ⌈容量/9⌉ 排成**一列**：216 格 = 24 行 = 448px 面板 ⇒ 542px 窗口，
     * 在任何常用 GUI 缩放（1080p 缩放 2 = 540、缩放 3 = 360、自动 = 270）下都放不下，
     * 于是退成了"8 行 + 拖动滚动区"。用户否掉了滚动，并要求"面板向上扩、底边固定在物品栏分割线"。
     * 现在改成：**高度只到 12 行封顶，多出来的容量往左右并块**，于是四档都不需要滚动，
     * 最坏 326px 的窗口在 1080p 缩放 2/3 下都装得下；真的装不下时（更小的窗口或更高的缩放）
     * 由 {@link ETPatternBufferUIWidget} 把窗口下移到底边贴屏，**宁可裁掉最上面几行也不让底部
     * 格子出屏**（用户口径）。宽度封在 2 块 = 340px 的理由见 {@link #MAX_BLOCKS}。
     *
     * <h2>块内怎么排</h2>
     * 每块 9 列、共 rows 行；第 b 块放槽位 {@code [b*9*rows, (b+1)*9*rows)}，块内**先列后行**
     * （与 GTM 面板一致：{@code x = i%9, y = i/9}）。所以槽号沿着一块从上往下、再换到右面一块，
     * 与"总成里的第 N 盘样板"一一对应、不跳号。
     *
     * <h2>兜底（几乎不可达）</h2>
     * 容量若超过 9×12×2 = 216（当前注册表的四档最大就是 216；注册上限 4096 只是防笔误），
     * 块数被 {@link #MAX_BLOCKS} 卡住、行数会超过 12，这里才退回 GTM 那套"可拖动滚动区"，
     * 免得做出一个高得离谱的窗口。这是**防御性**分支，正常游戏里走不到。
     */
    @Override
    public Widget createUIWidget() {
        var inventory = getPatternInventory();
        int capacity = inventory.getSlots();

        int perBlockCapacity = BLOCK_COLUMNS * MAX_ROWS_PER_BLOCK;
        int blocks = Math.max(1, Math.min(MAX_BLOCKS, (capacity + perBlockCapacity - 1) / perBlockCapacity));
        int rows = (capacity + BLOCK_COLUMNS * blocks - 1) / (BLOCK_COLUMNS * blocks);

        int blockWidth = SLOT * BLOCK_COLUMNS;
        int gridWidth = blockWidth * blocks;
        int gridHeight = SLOT * rows;
        boolean scrolling = rows > MAX_ROWS_PER_BLOCK;
        int viewHeight = scrolling ? SLOT * MAX_ROWS_PER_BLOCK : gridHeight;

        int pageWidth = gridWidth + PADDING_X * 2;
        int pageHeight = HEADER + viewHeight + PADDING_BOTTOM;
        var group = new WidgetGroup(0, 0, pageWidth, pageHeight);

        // 顶部：ME 网络状态 + 改名（固定在面板最上一行，不随网格动）
        group.addWidget(new LabelWidget(PADDING_X, 2,
                () -> isOnline() ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        group.addWidget(new AETextInputButtonWidget(pageWidth - PADDING_X - 70, 2, 70, 10)
                .setText(access().gtet$getCustomName())
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(Component.translatable("gui.gtceu.rename.desc")));

        // 网格本体：块数 × (9 列 × rows 行)，格子数正好等于容量
        var grid = new WidgetGroup(0, 0, gridWidth, gridHeight);
        int index = 0;
        for (int b = 0; b < blocks && index < capacity; b++) {
            var block = new WidgetGroup(b * blockWidth, 0, blockWidth, gridHeight);
            for (int y = 0; y < rows && index < capacity; y++) {
                for (int x = 0; x < BLOCK_COLUMNS && index < capacity; x++) {
                    int slotIndex = index++;
                    block.addWidget(new AEPatternViewSlotWidget(inventory, slotIndex, x * SLOT, y * SLOT)
                            .setOccupiedTexture(GuiTextures.SLOT)
                            .setItemHook(stack -> {
                                // 编码样板显示成它的产物（与 GTM 的面板一致）
                                if (!stack.isEmpty() && stack.getItem() instanceof EncodedPatternItem iep) {
                                    ItemStack out = iep.getOutput(stack);
                                    if (!out.isEmpty()) return out;
                                }
                                return stack;
                            })
                            .setChangeListener(() -> access().gtet$onPatternChange(slotIndex))
                            .setBackground(GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY));
                }
            }
            grid.addWidget(block);
        }

        if (scrolling) {
            // 只有容量 > 216 才可能走到（见方法注释「兜底」）：退回 GTM 维护仓那套可拖动滚动区
            group.addWidget(new DraggableScrollableWidgetGroup(PADDING_X, HEADER, gridWidth, viewHeight)
                    .setYScrollBarWidth(4)
                    .setDraggable(false)
                    .addWidget(grid));
        } else {
            grid.setSelfPosition(new Position(PADDING_X, HEADER));
            group.addWidget(grid);
        }
        return group;
    }

    /**
     * 取父类私有成员的桥（{@code customName} 只有 setter；{@code onPatternChange} 是私有的
     * 唯一变更处理入口）。cast 走 {@code Object} 是因为编译期看不见 mixin 加在父类上的接口。
     */
    private IMEPatternBufferAccess access() {
        return (IMEPatternBufferAccess) (Object) this;
    }
}
