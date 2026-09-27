package rain.gtetcore.gtet.common.item.terminal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 高级终端自己的 8 项设置。
 *
 * <p>⚠️ 这 8 个键直接写在物品 NBT 的<b>顶层</b>，<b>不在</b>
 * {@link TerminalSettings} 管的 {@code gtet_terminal} 子树里 —— 那是另一棵独立的子树
 * （分级组 / 偏好 / AE 链接）。两棵树互不相干。
 *
 * <p>读取约定：每个键<b>独立判存在</b>，缺哪个就用哪个的默认值（不再用任何哨兵键）。
 * 复选框在 NBT 里存的是 int（0 / 1），不是布尔。
 *
 * @author rain fox
 */
public final class AdvancedTerminalSettings {

    /** 1 线圈等级（0 = 不指定）。 */
    public static final String COIL_TIER = "CoilTier";
    /** 2 重复结构次数。 */
    public static final String REPEAT_COUNT = "RepeatCount";
    /** 3 无仓室模式。 */
    public static final String NO_HATCH_MODE = "NoHatchMode";
    /** 4 线圈替换模式。 */
    public static final String REPLACE_COIL_MODE = "ReplaceCoilMode";
    /** 5 使用 AE 物品。 */
    public static final String IS_USE_AE = "IsUseAE";
    /** 6 镜像搭建。 */
    public static final String IS_FLIP = "IsFlip";
    /** 7 模块搭建（0 = 主结构）。 */
    public static final String MODULE = "Module";
    /** 8 拆除模式。 */
    public static final String DEMOLITION = "Demolition";

    /** 重复次数的取值范围（界面上限）。 */
    public static final int REPEAT_MIN = 0;
    public static final int REPEAT_MAX = 1000;
    /** 模块档位的取值范围（界面上限）。 */
    public static final int MODULE_MIN = 0;
    public static final int MODULE_MAX = 100;

    private AdvancedTerminalSettings() {}

    /**
     * 一次搭建要用的设置快照 —— 循环开始前读一次，之后都不再碰 NBT。
     *
     * @param coilTier    线圈等级（0 = 不指定）
     * @param repeatCount 可重复层的重复次数
     * @param noHatch     无仓室模式
     * @param replaceCoil 线圈替换模式
     * @param useAe       使用 AE 物品
     * @param flip        镜像搭建
     * @param module      模块档位（0 = 主结构）
     * @param demolition  拆除模式
     */
    public record Snapshot(int coilTier, int repeatCount, boolean noHatch, boolean replaceCoil,
                           boolean useAe, boolean flip, int module, boolean demolition) {}

    /** 读全部 8 项（每键独立判存在，缺失回退默认值）。 */
    public static Snapshot read(ItemStack stack) {
        return new Snapshot(
                getInt(stack, COIL_TIER, 0),
                clamp(getInt(stack, REPEAT_COUNT, 0), REPEAT_MIN, REPEAT_MAX),
                getFlag(stack, NO_HATCH_MODE, true),
                getFlag(stack, REPLACE_COIL_MODE, false),
                getFlag(stack, IS_USE_AE, false),
                getFlag(stack, IS_FLIP, false),
                clamp(getInt(stack, MODULE, 0), MODULE_MIN, MODULE_MAX),
                getFlag(stack, DEMOLITION, false));
    }

    /** 读一个整数键（存的是 int）。 */
    public static int getInt(ItemStack stack, String key, int fallback) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) : fallback;
    }

    /** 读一个复选框键（存的是 int 0 / 1）。 */
    public static boolean getFlag(ItemStack stack, String key, boolean fallback) {
        return getInt(stack, key, fallback ? 1 : 0) != 0;
    }

    /** 写一个整数键。 */
    public static void setInt(ItemStack stack, String key, int value) {
        stack.getOrCreateTag().putInt(key, value);
    }

    /** 写一个复选框键。 */
    public static void setFlag(ItemStack stack, String key, boolean value) {
        setInt(stack, key, value ? 1 : 0);
    }

    /** 夹到 [min, max]。 */
    public static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
