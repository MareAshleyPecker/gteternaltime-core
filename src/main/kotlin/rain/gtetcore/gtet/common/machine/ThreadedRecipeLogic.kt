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
 *    避免每条 tick 都去翻配方库）。[tryStartThreads] 分**两轮**发线程：
 *    - **第一轮「不同配方各一条」**（原行为，不退化）：配方库里命中、且「还没有线程在跑它」的候选占一条
 *      空闲槽位；同一种配方在这一轮最多占一条线程；
 *    - **第二轮「同一种配方去吃剩下的空闲线程」**（本版新增的「吃线程并行」）：第一轮开完还有空闲槽位时，
 *      把**已经在跑**的配方再多开一条线程，分配规则见下面「同配方多线程怎么分配」。
 *
 *    两轮走的都是同一条开线程流程：套机器自己的配方修改器（超频等）→ 套本线程的并行 → 条件检查 →
 *    模拟匹配（`RecipeHelper.matchContents`）→ `machine.beforeWorking` → **真正扣料** → 占住槽位。
 * 3. **上限**：能开几条线程由 [threadLimit] 决定（= 线程仓的 `threadCount`，没装仓就是 1）。
 *
 * ## 同配方多线程怎么分配（本版核心）
 * 「同一种配方最多占一条线程」原本是 GTO `duplicateCheck` 的语义，代价是**只有一种原料时整机只用到 1 条线程**：
 * 线程仓有 256 条、并行仓写着 3200，实际同时处理次数仍然只有「一条线程的那一份」。
 * 本版让空闲线程也发给同一种配方，目标是「总处理次数 → 线程数 × 每线程次数」（上限另有约束，见下）。
 *
 * 难点是**不能超发**。`ParallelLogic#getParallelAmount(machine, recipe, M)` 是按**当前库存**算的，
 * 而 GTM 的输入分两类，两类在「开过线程之后」的表现完全不同：
 * - **非 tick 输入（物品/流体）在开线程那一刻就被真扣掉了**（[startThread] 里的 `handleRecipeIO(IN)`），
 *   所以后开的线程看到的库存本来就少了一份 —— 这一类天然不会重复计；
 * - **tick 输入（EUt）与出料口容量不会被提前扣掉**：`EURecipeCapability#getMaxParallelByInput(tick=true)`
 *   算的是「能量仓电压 / 配方 EUt」，`ParallelLogic#limitByOutputMerging` 算的是「出料口塞不塞得下」，
 *   两者在开线程那一刻都还是**满的** → k 条线程会各算一遍同一批额度，加起来就是 k 份，纯属白开
 *   （落后的线程只能等待/回退）。
 *
 * 所以第二轮**不做「每条线程各算一次」**，而是**一组一预算、再把预算均分**（依据见 GTM 源码：
 * `ParallelLogic#getParallelAmount` / `#getMaxByInput` / `#limitByOutputMerging`、
 * `EURecipeCapability#getMaxParallelByInput`、`RecipeCapability#getMaxParallelByInput`）：
 * 1. 取并行仓上限 `M` = `IParallelHatch#getCurrentParallel()`（单线程上限）；
 * 2. 聚合上限 = `M × 本组最多能占的线程数`（= 已在跑条数 + 当前空闲槽位数）；
 * 3. 拿聚合上限当 `parallelLimit` **只调一次** `ParallelLogic#getParallelAmount(...)` ——
 *    这一次调用里 GTM 已经把「输入 / tick 输入 / 输出」三头都判过一遍了；
 * 4. 减掉**本组已在跑线程已经提交的份额** `Σ getTotalRuns() ÷ (subtick × batch)`（[committedUnits]）：
 *    这一步专治上面第二类重复计 —— tick 输入与出料口容量不会因为「已经开过线程」而变小，
 *    只能自己记账减掉；
 * 5. 剩下的按「本组还能开几条线程」**均分**（`extra / openable`），每条再夹到单线程上限 `M`。
 *
 * 这一轮开不完的空闲线程，下一轮（[SEARCH_INTERVAL] tick 后）按同样规则继续，
 * 直到「本组预算用完 / 没空闲槽位 / 谁都开不出来」。
 *
 * ## 公平性（多个配方争抢空闲线程）
 * 第二轮是**轮转**的：每一趟给「每个在跑的配方」各加**最多一条**线程，趟与趟之间循环，直到没人能再开。
 * 于是多个配方是**齐步长**的（A 加一条、B 加一条、A 再加一条…），谁也不会把空闲线程吃光。
 * 均分用的 `openable` 里还含「本组还能用几条空闲槽位」这一层，所以「好几个配方 + 槽位不够」
 * 时每个配方各自分到的倍数也会跟着自己的线程数一起缩。
 *
 * ## 每线程并行怎么套（④）
 * 每条线程**自己**读 `IMultiController#getParallelHatch()` 的 `getCurrentParallel()` 当上限 M，
 * 再用 `ParallelLogic#getParallelAmount(...)` 收缩到「这台机器当下真的喂得起的倍数」
 * （输入不够按输入算、输出塞不下按输出算，这段判定不自己写），然后套
 * `ModifierFunction.builder().modifyAllContents(×p).eutMultiplier(×p).parallels(p)`。
 * 差别只在「p 从哪来」：[planThreadParallel] 里第一轮是「这一条线程自己能吃多少」，
 * 第二轮是「本组总预算减掉已提交，再均分」。
 *
 * 之所以走 `ModifierFunction` 链路而不是自己改内容表：概率逻辑、tick 内容、
 * `parallels` 与 `getTotalRuns()`（概率掷点要按总运行次数算）全都挂在 GTRecipe 的既有语义上，
 * 自己手改内容会把这些一起改坏。
 *
 * ## ⚠️ 并行口径的变化（接入本内核的机器必读）
 * 并行仓的 `getCurrentParallel()` 在本内核里被定义为**每条线程**的并行上限，
 * 整机上限 = `M × 同时跑的线程数`。这是线程仓存在的意义（「在并行仓之上再叠一层线程」），
 * 但接线的机器要清楚这条口径：**装了线程仓 + 并行仓时，总处理次数上限 = 线程数 × 并行倍数**，
 * 而不再是「并行仓数字」本身。实际能吃到多少还受输入供给、EUt、出料口容量的约束（见上）。
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
 * 1. **客户端只有一个进度条，但「同时跑了几条」现在能看见了**：线程表仍然**没有**同步到客户端
 *    （不是 `@DescSynced`）。为了让 Jade / TOP / 机器 UI 不至于显示空白，本类把「下标最小的那条在跑线程」
 *    镜像进基类的 `lastRecipe` / `progress` / `duration` / `isActive` 四个**已同步**字段，
 *    也就是「基类那套单进度」照旧能用，但只反映**一条**线程。
 *    整机线程状态另外走两条**服务端求值**的路子，都不需要同步字段：
 *    - 机器自身 UI：`TestMultiblockMachine#addDisplayText` 把线程行写进
 *      `ComponentPanelWidget` 的文本表（该 widget 的 `textSupplier` 是在**服务端**
 *      `detectAndSendChanges` 里求值后再把组件同步给客户端的）；
 *    - Jade：GTET 自己的 `ThreadedRecipeLogicProvider` 在 `appendServerData`（服务端）里读本类，
 *      写成 NBT 交给客户端渲染。
 *    线程表本身仍然没有 `@DescSynced`，所以 `runningThreadSlots` / `runningTotalRuns` 这些
 *    快照**只能在服务端读**（客户端读到的永远是空表）。
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
 * 8. **同配方多线程的预算是「一组一份」的，跨组不互算**：k 条线程跑**不同**配方时，每条线程仍各算一遍
 *    `getParallelAmount`（与加入 fan-out 之前完全一致）—— 也就是说「tick 输入 / 出料口容量」这类
 *    不会被提前扣掉的资源，在**多个不同配方之间**仍然可能各算一份。这是改动前就有的口径，
 *    本版**不动它**：一改就会把「不同配方各跑各的」变成「不同配方互相抢并行」，
 *    而「每种原料各吃满自己那份」正是这台机器原本的卖点。同配方多线程那一侧则由
 *    [committedUnits] 记账兜住（见类 KDoc「同配方多线程怎么分配」第 4 步）。
 * 9. **跨轮次时对「已真扣掉的物品」会保守地再减一次**：`committedUnits` 减的是「已在跑线程提交的
 *    全部份额」，而非 tick 输入那部分库存其实**已经**被扣过了 → 预算会被低估一点点，
 *    表现为「同一种配方在物品刚好够时可能少开一条线程」。这是有意选的保守侧：
 *    物品有 `RecipeHelper#matchContents` 这道闸门兜底（不够就开不出来，不会凭空吞），
 *    而 **tick 输入与出料口容量没有这道闸门**，宁可少开也不超发。
 *    同一条账还覆盖不到的地方是**机器修改器自己算出来的那一份**（超频的 `subtickParallels`、
 *    批处理的 `batchParallels` 都在 `fullModifyRecipe` 里按当时库存各算一遍）：本内核只能在
 *    「每条线程该拿几倍并行」这一层记账，这已经能把 `parallels` 那一份卡住；剩下那一份靠
 *    「非 tick 输入开线程时真扣料」自然收敛，只有 tick 输入 / 出料口容量仍可能在多线程下多算，
 *    后果是「线程等电」（自限）与「出料口满时丢产物」（GTM 单配方本来就这样丢，见第 5 条）。
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
 * ## 与 GTM 自带 Jade 提示的口径差异（为什么那串数字里没有线程数）
 * GTM 的 `ParallelProvider` 报的是**单个配方对象**的运行次数
 * `getTotalRuns() = parallels × subtickParallels × batchParallels`，而它读的是
 * `recipeLogic.getLastRecipe()` —— 在本内核里这个字段被镜像成「下标最小的那条在跑线程」。
 * 也就是说那串数字描述的是**一条线程**，而线程数是**机器级**的量（同时跑几种不同配方），
 * 两者是相乘关系、不在同一个口径里：GTM 那个提示结构上就装不下线程数。
 * 整机口径由本类的 [runningTotalRuns]（= Σ 各线程 `getTotalRuns()`）给出，
 * 显示在机器 UI 与 GTET 自己的 Jade provider 里。
 *
 * ## 思路来源
 * - 【借鉴形状】GTOCore（`D:\java\GTOCore`）的线程记录结构 `ICrossRecipeMachine$Thread { progress, recipe, duration, use }` 与 `ICrossRecipeMachine$Logic`（`updateTickSubscription` / `findAndHandleRecipe` / `serverTick` / `onRecipeFinish` / `saveCustomPersistedData` / `loadCustomPersistedData` 一整套 `native` 覆盖）—— 借的只是「每条线程一条记录（配方 + 进度 + 时长）」和「线程逻辑要自己接管 tick / 存档」这两个形状。
 *   ⚠️ **必须注明**：GTO 的**调度与记账算法一行都拿不到** —— `libs/gtolib-1.0.jar` 里 `ICrossRecipeMachine`、`ICrossRecipeMachine$Thread`、`ICrossRecipeMachine$Logic`、`ThreadPartMachine` 的方法体全是 `native`（`javap` 只能看到签名，实现被抽到 `native0/native/` 那堆 `.bin` 的加密库里）。所以**线程表调度、动态开线程策略、每线程独立 IO 记账、线程数上限来源，全部是 GTET 自研**（见下面的【自研】条）。
 * - 【自研】线程表（`data class ThreadRec` 槽位数组）+ 「先给不同配方、再把剩余空闲线程发给**同一种**配方」的两轮调度（[tryStartThreads]）+ 「一组一预算、再均分」的防超发记账（[planThreadParallel] / [committedUnits]：用 `ParallelLogic#getParallelAmount` 的聚合上限调用一次性判定「输入 / tick 输入 / 输出」三头，再减掉本组已提交的份额，然后按本组还能开的线程数均分）+ 轮转式公平分配 + 同配方判等（按 `GTRecipe#id`，`id == null` 时退化成引用相等 —— 现成的 `GTRecipe#equals` 只比 id 且对 null id 会 NPE）+ 「线程数上限由线程仓 tier 决定」+ 「玩家下调线程数不砍已开线程」+ 「每线程独立 `chanceCaches`」+ 「基类单进度镜像」+ 存档恢复策略：这些在 GTOCore 里都没有可抄的实现（GTO 那半边在 native 里，且是 `ICrossRecipeMachine` 专属的私有调度 —— 它只有「同一种配方占一条线程」的 `duplicateCheck` 语义，没有「同配方多线程」这回事）。
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
    data class ThreadRec(val recipe: GTRecipe, var progress: Int, val duration: Int) {

        /**
         * 这条线程的进度百分比（0~100）。
         *
         * 时长为 0 时返回 0：`duration` 是开线程时从配方钉下来的，理论上 ≥1，
         * 但这里不假设上游——除零比显示个 0% 糟糕得多。
         */
        val percent: Int get() = if (duration <= 0) 0 else progress * 100 / duration
    }

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

    /**
     * 正在跑的线程的「槽位 + 记录」快照（只读）。
     *
     * 与 [runningThreads] 的区别只有一个：带上槽位下标，玩家/UI 才能对上「第几条线程」。
     * **不做任何同步**：本快照是服务端状态，UI 侧靠 `ComponentPanelWidget` 的
     * `textSupplier`（服务端 `detectAndSendChanges` 时求值）与 Jade 的 `appendServerData`
     * 把它变成文本/组件送出去 —— 线程表本身仍然不是 `@DescSynced`（见类 KDoc「v1 已知简化」第 1 条）。
     */
    val runningThreadSlots: List<Pair<Int, ThreadRec>>
        get() = threads.withIndex().filter { it.value != null }.map { it.index to it.value!! }

    /**
     * 所有在跑线程的「运行次数」之和 —— 每线程取 `GTRecipe#getTotalRuns()`
     * （`parallels × subtickParallels × batchParallels`）。
     *
     * 这个数字才是「这台机器此刻同时在处理多少次配方」的**整机口径**：
     * GTM 自己的 Jade 提示只报 [runningThreads] 里某**一条**配方的 `getTotalRuns()`，
     * 天然不含线程数，所以两者要相乘着看（见类 KDoc 末尾「与 GTM 提示的口径差异」）。
     *
     * ## 「吃线程并行」之后这个数字的含义变化（⚠️ 玩家/UI 必读）
     * 加入 fan-out 之前：`Σ` = 「**不同配方**各自那一份并行之和」，同一种配方最多贡献一份。
     * 加入之后：**同一种配方可以同时占多条线程**，每条线程的 `parallels` 是「本组预算均分下来的一份」，
     * 于是 `Σ` 仍然等于「整机此刻一次性处理多少次配方运行」，但它的**来源变了**：
     * 它可以由 k 条跑同一种配方的线程凑出来（≈ `k × 每线程倍数`），而不是「k 种不同配方各一份」。
     * 换句话说：显示不变、口径不变（都是「同时处理次数」），变的是**它现在会随线程数一起涨**。
     * 能不能真的涨到 `线程数 × 并行仓倍数`，取决于输入供给 / EUt / 出料口容量
     * （见类 KDoc「同配方多线程怎么分配」；本内核只保证**不超发**，不保证喂得起）。
     */
    val runningTotalRuns: Long get() = runningThreads.sumOf { it.recipe.totalRuns.toLong() }

    // ────────────────────────────────────────────────
    //  诊断（临时）
    // ────────────────────────────────────────────────

    /**
     * 【临时诊断】线程数变化时往日志里打一行整机快照。
     *
     * 为什么要有它：线程表不进同步字段，光靠「机器在跑」看不出**开了几条**线程。
     * `logs/latest.log` 里搜 `[GTET][线程诊断]` 就能拿到「在用 N/上限 M + 样本配方 id 与进度 + 合计运行次数」，
     * 这是「线程到底有没有真的开」最直接的证据。
     *
     * 节流：[DIAG_INTERVAL] tick 之内最多一行，且只在「在用线程数」变化时打，
     * 所以 256 线程也不会刷屏（每次变化一行）。
     * **验证完线程行为后可以整段删掉**（本字段 + [diagnose] + `serverTick` 里的调用 + 常量）。
     */
    private var diagCooldown: Int = 0
    private var diagLastRunning: Int = -1

    private fun diagnose() {
        if (diagCooldown > 0) {
            diagCooldown--
            return
        }
        val running = runningThreadCount
        if (running == diagLastRunning) return
        diagLastRunning = running
        diagCooldown = DIAG_INTERVAL
        // 样本只取前几条：256 线程全打出来会让一行日志有上万字符
        // 带上每条线程自己的并行倍数（`×p`）—— 「吃线程并行」是否生效就看这一列：
        // 同一种配方应当出现多行、每行的 p 是「本组总预算 ÷ 线程数」而不是满 M
        val sample = runningThreadSlots.take(DIAG_SAMPLE)
            .joinToString(", ") { (slot, rec) ->
                "#$slot ${rec.recipe.id ?: "?"} ×${rec.recipe.parallels} ${rec.percent}%"
            }
        Gtetcore.LOGGER.info(
            "[GTET][线程诊断] 在跑线程 {}/{}；同时处理次数合计 {}；样本：[{}]",
            running,
            threadLimit,
            runningTotalRuns,
            sample
        )
    }

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
        // 【临时诊断】线程数一变就在日志里留一行快照（见 diagnose() 的说明，验证完可整段删）
        diagnose()

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
     * 分**两轮**发线程（见类 KDoc「调度策略」与「同配方多线程怎么分配」）：
     * 1. **第一轮：不同配方各占一条**（原行为）—— 命中且「还没有线程在跑它」的候选占一条空闲槽位；
     * 2. **第二轮：同一种配方去吃剩下的空闲线程**（「吃线程并行」）—— **轮转**着给每个在跑的配方
     *    各加最多一条线程，一趟下来一条都没开出来就收手。
     *
     * ⚠️ 第二轮的「在跑配方」池必须**含本轮刚从第一轮开出来的那些**：池子是在第一轮的遍历里就地攒的
     * （「已经在线程表里的」和「这一轮刚开成功的」都进池），而不是只收「进这一轮之前就在跑的」——
     * 短配方（`duration = 1` tick）可能在**下一次搜索（[SEARCH_INTERVAL] = 5 tick）之前就跑完**，
     * 那时它已经不在「在跑」状态；只认「之前就在跑」的话，这种配方永远等不到 fan-out，
     * 而「一种原料、只跑一条线程」恰恰就是这种场景。
     *
     * 池子里装的是**配方库给的原始候选对象**：已在跑线程的 `rec.recipe` 已经被套过机器修改器与并行，
     * 拿它再 `fullModifyRecipe` 一遍会重复超频，所以绝不能拿来当原配方。
     * 反过来，`machine.fullModifyRecipe` 每次都会**新建副本**（`ModifierFunction#apply` 里 `new GTRecipe(...)`），
     * 不会改到候选对象本身，所以同一轮里对同一条候选反复算并行是安全的。
     *
     * 一轮搜索本来就能填满多个空闲槽（每命中一条占一个槽位继续往下找），
     * 所以 `[A, B, C] → 三条线程` 仍然只需要一轮。
     */
    private fun tryStartThreads(limit: Int) {
        // ① 第一轮：不同配方各一条线程；池子顺便在这里就地攒（见 KDoc 的 ⚠️）
        val repeats = ArrayList<GTRecipe>()
        val iterator = machine.recipeType.searchRecipe(machine) { true }
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            // 已经有线程在跑这一种配方 → 跳过它（留给第二轮去吃空闲线程）
            if (isRunningElsewhere(candidate)) {
                if (repeats.none { sameRecipe(it, candidate) }) repeats.add(candidate)
                continue
            }
            // 槽位满了：第一轮到此为止（第二轮也没槽位可用，直接收工）
            val slot = freeThreadSlot(limit) ?: return
            if (startThread(slot, candidate, fanOut = false, limit = limit)) {
                // 刚开出来的也要进池子：短配方可能在下一次搜索之前就跑完，那时它已经不在「在跑」状态
                if (repeats.none { sameRecipe(it, candidate) }) repeats.add(candidate)
            }
        }
        if (repeats.isEmpty()) return

        // ② 第二轮：轮转着把同一种配方再开一条线程。
        //    每一趟「每个配方组各加最多一条」→ 多个配方齐步长，谁也不会把空闲线程吃光；
        //    某一趟一条都没开出来（本组预算用完 / 谁都匹配不上）就整轮收手 —— 下一轮搜索会再试。
        while (true) {
            var opened = false
            for (candidate in repeats) {
                val slot = freeThreadSlot(limit) ?: return
                if (startThread(slot, candidate, fanOut = true, limit = limit)) opened = true
            }
            if (!opened) return
        }
    }

    /**
     * 开一条线程：
     * 机器配方修改器（超频等）→ 本线程并行 → 条件 → 模拟匹配 → `beforeWorking` → **真正扣料** → 占槽。
     *
     * 中间任何一步失败都只是「这条候选不能用」，不影响别的线程。
     *
     * @param fanOut `false` = 第一轮「不同配方各一条」；`true` = 第二轮「同一种配方再吃一条空闲线程」，
     *               并行倍数走 [planThreadParallel] 的「一组一预算、再均分」
     * @param limit  当前生效的线程数上限（fan-out 的预算要用它数空闲槽位）
     */
    private fun startThread(slot: Int, origin: GTRecipe, fanOut: Boolean, limit: Int): Boolean {
        val threaded = buildThreadRecipe(origin, fanOut, limit) ?: return false

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
     * 「机器配方修改器（①） + 本线程并行（④）」这两步，开线程与存档恢复共用。
     *
     * @return 这条线程要跑的配方；`null` = 这条配方当下开不出线程
     *         （喂不起 / 上游已经并行过而又要 fan-out —— 见下）
     */
    private fun buildThreadRecipe(origin: GTRecipe, fanOut: Boolean, limit: Int): GTRecipe? {
        // ① 机器自己的配方修改器（超频等）。
        //    注意契约：接入本逻辑的多方块，其 recipeModifier 不应包含并行修改器（见类 KDoc）。
        val modified = machine.fullModifyRecipe(origin) ?: return null

        // 防御：上游修改器已经并行过了就别再叠一遍（会变成 并行²）。
        // 这种配方**也不参与 fan-out**：它的倍数是上游算的，本内核拿不到干净的「本组已提交多少」
        // 口径，硬叠只会超发（第一条线程照旧按原行为直接用它）。
        if (modified.parallels > 1) {
            warnAlreadyParallel(modified)
            return if (fanOut) null else modified
        }

        // ④ 这条线程**自己**那份并行
        val parallels = planThreadParallel(modified, fanOut, limit) ?: return null
        return applyParallel(modified, parallels)
    }

    /**
     * 「上游修改器已经套过并行」这条警告只打一次，免得刷屏。
     *
     * 之所以刷屏风险真实存在：fan-out 每轮搜索都可能再撞上同一批配方，而去重只做一次。
     */
    private fun warnAlreadyParallel(recipe: GTRecipe) {
        if (warnedAlreadyParallel) return
        warnedAlreadyParallel = true
        Gtetcore.LOGGER.warn(
            "[GTET] 线程仓：配方 {} 在机器配方修改器里已经吃过并行（parallels={}），" +
                "线程逻辑不再叠加。请把该多方块的 recipeModifier 换成不含并行的版本。",
            recipe.id,
            recipe.parallels
        )
    }

    /**
     * 算「这条新线程该拿几倍并行」（④）。
     *
     * **[fanOut] = false（第一轮：这一种配方还没有别的线程在跑）**：沿用原行为 ——
     * 上限取并行仓的 `getCurrentParallel()`（记作 M），再用 `ParallelLogic#getParallelAmount(...)`
     * 把 M 收缩到「这台机器当下真的喂得起的倍数」（输入不够按输入算、输出塞不下按输出算，
     * 这段判定不自己写）。
     *
     * **[fanOut] = true（第二轮：同一种配方再吃一条空闲线程）**：**一组一预算、再均分**。
     * 为什么不能像第一轮那样「每条线程各算一次」：`getParallelAmount` 看的只是**当下库存**，
     * 而 tick 输入（EUt：`EURecipeCapability#getMaxParallelByInput` 算的是「能量仓电压 / 配方 EUt」）
     * 与出料口容量（`ParallelLogic#limitByOutputMerging`）在开线程那一刻**都是满的**，
     * k 条线程会各算一遍同一份额度 → 加起来 k 份，等于白开。所以：
     * ```
     * 聚合上限 = M × (本组已在跑条数 + 当前空闲槽位数)
     * 本组总预算 = ParallelLogic.getParallelAmount(machine, recipe, 聚合上限)   ← 只调一次
     * 还能提交   = 本组总预算 − Σ(本组已在跑线程已提交的份额)                     ← 自己记账减掉
     * 本条线程   = clamp(还能提交 ÷ 本组还能开的线程数, 1, M)
     * ```
     * 「已提交的份额」用 [committedUnits] 换算成**当下这份配方的运行次数**为单位
     * （`parallels × subtickParallels × batchParallels`，即 `GTRecipe#getTotalRuns()`）：
     * `getParallelAmount` 判的是「**这份配方**还能跑几次」，而每条线程其实跑的是
     * `getTotalRuns()` 那么多次 —— 有 `OC_NON_PERFECT_SUBTICK` / `BATCH_MODE` 时
     * 每线程的 `subtickParallels × batchParallels` 是按**当时库存**各算各的，线程之间可能不一样，
     * 只按 `parallels` 记账会在这种时候低估上游线程真正吃掉的那份料。形状相同时
     * （稳态下就是这样）这项换算恰好退化成 `Σ parallels`，不影响「cap 限住时 k 条线程各拿满 M」这条主路径。
     *
     * 非 tick 输入（物品/流体）那一侧本来就因为「开线程时真扣料」而自动变小，
     * 这里再减一次已提交份额属于**保守**（见类 KDoc「v1 已知简化」第 9 条）。
     *
     * @return 该线程的并行倍数（≥1）；`null` = 这条配方当下连 1 份都喂不起（开不出线程）
     */
    private fun planThreadParallel(recipe: GTRecipe, fanOut: Boolean, limit: Int): Int? {
        val hatch = parallelHatch() ?: return 1
        val cap = hatch.currentParallel
        if (cap <= 1) return 1

        if (!fanOut) {
            val achievable = ParallelLogic.getParallelAmount(getMachine(), recipe, cap)
            return if (achievable <= 0) null else achievable
        }

        val running = countRunningSameRecipe(recipe)
        val committed = committedUnits(recipe, unitRuns(recipe))
        val free = countFreeSlots(limit)
        if (free <= 0) return null

        // 聚合上限 = M × 本组最多能占的线程数；先按 Long 乘再夹到 Int，免得高位 tier 直接溢出
        val threadsForGroup = (running + free).toLong()
        val aggregateCap = minOf(cap.toLong() * threadsForGroup, Int.MAX_VALUE.toLong()).toInt()

        // 便宜的早退：`getParallelAmount` 的结果永远 ≤ 传进去的上限，所以上限已经 ≤ 已提交时
        // 一定没有余量 —— 省掉一次「输入/输出模拟」（大倍数下 `limitByOutputMerging` 是二分搜索，
        // 每 5 tick 每个空闲槽位都调一次的话不便宜）
        if (aggregateCap.toLong() <= committed) return null

        val affordable = ParallelLogic.getParallelAmount(getMachine(), recipe, aggregateCap)

        // 减掉本组已经提交的份额 —— 这一步专治「tick 输入 / 出料口容量不会因为开过线程而变小」
        val extra = affordable.toLong() - committed
        if (extra < 1L) return null

        // 均分：本组还能开的线程数（每条至少 1 份，所以取 min(free, extra)）
        val openable = minOf(free.toLong(), extra)
        return minOf(cap.toLong(), maxOf(1L, extra / openable)).toInt()
    }

    /**
     * 「当下这份配方跑一次」等于多少次**配方运行**：`subtickParallels × batchParallels`
     * （配方的 `parallels` 还没套，所以 `GTRecipe#getTotalRuns()` 此刻就等于这个乘积）。
     *
     * 这是 [planThreadParallel] 与 [committedUnits] 之间的**换算单位**：不这么做的话，
     * 「有亚 tick / 批处理的机器」上线程之间的份额就没法比较（每线程的乘积是按当时库存各算的）。
     * 两个字段都 ≥1（GTRecipe 的默认值），所以不用担心除零。
     */
    private fun unitRuns(recipe: GTRecipe): Long =
        recipe.subtickParallels.toLong() * recipe.batchParallels.toLong()

    /**
     * 本组（同 [sameRecipe]）已在跑线程**已经提交**的份额，单位 = 当下这份配方的运行次数
     * （见 [unitRuns]）：Σ 各线程 `getTotalRuns() ÷ unitRuns`，**向上取整**。
     *
     * 为什么必须自己记这份账：[planThreadParallel] 里说过 —— 非 tick 输入在开线程时就被真扣掉了
     * （库存会自己变小），而 **tick 输入与出料口容量不会**，`ParallelLogic` 每次去看都还是「满的」，
     * 只能靠这份账把它们减掉。向上取整是有意的：宁可高估已提交（少开一条），也不超发。
     */
    private fun committedUnits(recipe: GTRecipe, unit: Long): Long {
        var sum = 0L
        for (rec in threads) {
            if (rec == null || !sameRecipe(rec.recipe, recipe)) continue
            val runs = rec.recipe.totalRuns.toLong()
            sum += if (unit <= 0L) runs else (runs + unit - 1L) / unit
        }
        return sum
    }

    /**
     * 把并行倍数真正套到配方上。
     *
     * 套法：内容 ×p、EUt ×p、`recipe.parallels = p`（三样缺一不可：只乘内容不设 `parallels`
     * 会让概率掷点按 1 次算，只设 `parallels` 不乘内容则等于白开线程）。
     * 走 `ModifierFunction` 链路而不是自己改内容表：概率逻辑、tick 内容、
     * `parallels` 与 `getTotalRuns()`（概率掷点要按总运行次数算）全都挂在 GTRecipe 的既有语义上，
     * 自己手改内容会把这些一起改坏。
     *
     * @return 套好并行的配方；`null` = 上游修改器判了 NULL（`ModifierFunction.NULL`）
     */
    private fun applyParallel(recipe: GTRecipe, parallels: Int): GTRecipe? {
        if (parallels <= 1) return recipe
        return ModifierFunction.builder()
            .modifyAllContents(ContentModifier.multiplier(parallels.toDouble()))
            .eutMultiplier(parallels.toDouble())
            .parallels(parallels)
            .build()
            .apply(recipe)
    }

    /** 这一种配方（同 [sameRecipe] 口径）此刻有几条线程在跑。 */
    private fun countRunningSameRecipe(recipe: GTRecipe): Int {
        var n = 0
        for (rec in threads) {
            if (rec != null && sameRecipe(rec.recipe, recipe)) n++
        }
        return n
    }

    /** 线程上限以内的空闲槽位数（fan-out 的「本组还能开几条线程」与均分都用它）。 */
    private fun countFreeSlots(limit: Int): Int {
        var n = 0
        for (slot in 0 until minOf(limit, threads.size)) {
            if (threads[slot] == null) n++
        }
        return n
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

    /** 两条配方算不算「同一种」；判定见 [isSameRecipe]。 */
    private fun sameRecipe(a: GTRecipe, b: GTRecipe): Boolean = isSameRecipe(a, b)

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
     *
     * 存档里同一种配方可能有好几条线程（fan-out 开出来的，见类 KDoc「同配方多线程怎么分配」），
     * 所以除第一条之外都按 [planThreadParallel] 的 fan-out 预算走 —— 否则恢复时每条线程都会
     * 各算一遍满 M，tick 输入与出料口容量又被重复计一遍。
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
            val fanOut = countRunningSameRecipe(origin) > 0
            val threaded = buildThreadRecipe(origin, fanOut, limit) ?: continue
            threads[slot] = ThreadRec(threaded, savedProgress.coerceIn(0, threaded.duration), threaded.duration)
            threadChanceCaches[slot] = makeChanceCaches()
            slot++
        }
    }

    companion object {

        /** 空闲槽位找配方的节流间隔（tick）。 */
        const val SEARCH_INTERVAL: Int = 5

        /** 【临时诊断】两条诊断日志之间至少间隔的 tick 数（20 tick = 1 秒）。 */
        private const val DIAG_INTERVAL: Int = 20

        /** 【临时诊断】一行日志里最多列几条线程做样本。 */
        private const val DIAG_SAMPLE: Int = 8

        /** 存档里线程表的 NBT 键。 */
        private const val TAG_THREADS: String = "gtet_threads"
        private const val TAG_RECIPE: String = "recipe"
        private const val TAG_PROGRESS: String = "progress"

        /**
         * 两条配方算不算「同一种」：两边都有 `id` 就比 `id`，否则退化成引用相等。
         *
         * 不用现成的 `GTRecipe#equals`：它只比 `id`，且对 `id == null` 会 NPE（`return this.id.equals(recipe.id);`）。
         * 公开出来是给显示层的「同配方合并」（[ThreadedRecipeStatus.groupSnapshots]）复用同一条判定，
         * 免得两处口径各写一份后漂移。
         */
        fun isSameRecipe(a: GTRecipe, b: GTRecipe): Boolean {
            val idA = a.id
            val idB = b.id
            return if (idA != null && idB != null) idA == idB else a === b
        }
    }
}
