@file:Suppress("UNCHECKED_CAST", "DEPRECATION","unused")   // 只压真正需要压的那几项
package rain.gtetcore.gtet.common.item.recipe

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient
import com.gregtechceu.gtceu.api.registry.GTRegistries
import com.gregtechceu.gtceu.common.data.GTRecipeTypes

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.registries.ForgeRegistries

import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.config.GTETConfig

import java.io.File
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 把 [RecipeDraft] 翻译成"可以直接粘进 datagen 的代码文本"，并导出到磁盘。
 *
 * 输出的是**代码**而不是 JSON：整合包里的 GT 配方基本都是用代码（datagen）注册的，
 * 导出成 `GTRecipeTypes.xxx.recipeBuilder(...)…save(provider);` 这种片段，
 * Java / Kotlin 两边都能直接改改用，代价是不做语言选择。
 *
 * 文件落点与多方块导出统一收在 `GtetExport/` 下：本类是 `GtetExport/recipes/`，
 * 多方块是 `GtetExport/multiblock/`（都可用配置改）。文件名取配方 id，重复导出直接覆盖。
 *
 * @author rain fox
 */
object RecipeCodeWriter {

    /** 预览最多显示多少行。 */
    const val PREVIEW_LINES: Int = 16

    // ======================== 代码文本 ========================

    /** 生成完整代码文本（含头部注释）。 */
    @JvmStatic
    fun toCode(draft: RecipeDraft): String = buildString {
        append("// ").append(describe(draft)).append('\n')
        append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .append('\n')
        append(
            when (draft.kind) {
                RecipeDraft.Kind.CRAFTING_SHAPED -> shaped(draft)
                RecipeDraft.Kind.CRAFTING_SHAPELESS -> shapeless(draft)
                RecipeDraft.Kind.SMELTING -> cooking(draft, "smelting")
                RecipeDraft.Kind.BLASTING -> cooking(draft, "blasting")
                RecipeDraft.Kind.SMOKING -> cooking(draft, "smoking")
                RecipeDraft.Kind.STONECUTTING -> stonecutting(draft)
                RecipeDraft.Kind.SMITHING -> smithing(draft)
                RecipeDraft.Kind.GT -> gt(draft)
            },
        )
    }

    /** 预览用的前若干行。 */
    @JvmStatic
    fun previewLines(draft: RecipeDraft): Array<String> {
        val all = toCode(draft).split("\n")
        if (all.size <= PREVIEW_LINES) return all.toTypedArray()
        return (all.take(PREVIEW_LINES) + "// …（共 ${all.size} 行）").toTypedArray()
    }

    /** 一句话描述当前草稿，用于头部注释与界面提示。 */
    @JvmStatic
    fun describe(draft: RecipeDraft): String {
        val id = effectiveId(draft)
        return if (draft.kind == RecipeDraft.Kind.GT) {
            "${draft.gtType} / $id / ${draft.duration}t / ${draft.eut} EU/t / ${tierName(draft.tier)}"
        } else {
            "${draft.kind.cn} / $id"
        }
    }

    // ======================== 各类型的代码 ========================

    private fun shaped(draft: RecipeDraft): String {
        val out = draft.firstOutput()
        if (out.isEmpty) return "// 还没有填输出槽，先放一个产物再生成。\n"

        // 3x3 输入摊平成图案：先按行拼字符，再裁掉整空的上下行
        val legend = LinkedHashMap<String, Char>()
        var next = 'A'
        val rows = (0 until 3).map { row ->
            buildString {
                for (col in 0 until 3) {
                    val input = draft.input(row * 3 + col)
                    if (input.isEmpty) {
                        append(' ')
                        continue
                    }
                    val key = itemKey(input)
                    val ch = legend.getOrPut(key) { next++ }
                    append(ch)
                }
            }.trimEnd()
        }.let { list ->
            val trimmed = list.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            trimmed
        }
        if (rows.isEmpty() || legend.isEmpty()) return shapeless(draft)

        val first = legend.keys.first()
        return buildString {
            append("ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ").append(itemExpr(out)).append(")\n")
            rows.forEach { append("        .pattern(\"").append(it).append("\")\n") }
            legend.forEach { (key, ch) -> append("        .define('").append(ch).append("', ").append(key).append(")\n") }
            append("        .unlockedBy(\"has_item\", has(").append(first).append("))\n")
            append("        .save(provider);\n")
        }
    }

    private fun shapeless(draft: RecipeDraft): String {
        val out = draft.firstOutput()
        if (out.isEmpty) return "// 还没有填输出槽，先放一个产物再生成。\n"

        val used = (0 until RecipeDraft.MAX_INPUTS).map { draft.input(it) }.filter { !it.isEmpty }
        if (used.isEmpty()) return "// 还没有填输入槽，先放点材料再生成。\n"

        return buildString {
            append("ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ").append(itemExpr(out)).append(")\n")
            used.forEach { stack ->
                append("        .requires(").append(itemExpr(stack))
                if (stack.count > 1) append(", ").append(stack.count)
                append(")\n")
            }
            append("        .unlockedBy(\"has_item\", has(").append(itemExpr(used.first())).append("))\n")
            append("        .save(provider);\n")
        }
    }

    private fun cooking(draft: RecipeDraft, method: String): String {
        val input = draft.firstInput()
        val out = draft.firstOutput()
        if (input.isEmpty || out.isEmpty) return "// 熔炉系配方需要一个输入 + 一个输出。\n"

        return buildString {
            append("SimpleCookingRecipeBuilder.").append(method).append('(')
                .append("Ingredient.of(").append(itemExpr(input)).append("), ")
                .append("RecipeCategory.MISC, ").append(itemExpr(out)).append(", ")
                .append("0.1F, ") // 经验值界面暂未提供，默认 0.1
                .append(draft.duration).append(")\n")
            append("        .unlockedBy(\"has_item\", has(").append(itemExpr(input)).append("))\n")
            append("        .save(provider);\n")
        }
    }

    private fun stonecutting(draft: RecipeDraft): String {
        val input = draft.firstInput()
        val out = draft.firstOutput()
        if (input.isEmpty || out.isEmpty) return "// 切石机配方需要一个输入 + 一个输出。\n"

        return buildString {
            append("SingleItemRecipeBuilder.stonecutting(Ingredient.of(").append(itemExpr(input)).append("), ")
                .append("RecipeCategory.MISC, ").append(itemExpr(out)).append(", ")
                .append(maxOf(1, out.count)).append(")\n")
            append("        .unlockedBy(\"has_item\", has(").append(itemExpr(input)).append("))\n")
            append("        .save(provider, new ResourceLocation(\"").append(Gtetcore.MODID).append("\", \"")
                .append(effectiveId(draft)).append("\"));\n")
        }
    }

    private fun smithing(draft: RecipeDraft): String {
        val template = draft.input(0)
        val base = draft.input(1)
        val addition = draft.input(2)
        val out = draft.firstOutput()
        if (template.isEmpty || base.isEmpty || addition.isEmpty || out.isEmpty) {
            return "// 锻造台配方需要模板 + 底材 + 附加材料 + 输出四个槽。\n"
        }

        return buildString {
            append("SmithingTransformRecipeBuilder.smithing(")
                .append("Ingredient.of(").append(itemExpr(template)).append("), ")
                .append("Ingredient.of(").append(itemExpr(base)).append("), ")
                .append("Ingredient.of(").append(itemExpr(addition)).append("), ")
                .append("RecipeCategory.MISC, ").append(itemExpr(out)).append(")\n")
            append("        .unlocks(\"has_item\", has(").append(itemExpr(base)).append("))\n")
            append("        .save(provider, new ResourceLocation(\"").append(Gtetcore.MODID).append("\", \"")
                .append(effectiveId(draft)).append("\"));\n")
        }
    }

    private fun gt(draft: RecipeDraft): String {
        val inputs = (0 until RecipeDraft.MAX_INPUTS).map { draft.input(it) }.filter { !it.isEmpty }
        val outputs = (0 until RecipeDraft.MAX_OUTPUTS).map { draft.output(it) }.filter { !it.isEmpty }
        // 流体槽只有 GT 种类才有（原版配方模型里没有流体这一说），所以原版那些分支完全不用管。
        // 这里遍历到容量上限而不是"当前类型用几个"：换过类型后残留在槽里的东西不该被悄悄丢掉，
        // 用户能在预览里看到多出来的那几行，自己决定删不删。
        val fluidInputs = (0 until RecipeDraft.MAX_FLUID_INPUTS).map { draft.fluidInput(it) }.filter { !it.isEmpty }
        val fluidOutputs = (0 until RecipeDraft.MAX_FLUID_OUTPUTS).map { draft.fluidOutput(it) }.filter { !it.isEmpty }
        if (inputs.isEmpty() && outputs.isEmpty() && fluidInputs.isEmpty() && fluidOutputs.isEmpty()) {
            return "// 至少填一个输入或输出槽。\n"
        }

        return buildString {
            append(typeExpr(draft.gtType)).append(".recipeBuilder(\"").append(effectiveId(draft)).append("\")\n")
            inputs.forEach { stack ->
                append("        .inputItems(").append(itemExpr(stack))
                if (stack.count > 1) append(", ").append(stack.count)
                append(")\n")
            }
            fluidInputs.forEach { stack ->
                append("        .inputFluids(").append(fluidExpr(stack)).append(")\n")
            }
            outputs.forEach { stack ->
                append("        .outputItems(").append(itemExpr(stack))
                if (stack.count > 1) append(", ").append(stack.count)
                append(")\n")
            }
            fluidOutputs.forEach { stack ->
                append("        .outputFluids(").append(fluidExpr(stack)).append(")\n")
            }
            // 幽灵电路：设置过才写（0 也是合法配置，所以用 -1 表示"不用"）
            if (draft.circuit >= 0) append("        .circuitMeta(").append(draft.circuit).append(")\n")
            if (draft.duration > 0) append("        .duration(").append(draft.duration).append(")\n")
            append("        .EUt(").append(eutExpr(draft)).append(")\n")
            append("        .save(provider);\n")
        }
    }

    // ======================== 导出到磁盘 ========================

    /** 导出到 `GtetExport/recipes/<配方id>.kt`；返回写出的文件。 */
    @JvmStatic
    fun export(draft: RecipeDraft): File {
        val dir = File(GTETConfig.recipeExportDirectory())
        if (!dir.exists() && !dir.mkdirs()) error("无法创建导出目录: ${dir.absolutePath}")

        val file = File(dir, "${sanitize(effectiveId(draft))}.kt")
        file.writeText(toCode(draft), StandardCharsets.UTF_8)
        Gtetcore.LOGGER.info("[gtetcore] 配方代码已导出: {}", file.absolutePath)
        return file
    }

    // ======================== 表达式工具 ========================

    /** 配方 id：没填就用「种类（或 GT 类型）+ 第一个输出」自动拼一个。 */
    @JvmStatic
    fun effectiveId(draft: RecipeDraft): String {
        val raw = draft.recipeId.trim()
        if (raw.isNotEmpty()) return sanitize(raw)

        var base = if (draft.kind == RecipeDraft.Kind.GT) path(draft.gtType) else draft.kind.name.lowercase()
        val out = draft.firstOutput()
        if (!out.isEmpty) {
            ForgeRegistries.ITEMS.getKey(out.item)?.let { base = "${base}_${it.path}" }
        }
        return sanitize(base)
    }

    /**
     * 电压等级名（越界先夹进合法范围）：GTM 档是 `ULV`…`MAX`，GTET 的特殊档是 `MAX+1`…`MAX+16`。
     * 详细分工见 [VoltageTiers.name]。
     */
    @JvmStatic
    fun tierName(tier: Int): String = VoltageTiers.name(tier)

    /**
     * `VA[LV]` 还是具体数字。
     *
     * GTM 档位（`0..MAX`）行为不变：耗电正好等于该档的 `GTValues.VA[tier]` 时写成 `VA[档名]` 常量。
     * 特殊档（`MAX+1` 之后）**没有**对应的 VA —— `VA` 是 `int[15]`，连 `2^33` 都装不下，
     * 更没有 `VA[MAX+1]` 这种常量可写，所以一律输出数字字面量。
     *
     * ⚠️ 超过 int 的耗电必须带 `L`：特殊档从 `MAX+1`（8589934592）起就全在 int 之上，
     * 写成裸整数的话，粘进 datagen 的 Java/Kotlin 会直接「整数字面量过大」编译不过。
     */
    @Suppress
    private fun eutExpr(draft: RecipeDraft): String {
        val tier = VoltageTiers.coerce(draft.tier)
        if (tier in GTValues.VA.indices && GTValues.VA[tier].toLong() == draft.eut) {
            return "VA[${tierName(tier)}]"
        }
        return if (draft.eut > Int.MAX_VALUE) "${draft.eut}L" else draft.eut.toString()
    }

    /** 物品表达式：能对上 `Items` 常量就用常量，否则退回注册表查询。 */
    private fun itemExpr(stack: ItemStack): String {
        val key = ForgeRegistries.ITEMS.getKey(stack.item) ?: return "Items.AIR /* 未注册物品 */"
        ITEM_CONSTANTS[key]?.let { return "Items.$it" }
        return "ForgeRegistries.ITEMS.getValue(new ResourceLocation(\"${key.namespace}\", \"${key.path}\"))"
    }

    /**
     * 流体表达式：`FluidIngredient.of(<流体>, <mB>)`。
     *
     * 用 [FluidIngredient.of] 而不是 `.inputFluids(new FluidStack(...))`：
     * 后者的重载会把流体转成**流体标签**（内部走 `TagUtil.createFluidTag`），
     * 对 GT 自己的材料流体没问题，但对整合包里任意一个流体就可能生成一个根本不存在的标签；
     * `of(Fluid, int)` 建的是精确流体条件，导出成 datagen 代码后语义最直白。
     *
     * 流体照样用注册表查（和 [itemExpr] 的兜底分支同一个套路），
     * 拿不到 id 说明草稿里那个 FluidStack 已经失效，给个显眼的占位而不是写出崩不掉的假代码。
     */
    private fun fluidExpr(stack: FluidStack): String {
        val key = ForgeRegistries.FLUIDS.getKey(stack.fluid)
            ?: return "FluidIngredient.EMPTY /* 未注册流体 */"
        return "FluidIngredient.of(ForgeRegistries.FLUIDS.getValue(new ResourceLocation(\"" +
            "${key.namespace}\", \"${key.path}\")), ${stack.amount})"
    }

    /** define(...) 的第二个参数（既可以是物品也可以是标签，这里统一给物品常量）。 */
    private fun itemKey(stack: ItemStack): String = itemExpr(stack)

    /** GT 配方类型表达式：优先用 `GTRecipeTypes` 的常量名。 */
    private fun typeExpr(gtTypeRaw: String): String {
        val id = parse(gtTypeRaw) ?: return "GTRecipeTypes.DUMMY_RECIPES /* 配方类型 id 写错了 */"
        GT_TYPE_CONSTANTS[id]?.let { return "GTRecipeTypes.$it" }
        return "GTRegistries.RECIPE_TYPES.get(new ResourceLocation(\"${id.namespace}\", \"${id.path}\"))"
    }

    private fun path(id: String): String = parse(id)?.path ?: "recipe"

    private fun parse(raw: String): ResourceLocation? =
        raw.trim().takeIf { it.isNotEmpty() }?.let { ResourceLocation.tryParse(it) }

    /** 文件名 / id 清洗：只留字母数字、下划线、点、斜杠、短横。 */
    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_./-]"), "_").ifEmpty { "recipe" }

    // ======================== 常量名反射表 ========================
    // 说明：这里刻意用普通 for 循环而不是 buildMap { forEach { runCatching { return@forEach } } }：
    // 嵌套 lambda + 带标签的 return 在不同版本的 Kotlin 插件里判断不一致（编译器认、IDE 可能飘红），
    // 展开成循环后语义一样，任何版本都不会有异议。

    /** 物品 → `Items` 里的常量名（拿不到就退回注册表查询）。 */
    private val ITEM_CONSTANTS: Map<ResourceLocation, String> = HashMap<ResourceLocation, String>().apply {
        for (field in Items::class.java.declaredFields) {//idea犯病了
            if (!Modifier.isStatic(field.modifiers) || !Modifier.isPublic(field.modifiers)) continue
            if (!Item::class.java.isAssignableFrom(field.type)) continue//idea犯病了

            val item = try {
                field.get(null) as? Item
            } catch (e: Throwable) { // 含 IllegalAccessException 与类初始化失败
                null
            } ?: continue

            val key = ForgeRegistries.ITEMS.getKey(item) ?: continue
            putIfAbsent(key, field.name)
        }
    }

    /** 配方类型 id → `GTRecipeTypes` 里的常量名。 */
    private val GT_TYPE_CONSTANTS: Map<ResourceLocation, String> = HashMap<ResourceLocation, String>().apply {
        for (field in GTRecipeTypes::class.java.declaredFields) { //idea犯病了
            if (!Modifier.isStatic(field.modifiers)) continue
            if (!GTRecipeType::class.java.isAssignableFrom(field.type)) continue

            val type = try {
                field.get(null) as? GTRecipeType //idea犯病了
            } catch (e: Throwable) {
                null
            } ?: continue

            val key = GTRegistries.RECIPE_TYPES.getKey(type) ?: continue
            putIfAbsent(key, field.name)
        }
    }
}
