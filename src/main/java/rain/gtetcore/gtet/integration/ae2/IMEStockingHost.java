package rain.gtetcore.gtet.integration.ae2;

import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

/**
 * 「本 mod 的 ME 库存部件」给**库存槽**看的那一面：标签判定器 + 每次拉取上限 + 三样 AE 侧句柄。
 *
 * <h2>为什么要单独一个接口</h2>
 * 库存槽必须自己完成 AE 抽取（GTM 把那段逻辑写在 {@code MEStockingBusPartMachine} 的
 * <b>private 内部类</b> 里，跨包继承不到，只能自己写一份），而抽取要用到
 * {@code isOnline()} / {@code getMainNode()} / {@code getActionSource()}；
 * 这三个在 GTM 侧本来就是 public（{@code MEBusPartMachine} 与 {@code MEHatchPartMachine} 都有），
 * 所以本接口只是把它们与 GTET 自己的两个配置项（标签、每批上限）打包成一个槽能持有的视图，
 * 免得槽去依赖具体的机器类型。
 *
 * <p>
 * ⚠️ **不要在构造期读 {@link #getBatchSize()}**：库存是在 {@code super(...)} 构造期间由
 * {@code createInventory} / {@code createTank} 造出来的，那时子类的字段还没初始化。
 * 本接口的实现方（部件类）只在运行期（{@code syncME} / 取数）读它，不受影响。
 *
 * @author rain fox
 */
public interface IMEStockingHost extends IETTagFilterPart {

    /** 本部件连的 AE 网络节点是否在线。 */
    boolean isOnline();

    /** AE 网络节点（GTM 的 {@code MEBusPartMachine#getMainNode()} / {@code MEHatchPartMachine#getMainNode()}）。 */
    IManagedGridNode getMainNode();

    /** AE 抽取用的动作来源（GTM 的 {@code getActionSource()}）。 */
    IActionSource getActionSource();

    /**
     * 是否处于自动拉取模式（GTM 的 {@code IAutoPullPart#isAutoPull()}）。
     *
     * <p>
     * 库存列表要用它回答 GTM 配置面板的 {@code isAutoPull()}（只在 GUI 上用：autoPull 模式下配置槽是只读的显示位）。
     */
    boolean isAutoPull();

    /**
     * 「每次拉 N 个」的 N。**0 = 不限制**（默认，行为与 GTM 原版库存部件完全一致）。
     *
     * <p>
     * 语义是**本仓一次从网络里备多少**，不是「保底补到 N」——保底那件事 GTM 已经做了
     * （{@code min_item_count} / {@code min_fluid_count}，见 GTM 的 {@code AutoStockingFancyConfigurator}）。
     * 所以两者会互相影响：⚠️ 若 N 小于保底数量，本仓永远备不满（备出来的量达不到保底，{@code syncME} 会把 stock 清空），
     * 面板提示里写明了这一点。
     */
    int getBatchSize();

    /** 换 N（0 = 不限制）。 */
    void setBatchSize(int size);

    /**
     * 该 stack 是否已经配置在**同一个多方块里的另一个库存部件**上（GTM 的
     * {@code MEStockingBusPartMachine#testConfiguredInOtherPart} / {@code MEStockingHatchPartMachine} 同名方法，两者都是 public）。
     *
     * <p>
     * 库存列表要用它补上 GTM 自己那份列表的 {@code hasStackInConfig(stack, true)} 语义（见两件列表的覆写）：
     * GTM 的库存列表是 private 内部类，我们换掉了它，就得把这条语义一起搬过来，否则面板上
     * 「这个物品已经配置在别的库存件上了」的判断会静默失效。
     */
    boolean testConfiguredInOtherPart(@Nullable GenericStack config);

    /** {@link #getBatchSize()} 的 long 形式：0 翻成「无限」。 */
    default long batchLimit() {
        int size = getBatchSize();
        return size <= 0 ? Long.MAX_VALUE : size;
    }

    /**
     * 「本件能不能被别的多方块占用」—— 就是 {@code IMultiPart#canShared()} 的返回值来源。
     *
     * <p>
     * ⚠️ 这是**整个部件一个**开关，不是「每一侧一个」：{@code canShared()} 是机器级的一个方法，
     * 二合一那条总成虽然物品 / 流体各有一套标签与定量，共享开关也只有一个（见
     * {@code ETMEDualStockingPartMachine#canShared()}）。
     *
     * <p>
     * ⚠️ 只在**结构检查那一刻**被读（全 GTM 唯一消费点是 {@code BlockPattern#checkPatternAt}），
     * 所以这个值改了以后，已成型结构不会凭空变化 —— 见 {@link #setCanBeShared(boolean)} 的说明。
     */
    boolean canBeShared();

    /**
     * 拨动共享开关（true = 允许别的多方块占用本件，false = 隔离）。
     *
     * <p>
     * ⚠️ 实现方应当**在服务端**改值，并顺手让本件所属的每个多方块立刻复检一次结构
     * （{@code IMultiController#requestCheck()}）—— 否则玩家拨完开关要等下一次结构复检
     * （周期检查 / 方块变化）才看得到效果。两个方向的效果不一样，见 {@link #canBeShared()}。
     *
     * <p>
     * ⚠️ 与 {@link #setBatchSize(int)} 不同，这里的值**不能**由客户端自己写：它是
     * {@code @Persisted} 字段，客户端改了会在下一次同步时被服务端覆盖（和定量框一样）。
     */
    void setCanBeShared(boolean shared);
}
