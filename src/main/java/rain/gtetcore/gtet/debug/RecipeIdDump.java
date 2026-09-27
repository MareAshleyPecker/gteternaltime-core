package rain.gtetcore.gtet.debug;

import com.gregtechceu.gtceu.common.data.GTRecipes;
import com.gregtechceu.gtceu.data.recipe.GTCraftingComponents;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import rain.gtetcore.gtet.Gtetcore;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 诊断开关：把 GTM 配方生成流水线产出的**真实配方 id** 全量导到 `build/tmp/recipe-id-dump.txt`。
 * <p>
 * 默认**关闭**，只有显式打开才干活：
 * ```
 * gradlew runData -Dgtetcore.debug.recipeDump=true
 * ```
 * （JVM 系统属性，不是 mod 配置项——它只在排查配方 id 时用得上，不该进玩家的配置界面。）
 * <p>
 * ## 为什么不用服务端 RecipeManager
 * 本仓的 `MixinGTMaterialBlocks` 引用了客户端专属类 `net.minecraft.client.color.item.ItemColor`，
 * 却挂在 gtetcore.mixins.json 的公共 mixins 里 —— 专用服务端跑到 `GTBlocks.init` 就
 * `MixinApplyError: ClassMetadataNotFoundException: net.minecraft.client.color.item.ItemColor`
 * （既有问题，与本功能无关）；而 runData 不加载数据包，`AddPackFindersEvent` 不会触发。
 * 所以这里在 runData 里手工调 GTM 自己的生成入口。
 * <p>
 * ## 跑两遍
 * A. 先清空 `GTRecipes.RECIPE_FILTERS` 再生成 → 全集（未过滤）
 * B. 还原过滤器再生成 → 过滤后（= 服务端实际会拿到的集合）
 * 差集就是 `removeRecipes` 的真实效果；filters 里对不上任何 id 的条目记为 `FILTER-NOOP`
 * （静默失效的删除请求会在这里现形）。
 * <p>
 * 只调 `getId()`、**不调 `toJson()`**，所以不会踩 `GTRegistries.builtinRegistry()` 那个客户端 NPE。
 */
@SuppressWarnings("removal")
@Mod.EventBusSubscriber(modid = Gtetcore.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RecipeIdDump {

    /** 打开诊断的系统属性 / 环境变量。 */
    private static final String ENABLE_PROPERTY = "gtetcore.debug.recipeDump";

    /** 环境变量优先：JavaExec 默认继承父进程环境，比 `-D` 更容易传到被 fork 的 runData JVM。 */
    private static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY) || Boolean.parseBoolean(System.getenv("GTETCORE_DEBUG_RECIPE_DUMP"));
    }

    @SubscribeEvent
    public static void onGatherData(GatherDataEvent event) {
        if (!enabled()) return;
        Gtetcore.LOGGER.info("[RecipeIdDump] enabled via -D{}=true / GTETCORE_DEBUG_RECIPE_DUMP=true", ENABLE_PROPERTY);
        List<String> failures = new ArrayList<>();
        try {
            GTCraftingComponents.init();
        } catch (Throwable t) {
            failures.add("GTCraftingComponents.init: " + t);
            Gtetcore.LOGGER.error("[RecipeIdDump] GTCraftingComponents.init failed", t);
        }

        // 让 GTM 与本仓的 addon 把「想删的 id」登记进来
        TreeSet<String> filters = new TreeSet<>();
        try {
            GTRecipes.recipeRemoval();
            for (ResourceLocation id : GTRecipes.RECIPE_FILTERS) filters.add(id.toString());
        } catch (Throwable t) {
            failures.add("recipeRemoval: " + t);
            Gtetcore.LOGGER.error("[RecipeIdDump] recipeRemoval failed", t);
        }

        // A：未过滤全集
        TreeSet<String> all = new TreeSet<>();
        GTRecipes.RECIPE_FILTERS.clear();
        try {
            GTRecipes.recipeAddition(recorder(all, failures));
        } catch (Throwable t) {
            failures.add("recipeAddition(unfiltered): " + t);
            Gtetcore.LOGGER.error("[RecipeIdDump] unfiltered recipe generation failed", t);
        }

        // B：还原过滤器后的实际集合
        TreeSet<String> kept = new TreeSet<>();
        GTRecipes.RECIPE_FILTERS.addAll(filters.stream().map(ResourceLocation::new).toList());
        try {
            GTRecipes.recipeAddition(recorder(kept, failures));
        } catch (Throwable t) {
            failures.add("recipeAddition(filtered): " + t);
            Gtetcore.LOGGER.error("[RecipeIdDump] filtered recipe generation failed", t);
        }

        TreeSet<String> removed = new TreeSet<>(all);
        removed.removeAll(kept);

        dump(all, kept, removed, filters, failures);
    }

    private static Consumer<FinishedRecipe> recorder(TreeSet<String> sink, List<String> failures) {
        return r -> {
            try {
                sink.add(r.getId().toString());
            } catch (Throwable t) {
                failures.add(String.valueOf(t));
            }
        };
    }

    private static void dump(TreeSet<String> all, TreeSet<String> kept, TreeSet<String> removed,
                            TreeSet<String> filters, List<String> failures) {
        try {
            Path out = Path.of("build", "tmp", "recipe-id-dump.txt");
            Files.createDirectories(out.getParent());
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                w.println("# ALL(未过滤) = " + all.size());
                for (String id : all) w.println("ALL " + id);
                w.println("# KEPT(过滤后) = " + kept.size());
                for (String id : kept) w.println("KEPT " + id);
                w.println("# REMOVED(被过滤器真的删掉) = " + removed.size());
                for (String id : removed) w.println("REMOVED " + id);
                w.println("# FILTERS(登记的删除请求) = " + filters.size());
                for (String id : filters) {
                    if (all.contains(id)) w.println("FILTER-HIT " + id);
                    else w.println("FILTER-NOOP " + id);
                }
                for (String f : failures) w.println("# FAILURE: " + f);
            }
            Gtetcore.LOGGER.info("[RecipeIdDump] all={} kept={} removed={} filters={} failures={}",
                    all.size(), kept.size(), removed.size(), filters.size(), failures.size());
            for (String needle : new String[] { "fireclay", "firebrick", "casing_primitive", "parallel_hatch" }) {
                Gtetcore.LOGGER.info("[RecipeIdDump] ---- ALL matching '{}' ----", needle);
                for (String id : all) if (id.contains(needle)) Gtetcore.LOGGER.info("[RecipeIdDump] ALL  {}", id);
                Gtetcore.LOGGER.info("[RecipeIdDump] ---- KEPT matching '{}' ----", needle);
                for (String id : kept) if (id.contains(needle)) Gtetcore.LOGGER.info("[RecipeIdDump] KEPT {}", id);
            }
            Gtetcore.LOGGER.info("[RecipeIdDump] ---- FILTERS ----");
            for (String id : filters) {
                Gtetcore.LOGGER.info("[RecipeIdDump] {} {}", all.contains(id) ? "FILTER-HIT " : "FILTER-NOOP", id);
            }
            Gtetcore.LOGGER.info("[RecipeIdDump] ---- ITEMS ----");
            for (ResourceLocation id : net.minecraftforge.registries.ForgeRegistries.ITEMS.getKeys()) {
                String p = id.getPath();
                if (p.contains("fireclay") || p.contains("firebrick")) {
                    Gtetcore.LOGGER.info("[RecipeIdDump] ITEM {}",
                            net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(
                                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id)));
                }
            }
        } catch (Exception e) {
            Gtetcore.LOGGER.error("[RecipeIdDump] dump failed", e);
        }
    }
}
