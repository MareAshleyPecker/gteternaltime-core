package rain.gtetcore.gtet.util

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import rain.gtetcore.gtet.util.NumberUtil.getLongValue
import java.math.BigInteger
import java.text.DecimalFormat

/**
 * 大数字短格式化：`1500 -> 1.5K`，按 1000 进制一路到 `D`。
 *
 * 单位表只到 `D`（10^36 量级），再大就在最后一档里继续用十进制写。
 *
 * @author rain fox
 */
object NumberUtil {

    /** 单位后缀，下标 = 除以 1000 的次数。 */
    private val UNITS = arrayOf("", "K", "M", "G", "T", "P", "E", "Z", "Y", "B", "N", "D")

    /** `Long.MAX_VALUE` 的 `BigInteger` 形式，配合 [getLongValue] 做饱和转换。 */
    @JvmField
    val BIG_INTEGER_MAX_LONG: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)

    @JvmStatic
    fun formatLong(number: Long): String = formatDouble(number.toDouble())

    @JvmStatic
    fun formatDouble(number: Double): String {
        val df = DecimalFormat("#.##")
        var temp = number
        var unitIndex = 0
        while (temp >= 1000 && unitIndex < UNITS.size - 1) {
            temp /= 1000
            unitIndex++
        }
        return df.format(temp) + UNITS[unitIndex]
    }

    @JvmStatic
    fun numberText(number: Double): MutableComponent = Component.literal(formatDouble(number))

    @JvmStatic
    fun numberText(number: Long): MutableComponent = Component.literal(formatLong(number))

    /** 超过 `Long` 上限就饱和到 `Long.MAX_VALUE`，不抛异常。 */
    @JvmStatic
    fun getLongValue(bigInt: BigInteger): Long =
        if (bigInt > BIG_INTEGER_MAX_LONG) Long.MAX_VALUE else bigInt.toLong()
}