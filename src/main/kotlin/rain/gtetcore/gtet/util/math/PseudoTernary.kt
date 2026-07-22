package rain.gtetcore.gtet.util.math

/**
 * 伪三元运算符：condition then valueIfTrue or valueIfFalse
 *
 * 用法：
 * ```
 * Condition then T or F
 * ```
 */
class ElseBuilder<T>(private val condition: Boolean, private val trueValue: T) {
    infix fun or(falseValue: T): T = if (condition) trueValue else falseValue
}

infix fun <T> Boolean.then(trueValue: T): ElseBuilder<T> = ElseBuilder(this, trueValue)