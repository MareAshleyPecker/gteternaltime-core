package rain.gtetcore.gtet.common.item.timeflow

import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.api.timeflow.ITimeFlowStorage

/**
 * [ITimeFlowStorage] 的**物品 NBT 实现** —— 时序之瓶用的就是它。
 *
 * 这是个薄壳：真正的读写都在 [TimeClockData] 里，写进去立刻落在物品 NBT 上，
 * 所以不需要「用完回写」这种同步动作。
 *
 * ⚠️ 同一件物品的多个 [ItemStack] 实例各拿各的壳，**互相看不见对方的内存副本**
 * —— 但本实现没有内存副本，读写每次都直查 NBT，所以不存在跨壳不一致的问题。
 * （注意 `ItemStack` 的 NBT 本身仍是「谁拿着谁改」的语义，客户端改的不会自动同步回服务端。）
 */
class TimeBottleNbtStorage private constructor(private val stack: ItemStack) : ITimeFlowStorage {

    override fun getTimeFlow(): Long = TimeClockData.getTimeFlow(stack)

    override fun setTimeFlow(amount: Long): Long = TimeClockData.setTimeFlow(stack, amount)

    override fun getTimeFlowCapacity(): Long = TimeClockData.getCapacity(stack)

    companion object {
        /** 包一层；同一件物品多次调用会得到多个壳（都是同一个 NBT，行为一致）。 */
        @JvmStatic
        fun of(stack: ItemStack): TimeBottleNbtStorage = TimeBottleNbtStorage(stack)
    }
}