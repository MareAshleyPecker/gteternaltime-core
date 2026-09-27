package rain.gtetcore.gtet.api.capability

import com.gregtechceu.gtceu.api.recipe.content.IContentSerializer
import com.mojang.serialization.Codec
import org.apache.commons.lang3.math.NumberUtils

/**
 * 时间流（TF）的**配方内容**：一个 long 数量，单位 TF。
 *
 * ## 存量与流量共用同一个 long
 * 放进 `tickInputs` 就是「每 tick 消耗多少 TF」，放进 `inputs` 就是「开工时一次性消耗多少 TF」，
 * 由 GTM 的 tick 输入机制负责按 tick 扣（见 [ETTimeFlowHandler] 的注释）。
 * 换算基准：`1 TF = 1A IV × 1 tick = 8192 EU`（设定文档 §3.1）。
 *
 * ## 为什么不用裸 `Long`
 * GTM 自带 [com.gregtechceu.gtceu.api.recipe.content.SerializerLong]，
 * 直接写 `RecipeCapability<Long>` 也能跑（`CWURecipeCapability` 就是 `RecipeCapability<Integer>`）。
 * 但 TF 是**独立于 EU 的第二种能量记账**，专属类型让「TF 内容」在代码里一眼可辨，
 * 也为以后换精度（设定 §4 提到的 mTF）留了位置；代价只是一个 4 行的序列化器。
 */
data class ETTimeFlowStack(
    /** 数量，单位 TF。负数没有意义，由使用方保证非负。 */
    val amount: Long,
) {
    companion object {
        /** 空内容，用作 [IContentSerializer.defaultValue]。 */
        @JvmField
        val EMPTY: ETTimeFlowStack = ETTimeFlowStack(0L)
    }
}

/**
 * [ETTimeFlowStack] 的内容序列化器。
 *
 * ## 只需要实现 4 个方法
 * `IContentSerializer` 的抽象成员只有 `of` / `defaultValue` / `contentClass` / `codec`
 * （`IContentSerializer.java:33,35,54,56`）；`toNetwork` / `fromNetwork` / `toJson` / `fromJson` /
 * `toNbt` / `fromNbt` **全部是基于 [codec] 的 default 方法**（`IContentSerializer.java:17-31,78-84`），
 * 所以这里不重写它们。
 *
 * ## 序列化格式
 * 与 `SerializerLong` 同构：JSON / NBT 里就是一个普通数字，网络里是 varint long。
 */
object ETTimeFlowSerializer : IContentSerializer<ETTimeFlowStack> {

    private val CODEC: Codec<ETTimeFlowStack> = Codec.LONG.xmap({ ETTimeFlowStack(it) }, { it.amount })

    override fun of(o: Any?): ETTimeFlowStack = when (o) {
        is ETTimeFlowStack -> o
        is Number -> ETTimeFlowStack(o.toLong())
        is CharSequence -> ETTimeFlowStack(NumberUtils.toLong(o.toString(), 0L))
        else -> ETTimeFlowStack.EMPTY
    }

    override fun defaultValue(): ETTimeFlowStack = ETTimeFlowStack.EMPTY

    override fun contentClass(): Class<ETTimeFlowStack> = ETTimeFlowStack::class.java

    override fun codec(): Codec<ETTimeFlowStack> = CODEC
}
