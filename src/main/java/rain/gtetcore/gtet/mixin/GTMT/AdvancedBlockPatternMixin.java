package rain.gtetcore.gtet.mixin.GTMT;

import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.hepdd.gtmthings.api.pattern.AdvancedBlockPattern;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rain.gtetcore.gtet.util.LiveGlobalCountView;

/**
 * 适配桥：把 GTMThings 1.6.0 对 {@code MultiblockState.getGlobalCount()/getLayerCount()} 的
 * <b>旧描述符</b>调用，重定向到适配器上，消除 {@link NoSuchMethodError}。
 *
 * <h2>崩溃与成因</h2>
 *
 * 高级终端的「自动搭建」一右键就炸：
 *
 * <pre>
 * java.lang.NoSuchMethodError:
 *   'it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap MultiblockState.getGlobalCount()'
 *   at com.hepdd.gtmthings.api.pattern.AdvancedBlockPattern.autoBuild(AdvancedBlockPattern.java:126)
 *   at com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior.useOn(AdvancedTerminalBehavior.java:55)
 * </pre>
 *
 * 成因是<b>第三方 mod 与 GTM 的版本错配</b>（javap -p -c 实证，命令见下）：
 * GTMThings 1.6.0 是按旧 GTM 编译的，{@code AdvancedBlockPattern.autoBuild} 的字节码里
 * 把这两个 getter 的返回类型写死成了 {@code Object2IntOpenHashMap}；而 GTM 7.5.3 把它们
 * 换成了 {@code Reference2IntOpenHashMap<SimplePredicate>}。
 *
 * <pre>
 * # GTMThings 侧（ForgeGradle 的 deobf_dependencies 缓存里那份 curse maven jar）
 * javap -p -c ...\gtmthings-1104310-7712957_mapped_parchment_2023.09.03-1.20.1.jar 里的 AdvancedBlockPattern.class
 *   75: invokevirtual // Method .../MultiblockState.getGlobalCount:()Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;
 *   81: invokevirtual // Method .../MultiblockState.getLayerCount:()Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;
 *
 * # GTM 7.5.3 侧（gradle 缓存版与 mavenLocal 补丁版结果一致）
 * javap -p -classpath .../gtceu-1.20.1-7.5.3.jar com.gregtechceu.gtceu.api.pattern.MultiblockState
 *   public it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap&lt;...SimplePredicate&gt; getGlobalCount();
 *   public it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap&lt;...SimplePredicate&gt; getLayerCount();
 * </pre>
 *
 * 两种 map 在 fastutil 里<b>没有继承关系</b>（{@code Object2IntMap} 与 {@code Reference2IntMap}
 * 是两条独立类型链），JVM 按「名字 + 描述符」精确解析方法，描述符对不上 → {@code NoSuchMethodError}。
 *
 * <h2>桥怎么搭</h2>
 *
 * 两个调用点（都在 {@code autoBuild} 里，全类就这两处）各来一个 {@link Redirect}，
 * 换成返回 {@link LiveGlobalCountView} —— 一个继承 {@code Object2IntOpenHashMap} 但把所有读写
 * 转发到 GTM 真表上的活视图。返回值类型与调用点期望的描述符完全一致，
 * 所以 GTMThings 里后续的 {@code clear()/getInt()/addTo()/getOrDefault()} 会真的作用到
 * GTM 的实时计数表上，行为等价于它编译时的预期。
 *
 * <p><b>为什么不是一次性拷贝（{@code putAll} 到新表）</b>：{@code autoBuild} 把这些表当累加计数器，
 * 「写进去的值要能被后面的读看见」，拷贝表会立刻失去这个性质，限次谓词
 * （{@code minCount/maxCount/minLayerCount/maxLayerCount}）的判断会失真。
 * 详见 {@link LiveGlobalCountView} 的类注释。
 *
 * <p>调用点的完整清单（全类 9 处、4 个方法）与覆写集合的依据，同样记在
 * {@link LiveGlobalCountView} 的类注释里，这里不重复。
 *
 * <h2>⚠ 警示与 TODO</h2>
 *
 * <ul>
 * <li><b>AP 校验到哪一步（实测，MixinGradle 0.7.38 + Mixin AP 0.8.5）</b>：
 *     注解处理器<b>会</b>校验 {@code @Redirect.method} 选择器 —— 把下面 {@code AUTO_BUILD} 的返回类型
 *     故意改成 {@code )Z} 就会立刻报
 *     {@code Cannot find target method "autoBuild(...)Z" for @Redirect.method=... in com.hepdd.gtmthings.api.pattern.AdvancedBlockPattern}，
 *     也就是说「目标类 + 方法名 + 参数/返回描述符」这一段是 AP 替我们核对过的（含
 *     {@code AdvancedTerminalBehavior$AutoBuildSetting} 这种内部类名）；
 *     但 {@code @At} 里的 INVOKE 目标<b>完全不校验</b> —— 把 {@code OLD_COUNT_RETURN} 故意换成不存在的
 *     {@code .../BOGUSOpenHashMap;} 后 AP 一声不吭（编译照样成功）。原因也清楚：那串旧描述符在
 *     编译期 classpath（GTM 7.5.3）上<b>根本不存在</b>，AP 无从校验。
 *     <b>所以「编译通过」证明不了注入点在运行期找得到 —— 那串旧描述符的正确性完全依赖 javap 人工核对</b>
 *     （核对结果见类注释开头的反汇编片段）；真正的验证只能在启动客户端、右键一次高级终端时才拿得到。</li>
 * <li>{@code require = 1} 是本项目对第三方 mod 注入点的一贯选择（响亮失败）：万一将来 GTMThings
 *     更新到新签名、调用点消失，加载期会直接报「找不到重定向目标」，而不是静默失效后在游戏里
 *     再甩一次 {@code NoSuchMethodError}。届时本 mixin 连同 {@link LiveGlobalCountView} 一起删掉即可。</li>
 * <li>TODO：若 GTMThings 升级后 {@code autoBuild} 里还出现了别的 map 用法（例如开始遍历
 *     {@code layerCount.object2IntEntrySet()}），活视图的覆写集合要跟着补，
 *     核对手法见 {@link LiveGlobalCountView} 的「已知边界」。</li>
 * <li>两个 {@code @Redirect} 分开写（而不是合成一个），是因为它们的返回值要分别来自
 *     {@code getGlobalCount()} 与 {@code getLayerCount()} 两张不同的表，不能共用。</li>
 * </ul>
 *
 * @author rain fox
 */
@Mixin(value = AdvancedBlockPattern.class, remap = false)
public abstract class AdvancedBlockPatternMixin {

    /** 目标方法：唯一一个 {@code autoBuild}（描述符取自 javap 的方法头）。 */
    @Unique
    private static final String AUTO_BUILD = "autoBuild(Lnet/minecraft/world/entity/player/Player;"
            + "Lcom/gregtechceu/gtceu/api/pattern/MultiblockState;"
            + "Lcom/hepdd/gtmthings/common/item/AdvancedTerminalBehavior$AutoBuildSetting;)V";

    /** GTMThings 1.6.0 编译期的旧返回类型：{@code Object2IntOpenHashMap}（不是实际存在的 GTM 签名）。 */
    @Unique
    private static final String OLD_COUNT_RETURN = "Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;";

    @Unique
    private static final String GET_GLOBAL_COUNT = "Lcom/gregtechceu/gtceu/api/pattern/MultiblockState;"
            + "getGlobalCount()" + OLD_COUNT_RETURN;

    @Unique
    private static final String GET_LAYER_COUNT = "Lcom/gregtechceu/gtceu/api/pattern/MultiblockState;"
            + "getLayerCount()" + OLD_COUNT_RETURN;

    /** 取 GTM 实时 globalCount（受限谓词的全局计数）的活视图。 */
    @Redirect(
            method = AUTO_BUILD,
            at = @At(value = "INVOKE", target = GET_GLOBAL_COUNT, remap = false),
            remap = false,
            require = 1)
    private Object2IntOpenHashMap<SimplePredicate> gtetcore$bridgeGlobalCount(MultiblockState state) {
        return LiveGlobalCountView.of(state.getGlobalCount());
    }

    /** 取 GTM 实时 layerCount（当前层计数，每层开头被 GTMThings 清零）的活视图。 */
    @Redirect(
            method = AUTO_BUILD,
            at = @At(value = "INVOKE", target = GET_LAYER_COUNT, remap = false),
            remap = false,
            require = 1)
    private Object2IntOpenHashMap<SimplePredicate> gtetcore$bridgeLayerCount(MultiblockState state) {
        return LiveGlobalCountView.of(state.getLayerCount());
    }
}
