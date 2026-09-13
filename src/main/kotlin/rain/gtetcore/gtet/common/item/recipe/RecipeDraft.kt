package rain.gtetcore.gtet.common.item.recipe

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.api.registry.GTRegistries

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.items.ItemStackHandler

/**
 * 配方编辑器的工作区（草稿）—— 记录"当前这个配方填了什么"。
 *
 * 整份草稿存在手持物品的 NBT（键 [KEY]）里，跟结构工具存选区坐标是同一个套路：
 * 关掉界面再打开、乃至退出游戏都还在。
 *
 * ⚠️ 别指望 LDLib 帮着同步：`HeldItemUIFactory.HeldItemHolder.markAsDirty()` 是个**空实现**，
 * 而界面是客户端与服务端**各建一份**的（服务端 `UIFactory.openUI` 建好再把控件树发过去，
 * 客户端 `initClientUI` 会自己再跑一遍 `createUI`），两边各持一份草稿、各写各的物品 NBT。
 * 保持一致靠的是"同一个动作在两边都执行"：按钮回调两边都会跑（客户端点击时直接调、
 * 服务端收到 client action 后再调一次），幽灵槽的改动走 client action 到服务端、
 * 再由控件自己的 update info 同步回客户端显示。所以改草稿的代码必须两边都能跑，
 * 别只改一边、也别在构造时把状态抄进控件里。
 *
 * 槽位用 Forge 的 [ItemStackHandler]（自带 NBT 序列化）+ 【自研】的 [DraftFluidTanks]
 * （多槽幽灵流体罐），界面侧分别交给 GTCEu 的 `PhantomSlotWidget` / `PhantomFluidWidget`。
 * 界面固定只建 [MAX_INPUTS] + [MAX_OUTPUTS] + [MAX_FLUID_INPUTS] + [MAX_FLUID_OUTPUTS]
 * 这一片网格，**这次实际用几个由配方类型的真实能力决定**（见 [inputSlots] 等四个方法），
 * 代码生成时只读非空槽。
 *
 * @author rain fox
 */
class RecipeDraft {

    /**
     * 支持的配方种类：原版五类（工作台 / 熔炉 + 两种变种 / 切石机 / 锻造台），
     * 外加一个 [GT]（具体用哪个 GT 配方类型看 [gtType]）。
     */
    enum class Kind(val cn: String, val en: String, val inputs: Int, val outputs: Int) {
        CRAFTING_SHAPED("工作台（有序）", "Crafting (shaped)", 9, 1),
        CRAFTING_SHAPELESS("工作台（无序）", "Crafting (shapeless)", 9, 1),
        SMELTING("熔炉", "Furnace", 1, 1),
        BLASTING("高炉", "Blast furnace", 1, 1),
        SMOKING("烟熏炉", "Smoker", 1, 1),
        STONECUTTING("切石机", "Stonecutter", 1, 1),
        SMITHING("锻造台", "Smithing table", 3, 1),
        GT("GT 配方", "GT recipe", MAX_INPUTS, MAX_OUTPUTS),
    }

    // ======================== 字段 ========================

    /** 配方种类。 */
    var kind: Kind = Kind.SMELTING

    /** [Kind.GT] 专用：GT 配方类型 id，例如 `gtceu:assembler`。 */
    var gtType: String = "gtceu:assembler"

    /** 配方 id（命名空间后面的那一段；留空会自动拼一个）。 */
    var recipeId: String = ""

    /** 配方时间（tick）。 */
    var duration: Int = 200

    /** 基础耗电 EU/t。 */
    var eut: Long = 30L

    /**
     * 要求电压等级：`0..MAX` 是 GTM 档（下标含义同 `GTValues.VN`，0=ULV、1=LV … 14=MAX），
     * `MAX+1` 之后是 [VoltageTiers] 那 16 个特殊档（15=MAX+1 … 30=MAX+16）。
     *
     * 合法范围由 setter 统一夹紧（[VoltageTiers.coerce]）：存档里的脏值、界面上的越界点击
     * 都进不来，[RecipeCodeWriter] 那边就不会去索引 `VA[MAX+1]` 这种不存在的常量。
     */
    var tier: Int = 1
        set(value) {
            field = VoltageTiers.coerce(value)
        }

    /** 幽灵电路配置号；`-1` 表示这个配方不用电路。 */
    var circuit: Int = -1

    /**
     * 流体槽的"放进来的量"（mB）。
     *
     * 拖进来的流体自带多少就是多少（通常是一桶 1000，JEI 里也多是 1000），
     * 而 GT 配方里的量经常是 144 / 576 / 2000 这种，所以界面给一个字段：
     * 往槽里放流体时按这个值覆盖。已经放好的槽不会跟着变（要改就重新放一次）。
     */
    var fluidAmount: Int = 1000

    val inputs = ItemStackHandler(MAX_INPUTS)
    val outputs = ItemStackHandler(MAX_OUTPUTS)

    /** 幽灵流体输入罐（多槽，`PhantomFluidWidget` 直接绑它）。 */
    val fluidInputs = DraftFluidTanks(MAX_FLUID_INPUTS)

    /** 幽灵流体输出罐。 */
    val fluidOutputs = DraftFluidTanks(MAX_FLUID_OUTPUTS)

    /** 幽灵电路的显示槽（界面里显示当前电路，并在滚动时同步）。 */
    val circuitSlot = ItemStackHandler(1)

    // ======================== 便捷读取 ========================

    /**
     * 第 [i] 个输入（越界或空槽返回空栈）。
     *
     * 上界同时受 [MAX_INPUTS] 和槽位表**当前实际容量**限制：
     * 槽位容量理论上只在构造时定，但 `ItemStackHandler` 反序列化有可能把它改小
     * （见 [restore]），多写一道判断，任何情况下都不会再抛
     * `Slot N not in valid range`。
     */
    fun input(i: Int): ItemStack =
        if (i in 0 until minOf(MAX_INPUTS, inputs.slots)) inputs.getStackInSlot(i) else ItemStack.EMPTY

    /** 第 [i] 个输出（越界或空槽返回空栈）。上界规则同 [input]。 */
    fun output(i: Int): ItemStack =
        if (i in 0 until minOf(MAX_OUTPUTS, outputs.slots)) outputs.getStackInSlot(i) else ItemStack.EMPTY

    /** 第一个非空输入（没有就返回空栈）。 */
    fun firstInput(): ItemStack = (0 until MAX_INPUTS).firstNotNullOfOrNull { input(it).takeIf { s -> !s.isEmpty } }
        ?: ItemStack.EMPTY

    /** 第一个非空输出（没有就返回空栈）。 */
    fun firstOutput(): ItemStack = (0 until MAX_OUTPUTS).firstNotNullOfOrNull { output(it).takeIf { s -> !s.isEmpty } }
        ?: ItemStack.EMPTY

    /**
     * 第 [i] 个流体输入（越界或空槽返回空栈）。
     *
     * [DraftFluidTanks] 自己保证越界不抛异常（它是"忽略存档容量"的实现，槽数只由构造时决定），
     * 这里再挡一道纯粹是为了防御：真被改坏了也只是读到空，不会在界面构建阶段炸掉。
     */
    fun fluidInput(i: Int): FluidStack =
        if (i in 0 until MAX_FLUID_INPUTS) fluidInputs.getFluid(i) else FluidStack.EMPTY

    /** 第 [i] 个流体输出（越界或空槽返回空栈）。上界规则同 [fluidInput]。 */
    fun fluidOutput(i: Int): FluidStack =
        if (i in 0 until MAX_FLUID_OUTPUTS) fluidOutputs.getFluid(i) else FluidStack.EMPTY

    // ======================== 按配方类型决定"实际用几个槽" ========================

    /** 当前配方类型对应的 GT 配方类型（不是 GT 种类、或者 id 写错了就返回 null）。 */
    fun gtRecipeType(): GTRecipeType? =
        if (kind != Kind.GT) null else ResourceLocation.tryParse(gtType)?.let { GTRegistries.RECIPE_TYPES.get(it) }

    /**
     * 实际使用的输入槽数量。
     *
     * GT 类型按它自己的能力上限算（`GTRecipeType.getMaxInputs(ItemRecipeCapability.CAP)`，
     * 例如组装机 9、装配线 16、燃烧发电机 0），原版种类用 [Kind] 里写死的数量。
     */
    fun inputSlots(): Int =
        if (gtDeclaresNothing()) 1 else slotCount(MAX_INPUTS, kind.inputs) { it.getMaxInputs(ItemRecipeCapability.CAP) }

    /** 实际使用的输出槽数量。 */
    fun outputSlots(): Int =
        if (gtDeclaresNothing()) 1 else slotCount(MAX_OUTPUTS, kind.outputs) { it.getMaxOutputs(ItemRecipeCapability.CAP) }

    /**
     * 实际使用的流体输入槽数量。
     *
     * 流体是**原版种类没有**的能力，所以原版一律 0（界面一个流体槽都不画）；
     * GT 类型同样按真实能力来（装配线 4、大型化学反应釜 5、研磨机之类没有就是 0）。
     */
    fun fluidInputSlots(): Int = fluidSlotCount(MAX_FLUID_INPUTS) { it.getMaxInputs(FluidRecipeCapability.CAP) }

    /** 实际使用的流体输出槽数量（离心机 / 电解机 6，蒸馏塔 12 会被夹到 [MAX_FLUID_OUTPUTS]）。 */
    fun fluidOutputSlots(): Int = fluidSlotCount(MAX_FLUID_OUTPUTS) { it.getMaxOutputs(FluidRecipeCapability.CAP) }

    /** 是不是 GT 种类、但类型一个槽都没声明（GT 自己注册的 `gtceu:dummy` 占位类型就是这样）。 */
    private fun gtDeclaresNothing(): Boolean = kind == Kind.GT && gtRecipeType()?.let { type ->
        type.getMaxInputs(ItemRecipeCapability.CAP) <= 0 && type.getMaxOutputs(ItemRecipeCapability.CAP) <= 0 &&
            type.getMaxInputs(FluidRecipeCapability.CAP) <= 0 && type.getMaxOutputs(FluidRecipeCapability.CAP) <= 0
    } == true

    /**
     * 把「这个类型要用几个槽」夹到 `[0, cap]`。
     *
     * `cap` 必须是**对应的**那个上限：物品输入夹到 [MAX_INPUTS]、物品输出夹到 [MAX_OUTPUTS]、
     * 流体各自夹到 [MAX_FLUID_INPUTS] / [MAX_FLUID_OUTPUTS]。
     * 之前两边都夹到 [MAX_INPUTS]（16），万一某个 GT 类型声明的物品输出超过 [MAX_OUTPUTS]（9），
     * 界面就会给 9 格的输出表建第 10 个幽灵槽 → 又在 `writeInitialData` 里抛
     * `Slot N not in valid range` 把整个界面炸掉。
     *
     * 下界是 0 而不是 1：GT 里真有"零物品输入"的类型（燃烧发电机 / 燃气轮机只有流体输入），
     * 硬给 1 个空物品槽属于凭空捏造能力。原版种类不受影响 —— 它们的数量写死在 [Kind] 里，都 ≥ 1。
     */
    private inline fun slotCount(cap: Int, vanilla: Int, gt: (GTRecipeType) -> Int): Int =
        (if (kind == Kind.GT) gtRecipeType()?.let(gt) ?: vanilla else vanilla).coerceIn(0, cap)

    /**
     * 流体槽数量：原版种类恒为 0，GT 类型按能力算，`cap` 同样是各自的上限。
     *
     * 和 [slotCount] 分开写是因为"没有流体能力"（0）和"类型查不到"（也返回 0）**都是合法结果**，
     * 不像物品那样有"类型 id 写错了就退回满容量"的兜底 —— 那会凭空给研磨机之类的机器
     * 画一排永远用不上的流体槽。
     */
    private inline fun fluidSlotCount(cap: Int, gt: (GTRecipeType) -> Int): Int =
        (if (kind == Kind.GT) gtRecipeType()?.let(gt) ?: 0 else 0).coerceIn(0, cap)

    // ======================== NBT ========================

    /** 把草稿写回手持物品。 */
    fun save(stack: ItemStack) {
        val tag = stack.getOrCreateTagElement(KEY)
        tag.putString("kind", kind.name)
        tag.putString("gtType", gtType)
        tag.putString("recipeId", recipeId)
        tag.putInt("duration", duration)
        tag.putLong("eut", eut)
        tag.putInt("tier", tier)
        tag.putInt("circuit", circuit)
        tag.putInt("fluidAmount", fluidAmount)
        tag.put("inputs", inputs.serializeNBT())
        tag.put("outputs", outputs.serializeNBT())
        tag.put("fluidInputs", fluidInputs.serializeNBT())
        tag.put("fluidOutputs", fluidOutputs.serializeNBT())
    }

    companion object {

        /** 手持物品里存草稿的 NBT 键。 */
        const val KEY: String = "recipe_editor"

        const val MAX_INPUTS: Int = 64
        const val MAX_OUTPUTS: Int = 32

        /**
         * 幽灵流体槽的容量上限。
         *
         * 为什么是 8：
         *  - GT 7.5.3 全部 57 个配方类型里，流体输入最多的是大型化学反应釜 5 个、
         *    流体输出最多的是离心机 / 电解机 6 个（蒸馏塔 12 个，见下），装配线是 4 个 —
         *    8 个足够放下"装配线 4 个"这类需求，还留了余量给附属 mod 的类型；
         *  - 界面高度有限：流体槽按 8 个一行、行距 20 排，一行 8 个正好卡在
         *    "最坏情况（16 物品输入 + 8 流体输入 + 9 物品输出 + 8 流体输出）也不会压到
         *    底部按钮"的上限（详细数字见 RecipeEditorBehavior 的布局注释）。
         *    再往上加大（比如按蒸馏塔的 12 个给两行）就会顶到按钮那一行。
         *
         * ⚠️ 所以蒸馏塔那 12 个流体输出在后 4 个上是被**夹掉**的 —— 和物品这边
         * （上限 16 / 9）一样的处理方式：界面放不下就不画，草稿里多出来的槽也不会被写进代码。
         * 真要做 12 输出的蒸馏塔配方时，在导出代码里手补两行 `.outputFluids(...)` 即可。
         */
        const val MAX_FLUID_INPUTS: Int = 16
        const val MAX_FLUID_OUTPUTS: Int = 16

        /** GT 幽灵电路的最大编号（与 GTCEu `IntCircuitBehaviour.CIRCUIT_MAX` 一致）。 */
        const val CIRCUIT_MAX: Int = 32

        /** 从手持物品读草稿；没有就返回一份默认草稿。 */
        @JvmStatic
        fun load(stack: ItemStack): RecipeDraft {
            val draft = RecipeDraft()
            val root: CompoundTag = stack.tag ?: return draft
            if (!root.contains(KEY, 10)) return draft

            val tag = root.getCompound(KEY)
            if (tag.contains("kind")) {
                draft.kind = runCatching { Kind.valueOf(tag.getString("kind")) }.getOrDefault(Kind.SMELTING)
            }
            if (tag.contains("gtType")) draft.gtType = tag.getString("gtType")
            if (tag.contains("recipeId")) draft.recipeId = tag.getString("recipeId")
            if (tag.contains("duration")) draft.duration = tag.getInt("duration")
            if (tag.contains("eut")) draft.eut = tag.getLong("eut")
            if (tag.contains("tier")) draft.tier = tag.getInt("tier")
            draft.circuit = if (tag.contains("circuit")) tag.getInt("circuit") else -1
            // 旧草稿没有这个键 → 保持默认 1000；读到的脏值（0 / 负数）也夹回至少 1 mB，
            // 免得"流体量"填了 0 之后怎么拖都是空罐子。
            if (tag.contains("fluidAmount")) draft.fluidAmount = maxOf(1, tag.getInt("fluidAmount"))
            if (tag.contains("inputs")) restore(draft.inputs, tag.getCompound("inputs"))
            if (tag.contains("outputs")) restore(draft.outputs, tag.getCompound("outputs"))
            // 流体罐自己就是"忽略存档容量"的实现（见 DraftFluidTanks.deserializeNBT），
            // 不像 ItemStackHandler 那样需要先剥掉 Size 键，所以直接交给它读。
            if (tag.contains("fluidInputs", 10)) draft.fluidInputs.deserializeNBT(tag.getCompound("fluidInputs"))
            if (tag.contains("fluidOutputs", 10)) draft.fluidOutputs.deserializeNBT(tag.getCompound("fluidOutputs"))
            return draft
        }

        /**
         * 读槽位表 —— **必须先删掉 NBT 里的 `Size` 键再交给 Forge**。
         *
         * Forge 的 `ItemStackHandler#deserializeNBT` 第一件事就是
         * `setSize(nbt.contains("Size") ? nbt.getInt("Size") : 当前容量)`，
         * 也就是拿**存档里记的旧容量覆盖当前容量**；`setSize` 又会重建整个槽位数组。
         *
         * 旧版本草稿的输出槽只有 4 格（当年配 2×2 合成），于是：
         * 手上那把配方编辑器读出来 `outputs` 只剩 4 格 → [firstOutput] 仍按
         * [MAX_OUTPUTS] = 9 扫 → 前 4 格都空时读到第 5 格 → 抛
         * `RuntimeException: Slot 4 not in valid range - [0,4)`。
         * 而这个异常是在界面构建阶段（`LabelWidget.writeInitialData`）里抛的，
         * 会顺着 `UIFactory.openUI` 一路炸出去 → **整个界面都发不出来**，
         * 表现就是"配方编辑器怎么点都打不开"。
         *
         * 删掉 `Size` 后 Forge 会退回用「当前容量」（构造时给的 16 / 9）来装载，
         * 旧草稿里已有的物品按槽位号原样读回，不会丢；
         * 之后任何一次 [save] 都会写回正确容量，NBT 也就此修好。
         *
         * 另外这里用 `copy()` 再删 —— 直接改会污染物品上的原始 NBT。
         */
        private fun restore(handler: ItemStackHandler, raw: CompoundTag) {
            handler.deserializeNBT(raw.copy().also { it.remove("Size") })
        }
    }
}
