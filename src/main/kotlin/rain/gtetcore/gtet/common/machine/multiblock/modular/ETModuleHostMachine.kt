package rain.gtetcore.gtet.common.machine.multiblock.modular

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 模块化多方块的**主机（核心）**：单元挂在它身上借电，并继承它的等级。
 *
 * 一句话机器逻辑：**主机只负责「供电」与「等级」两件事** —— 单元能不能跑那条配方由单元自己按
 * `主机等级 + 主机电量` 判（GTO 净化水工厂是核心代管进度，这里先把这两件事做成可复用底座）。
 *
 * @author rain fox
 */
abstract class ETModuleHostMachine(holder: IMachineBlockEntity) : WorkableElectricMultiblockMachine(holder) {

    private val modules = LinkedHashSet<ETModuleMachine>()

    /** 主机等级（成型时由能源仓算出）：**单元的配方等级以它为准**。 */
    open fun hostTier(): Int = tier

    /** 主机当前可用 EU —— 单元借电前先看这个值就知道够不够。 */
    fun availableEu(): Long = energyContainer?.energyStored ?: 0

    /**
     * 从主机的能源仓里扣 [amount]；返回**实际扣到**的量（不够就少扣，永远不会扣成负数）。
     *
     * 单元侧的统一入口是 [ETModuleMachine.consumeEu]（`ETPowerSource.HOST` 那条分支走这里）。
     */
    fun consumeHostEu(amount: Long): Long {
        if (amount <= 0) return 0
        val container = energyContainer ?: return 0
        return (-container.changeEnergy(-amount)).coerceAtLeast(0)
    }

    fun attachModule(module: ETModuleMachine) {
        if (modules.add(module)) onModulesChanged()
    }

    fun detachModule(module: ETModuleMachine) {
        if (modules.remove(module)) onModulesChanged()
    }

    /** 当前挂着的单元（只读）。 */
    fun modules(): Set<ETModuleMachine> = modules

    /**
     * 单元表变了：重判配方 + 唤醒 tick。
     *
     * ⚠️ 一定要 `updateTickSubscription()`：GTM 的配方逻辑没活干时**会自己退订 tick**，
     * 新挂上来的单元可能正躺在退订状态里，不唤醒它就永远不会开始跑。
     */
    protected open fun onModulesChanged() {
        recipeLogic.markLastRecipeDirty()
        recipeLogic.updateTickSubscription()
    }

    /**
     * 面板里显示「挂了几台单元 / 主机等级 / 现在有多少电」——
     * 单元那边显示的是「接没接上主机」，两边对着看就知道链路的哪一头断了。
     */
    override fun addDisplayText(textList: MutableList<Component>) {
        super.addDisplayText(textList)
        textList += Component.translatable(LANG_MODULES, modules.size, modules.count { it.isFormed })
        val vn = GTValues.VN
        textList += Component.translatable(LANG_HOST_TIER, vn[hostTier().coerceIn(0, vn.size - 1)])
        textList += Component.translatable(LANG_AVAILABLE_EU, FormattingUtil.formatNumbers(availableEu()))
    }

    override fun onStructureFormed() {
        super.onStructureFormed()
        ETModuleNetwork.addHost(this)
    }

    override fun onStructureInvalid() {
        ETModuleNetwork.removeHost(this)
        super.onStructureInvalid()
    }

    override fun onUnload() {
        ETModuleNetwork.removeHost(this)
        super.onUnload()
    }

    companion object {

        /** 「单元 N 台（已成型 M）」——参数是两个数量。 */
        const val LANG_MODULES: String = "gtetcore.machine.host.modules"

        /** 主机等级（参数 = 档位名）。 */
        const val LANG_HOST_TIER: String = "gtetcore.machine.host.tier"

        /** 主机可用电量（参数 = 已格式化的 EU）。 */
        const val LANG_AVAILABLE_EU: String = "gtetcore.machine.host.available_eu"

        /** 登记本基类的语言键；必须在数据生成之前调（见 `CommonProxy#kotlinInit`）。 */
        @JvmStatic
        fun initLang() {
            LangUtil.add(
                LANG_MODULES,
                "Modules %s (%s formed)",
                "单元 %s 台（已成型 %s）",
            )
            LangUtil.add(
                LANG_HOST_TIER,
                "Host tier %s (unit recipe tier follows it)",
                "主机等级 %s（单元的配方等级以它为准）",
            )
            LangUtil.add(
                LANG_AVAILABLE_EU,
                "Host buffer %s EU (units may draw from it)",
                "主机缓存 %s EU（单元可以从这里取电）",
            )
        }
    }
}
