package rain.gtetcore.gtet.common.machine

import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * [ThreadedRecipeLogic] 的**线程状态文本**：语言键 + 机器 UI 的文本行。
 *
 * 为什么单独一个对象：同一份「线程 N（在用 k/N）+ 每条线程的配方 id 与进度 + 整机同时处理次数」
 * 要出现在两个地方 —— 机器自己的 UI（`TestMultiblockMachine#addDisplayText`）与
 * GTET 自己的 Jade provider（`rain.gtetcore.gtet.integration.jade.provider.ThreadedRecipeLogicProvider`）。
 * - UI 那条路拿到的是**活的** [ThreadedRecipeLogic]（服务端求值），所以直接读快照、走本文件；
 * - Jade 那条路要跨网络，服务端先把数字写进 NBT、客户端再拼文本，所以 provider 只**复用这里的中文/英文键**，
 *   自己按 NBT 拼（不能直接调 [appendDisplayLines] —— 客户端手里的线程表是空的）。
 *
 * ## 为什么线程表不用 `@DescSynced`
 * 见 [ThreadedRecipeLogic] 类 KDoc「v1 已知简化」第 1 条：两条显示路径都是**服务端求值**，
 * 线程表本身不需要同步字段，也就不会每 tick 把一整张表推给客户端。
 *
 * ## 思路来源
 * - 【自研】把显示拆成「语言键（本文件）+ 服务端求值（UI / Jade 各自）」这套结构，
 *   以及四个语言键的文案口径（线程数在上、逐线程明细在下、末行给整机合计）—— GTM 的
 *   `ParallelProvider` / `RecipeLogicProvider` 只报单条配方的数字，没有「机器级线程数」这个概念，
 *   本文件是 GTET 为多线程内核新加的显示层。
 *
 * @author rain fox
 */
object ThreadedRecipeStatus {

    /** 语言键：`线程 %s（在用 %s）` —— 参数依次是「线程数上限」「正在跑的线程数」。 */
    const val LANG_STATUS: String = "gtetcore.threads.status"

    /** 语言键：整机口径的「同时处理多少次配方运行」—— 单一参数，已格式化过的数字字符串。 */
    const val LANG_TOTAL_RUNS: String = "gtetcore.threads.total_runs"

    /** 语言键：单条线程明细 —— 参数依次是「槽位」「配方 id」「进度百分比」。 */
    const val LANG_LINE: String = "gtetcore.threads.line"

    /** 语言键：明细被截断时的尾行 —— 参数依次是「没显示的条数」「显示的条数」。 */
    const val LANG_MORE: String = "gtetcore.threads.more"

    /**
     * 机器 UI 里最多列几条线程明细。
     *
     * 为什么要有上限：多线程机器的槽位能到 256，而进度百分比**每 tick 都在变**，
     * 也就是说 `ComponentPanelWidget` 的文本表会每 tick 变化一次并整表同步给客户端
     * （`detectAndSendChanges` 比较的是整张表）。256 行就是每 tick 一个几百组件的包——
     * 上限取 10 行，剩下的用 [LANG_MORE] 一行交代过去，够玩家看清「线程真的在并行跑」。
     */
    const val DISPLAY_LINES: Int = 10

    /**
     * 登记四个语言键的中英双语。
     *
     * 必须在**数据生成之前**调用（和 `TerminalLang.init()` 一样的道理），
     * 所以挂在 `CommonProxy#kotlinInit()` 里，而不是挂在只会在加载末尾被 Jade 扫描到的插件类上。
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
            LANG_LINE,
            "  Thread #%s: %s (%s)",
            "  线程 #%s：%s（%s）"
        )
        LangUtil.add(
            LANG_MORE,
            "  ...and %s more threads (showing first %s)",
            "  ……还有 %s 条线程（仅显示前 %s 条）"
        )
    }

    /**
     * 把线程状态追加成机器 UI 的文本行（服务端调用）。
     *
     * 两道闸：
     * 1. **客户端直接收手**：线程表只在服务端有内容（它不是 `@DescSynced`，客户端拿到的是空表），
     *    而这条显示路径本来就是「服务端求值 → 组件同步给客户端渲染」，所以客户端调到这里没有意义。
     *    少了这道闸，客户端会把「读不到」显示成「线程 N（在用 0）」，比不显示更误导。
     * 2. **没装线程仓的机器不显示**（线程数上限 ≤ 1）：那种机器退化成 GTCEu 原版「一台机器一条配方」，
     *    线程概念不存在，多两行只是噪音。
     */
    @JvmStatic
    fun appendDisplayLines(textList: MutableList<Component>, logic: ThreadedRecipeLogic) {
        val level = logic.machine.self().level
        if (level != null && level.isClientSide) return

        val limit = logic.threadLimit
        if (limit <= 1) return

        val running = logic.runningThreadCount
        textList.add(Component.translatable(LANG_STATUS, limit, running))
        if (running > 0) {
            textList.add(
                Component.translatable(LANG_TOTAL_RUNS, FormattingUtil.formatNumbers(logic.runningTotalRuns))
            )
        }

        val shown = logic.runningThreadSlots.take(DISPLAY_LINES)
        for ((slot, rec) in shown) {
            // 配方 id 理论上可能为 null（运行期 new 出来的配方没有 id），退化成 "?"
            textList.add(
                Component.translatable(
                    LANG_LINE,
                    slot,
                    rec.recipe.id?.toString() ?: "?",
                    "${rec.percent}%"
                )
            )
        }
        if (running > shown.size) {
            textList.add(Component.translatable(LANG_MORE, running - shown.size, shown.size))
        }
    }
}
