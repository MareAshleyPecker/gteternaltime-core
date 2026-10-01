package rain.gtetcore.gtet.api

import com.gregtechceu.gtceu.api.GTValues
import rain.gtetcore.gtet.api.ETValues.ETV_NAME
import rain.gtetcore.gtet.api.ETValues.gtmTierOf
import rain.gtetcore.gtet.api.ETValues.nameOf
import rain.gtetcore.gtet.api.ETValues.voltageOf

/**
 * GTET 自己的档位表 —— 目前只承载一个「**虚档位**」：`ETV`（= `MAX + 1`）。
 *
 * ## 为什么是"虚"的
 * GTM 7.5.3 的电压体系到 `MAX` 就封顶了：`GTValues.V` / `VH` / `VEX` 与名字表 `VN` **都只有 15 项**
 * （索引 `0..14`，`GTValues.MAX = 14`，见 `GTValues.java:60-61` / `:66-67` / `:84` / `:104` / `:154`）。
 * 它们是 `public static final` 数组 —— **长度改不了、引用也换不掉**，所以第 15 档**不可能**作为
 * GTM 的档位索引存在：任何 `GTValues.V[15]` 都会数组越界。
 *
 * 于是 `ETV` 只活在我们自己的账本里（电压、名字、价格计算），**凡是要交给 GTM 的档位都必须先过
 * [gtmTierOf] 夹回合法范围**。这条是硬约定，别绕过。
 *
 * ## 用哪个
 * - **算我们自己的东西**（TF 价格、功率上限、文案里的档位名）→ [voltageOf] / [nameOf]
 * - **交给 GTM 的 API**（`MachineBuilder#tier`、分级外壳查找、配方等级……）→ [gtmTierOf]
 *
 * @author rain fox
 */
object ETValues {

    /** 虚档位号：`MAX + 1`。⚠️ **永远不要**拿它去索引 GTM 的 `GTValues.V` / `VN`。 */
    const val ETV: Int = GTValues.MAX + 1

    /**
     * `ETV` 的电压：`2^33 = 8589934592 EU/t`（= `GTValues.V[MAX] * 4`）。
     *
     * 用字面量而不是 `GTValues.V[GTValues.MAX] * 4`：`const val` 要求编译期常量，而数组取值不是。
     * 本仓 `README/zh_CN/对照表.md` 里 ETV 记的就是这个数。
     */
    const val ETV_VOLTAGE: Long = 8_589_934_592L

    /** `ETV` 的显示名（GTM 的 `VN` 里没有它）。 */
    const val ETV_NAME: String = "ETV"

    /** 本档位表的最高档。 */
    const val MAX_TIER: Int = ETV

    /**
     * 逻辑档位 → 电压。
     *
     * ⚠️ 这是**我们自己的**口径：`ETV` 及以上按 4 倍一档往上推，溢出直接夹到 `Long.MAX_VALUE`
     * （不抛异常 —— 这个方法会在算价格的热路径上被调）。
     *
     * ⚠️ 移位位数必须用 Long 算再夹住：`shl` / `shr` 只用低 6 位，`shl 64` 就等于 `shl 0`，
     * 拿它当"溢出判断"会算出一个很小的值（写这段时踩过一次）。
     */
    @JvmStatic
    fun voltageOf(tier: Int): Long {
        val t = tier.coerceAtLeast(0)
        if (t <= GTValues.MAX) return GTValues.V[t]
        if (t == ETV) return ETV_VOLTAGE
        val shift = 2L * (t - ETV)
        return if (shift >= 63L || ETV_VOLTAGE > (Long.MAX_VALUE shr shift.toInt())) Long.MAX_VALUE
        else ETV_VOLTAGE shl shift.toInt()
    }

    /**
     * 逻辑档位 → **能交给 GTM 的档位**（虚档位夹回 `MAX`）。
     *
     * 凡是把档位传进 GTM 的地方（机器注册、外壳/贴图按档位查表、配方等级判定……）都要过这里，
     * 否则就是上面注释里说的数组越界。
     */
    @JvmStatic
    fun gtmTierOf(tier: Int): Int = tier.coerceIn(0, GTValues.MAX)

    /** 逻辑档位 → 显示名；`ETV` 及以上一律显示 [ETV_NAME]。 */
    @JvmStatic
    fun nameOf(tier: Int): String = if (tier >= ETV) ETV_NAME else GTValues.VN[tier.coerceIn(0, GTValues.MAX)]

    /** 是不是虚档位（`>= ETV`）。 */
    @JvmStatic
    fun isVirtual(tier: Int): Boolean = tier >= ETV
}