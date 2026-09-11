package rain.gtetcore.gtet.common.machine.multiblock

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import rain.gtetcore.gtet.common.machine.IThreadedRecipeMachine
import rain.gtetcore.gtet.common.machine.ThreadedRecipeLogic

/**
 * GTET 的**第一台多方块机器** —— 「多方块测试机」。
 *
 * 除了继承 GTM 现成的 [WorkableElectricMultiblockMachine]（EU 输入 + 部件能力聚合 + 超频 + Fancy UI），
 * 本类只做一件事：**把配方逻辑换成多线程内核**，也就是实现 [IThreadedRecipeMachine]
 * 并重写 [createRecipeLogic] 返回 [ThreadedRecipeLogic]。
 * 它存在的意义是当 GTET「线程仓 + 多线程配方逻辑」的**试验台**：
 * 结构简单（3×3×3）、只吃一类配方（[com.gregtechceu.gtceu.common.data.GTRecipeTypes.MACERATOR_RECIPES]，
 * 研磨配方是全 GTCEu 最多的一类，最容易验证「不同配方各自跑」）。
 *
 * ## 接上内核之后的行为
 * - 装线程仓 → 最多同时跑 N 种**不同**配方（N = 线程仓的 `threadCount`，UV=4 起、MAX=256），
 *   没装仓时退化成原版「一台机器一条配方」（`threadCount` 默认 1）；
 * - 每条线程各扣各的料、各自计时、各自出料，并**各自吃一遍并行仓的并行倍率**
 *   （见 [ThreadedRecipeLogic] 的「每线程并行怎么套」一节）。
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
 * ## ⚠️ 并行契约（与 [ThreadedRecipeLogic] 的类 KDoc 对应）
 * 接入线程内核后，本机的 `recipeModifier` **不得**再包含并行修改器（否则并行会被套两遍）——
 * 并行已改由 [ThreadedRecipeLogic] 逐线程按并行仓的 `getCurrentParallel()` 施加。
 * 本机的配方修改器清单见 [rain.gtetcore.gtet.common.data.machine.ETTestMultiblocks.register]：
 * 只保留 `OC_NON_PERFECT_SUBTICK` 与 `BATCH_MODE`，`PARALLEL_HATCH` 已移除。
 *
 * ## 为什么没有重写 `getFieldHolder()`
 * 子类不加额外 `@Persisted` 字段时无需自己声明 `MANAGED_FIELD_HOLDER`，所以本类没有重写它。
 *
 * @author rain fox
 */
class TestMultiblockMachine(holder: IMachineBlockEntity) : WorkableElectricMultiblockMachine(holder),
    IThreadedRecipeMachine {

    /**
     * 换掉 GTM 默认的 `RecipeLogic`（单配方、单线程），改用多线程内核。
     *
     * 签名必须与 Java 侧的 `protected RecipeLogic createRecipeLogic(Object... args)` 对齐
     * （Kotlin 侧就是 `vararg args: Any?`）；[args] 本类用不到，透传语义由父类保留。
     */
    override fun createRecipeLogic(vararg args: Any?): RecipeLogic = ThreadedRecipeLogic(this)
}
