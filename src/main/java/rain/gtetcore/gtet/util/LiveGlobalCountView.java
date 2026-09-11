package rain.gtetcore.gtet.util;

import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import java.util.Map;

/**
 * 「活视图」适配器：把 GTMThings 1.6.0 眼里的 {@link Object2IntOpenHashMap} 直接接到
 * GTM 7.5.3 真实的 {@link Reference2IntOpenHashMap} 上，读写全部转发。
 *
 * <h2>为什么需要它（第三方 mod 的版本错配）</h2>
 *
 * GTMThings 1.6.0 的 {@code AdvancedBlockPattern.autoBuild} 是按<b>旧 GTM</b>编译的，
 * 字节码里把返回类型写死成了 {@code Object2IntOpenHashMap}（javap -p -c 实证，调用点在
 * {@code autoBuild} 的 offset 75 / 81）：
 *
 * <pre>
 * 75: invokevirtual // Method com/gregtechceu/gtceu/api/pattern/MultiblockState.getGlobalCount:()Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;
 * 81: invokevirtual // Method com/gregtechceu/gtceu/api/pattern/MultiblockState.getLayerCount:()Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;
 * </pre>
 *
 * 而 GTM 7.5.3 里这两个 getter 的返回类型已改成 {@code Reference2IntOpenHashMap<SimplePredicate>}
 * （gradle 缓存那份与 mavenLocal 那份补丁版，javap 结果一致）。这两者在 fastutil 里
 * <b>没有任何继承关系</b>，是两条互不相干的类型链（javap 实证）：
 *
 * <pre>
 * Object2IntOpenHashMap     extends AbstractObject2IntMap      implements Object2IntMap
 * Object2IntMap             extends Object2IntFunction, java.util.Map
 * Reference2IntOpenHashMap  extends AbstractReference2IntMap  implements Reference2IntMap
 * Reference2IntMap          extends Reference2IntFunction, java.util.Map
 * </pre>
 *
 * JVM 解析 invokevirtual 是按「方法名 + 描述符」精确匹配的，描述符里的返回类型对不上，
 * 唯一结果就是 {@link NoSuchMethodError}（正是本次崩溃的成因）。
 * 本类不改变 GTM，也不改 GTMThings 的字节码语义 —— 它只是让旧描述符的调用点拿到一个
 * <b>类型上兼容、行为上等价</b>的对象。
 *
 * <h2>为什么是「活视图」而不是一次性拷贝</h2>
 *
 * GTMThings 在 {@code autoBuild} 里把这些表当<b>累加计数器</b>用（javap -c 看到的用法）：
 * 每进一层先 {@code layerCount.clear()} 清零，随后每次判定/放置成功就 {@code addTo(pred, 1)}，
 * 中途反复 {@code getInt(pred)} / {@code getOrDefault(pred, Integer.MAX_VALUE)} 读回当前计数
 * 去和 {@code SimplePredicate} 的 {@code minCount/maxCount/minLayerCount/maxLayerCount} 比大小。
 * 也就是说「写进去的值必须能被后面的读看见」，一次性拷贝（{@code putAll} 到新表）从第二次读开始
 * 拿到的就全是陈旧值或 0，限次谓词的判断会失真。
 *
 * <p>更关键的是：这不是我们发明的用法，而是旧 GTM 的原生语义。GTM 7.5.3 自己的
 * {@code BlockPattern.checkPatternAt} 就是从 {@code worldState.getGlobalCount()/getLayerCount()}
 * 拿引用后 {@code layerCount.clear()} → {@code addTo} → {@code getInt} →
 * {@code getOrDefault(pred, Integer.MAX_VALUE)} 一路操作同一张表；GTMThings 1.6.0 的
 * {@code autoBuild} 显然是照着它写的，只是编译时那版 GTM 的 getter 还叫
 * {@code Object2IntOpenHashMap}。所以「让它继续操作 GTM 的同一张真表」才是行为等价的做法，
 * 拷贝只是退路。
 *
 * <h2>覆写范围怎么定的</h2>
 *
 * 覆写集合不是拍脑袋，是把 GTMThings 的 {@code AdvancedBlockPattern.class} 反汇编后，
 * 按 {@code autoBuild} 里这两个局部变量（javap 的 slot 11 = globalCount、slot 12 = layerCount）
 * 的<b>全部</b>调用点定的。该类的完整调用点只有 9 处、4 个方法：
 *
 * <pre>
 * clear()V                        1 处（每层开头重置 layerCount）
 * getInt(Ljava/lang/Object;)I     2 处（layer / global 各一）
 * addTo(Ljava/lang/Object;I)I     4 处（layer 2 + global 2）
 * getOrDefault(Ljava/lang/Object;I)I  2 处（layer / global 各一，默认值 Integer.MAX_VALUE）
 * </pre>
 *
 * 另外顺手把 {@code put/removeInt/containsKey/size/isEmpty/putAll} 也一并转发 —— 都是
 * 一行转发、零成本，防止将来别处（或 GTMThings 小版本更新）用到其中一个却读到空表。
 * 全类只有 9 处调用，且这两个表引用没有逃逸出 {@code autoBuild}（javap 只见 astore 11/12
 * 各一次，且随即只被上述 invoke 消费）。
 *
 * <h2>⚠ 已知边界（没有转发的东西）</h2>
 *
 * <b>迭代接口没有转发</b>：{@code object2IntEntrySet()} / {@code keySet()} / {@code values()} /
 * {@code forEach()} / {@code intStream()} / {@code equals} / {@code hashCode} / {@code toString}
 * 仍然走父类那份<b>永远为空</b>的内部表（{@code super()} 分配的表我们一次都不碰）。
 * 之所以不转发下去，是因为 fastutil 这些迭代器的返回类型分别绑在
 * {@code Object2IntMap.FastEntrySet} / {@code ObjectSet} 上，跟 {@code Reference2IntMap} 的
 * 对应类型同样没有继承关系，硬转需要再写一层迭代器包装 —— 而 GTMThings 1.6.0 一处都没用到。
 *
 * <p><b>⚠ 因此：如果将来 GTMThings 升级后开始遍历这个视图（例如
 * {@code for (var e : layerCount.object2IntEntrySet())}），它会静默看到一张空表。</b>
 * 届时必须补上 {@code object2IntEntrySet()} 的转发包装，而不是继续沿用本类。
 * 排查手法：{@code javap -p -c} 反汇编新的 {@code AdvancedBlockPattern.class}，
 * 看 slot 上那两个 map 还调了哪些方法（见本类开头列的那 4 个之外的都是新用法）。
 *
 * <p>另外几点：
 * <ul>
 * <li>{@code super()} 会按 fastutil 默认容量 16 分配两个小数组，纯属为了满足「是
 *     {@code Object2IntOpenHashMap} 的子类」这个类型要求；一次 {@code autoBuild} 只 new 两个对象，
 *     可以忽略。</li>
 * <li>{@code defaultReturnValue(int)} 只在父类那份空表上生效，不影响转发（转发目标
 *     {@code Reference2IntOpenHashMap} 自己也有默认返回值 0，GTM 从未改过它）。</li>
 * <li>委托对象不会为 null：{@code MultiblockState} 的两个构造器都会
 *     {@code new Reference2IntOpenHashMap<>()} 初始化这两个字段（GTM 源码实证）。</li>
 * </ul>
 *
 * ## 思路来源
 * - 【自研】整个适配层 —— 活视图类、覆写集合的选择、以及「用转发而不是拷贝」这个判断，
 *   都是我们为 GTMThings 1.6.0 与 GTM 7.5.3 的签名错配自己写的。GTMThings 与 GTM 双方
 *   都没有为此提供任何兼容层或扩展点。
 *
 * @author rain fox
 */
public class LiveGlobalCountView extends Object2IntOpenHashMap<SimplePredicate> {

    private static final long serialVersionUID = 1L;

    /** GTM 的实时表（globalCount 或 layerCount，两者结构相同，本类不区分）。 */
    private final transient Reference2IntOpenHashMap<SimplePredicate> delegate;

    private LiveGlobalCountView(Reference2IntOpenHashMap<SimplePredicate> delegate) {
        // 父类的内部表刻意保持为空：所有读写都转发给 delegate，super 的表一次都不碰。
        super();
        this.delegate = delegate;
    }

    /**
     * 把一个 GTM 实时表包装成 GTMThings 期望的 {@code Object2IntOpenHashMap}。
     *
     * @param delegate GTM {@code MultiblockState} 的 globalCount / layerCount，非 null
     * @return 转发到 {@code delegate} 的活视图
     */
    public static Object2IntOpenHashMap<SimplePredicate> of(Reference2IntOpenHashMap<SimplePredicate> delegate) {
        return new LiveGlobalCountView(delegate);
    }

    // ======================== javap 实证的实际调用点（4 个） ========================

    /** {@code layerCount.clear()}：每层开头清零，必须落到真表上。 */
    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public int getInt(Object key) {
        return delegate.getInt(key);
    }

    @Override
    public int addTo(SimplePredicate key, int incr) {
        return delegate.addTo(key, incr);
    }

    /** 默认值语义照抄 GTMThings 的用法（{@code Integer.MAX_VALUE} 表示「没到上限」）。 */
    @Override
    public int getOrDefault(Object key, int defaultValue) {
        return delegate.getOrDefault(key, defaultValue);
    }

    // ======================== 顺手转发（当前未被调用，防御性） ========================

    @Override
    public int put(SimplePredicate key, int value) {
        return delegate.put(key, value);
    }

    @Override
    public void putAll(Map<? extends SimplePredicate, ? extends Integer> m) {
        delegate.putAll(m);
    }

    @Override
    public int removeInt(Object key) {
        return delegate.removeInt(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }
}
