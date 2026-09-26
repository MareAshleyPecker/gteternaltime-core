package rain.gtetcore.gtet.common.item.recipe

import net.minecraft.nbt.CompoundTag
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.fluids.FluidType
import net.minecraftforge.fluids.capability.IFluidHandler

/**
 * 配方编辑器的多槽「幽灵流体罐」—— 只存草稿，不参与任何真实物流。
 *
 * 为什么要自己写一个而不是直接用 Forge 的 `FluidTank`：
 *  1. `FluidTank` 是**单槽**，而配方编辑器一个配方可能要 4 个流体输入（装配线）、
 *     6 个流体输出（离心机 / 电解机），必须多槽；
 *  2. `FluidTank` 有真实容量上限并会按上限截断，而草稿里的量是"配方要多少"，
 *     应该原样保存（用户填 2000 mB 就该存 2000 mB，不该被罐子容量砍掉）；
 *  3. 要能整份写进物品 NBT（跟 [RecipeDraft.inputs] / [RecipeDraft.outputs] 一个套路）。
 *
 * 界面侧交给 GTCEu 的 `PhantomFluidWidget`：它正是要一个 Forge 的 `IFluidHandler` +
 * (第几个槽, 取流体, 设流体) 三个参数，所以这个类实现的是 Forge 的 [IFluidHandler]。
 *
 * @author rain fox
 */
class DraftFluidTanks(
    /** 槽位数量（构造时定死，之后永不改变 —— 见 [deserializeNBT] 的说明）。 */
    private val capacity: Int,
) : IFluidHandler {

    private val tanks: Array<FluidStack> = Array(capacity) { FluidStack.EMPTY }

    // ======================== 草稿侧读写 ========================

    /** 第 [i] 个槽的流体（越界返回空，绝不抛异常）。 */
    fun getFluid(i: Int): FluidStack = if (i in tanks.indices) tanks[i] else FluidStack.EMPTY

    /**
     * 设置第 [i] 个槽。`null` / 空栈都表示"清空"。
     *
     * [amount] 大于 0 时用它覆盖流体自带的量（界面上的"流体量"字段就走这条路，
     * 否则拖一桶进来永远只有 1000 mB，做不了"要 2000 mB 硫酸"这种配方）。
     *
     * 存进去的是 [FluidStack.copy]，不是调用方的那个对象：
     * 拖进来的栈可能来自 JEI/EMI 的缓存或玩家手上的容器，直接持有引用的话
     * 别人一改（比如把 amount 改掉）草稿就跟着变了。
     */
    fun setFluid(i: Int, stack: FluidStack?, amount: Int = 0) {
        if (i !in tanks.indices) return
        if (stack == null || stack.isEmpty) {
            tanks[i] = FluidStack.EMPTY
            return
        }
        val copy = stack.copy()
        if (amount > 0) copy.amount = amount
        tanks[i] = copy
    }

    /** 整个罐子是否全空。 */
    fun isEmpty(): Boolean = tanks.all { it.isEmpty }

    // ======================== IFluidHandler（Forge）========================
    // 这些方法只服务于界面控件的显示与"用容器点击槽"的交互，不是真实物流。

    override fun getTanks(): Int = capacity

    override fun getFluidInTank(tank: Int): FluidStack = getFluid(tank)

    /**
     * 单槽"容量"。
     *
     * ⚠️ 这里返回的**不是**罐子的真实上限（草稿本来就没有上限），而是刻意返回
     * `max(1 桶, 当前量)`：
     *  - `TankWidget` 画流体时算的是 `progress = amount / max(amount, 容量)`，
     *    容量取真实大数（比如 1000000）的话，填 1000 mB 的槽只画出千分之一的液面，
     *    幽灵槽看起来就跟空的一样；取当前量则恒为"满"，一眼就能看出"这个槽有东西"；
     *  - `TankWidget` 的悬浮提示会显示"当前量 / 容量"，容量 = 当前量读起来是
     *    "1000 / 1000 mB"，对一个幽灵槽来说比"1000 / 1000000 mB"合理得多。
     */
    override fun getTankCapacity(tank: Int): Int = maxOf(FluidType.BUCKET_VOLUME, getFluid(tank).amount)

    override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = true

    /** 找第一个空槽或同流体槽放进去（幽灵语义：直接覆盖，不按容量截断）。 */
    override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
        if (resource.isEmpty) return 0
        val target = tanks.indexOfFirst { it.isEmpty || it.isFluidEqual(resource) }
        if (target < 0) return 0
        if (action.execute()) setFluid(target, resource)
        return resource.amount
    }

    /** 按流体种类取走整槽。 */
    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        if (resource.isEmpty) return FluidStack.EMPTY
        val target = tanks.indexOfFirst { !it.isEmpty && it.isFluidEqual(resource) }
        if (target < 0) return FluidStack.EMPTY
        val drained = tanks[target].copy()
        if (action.execute()) setFluid(target, null)
        return drained
    }

    /** 按数量取走第一个非空槽（数量不足就只给这么多）。 */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        if (maxDrain <= 0) return FluidStack.EMPTY
        val target = tanks.indexOfFirst { !it.isEmpty }
        if (target < 0) return FluidStack.EMPTY
        val stack = tanks[target]
        val drained = stack.copy()
        drained.amount = minOf(maxDrain, stack.amount)
        if (action.execute()) {
            if (drained.amount >= stack.amount) setFluid(target, null) else {
                val rest = stack.copy()
                rest.amount = stack.amount - drained.amount
                setFluid(target, rest)
            }
        }
        return drained
    }

    // ======================== NBT ========================

    /** 整罐写进 NBT：`Size` + 每个非空槽一个 `Tank{N}` 子标签。 */
    fun serializeNBT(): CompoundTag {
        val tag = CompoundTag()
        tag.putInt(SIZE_KEY, capacity)
        for (i in tanks.indices) {
            if (tanks[i].isEmpty) continue
            tag.put(TANK_PREFIX + i, tanks[i].writeToNBT(CompoundTag()))
        }
        return tag
    }

    /**
     * 从 NBT 读回。
     *
     * ⚠️ **这里绝不读 `Size`，也绝不改变自己的槽位数量** —— 这一点和
     * `ItemStackHandler.deserializeNBT`（它会 `setSize(nbt.getInt("Size"))`，
     * 拿存档里的旧容量覆盖当前容量，进而重建整个槽位数组）完全相反，
     * 也是[RecipeDraft] 里那段"打不开界面"的历史教训的直接防线：
     *  - 按 `0 until capacity` 逐槽读，存档里多的槽直接忽略（不会越界、不会缩容）；
     *  - 存档里少的槽保持空（不会因为存档缺键就崩）；
     *  - 容量永远是构造时那个数，[RecipeDraft.fluidInput] 之类的上界判断因此永远成立。
     *
     * 另外读之前先清空：反序列化是"覆盖"语义，不先把旧内容抹掉的话，
     * 存档里删掉的槽会留下上一次的残留。
     *
     * @return 是否读到了流体数据（存档里一个槽都没有就是 false）
     */
    fun deserializeNBT(raw: CompoundTag?): Boolean {
        tanks.fill(FluidStack.EMPTY)
        if (raw == null) return false

        var found = false
        for (i in tanks.indices) {
            val entry = raw.getCompound(TANK_PREFIX + i)
            if (entry.isEmpty) continue

            // loadFluidStackFromNBT 对缺键 / 流体名不合法 / 未注册的流体都返回 EMPTY，
            // 所以这里不用 try-catch；amount <= 0 的脏数据也一并当成空处理。
            val stack = FluidStack.loadFluidStackFromNBT(entry)
            if (stack == null || stack.isEmpty || stack.amount <= 0) continue
            tanks[i] = stack
            found = true
        }
        return found
    }

    companion object {
        /** NBT 里的容量键（只为可读性保留，读取时会被忽略 —— 见 [deserializeNBT]）。 */
        private const val SIZE_KEY: String = "Size"

        /** 每个槽的子标签前缀。 */
        private const val TANK_PREFIX: String = "Tank"
    }
}
