package rain.gtetcore.gtet.integration.jade.provider

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.machine.thread.ThreadedRecipeLogic
import rain.gtetcore.gtet.common.machine.thread.ThreadedRecipeStatus
import snownee.jade.api.BlockAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.config.IPluginConfig
import snownee.jade.api.ui.BoxStyle

/**
 * GTET 自己的 Jade provider：把**多线程配方逻辑**的整机线程状态摆到提示里。
 *
 * 显示：`线程 256（在用 5）` + 整机「同时处理 N 次配方运行」+ 整机「耗电 Σ EU/t」+ 逐**配方组**两行
 * （第一行是整条绿色进度条，条内 `#槽位  进度/时长 t`，独占一行；第二行是**产物图标 + 名字 + 数量**
 * + 线程条数 + **该组 EU/t**，配方 id 不上屏）。组的定义与合并规则见 [ThreadedRecipeStatus]。
 *
 * 产物的画法照 GTM 的 `RecipeOutputProvider`（`add(helper.smallItem(stack))` 起行、`append(...)` 续同一行），
 * 也就是 GTO 那套「图标 + `数量× 名字`」的多线程提示 —— 区别是 GTET 把一组挤在**一行**里（行数上限见 [GROUP_LIMIT]）。
 *
 * ## 为什么必须自己写一个 provider
 * GTM 的 `ParallelProvider` / `RecipeOutputProvider` 读的都是 `recipeLogic.getLastRecipe()` —— **单个配方对象**，
 * 而线程数是**机器级**的量（线程条数 ≥ 配方种数：同一种配方可以占多条线程），GTM 那个提示结构上装不下。
 *
 * ## 服务端/客户端分工（Jade 的标准写法）
 * [appendServerData] 在**服务端**跑（此时才读得到真的线程表，它不是 `@DescSynced`），把数字与最多
 * [GROUP_LIMIT] 条组行写进 NBT；[appendTooltip] 在**客户端**跑，只读 NBT 拼文本，不碰服务端对象。
 * 两边共用 [ThreadedRecipeStatus] 的语言键与产物文本构造。
 *
 * ## ⚠️ 与 GTM 自带「单配方进度条」的关系
 * 装了线程仓（线程上限 > 1）的机器上，GTM 的 `WorkableBlockProvider` 那条「4 / 5 s」进度条已经被
 * `MixinWorkableProgressBar` 取消（它反映的是基类那几个单配方字段，多线程下没有意义），
 * 由本 provider 的逐组绿色进度条接管；没装线程仓的机器照旧显示 GTM 那条。
 *
 * ## 没有线程仓的机器不显示
 * 线程上限 ≤ 1 直接返回（连 NBT 都不写）：那种机器（GTM 原版机器 + 没装线程仓的 GTET 多方块）
 * 没有线程概念，多几行只是噪音。
 *
 * ## 思路来源
 * - 【借鉴形状】GTM 7.5.3 的 `ParallelProvider` / `RecipeLogicProvider` / `WorkableBlockProvider`：
 *   借「`IBlockComponentProvider` + `IServerDataProvider` 双接口一个类」、「服务端写 NBT、客户端读 NBT」、
 *   以及 `IElementHelper#progress(...)` 画进度条的用法。
 * - 【自研】NBT 键名与「上限 / 在用 / 合计运行次数 / 合计 EU/t / 逐组明细（含每组 EU/t）+ 每组产物」
 *   这套字段取舍；以及产物**传物品栈**而不是名字/翻译键 —— 客户端因此同时拿到图标与正确的材料名
 *   （早期传组件 JSON 是为了绕开「材料物品的键是 `%s粉` 这种模板」的坑，传栈之后这个坑自然没了）。
 *
 * @author rain fox
 */
class ThreadedRecipeLogicProvider : IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun appendServerData(data: CompoundTag, accessor: BlockAccessor) {
        val logic = threadLogic(accessor) ?: return
        val limit = logic.threadLimit
        // 没装线程仓的机器不参与显示（理由见类 KDoc），连 NBT 都不写，客户端据此直接跳过
        if (limit <= 1) return

        data.putInt(NBT_LIMIT, limit)
        data.putInt(TAG_RUNNING, logic.runningThreadCount)
        data.putLong(TAG_TOTAL_RUNS, logic.runningTotalRuns)
        data.putLong(TAG_TOTAL_EUT, ThreadedRecipeStatus.totalEutPerTick(logic))

        val page = ThreadedRecipeStatus.groupSnapshots(logic, GROUP_LIMIT)
        data.putInt(TAG_GROUPS, page.totalGroups)

        val list = ListTag()
        for (group in page.groups) {
            val entry = CompoundTag()
            entry.putInt(TAG_SLOT, group.slot)
            entry.putInt(TAG_GROUP_THREADS, group.threadCount)
            entry.putLong(TAG_GROUP_EUT, group.eutPerTick)
            entry.putInt(TAG_PROGRESS, group.progress)
            entry.putInt(TAG_DURATION, group.duration)
            entry.putInt(TAG_HIDDEN_KINDS, group.hiddenKinds)
            val outputs = ListTag()
            for (output in group.outputs) {
                val outputTag = CompoundTag()
                // 产物物品（数量 1，只给客户端取**图标 + 名字**）+ 显示用的数量区间
                outputTag.put(TAG_OUT_ITEM, output.stack.copyWithCount(1).save(CompoundTag()))
                outputTag.putLong(TAG_OUT_MIN, output.min)
                outputTag.putLong(TAG_OUT_MAX, output.max)
                outputTag.putBoolean(TAG_OUT_CHANCED, output.chanced)
                outputs.add(outputTag)
            }
            entry.put(TAG_OUTPUTS, outputs)
            list.add(entry)
        }
        data.put(TAG_THREADS, list)
    }

    override fun appendTooltip(tooltip: ITooltip, accessor: BlockAccessor, config: IPluginConfig) {
        val data = accessor.serverData
        if (!data.contains(NBT_LIMIT)) return

        val limit = data.getInt(NBT_LIMIT)
        val running = data.getInt(TAG_RUNNING)
        tooltip.add(Component.translatable(ThreadedRecipeStatus.LANG_STATUS, limit, running))
        if (running <= 0) return

        tooltip.add(
            Component.translatable(
                ThreadedRecipeStatus.LANG_TOTAL_RUNS,
                FormattingUtil.formatNumbers(data.getLong(TAG_TOTAL_RUNS))
            )
        )
        tooltip.add(
            Component.translatable(
                ThreadedRecipeStatus.LANG_TOTAL_EUT,
                FormattingUtil.formatNumbers(data.getLong(TAG_TOTAL_EUT))
            )
        )

        val helper = tooltip.elementHelper
        val list = data.getList(TAG_THREADS, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            val progress = entry.getInt(TAG_PROGRESS)
            val duration = entry.getInt(TAG_DURATION)
            // 组行第一行 = 整条进度条：条内是「槽位 + 进度」，后面不接任何文字，这条进度条就独占这一行
            // （条内文字沿用 GTM 的白字，绿底绿字看不清）
            tooltip.add(
                helper.progress(
                    if (duration <= 0) 0f else (progress.toFloat() / duration).coerceIn(0f, 1f),
                    Component.translatable(
                        ThreadedRecipeStatus.LANG_PROGRESS,
                        entry.getInt(TAG_SLOT),
                        progress,
                        duration
                    ),
                    helper.progressStyle().color(PROGRESS_BAR_ARGB).textColor(-1),
                    BoxStyle.DEFAULT,
                    true
                )
            )
            // 组行第二行 = 产物（**图标 + 名字 + 数量**，照 GTM `RecipeOutputProvider` / GTO 的画法）+ 线程条数 + 该组 EU/t
            appendOutputs(tooltip, entry)
        }

        // 明细只写到 GROUP_LIMIT 条组，多出来的组用一行交代过去（载荷大小见 GROUP_LIMIT 的说明）
        val groups = data.getInt(TAG_GROUPS)
        if (groups > list.size) {
            tooltip.add(Component.translatable(ThreadedRecipeStatus.LANG_MORE, groups - list.size, list.size))
        }
    }

    /**
     * 把一组的产物画成「图标 + 名字 + 数量」的一行。
     *
     * 画法照 GTM 的 `RecipeOutputProvider`（GTO 的多线程版也是这么扩展的）：`add(helper.smallItem(stack))`
     * 起一行、`append(...)` 把后续内容续在**同一行**。
     *
     * ⚠️ 只有**第一件**产物用 `add`（它开的那一行就是进度条下面那行），之后图标与文字全部用 `append` ——
     * 否则一件产物一行，6 组 × 4 种就是 24 行，悬浮框会盖住半个屏幕（行数上限的理由见 [GROUP_LIMIT]）。
     */
    private fun appendOutputs(tooltip: ITooltip, entry: CompoundTag) {
        val helper = tooltip.elementHelper
        val outputs = readOutputs(entry)
        val green = ChatFormatting.GREEN

        if (outputs.isEmpty()) {
            tooltip.add(Component.translatable(ThreadedRecipeStatus.LANG_NO_OUTPUT).withStyle(green))
        }
        outputs.forEachIndexed { index, output ->
            val icon = helper.smallItem(output.stack)
            if (index == 0) tooltip.add(icon) else tooltip.append(icon)
            val text = Component.literal(" ")
            if (index > 0) text.append(Component.translatable(ThreadedRecipeStatus.LANG_OUTPUT_SEP))
            text.append(output.name).append(" ").append(ThreadedRecipeStatus.countText(output))
            tooltip.append(text.withStyle(green))
        }
        val hidden = entry.getInt(TAG_HIDDEN_KINDS)
        if (hidden > 0) {
            tooltip.append(Component.translatable(ThreadedRecipeStatus.LANG_OUTPUT_MORE, hidden).withStyle(green))
        }
        tooltip.append(
            Component.translatable(
                ThreadedRecipeStatus.LANG_GROUP_TAIL,
                entry.getInt(TAG_GROUP_THREADS),
                FormattingUtil.formatNumbers(entry.getLong(TAG_GROUP_EUT))
            ).withStyle(green)
        )
    }

    /** 客户端按 NBT 重建产物条目（NBT 里存的就是物品栈，名字由客户端自己解析）。 */
    private fun readOutputs(entry: CompoundTag): List<ThreadedRecipeStatus.OutputSnapshot> {
        val list = entry.getList(TAG_OUTPUTS, Tag.TAG_COMPOUND.toInt())
        if (list.isEmpty()) return emptyList()
        val outputs = ArrayList<ThreadedRecipeStatus.OutputSnapshot>(list.size)
        for (i in 0 until list.size) {
            val output = list.getCompound(i)
            // 物品没了（整合包换过料）就跳过，别画个空图标
            val stack = ItemStack.of(output.getCompound(TAG_OUT_ITEM))
            if (stack.isEmpty) continue
            outputs.add(
                ThreadedRecipeStatus.OutputSnapshot(
                    stack,
                    output.getLong(TAG_OUT_MIN),
                    output.getLong(TAG_OUT_MAX),
                    output.getBoolean(TAG_OUT_CHANCED)
                )
            )
        }
        return outputs
    }

    override fun getUid(): ResourceLocation = Gtetcore.id(UID_PATH)

    /** 从被看的方块实体上取多线程配方逻辑；不是（或不是这台机器）就返回 null。 */
    private fun threadLogic(accessor: BlockAccessor): ThreadedRecipeLogic? {
        val machine = (accessor.blockEntity as? MetaMachineBlockEntity)?.metaMachine ?: return null
        return (machine as? IRecipeLogicMachine)?.recipeLogic as? ThreadedRecipeLogic
    }

    companion object {

        /**
         * provider 的 uid 路径（与 [Gtetcore.MODID] 一起构成 uid `gtetcore:threaded_recipe_logic`）。
         *
         * 抽成 `const` 不是洁癖：Jade 要求每个 provider 都有一条 `config.jade.plugin_<命名空间>.<uid 路径>`
         * 的翻译键，且**在 dev 环境缺键会用 `AssertionError` 把客户端崩掉**
         * （校验点在 `snownee.jade.JadeClient#onGui`，键名拼法与「哪些键必填」见 [GTETJadeLang] 的类注释）。
         * 键由 [GTETJadeLang] 用本常量拼出，`const` 是编译期内联，所以这样引用**不会**让
         * [GTETJadeLang] 反过来提前加载本类（本类带 Jade API 接口）。
         */
        const val UID_PATH: String = "threaded_recipe_logic"

        /**
         * 本 provider 写在 Jade `serverData` **根**上的线程数上限。
         *
         * 公开是为了让 `MixinWorkableProgressBar` 也能读到它 —— 服务端算出来的这个值比客户端现扫
         * `getParts()` 更可靠（客户端部件表万一还没同步到，现扫会拿到「没装线程仓」）。
         */
        const val NBT_LIMIT: String = "gtet_thread_limit"

        /**
         * 本 provider 的绿色进度条填充色（ARGB）。
         *
         * 取 `0xFF4CBB17` 而不是纯绿：它就是 GTM `WorkableBlockProvider` 里「机器在跑」那条进度条的绿，
         * 玩家看起来与别的 GT 机器是同一套观感；文字用 `ChatFormatting.GREEN`（`0xFF55FF55`），
         * 比条上那种深绿更亮、在 Jade 的深色背景上更清楚。
         */
        private val PROGRESS_BAR_ARGB: Int = 0xFF4CBB17.toInt()

        /**
         * 明细最多列几条**配方组**（合并后的一行算一条）。
         *
         * ⚠️ 用户硬性要求 ≤ 6：Jade 提示是跟着准星走的悬浮框，行数一多会盖住半个屏幕，而线程条数上限能到 256。
         * 6 行足够看出「多条线程各跑各的、产出各是多少」；不够的部分由 [ThreadedRecipeStatus.LANG_MORE] 一行交代。
         */
        private const val GROUP_LIMIT: Int = 6

        private const val TAG_RUNNING: String = "gtet_thread_running"
        private const val TAG_TOTAL_RUNS: String = "gtet_thread_total_runs"
        private const val TAG_TOTAL_EUT: String = "gtet_thread_total_eut"
        private const val TAG_GROUPS: String = "gtet_thread_groups"
        private const val TAG_THREADS: String = "gtet_thread_list"
        private const val TAG_SLOT: String = "slot"
        private const val TAG_GROUP_THREADS: String = "threads"
        private const val TAG_GROUP_EUT: String = "eut"
        private const val TAG_PROGRESS: String = "progress"
        private const val TAG_DURATION: String = "duration"
        private const val TAG_HIDDEN_KINDS: String = "hidden_kinds"
        private const val TAG_OUTPUTS: String = "outputs"
        private const val TAG_OUT_ITEM: String = "item"
        private const val TAG_OUT_MIN: String = "min"
        private const val TAG_OUT_MAX: String = "max"
        private const val TAG_OUT_CHANCED: String = "chanced"
    }
}
