package rain.gtetcore.gtet.common.item.recipe

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.api.registry.GTRegistries

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraftforge.items.ItemStackHandler

/**
 * 配方编辑器的工作区（草稿）—— 记录"当前这个配方填了什么"。
 *
 * 整份草稿存在手持物品的 NBT（键 [KEY]）里，跟结构工具存选区坐标是同一个套路：
 * 关掉界面再打开、乃至退出游戏都还在；服务端与客户端各持一份，由
 * `HeldItemUIFactory.HeldItemHolder.markAsDirty()` 同步。
 *
 * 槽位用 Forge 的 [ItemStackHandler]（自带 NBT 序列化），界面侧交给 GTCEu 的
 * `PhantomSlotWidget` 当幽灵槽用。所以界面永远只画 [MAX_INPUTS] + [MAX_OUTPUTS]
 * 这一片固定网格，**这次实际用几个由 [Kind] 决定**，代码生成时只读前 N 个。
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

    /** 要求电压等级：`GTValues.VN` 的下标（0=ULV，1=LV …）。 */
    var tier: Int = 1

    /** 幽灵电路配置号；`-1` 表示这个配方不用电路。 */
    var circuit: Int = -1

    val inputs = ItemStackHandler(MAX_INPUTS)
    val outputs = ItemStackHandler(MAX_OUTPUTS)

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

    // ======================== 按配方类型决定"实际用几个槽" ========================

    /** 当前配方类型对应的 GT 配方类型（不是 GT 种类、或者 id 写错了就返回 null）。 */
    fun gtRecipeType(): GTRecipeType? =
        if (kind != Kind.GT) null else ResourceLocation.tryParse(gtType)?.let { GTRegistries.RECIPE_TYPES.get(it) }

    /**
     * 实际使用的输入槽数量。
     *
     * GT 类型按它自己的能力上限算（`GTRecipeType.getMaxInputs(ItemRecipeCapability.CAP)`，
     * 例如组装机 9、装配线 16），原版种类用 [Kind] 里写死的数量。
     */
    fun inputSlots(): Int = slotCount(MAX_INPUTS, kind.inputs) { type -> type.getMaxInputs(ItemRecipeCapability.CAP) }

    /** 实际使用的输出槽数量。 */
    fun outputSlots(): Int = slotCount(MAX_OUTPUTS, kind.outputs) { type -> type.getMaxOutputs(ItemRecipeCapability.CAP) }

    /**
     * 把「这个类型要用几个槽」夹到 [1, cap]。
     *
     * `cap` 必须是**对应的**那个上限：输入夹到 [MAX_INPUTS]、输出夹到 [MAX_OUTPUTS]。
     * 之前两边都夹到 [MAX_INPUTS]（16），万一某个 GT 类型声明的物品输出超过 [MAX_OUTPUTS]（9），
     * 界面就会给 9 格的输出表建第 10 个幽灵槽 → 又在 `writeInitialData` 里抛
     * `Slot N not in valid range` 把整个界面炸掉。
     */
    private inline fun slotCount(cap: Int, vanilla: Int, gt: (GTRecipeType) -> Int): Int =
        (if (kind == Kind.GT) gtRecipeType()?.let(gt) ?: vanilla else vanilla).coerceIn(1, cap)

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
        tag.put("inputs", inputs.serializeNBT())
        tag.put("outputs", outputs.serializeNBT())
    }

    companion object {

        /** 手持物品里存草稿的 NBT 键。 */
        const val KEY: String = "recipe_editor"

        /** 幽灵槽的容量上限（按 GT 里最大的配方类型给：装配线 16 物品输入、9 输出）。 */
        const val MAX_INPUTS: Int = 16
        const val MAX_OUTPUTS: Int = 9

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
            if (tag.contains("inputs")) restore(draft.inputs, tag.getCompound("inputs"))
            if (tag.contains("outputs")) restore(draft.outputs, tag.getCompound("outputs"))
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
