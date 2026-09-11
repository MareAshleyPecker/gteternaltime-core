package rain.gtetcore.gtet.common.machine

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import rain.gtetcore.gtet.api.capability.IThreadHatch

/**
 * 「可跑多线程配方」多方块控制器的标记接口。
 *
 * 机器只要实现本接口（并且把 `createRecipeLogic` 重写成返回 [ThreadedRecipeLogic]），
 * 就获得「一台机器同时跑 N 种不同配方、每条线程各自计时、各自吃并行倍率」的行为：
 *
 * ```kotlin
 * class MyThreadedMachine(holder: IMachineBlockEntity, ...) :
 *     WorkableMultiblockMachine(holder), IThreadedRecipeMachine {
 *
 *     override fun createRecipeLogic(vararg args: Any?): RecipeLogic = ThreadedRecipeLogic(this)
 * }
 * ```
 *
 * 这是 GTCEu 提供的**正规扩展点**（`WorkableMultiblockMachine#createRecipeLogic(Object...)`
 * 本来就是 `protected` 可重写的工厂方法），所以本功能不需要任何 mixin。
 *
 * ## 思路来源
 * - 【借鉴形状】GTOCore（`D:\java\GTOCore`）`com.gtolib.api.machine.feature.multiblock.ICrossRecipeMachine` —— 借「一个标记接口把控制器标记成多线程机器、并从它身上取线程能力」的形状（`ICrossRecipeMachine#getThread()` / `createRecipeLogic(Object...)` 的签名形状一致）。⚠️ 它只是**形状**来源：该接口在 `libs/gtolib-1.0.jar` 里所有方法体都是 `native`（真正实现被抽到 `native0/native/` 那堆 `.bin` 的加密库里），一行实现都看不到。
 * - 【自研】`threadHatch` 的默认实现（扫 `getParts()`、装机多个时取线程数最大的那个）+ `threadCount` 退化策略（没装仓 → 1，即退化成原版单配方机器）—— 现扫而不是「结构成型时缓存唯一实例」：部件的装卸就发生在结构成型/失效那一刻，现扫永远是最新状态，也不会在拆结构时留下悬空引用；多个仓取最大而不是叠加，避免「装两个仓就能无限开线程」这种结构漏洞。
 *
 * @author rain fox
 */
interface IThreadedRecipeMachine : IMultiController {

    /**
     * 这台机器当前挂着的线程仓；没装（或结构没成型）时为 `null`。
     *
     * 默认实现是**现扫** `getParts()`：因为部件的装卸发生在结构成型/失效时，
     * 现扫永远拿得到最新状态，也不会像缓存字段那样在拆结构时留下悬空引用。
     * 一台机器装多个线程仓时取 [IThreadHatch.threadCount] 最大的那个（不叠加）。
     */
    val threadHatch: IThreadHatch?
        get() {
            var best: IThreadHatch? = null
            for (part in parts) {
                // 部件机器本身通常就实现了 IThreadHatch；`self()` 那一路是防御性写法
                // （万一以后有部件用包装对象实现 IMultiPart）。
                val hatch = (part as? IThreadHatch) ?: (part.self() as? IThreadHatch) ?: continue
                if (best == null || hatch.threadCount > best.threadCount) best = hatch
            }
            return best
        }

    /**
     * 当前生效的线程数上限（= 线程仓的 [IThreadHatch.threadCount]）。
     *
     * 没装线程仓时返回 1：整套线程逻辑照常跑，但只有一条线程可用，
     * 行为就退化回 GTCEu 原版「一台机器一条配方」。
     */
    val threadCount: Int get() = threadHatch?.threadCount ?: 1
}
