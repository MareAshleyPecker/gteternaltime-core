package rain.gtetcore.gtet.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IDropSaveMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import org.jetbrains.annotations.Nullable;
import rain.gtetcore.gtet.integration.ae2.ETTagFilter;
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator;
import rain.gtetcore.gtet.integration.ae2.IMEStockingHost;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * 「ME 标签库存输入总线」：GTM 的 {@code me_stocking_input_bus}（ME 库存输入总线）+ 两条 GTET 自己的策略。
 *
 * <h2>两个模式</h2>
 * <ol>
 * <li><b>标签模式</b>：白名单 / 黑名单两条标签表达式，只有「命中白名单且不命中黑名单」的 item key 才允许被拉进来。
 * 两条都留空 = 不过滤（默认）；判定语义与书写规则见 {@link ETTagFilter}。</li>
 * <li><b>定量模式</b>：{@code batchSize} = 每次从 AE 网络往本仓备多少（0 = 不限制，默认）。
 * 语义是<b>一批一批地备</b>，不是「保底补到 N」——保底那件事 GTM 自己已经有了
 * （{@code min_item_count}，面板见 GTM 的 {@code AutoStockingFancyConfigurator}）。</li>
 * </ol>
 * 两者**互不干涉、可叠加**：标签决定「哪些 key 允许进来」，定量决定「一次备多少」。
 * ⚠️ 但定量与保底会打架：若本仓的「保底数量」比 batchSize 还大，备出来的量永远达不到保底，本仓会一直空着。
 *
 * <h2>为什么走这条路（而不是覆写 GTM 的某个判定方法）</h2>
 * GTOCore（LGPL-3.0）的 {@code METagFilterStockBusPartMachine} 是覆写
 * {@code boolean test(AEKey what)} 实现的，但那个方法**只存在于它自己复制的那份
 * {@code com.gtocore.common.machine.multiblock.part.ae.MEStockingBusPartMachine} 里**
 * （它把 GTM 的这个类整份搬进了自己的包，并把 GTM 的 {@code setAutoPullTest} 覆写成空方法）。
 * 官方 GTM 7.5.3 的 {@code com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine}
 * **没有** {@code test(AEKey)}：全量源码与 {@code javap -p} 的方法表都对不上，
 * 所以 mixin 注入 / Accessor 都无从下手（目标方法不存在，注入会硬失败）。
 *
 * <p>
 * 7.5.3 上能用的公开扩展点是 {@code setAutoPullTest(Predicate<GenericStack>)}，
 * 但它只覆盖 autoPull 模式「选哪些物品填进配置槽」这一步，**管不到取数**。
 * 所以 GTET 三条路一起用，覆盖「配置 → 备货 → 真取数」全链：
 * <table border="1">
 * <caption>把关点</caption>
 * <tr><th>环节</th><th>方法</th><th>标签</th><th>定量</th></tr>
 * <tr><td>autoPull 选配置</td><td>{@code refreshList()}（GTM）</td><td>{@link #installAutoPullTest()}</td><td>—</td></tr>
 * <tr><td>每个周期备货</td><td>{@link #syncME()}（本类覆写）</td><td>✔</td><td>✔（stock ≤ N）</td></tr>
 * <tr><td>真正取数</td><td>{@link ETTagFilterStockItemSlot#extractItem}</td><td>✔（兜底）</td><td>✔（min(amount, 持有量, N)）</td></tr>
 * </table>
 * ⚠️ {@code stock} 全 GTM 只有两处会写：GTM 的 {@code refreshList()} 与本类的 {@code syncME()}，
 * 而 {@code autoIO()} 里的顺序是「先 refreshList 再 syncME」，所以 autoPull 模式下定量上限**最终仍然生效**；
 * 取数点再夹一次是为了不依赖这个时序（玩家刚改完 N 的那一帧也不会超发）。
 *
 * <h2>⚠️ 与 GTM 基类的耦合</h2>
 * {@link #syncME()} 是 GTM 那段逻辑的等价重写（只多了标签判定与定量上限）。GTM 升级后若改了这个方法，
 * 这里必须同步跟改 —— 注释里标了与原版的逐行对应关系。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ETTagFilterStockBusPartMachine extends MEStockingBusPartMachine
                                           implements IMEStockingHost, IDropSaveMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ETTagFilterStockBusPartMachine.class, MEStockingBusPartMachine.MANAGED_FIELD_HOLDER);

    /** 「每次拉 N 个」的可配范围。上限取 1_000_000：物品侧远超任何实际缓冲需求，又让输入框能一眼看全。 */
    public static final int BATCH_MIN = 0;
    public static final int BATCH_MAX = 1_000_000;

    /** 标签判定器：构造一次、按表达式变化与 key 缓存，绝不在每次判定时重新解析字符串。 */
    private final ETTagFilter tagFilter = new ETTagFilter();

    @Persisted
    private String tagWhite = "";
    @Persisted
    private String tagBlack = "";

    /** 每次从网络备货的上限；0 = 不限制（默认）。 */
    @Persisted
    private int batchSize = 0;

    public ETTagFilterStockBusPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        applyTagFilter();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    // ///////////////////////////////
    // ****** 标签过滤（接口实现）*****//
    // ///////////////////////////////

    @Override
    public ETTagFilter getTagFilter() {
        // ⚠️ 每次取用都先用当前那两条原始表达式校准一次，而不是只在 setter 里解析。
        //    原因：@Persisted 字段是 LDLib 用反射**直接写进字段**的（读档、拆方块放回去、以后有人直接赋值），
        //    一个 setter 都不会经过 —— 只在 setter 里解析的话，存档读回之后判定器里还是空表达式，
        //    过滤会**静默失效**（看着有配置、实际不过滤）。
        //    ETTagFilter#set 在两条字符串都没变时第一步就 return，所以这么做没有重复解析的代价。
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
     * 换掉 GTM 的库存列表，好让每个槽都由 {@link ETTagFilterStockItemSlot} 承担取数（见类注释「为什么走这条路」）。
     * <p>
     * ⚠️ 这个方法是**在 {@code super(...)} 构造期间**被调用的，那时本类的字段还没初始化 ——
     * 所以槽只持有 {@code this} 这个视图，绝不在构造期读 {@code batchSize} / {@code tagFilter}。
     */
    @Override
    protected NotifiableItemStackHandler createInventory(Object... args) {
        this.aeItemHandler = new ETTagFilterStockItemList(this, CONFIG_SIZE);
        return this.aeItemHandler;
    }

    @Override
    public void addedToController(IMultiController controller) {
        super.addedToController(controller);
        installAutoPullTest();
    }

    /**
     * 装回自动拉取谓词。
     * <p>
     * ⚠️ 必须在 {@code super.addedToController} **之后**调：GTM 的
     * {@code IMEStockingPart#addedToController} 会把 {@code autoPullTest} 覆盖成「不同仓去重」检查，
     * 构造函数里设的会被它抹掉。这里把「标签放行」与「去重」两条语义组合回去。
     */
    private void installAutoPullTest() {
        setAutoPullTest(tagAutoPullTest(this::testConfiguredInOtherPart));
    }

    // ///////////////////////////////
    // ********** Sync ME *********//
    // ///////////////////////////////

    /**
     * 库存刷新：把「网络上有多少」按标签闸门与定量上限折算成「本仓备多少」，写进 {@code stock}。
     *
     * <p>
     * ⚠️ 与 GTM 的 {@code MEStockingBusPartMachine#syncME()} 逐行等价，只多了三处：
     * ① 网络取不到时直接返回（GTM 原版这里没判空，只在在线时被调）；② 标签闸门；
     * ③ {@code want = min(available, batchLimit())} —— 这就是「每次只备 N 个」。
     *
     * <p>
     * 这一处的标签闸门不是装饰：{@code stock} 就是多方块配方匹配看到的「本仓有多少」，
     * 若把一个不放行的 key 留在 stock 里，配方会以为有料、开起来才发现取不到，于是卡住。
     */
    @Override
    protected void syncME() {
        var grid = getMainNode().getGrid();
        if (grid == null) return;
        MEStorage networkInv = grid.getStorageService().getInventory();
        long batch = batchLimit();

        for (ExportOnlyAEItemSlot slot : this.aeItemHandler.getInventory()) {
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
        configuratorPanel.attachConfigurators(new ETTagFilterConfigurator(this, false));
    }

    // ////////////////////////////////
    // ****** Configuration ******//
    // ////////////////////////////////

    /** 数据棒：在 GTM 原有的配置之外，把标签与定量一起带走。 */
    @Override
    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = super.writeConfigToTag();
        writeTagFilter(tag);
        tag.putInt(NBT_BATCH_SIZE, batchSize);
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        super.readConfigFromTag(tag);
        readTagFilter(tag);
        if (tag.contains(NBT_BATCH_SIZE)) setBatchSize(tag.getInt(NBT_BATCH_SIZE));
    }

    /** 拆方块：标签与定量要能存进掉落物，换位置装回去不丢。 */
    @Override
    public void saveToItem(CompoundTag tag) {
        IDropSaveMachine.super.saveToItem(tag);
        writeTagFilter(tag);
        tag.putInt(NBT_BATCH_SIZE, batchSize);
    }

    @Override
    public void loadFromItem(CompoundTag tag) {
        IDropSaveMachine.super.loadFromItem(tag);
        readTagFilter(tag);
        if (tag.contains(NBT_BATCH_SIZE)) setBatchSize(tag.getInt(NBT_BATCH_SIZE));
    }

    /** 定量上限在数据棒 / 拆方块里的键名。 */
    private static final String NBT_BATCH_SIZE = "ETBatchSize";

    // ///////////////////////////////
    // ******* 库存实现 *************//
    // ///////////////////////////////

    /**
     * GTET 自己的 AE 库存列表：只做一件 GTM 的 private 内部列表做不了的事 —— 让每个槽都是
     * {@link ETTagFilterStockItemSlot}（GTM 的库存槽是 {@code MEStockingBusPartMachine} 的 private 内部类，跨包继承不到）。
     *
     * <p>
     * ⚠️ 槽实例是在这里由构造器通过 slot 工厂造出来的；存档读回时 LDLib 会**复用已有元素**而不是按字段声明类型
     * 重建数组（{@code ArrayAccessor.writeManagedField} 在子访问器非 managed 时走
     * {@code Array.get(array, i)} 再写元素，长度不等则直接抛异常），所以子类行为在重新读档后仍然在。
     */
    public static class ETTagFilterStockItemList extends ExportOnlyAEItemList {

        private final IMEStockingHost host;

        public ETTagFilterStockItemList(IMEStockingHost host, int slots) {
            // ⚠️ 这里的强转是安全的：IMEStockingHost 只由本包的两件库存部件实现，
            //    而它们（经 GTM 的 MEStockingBusPartMachine / MEStockingHatchPartMachine）都是 MetaMachine。
            //    接口上不声明 self() 是因为 GTM 的 IMachineFeature#self() 是另一条接口链上的 default 方法，
            //    两个无关接口的 default 会在实现类上撞成「inherits unrelated defaults」。
            super((MetaMachine) host, slots, () -> new ETTagFilterStockItemSlot(host));
            this.host = host;
        }

        /** 让 GTM 的配置面板把本列表当作「库存列表」画（否则配置槽会被画成可编辑的普通槽）。 */
        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean isAutoPull() {
            return host.isAutoPull();
        }

        /**
         * GTM 自己那份库存列表在这里做的是
         * {@code super.hasStackInConfig(stack, false) || (checkExternal && testConfiguredInOtherPart(stack))}。
         * 我们把库存列表换成了自己的，就必须把同一条语义搬过来 —— 否则面板
         * （{@code AEItemConfigWidget#hasStackInConfig} → {@code AEConfigSlotWidget}）里
         * 「这个物品已经配置在本多方块的另一个库存总成上了」的判断会静默失效。
         *
         * ⚠️ 不能写 {@code IConfigurableSlotList.super.hasStackInConfig(...)}：那个接口在**父类**上，
         * 不在本类的直接 superinterface 列表里，Java 不允许这么限定。所以这里把它的循环照抄一遍。
         */
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
     * GTET 自己的 AE 库存槽：把 GTM {@code MEStockingBusPartMachine.ExportOnlyAEStockingItemSlot#extractItem}
     * 那段 AE 抽取逻辑重写一遍（它在 private 内部类里，跨包拿不到），并在里面加上两道 GTET 自己的闸门。
     */
    public static class ETTagFilterStockItemSlot extends ExportOnlyAEItemSlot {

        private final IMEStockingHost host;

        public ETTagFilterStockItemSlot(IMEStockingHost host) {
            this.host = host;
        }

        public ETTagFilterStockItemSlot(IMEStockingHost host, @Nullable GenericStack config,
                                       @Nullable GenericStack stock) {
            super(config, stock);
            this.host = host;
        }

        /**
         * 从 AE 网络真正取数（GTM 原逻辑）+ 两道闸门。
         *
         * <p>
         * ⚠️ 闸门对**模拟与真实两条路一视同仁**，这是刻意的：GT 的配方匹配用
         * {@code handleRecipe(..., simulate=true)} 走的就是 {@code simulate=true} 这条路，
         * 只卡真实抽取的话配方会「看着有料 → 开起来 → 实际拿不到 → 卡住」。
         */
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || this.stock == null || this.config == null) return ItemStack.EMPTY;

            AEKey key = this.config.what();
            // 闸门一：标签。stock 万一被别处的写法填上，这里也不放行。
            if (!host.getTagFilter().test(key)) return ItemStack.EMPTY;
            // 闸门二：取数上限 = min(调用方要的量, 本仓当前持有量, 每批 N)。
            // ⚠️ 「不超过持有量」这一条是必需的：GTM 原版这里不看 stock，
            //    因为它的 stock 是全网存量（永远够），而定量模式下 stock 会被压到 N，
            //    不夹的话 stock 会被减成负数。
            long limit = Math.min(Math.min(amount, this.stock.amount()), host.batchLimit());
            if (limit <= 0 || !host.isOnline()) return ItemStack.EMPTY;

            var grid = host.getMainNode().getGrid();
            if (grid == null) return ItemStack.EMPTY;
            MEStorage aeNetwork = grid.getStorageService().getInventory();

            Actionable action = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
            long extracted = aeNetwork.extract(key, limit, action, host.getActionSource());
            if (extracted <= 0) return ItemStack.EMPTY;

            ItemStack resultStack = key instanceof AEItemKey itemKey ? itemKey.toStack((int) extracted) :
                    ItemStack.EMPTY;
            if (!simulate) {
                this.stock = ExportOnlyAESlot.copy(this.stock, this.stock.amount() - extracted);
                if (this.stock.amount() <= 0) this.stock = null;
                if (this.onContentsChanged != null) this.onContentsChanged.run();
            }
            return resultStack;
        }

        @Override
        public ETTagFilterStockItemSlot copy() {
            return new ETTagFilterStockItemSlot(host,
                    this.config == null ? null : ExportOnlyAESlot.copy(this.config),
                    this.stock == null ? null : ExportOnlyAESlot.copy(this.stock));
        }
    }
}
