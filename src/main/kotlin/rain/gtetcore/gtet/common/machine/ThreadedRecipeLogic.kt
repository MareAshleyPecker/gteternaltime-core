package rain.gtetcore.gtet.common.machine

import com.gregtechceu.gtceu.api.capability.IParallelHatch
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic.Status
import com.gregtechceu.gtceu.api.recipe.ActionResult
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier
import com.gregtechceu.gtceu.api.recipe.modifier.ModifierFunction
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic
import it.unimi.dsi.fastutil.objects.Object2IntMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.Gtetcore

/**
 * 「多线程配方逻辑内核」。
 *
 * 把 N 台同型机器**融合成一台**：一张线程表，一个线程槽跑一种配方，
 * 每种配方**自己计时**、到点**自己结算**、各自吃一遍**并行仓的并行倍率**。
 *
 * ```
 * 线程槽 0：[ 配方 A  progress=137/200 ]  ← 自己扣料、自己出料
 * 线程槽 1：[ 配方 B  progress=18/20   ]
 * 线程槽 2：[ 空闲                      ]  ← 每 tick 试着找一条「还没别的线程在跑」的配方
 * ```
 *
 * ## 调度策略（怎么决定开新线程）
 * 每个 `serverTick`：
 * 1. **推进**：遍历所有**已在跑**的线程槽，各自检查条件 → 扣每 tick 的输入（EUt 等）→ `progress++`，
 *    到点就走 [finishThread] 结算输出。线程之间完全独立，一个线程在等待/空闲不影响别的线程。
 * 2. **开新线程**：只有当存在**空闲槽位**时才去找配方（每 [SEARCH_INTERVAL] tick 搜一次，
 *    避免每条 tick 都去翻配方库）。找到候选后：
 *    - **重复检查**（GTO 的 `duplicateCheck` 语义）：`id` 与「已有线程正在跑的配方」相同的候选**直接跳过**，
 *      所以同一种配方永远只占 1 条线程，N 条线程 = N 种**不同**配方；
 *    - 套机器自己的配方修改器（超频等）→ 套本线程的并行 → 条件检查 → 模拟匹配
 *      （`RecipeHelper.matchContents`）→ `machine.beforeWorking` → **真正扣料** → 占住槽位。
 * 3. **上限**：能开几条线程由 [threadLimit] 决定（= 线程仓的 `threadCount`，没装仓就是 1）。
 *
 * ## 每线程并行怎么套（④）
 * 每条线程**自己**调用 [applyThreadParallel]：读 `IMultiController#getParallelHatch()` 的
 * `getCurrentParallel()` 当上限 M，再用 `ParallelLogic#getParallelAmount(...)`
 * 把 M 收缩到「这台机器当下真的喂得起的倍数」（输入不够按输入算、输出塞不下按输出算，
 * 这段判定不自己写），然后套
 * `ModifierFunction.builder().modifyAllContents(×p).eutMultiplier(×p).parallels(p)`。
 *
 * 之所以走 `ModifierFunction` 链路而不是自己改内容表：概率逻辑、tick 内容、
 * `parallels` 与 `getTotalRuns()`（概率掷点要按总运行次数算）全都挂在 GTRecipe 的既有语义上，
 * 自己手改内容会把这些一起改坏。
 * 于是「总处理次数上限 ≈ 线程数 × M」，每种配方各自吃满自己的那份并行。
 *
 * ## 每线程记账（⑤ + 独立扣料/出料）
 * 每条线程**独立**走一遍 `RecipeHelper#checkConditions` / `matchTickRecipe` / `handleRecipeIO(IN/OUT)` /
 * `handleTickRecipeIO(IN/OUT)`（全是 GTM 现成 API，没有自己重写配方匹配）：
 * - 开线程时扣一次**非 tick 输入**（`handleRecipeIO(IN)`），tick 输入每个 tick 扣（`handleTickRecipeIO(IN)`，
 *   缺 EUt 就整条线程等待 + 按 `regressWhenWaiting()` 回退进度，与基类一致）；
 * - 到点出料（`handleRecipeIO(OUT)`）；
 * - 每条线程有**自己的** `chanceCaches`（基类的 `chanceCaches` 是「一条配方一份」的，
 *   多线程共用会串概率：A 配方掷出的概率会被 B 配方同内容复用）；
 * - 服务器主线程上按槽位顺序串行处理，不存在两个线程同时改同一批库存的交错。
 *
 * ## v1 已知简化（**诚实标注**）
 * 1. **客户端只有一个进度条**：线程表**没有**同步到客户端（不是 `@DescSynced`）。为了让
 *    Jade / TOP / 机器 UI 不至于显示空白，本类把「下标最小的那条在跑线程」镜像进基类的
 *    `lastRecipe` / `progress` / `duration` / `isActive` 四个**已同步**字段，
 *    也就是「基类那套单进度」照旧能用，但只反映**一条**线程。
 *    多线程进度条 UI（把整张线程表 DescSync 出去）留到下一步。
 * 2. **没有「缺电 5 次自动 SUSPEND」**：基类缺 EUt 时会累加 `runAttempt`、`runDelay = runAttempt * 60`
 *    并在第 5 次 SUSPEND 整台机器；本内核 v1 统一处理成「该线程等待 + 回退进度」，
 *    不自动挂起、也不加 `runDelay` 重试间隔。
 * 3. **`recipeDirty` 只对新线程生效**：并行仓/线程仓改数值时 GTM 会 `markLastRecipeDirty()`；
 *    本内核不打断已在跑的线程（它们的并行倍率是开线程那一刻的快照），只影响之后新开的线程。
 * 4. **存档只存「配方 id + 进度」**：区块卸载/存档重载时不会吞掉材料，但恢复是**尽力而为**——
 *    配方被数据包改掉、部件被拆、结构没成型，都会让那条线程直接消失（已经扣掉的料不退回，
 *    与基类 `resetRecipeLogic()` 的行为一致）。
 * 5. **出料口满了会丢产物**：`handleRecipeIO(OUT)` 的返回值被忽略 —— 这一点与基类
 *    `RecipeLogic#onRecipeFinish` 的行为一致（它同样不看返回值），不是本内核新引入的行为；
 *    要改成「等出料口腾地方再出」属于另一个特性，本版不做。
 * 6. **机器级 `onWorking()` 返回 false 时只「等」，不「打断」**：维护仓坏掉这类情况下，
 *    基类会 `interruptRecipe()`（进度清零、**料不退**）；本内核把**所有**线程置为等待并保留进度，
 *    等机器恢复后继续跑。理由是「多线程下打断一条线程的代价是吞掉一份材料，而玩家看不出来是哪条」。
 * 7. **`machine.onWorking()` 每 tick 只调一次**（不是每条线程一次）：部件回调不会被线程数放大，
 *    代价是「某条线程的独立失败」无法通过这个钩子表达 —— 属于接线时的取舍。
 *
 * ## 与「并行仓不能重复套」的契约（重要）
 * 接入本逻辑的多方块，其 `recipeModifier` **不能**再包含并行修改器
 * （例如 `GTRecipeModifiers.PARALLEL_HATCH`），否则就会「并行套两遍」。
 * 本内核有一道防御：如果传进来的配方 `parallels > 1`（说明上游修改器已经并行过了），
 * 就不再叠加，并且只警告一次。
 * GTET 目前的接线是 `ETTestMultiblocks` 里的
 * `.recipeModifiers(OC_NON_PERFECT_SUBTICK, BATCH_MODE)` —— **只做超频与批处理、不做并行**，
 * 并行全部交给本内核按线程施加（并行仓本身照旧要装，本内核就是从它身上读 `getCurrentParallel()`）。
 *
 * ## 思路来源
 * - 【借鉴形状】GTOCore（`D:\java\GTOCore`）的线程记录结构 `ICrossRecipeMachine$Thread { progress, recipe, duration, use }` 与 `ICrossRecipeMachine$Logic`（`updateTickSubscription` / `findAndHandleRecipe` / `serverTick` / `onRecipeFinish` / `saveCustomPersistedData` / `loadCustomPersistedData` 一整套 `native` 覆盖）—— 借的只是「每条线程一条记录（配方 + 进度 + 时长）」和「线程逻辑要自己接管 tick / 存档」这两个形状。
 *   ⚠️ **必须注明**：GTO 的**调度与记账算法一行都拿不到** —— `libs/gtolib-1.0.jar` 里 `ICrossRecipeMachine`、`ICrossRecipeMachine$Thread`、`ICrossRecipeMachine$Logic`、`ThreadPartMachine` 的方法体全是 `native`（`javap` 只能看到签名，实现被抽到 `native0/native/` 那堆 `.bin` 的加密库里）。所以**线程表调度、动态开线程策略、每线程独立 IO 记账、线程数上限来源，全部是 GTET 自研**（见下面的【自研】条）。
 * - 【自研】线程表（`data class ThreadRec` 槽位数组）+ `duplicateCheck` 去重（按 `GTRecipe#id`，`id == null` 时退化成引用相等 —— 现成的 `GTRecipe#equals` 只比 id 且对 null id 会 NPE）+ 「线程数上限由线程仓 tier 决定」+ 「玩家下调线程数不砍已开线程」+ 「每线程独立 `chanceCaches`」+ 「基类单进度镜像」+ 存档恢复策略：这些在 GTOCore 里都没有可抄的实现（GTO 那半边在 native 里，且是 `ICrossRecipeMachine` 专属的私有调度）。
 *
 * @param machine 持有本逻辑的机器（应当实现 [IThreadedRecipeMachine]；否则线程数上限退化为 1）
 *
 * @author rain fox
 */
class ThreadedRecipeLogic(machine: IRecipeLogicMachine) : RecipeLogic(machine) {

    /**
     * 一条线程 = 一个正在跑的配方。
     *
     * @param recipe   这条线程**自己那份**配方（已经套过机器修改器与并行，各线程互不共享对象）
     * @param progress 这条线程自己的进度（tick）
     * @param duration 开线程时钉下来的耗时（tick）—— 钉死而不是每 tick 读 `recipe.duration`，
     *                 免得中途有东西改了配方对象导致进度条乱跳
     */
    data class ThreadRec(val recipe: GTRecipe, var progress: Int, val duration: Int)

    /** 提供线程仓的机器；拿不到就退化成单线程。 */
    private val threadedMachine: IThreadedRecipeMachine? = machine as? IThreadedRecipeMachine

    /**
     * 线程槽表：下标 = 槽位，`null` = 空闲。
     *
     * 只增不减（见 [ensureSlots]）：玩家下调线程数时超出的槽位保留到跑完为止。
     */
    private val threads: MutableList<ThreadRec?> = ArrayList()

    /**
     * 每线程**独立**的概率缓存。
     *
     * 基类的 `chanceCaches` 是「一条配方一份」的语义（换配方时整表清空），
     * 多线程共用一个表会互相串概率，所以每条线程开线程时各拿一份新的。
     */
    private val threadChanceCaches: MutableList<MutableMap<RecipeCapability<*>, Object2IntMap<*>>?> = ArrayList()

    /** 存档里读出来、还没恢复的线程（配方 id → 进度）；第一次能跑 `serverTick` 时恢复。 */
    private var pendingRestore: MutableList<Pair<ResourceLocation, Int>>? = null

    /** 找配方的节流计数：每 [SEARCH_INTERVAL] tick 搜一次。 */
    private var searchCooldown: Int = 0

    /** 「上游修改器已经套过并行」这条警告只打一次，免得刷屏。 */
    private var warnedAlreadyParallel: Boolean = false

    // ────────────────────────────────────────────────
    //  对外只读状态
    // ────────────────────────────────────────────────

    /** 当前生效的线程数上限。 */
    val threadLimit: Int get() = threadedMachine?.threadCount ?: 1

    /** 正在跑的线程数。 */
    val runningThreadCount: Int get() = threads.count { it != null }

    /** 正在跑的线程们（只读快照，给 UI / 调试用）。 */
    val runningThreads: List<ThreadRec> get() = threads.filterNotNull()

    // ────────────────────────────────────────────────
    //  主循环
    // ────────────────────────────────────────────────

    override fun serverTick() {
        // 与基类一致：SUSPEND（玩家关掉了机器）时一个线程都不推进。
        if (isSuspend) return

        restoreThreadsIfPossible()
        ensureSlots()

        val limit = threadLimit
        val anyRunning = threads.any { it != null }

        var progressed = false
        var waitingNow = false
        var waitReason: Component? = null

        if (anyRunning) {
            // 基类每 tick 调一次 machine.onWorking()；这里整台机器**只调一次**（不是每个线程一次，
            // 否则部件回调会被放大 N 倍）。返回 false = 这一 tick 谁都不许推进。
            if (machine.onWorking()) {
                for (slot in threads.indices) {
                    val rec = threads[slot] ?: continue
                    val reason = advanceThread(slot, rec)
                    if (reason == null) {
                        progressed = true
                    } else {
                        waitingNow = true
                        waitReason = reason
                    }
                }
            } else {
                waitingNow = true
            }
        }

        if (progressed) totalContinuousRunningTime++

        // 空闲槽位找新配方（节流：每 SEARCH_INTERVAL tick 一次）
        var searched = false
        if (searchCooldown > 0) searchCooldown--
        if (searchCooldown <= 0) {
            searchCooldown = SEARCH_INTERVAL
            searched = true
            if (freeThreadSlot(limit) != null) tryStartThreads(limit)
        }

        mirrorToBaseFields()
        syncStatus(progressed, waitingNow, waitReason)

        // 一条线程都没在跑、这一轮也搜不到活干 → 退订，等部件内容变化时被 updateTickSubscription() 唤醒。
        // 这与基类在多方块上（keepSubscribing() == false）的退订行为一致。
        if (searched && runningThreadCount == 0) {
            subscription?.unsubscribe()
            subscription = null
        }
    }

    /**
     * 推进一条线程。
     *
     * @return `null` = 这一 tick 正常推进了；非 null = 「没推进 + 这个原因（可能为 null）」
     */
    private fun advanceThread(slot: Int, rec: ThreadRec): Component? {
        // 1) 配方条件（清洁室、维度、研究等）—— 与基类 handleRecipeWorking 的第一步一致
        val conditions = RecipeHelper.checkConditions(rec.recipe, this)
        if (!conditions.isSuccess) {
            regress(rec)
            return conditions.reason()
        }

        // 2) 每 tick 的输入/输出（EUt 等）—— 每条线程各扣各的
        val tick = handleThreadTickRecipe(slot, rec.recipe)
        if (!tick.isSuccess) {
            regress(rec)
            return tick.reason()
        }

        // 3) 推进这条线程；到点就结算
        rec.progress++
        if (rec.progress >= rec.duration) finishThread(slot, rec)
        return null
    }

    /**
     * 一条线程的 tick IO，等价于基类的 `handleTickRecipe(GTRecipe)`，只是换成**它自己的**概率缓存。
     *
     * 顺序与基类的 tick IO 一致：`matchTickRecipe`（模拟）→ `handleTickRecipeIO(IN)` → `handleTickRecipeIO(OUT)`。
     */
    private fun handleThreadTickRecipe(slot: Int, recipe: GTRecipe): ActionResult {
        if (!recipe.hasTick()) return ActionResult.SUCCESS

        val caches = threadChanceCaches[slot] ?: makeChanceCaches().also { threadChanceCaches[slot] = it }

        val match = RecipeHelper.matchTickRecipe(machine, recipe)
        if (!match.isSuccess) return match

        val input = RecipeHelper.handleTickRecipeIO(machine, recipe, IO.IN, caches)
        if (!input.isSuccess) return input

        return RecipeHelper.handleTickRecipeIO(machine, recipe, IO.OUT, caches)
    }

    /** 与基类 `regressRecipe()` 一致：等待时把进度退回 1（不整个重来），条件取 `regressWhenWaiting()`。 */
    private fun regress(rec: ThreadRec) {
        if (rec.progress > 0 && machine.regressWhenWaiting()) rec.progress = 1
    }

    /**
     * 线程到点：出料 + 释放槽位。
     *
     * 顺序与基类 `onRecipeFinish()` 一致：先 `machine.afterWorking()`，再 `handleRecipeIO(OUT)`。
     * 出料失败（出料口满）的处理也与基类一致：忽略返回值 —— 溢出的产物直接丢弃。
     */
    private fun finishThread(slot: Int, rec: ThreadRec) {
        machine.afterWorking()
        val caches = threadChanceCaches[slot] ?: makeChanceCaches()
        RecipeHelper.handleRecipeIO(machine, rec.recipe, IO.OUT, caches)
        threads[slot] = null
        threadChanceCaches[slot] = null
    }

    // ────────────────────────────────────────────────
    //  开新线程
    // ────────────────────────────────────────────────

    /**
     * 给空闲槽位找配方。用的是 GTM 现成的 `GTRecipeType#searchRecipe`（配方库自带的内容过滤），
     * 没有自己重写配方匹配。
     *
     * 一轮搜索可以填满多个空闲槽：每命中一条就占一个槽位继续往下找，
     * 所以 `[A, B, C] → 三条线程` 只需要一轮。
     */
    private fun tryStartThreads(limit: Int) {
        val iterator = machine.recipeType.searchRecipe(machine) { true }
        while (iterator.hasNext()) {
            val slot = freeThreadSlot(limit) ?: return
            val candidate = iterator.next()
            // ② duplicateCheck：这条配方已经有别的线程在跑 → 跳过，不给它开第二条线程
            if (isRunningElsewhere(candidate)) continue
            startThread(slot, candidate)
        }
    }

    /**
     * 开一条线程：
     * 机器配方修改器（超频等）→ 本线程并行 → 条件 → 模拟匹配 → `beforeWorking` → **真正扣料** → 占槽。
     *
     * 中间任何一步失败都只是「这条候选不能用」，不影响别的线程。
     */
    private fun startThread(slot: Int, origin: GTRecipe): Boolean {
        // ① 机器自己的配方修改器（超频等）。
        //    注意契约：接入本逻辑的多方块，其 recipeModifier 不应包含并行修改器（见类 KDoc）。
        val modified = machine.fullModifyRecipe(origin) ?: return false

        // ④ 这条线程**自己**那份并行
        val threaded = applyThreadParallel(modified) ?: return false

        // ③ 条件 + 材料（模拟匹配，复用 GTM 现成 API）
        val conditions = RecipeHelper.checkConditions(threaded, this)
        if (!conditions.isSuccess) {
            RecipeLogic.putFailureReason(this, origin, conditions.reason())
            return false
        }
        if (!RecipeHelper.matchContents(machine, threaded).isSuccess) return false

        if (!machine.beforeWorking(threaded)) return false

        val caches = makeChanceCaches()
        threads[slot] = ThreadRec(threaded, 0, threaded.duration)
        threadChanceCaches[slot] = caches

        // 真正扣料（每个线程各扣各的；前面已经模拟匹配过，这里失败属于极端情况）
        val consumed = RecipeHelper.handleRecipeIO(machine, threaded, IO.IN, caches)
        if (!consumed.isSuccess) {
            threads[slot] = null
            threadChanceCaches[slot] = null
            return false
        }
        return true
    }

    /**
     * 给一条线程套上「并行仓的并行倍率」（④）。
     *
     * - 上限取 `IMultiController#getParallelHatch()` 的 `getCurrentParallel()`；
     * - 用 `ParallelLogic#getParallelAmount(...)` 把上限收缩到「当下喂得起」的倍数
     *   （输入不够就按输入算、输出塞不下就按输出算 —— 这段判定不自己写）；
     * - 套法：内容 ×p、EUt ×p、`recipe.parallels = p`（三样缺一不可：只乘内容不设 `parallels`
     *   会让概率掷点按 1 次算，只设 `parallels` 不乘内容则等于白开线程）。
     *
     * @return 套好并行的配方；`null` = 这条配方当下连 1 份都跑不了
     */
    private fun applyThreadParallel(recipe: GTRecipe): GTRecipe? {
        // 防御：上游修改器已经并行过了就别再叠一遍（会变成 并行²）
        if (recipe.parallels > 1) {
            if (!warnedAlreadyParallel) {
                warnedAlreadyParallel = true
                Gtetcore.LOGGER.warn(
                    "[GTET] 线程仓：配方 {} 在机器配方修改器里已经吃过并行（parallels={}），" +
                        "线程逻辑不再叠加。请把该多方块的 recipeModifier 换成不含并行的版本。",
                    recipe.id,
                    recipe.parallels
                )
            }
            return recipe
        }

        val hatch = parallelHatch() ?: return recipe
        val limit = hatch.currentParallel
        if (limit <= 1) return recipe

        val achievable = ParallelLogic.getParallelAmount(getMachine(), recipe, limit)
        if (achievable <= 0) return null
        if (achievable == 1) return recipe

        return ModifierFunction.builder()
            .modifyAllContents(ContentModifier.multiplier(achievable.toDouble()))
            .eutMultiplier(achievable.toDouble())
            .parallels(achievable)
            .build()
            .apply(recipe)
    }

    /** 控制器上挂着的并行仓（`IMultiController#getParallelHatch()` 只缓存一个实例）。 */
    private fun parallelHatch(): IParallelHatch? =
        (getMachine() as? IMultiController)?.parallelHatch?.orElse(null)

    /** 这条配方是不是已经有（别的）线程在跑。 */
    private fun isRunningElsewhere(candidate: GTRecipe): Boolean {
        for (rec in threads) {
            if (rec == null) continue
            if (sameRecipe(rec.recipe, candidate)) return true
        }
        return false
    }

    /**
     * 两条配方算不算「同一种」。
     *
     * GTM 的 `GTRecipe#equals` 就是「比 `id`」，但它对 `id == null` 的配方会 NPE
     * （`return this.id.equals(recipe.id);`），所以这里自己判一次：
     * 两边都有 id 就比 id，否则退化成引用相等。
     */
    private fun sameRecipe(a: GTRecipe, b: GTRecipe): Boolean {
        val idA = a.id
        val idB = b.id
        return if (idA != null && idB != null) idA == idB else a === b
    }

    // ────────────────────────────────────────────────
    //  槽位 / 状态 / 客户端镜像
    // ────────────────────────────────────────────────

    /**
     * 按当前线程上限把槽表**加长**；**不缩短**。
     *
     * 玩家在 UI 里下调线程数时，已经在跑的线程不会被砍掉（砍掉等于把扣过的料凭空吞掉），
     * 它们跑完自然释放；只是 [freeThreadSlot] 不再给出超出的槽位，也就是「不再开新线程」。
     */
    private fun ensureSlots() {
        val limit = threadLimit.coerceAtLeast(1)
        while (threads.size < limit) {
            threads.add(null)
            threadChanceCaches.add(null)
        }
    }

    /** 第一个空闲槽位（只看上限以内的槽位）。 */
    private fun freeThreadSlot(limit: Int): Int? {
        for (slot in 0 until minOf(limit, threads.size)) {
            if (threads[slot] == null) return slot
        }
        return null
    }

    /**
     * 把「下标最小的那条在跑线程」镜像进基类的同步字段，让客户端那套单进度 UI 照旧能用。
     *
     * 这四个字段在基类里都是 `@Persisted @DescSynced`，写它们等于免费拿到
     * Jade / TOP / 机器 UI 的「当前配方 + 进度条」显示。多条线程同时跑时只反映其中一条
     * —— 这是 v1 有意的简化，见类 KDoc「v1 已知简化」第 1 条。
     */
    private fun mirrorToBaseFields() {
        val primary = threads.firstOrNull { it != null }
        if (primary != null) {
            lastRecipe = primary.recipe
            progress = primary.progress
            duration = primary.duration
            isActive = true
        } else {
            progress = 0
            duration = 0
            isActive = false
        }
    }

    /** 状态机：有线程在推进 = WORKING；有线程但在等 = WAITING；一条线程都没有 = IDLE。 */
    private fun syncStatus(progressed: Boolean, waitingNow: Boolean, reason: Component?) {
        if (runningThreadCount == 0) {
            if (getStatus() != Status.IDLE) setStatus(Status.IDLE)
            return
        }
        when {
            progressed -> setStatus(Status.WORKING)
            // setWaiting 每次都调 machine.onWaiting()，所以只在状态真的变了的时候调
            waitingNow -> if (getStatus() != Status.WAITING) setWaiting(reason)
            getStatus() != Status.WORKING && getStatus() != Status.WAITING -> setStatus(Status.WORKING)
        }
    }

    // ────────────────────────────────────────────────
    //  生命周期 / 存档
    // ────────────────────────────────────────────────

    /** 结构失效时基类会 `resetRecipeLogic()`；线程表也必须一起清掉（否则会留下跑不了的幽灵线程）。 */
    override fun resetRecipeLogic() {
        super.resetRecipeLogic()
        threads.clear()
        threadChanceCaches.clear()
        pendingRestore = null
    }

    /**
     * 存档：只存「配方 id + 进度」。
     *
     * 不存整条 `GTRecipe`：配方本体从 `RecipeManager` 能重新取到，存 id 就够，
     * 而且这样存档体积与配方改动都不会互相拖累。
     */
    override fun saveCustomPersistedData(tag: CompoundTag, forDrop: Boolean) {
        super.saveCustomPersistedData(tag, forDrop)
        val list = ListTag()
        for (rec in threads) {
            val running = rec ?: continue
            val id = running.recipe.id ?: continue
            val entry = CompoundTag()
            entry.putString(TAG_RECIPE, id.toString())
            entry.putInt(TAG_PROGRESS, running.progress)
            list.add(entry)
        }
        tag.put(TAG_THREADS, list)
    }

    override fun loadCustomPersistedData(tag: CompoundTag) {
        super.loadCustomPersistedData(tag)
        val list = tag.getList(TAG_THREADS, Tag.TAG_COMPOUND.toInt())
        if (list.isEmpty()) {
            pendingRestore = null
            return
        }
        val parsed = ArrayList<Pair<ResourceLocation, Int>>(list.size)
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            val id = ResourceLocation.tryParse(entry.getString(TAG_RECIPE)) ?: continue
            parsed += id to entry.getInt(TAG_PROGRESS)
        }
        pendingRestore = parsed
    }

    /**
     * 恢复存档里没跑完的线程（**尽力而为**，见类 KDoc「v1 已知简化」第 4 条）。
     *
     * 只在能跑 `serverTick`（服务端）且结构可用时才恢复；配方从 `RecipeManager` 重取，
     * 再重跑一遍 `fullModifyRecipe` + 本线程并行 —— **不再扣一次料**（料在存档前就扣过了），
     * 所以这里绕开 [startThread] 自己拼 `ThreadRec`。
     */
    private fun restoreThreadsIfPossible() {
        val pending = pendingRestore ?: return
        if (!machine.isRecipeLogicAvailable) return
        pendingRestore = null

        ensureSlots()
        val limit = threadLimit
        var slot = 0
        for ((id, savedProgress) in pending) {
            if (slot >= limit) break
            val origin = getRecipeManager().byKey(id).orElse(null) as? GTRecipe ?: continue
            val modified = machine.fullModifyRecipe(origin) ?: continue
            val threaded = applyThreadParallel(modified) ?: continue
            threads[slot] = ThreadRec(threaded, savedProgress.coerceIn(0, threaded.duration), threaded.duration)
            threadChanceCaches[slot] = makeChanceCaches()
            slot++
        }
    }

    companion object {

        /** 空闲槽位找配方的节流间隔（tick）。 */
        const val SEARCH_INTERVAL: Int = 5

        /** 存档里线程表的 NBT 键。 */
        private const val TAG_THREADS: String = "gtet_threads"
        private const val TAG_RECIPE: String = "recipe"
        private const val TAG_PROGRESS: String = "progress"
    }
}
