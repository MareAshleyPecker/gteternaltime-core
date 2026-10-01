package rain.gtetcore.gtet.api.timeflow
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.lowdragmc.lowdraglib.syncdata.ISubscription

/**
 * **TF 内容处理器**：GTM 真正用来扣 TF 的那个口子，内部就是一个 long 缓冲区。
 *
 * ## GTM 什么时候、从哪条路径调它
 * 「每 tick 扣一次」成立与否全看下面这条链路（GTM 7.5.3）：
 * ```
 * RecipeLogic#serverTick            (RecipeLogic.java:206)
 *  └─ handleRecipeWorking()         (:213, 定义在 :278)
 *      ├─ handleTickRecipe(lastRecipe)                     (:282, 定义在 :377)
 *      │   ├─ RecipeHelper.matchTickRecipe(machine, recipe) (:380 → RecipeHelper.java:182-184)
 *      │   │    └─ matchRecipe(..., tick=true)              (RecipeHelper.java:186-196)  ← simulated=true
 *      │   └─ handleTickRecipeIO(recipe, IO.IN)             (:383 → RecipeHelper.java:205-210) ← simulated=false
 *      │        └─ RecipeHelper.handleRecipe(...)           (RecipeHelper.java:218)
 *      │             └─ new RecipeRunner(recipe, io, isTick=true, ..., simulated)  (RecipeHelper.java:222)
 *      │                  └─ RecipeRunner#handle(tickInputs)  (RecipeRunner.java:62 → :69)
 *      │                       └─ handleContents()            (:121)
 *      │                            └─ RecipeHandlerList#handleRecipe(io, recipe, contents, false)   (:214, 定义在 RecipeHandlerList.java:178)
 *      │                                 └─ IRecipeHandler#handleRecipe(...)  (IRecipeHandler.java:75-81 的 default)
 *      │                                      └─ **本类的 handleRecipeInner(io, recipe, left, simulate=false)**  (IRecipeHandler.java:37)
 *      └─ 成功后 progress++ (:289)  →  失败则 setWaiting(:292)
 * ```
 * 结论：**只要一条配方的 `tickInputs` 里带 TF，配方每跑一 tick 就会以 `simulate=false` 调一次
 * [handleRecipeInner]**；匹配阶段（`simulate=true`）每 tick 也会被调一次，但不扣钱。
 *
 * ## 为什么实现 [IRecipeHandlerTrait] 而不是裸 [com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler]
 * GTM 里**没有** `IContentHandler` 这个名字，对应物就是 `IRecipeHandler<K>`；而在机器 / 多方块上
 * 能挂进 `RecipeHandlerList` 的形态是 `IRecipeHandlerTrait<K>`（多了 `getHandlerIO()` 与变更订阅，
 * `IRecipeHandlerTrait.java:8-16`）。本类多实现那两个方法只多 4 行，却能被
 * `RecipeHandlerList.of(IO.IN, this)` 直接收下、也能被 GTM 的 `RecipeLogic` 订阅变更——
 * 所以按 trait 实现，接仓的那一侧不用再包一层。
 *
 * ## 与「时序仓 / 动力仓」的分工
 * 本类只管**缓冲与扣费**，不负责从塔里拉货、也不做速率整形；上下限由构造参数给出。
 * 真实仓（时流仓 / 动力仓）应各自持有一个本类实例，并在自己的 tick 里
 * [insert] / [extract]（`NotifiableRecipeHandlerTrait` 那套 MachineTrait 生命周期之外，
 * 也可以用更贴合的 `NotifiableRecipeHandlerTrait` 子类重写；本类刻意不继承它，避免绑死机器）。
 *
 * @param initialAmount 初始存量，单位 TF
 * @param capacity      缓冲上限，单位 TF（原型默认 `Long.MAX_VALUE`＝不设限）
 * @author rain fox
 */
class ETTimeFlowHandler(
    initialAmount: Long = 0L,
    @JvmField val capacity: Long = Long.MAX_VALUE,
) : IRecipeHandlerTrait<ETTimeFlowStack> {

    private val listeners: MutableList<Runnable> = ArrayList()

    /** 缓冲区里的 TF 存量。 */
    var amount: Long = initialAmount.coerceIn(0L, capacity)
        private set

    /** 收入 TF（塔 / 动力仓往里灌）。返回**没装下**的那部分。 */
    fun insert(toInsert: Long): Long {
        if (toInsert <= 0L) return 0L
        val accepted = toInsert.coerceAtMost(capacity - amount)
        if (accepted <= 0L) return toInsert
        amount += accepted
        notifyListeners()
        return toInsert - accepted
    }

    /** 取出 TF（时序之瓶 / 手动取）。返回**实际取到**的数量。 */
    fun extract(toExtract: Long): Long {
        if (toExtract <= 0L) return 0L
        val taken = toExtract.coerceAtMost(amount)
        amount -= taken
        notifyListeners()
        return taken
    }

    override fun getCapability(): RecipeCapability<ETTimeFlowStack> = ETTimeFlowCapability.CAP

    /** TF 仓是**输入侧**仓（把 TF 供进配方）。 */
    override fun getHandlerIO(): IO = IO.IN

    override fun getContents(): List<Any> =
        if (amount > 0L) listOf(ETTimeFlowStack(amount)) else emptyList()

    override fun getTotalContentAmount(): Double = amount.toDouble()

    override fun addChangedListener(listener: Runnable): ISubscription {
        listeners.add(listener)
        return ISubscription { listeners.remove(listener) }
    }

    /** 通知订阅者「存量变了」。实现 `NotifiableRecipeHandlerTrait` 时这一步由父类负责。 */
    fun notifyListeners() {
        for (listener in listeners) listener.run()
    }

    /**
     * 扣费本体。
     *
     * ### 返回值语义（照抄 `IRecipeHandler.java:31-37` 的约定）
     * - `null` = **这笔内容已经被处理干净**，GTM 认为成功并停止向后面的仓传递；
     * - 非 null = **还没处理完的部分**，`RecipeHandlerList.java:186-194` 会把它交给同组的下一个仓。
     *
     * 所以这里是**尽量多扣、扣不完的原样退回**：一台多方块里挂两个 TF 仓时，它们会自动合池。
     * 会不会出现「扣了一部分而整 tick 失败 ⇒ 白烧 TF」？不会：
     * `RecipeLogic#handleTickRecipe` 先跑 `simulate=true` 的匹配（`RecipeLogic.java:380`），
     * 匹配不过就直接 `setWaiting` 走人（:381），根本到不了这次真实扣费（:383）。
     *
     * @param io      调用方给的 IO；只有与本仓 [getHandlerIO] 一致时才处理
     * @param simulate `true` = 只试算不扣（匹配阶段）
     * @return 剩余未满足的 TF，或 `null` 表示已结清
     */
    override fun handleRecipeInner(
        io: IO,
        recipe: GTRecipe,
        left: List<ETTimeFlowStack>,
        simulate: Boolean,
    ): List<ETTimeFlowStack>? {
        if (io != getHandlerIO()) return left

        var remaining = 0L
        for (stack in left) if (stack.amount > 0L) remaining += stack.amount
        if (remaining <= 0L) return null
        if (amount <= 0L) return left

        val consumed = remaining.coerceAtMost(amount)
        if (!simulate && consumed > 0L) {
            amount -= consumed
            notifyListeners()
        }

        val leftover = remaining - consumed
        return if (leftover <= 0L) null else listOf(ETTimeFlowStack(leftover))
    }
}