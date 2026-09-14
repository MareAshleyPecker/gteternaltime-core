package rain.gtetcore.gtet.integration.ae2;

import net.minecraft.nbt.CompoundTag;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * 「带标签过滤的 ME 库存部件」共用的那一小块：白 / 黑两条标签表达式 + 一个可缓存的判定器。
 *
 * <p>
 * 与 GTOCore（LGPL-3.0）的 {@code com.gtocore.common.machine.multiblock.part.ae.ITagFilterPartMachine}
 * 是同一个角色（那边也是「接口 + 两个字符串 + 面板」的形状），但**不是**照搬：
 * GTOCore 的接口直接声明包私有的 {@code boolean test(AEKey)} 让子类覆写 GTM 的库存总线，
 * 那需要把 GTM 的 {@code MEStockingBusPartMachine} 整份复制进自己的包（它们确实这么做了，
 * 见 {@code com.gtocore.common.machine.multiblock.part.ae.MEStockingBusPartMachine}）。
 * 官方 GTM 7.5.3 上根本没有 {@code test(AEKey)} 这个方法可覆写，所以 GTET 走的是公开扩展点，
 * 详见 {@link rain.gtetcore.gtet.common.machine.multiblock.part.ETTagFilterStockBusPartMachine} 的类注释。
 *
 * <p>
 * ⚠️ 实现类必须自己持有 {@link ETTagFilter} 实例（而不是每次判定 new 一个），否则「缓存解析结果」就没意义了。
 *
 * @author rain fox
 */
public interface IETTagFilterPart {

    /** 本部件持有的判定器（实现类构造时 new 一次，之后一直复用）。 */
    ETTagFilter getTagFilter();

    /** 白名单原始表达式（玩家写什么就是什么，不做归一化）。 */
    String getTagWhite();

    /** 换白名单表达式。实现里必须顺手调 {@link #applyTagFilter()}。 */
    void setTagWhite(@Nullable String expression);

    /** 黑名单原始表达式。 */
    String getTagBlack();

    /** 换黑名单表达式。实现里必须顺手调 {@link #applyTagFilter()}。 */
    void setTagBlack(@Nullable String expression);

    /** 把当前两条原始表达式喂给判定器（内部会跳过「没变」的情况）。 */
    default void applyTagFilter() {
        getTagFilter().set(getTagWhite(), getTagBlack());
    }

    /** 判定一个 AE key 是否放行。 */
    default boolean testTag(@Nullable AEKey what) {
        return getTagFilter().test(what);
    }

    /**
     * 库存逻辑真正用的谓词：**标签放行 ∧ 不是别的仓已经配置过的东西**。
     * <p>
     * ⚠️ 后半截不能省：GTM 的 {@code IMEStockingPart#addedToController} 默认就是把自动拉取谓词设成
     * 「不同仓去重」检查，我们覆写它时必须把这条语义组合回来，否则同一个多方块里两个库存总成会配置到同一种物品。
     *
     * @param distinctCheck GTM 那边「该 stack 是否已配置在别的库存部件上」的检查
     */
    default Predicate<GenericStack> tagAutoPullTest(Predicate<GenericStack> distinctCheck) {
        return stack -> testTag(stack.what()) && !distinctCheck.test(stack);
    }

    /** 把两条表达式写进给定 tag（数据棒 / 拆方块保存共用一份键名）。 */
    default void writeTagFilter(CompoundTag tag) {
        tag.putString(NBT_TAG_WHITE, getTagWhite());
        tag.putString(NBT_TAG_BLACK, getTagBlack());
    }

    /** 从给定 tag 读回两条表达式；缺键就不动（老存档 / 没存过的数据棒）。 */
    default void readTagFilter(CompoundTag tag) {
        if (tag.contains(NBT_TAG_WHITE)) setTagWhite(tag.getString(NBT_TAG_WHITE));
        if (tag.contains(NBT_TAG_BLACK)) setTagBlack(tag.getString(NBT_TAG_BLACK));
    }

    String NBT_TAG_WHITE = "ETTagWhite";
    String NBT_TAG_BLACK = "ETTagBlack";
}
