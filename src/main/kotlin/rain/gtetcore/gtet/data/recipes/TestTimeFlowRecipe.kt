package rain.gtetcore.gtet.data.recipes

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.GTValues.VA
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.data.tag.TagPrefix.dust
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.common.data.GTRecipeTypes.MACERATOR_RECIPES
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder
import net.minecraft.data.recipes.FinishedRecipe
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.capability.ETTimeFlowCapability
import rain.gtetcore.gtet.api.capability.ETTimeFlowStack
import java.util.function.Consumer

/**
 * ⚠️ **测试配方**，不是产线内容 —— 存在的唯一目的是证明「TF 能被配方真正扣掉」这条链是通的：
 *
 * ```
 * 能力注册（ETGTAddon#registerRecipeCapabilities）
 *   → 配方里写 tickInputs.time_flow（本文件）
 *      → 序列化（GTRecipeSerializer 的 codec，键就是 "tickInputs": { "time_flow": ... }）
 *         → 写盘（runData 的 reports/gtetcore/test_time_flow_recipe.json，见 GTETDataReport）
 * ```
 *
 * ## 它长什么样
 * 挂在**多方块测试机**（`ETTestMultiblocks` / `TestMultiblockMachine`，只吃研磨配方）能跑的
 * [MACERATOR_RECIPES] 上：`1 × 铁粉 → 1 × 铁粉`，外加 [TF_PER_TICK] TF/t 的 tick 输入。
 *
 * ⚠️ 物品是「一进一出、不增不减」的：这条配方要做的是**验证扣费**，不该顺手给研磨机加一种转换；
 * 同样的理由，输入的物品与数量都刻意选得不与 GTM 既有的研磨配方重合。
 *
 * ⚠️ 研磨机没有时序仓时这条配方**匹配不过**（TF 是 tick 输入，匹配阶段就会失败），
 * GTM 会继续试下一个候选配方，所以它不会把普通研磨机卡住。
 *
 * ## 为什么没有 `tickInput(...)` 这个现成方法
 * GTM 7.5.3 的 `GTRecipeBuilder` **没有** `tickInput(...)`（javap 实证：只有 `input` / `output` /
 * `chancedTickInputLogic`，以及一个公开的 `tickInput` 字段）。它的机制是「由 `perTick` 标志决定
 * `input(...)` 写进 `input` 还是 `tickInput`」，`EUt(...)` 就是这么干的。所以这里补一个同形的小扩展
 * [tickInput]，调用点读起来就是任务要的那一行。
 *
 * @author rain fox
 */
object TestTimeFlowRecipe {

    /** 配方 id（注册后是 `gtceu:macerator/test_time_flow_tick_input`）。 */
    const val ID: String = "test_time_flow_tick_input"

    /** 每 tick 消耗的 TF。 */
    const val TF_PER_TICK: Long = 16L

    /** 配方耗时（tick）。 */
    const val DURATION: Int = 200

    /** 整条配方一次的 TF 总消耗 = `TF_PER_TICK × DURATION` = 3200 TF（< 最低档 1h 仓的 3600 TF）。 */
    const val TOTAL_TF: Long = TF_PER_TICK * DURATION

    /**
     * 构造这条测试配方。
     *
     * 调用点有两处：① [init] 走运行时动态数据包（`IGTAddon#addRecipes`）；
     * ② `GTETDataReport` 在 `runData` 里把它序列化成 JSON。
     * 两处共用同一个 builder，保证「写盘的那份 JSON」就是「游戏里加载的那条配方」。
     */
    fun builder(): GTRecipeBuilder = MACERATOR_RECIPES.recipeBuilder(Gtetcore.id(ID))
        .inputItems(dust, GTMaterials.Iron, 1)
        .outputItems(dust, GTMaterials.Iron, 1)
        .duration(DURATION)
        .EUt(VA[GTValues.LV].toLong())                  // LV：测试机不挑电压，给个最低的
        .tickInput(ETTimeFlowCapability.CAP, ETTimeFlowStack(TF_PER_TICK))

    /** 注册进运行时动态数据包（由 [ALLRecipes.init] 调用）。 */
    fun init(provider: Consumer<FinishedRecipe>) {
        builder().save(provider)
    }

    /**
     * 把一个 TF 内容写进 **tick 输入**。
     *
     * 实现照抄 GTM 自己的 `EUt(long)`：临时把 `perTick` 置真，`input(...)` 就会写进 `tickInput` 表，
     * 写完立刻还原（`EUt` 里也是「存旧值 → 置真 → 写 → 还原」这套）。
     */
    private fun GTRecipeBuilder.tickInput(
        capability: RecipeCapability<ETTimeFlowStack>,
        stack: ETTimeFlowStack
    ): GTRecipeBuilder = apply {
        val previous = perTick
        perTick = true
        input(capability, stack)
        perTick = previous
    }
}
