package rain.gtetcore.gtet.common.item.terminal

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack

/**
 * 高级终端自己的 8 项设置。
 *
 * ⚠️ 这 8 个键直接写在物品 NBT 的**顶层**，**不在**
 * [TerminalSettings] 管的 `gtet_terminal` 子树里 —— 那是另一棵独立的子树
 * （分级组 / 偏好 / AE 链接）。两棵树互不相干。
 *
 * 读取约定：每个键**独立判存在**，缺哪个就用哪个的默认值（不再用任何哨兵键）。
 * 复选框在 NBT 里存的是 int（0 / 1），不是布尔。
 *
 * @author rain fox
 */
object AdvancedTerminalSettings {

    /** 1 线圈等级（0 = 不指定）。 */
    const val COIL_TIER: String = "CoilTier"

    /** 2 重复结构次数。 */
    const val REPEAT_COUNT: String = "RepeatCount"

    /** 3 无仓室模式。 */
    const val NO_HATCH_MODE: String = "NoHatchMode"

    /** 4 线圈替换模式。 */
    const val REPLACE_COIL_MODE: String = "ReplaceCoilMode"

    /** 5 使用 AE 物品。 */
    const val IS_USE_AE: String = "IsUseAE"

    /** 6 镜像搭建。 */
    const val IS_FLIP: String = "IsFlip"

    /** 7 模块搭建（0 = 主结构）。 */
    const val MODULE: String = "Module"

    /** 8 拆除模式。 */
    const val DEMOLITION: String = "Demolition"

    /** 重复次数的取值范围（界面上限）。 */
    const val REPEAT_MIN: Int = 0
    const val REPEAT_MAX: Int = 1000

    /** 模块档位的取值范围（界面上限）。 */
    const val MODULE_MIN: Int = 0
    const val MODULE_MAX: Int = 100

    /**
     * 一次搭建要用的设置快照 —— 循环开始前读一次，之后都不再碰 NBT。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
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
    data class Snapshot(
        @get:JvmName("coilTier") val coilTier: Int,
        @get:JvmName("repeatCount") val repeatCount: Int,
        @get:JvmName("noHatch") val noHatch: Boolean,
        @get:JvmName("replaceCoil") val replaceCoil: Boolean,
        @get:JvmName("useAe") val useAe: Boolean,
        @get:JvmName("flip") val flip: Boolean,
        @get:JvmName("module") val module: Int,
        @get:JvmName("demolition") val demolition: Boolean,
    )

    /** 读全部 8 项（每键独立判存在，缺失回退默认值）。 */
    @JvmStatic
    fun read(stack: ItemStack): Snapshot {
        return Snapshot(
            getInt(stack, COIL_TIER, 0),
            clamp(getInt(stack, REPEAT_COUNT, 0), REPEAT_MIN, REPEAT_MAX),
            getFlag(stack, NO_HATCH_MODE, true),
            getFlag(stack, REPLACE_COIL_MODE, false),
            getFlag(stack, IS_USE_AE, false),
            getFlag(stack, IS_FLIP, false),
            clamp(getInt(stack, MODULE, 0), MODULE_MIN, MODULE_MAX),
            getFlag(stack, DEMOLITION, false),
        )
    }

    /** 读一个整数键（存的是 int）。 */
    @JvmStatic
    fun getInt(stack: ItemStack, key: String, fallback: Int): Int {
        val tag: CompoundTag? = stack.tag
        return if (tag != null && tag.contains(key, Tag.TAG_ANY_NUMERIC.toInt())) tag.getInt(key) else fallback
    }

    /** 读一个复选框键（存的是 int 0 / 1）。 */
    @JvmStatic
    fun getFlag(stack: ItemStack, key: String, fallback: Boolean): Boolean {
        return getInt(stack, key, if (fallback) 1 else 0) != 0
    }

    /** 写一个整数键。 */
    @JvmStatic
    fun setInt(stack: ItemStack, key: String, value: Int) {
        stack.orCreateTag.putInt(key, value)
    }

    /** 写一个复选框键。 */
    @JvmStatic
    fun setFlag(stack: ItemStack, key: String, value: Boolean) {
        setInt(stack, key, if (value) 1 else 0)
    }

    /** 夹到 [min, max]。 */
    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int {
        return if (value < min) min else Math.min(value, max)
    }
}