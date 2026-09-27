package rain.gtetcore.gtet.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.gregtechceu.gtceu.api.pattern.Predicates
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer
import com.mojang.serialization.JsonOps
import net.minecraft.core.HolderLookup
import net.minecraft.data.CachedOutput
import net.minecraft.data.DataProvider
import net.minecraft.data.PackOutput
import net.minecraft.resources.RegistryOps
import rain.gtetcore.gtet.api.capability.ETPartAbility
import rain.gtetcore.gtet.common.data.machine.ALLSmachine
import rain.gtetcore.gtet.data.recipes.TestTimeFlowRecipe
import java.util.concurrent.CompletableFuture

/**
 * **把 TF 测试配方序列化成 JSON 落盘** —— 给「能力注册 → 配方序列化 → 写盘」这条链留一份可检验的产物。
 *
 * 产物：`reports/gtetcore/test_time_flow_recipe.json`（相对 datagen 输出根，即
 * `src/generated/resources/reports/gtetcore/`）。
 *
 * ## 为什么不走 GTM 自带的 `GTRecipeBuilder#toJson()`
 * 那条路在 datagen 下**必崩**，本仓 `GTETDatagen` 的类注释已经记过原文：
 * `GTRecipeBuilder.toJson()` 第一件事是 `GTRegistries.builtinRegistry()`
 * （javap 实证：`toJson` 字节码第 3 条指令就是它），而它内部是
 * `GTCEu.isClientThread()` → `Minecraft.getInstance().isSameThread()`，
 * 数据生成环境里没有 `Minecraft` 实例 ⇒ NPE。
 * 本类**仍然**会去调一次 `toJson` 并把异常原文一起写进产物（键 `gtm_toJson_error`），
 * 这样这条已知限制在产物里是**可复核**的，而不是只存在于注释里。
 *
 * ## 那这份 JSON 是怎么来的
 * 用 GTM **自己的**那个 codec：`GTRecipeSerializer.CODEC`
 * （`GTRecipeBuilder#toJson` 内部用的也正是它），只是把 `RegistryOps` 的注册表来源换成
 * datagen 给的 `HolderLookup.Provider`（`GatherDataEvent#getLookupProvider`），
 * 从而绕开 `GTRegistries.builtinRegistry()`。
 * 也就是说：**序列化本身一个字节都没有自己拼**，`"tickInputs": { "time_flow": ... }`
 * 这个键是 GTM 的方案 + 我们注册的 `ETTimeFlowCapability` 的序列化器一起产出的。
 *
 * @param output datagen 输出根（`DataGenerator#getPackOutput`）
 * @param lookup `GatherDataEvent#getLookupProvider` 给的注册表查询器
 *
 * @author rain fox
 */
class GTETDataReport(
    private val output: PackOutput,
    private val lookup: CompletableFuture<HolderLookup.Provider>
) : DataProvider {

    override fun run(cache: CachedOutput): CompletableFuture<*> = lookup.thenCompose { provider ->
        DataProvider.saveStable(cache, buildJson(provider), targetPath())
    }

    override fun getName(): String = "GTET datagen report (TF test recipe serialization)"

    /** 产物路径：**不在 `data/` / `assets/` 下**，所以它不会被游戏当成数据包内容加载。 */
    private fun targetPath() = output.getOutputFolder().resolve(PATH)

    private fun buildJson(provider: HolderLookup.Provider): JsonObject {
        val root = JsonObject()
        root.addProperty("_comment", "测试产物：TF（time_flow）参与配方序列化的实证，不参与游戏运行")
        root.addProperty("recipe_id", "gtceu:macerator/${TestTimeFlowRecipe.ID}")
        root.addProperty("recipe_class", "MACERATOR_RECIPES")
        root.addProperty("tf_per_tick", TestTimeFlowRecipe.TF_PER_TICK)
        root.addProperty("duration", TestTimeFlowRecipe.DURATION)
        root.addProperty("total_tf_per_craft", TestTimeFlowRecipe.TOTAL_TF)
        root.addProperty(
            "how",
            "GTRecipeSerializer.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, GatherDataEvent#getLookupProvider), GTRecipeBuilder#buildRawRecipe)"
        )

        // ① 正路：GTM 自己的 codec（见类注释）
        try {
            val ops = RegistryOps.create(JsonOps.INSTANCE, provider)
            val recipe = TestTimeFlowRecipe.builder().buildRawRecipe()
            val encoded = GTRecipeSerializer.CODEC.encodeStart(ops, recipe)
            val json: JsonElement? = encoded.result().orElse(null)
            if (json == null) {
                root.addProperty(
                    "codec_error",
                    encoded.error().map { it.message() }.orElse("unknown codec error")
                )
            } else {
                root.add("recipe", json)
            }
        } catch (t: Throwable) {
            root.addProperty("codec_error", t.toString())
        }

        // ② 对照：GTM 自带的 toJson（datagen 下预期 NPE，把原文留下当证据）
        try {
            val legacy = JsonObject()
            TestTimeFlowRecipe.builder().toJson(legacy)
            root.add("gtm_toJson", legacy)
        } catch (t: Throwable) {
            root.addProperty("gtm_toJson_error", t.toString())
        }

        // ③ 能力谓词自检：`autoAbilities(ZZZ)` 的返回值里到底有没有时序仓
        probeAutoAbilities(root)

        return root
    }

    /**
     * **谓词自检**：证明「`ETPartAbility.TF_HATCH` 登记成功」+「mixin 真的把这条能力追加进了
     * `Predicates.autoAbilities(_, _, true)` 的返回值」。
     *
     * 做法：调用一次 `autoAbilities(true, false, true)`（这会强制类加载 `Predicates`，
     * mixin 随之应用；注入点写错的话这一步就会抛异常），然后把返回谓词里所有
     * `SimplePredicate` 的**预览候选**（`getCandidates()`，就是 JEI 结构预览里摆的那些方块）
     * 摊平，看看时序仓的方块在不在里面。
     *
     * ⚠️ 这**不是**实机结构检测：真正的「插进多方块能成型」还是要开游戏摆一遍。
     * 它证到的是能力登记 + 谓词注入这两段。
     * ⚠️ 这里会把 `PartAbility#getAllBlocks()` 的记忆化快照提前取下来（就在 datagen 这一个 JVM 里），
     * 不影响游戏进程。
     */
    private fun probeAutoAbilities(root: JsonObject) {
        val probe = JsonObject()
        try {
            val abilityBlocks = ETPartAbility.TF_HATCH.allBlocks
            probe.addProperty("ability", ETPartAbility.TF_HATCH.name)
            probe.addProperty("ability_block_count", abilityBlocks.size)
            probe.addProperty("registered_hatch_count", ALLSmachine.TIME_FLOW_HATCHES.size)

            val exported = Predicates.autoAbilities(true, false, true)
            // candidates 是 @Nullable（GTM 里 ANY / AIR 那种谓词没有候选方块），这里跳过它们
            val candidates = (exported.common + exported.limited)
                .flatMap { predicate -> predicate.candidates?.get()?.map { info -> info.blockState.block } ?: emptyList() }

            val found = ALLSmachine.TIME_FLOW_HATCHES
                .map { it.block }
                .filter { candidates.contains(it) }
            probe.addProperty("predicate_candidate_block_count", candidates.size)
            probe.addProperty("tf_hatch_found_in_predicate", found.size)
            probe.addProperty("note", "autoAbilities(true,false,true) 的返回谓词里出现的时序仓方块数（期望 7）")
        } catch (t: Throwable) {
            probe.addProperty("error", t.toString())
        }
        root.add("ability_probe", probe)
    }

    companion object {

        /** 产物相对路径（datagen 输出根之下）。 */
        const val PATH: String = "reports/gtetcore/test_time_flow_recipe.json"
    }
}
