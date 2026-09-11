package rain.gtetcore.gtet.integration.jade.provider

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.machine.ThreadedRecipeLogic
import rain.gtetcore.gtet.common.machine.ThreadedRecipeStatus
import snownee.jade.api.BlockAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.config.IPluginConfig

/**
 * GTET 自己的 Jade provider：把**多线程配方逻辑**的整机线程状态摆到提示里。
 *
 * 显示三件事（都能在同一台机器上同时看到，不再像 GTM 那样只报一条配方的数字）：
 * 1. `线程 256（在用 3）` —— 线程数上限与**正在跑几条**，这一行就是「线程真的开了」的直接证据；
 * 2. `同时处理 N 次配方运行` —— 整机口径，= Σ 各线程 `getTotalRuns()`
 *    （`parallels × subtickParallels × batchParallels`，见 [ThreadedRecipeLogic.runningTotalRuns]）；
 * 3. 逐线程明细（最多 [SAMPLE_LIMIT] 条）：`线程 #0：<配方 id>（45%）`。
 *
 * ## 为什么必须自己写一个 provider
 * GTM 的 `com.gregtechceu.gtceu.integration.jade.provider.ParallelProvider` 读的是
 * `recipeLogic.getLastRecipe()`，口径是**单个配方对象**的运行次数；而线程数（同时跑几种不同配方）
 * 是**机器级**的量，两者相乘才是整机吞吐 —— GTM 那个提示结构上装不下线程数，
 * 所以实机上装了 256 线程仓也只显示「3200 个配方」。本 provider 补的就是这一层。
 *
 * ## 服务端/客户端分工（Jade 的标准写法，照 GTM 的 provider 抄形状）
 * - [appendServerData] 在**服务端**跑：此时能读到真的线程表（它不是 `@DescSynced`，
 *   客户端拿到的是空表），把数字与最多 [SAMPLE_LIMIT] 条明细写进 NBT；
 * - [appendTooltip] 在**客户端**跑：只读 NBT 拼文本，所以这里**不碰**任何服务端对象。
 *
 * 两边的语言键都取自 [ThreadedRecipeStatus]（UI 与 Jade 共用同一批键，改文案只改一处）。
 *
 * ## 没有线程仓的机器不显示
 * `线程上限 ≤ 1` 直接返回：那种机器（所有 GTM 原版机器 + 没装线程仓的 GTET 多方块）
 * 根本没有线程概念，多一行只会是噪音。
 *
 * ## 思路来源
 * - 【借鉴形状】GTM 7.5.3 的 `ParallelProvider` / `RecipeLogicProvider`（都在
 *   `com.gregtechceu.gtceu.integration.jade.provider`，随 GTM 源码分发）：借「`IBlockComponentProvider` +
 *   `IServerDataProvider<BlockAccessor>` 双接口一个类」与「服务端写 NBT、客户端读 NBT」这套分工；
 *   它们只处理单个配方的数字，线程口径是本 provider 自己定的。
 * - 【自研】NBT 键名与「上限 / 在用 / 合计 / 明细」这四个字段的取舍，以及明细条数的上限
 *   （见 [SAMPLE_LIMIT] 的说明）。
 *
 * @author rain fox
 */
class ThreadedRecipeLogicProvider : IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun appendServerData(data: CompoundTag, accessor: BlockAccessor) {
        val logic = threadLogic(accessor) ?: return
        val limit = logic.threadLimit
        // 没装线程仓的机器不参与显示（理由见类 KDoc），连 NBT 都不写，客户端据此直接跳过
        if (limit <= 1) return

        data.putInt(TAG_LIMIT, limit)
        data.putInt(TAG_RUNNING, logic.runningThreadCount)
        data.putLong(TAG_TOTAL_RUNS, logic.runningTotalRuns)

        val list = ListTag()
        for ((slot, rec) in logic.runningThreadSlots.take(SAMPLE_LIMIT)) {
            val entry = CompoundTag()
            entry.putInt(TAG_SLOT, slot)
            entry.putString(TAG_RECIPE, rec.recipe.id?.toString() ?: "?")
            entry.putInt(TAG_PERCENT, rec.percent)
            list.add(entry)
        }
        data.put(TAG_THREADS, list)
    }

    override fun appendTooltip(tooltip: ITooltip, accessor: BlockAccessor, config: IPluginConfig) {
        val data = accessor.serverData
        if (!data.contains(TAG_LIMIT)) return

        val limit = data.getInt(TAG_LIMIT)
        val running = data.getInt(TAG_RUNNING)
        tooltip.add(Component.translatable(ThreadedRecipeStatus.LANG_STATUS, limit, running))
        if (running <= 0) return

        tooltip.add(
            Component.translatable(
                ThreadedRecipeStatus.LANG_TOTAL_RUNS,
                FormattingUtil.formatNumbers(data.getLong(TAG_TOTAL_RUNS))
            )
        )

        val list = data.getList(TAG_THREADS, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            tooltip.add(
                Component.translatable(
                    ThreadedRecipeStatus.LANG_LINE,
                    entry.getInt(TAG_SLOT),
                    entry.getString(TAG_RECIPE),
                    "${entry.getInt(TAG_PERCENT)}%"
                )
            )
        }
        if (running > list.size) {
            tooltip.add(
                Component.translatable(ThreadedRecipeStatus.LANG_MORE, running - list.size, list.size)
            )
        }
    }

    override fun getUid(): ResourceLocation = Gtetcore.id("threaded_recipe_logic")

    /** 从被看的方块实体上取多线程配方逻辑；不是（或不是这台机器）就返回 null。 */
    private fun threadLogic(accessor: BlockAccessor): ThreadedRecipeLogic? {
        val machine = (accessor.blockEntity as? MetaMachineBlockEntity)?.metaMachine ?: return null
        return (machine as? IRecipeLogicMachine)?.recipeLogic as? ThreadedRecipeLogic
    }

    companion object {

        /**
         * 明细最多列几条线程。
         *
         * Jade 提示是**跟着准星走**的悬浮框，行数一多会盖住半个屏幕；线程数上限能到 256，
         * 全列出来既没用又挡视线。取 6 —— 足够看出「好几条线程各跑各的配方、各自进度不同」
         * （这正是「线程生效」的观感证据），不够的部分由 [ThreadedRecipeStatus.LANG_MORE] 一行交代。
         */
        private const val SAMPLE_LIMIT: Int = 6

        private const val TAG_LIMIT: String = "gtet_thread_limit"
        private const val TAG_RUNNING: String = "gtet_thread_running"
        private const val TAG_TOTAL_RUNS: String = "gtet_thread_total_runs"
        private const val TAG_THREADS: String = "gtet_thread_list"
        private const val TAG_SLOT: String = "slot"
        private const val TAG_RECIPE: String = "recipe"
        private const val TAG_PERCENT: String = "percent"
    }
}
