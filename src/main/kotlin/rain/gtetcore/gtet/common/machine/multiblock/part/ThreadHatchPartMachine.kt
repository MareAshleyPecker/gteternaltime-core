package rain.gtetcore.gtet.common.machine.multiblock.part

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import net.minecraft.util.Mth
import rain.gtetcore.gtet.api.capability.IThreadHatch

/**
 * 「线程仓」多方块部件。
 *
 * 方块本体就是一个分级 part machine：
 * - 实现 [IFancyUIMachine] 给一个带 `IntInputWidget` 的配置面板；
 * - 实现 [IThreadHatch] 让多线程配方逻辑认出它；
 * - `canShared() = false`，禁止多方块部件共享。
 *
 * 真正「开线程 / 每线程计时 / 每线程结算」的是
 * [rain.gtetcore.gtet.common.machine.ThreadedRecipeLogic]：它从控制器上找到本部件，
 * 用 [threadCount] 当线程数上限。
 *
 * ## 线程数怎么给
 * [maxThreadsForTier] 就是 `1 shl (tier - GTValues.LuV)`：上限**由 tier 直接推出来**，
 * 既不写死在变体表里、也不做成配置项 —— 从 LuV 起每级翻倍，于是
 * UV=4、UHV=8、UEV=16、UIV=32、UXV=64、OpV=128、MAX=256，
 * 语义是「比上一级多一倍同时跑得动的配方种类」。
 * 低于 LuV 的档位不注册，函数里兜底返回 1（不能是 0，否则线程逻辑会一条也开不出来）。
 *
 * ## 玩家可下调
 * [currentThread] 是 `@Persisted` 字段（所以 UI 改了能存进 NBT），
 * 玩家只能往下调（1 ~ [maxThreads]）。下调**不会**杀掉已经在跑的线程
 * （否则扣掉的料会凭空消失），只是「不再为多出来的槽位开新线程」，见 `ThreadedRecipeLogic#ensureSlots`。
 *
 * ## 思路来源
 * - 【借鉴形状】GTOCore（`D:\java\GTOCore`）`com.gtolib.api.machine.impl.part.ThreadPartMachine` / `AmountConfigurationPartMachine` —— 借「分级部件 + 一个 `min`/`max`/`current` 三元配置 + `createUIWidget()` 里放数值输入 + `canShared() = false`」这个形状（这两个类只能用 `javap` 看到字段与签名：`protected final long min`、`private final long max`、`protected long current`、`native createUIWidget()`、`native canShared()` —— 方法体在加密 native 里，一行都拿不到）；GTET 侧把它改成 `IntInput` 版本，并把「当前值 / 上限」拆成两个语义（GTO 只有一个 `getCurrentThread()`）。
 * - 【自研】「下调线程数不砍已开线程」的取舍 —— 并行仓改并行数只是改个数字，没有「已经吃掉的料」这回事；线程仓一旦开线程就已经扣过料，砍线程等于吞材料，所以这里只封住「新线程」而放已开线程跑完。
 * - 【自研】`currentThread` 与 `maxThreads` 分开存 —— 上限由 tier 算出来（不可改），当前值才是 `@Persisted` 的那一份；这样「玩家把 256 线程的仓调到 3」之后存档重载仍然记得，而不会被 tier 上限覆盖回去。
 *
 * @param holder 方块实体持有者
 * @param tier   电压等级（同时决定外壳贴图与线程数上限，见 `ETThreadHatches` 的变体表）
 *
 * @author rain fox
 */
class ThreadHatchPartMachine(
    holder: IMachineBlockEntity,
    tier: Int
) : TieredPartMachine(holder, tier), IFancyUIMachine, IThreadHatch {

    /** 该仓按 tier 提供的线程数上限，构造后不再变化，所以不需要 `@Persisted`。 */
    override val maxThreads: Int = maxThreadsForTier(tier)

    /**
     * 玩家在 UI 里设定的线程数（1 ~ [maxThreads]）。
     *
     * `@Persisted` 让它能进 NBT（ldlib 的 `@Persisted` 目标就是字段），
     * 所以拆掉再装、存档重载都不会丢设定。默认值 = 上限（装上去就是全开）。
     */
    @Persisted
    var currentThread: Int = maxThreads
        private set

    /** 配方逻辑读的是**当前生效**的线程数，不是上限。 */
    override val threadCount: Int get() = currentThread

    /**
     * 面板：方块名 + 线程数输入框 + 一行说明。
     *
     * 第 1、3 行传的是 **lang key**：ldlib 的 `LabelWidget` 在客户端用 `I18n` 解析，
     * 所以两种语言各显示各的。中间那行是 GTCEu 的 `IntInputWidget`
     * （`Supplier` 读值、`Consumer` 写值，`setMin`/`setMax` 卡范围）。
     */
    override fun createUIWidget(): Widget {
        val id = definition.name
        val group = WidgetGroup(0, 0, 150, 58)
        // 用 setColor(-1)（白字）而不是已弃用的 setTextColor —— 两者等价，后者只是 ldlib 的老 API
        group.addWidget(LabelWidget(5, 5, "block.gtetcore.$id").apply { setColor(-1) })
        // 位置走构造函数而不是 `setSelfPosition(...)`：后者返回 Unit，链式调用就接不回 Widget 了
        group.addWidget(
            IntInputWidget(5, 19, 100, 20, { currentThread }, { setThreadAmount(it) })
                .setMin(MIN_THREAD)
                .setMax(maxThreads)
        )
        group.addWidget(
            LabelWidget(5, 42, "gtetcore.machine.$id.config").apply { setColor(-1) }
        )
        return group
    }

    /**
     * 玩家改线程数。
     *
     * 夹到合法区间；值真的变了才去敲控制器，让它们的配方逻辑下一轮重新取数（[currentThread] 变了，
     * 之后新开的线程按新上限走；已在跑的线程不受影响）。
     */
    fun setThreadAmount(amount: Int) {
        val clamped = Mth.clamp(amount, MIN_THREAD, maxThreads)
        if (clamped == currentThread) return
        currentThread = clamped
        for (controller in controllers) {
            if (controller is IRecipeLogicMachine) {
                controller.recipeLogic.markLastRecipeDirty()
            }
        }
    }

    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    override fun canShared(): Boolean = false

    companion object {

        /** 线程数下限：至少 1 条线程（= 退化成 GTCEu 原版单配方机器）。 */
        const val MIN_THREAD: Int = 1

        /**
         * tier → 线程数上限：`1 shl (tier - GTValues.LuV)`。
         *
         * | tier | UV | UHV | UEV | UIV | UXV | OpV | MAX |
         * |---|---|---|---|---|---|---|---|
         * | 线程数 | 4 | 8 | 16 | 32 | 64 | 128 | 256 |
         *
         * 低于 LuV 的 tier 一律返回 [MIN_THREAD]（本 mod 不注册那些档，只是别返回 0 或负数）。
         */
        @JvmStatic
        fun maxThreadsForTier(tier: Int): Int {
            val shift = tier - GTValues.LuV
            return if (shift <= 0) MIN_THREAD else 1 shl shift
        }

        /**
         * 挂在 [MultiblockPartMachine] 的字段持有者后面，保证父类的
         * `controllerPositions`（`@DescSynced`）与本类的 `currentThread`（`@Persisted`）都串得起来。
         */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder = ManagedFieldHolder(
            ThreadHatchPartMachine::class.java,
            MultiblockPartMachine.MANAGED_FIELD_HOLDER
        )
    }
}
