package rain.gtetcore.gtet.common.item.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高级终端的设置（存在物品 NBT 里）。
 *
 * <p>两类内容：
 * <ul>
 *   <li>AE 链接：一个指向<b>无线接入点</b>的 {@link GlobalPos}（与 AE2 无线终端同一套思路，
 *       但本终端自己解析网络，不依赖背包里带 AE 无线终端）；</li>
 *   <li>分级组偏好：{@link StructureBuildPlanner.Plan#groups()} 里每个组选了哪个方块
 *       （组键 → 物品注册名）。线圈、聚变玻璃、火箱、分级机壳都走这一套。</li>
 * </ul>
 *
 * @author rain fox
 */
public final class TerminalSettings {

    private static final String ROOT = "gtet_terminal";
    private static final String AE_LINK = "ae_link";
    private static final String PREFS = "group_prefs";
    private static final String PREF_KEY = "group";
    private static final String PREF_ITEM = "item";

    private TerminalSettings() {}

    private static CompoundTag root(ItemStack stack, boolean create) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            if (!create) return null;
            tag = new CompoundTag();
            stack.setTag(tag);
        }
        if (!tag.contains(ROOT, Tag.TAG_COMPOUND)) {
            if (!create) return null;
            tag.put(ROOT, new CompoundTag());
        }
        return tag.getCompound(ROOT);
    }

    // ======================== AE 链接 ========================

    /** 绑定到某个无线接入点。 */
    public static void linkAe(ItemStack stack, GlobalPos pos) {
        CompoundTag link = new CompoundTag();
        link.putString("dim", pos.dimension().location().toString());
        link.putInt("x", pos.pos().getX());
        link.putInt("y", pos.pos().getY());
        link.putInt("z", pos.pos().getZ());
        root(stack, true).put(AE_LINK, link);
    }

    /** 解绑。 */
    public static void unlinkAe(ItemStack stack) {
        CompoundTag tag = root(stack, false);
        if (tag != null) tag.remove(AE_LINK);
    }

    /** 当前绑定的无线接入点位置；未绑定时为 null。 */
    @Nullable
    public static GlobalPos getAeLink(ItemStack stack) {
        CompoundTag tag = root(stack, false);
        if (tag == null || !tag.contains(AE_LINK, Tag.TAG_COMPOUND)) return null;
        CompoundTag link = tag.getCompound(AE_LINK);
        ResourceLocation dimension = ResourceLocation.tryParse(link.getString("dim"));
        if (dimension == null) return null;
        return GlobalPos.of(
                ResourceKey.create(Registries.DIMENSION, dimension),
                new BlockPos(link.getInt("x"), link.getInt("y"), link.getInt("z")));
    }

    /** 是否已绑定 AE 网络。 */
    public static boolean hasAeLink(ItemStack stack) {
        return getAeLink(stack) != null;
    }

    // ======================== 分级组偏好 ========================

    /** 读取全部组偏好。 */
    public static Map<String, String> getPreferences(ItemStack stack) {
        Map<String, String> prefs = new LinkedHashMap<>();
        CompoundTag tag = root(stack, false);
        if (tag == null || !tag.contains(PREFS, Tag.TAG_LIST)) return prefs;
        ListTag list = tag.getList(PREFS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            prefs.put(entry.getString(PREF_KEY), entry.getString(PREF_ITEM));
        }
        return prefs;
    }

    /** 为某个分级组选定方块（传 null 表示恢复默认）。 */
    public static void setPreference(ItemStack stack, String groupKey, @Nullable String itemId) {
        Map<String, String> prefs = getPreferences(stack);
        if (itemId == null) {
            prefs.remove(groupKey);
        } else {
            prefs.put(groupKey, itemId);
        }
        ListTag list = new ListTag();
        prefs.forEach((key, value) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString(PREF_KEY, key);
            entry.putString(PREF_ITEM, value);
            list.add(entry);
        });
        root(stack, true).put(PREFS, list);
    }

    /** 清空全部组偏好。 */
    public static void clearPreferences(ItemStack stack) {
        CompoundTag tag = root(stack, false);
        if (tag != null) tag.remove(PREFS);
    }

    /**
     * 按偏好在这格的候选里挑一个方块；没设偏好就用第一个候选。
     */
    public static ItemStack resolve(ItemStack terminal, StructureBuildPlanner.Slot slot) {
        if (slot.candidates().isEmpty()) return ItemStack.EMPTY;
        String groupKey = slot.groupKey();
        if (groupKey == null) return slot.candidates().get(0);

        String wanted = getPreferences(terminal).get(groupKey);
        if (wanted != null) {
            for (ItemStack candidate : slot.candidates()) {
                var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(candidate.getItem());
                if (key != null && key.toString().equals(wanted)) {
                    return candidate;
                }
            }
        }
        return slot.candidates().get(0);
    }

    // ======================== 上次规划（供界面显示分级组、供"开始搭建"复用） ========================

    private static final String PLAN = "plan";
    private static final String PLAN_DIM = "dim";
    private static final String PLAN_X = "x";
    private static final String PLAN_Y = "y";
    private static final String PLAN_Z = "z";
    private static final String PLAN_GROUPS = "groups";
    private static final String GROUP_KEY = "key";
    private static final String GROUP_CANDIDATES = "candidates";

    /** 界面上展示的一个分级组。 */
    public record GroupView(String key, List<String> candidates, String chosen) {}

    /** 记录一次规划：控制器位置 + 各分级组候选。 */
    public static void cachePlan(ItemStack stack, BlockPos controller, ResourceLocation dimension,
                                 Map<String, List<String>> groups) {
        CompoundTag plan = new CompoundTag();
        plan.putString(PLAN_DIM, dimension.toString());
        plan.putInt(PLAN_X, controller.getX());
        plan.putInt(PLAN_Y, controller.getY());
        plan.putInt(PLAN_Z, controller.getZ());

        ListTag list = new ListTag();
        groups.forEach((key, candidates) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString(GROUP_KEY, key);
            ListTag ids = new ListTag();
            for (String id : candidates) ids.add(net.minecraft.nbt.StringTag.valueOf(id));
            entry.put(GROUP_CANDIDATES, ids);
            list.add(entry);
        });
        plan.put(PLAN_GROUPS, list);
        root(stack, true).put(PLAN, plan);
    }

    /** 上次规划涉及的控制器位置。 */
    @Nullable
    public static GlobalPos getPlanTarget(ItemStack stack) {
        CompoundTag tag = root(stack, false);
        if (tag == null || !tag.contains(PLAN, Tag.TAG_COMPOUND)) return null;
        CompoundTag plan = tag.getCompound(PLAN);
        ResourceLocation dimension = ResourceLocation.tryParse(plan.getString(PLAN_DIM));
        if (dimension == null) return null;
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension),
                new BlockPos(plan.getInt(PLAN_X), plan.getInt(PLAN_Y), plan.getInt(PLAN_Z)));
    }

    public static boolean hasPlan(ItemStack stack) {
        return getPlanTarget(stack) != null;
    }

    /** 上次规划里的分级组（只有多候选的组会进这个列表）。 */
    public static List<GroupView> cachedGroups(ItemStack stack) {
        List<GroupView> groups = new ArrayList<>();
        CompoundTag tag = root(stack, false);
        if (tag == null || !tag.contains(PLAN, Tag.TAG_COMPOUND)) return groups;
        CompoundTag plan = tag.getCompound(PLAN);
        if (!plan.contains(PLAN_GROUPS, Tag.TAG_LIST)) return groups;

        Map<String, String> prefs = getPreferences(stack);
        ListTag list = plan.getList(PLAN_GROUPS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String key = entry.getString(GROUP_KEY);
            List<String> candidates = new ArrayList<>();
            ListTag ids = entry.getList(GROUP_CANDIDATES, Tag.TAG_STRING);
            for (int j = 0; j < ids.size(); j++) candidates.add(ids.getString(j));
            if (candidates.size() < 2) continue;
            groups.add(new GroupView(key, candidates, prefs.getOrDefault(key, candidates.get(0))));
        }
        return groups;
    }

    /** 在某个分级组里循环切换到下一个候选。 */
    public static void cycleChoice(ItemStack stack, GroupView group) {
        List<String> candidates = group.candidates();
        if (candidates.isEmpty()) return;
        int index = candidates.indexOf(group.chosen());
        String next = candidates.get((index + 1) % candidates.size());
        setPreference(stack, group.key(), next);
    }
}
