package rain.gtetcore.gtet.common.machine.multiblock.modular

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.TickableSubscription
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import com.gregtechceu.gtceu.api.recipe.ActionResult
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import com.lowdragmc.lowdraglib.gui.util.ClickData
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.util.lang.LangUtil

/** 单元从哪取电。 */
enum class ETPowerSource {
    /** 自己的能源仓。 */
    SELF,

    /** 主机的能源仓（GTO 净化水单元的写法：单元不自己耗电，主机统一供电）。 */
    HOST,
}

/**
 * 模块化多方块的**单元（子机）**：认一台主机，可以从自己的或主机的能源仓取电。
 *
 * 一句话机器逻辑：**附近 [HOST_RANGE] 格内有成型的主机 + 配方等级不超过主机 + 付得起每 tick 的电 → 才跑这条配方。**
 *
 * 等级分工（按需求定死）：
 * - **配方等级以主机算** —— [recipeTier] 决定「这条配方配不配在本单元跑」；
 * - **处理等级以自己算** —— [processingTier] 是本单元自己结构的电压档位（超频 / 并行按它走）。
 *
 * 供电预留了两个入口：`consumeEu(...)` 走 [defaultPowerSource]，也可以显式指定
 * `ETPowerSource.SELF` / `ETPowerSource.HOST` 单次切换。**扣电请在 `onWorking()`（每工作 tick 一次）里调**，
 * 不要放在 [recipeRequirement] / `matchRecipe` 里 —— 那一层是**模拟**匹配，一 tick 会被问好几次。
 *
 * @author rain fox
 */
abstract class ETModuleMachine(holder: IMachineBlockEntity) : WorkableElectricMultiblockMachine(holder) {

    /** 对接上的主机（服务端瞬态；重进世界后由 [onModuleTick] 的周期重查重新接上）。 */
    var host: ETModuleHostMachine? = null
        private set

    /** 默认从哪取电；自己带能源仓的单元可以覆写成 [ETPowerSource.SELF]。 */
    protected open val defaultPowerSource: ETPowerSource = ETPowerSource.HOST

    private var tickSub: TickableSubscription? = null
    private var tickCounter = 0

    /** 处理等级：**以自己算**。 */
    open fun processingTier(): Int = tier

    /** 配方等级：**以主机算**（没主机时退化成自己，方便单独调试）。 */
    open fun recipeTier(): Int = host?.hostTier() ?: processingTier()

    /** 本单元每 tick 要的电（EU/t）；子类按自己的配方与倍率折算。 */
    protected open fun euPerTick(): Long = 0

    /**
     * **一句话机器逻辑**：跑这条配方需要什么条件。
     *
     * 返回 `null` = 条件都满足；返回原因 = 不开工，并把这句话显示给玩家（`failureReasons` → Jade / 面板）。
     * 子类覆写时**先调 `super`**（主机那一条是所有单元共有的），再追加自己的条件。
     */
    open fun recipeRequirement(): Component? = when {
        host == null -> Component.translatable(LANG_NO_HOST, HOST_RANGE)
        else -> null
    }

    /**
     * 扣电：返回**实际扣到**的量（不够就是不够，调用方按返回值决定开工 / 停机）。
     *
     * @param amount 要扣多少（EU）；默认取 [euPerTick]
     * @param source 从哪扣；默认 [defaultPowerSource]
     */
    fun consumeEu(amount: Long = euPerTick(), source: ETPowerSource = defaultPowerSource): Long {
        if (amount <= 0) return 0
        return when (source) {
            ETPowerSource.SELF -> (-(energyContainer?.changeEnergy(-amount) ?: 0L)).coerceAtLeast(0)
            ETPowerSource.HOST -> host?.consumeHostEu(amount) ?: 0
        }
    }

    /** 主机现在有多少电（借电前先看一眼，避免「扣不到再回滚」）。 */
    fun hostAvailableEu(): Long = host?.availableEu() ?: 0

    /**
     * 面板里显示对接状态与等级分工，并在末尾挂一个**可点的「重新对接」**（排查时不用等 80 tick）。
     *
     * 一律读服务端那份数据（`ComponentPanelWidget` 的 textSupplier 只在服务端求值后同步）。
     */
    override fun addDisplayText(textList: MutableList<Component>) {
        super.addDisplayText(textList)
        val linked = host
        if (linked == null) {
            textList += Component.translatable(LANG_NO_HOST, HOST_RANGE)
        } else {
            val distance = Math.sqrt(linked.pos.distSqr(pos).toDouble()).toInt()
            textList += Component.translatable(LANG_HOST_LINKED, distance)
        }

        val vn = GTValues.VN
        val processName = vn[processingTier().coerceIn(0, vn.size - 1)]
        val recipeName = vn[recipeTier().coerceIn(0, vn.size - 1)]
        textList += Component.translatable(LANG_TIER_SPLIT, processName, recipeName)

        val button = Component.translatable(LANG_RECHECK)
        button.append(" ").append(ComponentPanelWidget.withButton(Component.literal("[↻]"), CLICK_RECHECK))
        textList += button
    }

    /** 点「重新对接」：服务端重找一次主机（客户端那次点击直接丢掉）。 */
    override fun handleDisplayClick(componentData: String, clickData: ClickData) {
        if (clickData.isRemote) return
        if (componentData == CLICK_RECHECK) recheckHost()
    }

    override fun createRecipeLogic(vararg args: Any?): RecipeLogic = ETModuleRecipeLogic(this)

    override fun onStructureFormed() {
        super.onStructureFormed()
        recheckHost()
        tickSub = subscribeServerTick(tickSub) { onModuleTick() }
    }

    override fun onStructureInvalid() {
        tickSub?.unsubscribe()
        tickSub = null
        unbindHost()
        super.onStructureInvalid()
    }

    /** ⚠️ 卸载同样要解绑，否则主机的单元表里会留悬空引用。 */
    override fun onUnload() {
        tickSub?.unsubscribe()
        tickSub = null
        unbindHost()
        super.onUnload()
    }

    /** 每 [RECHECK_INTERVAL] tick 重找一次主机：主机后成型、被拆、区块重载都能自己接回来。 */
    private fun onModuleTick() {
        if (isRemote) return
        if (++tickCounter < RECHECK_INTERVAL) return
        tickCounter = 0
        recheckHost()
    }

    /** 找一台主机接上；已经接着且主机还在成型状态就不动。 */
    fun recheckHost() {
        if (isRemote) return
        if (host?.isFormed == true) return
        bindHost(ETModuleNetwork.findHost(level ?: return, pos, HOST_RANGE))
    }

    private fun bindHost(newHost: ETModuleHostMachine?) {
        if (host === newHost) return
        host?.detachModule(this)
        host = newHost
        newHost?.attachModule(this)
        if (newHost != null) {
            waitingTime = 0
            recipeLogic.markLastRecipeDirty()
            recipeLogic.updateTickSubscription()
        }
    }

    private fun unbindHost() {
        if (host === null) return
        host?.detachModule(this)
        host = null
        recipeLogic.markLastRecipeDirty()
    }

    companion object {

        /** 主机的最远对接距离（格）。 */
        const val HOST_RANGE: Int = 32

        /** 重找主机的间隔（tick）。 */
        private const val RECHECK_INTERVAL: Int = 80

        /** 「附近没有主机」的原因键（参数 = 距离）。 */
        const val LANG_NO_HOST: String = "gtetcore.machine.module.no_host"

        /** 「配方等级高于主机」的原因键（参数 = 主机等级名）。 */
        const val LANG_TIER_TOO_LOW: String = "gtetcore.machine.module.tier_too_low"

        /** 已对接主机（参数 = 距离，格）。 */
        const val LANG_HOST_LINKED: String = "gtetcore.machine.module.host_linked"

        /** 等级分工（参数 = 处理等级名、配方等级名）。 */
        const val LANG_TIER_SPLIT: String = "gtetcore.machine.module.tier_split"

        /** 「重新对接」按钮前面的说明文字。 */
        const val LANG_RECHECK: String = "gtetcore.machine.module.recheck"

        /** 面板按钮的 click data（[handleDisplayClick] 按它分流）。 */
        private const val CLICK_RECHECK: String = "gtet_recheck_host"

        /** 登记本基类的语言键；必须在数据生成之前调（见 `CommonProxy#kotlinInit`）。 */
        @JvmStatic
        fun initLang() {
            LangUtil.add(
                LANG_NO_HOST,
                "No module host within %s blocks",
                "附近 %s 格内没有模块主机",
            )
            LangUtil.add(
                LANG_TIER_TOO_LOW,
                "Recipe voltage tier is above the host (%s)",
                "配方的电压等级高于主机（%s）",
            )
            LangUtil.add(
                LANG_HOST_LINKED,
                "Module host linked (%s blocks)",
                "已对接模块主机（距离 %s 格）",
            )
            LangUtil.add(
                LANG_TIER_SPLIT,
                "Processing tier %s (own) · Recipe tier %s (host)",
                "处理等级 %s（以自己算）· 配方等级 %s（以主机算）",
            )
            LangUtil.add(LANG_RECHECK, "Relink host", "重新对接主机")
        }
    }
}

/**
 * 单元的配方逻辑：把 [ETModuleMachine.recipeRequirement]（一句话机器逻辑）与主机等级当作**内容匹配**的一部分。
 *
 * 在 `matchRecipe` 这一层拦下（而不是 `doModifyRecipe`：多方块里它是 `final`），失败会带原因文本，
 * GTM 会写进 `failureReasons` —— **Jade 与机器面板都能看到「为什么这条配方不跑」**。
 */
class ETModuleRecipeLogic(private val module: ETModuleMachine) : RecipeLogic(module) {

    override fun matchRecipe(recipe: GTRecipe): ActionResult {
        module.recipeRequirement()?.let { return ActionResult.fail(it, null, null) }
        val cap = module.recipeTier()
        if (RecipeHelper.getRecipeEUtTier(recipe) > cap) {
            val tierName = GTValues.VN[cap.coerceIn(0, GTValues.VN.size - 1)]
            return ActionResult.fail(Component.translatable(ETModuleMachine.LANG_TIER_TOO_LOW, tierName), null, null)
        }
        return super.matchRecipe(recipe)
    }
}
