package rain.gtetcore.gtet.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IDataStickInteractable;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredIOPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerList;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import rain.gtetcore.gtet.Gtetcore;
import rain.gtetcore.gtet.integration.ae2.ETProxySlotRecipeHandler;

/**
 * 「多阶段 ME 样板总成镜像」：贴在多方块里的代理部件，把配方输入转发到别处的
 * {@link ETMEPatternBufferPartMachine}。
 *
 * <h2>作用与用法</h2>
 * 与 GTM 的 {@code me_pattern_buffer_proxy} 完全同一套用法：用**闪存**对着样板总成右键
 * （总成的 {@code onDataStickShiftUse} 会把坐标写进闪存的 {@code pos}），再对着镜像右键绑定；
 * 之后多方块从镜像这里拿输入，实际数据在总成里。一个总成可以挂多个镜像（各机器共享同一批样板）。
 *
 * <h2>为什么不继承 GTM 的 MEPatternBufferProxyPartMachine</h2>
 * <ol>
 * <li>它的构造器把槽位数写死成 {@code MEPatternBufferPartMachine.MAX_PATTERN_COUNT}（27），
 * 而本 mod 的镜像要按档转发 27/63/126/216 个槽 —— 这是本功能的**核心**，改不了就等于没做；</li>
 * <li>它的 tier 被它自己的 super 调用锁死在 {@code GTValues.LuV}，而我们要与四个阶段同级外壳；</li>
 * <li>它转发时点名 {@code InternalSlotRecipeHandler.SlotRHL}（protected 嵌套类，跨包不可见），
 * 我们无法把它换成"转发到本 mod 总成"。</li>
 * </ol>
 * 所以本类是**照着它的形状自己写的一份**（生命周期、闪存绑定、数据棒解析、UI 转发、
 * 绑定状态这几个点一一对应），转发目标下沉到 public 的 {@code InternalSlot}，见
 * {@link ETProxySlotRecipeHandler}。GTM 那份镜像仍然原样存在、仍然只认 GTM 自己的总成
 * （它用 {@code instanceof MEPatternBufferPartMachine}），两者井水不犯河水。
 * <p>
 * 形状上的参考只有两处，且都只取设计、不取代码：GTM 自己的镜像（LGPL-3.0，与本项目同族，
 * 上面那些对应点全都能在它源码里逐条对照），以及 BetterGregTechAndAppliedEnergies 的
 * 「自己写镜像 + 认自己的宿主类」这一思路（⚠️ 那个仓库是 **GPL-3.0**，与本项目 LGPL-3.0 不同，
 * 因此只借鉴结构、**不复制任何代码**，以免把分发许可拖成 GPL）。
 *
 * <h2>「认不出对方」这个问题怎么解的（本类的 instanceof 是唯一的连接点）</h2>
 * 本镜像找宿主用的是 {@code instanceof ETMEPatternBufferPartMachine}（见 {@link #setBuffer}），
 * 也就是"复制版自己改 instanceof"这条路线 —— 而不是给 GTM 的代理打 mixin。理由：
 * <ul>
 * <li>宿主总成**确实**是 GTM 的 {@code MEPatternBufferPartMachine} 的子类（容量靠 mixin 参数化，
 * 没复制实现），所以 GTM 那份镜像的 {@code instanceof} 其实**认得**我们的总成 ——
 * 但它只建 27 个代理槽，用在 63/126/216 档上会**静默少转发**后面几十上百个槽，
 * 是比"认不出来"更糟的失败方式（认不出来至少什么都不发生）；</li>
 * <li>反向（让我们认识 GTM 的原生总成）没意义：容量对不上就得回退到 27，等于自废武功。</li>
 * </ul>
 * 因此：镜像与总成是**一一对应的自己人**，用自己人之间最直白的 {@code instanceof}。
 *
 * <h2>已知的（仅外观）缺口</h2>
 * GTM 的 Jade 插件用 {@code instanceof MEPatternBufferProxyPartMachine} 显示镜像信息，
 * 而它的 {@code me_pattern_buffer} 插件用 {@code buffer.getProxies()} 数已连接镜像
 * （那个集合的类型是 GTM 的镜像类，我们的进不去）。所以：**总成**上的 Jade 仍会正常显示
 * 缓冲内容（走 {@code mergeInternalSlots()}，与本 mod 的扩容无关），但"已连接镜像数"与
 * **镜像**上的 Jade 提示都不会算上我们的镜像。功能不受影响，属于显示层的欠账。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ETMEPatternBufferProxyPartMachine extends TieredIOPartMachine
                                             implements IMachineLife, IDataStickInteractable {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ETMEPatternBufferProxyPartMachine.class, TieredIOPartMachine.MANAGED_FIELD_HOLDER);

    /** 本镜像的槽级代理表（构造期按本档容量建好，绑定只改转发目标）。 */
    @Getter
    private final ETProxySlotRecipeHandler proxySlotRecipeHandler;

    /** 绑定的宿主坐标（持久化 + 同步到客户端，UI/闪存都靠它）。 */
    @Persisted
    @Getter
    @DescSynced
    private @Nullable BlockPos bufferPos;

    /** 宿主实例（运行时解析，不持久化）。 */
    private @Nullable ETMEPatternBufferPartMachine buffer = null;
    private boolean bufferResolved = false;

    /**
     * @param holder   方块实体
     * @param tier     本档电压等级（只影响外壳贴图与提示：多方块的非能源部件不看部件 tier，
     *                 GTM 自己也是拿 LuV 的 ME 部件去装机）
     * @param capacity 本档要转发的样板槽位数（与同档总成一致；见 {@link ETProxySlotRecipeHandler}）
     */
    public ETMEPatternBufferProxyPartMachine(IMachineBlockEntity holder, int tier, int capacity) {
        super(holder, tier, IO.IN);
        this.proxySlotRecipeHandler = new ETProxySlotRecipeHandler(this, capacity);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 与 GTM 一致：等 0 tick 再解析宿主（同区块的方块实体可能还没建好/还没读到存档里的 bufferPos）
        if (getLevel() instanceof ServerLevel level) {
            level.getServer().tell(new TickTask(0, () -> this.setBuffer(bufferPos)));
        }
    }

    @Override
    public List<RecipeHandlerList> getRecipeHandlers() {
        return proxySlotRecipeHandler.getProxySlotHandlers();
    }

    /**
     * 绑定（或解绑）宿主总成。
     *
     * @param pos 宿主坐标；null = 解绑
     */
    public void setBuffer(@Nullable BlockPos pos) {
        bufferResolved = true;
        var level = getLevel();
        if (level == null || pos == null) {
            buffer = null;
            if (!isRemote()) proxySlotRecipeHandler.clearProxy();
            return;
        }
        if (MetaMachine.getMachine(level, pos) instanceof ETMEPatternBufferPartMachine machine) {
            bufferPos = pos;
            buffer = machine;
            if (!isRemote()) {
                proxySlotRecipeHandler.updateProxy(machine);
                warnIfCapacityMismatch(machine);
            }
        } else {
            // 不是本 mod 的样板总成（GTM 原生的、或那个位置压根不是总成）：解绑并清掉代理，
            // 免得多方块还从一条已经没人管的旧链路上找货
            buffer = null;
            if (!isRemote()) proxySlotRecipeHandler.clearProxy();
        }
    }

    /** 取宿主；未解析过就按存档里的坐标解析一次（客户端也会走这条，用于开 UI 判空）。 */
    @Nullable
    public ETMEPatternBufferPartMachine getBuffer() {
        if (!bufferResolved) setBuffer(bufferPos);
        return buffer;
    }

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return getBuffer() != null;
    }

    @Override
    public ModularUI createUI(Player entityPlayer) {
        // 镜像自己没有面板：直接开宿主总成的面板（与 GTM 一致），玩家在镜像上就能放样板
        var buf = getBuffer();
        if (buf == null) {
            // 未绑定时 {@link #shouldOpenUI} 已经拦住了玩家右键；这里给个空面板而不是抛异常 ——
            // 别的整合（JEI/EMI 预览、Jade 之类）也会直接问 createUI，不该把游戏带崩
            return new ModularUI(176, 166, this, entityPlayer);
        }
        return buf.createUI(entityPlayer);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onMachineRemoved() {
        // 注意：这里**不**调宿主的 removeProxy —— 父类的 addProxy/removeProxy 只收 GTM 自己的
        // 镜像类型（见类注释「已知缺口」），所以镜像与宿主之间只有单向的只读关系，
        // 拆掉镜像只需清掉本地的代理表。
        proxySlotRecipeHandler.clearProxy();
        buffer = null;
    }

    @Override
    public InteractionResult onDataStickUse(Player player, ItemStack dataStick) {
        if (dataStick.hasTag()) {
            var tag = dataStick.getTag();
            if (tag != null && tag.contains("pos", Tag.TAG_INT_ARRAY)) {
                var posArray = tag.getIntArray("pos");
                if (posArray.length >= 3) {
                    setBuffer(new BlockPos(posArray[0], posArray[1], posArray[2]));
                    return InteractionResult.SUCCESS;
                }
            }
        }
        return InteractionResult.PASS;
    }

    /**
     * 档位不对（镜像槽数 < 宿主槽数）时警告一次：多出来的宿主槽**不会**被本镜像转发，
     * 多方块只能吃到前 N 个槽。低档镜像配高档总成是能用的，但通常不是本意。
     */
    private void warnIfCapacityMismatch(ETMEPatternBufferPartMachine machine) {
        int proxySlots = proxySlotRecipeHandler.getProxySlotHandlers().size();
        int bufferSlots = machine.getPatternCapacity();
        if (bufferSlots > proxySlots) {
            Gtetcore.LOGGER.warn("[gtetcore] {} 的镜像只带 {} 个槽，但 {} 有 {} 个样板槽：后面 {} 个槽不会被转发。" +
                    "请用与总成同档的镜像", getPos(), proxySlots, machine.getPos(), bufferSlots, bufferSlots - proxySlots);
        }
    }
}
