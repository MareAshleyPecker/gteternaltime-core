package rain.gtetcore.gtet.common.machine.multiblock.modular

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
import com.gregtechceu.gtceu.api.pattern.BlockPattern
import com.gregtechceu.gtceu.api.recipe.ActionResult
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 「模块物品决定等级」的模块化多方块基类（写法照 GTO 的 PCB 工厂）。
 *
 * 机制：模块槽里的物品 → [moduleTier] → ① 换结构（[patternOfTier]）② 卡配方电压等级（[maxRecipeTier]）。
 * 子类只需要回答两件事：**什么物品算哪个等级**（登记进 [ETModuleTiers]，或覆写 [tierOfModule] 自己算）、
 * **每个等级长什么样**（[patternOfTier]）。
 *
 * ⚠️ 等级 0（没模块 / 模块不合法）时 [checkPattern] 直接不通过 —— 「等级不合法」自然表现为
 * 「结构不成型」，不需要另外写报错。
 *
 * @author rain fox
 */
abstract class ETModularMachine(holder: IMachineBlockEntity) : WorkableElectricMultiblockMachine(holder) {

    /** 模块槽：只收 1 个、不参与配方 IO；内容一变就重算等级并重检结构。 */
    val moduleSlot: NotifiableItemStackHandler = object : NotifiableItemStackHandler(this, 1, IO.NONE, IO.NONE) {
        override fun onContentsChanged() {
            super.onContentsChanged()
            onModuleChanged()
        }
    }

    /** 当前等级（0 = 无模块 / 不合法）。 */
    @Persisted
    @DescSynced
    var moduleTier: Int = 0
        private set

    /**
     * 模块物品 → 等级；返回 0 表示这个物品不是合法模块。
     *
     * 默认走全局规则表 [ETModuleTiers]（支持「物品 / 标签 / tagprefix」三种登记方式，
     * 查询顺序：**物品 → tagprefix → 标签**）。想完全自定义算法的子类照旧 `override` 本方法。
     */
    protected open fun tierOfModule(stack: ItemStack): Int = ETModuleTiers.tierOf(stack)
    /** 等级 → 结构图案。 */
    protected abstract fun patternOfTier(tier: Int): BlockPattern

    /** 该等级允许的配方电压等级上限（`GTValues` 里的档位）；返回 -1 表示不限。 */
    open fun maxRecipeTier(): Int = moduleTier - 1

    /** 换配方逻辑：等级不够的配方在这里被拦下（见 [ETModularRecipeLogic]）。 */
    override fun createRecipeLogic(vararg args: Any?): RecipeLogic = ETModularRecipeLogic(this)

    /** 等级 0 不成型（见类注释）。 */
    override fun checkPattern(): Boolean = moduleTier > 0 && super.checkPattern()

    override fun getPattern(): BlockPattern = patternOfTier(moduleTier)

    /** 模块槽内容变了：重算等级 → 重检结构 → 让配方重新判定。 */
    fun onModuleChanged() {
        val stack = moduleSlot.getStackInSlot(0)
        val newTier = if (stack.isEmpty) 0 else tierOfModule(stack)
        if (newTier == moduleTier) return
        moduleTier = newTier
        waitingTime = 0
        requestCheck()                          // 换结构：必须重检
        recipeLogic.markLastRecipeDirty()
        recipeLogic.updateTickSubscription()
    }

    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    companion object {

        /** 挂在 `WorkableElectricMultiblockMachine` 的字段持有者后面（本类有 `@Persisted` 字段，必须接）。 */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder =
            ManagedFieldHolder(ETModularMachine::class.java, WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER)

        /** 「配方等级超出当前模块」的语言键（参数 = 允许的最高档位名）。 */
        const val LANG_TIER_TOO_LOW: String = "gtetcore.machine.modular.tier_too_low"

        /** 登记本基类用到的语言键；必须在数据生成之前调用（见 `CommonProxy#kotlinInit`）。 */
        @JvmStatic
        fun initLang() {
            LangUtil.add(
                LANG_TIER_TOO_LOW,
                "Recipe voltage tier is above the current module (max: %s)",
                "配方的电压等级超出当前模块（上限：%s）",
            )
        }
    }
}

/**
 * 模块化多方块的配方逻辑：**等级不够的配方判为不可用**。
 *
 * 拦在 [matchRecipe]（内容匹配）这一层而不是 `doModifyRecipe`，原因有两条：
 * 1. ⚠️ GTM 的 `WorkableMultiblockMachine#doModifyRecipe` 是 **final**，多方块里根本覆盖不了；
 * 2. 在这里返回 [ActionResult.fail] 会带上原因文本，GTM 会把它写进 `failureReasons` ——
 *    **Jade 与机器面板都能看到「为什么这条配方不跑」**，比静默返回 `null` 好得多。
 */
class ETModularRecipeLogic(private val modular: ETModularMachine) : RecipeLogic(modular) {

    override fun matchRecipe(recipe: GTRecipe): ActionResult {
        val cap = modular.maxRecipeTier()
        if (cap >= 0 && RecipeHelper.getRecipeEUtTier(recipe) > cap) {
            val tierName = GTValues.VN[cap.coerceIn(0, GTValues.VN.size - 1)]
            return ActionResult.fail(Component.translatable(ETModularMachine.LANG_TIER_TOO_LOW, tierName), null, null)
        }
        return super.matchRecipe(recipe)
    }
}
