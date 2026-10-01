package rain.gtetcore.gtet.integration.ae2

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait
import com.gregtechceu.gtceu.api.machine.trait.NotifiableRecipeHandlerTrait
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerGroupDistinctness
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerList
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine.InternalSlot
import com.lowdragmc.lowdraglib.syncdata.ISubscription
import net.minecraft.world.item.crafting.Ingredient
import rain.gtetcore.gtet.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine
import java.util.*

/**
 * 「ME 样板总成镜像」的配方处理代理表：**每个样板槽一份 [RecipeHandlerList]**，
 * 把多方块要吃的输入转发到宿主总成对应槽里。
 *
 * ## 为什么要每个槽一份（不能合并成一份）
 *
 * 总成本体是用 `InternalSlotRecipeHandler` 一格一份 RHL 建的表，并声明
 * `isDistinct() == true` / 分组 `BUS_DISTINCT`：多方块的配方逻辑据此把**每个样板槽**
 * 当成一条独立的输入总线（GTM 的样板总成就是靠这个「一格一份」来同时供多种输入的）。
 * 镜像若把 N 个槽并成一份，distinct 语义就变了，某些需要「来自不同总线」的配方会判不出来。
 * 所以这里与 GTM 的 `ProxySlotRecipeHandler` 同构：一份 RHL 里放 5 个转发器
 * （电路槽 / 共享库存 / 本槽物品 / 共享流体仓 / 本槽流体）。
 *
 * ## 与 GTM 那版唯一的实现差别：转发目标下沉到 InternalSlot
 *
 * GTM 的镜像转发到它自己的 `InternalSlotRecipeHandler.SlotRHL#getItemRecipeHandler()`，
 * 但 `SlotRHL` 是 **protected 嵌套类**，跨包既不能点名也不能继承
 * （GTOCore / BetterGregTechAndAppliedEnergistics 那两家的解法是**连 InternalSlotRecipeHandler
 * 一起复制一份**，我们不做）。这里改成直接吃 public 的 [InternalSlot]：
 * `handleItemInternal`/`handleFluidInternal`/`getItems`/`getFluids`
 * 都是 public，行为与 GTM 的 `SlotItemRecipeHandler`/`SlotFluidRecipeHandler`
 * 逐个对应（含 `HIGH + 槽号 + 1` 的优先级与「空槽直接放过」的短路）。
 * 坏处是多了一层自己的转发器对象，好处是一行 GTM 代码都不用复制。
 *
 * ## 表为什么按「已登记的最大容量」一次建死（**本类的核心取舍**）
 *
 * 镜像只有 LuV 一件，却要能连 27 / 63 / 126 / 216 任何一档的总成，所以表长在**构造期**就得
 * 取一个"够所有档位用"的值（[ETPatternBufferCapacities.maxCapacity]）。
 * 为什么不能在「连上宿主的时刻」按宿主的实际容量重建表 —— 这是本轮专门核实的点，证据链：
 *
 * 1. `WorkableMultiblockMachine#onStructureFormed`（源码 121-141 行）在**成型那一刻**
 *    遍历 `getParts()`、拿走每个部件的 `getRecipeHandlers()`，逐个
 *    `this.addHandlerList(handlerList)` 并订阅；
 * 2. `IRecipeCapabilityHolder#addHandlerList`（源码 37-47 行）把**这一批 RHL 对象与它们
 *    内部的 `IRecipeHandler` 引用**拷进控制器的 `capabilitiesProxy` /
 *    `capabilitiesFlat` 两张表；
 * 3. 多方块侧全项目只有 `onStructureFormed` 这一处调用它（`grep addHandlerList` 只有
 *    WorkableMultiblockMachine:138 / 153 两行是多方块路径），`onStructureInvalid` 才会清表。
 *    也就是说：**成型之后再改我们返回的 List，控制器不会重新收集**，只会继续拿旧对象。
 *
 * 好消息是"收集的是对象、不是快照值"：所以只要**对象本身**在，绑定时改它们的目标指针就立刻生效
 * （这也是 GTM 自己 `ProxySlotRecipeHandler` 的做法）。
 * 于是方案是：表按最大容量建死，绑定时前 N 个指到宿主的 N 个槽，**宿主用不到的格子整条解绑**
 * （与 GTM 未绑定 ProxyRHL 同语义：`handleRecipeInner` 原样返回、`getSize()==0`、
 * `getContents()` 为空、优先级 LOW），于是它们既不会转发也不会订阅任何东西。
 *
 * ### 代价（诚实记账）
 *
 * 低档总成（27）配这件镜像时，控制器里会多出 216-27=189 条**空** RHL —— 每次配方匹配/并行检查
 * 都会各自被"试一次然后跳过"。同样的量级 GTM 自己也吃：`InternalSlotRecipeHandler`
 * 是**一槽一条 RHL**，所以 216 档总成本来就有 216 条。语义上无影响（空 RHL 在
 * `RecipeRunner#handleContents` 的 BUS_DISTINCT 循环里永远返回未消耗完的 left → continue，
 * 在 `ItemRecipeCapability#getInputContents` 里因内容为空被跳过）。
 *
 * 被否掉的另一条路：绑定时重建一个"正好等于宿主容量"的表，然后手动让控制器重收集
 * （`MultiblockControllerMachine#checkPattern()` 是 public、`onStructureFormed()`
 * 会 clear 两张表再收）。不做，因为：它要在**玩家插闪存**这一刻跑一次完整结构校验并重放
 * 部件的成型/失效回调（`onStructureInvalid` → `recipeLogic.resetRecipeLogic()` 等），
 * 而镜像**未必已经成型**（先绑后建是常规玩法），两条时序都得兜；相比之下"建大表"只是一点空遍历。
 *
 * @author rain fox
 */
class ETProxySlotRecipeHandler(machine: MetaMachine) {

    /**
     * 取整张代理表（本表按最大容量建死，见类注释）。
     *
     * ⚠️ 这里是**手写的 getter**，原来由 Lombok `@Getter` 生成，两者字节码等价；
     * 改成手写只为一件事：**Kotlin 侧要看得见它**。同一个 source set 里 kotlinc 解析 Java
     * 源码时只做轻量类（light class），**不跑注解处理器**（本项目的 Lombok 插件也只挂在
     * `JavaCompile` 上），所以 Lombok 生成的方法在 Kotlin 里是"不存在"的 ——
     * `ETMEPatternBufferProxyPartMachine.kt` 的 `getRecipeHandlers()` 必须能读到这张表。
     *
     * ⚠️ Kotlin 侧以**属性**形式暴露，JVM 上仍然是 `getProxySlotHandlers()`。
     */
    val proxySlotHandlers: List<RecipeHandlerList>

    init {
        val slots = ETPatternBufferCapacities.maxCapacity()
        val table = ArrayList<RecipeHandlerList>(slots)
        for (i in 0 until slots) {
            table.add(ProxyRHL(machine, i))
        }
        proxySlotHandlers = table
    }

    /** 本表能转发多少个样板槽（= 已登记的最大容量，也是 `getProxySlotHandlers().size`）。 */
    val slotCount: Int
        get() = proxySlotHandlers.size

    /**
     * 绑定宿主：第 i 个镜像槽 → 宿主第 i 个槽；**宿主没有的格子（i ≥ 宿主容量）整条解绑**。
     *
     * 因为表按最大容量建死，低档宿主天然只用到前面一段，剩下的格子保持"空代理"，
     * 这正是 GTM 未绑定 ProxyRHL 的语义，不再需要按档警告（见
     * `ETMEPatternBufferProxyPartMachine` 的类注释）。
     *
     * @param buffer 宿主总成（本 mod 的多阶段样板总成）
     */
    fun updateProxy(buffer: ETMEPatternBufferPartMachine) {
        val slots = buffer.getInternalInventory()
        for (i in proxySlotHandlers.indices) {
            (proxySlotHandlers[i] as ProxyRHL).bind(buffer, if (i < slots.size) slots[i] else null)
        }
    }

    /** 解绑：全部退回空代理（清闪存绑定 / 拆方块时用）。 */
    fun clearProxy() {
        for (handler in proxySlotHandlers) {
            (handler as ProxyRHL).unbind()
        }
    }

    /**
     * 一份「槽级」代理：5 个转发器 + 挂在宿主槽上的内容变化回调。
     *
     * `bind` / `unbind` / `setProxy` / `setSlot` 要跨这几个嵌套类互相调用，所以是 public。
     */
    private class ProxyRHL(machine: MetaMachine, index: Int) : RecipeHandlerList(IO.IN) {

        private val circuit: ProxyItemHandler = ProxyItemHandler(machine)
        private val sharedItem: ProxyItemHandler = ProxyItemHandler(machine)
        private val slotItem: SlotItemHandler = SlotItemHandler(machine, index)
        private val sharedFluid: ProxyFluidHandler = ProxyFluidHandler(machine)
        private val slotFluid: SlotFluidHandler = SlotFluidHandler(machine, index)

        /** 当前绑定的宿主槽（解绑时要按它还原回调）。 */
        private var boundSlot: InternalSlot? = null

        /** 绑定前宿主槽上的内容变化回调，解绑时原样装回去。 */
        private var boundSlotPreviousCallback: Runnable? = null

        /** 我们装上去的那层回调，用来判断「链顶还是不是自己」。 */
        private var boundSlotCallback: Runnable? = null

        init {
            addHandlers(circuit, sharedItem, slotItem, sharedFluid, slotFluid)
            group = RecipeHandlerGroupDistinctness.BUS_DISTINCT
        }

        fun bind(buffer: ETMEPatternBufferPartMachine, slot: InternalSlot?) {
            // 宿主没有这一格（低档总成配这件通用镜像的常规情形）：整条解绑 ——
            // 共享设施也不挂，免得给宿主的电路槽/共享库存白加几百个内容变化订阅。
            if (slot == null) {
                unbind()
                return
            }
            // 宿主总成的三件共享设施：电路槽、共享库存、共享流体仓（与 GTM 的 SlotRHL 装的是同一批对象）
            circuit.setProxy(buffer.getCircuitInventory())
            sharedItem.setProxy(buffer.getShareInventory())
            sharedFluid.setProxy(buffer.getShareTank())

            restoreSlotCallback() // 重新绑定时先还原，避免回调层层叠加
            boundSlot = slot
            slotItem.setSlot(slot)
            slotFluid.setSlot(slot)

            // ⚠️ InternalSlot 只有一个 onContentsChanged 回调位，GTM 自己的
            // SlotItemRecipeHandler/SlotFluidRecipeHandler 也在这里装了回调（后装的把先装的顶掉）。
            // 我们**套娃**而不是覆盖：先跑原有回调，再通知本镜像的两个转发器；
            // 解绑时把这一层摘掉（GTM 那版是直接覆盖、解绑也不还原）。
            val previous = slot.onContentsChanged
            val layer = Runnable {
                previous.run()
                slotItem.notifyListeners()
                slotFluid.notifyListeners()
            }
            boundSlotPreviousCallback = previous
            boundSlotCallback = layer
            slot.onContentsChanged = layer
        }

        fun unbind() {
            circuit.setProxy(null)
            sharedItem.setProxy(null)
            sharedFluid.setProxy(null)
            restoreSlotCallback()
            boundSlot = null
            slotItem.setSlot(null)
            slotFluid.setSlot(null)
        }

        /**
         * 摘掉本层回调。
         *
         * ⚠️ 只在「链顶仍是自己」时还原：同一个总成上可以挂多个镜像，它们会在**同一批槽**上层层套娃，
         * 若某个先解绑就无条件还原，会把后来者的那一层一起抹掉（它就不再收到内容变化通知，
         * 多方块可能要等到别的触发点才重新判定配方）。不是链顶就留着不管 —— 本层转到空槽后
         * 只是空通知，不再有任何副作用。
         */
        private fun restoreSlotCallback() {
            // 先取局部量（可变属性不能智能转换）；判断链顶用引用比较（===）
            val slot = boundSlot
            val previous = boundSlotPreviousCallback
            val callback = boundSlotCallback
            if (slot != null && previous != null && callback != null &&
                slot.onContentsChanged === callback
            ) {
                slot.onContentsChanged = previous
            }
            boundSlotPreviousCallback = null
            boundSlotCallback = null
        }

        override fun isDistinct(): Boolean {
            return true
        }

        /**
         * 与 GTM 一致：镜像槽**永远**是 distinct 总线，配方逻辑不许改这一位。
         *
         * 显式 `public`：被覆写的父类成员是 protected，Kotlin 的 override 默认会继承它的可见性。
         */
        public override fun setDistinct(ignored: Boolean, notify: Boolean) {}
    }

    /**
     * 转发到宿主总成**现成的**配方处理器（电路槽 / 共享库存 / 共享流体仓）。
     *
     * 这三个都是 `NotifiableRecipeHandlerTrait`，所以照 GTM 的做法订阅它们的
     * 内容变化（多方块据此重新判定配方），绑定/解绑时换目标指针即可，表中对象不重建。
     */
    private class ProxyItemHandler(machine: MetaMachine) : NotifiableRecipeHandlerTrait<Ingredient>(machine) {

        private var proxy: IRecipeHandlerTrait<Ingredient>? = null
        private var proxySub: ISubscription? = null

        fun setProxy(newProxy: IRecipeHandlerTrait<Ingredient>?) {
            val oldSub = proxySub
            if (oldSub != null) {
                oldSub.unsubscribe()
                proxySub = null
            }
            proxy = newProxy
            if (newProxy != null) {
                proxySub = newProxy.addChangedListener(Runnable { notifyListeners() })
            }
        }

        override fun handleRecipeInner(
            io: IO,
            recipe: GTRecipe,
            left: List<Ingredient>,
            simulate: Boolean,
        ): List<Ingredient>? {
            val current = proxy ?: return left
            return current.handleRecipeInner(io, recipe, left, simulate)
        }

        override fun getSize(): Int {
            return proxy?.size ?: 0
        }

        override fun getContents(): List<Any> {
            val current = proxy ?: return Collections.emptyList()
            return current.contents
        }

        override fun getTotalContentAmount(): Double {
            return proxy?.totalContentAmount ?: 0.0
        }

        override fun getCapability(): RecipeCapability<Ingredient> {
            return ItemRecipeCapability.CAP
        }

        override fun getHandlerIO(): IO {
            return IO.IN
        }

        override fun getPriority(): Int {
            return proxy?.priority ?: LOW
        }

        override fun isDistinct(): Boolean {
            return true
        }
    }

    /** [ProxyItemHandler] 的流体版。 */
    private class ProxyFluidHandler(machine: MetaMachine) : NotifiableRecipeHandlerTrait<FluidIngredient>(machine) {

        private var proxy: IRecipeHandlerTrait<FluidIngredient>? = null
        private var proxySub: ISubscription? = null

        fun setProxy(newProxy: IRecipeHandlerTrait<FluidIngredient>?) {
            val oldSub = proxySub
            if (oldSub != null) {
                oldSub.unsubscribe()
                proxySub = null
            }
            proxy = newProxy
            if (newProxy != null) {
                proxySub = newProxy.addChangedListener(Runnable { notifyListeners() })
            }
        }

        override fun handleRecipeInner(
            io: IO,
            recipe: GTRecipe,
            left: List<FluidIngredient>,
            simulate: Boolean,
        ): List<FluidIngredient>? {
            val current = proxy ?: return left
            return current.handleRecipeInner(io, recipe, left, simulate)
        }

        override fun getSize(): Int {
            return proxy?.size ?: 0
        }

        override fun getContents(): List<Any> {
            val current = proxy ?: return Collections.emptyList()
            return current.contents
        }

        override fun getTotalContentAmount(): Double {
            return proxy?.totalContentAmount ?: 0.0
        }

        override fun getCapability(): RecipeCapability<FluidIngredient> {
            return FluidRecipeCapability.CAP
        }

        override fun getHandlerIO(): IO {
            return IO.IN
        }

        override fun getPriority(): Int {
            return proxy?.priority ?: LOW
        }

        override fun isDistinct(): Boolean {
            return true
        }
    }

    /**
     * 转发到宿主总成**某个样板槽**（[InternalSlot]）。
     *
     * 行为对齐 GTM 的 `InternalSlotRecipeHandler.SlotItemRecipeHandler`：
     * 优先级 `IFilteredHandler.HIGH + 槽号 + 1`、只吃 `IO.IN`、空槽直接放过、
     * `getSize()` 恒为 81（GTM 那个常量原样对齐，它在"能看到的条目数"语义上用）。
     */
    private class SlotItemHandler(machine: MetaMachine, index: Int) : NotifiableRecipeHandlerTrait<Ingredient>(machine) {

        private val priority: Int = HIGH + index + 1

        private var slot: InternalSlot? = null

        fun setSlot(newSlot: InternalSlot?) {
            slot = newSlot
        }

        override fun handleRecipeInner(
            io: IO,
            recipe: GTRecipe,
            left: List<Ingredient>,
            simulate: Boolean,
        ): List<Ingredient>? {
            val current = slot
            if (io != IO.IN || current == null || current.isItemEmpty) return left
            return current.handleItemInternal(left, simulate)
        }

        override fun getSize(): Int {
            return if (slot == null) 0 else REPORTED_SIZE
        }

        override fun getContents(): List<Any> {
            val current = slot ?: return Collections.emptyList()
            return ArrayList(current.getItems())
        }

        override fun getTotalContentAmount(): Double {
            val current = slot ?: return 0.0
            return current.getItems().stream().mapToLong { stack -> stack.count.toLong() }.sum().toDouble()
        }

        override fun getCapability(): RecipeCapability<Ingredient> {
            return ItemRecipeCapability.CAP
        }

        override fun getHandlerIO(): IO {
            return IO.IN
        }

        override fun getPriority(): Int {
            return if (slot == null) LOW else priority
        }

        override fun isDistinct(): Boolean {
            return true
        }

        private companion object {

            /** GTM `SlotItemRecipeHandler#size` 的取值，原样对齐。 */
            private const val REPORTED_SIZE = 81
        }
    }

    /** [SlotItemHandler] 的流体版。 */
    private class SlotFluidHandler(machine: MetaMachine, index: Int) :
        NotifiableRecipeHandlerTrait<FluidIngredient>(machine) {

        private val priority: Int = HIGH + index + 1

        private var slot: InternalSlot? = null

        fun setSlot(newSlot: InternalSlot?) {
            slot = newSlot
        }

        override fun handleRecipeInner(
            io: IO,
            recipe: GTRecipe,
            left: List<FluidIngredient>,
            simulate: Boolean,
        ): List<FluidIngredient>? {
            val current = slot
            if (io != IO.IN || current == null || current.isFluidEmpty) return left
            return current.handleFluidInternal(left, simulate)
        }

        override fun getSize(): Int {
            return if (slot == null) 0 else REPORTED_SIZE
        }

        override fun getContents(): List<Any> {
            val current = slot ?: return Collections.emptyList()
            return ArrayList(current.getFluids())
        }

        override fun getTotalContentAmount(): Double {
            val current = slot ?: return 0.0
            return current.getFluids().stream().mapToLong { stack -> stack.amount.toLong() }.sum().toDouble()
        }

        override fun getCapability(): RecipeCapability<FluidIngredient> {
            return FluidRecipeCapability.CAP
        }

        override fun getHandlerIO(): IO {
            return IO.IN
        }

        override fun getPriority(): Int {
            return if (slot == null) LOW else priority
        }

        override fun isDistinct(): Boolean {
            return true
        }

        private companion object {

            private const val REPORTED_SIZE = 81
        }
    }
}