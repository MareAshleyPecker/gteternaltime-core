package rain.gtetcore.gtet.common.data.machine.multiblock

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.api.capability.IThreadedRecipeMachine
import rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeLogic
import rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeStatus

/**
 * GTET 的**第一台多方块机器** —— 「多方块测试机」。
 *
 * 除了继承 GTM 现成的 [com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine]（EU 输入 + 部件能力聚合 + 超频 + Fancy UI），
 * 本类只做一件事：**把配方逻辑换成多线程内核**，也就是实现 [rain.gtetcore.gtet.api.capability.IThreadedRecipeMachine]
 * 并重写 [createRecipeLogic] 返回 [rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeLogic]。
 * 它存在的意义是当 GTET「线程仓 + 多线程配方逻辑」的**试验台**：
 * 结构简单（3×3×3）、只吃一类配方（[com.gregtechceu.gtceu.common.data.GTRecipeTypes.MACERATOR_RECIPES]，
 * 研磨配方是全 GTCEu 最多的一类，最容易验证「不同配方各自跑」）。
 *
 * ## 接上内核之后的行为
 * - 装线程仓 → 最多同时跑 N 条线程（N = 线程仓的 `threadCount`，ZPM=4 起、MAX=512）；⚠️ 是线程条数，
 *   同一种配方可以占多条（空闲线程会被发给已经在跑的同一种配方），所以线程条数 ≥ 配方种数；
 *   没装仓时退化成原版「一台机器一条配方」（`threadCount` 默认 1）；
 * - 每条线程各扣各的料、各自计时、各自出料，并**各自吃一遍并行仓的并行倍率**
 *   （见 [rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeLogic] 的「每线程并行怎么套」一节）。
 * - **线程状态可见**：机器面板里（[addDisplayText] 追加的行）与 Jade 提示里
 *   （`rain.gtetcore.gtet.integration.jade.provider.ThreadedRecipeLogicProvider`）都会报
 *   「线程 256（在用 k）」+ 整机同时处理次数 + 逐**配方组**一行（进度 + 该组所有线程加起来的产出物×数量，
 *   合并规则与行数上限见 [rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeStatus]）—— 这是「线程真的开了」在游戏里唯一能直接看到的地方
 *   （线程表本身不同步到客户端）。
 *
 * ## 为什么重写 `createRecipeLogic` 就够了（不需要 mixin）
 * [com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine] 的构造函数里有一句
 * `this.recipeLogic = createRecipeLogic(args);`，而它的定义是：
 *
 * ```java
 * protected RecipeLogic createRecipeLogic(Object... args) {
 *     return new RecipeLogic(this);
 * }
 * ```
 *
 * 也就是说**每一台多方块都已经预留了一个「换配方逻辑」的虚方法**，子类只要 override 它、
 * 返回自己的 [com.gregtechceu.gtceu.api.machine.trait.RecipeLogic] 子类即可。
 * 注意构造顺序：父类构造函数里就会调到这里，所以 [createRecipeLogic] 里**不能**读本类尚未初始化的字段
 * （本类没有自己的构造参数与字段，因此安全）。
 *
 * ## ⚠️ 并行契约（与 [rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeLogic] 的类 KDoc 对应）
 * 接入线程内核后，本机的 `recipeModifier` **不得**再包含并行修改器（否则并行会被套两遍）——
 * 并行已改由 [rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeLogic] 逐线程按并行仓的 `getCurrentParallel()` 施加。
 * 本机的配方修改器清单见 [ETTestMultiblocks.register]：
 * 只保留 `OC_NON_PERFECT_SUBTICK` 与 `BATCH_MODE`，`PARALLEL_HATCH` 已移除。
 *
 * ## 为什么没有重写 `getFieldHolder()`
 * 子类不加额外 `@Persisted` 字段时无需自己声明 `MANAGED_FIELD_HOLDER`，所以本类没有重写它。
 *
 * @author rain fox
 */
class TestMultiblockMachine(holder: IMachineBlockEntity) :
    WorkableElectricMultiblockMachine(holder),
    IThreadedRecipeMachine
{

    /**
     * 换掉 GTM 默认的 `RecipeLogic`（单配方、单线程），改用多线程内核。
     *
     * 签名必须与 Java 侧的 `protected RecipeLogic createRecipeLogic(Object... args)` 对齐
     * （Kotlin 侧就是 `vararg args: Any?`）；[args] 本类用不到，透传语义由父类保留。
     */
    override fun createRecipeLogic(vararg args: Any?): RecipeLogic = ThreadedRecipeLogic(this)

    /**
     * 往机器面板里追加「线程状态」。
     *
     * ## 为什么重写这个方法是「免费」的
     * `addDisplayText` 是 GTM 现成的扩展点（[com.gregtechceu.gtceu.api.machine.feature.multiblock.IDisplayUIMachine]），
     * `WorkableElectricMultiblockMachine` 自己那份实现负责「能量 / 并行 / 批处理 / 进度」那一套；
     * 本类只负责在它后面**追加**几行线程信息，其余一行都不动。
     *
     * ## 数据从哪来（为什么不需要把线程表同步到客户端）
     * 这段文本由 `ComponentPanelWidget` 在**服务端**求值（它的 `textSupplier` 只在
     * `detectAndSendChanges` 里跑），组件再同步给客户端渲染 —— 所以这里读到的是服务端那份真的线程表。
     * 客户端侧的 `textSupplier` 被 GTM 设成 `null`（`createUIWidget` 里的
     * `this.getLevel().isClientSide ? null : this::addDisplayText`），不会走到这里；
     * 万一别处复用 `IDisplayUIMachine#createUI` 在客户端调到，
     * [rain.gtetcore.gtet.common.machine.multiblock.thread.ThreadedRecipeStatus.appendDisplayLines] 里那道「客户端直接收手」的闸会挡掉误报。
     */
    override fun addDisplayText(textList: MutableList<Component>) {
        super.addDisplayText(textList)
        val logic = recipeLogic as? ThreadedRecipeLogic ?: return
        ThreadedRecipeStatus.appendDisplayLines(textList, logic)
    }
}