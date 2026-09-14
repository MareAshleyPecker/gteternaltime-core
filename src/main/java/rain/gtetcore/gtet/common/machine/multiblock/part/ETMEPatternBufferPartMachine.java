package rain.gtetcore.gtet.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.slot.AEPatternViewSlotWidget;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;

import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
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
 * <li>{@link #createUIWidget()}：父类的面板写死 9×3，换成 9 列 × ⌈容量/9⌉ 行；</li>
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

    /** 面板列数（与 GTM 一致：9 列）。 */
    private static final int COLUMNS = 9;

    /**
     * 面板最多同时显示几行（超过就套可拖动滚动区）。
     *
     * ⚠️ 为什么要有这个上限：GTM 的机器 UI 由 {@code FancyMachineUIWidget} **按内容尺寸撑开**
     * （源码 194-199 行：{@code setSize(max(172, page.width+border*2), ...)} 后还会
     * {@code getGui().setSize(...)}），所以 24 行 = 448px 的面板会把整个 GUI 顶出屏幕
     * （1080p + GUI 缩放 2 时可用高度只有 240px），底部格子既看不见也点不到。
     * 8 行 = 144px 配上标题栏与玩家背包刚好放得下。1 档 3 行、2 档 7 行都**不会**触发滚动，
     * 面板与规格一致；3、4 档各多出的行靠拖动/滚轮查看。
     */
    private static final int VISIBLE_ROWS = 8;

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

    @Override
    public Widget createUIWidget() {
        var inventory = getPatternInventory();
        int capacity = inventory.getSlots();
        // 9 列向上取整：27→3 行、63→7 行、126→14 行、216→24 行
        int rows = (capacity + COLUMNS - 1) / COLUMNS;
        int gridHeight = 18 * rows;
        int viewHeight = Math.min(gridHeight, 18 * VISIBLE_ROWS);

        var group = new WidgetGroup(0, 0, 18 * COLUMNS + 16, viewHeight + 16);

        // 顶部：ME 网络状态 + 改名（固定在顶部，不随网格滚动）
        group.addWidget(new LabelWidget(8, 2,
                () -> isOnline() ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        group.addWidget(new AETextInputButtonWidget(18 * COLUMNS + 8 - 70, 2, 70, 10)
                .setText(access().gtet$getCustomName())
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(Component.translatable("gui.gtceu.rename.desc")));

        // 网格本体：9 列 × rows 行，格子数正好等于容量
        var grid = new WidgetGroup(0, 0, 18 * COLUMNS, gridHeight);
        int index = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                if (index >= capacity) break;
                int slotIndex = index++;
                grid.addWidget(new AEPatternViewSlotWidget(inventory, slotIndex, x * 18, y * 18)
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

        if (gridHeight > viewHeight) {
            // 行数超上限：套可拖动滚动区（同 GTM 的维护仓那块面板的做法），
            // 拖动/滚轮查看被裁掉的行，而不是让面板顶出屏幕
            group.addWidget(new DraggableScrollableWidgetGroup(8, 14, 18 * COLUMNS, viewHeight)
                    .setYScrollBarWidth(4)
                    .setDraggable(false)
                    .addWidget(grid));
        } else {
            grid.setSelfPosition(new Position(8, 14));
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
