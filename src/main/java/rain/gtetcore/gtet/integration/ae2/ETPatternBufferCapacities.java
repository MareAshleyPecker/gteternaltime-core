package rain.gtetcore.gtet.integration.ae2;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import rain.gtetcore.gtet.Gtetcore;

/**
 * 「多阶段 ME 样板总成」的容量表：**方块定义 id → 样板槽位数**。
 *
 * <h2>为什么要有这张表（而不是把容量当构造参数传）</h2>
 * GTCEu 7.5.3 的 {@link MEPatternBufferPartMachine} 把容量写成
 * {@code protected static final int MAX_PATTERN_COUNT = 27}，并且**用在父类自己的字段初始化里**：
 * <pre>
 * patternInventory  = new CustomItemStackHandler(27)   // javap &lt;init&gt;：bipush 27（偏移 26）
 * internalInventory = new InternalSlot[27]             // bipush 27（偏移 35）
 * detailsSlotMap    = HashBiMap.create(27)             // bipush 27（偏移 44）
 * </pre>
 * 字段初始化发生在**父类构造期**，子类此时一个字段都还没赋值，所以「继承 + 覆写」这条路走不通
 * （子类怎么写都还是 27）。本项目的做法不是复制那 700 行，而是由 {@code MixinMEPatternBufferCapacity}
 * 把构造器里那 3 处内联的 {@code 27} 换成 {@code ETPatternBufferCapacities.of(getDefinition())}，
 * 让三个字段**一出生**就是本档容量 —— 于是父类自己的构造/读档/AE 终端/取回逻辑一行都不用改。
 *
 * <h2>为什么能在构造期问出「我是哪一档」</h2>
 * {@code MetaMachine} 的构造器第一件事就是 {@code this.holder = holder}（源码 128 行），
 * 而 {@code IMachineBlockEntity#getDefinition()} 读的是**方块**（{@code self().getBlockState().getBlock()}）
 * 而不是机器实例（源码 79-86 行）；再加上 {@code MetaMachineBlockEntity} 自己就是先
 * {@code this.metaMachine = getDefinition().createMetaMachine(this)} 才继续构造（源码 76 行）。
 * 所以父类字段初始化跑的时候，{@code getDefinition()} 一定可用、且一定是本档的定义。
 *
 * <h2>表必须在使用前填好</h2>
 * 条目由注册代码（{@code ETMEPatternBufferHatches}）在 {@code .register()} **之前**写入；
 * 机器实例只会在方块实体创建时构造，而方块实体创建必然晚于注册，所以顺序是安全的。
 * 万一没填到，退回 {@link #NATIVE}（= GTM 原生 27）并给一次警告 —— 不静默降级。
 *
 * @author rain fox
 */
public final class ETPatternBufferCapacities {

    /**
     * GTM 原生 {@code me_pattern_buffer} 的容量。
     *
     * ⚠️ 数值必须与 {@code MEPatternBufferPartMachine.MAX_PATTERN_COUNT} 一致（7.5.3 = 27）；
     * 本 mod 之外的任何一个 {@link MEPatternBufferPartMachine} 实例（GTM 自己的、别的附属的）
     * 都按这个数走，行为与打 mixin 之前**完全一样**。
     */
    public static final int NATIVE = 27;

    /** 容量的合理上限：只用来把注册期的笔误挡在门外，不是玩法限制。 */
    private static final int MAX_REASONABLE = 4096;

    private static final Map<ResourceLocation, Integer> BY_DEFINITION = new ConcurrentHashMap<>();

    /** 本 mod 注册过的命名空间：用来区分「我们漏登记了」和「这本来就是别人的机器」。 */
    private static final Set<String> OWN_NAMESPACES = ConcurrentHashMap.newKeySet();

    /** 已经警告过的定义 id，避免每放一个方块刷一次日志。 */
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    private ETPatternBufferCapacities() {}

    /**
     * 登记一档容量。
     *
     * @param definitionId 方块定义的 id（必须与 {@code MachineDefinition#getId()} 逐字一致）
     * @param capacity     样板槽位数（≥ {@link #NATIVE}，且 ≤ 4096）
     */
    public static void register(ResourceLocation definitionId, int capacity) {
        if (capacity < NATIVE || capacity > MAX_REASONABLE) {
            throw new IllegalArgumentException("[gtetcore] 样板总成容量越界：" + definitionId + " -> " + capacity +
                    "（允许 " + NATIVE + " ~ " + MAX_REASONABLE + "）");
        }
        OWN_NAMESPACES.add(definitionId.getNamespace());
        BY_DEFINITION.put(definitionId, capacity);
    }

    /**
     * 取某个方块定义的容量；**不是在册的定义一律返回 GTM 原生的 27**。
     *
     * <p>这个方法就是 mixin 在父类构造器里调用的那一个，所以它不能抛异常
     * （否则 GTM 自己的方块一放就崩）、要够快（一台机器构造期会调 3 次）。
     *
     * @param definition 方块定义，可为 null（方块状态还不是 GT 机器方块等异常情形）
     */
    public static int of(@Nullable MachineDefinition definition) {
        if (definition == null) return NATIVE;
        var id = definition.getId();
        var capacity = BY_DEFINITION.get(id);
        if (capacity != null) return capacity;
        // 本 mod 自己的机器命中这条分支 = 注册漏了/写错了 id，必须吵一次，不能静默按 27 建出来
        if (OWN_NAMESPACES.contains(id.getNamespace()) && WARNED.add(id)) {
            Gtetcore.LOGGER.warn("[gtetcore] {} 没在 ETPatternBufferCapacities 里登记容量，已按 GTM 原生 {} 个样板槽建立；" +
                    "请检查是否有注册处漏调 ETPatternBufferCapacities.register", id, NATIVE);
        }
        return NATIVE;
    }

    /**
     * 已登记的最大容量 —— 也就是「一件镜像要能服务所有档位」所需的代理槽数。
     *
     * <p>镜像只有 LuV 一件（见 {@code ETMEPatternBufferHatches}），却要能连 27 / 63 / 126 / 216
     * 任何一档的总成，所以它的槽级代理表必须按**表里最大的那一档**建：
     * 多方块只在**成型那一刻**收集一次部件处理器表（证据见
     * {@code WorkableMultiblockMachine#onStructureFormed} 与 {@code IRecipeCapabilityHolder#addHandlerList}），
     * 之后换表它看不见，所以容量不能等"连上宿主"再决定。
     * 完整取舍见 {@code ETProxySlotRecipeHandler} 的类注释。
     *
     * <p>⚠️ 必须在**所有档位注册完之后**调用（机器实例只会在方块实体创建时构造，必然晚于注册）。
     * 没登记过任何档位时返回 {@link #NATIVE}。
     */
    public static int maxCapacity() {
        int max = NATIVE;
        for (int capacity : BY_DEFINITION.values()) {
            if (capacity > max) max = capacity;
        }
        return max;
    }

    /**
     * 运行时自检：机器的实际存储格子数与表里登记的是否一致。
     *
     * <p>⚠️ 这是给「mixin 没生效」准备的**探针**：mixin 若因 GTM 版本变化没能改写那 3 处常量，
     * 存储会静默按 27 建出来（GUI 与 AE 终端也只有 27 格），这条警告是唯一能立刻看出问题的地方。
     *
     * @param machine 刚构造完的样板总成
     */
    public static void verify(MetaMachine machine) {
        if (!(machine instanceof MEPatternBufferPartMachine buffer)) return;
        var definition = machine.getDefinition();
        var expected = of(definition);
        var actual = buffer.getPatternInventory().getSlots();
        if (actual != expected && WARNED.add(definition.getId())) {
            Gtetcore.LOGGER.warn("[gtetcore] {} 的样板槽位数是 {}，容量表登记的是 {}；" +
                    "通常意味着 MixinMEPatternBufferCapacity 没生效（GTM 版本变了？）",
                    definition.getId(), actual, expected);
        }
    }
}
