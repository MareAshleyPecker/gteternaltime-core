package rain.gtetcore.gtet.common.machine

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import com.gregtechceu.gtceu.api.recipe.content.Content
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.util.lang.LangUtil
import kotlin.math.roundToLong

/**
 * [ThreadedRecipeLogic] 的**线程状态快照与显示文本**：语言键 + 「同配方合并」后的配方组 + 机器 UI 的文本行。
 *
 * 显示口径：**一个配方组占两行**（同一种配方占用的全部线程合并成一条）。
 * 第一行只放这一组的进度（Jade 那边这一行就是进度条本身，独占一行、后面不再接文字）；
 * 第二行只放这一组**所有线程加起来**的产出物与数量（一次机器周期的量）+ 线程条数 + 该组耗电（[LANG_OUTPUTS]）。
 * 明细之前另有整机口径的合计两行：同时处理多少次配方运行 + 总耗电（[LANG_TOTAL_RUNS] / [LANG_TOTAL_EUT]）。
 *
 * 两条显示路径共用本文件：机器 UI 在服务端求值后把组件同步给客户端；Jade 那条不能直接读线程表
 * （客户端手里的线程表是空的，见 [ThreadedRecipeLogic] 类 KDoc），所以 provider 只按 NBT 拼文本 ——
 * 语言键与 [outputsText] 两边共用，产物名也是同一份已解析组件（见 [OutputSnapshot.name]）。
 *
 * @author rain fox
 */
object ThreadedRecipeStatus {

    /** 语言键：`线程 %s（在用 %s）` —— 参数是「线程数上限」「正在跑的线程条数」。 */
    const val LANG_STATUS: String = "gtetcore.threads.status"

    /** 语言键：整机口径的「同时处理多少次配方运行」—— 单一参数，已格式化过的数字字符串。 */
    const val LANG_TOTAL_RUNS: String = "gtetcore.threads.total_runs"

    /** 语言键：整机口径的耗电 —— 单一参数，已格式化过的 EU/t 数字字符串。 */
    const val LANG_TOTAL_EUT: String = "gtetcore.threads.total_eut"

    /**
     * 语言键：**进度条条内**的文字，面板那边第一行的进度也是它 ——
     * 参数依次是「组内首个槽位」「进度」「总时长」。
     */
    const val LANG_PROGRESS: String = "gtetcore.threads.progress"

    /**
     * 语言键：组行的**第二行**（进度条那行的下面）—— 参数依次是「产出列表」「组内线程条数」「该组 EU/t」。
     *
     * 配方名（id 那一串）**不上屏**：跟产出挤一行会把主信息推远，独占一行又只是噪音。
     */
    const val LANG_OUTPUTS: String = "gtetcore.threads.outputs"

    /** 语言键：明细被截断时的尾行 —— 参数是「没显示的组数」「显示的组数」。 */
    const val LANG_MORE: String = "gtetcore.threads.more"

    /**
     * 语言键：Jade 组行里产物后面的尾巴（机器面板不用它，面板那条仍走 [LANG_OUTPUTS]）——
     * 参数依次是「组内线程条数」「该组 EU/t」。
     *
     * 之所以要单独一条：Jade 那条行把产物画成**图标 + 名字 + 数量**，前面不能再套「产出 %s」这种把整串塞进参数的模板。
     */
    const val LANG_GROUP_TAIL: String = "gtetcore.threads.group_tail"

    /** 语言键：一条组行里产物种类超出上限时的补充 —— 参数是「没显示的种数」。 */
    const val LANG_OUTPUT_MORE: String = "gtetcore.threads.output_more"

    /** 语言键：同一行内多个产物之间的分隔符。 */
    const val LANG_OUTPUT_SEP: String = "gtetcore.threads.output_sep"

    /** 语言键：该组没有任何**物品**产出时的占位（流体产出本条不列，见 [outputSnapshotOf] 的说明）。 */
    const val LANG_NO_OUTPUT: String = "gtetcore.threads.no_output"

    /**
     * 机器 UI 里最多列几条配方组。
     *
     * 上限的理由是**包大小**：这段文本由 `ComponentPanelWidget` 在服务端每 tick 求值，整表同步给客户端
     * （`detectAndSendChanges` 比较的是整张表），而进度每 tick 都在动 —— 256 条线程全列出来就是每 tick
     * 一个几百组件的包。取 10 行足够看清「多条线程在并行、各自产出什么」。
     */
    const val DISPLAY_LINES: Int = 10

    /**
     * 每条组行最多列几种产物，超出的用 [LANG_OUTPUT_MORE] 一句带过。
     *
     * 取 4 是照着研磨配方的上限来的（GTM `GTRecipeTypes#MACERATOR_RECIPES` = `setMaxIOSize(1, 4, 0, 0)`，
     * 也就是本内核那台试验机最爱跑的一类），这样研磨机永远不会出现「等 N 种」。
     * 更大的类型（离心机/筛选机/化学浸洗是 6 种）会落到 [LANG_OUTPUT_MORE]。
     * 上限本身是为载荷：Jade 侧最多 6 行 × 4 种 = 24 条产物条目（NBT 里每条 ≈ 一段组件 JSON + 两个 long + 一个标志，
     * 名字那段比原来的翻译键长，但换来的是客户端能翻出完整名字，见 [OutputSnapshot.name]）。
     */
    const val OUTPUTS_PER_LINE: Int = 4

    /**
     * 一个配方组的只读显示快照（服务端算：UI 直接渲染，Jade 写进 NBT）。
     *
     * @param slot        组内**最小**槽位（= 组内第一条在跑的线程）
     * @param threadCount 这一种配方占了几条线程
     * @param progress    组内最小槽位那条线程的进度（tick）
     * @param duration    同上那条线程的总时长（tick）
     * @param eutPerTick  这一组**实际吃的电**（EU/t）= 组内各线程 [eutPerTickOf] 之和
     * @param outputs     合并后的物品产出（已按 [OUTPUTS_PER_LINE] 截断）
     * @param hiddenKinds 被截断掉的产物种数（0 = 没截断）
     */
    data class GroupSnapshot(
        val slot: Int,
        val threadCount: Int,
        val progress: Int,
        val duration: Int,
        val eutPerTick: Long,
        val outputs: List<OutputSnapshot>,
        val hiddenKinds: Int
    )

    /**
     * 一种产物的合并结果。
     *
     * @param stack   产物物品（**数量固定 1**，只用来取图标与名字）；要显示的数量看 [min] / [max]。
     *                ⚠️ 传物品栈而不是「翻译键 / 已解析名字」：GTCEu 材料物品的键本身是带 `%s` 的模板
     *                （`tagprefix.dust` = `%s粉`），材料名要运行时拼进去 —— 传栈则**图标与正确名字一起拿到**，
     *                客户端自己 `hoverName` 就能解析（GTET 早期为此改传组件 JSON，现在这条限制没了，载荷也更小）。
     * @param min/max 一次机器周期该产出的数量区间（一般 min == max）
     * @param chanced 是否为**期望值**（内容带概率，见 [outputSnapshotOf]），显示时标 ≈
     */
    data class OutputSnapshot(val stack: ItemStack, val min: Long, val max: Long, val chanced: Boolean) {

        /** 产物显示名（服务端与客户端都能解析）。 */
        val name: Component get() = stack.hoverName
    }

    /** [groupSnapshots] 的结果：截断后的组行 + 组总数（组总数用于「还有 N 组」那一行）。 */
    data class GroupSnapshotPage(val groups: List<GroupSnapshot>, val totalGroups: Int)

    /**
     * 登记本文件的语言键（中英双语）；必须在**数据生成之前**调用，挂在 `CommonProxy#kotlinInit()` 里。
     */
    @JvmStatic
    fun initLang() {
        LangUtil.add(
            LANG_STATUS,
            "Threads %s (%s in use)",
            "线程 %s（在用 %s）"
        )
        LangUtil.add(
            LANG_TOTAL_RUNS,
            "Processing %s recipe runs at once (sum over all threads)",
            "同时处理 %s 次配方运行（各线程之和）"
        )
        LangUtil.add(
            LANG_TOTAL_EUT,
            "Consuming %s EU/t (sum over all threads)",
            "整机耗电 %s EU/t（各线程之和）"
        )
        LangUtil.add(
            LANG_PROGRESS,
            "#%s  %s/%s t",
            "#%s  %s/%s t"
        )
        LangUtil.add(
            LANG_OUTPUTS,
            "Output %s · %s threads · %s EU/t",
            "产出 %s · %s 条线程 · %s EU/t"
        )
        LangUtil.add(
            LANG_MORE,
            "  ...and %s more recipe groups (showing first %s)",
            "  ……还有 %s 个配方组（仅显示前 %s 个）"
        )
        LangUtil.add(
            LANG_GROUP_TAIL,
            " · %s threads · %s EU/t",
            " · %s 条线程 · %s EU/t"
        )
        LangUtil.add(
            LANG_OUTPUT_MORE,
            ", +%s more kinds",
            "，等 %s 种"
        )
        LangUtil.add(
            LANG_OUTPUT_SEP,
            ", ",
            "、"
        )
        LangUtil.add(
            LANG_NO_OUTPUT,
            "no item output",
            "无物品产出"
        )
    }

    /**
     * 把在跑的线程按**同一种配方**合并成组（组内按槽位升序），组也按「组内最小槽位」升序。
     *
     * 只对前 [maxGroups] 组算产物明细，剩下的组只计数 —— 截断时不必白算 200 多组的产出。
     */
    @JvmStatic
    fun groupSnapshots(logic: ThreadedRecipeLogic, maxGroups: Int): GroupSnapshotPage {
        val groups = ArrayList<MutableList<Pair<Int, ThreadedRecipeLogic.ThreadRec>>>()
        val byId = HashMap<ResourceLocation, MutableList<Pair<Int, ThreadedRecipeLogic.ThreadRec>>>()
        for ((slot, rec) in logic.runningThreadSlots) {
            val id = rec.recipe.id
            if (id == null) {
                // 与内核同一条判等（ThreadedRecipeLogic.isSameRecipe）：id 为 null 时退化成引用相等，
                // 而每条线程拿到的都是机器修改器新建的副本 → 各自成组
                val group = groups.firstOrNull {
                    ThreadedRecipeLogic.isSameRecipe(it[0].second.recipe, rec.recipe)
                }
                if (group != null) group.add(slot to rec) else groups.add(arrayListOf(slot to rec))
                continue
            }
            val existing = byId[id]
            if (existing != null) {
                existing.add(slot to rec)
            } else {
                val group = arrayListOf(slot to rec)
                byId[id] = group
                groups.add(group)
            }
        }
        val shown = groups.take(maxGroups).map { snapshotOf(it) }
        return GroupSnapshotPage(shown, groups.size)
    }

    private fun snapshotOf(group: List<Pair<Int, ThreadedRecipeLogic.ThreadRec>>): GroupSnapshot {
        val (slot, head) = group[0]
        val (outputs, hiddenKinds) = mergeOutputs(group)
        // 组内每条线程各扣各的电（ThreadedRecipeLogic#handleThreadTickRecipe），所以该组实际耗电是相加
        val eut = group.sumOf { eutPerTickOf(it.second.recipe) }
        // 进度取组内**最小槽位**那条线程：它正是镜像进基类单进度字段的那一条（见 mirrorToBaseFields），
        // 两边口径对得上，而且不会在组内几条线程之间来回跳（组内各线程进度本来就不同）。
        return GroupSnapshot(
            slot = slot,
            threadCount = group.size,
            progress = head.progress,
            duration = head.duration,
            eutPerTick = eut,
            outputs = outputs,
            hiddenKinds = hiddenKinds
        )
    }

    /**
     * 一条线程**每 tick 实际吃的电**（EU/t）。
     *
     * 取 GTM 现成的 `RecipeHelper.getRealEUt`（= 有电输入就取输入，否则取输出）。
     * GTM 7.5.3 把 EU 存在 **tickInputs/tickOutputs** 里（`GTRecipe#inputEUt = calculateEUt(tickInputs)`），
     * 而所有修改器都是直接改写这份 EU 内容（超频/亚 tick/并行走 `eutMultiplier`，批处理只改时长），
     * 所以这里读到的已经是「本线程超频 × 亚 tick × 它自己那份并行」之后的**每 tick** 值。
     *
     * ⚠️ 不要再乘 `getTotalRuns()`：那是「这条线程一次跑几下**配方运行**」的计数，
     * 不是功率倍率 —— 乘上去会把调度口径当成电工口径，报出机器根本不存在的负载。
     */
    private fun eutPerTickOf(recipe: GTRecipe): Long = RecipeHelper.getRealEUt(recipe).totalEU

    /**
     * 整机耗电（EU/t）= **全部**在跑线程之和。
     *
     * 不按 [DISPLAY_LINES] / Jade 那 6 行截断：截断的是明细，总量报了「只显示的那些」就是假数。
     */
    @JvmStatic
    fun totalEutPerTick(logic: ThreadedRecipeLogic): Long =
        logic.runningThreadSlots.sumOf { eutPerTickOf(it.second.recipe) }

    /** 组内各线程的产物按物品（含 NBT）合并求和 —— 这些线程跑的是同一种配方，只是各自的并行倍数不同。 */
    private fun mergeOutputs(
        group: List<Pair<Int, ThreadedRecipeLogic.ThreadRec>>
    ): Pair<List<OutputSnapshot>, Int> {
        val merged = ArrayList<Accumulated>()
        for ((_, rec) in group) {
            for (content in rec.recipe.getOutputContents(ItemRecipeCapability.CAP)) {
                val one = outputSnapshotOf(content, rec.recipe) ?: continue
                val existing = merged.firstOrNull { ItemStack.isSameItemSameTags(it.key, one.key) }
                if (existing == null) {
                    merged.add(one)
                } else {
                    existing.min += one.min
                    existing.max += one.max
                }
            }
        }
        val hidden = (merged.size - OUTPUTS_PER_LINE).coerceAtLeast(0)
        // 直接把物品栈带出去：面板取名字、Jade 取图标 + 名字，两边同一份口径
        return merged.take(OUTPUTS_PER_LINE).map { OutputSnapshot(it.key, it.min, it.max, it.chanced) } to hidden
    }

    /**
     * 一条产物内容 × 这份配方 → 一次机器周期的数量区间。
     *
     * ⚠️ 概率产出（`chance < maxChance`）按**期望值**折算：× `getTotalRuns()` × 加成后的概率，
     * 口径与 GTM 自己的 `RecipeOutputProvider` 一致。之所以要乘总运行次数：GTM 的修改器
     * （并行/批处理/亚 tick）**不**会乘概率内容的数量（`Content#copy` 对 `chance < maxChance` 直接跳过修改器），
     * 而必定产出的内容在开线程时就已经乘过并行倍数了 —— 所以这里只按内容里的数量取值，不再乘 `getTotalRuns()`。
     * 折算出来的数字带 `≈` 标记，不静默当成必定产出。
     *
     * 流体产出与 `chance == 0`（永不产出）的内容都**不列**：前者会让载荷翻倍且本机（研磨类）用不到，
     * 后者列出来只会误导。
     */
    private fun outputSnapshotOf(content: Content, recipe: GTRecipe): Accumulated? {
        if (content.chance <= 0) return null
        val ingredient = ItemRecipeCapability.CAP.of(content.content)
        val stack = ingredient.items.firstOrNull() ?: return null
        if (stack.isEmpty) return null

        // 数量可能是个区间（IntProviderIngredient：`SizedIngredient` 包一层的情况也算）
        val provider = when {
            ingredient is IntProviderIngredient -> ingredient
            ingredient is SizedIngredient && ingredient.inner is IntProviderIngredient ->
                ingredient.inner as IntProviderIngredient
            else -> null
        }
        var min = (provider?.countProvider?.minValue ?: stack.count).toLong()
        var max = (provider?.countProvider?.maxValue ?: stack.count).toLong()

        val chanced = content.chance < content.maxChance
        if (chanced) {
            val recipeTier = RecipeHelper.getPreOCRecipeEuTier(recipe)
            val boosted = recipe.recipeType.chanceFunction
                .getBoostedChance(content, recipeTier, recipeTier + recipe.ocLevel)
            val factor = recipe.totalRuns.toDouble() * boosted / content.maxChance
            min = (min * factor).roundToLong()
            max = (max * factor).roundToLong()
        }
        return Accumulated(stack.copyWithCount(1), min, max, chanced)
    }

    /** 合并过程中的可变累加项（`key` 的 count 固定为 1，只用来判「同一种产物」）。 */
    private class Accumulated(val key: ItemStack, var min: Long, var max: Long, val chanced: Boolean)

    /**
     * 产物列表 → 显示文本。Jade 侧也用这个方法（客户端按 NBT 重建出 [OutputSnapshot] 后调用），
     * 保证机器面板与 Jade 两处的产物文本一模一样。
     *
     * 名字直接用 [OutputSnapshot.name] 这个已解析的组件，**不**再 `translatable(键)`（理由见它的 KDoc）。
     */
    @JvmStatic
    fun outputsText(outputs: List<OutputSnapshot>, hiddenKinds: Int): Component {
        if (outputs.isEmpty()) return Component.translatable(LANG_NO_OUTPUT)
        val text = Component.empty()
        outputs.forEachIndexed { index, output ->
            if (index > 0) text.append(Component.translatable(LANG_OUTPUT_SEP))
            text.append(output.name).append(" ").append(countText(output))
        }
        if (hiddenKinds > 0) text.append(Component.translatable(LANG_OUTPUT_MORE, hiddenKinds))
        return text
    }

    /**
     * 数量文本：`×3`（定量）/ `×1~2`（区间）/ `≈4`（概率产出的期望值；区间 + 概率时两者都在）。
     *
     * 抽出来是为了让 Jade 那条「图标 + 名字 + 数量」的行与机器面板共用同一套口径。
     */
    @JvmStatic
    fun countText(output: OutputSnapshot): Component {
        val count = if (output.min == output.max) {
            FormattingUtil.formatNumbers(output.min)
        } else {
            "${FormattingUtil.formatNumbers(output.min)}~${FormattingUtil.formatNumbers(output.max)}"
        }
        return Component.literal("${if (output.chanced) "≈" else "×"}$count")
    }

    /**
     * 把线程状态追加成机器 UI 的文本行（服务端调用）。
     *
     * 两道闸：客户端直接收手（线程表只在服务端有内容，读不到会显示成「在用 0」，比不显示更误导）；
     * 线程数上限 ≤ 1 的机器不显示（那种机器退化成 GTCEu 原版的单配方机器，多两行只是噪音）。
     */
    @JvmStatic
    fun appendDisplayLines(textList: MutableList<Component>, logic: ThreadedRecipeLogic) {
        val level = logic.machine.self().level
        if (level != null && level.isClientSide) return

        val limit = logic.threadLimit
        if (limit <= 1) return

        val running = logic.runningThreadCount
        textList.add(Component.translatable(LANG_STATUS, limit, running))
        if (running <= 0) return

        textList.add(
            Component.translatable(LANG_TOTAL_RUNS, FormattingUtil.formatNumbers(logic.runningTotalRuns))
        )
        textList.add(
            Component.translatable(LANG_TOTAL_EUT, FormattingUtil.formatNumbers(totalEutPerTick(logic)))
        )

        val page = groupSnapshots(logic, DISPLAY_LINES)
        for (group in page.groups) {
            // 第一行只放进度（Jade 那边这一行就是进度条本身），第二行才是产出 / 线程数 / 耗电
            textList.add(
                Component.translatable(LANG_PROGRESS, group.slot, group.progress, group.duration)
            )
            textList.add(
                Component.translatable(
                    LANG_OUTPUTS,
                    outputsText(group.outputs, group.hiddenKinds),
                    group.threadCount,
                    FormattingUtil.formatNumbers(group.eutPerTick)
                )
            )
        }
        if (page.totalGroups > page.groups.size) {
            textList.add(Component.translatable(LANG_MORE, page.totalGroups - page.groups.size, page.groups.size))
        }
    }
}
