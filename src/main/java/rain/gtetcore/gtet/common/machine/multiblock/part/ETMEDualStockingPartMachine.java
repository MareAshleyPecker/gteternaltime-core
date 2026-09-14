package rain.gtetcore.gtet.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AEFluidConfigWidget;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AEItemConfigWidget;
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.multiblock.IMEStockingPart;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;
import rain.gtetcore.gtet.integration.ae2.ETTagFilter;
import rain.gtetcore.gtet.integration.ae2.ETTagFilterConfigurator;
import rain.gtetcore.gtet.integration.ae2.IMEStockingHost;

import java.util.Comparator;
import java.util.PriorityQueue;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * 「ME 二合一库存输入总成」：**一个方块**同时挂 {@code IMPORT_ITEMS} 与 {@code IMPORT_FLUIDS}，
 * 物品与流体**都走库存拉取**（不是把两个现成部件拼在一起），并且两侧**各带一套**标签白/黑名单 + 定量拉取。
 *
 * <h2>为什么这么组合（而不是照抄一份 500+ 行的二合一实现）</h2>
 * 另一个同类「二合一仓」的实现（BetterGregTechAndAppliedEnergistics，**GPL-3.0**，
 * 本项目是 **LGPL-3.0**）是**一份 600 行上下的单类**：自己继承物品总线、自己重写
 * {@code refreshList} / {@code syncME} / 四份 {@code ExportOnlyAE*} 内部类 / 数据棒 / 拆方块。
 * 本项目**只借鉴那个形状**（"机器本身继承物品侧那一支 + 流体侧另起一个列表 + 一块挂两种能力"），
 * 代码自己写，并且把能省的都省掉：
 * <ul>
 * <li><b>物品侧整支继承</b> {@link ETTagFilterStockBusPartMachine}（第一批已提交的「ME 标签库存输入总线」）——
 * 库存列表、库存槽、标签过滤、定量拉取、{@code syncME}、自动拉取、数据棒、拆方块、物品侧面板全部现成；</li>
 * <li><b>流体侧的库存槽不重写</b>：直接复用第一批的
 * {@link ETTagFilterStockHatchPartMachine.ETTagFilterStockFluidSlot}（它只依赖
 * {@link IMEStockingHost}，从不把宿主强转成机器，所以可以喂给它一个**只认流体配置的适配视图**）；</li>
 * <li>所以本类里**没有一行 AE 抽取逻辑**，只有「流体侧的列表 / 备货 / 自动配置 / 面板 / 存档」这些管道。</li>
 * </ul>
 * 代价（已实测，不是估计）：新代码约 430 行，其中机器约 330 行；**既有的标签过滤两件与样板总成零改动**。
 * 若照抄那份 GPL 实现，除了许可证问题，还要多背 4 份 {@code ExportOnlyAE*} 内部类与两套面板同步逻辑。
 *
 * <h2>两侧怎么共用一套「库存宿主机」</h2>
 * {@link IMEStockingHost} 只描述**一侧**（一套标签 + 一个定量上限 + 几个 AE 句柄），
 * 而库存槽又是**跨包拿不到的 private 内部类**（只能靠第一批自己写的槽子类），所以两边这样接：
 * <ul>
 * <li><b>物品侧</b>：宿主就是本机器自己 —— 本机器继承的那一支已经把 {@code getTagFilter()} /
 * {@code getBatchSize()} 实现成「物品侧那一套」（父类的 {@code tagWhite/tagBlack/batchSize}），
 * 于是父类的 {@link ETTagFilterStockBusPartMachine.ETTagFilterStockItemList} 直接拿来用；</li>
 * <li><b>流体侧</b>：宿主是 {@link FluidSideView} —— 一个把 AE 句柄转发给本机器、把标签/定量指向
 * 本机器**流体字段**的适配视图；机器本身仍然以 {@code MetaMachine} 的身份传给列表构造器
 * （列表要它来挂 trait）。</li>
 * </ul>
 *
 * <h2>两侧各一套配置</h2>
 * 物品侧：{@code tagWhite/tagBlack/batchSize}（父类字段，{@code @Persisted}）。
 * 流体侧：{@link #fluidTagWhite} / {@link #fluidTagBlack} / {@link #fluidBatchSize}（本类字段，{@code @Persisted}）。
 * ⚠️ 两侧的字段都是 {@code @Persisted}，而 LDLib 是**反射直写字段**的（读档、拆方块放回去都不走 setter），
 * 所以判定器一律在 getter 里按当前字符串校准（见 {@link #getFluidTagFilter()} 与父类同名方法）。
 *
 * <h2>⚠️ 与 GTM 基类耦合的六处（GTM 升级后要跟着看）</h2>
 * <ol>
 * <li>{@link #createInventory} —— 在父类造完物品侧列表后**顺手造流体侧列表**（这是唯一能在构造期挂 trait 的位置）；</li>
 * <li>{@link #addedToController} —— 必须在 {@code super} **之后**重装自动拉取谓词，否则被
 * {@code IMEStockingPart} 的默认实现顶掉（父类已经中过一次这个坑）；</li>
 * <li>{@link #autoIO} —— GTM 的 {@code refreshList()} 是 private 且只填物品侧，流体侧的自动配置
 * **必须抢在 {@code super.autoIO()} 之前**跑，否则流体侧要晚一整个周期才备上货；</li>
 * <li>{@link #syncME} —— {@code super} 管物品侧，本类补流体侧；</li>
 * <li>{@link #testConfiguredInOtherPart} / {@link #validateConfig} —— {@code IMEStockingPart} 只认**一个**
 * {@code getSlotList()}（物品侧），流体侧的去重与校验要自己补；</li>
 * <li>{@link #writeConfigToTag} / {@link #readConfigFromTag} —— 数据棒那两个入口在 GTM 里是
 * {@code final}（{@code MEInputBusPartMachine#onDataStickUse}），改不了；但它们都走这两个 protected 方法，
 * 所以流体配置挂在这里就能随数据棒与拆方块一起走。</li>
 * </ol>
 *
 * <h2>没做的事</h2>
 * <ul>
 * <li>「保底数量」({@code minStackSize}) 与「周期」({@code ticksPerCycle}) <b>两侧共用一份</b>：
 * 它们由 GTM 自带的 {@code AutoStockingFancyConfigurator} 编辑，而那个面板只认一个
 * {@link IMEStockingPart}。要分两侧就得自己写一块面板（本轮不做，故不假装支持）。</li>
 * <li>物品侧那块标签面板复用父类挂上的那份（标题是共用的「标签过滤」、没标侧别），
 * 流体侧这块的标题带「流体侧」字样 —— 见 {@link #LANG_TITLE_FLUIDS} 与
 * {@link #SideConfigurator}。刻意**不**重写 {@code attachConfigurators} 的全套
 * （那样会把 GTM 的电路槽 / 去重开关 / 库存保底面板的挂载逻辑复制一遍，GTM 一改就漂）。</li>
 * </ul>
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ETMEDualStockingPartMachine extends ETTagFilterStockBusPartMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ETMEDualStockingPartMachine.class, ETTagFilterStockBusPartMachine.MANAGED_FIELD_HOLDER);

    /** 流体侧面板标题键（物品侧那块沿用共用键「标签过滤」，所以这里必须标清侧别）。 */
    public static final String LANG_TITLE_FLUIDS = "gtetcore.machine.et_tag_filter.title.fluids";

    /**
     * 流体侧库存列表（16 个配置槽，与物品侧、与 GTM 的库存件一致）。
     *
     * <p>
     * ⚠️ **非 final、且在 {@link #createInventory} 里赋值**：那个方法是在 {@code super(...)} 构造链里被调的，
     * 那时本类的字段初始化器还没跑；而 Java 的「隐式置空」发生在对象分配时、早于所有构造器，
     * 所以不带初始化器的字段在这里赋的值**不会被随后的字段初始化器覆盖**。
     * （GTM 的 {@code MEInputHatchPartMachine.aeFluidHandler} 与那份 GPL 二合一实现都是这个写法。）
     */
    @Persisted
    protected ExportOnlyAEFluidList aeFluidHandler;

    /** 流体侧宿主视图；同 {@link #aeFluidHandler}，在构造链里赋值。 */
    private FluidSideView fluidSide;

    /** 流体侧标签判定器：构造一次、按表达式变化与 key 缓存。 */
    private final ETTagFilter fluidTagFilter = new ETTagFilter();

    @Persisted
    private String fluidTagWhite = "";
    @Persisted
    private String fluidTagBlack = "";

    /** 流体侧「每次拉 N 个」的 N，单位 mB；0 = 不限制（默认，与物品侧一致）。 */
    @Persisted
    private int fluidBatchSize = 0;

    public ETMEDualStockingPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /**
     * <b>仓室隔离</b>：显式再写一遍 {@code false}。
     *
     * <p>
     * 行为上父类 {@link ETTagFilterStockBusPartMachine#canShared()} 已经是 {@code false}，这一份是**冗余**的，
     * 但本件比单侧件更需要它：一块方块同时挂在 {@code IMPORT_ITEMS} 与 {@code IMPORT_FLUIDS} 两条能力链上，
     * 两侧各有一套标签 / 定量 / 库存列表（物品侧在父类字段、流体侧在本类字段），
     * 一旦被两个多方块共享，两个控制器会同时读**同一份两侧配置**，串配方比单侧件更严重。
     * 显式写出来是为了：以后若有人重构父类（例如把隔离逻辑挪走、或让父类改成 {@code true}），
     * 本件不会**静默**失去隔离。语义与运行时影响见父类同名方法的注释。
     */
    @Override
    public boolean canShared() {
        return false;
    }

    // ///////////////////////////////
    // ***** Machine LifeCycle ****//
    // ///////////////////////////////

    /**
     * 造库存列表：父类造物品侧那份，这里**再顺手造流体侧那份**。
     *
     * <p>
     * ⚠️ 为什么在这里而不是构造器里：{@code MetaMachine} 的 trait 只能在机器创建期挂
     * （{@code MachineTrait} 的构造器自己调 {@code machine.attachTraits(this)}，而
     * {@code MetaMachine#attachTraits} 的注释写明「不可以在运行期动态加」），
     * 而这个方法是构造链里唯一一处、且父类已经在这里造过物品侧。
     * <p>
     * ⚠️ trait 的**加入顺序**有后果：{@code MultiblockPartMachine#getHandlerList()} 把
     * **第一个** recipe-handler trait 的 IO 当作整张处理器表的 IO，物品侧（{@code IO.IN}）必须先加，
     * 流体侧后加 —— 顺序正好就是这里「先 super、后流体」。这也正是 GTM 自己
     * 物品总线（{@code inventory} 先于 {@code circuitInventory}）的排法。
     */
    @Override
    protected NotifiableItemStackHandler createInventory(Object... args) {
        NotifiableItemStackHandler items = super.createInventory(args);
        this.fluidSide = new FluidSideView(this);
        // ⚠️ CONFIG_SIZE 取父类链上 MEInputBusPartMachine 的（protected，可继承）；
        //    MEHatchPartMachine.CONFIG_SIZE 是别的包里的 protected，跨包取不到，数值同为 16。
        this.aeFluidHandler = new DualFluidList(this, CONFIG_SIZE, this.fluidSide);
        return items;
    }

    @Override
    public void addedToController(IMultiController controller) {
        super.addedToController(controller);
        // ⚠️ 必须在 super 之后：父类会把 autoPullTest 装成「物品侧标签 ∧ 去重」，
        //    这里换成「按 key 类型分派到两侧标签 ∧ 去重」，否则流体侧自动拉取会不看标签。
        setAutoPullTest(this::autoPullAllows);
    }

    /**
     * 自动拉取（screwdriver / 自动拉取按钮打开时）选配置槽的放行判定。
     *
     * <p>
     * {@code autoPullTest} 只有一个（GTM 的字段是 private 且只有 setter），所以**按 key 的类型分派**：
     * 物品 key 走物品侧标签，流体 key 走流体侧标签；两边的「别的库存件没配过」语义都保留。
     */
    private boolean autoPullAllows(GenericStack stack) {
        AEKey what = stack.what();
        boolean tagged = what instanceof AEFluidKey ? fluidSide.testTag(what) : testTag(what);
        return tagged && !testConfiguredInOtherPart(stack);
    }

    @Override
    public void removedFromController(IMultiController controller) {
        super.removedFromController(controller);
        // ⚠️ GTM 的 IMEStockingPart#removedFromController 只会清 getSlotList()（物品侧那份），流体侧要自己补
        if (isAutoPull() && aeFluidHandler != null) aeFluidHandler.clearInventory(0);
    }

    /**
     * 配置去重校验。
     *
     * <p>
     * ⚠️ 必须整个覆写：{@code IMEStockingPart} 只声明了**一个** {@code getSlotList()}（= 物品侧），
     * 所以接口的默认实现管不到流体侧 —— 同一个多方块里另一件库存仓配过的流体会被留在配置里。
     * <p>
     * ⚠️ 不能写 {@code IMEStockingPart.super.validateConfig()}：javac 只允许在「该接口是直接超接口
     * 且方法不是从超类继承来的」时用这种限定调用，而这里的接口是由**超类**实现的
     * （报错原文：the interface IMEStockingPart is extended by ETTagFilterStockBusPartMachine）。
     * 所以这里把接口默认实现那段循环搬过来，两侧各跑一遍。
     */
    @Override
    public void validateConfig() {
        clearDuplicateConfigs(getSlotList());
        clearDuplicateConfigs(aeFluidHandler);
    }

    /** 把「已经配置在同一多方块的另一个库存件上」的槽清掉（GTM 的 {@code IMEStockingPart#validateConfig} 循环体）。 */
    private void clearDuplicateConfigs(IConfigurableSlotList list) {
        for (int i = 0; i < list.getConfigurableSlots(); i++) {
            IConfigurableSlot slot = list.getConfigurableSlot(i);
            GenericStack config = slot.getConfig();
            if (config != null && testConfiguredInOtherPart(config)) {
                slot.setConfig(null);
                slot.setStock(null);
            }
        }
    }

    /**
     * 「该配置是否已经在同一个多方块的另一个库存件上」。
     *
     * <p>
     * ⚠️ 物品侧交给 GTM 的物品版实现（它会跳过 distinct 模式、只看别件物品总成）；
     * 流体配置它**看不见**（那套只扫 {@code MEStockingBusPartMachine} 的物品列表），所以流体要自己扫一遍。
     * 扫描语义与 GTM 的 {@code MEStockingHatchPartMachine#testConfiguredInOtherPart} 一致
     * —— 注意流体那版与物品版是**不对称**的：流体版**不**判 distinct，这里照做，不擅自"修好"它。
     */
    @Override
    public boolean testConfiguredInOtherPart(@Nullable GenericStack config) {
        if (config == null) return false;
        if (config.what() instanceof AEFluidKey) return testFluidConfiguredInOtherPart(config);
        return super.testConfiguredInOtherPart(config);
    }

    /** 流体侧的 {@link #testConfiguredInOtherPart}：扫别的库存输入仓，以及别的二合一件的流体列表。 */
    private boolean testFluidConfiguredInOtherPart(GenericStack config) {
        if (!isFormed()) return false;
        for (IMultiController controller : getControllers()) {
            for (IMultiPart part : controller.getParts()) {
                if (part == this) continue;
                if (part instanceof MEStockingHatchPartMachine hatch) {
                    // getSlotList() 是 public 的（IMEStockingPart 声明），不必碰它的 private 字段
                    if (hatch.getSlotList().hasStackInConfig(config, false)) return true;
                } else if (part instanceof ETMEDualStockingPartMachine dual) {
                    if (dual.aeFluidHandler.hasStackInConfig(config, false)) return true;
                }
            }
        }
        return false;
    }

    // ///////////////////////////////
    // ********** Sync ME *********//
    // ///////////////////////////////

    /**
     * 库存刷新：{@code super} 管物品侧（标签 + 定量，父类实现），本类补流体侧（同构的一份）。
     *
     * <p>
     * 调用点全在 GTM 那边（{@code MEStockingBusPartMachine#autoIO} 与
     * {@code MEInputBusPartMachine#autoIO}），本类只覆写不新增调用点，所以两侧天然同周期同步。
     */
    @Override
    protected void syncME() {
        super.syncME();
        syncFluidME();
    }

    /**
     * 流体侧备货：与父类物品侧那份**逐行同构**（GTM 的 {@code MEStockingBusPartMachine#syncME}
     * 与 {@code MEStockingHatchPartMachine#syncME} 本来也是同构的），只多了判空、标签闸门与定量上限。
     *
     * <p>
     * 这一处的标签闸门不是装饰：{@code stock} 就是多方块配方匹配看到的「本仓有多少」，
     * 把一个不放行的 key 留在 stock 里，配方会以为有料、开起来才发现取不到，于是卡住。
     */
    private void syncFluidME() {
        var grid = getMainNode().getGrid();
        if (grid == null) return;
        MEStorage networkInv = grid.getStorageService().getInventory();
        long batch = fluidSide.batchLimit();

        for (ExportOnlyAEFluidSlot slot : aeFluidHandler.getInventory()) {
            GenericStack config = slot.getConfig();
            if (config != null && fluidSide.testTag(config.what())) {
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

    /**
     * 自动配置（autoPull 模式）时，先把**流体侧**的配置槽按「网络上存量最大的若干个」填好。
     *
     * <p>
     * ⚠️ 顺序是本方法存在的唯一理由：GTM 的 {@code refreshList()} 是 private 且只填**物品侧**，
     * 而 {@code super.autoIO()} 内部紧接着就会调 {@code syncME()}。若在 {@code super} **之后**才刷流体配置，
     * 这一次 {@code syncME} 看到的还是旧配置，流体侧要等下一个 {@code ticksPerCycle} 才备上货
     * （默认 40 tick —— 面板上会表现为"流体格空着两秒"）。
     */
    @Override
    public void autoIO() {
        if (isAutoPull() && isWorkingEnabled() && shouldSyncME()) {
            int cycle = getTicksPerCycle();
            // ⚠️ 与 GTM 的 autoIO 一样要挡 0：ticksPerCycle 可能是 0（老存档 / 直接赋值），
            //    GTM 那边是在自己的 autoIO 里补成 updateIntervals，这里不能抢在它前面除零。
            if (cycle != 0 && getOffsetTimer() % cycle == 0) refreshFluidList();
        }
        super.autoIO();
    }

    /**
     * 流体侧的自动配置列表刷新：与 GTM 的 {@code MEStockingHatchPartMachine#refreshList} 同一套算法
     * （取网络上存量最大的 {@code CONFIG_SIZE} 个流体，按存量从大到小倒着填进配置槽），
     * 只把「该不该收这个 key」换成两侧共用的 {@link #autoPullAllows}。
     */
    private void refreshFluidList() {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            aeFluidHandler.clearInventory(0);
            return;
        }

        MEStorage networkStorage = grid.getStorageService().getInventory();
        var counter = networkStorage.getAvailableStacks();

        // 小顶堆：留下存量最大的 CONFIG_SIZE 个
        PriorityQueue<Object2LongMap.Entry<AEKey>> topFluids = new PriorityQueue<>(
                Comparator.comparingLong(Object2LongMap.Entry<AEKey>::getLongValue));

        for (Object2LongMap.Entry<AEKey> entry : counter) {
            long amount = entry.getLongValue();
            AEKey what = entry.getKey();

            if (amount <= 0) continue;
            if (!(what instanceof AEFluidKey fluidKey)) continue;

            long request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource);
            if (request == 0) continue;

            if (!autoPullAllows(new GenericStack(fluidKey, amount))) continue;
            if (amount >= getMinStackSize()) {
                if (topFluids.size() < CONFIG_SIZE) {
                    topFluids.offer(entry);
                } else if (amount > topFluids.peek().getLongValue()) {
                    topFluids.poll();
                    topFluids.offer(entry);
                }
            }
        }

        // poll() 先出最小的，所以从后往前填，面板上就是「多的在上面」
        int index;
        int fluidAmount = topFluids.size();
        for (index = 0; index < CONFIG_SIZE; index++) {
            if (topFluids.isEmpty()) break;
            Object2LongMap.Entry<AEKey> entry = topFluids.poll();
            AEKey what = entry.getKey();

            long request = networkStorage.extract(what, entry.getLongValue(), Actionable.SIMULATE, actionSource);

            var slot = aeFluidHandler.getInventory()[fluidAmount - index - 1];
            slot.setConfig(new GenericStack(what, 1));
            slot.setStock(new GenericStack(what, request));
        }

        aeFluidHandler.clearInventory(index);
    }

    // ///////////////////////////////
    // ********** GUI ***********//
    // ///////////////////////////////

    /**
     * 主页面：上半物品侧配置槽、下半流体侧配置槽（各 8×2，与 GTM 的 ME 部件布局一致）。
     *
     * <p>
     * ⚠️ 尺寸必须显式给：{@code FancyMachineUIWidget} 是拿 {@code page.getSize()} 反推整个界面大小的
     * （{@code size = (max(172, pageW + 8), max(86, pageH + 8))}），GTM 的 ME 部件给
     * {@code new WidgetGroup(new Position(0,0))}（动态尺寸组）也能用，只是因为它的最小尺寸 172×86
     * 刚好装得下一块 144×74 的配置面板。两块就装不下了。
     *
     * <p>
     * ⚠️ 本组是**固定尺寸**组（不是 {@code WidgetGroup(Position)} 那种按子控件撑开的动态组），
     * 所以 {@code PANEL_HEIGHT} 必须**正好等于最后一块配置面板的底边**（{@link #FLUID_BLOCK_Y} + 74 = 168）：
     * 多留 = 主界面底部多一块空白（界面按内容反推，空白会整体变成窗口的一部分），
     * 少留 = 内容画出组外、越到物品栏分割线以下。物品栏分割线固定在「主页面底边 + 4px」（窗口布局里 page
     * 居中于 container，container 高 = page 高 + border*2），所以只要底边给准，内容变多时是**向上长**、
     * 底边不动。
     *
     * <p>
     * ⚠️ 两块之间的间距给 8（原来是 4）：4px 时物品侧那 8×2 槽区的下沿与流体侧槽区的上沿几乎贴在一起，
     * 看着像压在一起。间距**不要**大于 50 —— GTM 的 {@code ConfigWidget} 会在自己上方 50px 处摆一块
     * 80×30 的「数量」浮层（构造器里写死 {@code new AmountSetWidget(31, -50, this)}，占 {@code [顶边-50, 顶边-20]}），
     * 间距一旦小于 50，那块浮层就会落到上面一块面板的槽区里。
     * 好在库存列表 {@code isStocking() == true}（见本类两个列表的实现），GTM 侧左键点配置槽时
     * 那个浮层**不会**弹出来（{@code AEItemConfigSlotWidget#mouseClicked} 与流体版同名方法里
     * {@code enableAmountClient} 都被 {@code !parentWidget.isStocking()} 挡着，服务端 {@code enableAmount}
     * 只改服务端实例的 visible、不会同步给客户端），这里只是把边界写清楚。
     *
     * @see com.gregtechceu.gtceu.integration.ae2.gui.widget.ConfigWidget 每块配置面板 144×74
     */
    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        // ME 网络状态
        group.addWidget(new LabelWidget(3, 0, () -> isOnline() ?
                "gtceu.gui.me_network.online" :
                "gtceu.gui.me_network.offline"));

        // 物品侧配置槽（父类那份列表）
        group.addWidget(new AEItemConfigWidget(3, ITEM_BLOCK_Y, aeItemHandler));
        // 流体侧配置槽
        group.addWidget(new AEFluidConfigWidget(3, FLUID_BLOCK_Y, aeFluidHandler));

        return group;
    }

    // ///////////////////////////////
    // ********** 主页面尺寸 *******//
    // ///////////////////////////////

    /** 主页面宽度：两块配置面板各 144 宽、左边距 3 → 内容 147；留到 150，窗口最小宽 172 依旧由 GTM 兜底。 */
    private static final int PANEL_WIDTH = 150;
    /** 物品侧配置面板的 Y（在上面；下面那块变大时它向上让位）。 */
    private static final int ITEM_BLOCK_Y = 12;
    /** 流体侧配置面板的 Y：物品块底边（12 + 74 = 86）再留 8px 间距。 */
    private static final int FLUID_BLOCK_Y = 94;
    /**
     * 主页面高度 = 流体块底边（94 + GTM 配置面板的 74）—— 别改成别的数，
     * 理由见 {@link #createUIWidget()} 上那两条 ⚠️。
     */
    private static final int PANEL_HEIGHT = FLUID_BLOCK_Y + 74;

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        // 父类一行把该有的都挂上了：自动拉取按钮、去重开关、电路槽、GTM 的库存保底面板、物品侧的标签面板
        super.attachConfigurators(configuratorPanel);
        // 再补流体侧那块标签面板（标题带「流体侧」，用来和上面那块没标侧别的区分）
        configuratorPanel.attachConfigurators(new SideConfigurator(fluidSide, true, LANG_TITLE_FLUIDS));
    }

    /**
     * 复用第一批那块「标签过滤 + 定量拉取」面板（[借鉴形状] GTOCore，LGPL-3.0），只换标题。
     *
     * <p>
     * ⚠️ 为什么不改 {@link ETTagFilterConfigurator} 的标题键：那个键是**三件部件共用**的
     * （两件单独的标签库存件 + 本件），改了会连带把它们的面板标题也改掉，
     * 而本件的目标只是「在同一个界面里把两侧分开」。
     */
    private static final class SideConfigurator extends ETTagFilterConfigurator {

        private final String titleKey;

        SideConfigurator(IMEStockingHost host, boolean fluid, String titleKey) {
            super(host, fluid);
            this.titleKey = titleKey;
        }

        @Override
        public Component getTitle() {
            return Component.translatable(titleKey);
        }
    }

    // ////////////////////////////////
    // ****** Configuration ******//
    // ////////////////////////////////

    /** 流体侧配置（标签两条 + 定量上限 + 全部配置槽）在 NBT 里的键名。 */
    private static final String NBT_FLUID_TAG_WHITE = "ETFluidTagWhite";
    private static final String NBT_FLUID_TAG_BLACK = "ETFluidTagBlack";
    private static final String NBT_FLUID_BATCH_SIZE = "ETFluidBatchSize";
    private static final String NBT_FLUID_CONFIGS = "ETFluidConfigStacks";

    /**
     * 数据棒：GTM 的物品版在这里写「AutoPull / GhostCircuit / 各配置槽」，父类又补了物品侧标签与定量，
     * 本类再补流体侧。
     *
     * <p>
     * ⚠️ 数据棒的两个入口（{@code onDataStickShiftUse} / {@code onDataStickUse}）在 GTM 里是 **final**，
     * 改不了；但两个都走本方法 / {@link #readConfigFromTag}，所以流体配置照样能随数据棒走。
     */
    @Override
    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = super.writeConfigToTag();
        // autoPull 模式下流体配置是自动填的，跟 GTM 对物品侧的处理一样不存（存了也会被下次刷新覆盖）
        if (!isAutoPull()) writeFluidSide(tag);
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        super.readConfigFromTag(tag);
        readFluidSide(tag);
    }

    /** 拆方块：流体侧配置要能存进掉落物，换位置装回去不丢。 */
    @Override
    public void saveToItem(CompoundTag tag) {
        super.saveToItem(tag);
        writeFluidSide(tag);
    }

    @Override
    public void loadFromItem(CompoundTag tag) {
        super.loadFromItem(tag);
        readFluidSide(tag);
    }

    /** 把流体侧的两条表达式、定量上限与全部配置槽写进给定 tag。 */
    private void writeFluidSide(CompoundTag tag) {
        tag.putString(NBT_FLUID_TAG_WHITE, fluidTagWhite);
        tag.putString(NBT_FLUID_TAG_BLACK, fluidTagBlack);
        tag.putInt(NBT_FLUID_BATCH_SIZE, fluidBatchSize);
        CompoundTag configs = new CompoundTag();
        tag.put(NBT_FLUID_CONFIGS, configs);
        for (int i = 0; i < aeFluidHandler.getConfigurableSlots(); i++) {
            GenericStack config = aeFluidHandler.getConfigurableSlot(i).getConfig();
            if (config != null) configs.put(Integer.toString(i), GenericStack.writeTag(config));
        }
    }

    /** 读回流体侧配置；缺键就不动（老存档 / 没存过的数据棒）。 */
    private void readFluidSide(CompoundTag tag) {
        if (tag.contains(NBT_FLUID_TAG_WHITE)) setFluidTagWhite(tag.getString(NBT_FLUID_TAG_WHITE));
        if (tag.contains(NBT_FLUID_TAG_BLACK)) setFluidTagBlack(tag.getString(NBT_FLUID_TAG_BLACK));
        if (tag.contains(NBT_FLUID_BATCH_SIZE)) setFluidBatchSize(tag.getInt(NBT_FLUID_BATCH_SIZE));
        if (tag.contains(NBT_FLUID_CONFIGS)) {
            CompoundTag configs = tag.getCompound(NBT_FLUID_CONFIGS);
            for (int i = 0; i < aeFluidHandler.getConfigurableSlots(); i++) {
                String key = Integer.toString(i);
                aeFluidHandler.getConfigurableSlot(i)
                        .setConfig(configs.contains(key) ? GenericStack.readTag(configs.getCompound(key)) : null);
            }
        }
    }

    // ///////////////////////////////
    // ***** 流体侧宿主视图 *******//
    // ///////////////////////////////

    /**
     * 判定器取用：**每次取用都先按当前那两条原始表达式校准一次**。
     *
     * <p>
     * ⚠️ 理由与父类同名方法一样：{@code @Persisted} 字段是 LDLib 用反射**直接写进字段**的
     * （读档、拆方块放回去、以后有人直接赋值），一个 setter 都不会经过 —— 只在 setter 里解析的话，
     * 存档读回之后判定器里还是空表达式，过滤会**静默失效**（看着有配置、实际不过滤）。
     * {@code ETTagFilter#set} 在两条字符串都没变时第一步就 return，所以这么做没有重复解析的代价。
     */
    ETTagFilter getFluidTagFilter() {
        fluidTagFilter.set(fluidTagWhite, fluidTagBlack);
        return fluidTagFilter;
    }

    String getFluidTagWhite() {
        return fluidTagWhite;
    }

    void setFluidTagWhite(@Nullable String expression) {
        fluidTagWhite = expression == null ? "" : expression;
    }

    String getFluidTagBlack() {
        return fluidTagBlack;
    }

    void setFluidTagBlack(@Nullable String expression) {
        fluidTagBlack = expression == null ? "" : expression;
    }

    int getFluidBatchSize() {
        return fluidBatchSize;
    }

    void setFluidBatchSize(int size) {
        // 上下限沿用物品侧那两个常量（0 = 不限制），两侧一致才好解释
        fluidBatchSize = Mth.clamp(size, BATCH_MIN, BATCH_MAX);
    }

    /**
     * 流体侧的库存宿主视图：AE 句柄转发给机器本身，标签与定量指向机器的流体字段。
     *
     * <p>
     * ⚠️ 必须是 {@code static} 嵌套类并自己持有机器引用：它在**构造链里**就被创建
     * （见 {@link #createInventory}），此时用非静态内部类捕获 {@code this} 虽然也能编译，
     * 但一旦构造器里碰到未初始化的字段就会拿到默认值 —— 拆成显式引用后，这里只保存引用、不读字段。
     */
    private static final class FluidSideView implements IMEStockingHost {

        private final ETMEDualStockingPartMachine machine;

        FluidSideView(ETMEDualStockingPartMachine machine) {
            this.machine = machine;
        }

        @Override
        public ETTagFilter getTagFilter() {
            return machine.getFluidTagFilter();
        }

        @Override
        public String getTagWhite() {
            return machine.getFluidTagWhite();
        }

        @Override
        public void setTagWhite(@Nullable String expression) {
            machine.setFluidTagWhite(expression);
        }

        @Override
        public String getTagBlack() {
            return machine.getFluidTagBlack();
        }

        @Override
        public void setTagBlack(@Nullable String expression) {
            machine.setFluidTagBlack(expression);
        }

        @Override
        public boolean isOnline() {
            return machine.isOnline();
        }

        @Override
        public IManagedGridNode getMainNode() {
            return machine.getMainNode();
        }

        @Override
        public IActionSource getActionSource() {
            return machine.getActionSource();
        }

        @Override
        public boolean isAutoPull() {
            return machine.isAutoPull();
        }

        @Override
        public int getBatchSize() {
            return machine.getFluidBatchSize();
        }

        @Override
        public void setBatchSize(int size) {
            machine.setFluidBatchSize(size);
        }

        @Override
        public boolean testConfiguredInOtherPart(@Nullable GenericStack config) {
            return machine.testConfiguredInOtherPart(config);
        }
    }

    // ///////////////////////////////
    // ******* 库存实现 *************//
    // ///////////////////////////////

    /**
     * 流体侧库存列表：只为把槽换成本 mod 自己的
     * {@link ETTagFilterStockHatchPartMachine.ETTagFilterStockFluidSlot}（配方/能力两条取数路都落到它）。
     *
     * <p>
     * ⚠️ 为什么不直接用第一批的 {@code ETTagFilterStockFluidList}：它的构造器要把宿主同时当
     * {@code MetaMachine}（拿去挂 trait）用，而流体侧的宿主是 {@link FluidSideView}（不是机器）。
     * 这里把两件事拆开 —— 机器当 {@code MetaMachine}、视图当 {@code IMEStockingHost}，
     * 于是槽本身**一行都不用重写**。
     */
    public static class DualFluidList extends ExportOnlyAEFluidList {

        private final IMEStockingHost side;

        public DualFluidList(MetaMachine machine, int slots, IMEStockingHost side) {
            super(machine, slots, () -> new ETTagFilterStockHatchPartMachine.ETTagFilterStockFluidSlot(side));
            this.side = side;
        }

        /** 让 GTM 的配置面板把本列表当作「库存列表」画（否则槽会被画成可编辑的普通槽）。 */
        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean isAutoPull() {
            return side.isAutoPull();
        }

        /** 把 GTM 库存列表 {@code hasStackInConfig(stack, checkExternal)} 的外部检查语义搬过来。 */
        @Override
        public boolean hasStackInConfig(GenericStack stack, boolean checkExternal) {
            if (super.hasStackInConfig(stack, false)) return true;
            return checkExternal && side.testConfiguredInOtherPart(stack);
        }
    }
}
