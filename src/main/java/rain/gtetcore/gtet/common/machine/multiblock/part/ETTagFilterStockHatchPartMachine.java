package rain.gtetcore.gtet.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IDropSaveMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.integration.ae2.machine.MEHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;
import com.gregtechceu.gtceu.integration.ae2.utils.AEUtil;

import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import org.jetbrains.annotations.Nullable;
import rain.gtetcore.gtet.integration.ae2.ETTagFilter;
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator;
import rain.gtetcore.gtet.integration.ae2.IMEStockingHost;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * 「ME 标签库存输入仓」：GTM 的 {@code me_stocking_input_hatch}（ME 库存输入仓）+ 标签过滤 + 定量拉取。
 *
 * <p>
 * 与物品版 {@link ETTagFilterStockBusPartMachine} 是同构的两件东西（GTM 自己那两件也是同构的：
 * {@code MEStockingBusPartMachine} / {@code MEStockingHatchPartMachine} 的成员几乎一一对应），
 * 所以这里不再重复说明设计取舍 —— 路线依据（GTM 7.5.3 没有 {@code test(AEKey)}、走公开的
 * {@code setAutoPullTest} + 覆写 {@code syncME} + 自带库存槽）与把关点表格见物品版类注释。
 *
 * <h2>流体侧的两处差异</h2>
 * <ul>
 * <li>取数点是 {@code ExportOnlyAEFluidSlot#drain(int, FluidAction)}（GTM 的库存流体槽覆写的是它），
 * 而不是 {@code extractItem}；{@code drain(FluidStack, FluidAction)} 会转发到它，所以两条路都覆盖到。</li>
 * <li>「每次拉 N 个」的 N 单位是 **mB**（1000 mB = 1 桶），上限 {@code BATCH_MAX} 因此取 1_000_000 mB = 1000 桶。</li>
 * </ul>
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ETTagFilterStockHatchPartMachine extends MEStockingHatchPartMachine
                                             implements IMEStockingHost, IDropSaveMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ETTagFilterStockHatchPartMachine.class, MEStockingHatchPartMachine.MANAGED_FIELD_HOLDER);

    /** 「每次拉 N 个」的可配范围（N 的单位是 mB，上限 1_000_000 mB = 1000 桶）。0 = 不限制。 */
    public static final int BATCH_MIN = 0;
    public static final int BATCH_MAX = 1_000_000;

    /** 标签判定器：构造一次、按表达式变化与 key 缓存。 */
    private final ETTagFilter tagFilter = new ETTagFilter();

    @Persisted
    private String tagWhite = "";
    @Persisted
    private String tagBlack = "";

    /** 每次从网络备货的上限（mB）；0 = 不限制（默认）。 */
    @Persisted
    private int batchSize = 0;

    /**
     * 「允许多方块共享」开关，**默认 false = 隔离**（与本族其他件一致）。
     *
     * <p>
     * ⚠️ 默认值不能改成 true：流体侧的标签 / 定量 / 库存列表同样是每件独立的配置，
     * 两个控制器读同一份 {@code stock} 就是串配方（详见物品版
     * {@link ETTagFilterStockBusPartMachine#canShared()} 的完整说明）。
     *
     * <p>
     * ⚠️ 带 {@code @DescSynced}（理由见物品版同名字段）：开关面板要在客户端读这个值。
     */
    @DescSynced
    @Persisted
    private boolean shareEnabled = false;

    public ETTagFilterStockHatchPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        applyTagFilter();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /**
     * ⚠️ 这一条是 GTM 的不对称处：物品侧 {@code MEBusPartMachine} 有 public 的 {@code getActionSource()}，
     * 流体侧 {@code MEHatchPartMachine} 只有 {@code protected final IActionSource actionSource} 字段、**没有 getter**
     * （javap 两份类的方法表可直接对比）。库存槽需要一个统一视图，所以这里把那个 protected 字段暴露出来。
     */
    @Override
    public IActionSource getActionSource() {
        return actionSource;
    }

    // ////////////////////////////////
    // ***** 仓室隔离（IMultiPart）****//
    // ////////////////////////////////

    /**
     * <b>仓室隔离（玩家可切换）</b>：默认禁止、面板开关打开后放行，返回值就是 {@link #shareEnabled}。
     *
     * <p>
     * 语义、运行时影响（{@code BlockPattern#checkPatternAt} 里
     * {@code isFormed() && !canShared() && !hasController(...)} 那一处判定）、拨动开关两个方向分别发生什么、
     * 以及「为什么默认必须隔离」的完整说明见物品版
     * {@link ETTagFilterStockBusPartMachine#canShared()} —— 流体侧的配置
     * （{@link #tagWhite} / {@link #tagBlack} / {@code batchSize}）同样是每件独立的，
     * 共享会让两个控制器的配方匹配读到同一份 {@code stock}。
     */
    @Override
    public boolean canShared() {
        return shareEnabled;
    }

    @Override
    public boolean canBeShared() {
        return shareEnabled;
    }

    /** 同物品版：服务端改值 + 让本件所属的每个多方块立刻复检结构（⚠️ 先复制再遍历，理由见物品版）。 */
    @Override
    public void setCanBeShared(boolean shared) {
        if (isRemote()) return;
        shareEnabled = shared;
        for (IMultiController controller : List.copyOf(getControllers())) {
            controller.requestCheck();
        }
    }

    // ///////////////////////////////
    // ****** 标签过滤（接口实现）*****//
    // ///////////////////////////////

    @Override
    public ETTagFilter getTagFilter() {
        // ⚠️ 每次取用都先按当前两条原始表达式校准一次（理由见物品版同名方法：@Persisted 是反射直写字段，
        //    读档不经过 setter）。set() 在字符串没变时直接返回，无重复解析代价。
        tagFilter.set(tagWhite, tagBlack);
        return tagFilter;
    }

    @Override
    public String getTagWhite() {
        return tagWhite;
    }

    @Override
    public void setTagWhite(String expression) {
        tagWhite = expression == null ? "" : expression;
        applyTagFilter();
    }

    @Override
    public String getTagBlack() {
        return tagBlack;
    }

    @Override
    public void setTagBlack(String expression) {
        tagBlack = expression == null ? "" : expression;
        applyTagFilter();
    }

    // ///////////////////////////////
    // ******* 定量模式 *************//
    // ///////////////////////////////

    @Override
    public int getBatchSize() {
        return batchSize;
    }

    @Override
    public void setBatchSize(int size) {
        batchSize = Mth.clamp(size, BATCH_MIN, BATCH_MAX);
    }

    // ///////////////////////////////
    // ***** Machine LifeCycle ****//
    // ///////////////////////////////

    /**
     * 换掉 GTM 的流体库存列表（理由见物品版类注释）。
     *
     * ⚠️ 构造期被调用，槽只持有 {@code this} 视图，不在构造期读字段。
     *
     * ⚠️ {@code CONFIG_SIZE} 用 {@link MEHatchPartMachine#CONFIG_SIZE}（= 16，protected）而不是
     * {@code MEStockingHatchPartMachine.CONFIG_SIZE}（那个是 private）；
     * 两者数值一致 —— GTM 给本仓构造时传的就是 {@code MEHatchPartMachine.CONFIG_SIZE}。
     */
    @Override
    protected NotifiableFluidTank createTank(int initialCapacity, int slots, Object... args) {
        this.aeFluidHandler = new ETTagFilterStockFluidList(this, MEHatchPartMachine.CONFIG_SIZE);
        return this.aeFluidHandler;
    }

    @Override
    public void addedToController(IMultiController controller) {
        super.addedToController(controller);
        // ⚠️ 必须在 super 之后：GTM 的 IMEStockingPart#addedToController 会覆盖 autoPullTest
        setAutoPullTest(tagAutoPullTest(this::testConfiguredInOtherPart));
    }

    // ///////////////////////////////
    // ********** Sync ME *********//
    // ///////////////////////////////

    /**
     * 与 GTM 的 {@code MEStockingHatchPartMachine#syncME()} 逐行等价，只多了判空、标签闸门与定量上限
     * （见物品版同名方法的注释）。
     */
    @Override
    protected void syncME() {
        var grid = getMainNode().getGrid();
        if (grid == null) return;
        MEStorage networkInv = grid.getStorageService().getInventory();
        long batch = batchLimit();

        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            GenericStack config = slot.getConfig();
            if (config != null && testTag(config.what())) {
                AEKey key = config.what();
                long available = networkInv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
                long want = Math.min(available, batch);
                if (want >= getMinStackSize()) {
                    slot.setStock(new GenericStack(key, want));
                    continue;
                }
            }
            slot.setStock(null);
        }
    }

    // ///////////////////////////////
    // ********** GUI ***********//
    // ///////////////////////////////

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        configuratorPanel.attachConfigurators(new ETTagFilterConfigurator(this, true));
    }

    // ////////////////////////////////
    // ****** Configuration ******//
    // ////////////////////////////////

    /** 定量上限在数据棒 / 拆方块里的键名。 */
    private static final String NBT_BATCH_SIZE = "ETBatchSize";

    /**
     * 共享开关在数据棒 / 拆方块里的键名。
     *
     * <p>
     * ⚠️ 必须显式写这一对键（理由见物品版 {@code NBT_SHARE}）：{@code @Persisted} 只管方块实体存档，
     * 掉落物走的是 {@code saveToItem} / {@code loadFromItem}。缺键时不动，默认 false = 隔离。
     */
    private static final String NBT_SHARE = "ETShareEnabled";

    @Override
    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = super.writeConfigToTag();
        writeTagFilter(tag);
        tag.putInt(NBT_BATCH_SIZE, batchSize);
        tag.putBoolean(NBT_SHARE, shareEnabled);
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        super.readConfigFromTag(tag);
        readTagFilter(tag);
        if (tag.contains(NBT_BATCH_SIZE)) setBatchSize(tag.getInt(NBT_BATCH_SIZE));
        if (tag.contains(NBT_SHARE)) setCanBeShared(tag.getBoolean(NBT_SHARE));
    }

    @Override
    public void saveToItem(CompoundTag tag) {
        IDropSaveMachine.super.saveToItem(tag);
        writeTagFilter(tag);
        tag.putInt(NBT_BATCH_SIZE, batchSize);
        tag.putBoolean(NBT_SHARE, shareEnabled);
    }

    @Override
    public void loadFromItem(CompoundTag tag) {
        IDropSaveMachine.super.loadFromItem(tag);
        readTagFilter(tag);
        if (tag.contains(NBT_BATCH_SIZE)) setBatchSize(tag.getInt(NBT_BATCH_SIZE));
        if (tag.contains(NBT_SHARE)) setCanBeShared(tag.getBoolean(NBT_SHARE));
    }

    // ///////////////////////////////
    // ******* 库存实现 *************//
    // ///////////////////////////////

    /**
     * GTET 自己的 AE 流体库存列表：只为让每个槽都是 {@link ETTagFilterStockFluidSlot}。
     *
     * <p>
     * ⚠️ 不需要另做什么：{@code ExportOnlyAEFluidList} 构造时会为每个槽建一个
     * {@code FluidStorageDelegate}，而那个委托把 {@code drain} 转发给**我们的槽实例**，
     * 所以配方 / 能力两条取数路都落到 {@link ETTagFilterStockFluidSlot#drain}。
     */
    public static class ETTagFilterStockFluidList extends ExportOnlyAEFluidList {

        private final IMEStockingHost host;

        public ETTagFilterStockFluidList(IMEStockingHost host, int slots) {
            // ⚠️ 强转安全性同物品版（IMEStockingHost 只由本包两件库存部件实现，都是 MetaMachine）
            super((MetaMachine) host, slots, () -> new ETTagFilterStockFluidSlot(host));
            this.host = host;
        }

        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean isAutoPull() {
            return host.isAutoPull();
        }

        /** 同物品版：把 GTM 库存列表 {@code hasStackInConfig(stack, checkExternal)} 的外部检查语义搬过来。 */
        @Override
        public boolean hasStackInConfig(GenericStack stack, boolean checkExternal) {
            if (stack != null && stack.amount() > 0) {
                for (int i = 0; i < getConfigurableSlots(); i++) {
                    GenericStack config = getConfigurableSlot(i).getConfig();
                    if (config != null && config.what().equals(stack.what())) return true;
                }
            }
            return checkExternal && host.testConfiguredInOtherPart(stack);
        }
    }

    /**
     * GTET 自己的 AE 库存流体槽：把 GTM {@code MEStockingHatchPartMachine.ExportOnlyAEStockingFluidSlot#drain}
     * 那段逻辑重写一遍，并加上标签闸门与取数上限。
     */
    public static class ETTagFilterStockFluidSlot extends ExportOnlyAEFluidSlot {

        private final IMEStockingHost host;

        public ETTagFilterStockFluidSlot(IMEStockingHost host) {
            this.host = host;
        }

        public ETTagFilterStockFluidSlot(IMEStockingHost host, @Nullable GenericStack config,
                                         @Nullable GenericStack stock) {
            super(config, stock);
            this.host = host;
        }

        /** 真正的取数点（{@code drain(FluidStack, FluidAction)} 会转发到这里）。 */
        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (this.stock == null || this.config == null) return FluidStack.EMPTY;
            if (!(this.stock.what() instanceof AEFluidKey fluidKey)) return FluidStack.EMPTY;

            // 闸门一：标签
            if (!host.getTagFilter().test(this.config.what())) return FluidStack.EMPTY;
            // 闸门二：取数上限 = min(调用方要的量, 本仓当前持有量, 每批 N)
            long limit = Math.min(Math.min(maxDrain, this.stock.amount()), host.batchLimit());
            if (limit <= 0 || !host.isOnline()) return FluidStack.EMPTY;

            var grid = host.getMainNode().getGrid();
            if (grid == null) return FluidStack.EMPTY;
            MEStorage aeNetwork = grid.getStorageService().getInventory();

            Actionable actionable = action.simulate() ? Actionable.SIMULATE : Actionable.MODULATE;
            long extracted = aeNetwork.extract(fluidKey, limit, actionable, host.getActionSource());
            if (extracted <= 0) return FluidStack.EMPTY;

            FluidStack resultStack = AEUtil.toFluidStack(fluidKey, extracted);
            if (action.execute()) {
                this.stock = ExportOnlyAESlot.copy(this.stock, this.stock.amount() - extracted);
                if (this.stock.amount() <= 0) this.stock = null;
                if (this.onContentsChanged != null) this.onContentsChanged.run();
            }
            return resultStack;
        }

        @Override
        public ETTagFilterStockFluidSlot copy() {
            return new ETTagFilterStockFluidSlot(host,
                    this.config == null ? null : ExportOnlyAESlot.copy(this.config),
                    this.stock == null ? null : ExportOnlyAESlot.copy(this.stock));
        }
    }
}
