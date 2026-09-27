package rain.gtetcore.gtet.api.capability

import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier
import com.gregtechceu.gtceu.api.registry.GTRegistries
import com.gregtechceu.gtceu.utils.GTMath

/**
 * **时间流（TF）作为 GTM 的一等 [RecipeCapability]**。
 *
 * 注册进 `GTRegistries.RECIPE_CAPABILITIES` 之后，GTM 的配方体系就原样负责 TF 的匹配与扣费：
 * 配方 JSON / NBT 里的 `time_flow` 键由 GTM 自己的 codec 读写，扣费走
 * [com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler#handleRecipeInner]，
 * 每 tick 一次（路径见 `ETTimeFlowHandler` 的类注释）。
 *
 * ## 注册窗口
 * GTM 在 `GTRecipeCapabilities#init()`（`GTRecipeCapabilities.java:23-36`）里
 * `unfreeze()` → 注册自带的 5 个 → **回调 `IGTAddon::registerRecipeCapabilities`（:32）** →
 * `postEvent(RegisterEvent)`（:33-34）→ `freeze()`（:35）。
 * 也就是说 **addon 回调天然落在 unfreeze 与 freeze 之间**，直接 `register` 即可，
 * 不需要（也不应该）用那个 Forge 事件——本仓 `05` 文档记过 `RegisterEvent` 的具体泛型匹配坑。
 *
 * ## 本类实现/覆写了什么（其余全部继承 `RecipeCapability` 的默认实现）
 * - 构造器 `super(name, color, doRenderSlot, sortIndex, serializer)`：唯一的**必需**项
 *   （`RecipeCapability.java:49-56`，`protected`，外部包可继承）。
 * - [copyInner]：TF 内容是**不可变值对象**，直接返回入参，省掉默认实现那次 FriendlyByteBuf 往返
 *   （`RecipeCapability.java:69-73` 的默认实现是 `toNetwork` + `fromNetwork`；EU 也是这么做的，
 *   见 `EURecipeCapability.java:27-30`）。
 * - [copyWithModifier]：**必须覆写**。默认实现直接丢弃 modifier（`RecipeCapability.java:78-80`），
 *   而并行数就是通过 `ContentModifier.multiplier(parallels)` + `Content#copy`（`Content.java:68-74`）
 *   作用到 `tickInputs` 上的（`GTRecipe.java:132`）——不覆写的话「并行 64 倍」不会让 TF 消耗跟着 ×64。
 * - [getMaxParallelByInput]：按「仓里现有的 TF 存量 ÷ 配方每 tick 的 TF 成本」限制并行，
 *   形状对齐 `EURecipeCapability.java:99-126`；机器上**一个 TF 仓都没有时直接返回 `limit`**
 *   （即不在这里判负），把「TF 不足」的报错留给内容匹配阶段，避免这一层提前把并行清零。
 *
 * ## 刻意**没有**覆写的（默认值就是对的，理由写在这里免得后人以为漏了）
 * - `limitMaxParallelByOutput`（`RecipeCapability.java:145-148`）：默认 `Integer.MAX_VALUE` = 不处理。
 *   TF 不是「会被撑爆的库存」，输出空间合并没有意义。
 * - `doMatchInRecipe`（:131-133）默认 `true`：TF 必须进配方匹配，这一条不能动。
 * - `shouldBypassDistinct`（:223-225）默认 `true`：TF 仓在语义上等同能源仓（「每台多方块一个全局仓」），
 *   要能绕过 distinct 检查，才能在 ME 样板仓那种 distinct 场景里照样供电。
 * - `isRecipeSearchFilter` / `doAddGuiSlots`（:115-117,165-167）默认 `false`：TF 做的是**消耗**不是匹配查找，
 *   没有配方搜索槽。因此也不需要 `getWidgetClass` 与 XEI 系列（:169-203），JEI/EMI 里暂时不显示 TF。
 *
 * @author rain fox
 */
class ETTimeFlowCapability private constructor() : RecipeCapability<ETTimeFlowStack>(
    NAME, COLOR, false, SORT_INDEX, ETTimeFlowSerializer,
) {

    override fun copyInner(content: ETTimeFlowStack): ETTimeFlowStack = content

    override fun copyWithModifier(content: ETTimeFlowStack, modifier: ContentModifier): ETTimeFlowStack =
        ETTimeFlowStack(modifier.apply(content.amount))

    override fun getMaxParallelByInput(
        holder: IRecipeCapabilityHolder,
        recipe: GTRecipe,
        limit: Int,
        tick: Boolean,
    ): Int {
        if (!holder.hasCapabilityProxies()) return limit

        val inputs = if (tick) recipe.getTickInputContents(this) else recipe.getInputContents(this)
        if (inputs.isEmpty()) return limit

        // 每份配方要花的 TF：chance == 0 的是「不消耗」内容，不计入。
        var costPerRecipe = 0L
        for (content in inputs) {
            if (content.chance != 0) costPerRecipe += of(content.content).amount
        }
        if (costPerRecipe <= 0L) return limit

        val handlers = holder.getCapabilitiesFlat(IO.IN, this)
        // 机器上还没有 TF 仓：不在这里判负，交给内容匹配阶段报「insufficient in: Time Flow」。
        if (handlers.isEmpty()) return limit

        var buffered = 0L
        for (handler in handlers) {
            for (content in handler.contents) {
                if (content is ETTimeFlowStack) buffered += content.amount
            }
        }

        if (buffered < costPerRecipe) return 0
        return GTMath.saturatedCast(buffered / costPerRecipe).coerceAtMost(limit)
    }

    companion object {

        /**
         * 能力名。**这是配方 JSON / NBT / 网络里的那个字符串键**，改它等于破坏存档兼容。
         * 带 `time_flow` 语义而非 `gtet_xxx` 前缀：它是要写进配方文件的公开标识。
         */
        const val NAME: String = "time_flow"

        /** 能力主题色，青蓝 `#00E5FF`（ARGB）。用于 [getColoredName]，与 EU 的黄色区分。 */
        const val COLOR: Int = 0xFF00E5FF.toInt()

        /**
         * 排序位。GTM 自带的是 item=0 / fluid=1 / eu=2 / cwu=3 / block_state=5
         * （各自的 `super(...)` 构造器），4 空着，TF 作为「第二种能量」紧跟在 EU 与 CWU 之后。
         */
        const val SORT_INDEX: Int = 4

        /** 全局唯一实例。GTM 自带能力也都是单例（`EURecipeCapability.CAP` 等）。 */
        @JvmField
        val CAP: ETTimeFlowCapability = ETTimeFlowCapability()

        /**
         * 把本能力注册进 GTM 的能力表。
         *
         * **只能在 `IGTAddon#registerRecipeCapabilities()` 里调用**：那一瞬间表是 unfreeze 的，
         * 早于它表还冻着（`register` 会抛 `registry ... has been frozen`），晚于它同理。
         */
        @JvmStatic
        fun init() {
            GTRegistries.RECIPE_CAPABILITIES.register(NAME, CAP)
        }
    }
}
