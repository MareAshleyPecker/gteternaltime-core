package rain.gtetcore.gtet.integration.ae2;

import com.gregtechceu.gtceu.utils.TagExprFilter;
import com.gregtechceu.gtceu.utils.TagExprFilter.TagExprParser.MatchExpr;

import net.minecraft.tags.TagKey;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Stream;

/**
 * 「标签表达式」判定器：白名单 + 黑名单两条表达式，把 {@link AEKey}（可以是物品也可以是流体）判成「放行 / 不放行」。
 *
 * <p>
 * 表达式解析本身复用 GTM 的 {@link TagExprFilter}（就是 GTM 标签过滤器盖子 {@code TagItemFilter} /
 * {@code TagFluidFilter} 用的那一套），所以运算符语义与 GTM 完全一致：
 * {@code &}（与）{@code |}（或）{@code !}（非）{@code ^}（异或）{@code ( )}（分组），
 * 以及标签路径末尾的 {@code *} 通配。另外本类额外接受两种**书写便利**（GTOCore 的玩家的书写习惯）：
 * <ul>
 * <li>{@code #} 前缀一律忽略（{@code #forge:ingots} 与 {@code forge:ingots} 等价）。⚠️ GTM 原版表达式**不认** {@code #}，
 * 直接写进 {@code TagExprFilter} 会变成永真不命中的字面量，所以必须在本类里先剥掉；</li>
 * <li>{@code ,} 当作 {@code |} 的简写（多条标签并排写）。</li>
 * </ul>
 *
 * <h2>判定语义</h2>
 * <ul>
 * <li>白名单、黑名单**都留空** → 完全不过滤（等价于 GTM 原版库存总线，装上去就能用）；</li>
 * <li>只填黑名单 → 除了命中黑名单的都放行；</li>
 * <li>填了白名单 → 必须命中白名单，且不得命中黑名单。</li>
 * </ul>
 *
 * <h2>⚠️ 表达式写错时的行为</h2>
 * {@link TagExprFilter#parseExpression(String)} 对非法输入**不抛异常**：它要么返回 {@code null}
 * （空串、只剩运算符），要么返回一棵「怎么判都是 false」的表达式树（如 {@code (}、{@code !!!}、
 * 括号不配对）。所以写坏的表达式 = 一个都拉不进来（fail-closed），而不是崩溃。
 * 一个例外要记住：合法但**不存在**的标签（拼错、或该整合包没装出这个标签）同样是不命中 —— 两侧都填了
 * 却一件也拉不进来时，先怀疑标签名拼错。
 *
 * <h2>缓存</h2>
 * 解析结果（{@link MatchExpr}）只在两条原始字符串真的变了时重算，**绝不在每次判定时重新解析字符串**；
 * 另外按 {@link AEKey} 缓存判定结果（标签集合要从 ItemStack / FluidStack 现取，取一次就够）。
 * 缓存上限见 {@link #CACHE_LIMIT}，超出直接整体清空（AE2 的 key 总量是有限的，正常玩不会触顶）。
 *
 * <h2>思路来源</h2>
 * 【借鉴形状】GTOCore（LGPL-3.0）{@code com.gtocore.common.machine.multiblock.part.ae.ITagFilterPartMachine}
 * 与 {@code METagFilterStockBusPartMachine} —— 借「白名单 / 黑名单两个字符串 + 一份可缓存的判定器 +
 * 面板上两个输入框配两个幻影槽」这个形状。⚠️ GTOCore 的判定器是 ExtendedAE 的
 * {@code TagPriorityList}（GTO 走自己的 GTM 分叉），GTET 这边不能直接用，改为自己基于 GTM 的
 * {@link TagExprFilter} 实现；判定语义（含「两侧都空 = 不过滤」）也是 GTET 自己定的，见上文。
 *
 * @author rain fox
 */
public final class ETTagFilter {

    /** 按 key 缓存判定结果的上限，超出整体清空。 */
    private static final int CACHE_LIMIT = 4096;

    /** 自动填表达式时最多取多少个标签（避免幻影槽塞出来的字符串超过输入框长度上限）。 */
    private static final int AUTO_FILL_TAG_LIMIT = 16;

    private final Object2BooleanMap<AEKey> results = new Object2BooleanOpenHashMap<>();

    private String whiteSource = "";
    private String blackSource = "";

    /** null = 该侧没有可用的表达式（留空，或写成了只剩运算符的非法表达式）。 */
    private @Nullable MatchExpr white;
    private @Nullable MatchExpr black;

    /**
     * 换一套表达式。**两条原始字符串都没变时什么都不做**（这是热路径：面板每次改字、每次读档都会调）。
     *
     * @param white 白名单原始表达式（可为 null，按空串处理）
     * @param black 黑名单原始表达式（可为 null，按空串处理）
     */
    public void set(@Nullable String white, @Nullable String black) {
        String w = white == null ? "" : white;
        String b = black == null ? "" : black;
        if (w.equals(this.whiteSource) && b.equals(this.blackSource)) return;
        this.whiteSource = w;
        this.blackSource = b;
        this.white = TagExprFilter.parseExpression(normalize(w));
        this.black = TagExprFilter.parseExpression(normalize(b));
        this.results.clear();
    }

    /** 两侧都留空 = 本判定器完全不拦东西。 */
    public boolean isDisabled() {
        return white == null && black == null;
    }

    /**
     * 判定一个 AE key 是否放行。
     *
     * @param what AE2 的物品 / 流体 key
     * @return true = 允许被库存逻辑拉进来
     */
    public boolean test(@Nullable AEKey what) {
        if (what == null) return false;
        if (isDisabled()) return true;
        if (results.containsKey(what)) return results.getBoolean(what);
        boolean allowed = matches(white, what, true) && !matches(black, what, false);
        if (results.size() >= CACHE_LIMIT) results.clear();
        results.put(what, allowed);
        return allowed;
    }

    /**
     * 单侧判定。
     *
     * @param expr            该侧的表达式树，null 表示该侧留空
     * @param what            AE key
     * @param blankMeansAllow 该侧留空时的取值：白名单留空 = 放行（true），黑名单留空 = 不命中（false）
     */
    private static boolean matches(@Nullable MatchExpr expr, AEKey what, boolean blankMeansAllow) {
        if (expr == null) return blankMeansAllow;
        // AEKey → ItemStack / FluidStack，再交给 GTM 自己的判定（它内部会把标签集合取出来喂给表达式树）
        if (what instanceof AEItemKey itemKey) return TagExprFilter.tagsMatch(expr, itemKey.toStack(1));
        if (what instanceof AEFluidKey fluidKey) return TagExprFilter.tagsMatch(expr, fluidKey.toStack(1));
        return false;
    }

    /**
     * 把一个 AE key 判成「属于哪些标签」，给面板的幻影槽用（放一个样本进来，把它的标签摊开写成表达式）。
     *
     * <p>
     * ⚠️ 取标签的路子与 GTM 自己的 {@code TagExprFilter.tagsMatch} 保持一致：物品走
     * {@code ItemStack#getTags()}，流体走 {@code FluidStack#getFluid().defaultFluidState().getTags()}
     * （流体标签在 Forge 里就挂在这上面）。不认识的非物品 / 非流体 key 返回空表。
     *
     * @return 该 key 的标签 id 列表（已去重、已排序），可能为空
     */
    public static List<String> tagIds(@Nullable AEKey what) {
        return tagStream(what)
                .map(tag -> tag.location().toString())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 把一个 AE key 的标签摊成一条可直接用的白/黑名单表达式：多个标签用 {@code |} 连接（命中任意一个即算命中）。
     *
     * <p>
     * 拿去铺开的标签最多 {@link #AUTO_FILL_TAG_LIMIT} 个（按字母序截断，截在标签之间，
     * 所以结果**一定是一条合法表达式**）；样本没有标签时返回空串。
     * 这是给玩家的**起点**而不是终点：面板里的输入框随便改，删掉多余的、换成 {@code &} / {@code !} 都行。
     */
    public static String expressionOf(@Nullable AEKey what) {
        List<String> ids = tagIds(what);
        if (ids.isEmpty()) return "";
        return String.join(" | ", ids.size() > AUTO_FILL_TAG_LIMIT ? ids.subList(0, AUTO_FILL_TAG_LIMIT) : ids);
    }

    /**
     * 把玩家书写的表达式整理成 {@link TagExprFilter} 能吃的形式：忽略 {@code #}、把 {@code ,} 当 {@code |}。
     *
     * ⚠️ 只做这两件事，运算符与括号原样透传（GTM 那套 {@code & | ! ^ ( ) *} 的语义不许在这里被破坏）。
     */
    public static String normalize(@Nullable String raw) {
        if (raw == null) return "";
        return raw.replace(',', '|').replace("#", "").trim();
    }

    /** 取该 key 的标签流；类型不认识时给空流。 */
    private static Stream<? extends TagKey<?>> tagStream(@Nullable AEKey what) {
        if (what instanceof AEItemKey itemKey) {
            return itemKey.toStack(1).getTags();
        }
        if (what instanceof AEFluidKey fluidKey) {
            return fluidKey.toStack(1).getFluid().defaultFluidState().getTags();
        }
        return Stream.<TagKey<?>>empty();
    }
}

