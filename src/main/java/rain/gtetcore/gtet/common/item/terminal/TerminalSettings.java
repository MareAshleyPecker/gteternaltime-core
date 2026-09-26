package rain.gtetcore.gtet.common.item.terminal;

import com.gregtechceu.gtceu.common.block.CoilBlock;
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

import java.util.*;

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
        Objects.requireNonNull(root(stack, true)).put(AE_LINK, link);
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
        Objects.requireNonNull(root(stack, true)).put(PREFS, list);
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
        String wanted = lookupPreference(terminal, slot.groupKey(), slot.candidates());
        ItemStack hit = findById(slot.candidates(), wanted);
        if (hit != null) return hit;
        // 偏好不在这一格的候选里：只有「整格候选都是线圈」才把它补回来 ——
        // GTMThings 的 AutoBuildSetting#apply 组装线圈候选时会砍掉最后一档（见 lookupPreference 注释），
        // 而线圈谓词本来就接受任何一级线圈，补回来是安全的。其它情况一律回退第一档：
        // 把谓词不接受的方块放到那格里只会让结构永远不成型。
        if (wanted != null && allCoils(slot.candidates())) {
            ItemStack extra = StructureBuildPlanner.itemStackOf(wanted);
            if (extra != null && !extra.isEmpty()) return extra;
        }
        return slot.candidates().get(0);
    }

    // ======================== 组键 → 偏好 ========================

    /**
     * 找出「这一格该用哪一档」的偏好物品 id（注册名）；没有可用偏好时返回 {@code null}。
     *
     * <p><b>第一步：组键精确命中</b> —— 面板/扫描两边用同一套
     * {@link StructureBuildPlanner#groupKey} 算键，键一样就直接取。
     *
     * <p>⚠️ <b>第二步：候选集回退</b>（这一步是必须的，否则「面板里选了却不生效」）。
     * 静态表（{@link TerminalStaticGroups}）与结构扫描谓词给的候选集**不保证完全一致**：
     * 典型是线圈 —— GTCEu 的 {@code Predicates.heatingCoils()} 给全部线圈，而 GTMThings 的
     * {@code AutoBuildSetting#apply} 组装候选时把最后一档砍掉了（{@code i < blockInfos.length - 1}），
     * 于是搭建时的键与面板里的键对不上。回退规则（按「同类」判定，避免误配到别的格）：
     *
     * <ul>
     *   <li>偏好所属的组（{@code plan.groups} 里的候选集 {@code G}）与本格候选集 {@code S}
     *       必须有交集，<b>且</b>满足 {@code G ⊆ S} 或 {@code S ⊆ G}；</li>
     *   <li>满足多个时取交集最大的那个（并列取 NBT 里的先后顺序，保证确定性）。</li>
     * </ul>
     *
     * <p>为什么要那个「包含关系」的闸：光看「偏好物品在不在 {@code S} 里」会把
     * 「能源仓那一组选的档」误配到别的也接受仓室的位置上（例如某个笼统的「任意仓室」格）。
     * 加上包含关系之后，只有「同一类部件的更具体 / 更宽泛版本」才会命中 ——
     * 例如线圈（{@code S} 缺一档，{@code S ⊆ G}）与只收某一档的仓室格（{@code S ⊆ G}）。
     *
     * <p>⚠️ 两条都取不到就返回 {@code null}（不猜）：偏好的组键在 {@code plan.groups} 里找不到
     * （例如旧 NBT 里留下的、已被后来扫描覆盖掉的组）时不做回退匹配。
     */
    @Nullable
    public static String lookupPreference(ItemStack terminal, @Nullable String groupKey, List<ItemStack> candidates) {
        if (candidates.isEmpty()) return null;
        Map<String, String> prefs = getPreferences(terminal);
        if (prefs.isEmpty()) return null;

        Set<String> slotIds = idsOf(candidates);
        if (groupKey != null) {
            String wanted = prefs.get(groupKey);
            if (wanted != null && slotIds.contains(wanted)) return wanted;
        }

        Map<String, List<String>> planned = plannedGroups(terminal);
        if (planned.isEmpty()) return null;
        String best = null;
        int bestOverlap = -1;
        for (Map.Entry<String, String> pref : prefs.entrySet()) {
            List<String> group = planned.get(pref.getKey());
            if (group == null || group.size() < 2) continue;
            Set<String> groupIds = new LinkedHashSet<>(group);
            int overlap = 0;
            for (String id : groupIds) {
                if (slotIds.contains(id)) overlap++;
            }
            if (overlap == 0) continue;
            boolean related = slotIds.containsAll(groupIds) || groupIds.containsAll(slotIds);
            if (!related) continue;
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = pref.getValue();
            }
        }
        return best;
    }

    // ======================== 小工具 ========================

    /** 候选里的物品 id 集合。 */
    private static Set<String> idsOf(List<ItemStack> candidates) {
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack candidate : candidates) {
            String id = StructureBuildPlanner.itemId(candidate);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /** 候选里注册名等于 {@code itemId} 的那一档；没有就返回 null。 */
    @Nullable
    private static ItemStack findById(List<ItemStack> candidates, @Nullable String itemId) {
        if (itemId == null) return null;
        for (ItemStack candidate : candidates) {
            if (itemId.equals(StructureBuildPlanner.itemId(candidate))) return candidate;
        }
        return null;
    }

    /** 整格候选是不是全是线圈（见 {@link #resolve} 里「把最后一档补回来」那条的准入条件）。 */
    private static boolean allCoils(List<ItemStack> candidates) {
        for (ItemStack candidate : candidates) {
            if (!(candidate.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) ||
                    !(blockItem.getBlock() instanceof CoilBlock)) {
                return false;
            }
        }
        return !candidates.isEmpty();
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

    /**
     * 把 {@link TerminalStaticGroups} 的 6 类静态组预置进终端 NBT，让界面**不扫描**也能列出可选部件。
     *
     * <p>⚠️ <b>只在服务端调</b>（见 {@link TerminalGroupSeeder}）：客户端写自己背包物品的 NBT
     * 不会同步回服务端，写了也白写，还会让两端界面用不同的数据建树。
     *
     * <p>合并规则（两条都必要）：
     * <ul>
     *   <li>已有的组（上次扫描出来的真实分级组）原样保留 —— 静态组与它们<b>共存</b>；</li>
     *   <li>已有的组偏好 {@code group_prefs} <b>一个都不动</b>（本方法只写 {@code plan}）。</li>
     * </ul>
     *
     * @return 是否真的改动了 NBT（没改就不写，免得每 tick 都触发一次物品同步）
     */
    public static boolean installStaticGroups(ItemStack stack) {
        Map<String, List<String>> statics = TerminalStaticGroups.groups();
        if (statics.isEmpty()) return false;
        CompoundTag root = root(stack, false);
        CompoundTag existing = root == null ? null : root.getCompound(PLAN);
        Map<String, List<String>> merged = mergedGroups(existing, statics);
        if (existing != null && readGroups(existing).equals(merged)) return false;
        if (root == null) root = root(stack, true);
        CompoundTag plan = existing == null ? new CompoundTag() : existing.copy();
        writeGroups(plan, merged);
        Objects.requireNonNull(root).put(PLAN, plan);
        return true;
    }

    /** 静态组与已有组合并：已有的优先（同键就是同一组候选，不用替换）。 */
    private static Map<String, List<String>> mergedGroups(@Nullable CompoundTag plan, Map<String, List<String>> statics) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        if (plan != null) merged.putAll(readGroups(plan));
        statics.forEach(merged::putIfAbsent);
        return merged;
    }

    /** 读 {@code plan} 里的全部组（key → 候选 id，不按档数过滤）。 */
    public static Map<String, List<String>> readGroups(@Nullable CompoundTag plan) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        if (plan == null || !plan.contains(PLAN_GROUPS, Tag.TAG_LIST)) return groups;
        ListTag list = plan.getList(PLAN_GROUPS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ListTag ids = entry.getList(GROUP_CANDIDATES, Tag.TAG_STRING);
            List<String> candidates = new ArrayList<>();
            for (int j = 0; j < ids.size(); j++) candidates.add(ids.getString(j));
            groups.put(entry.getString(GROUP_KEY), candidates);
        }
        return groups;
    }

    /** 终端 NBT 里当前记录的分级组（key → 候选 id）—— 静态组 + 上次扫描的组。 */
    public static Map<String, List<String>> plannedGroups(ItemStack stack) {
        CompoundTag root = root(stack, false);
        return readGroups(root == null ? null : root.getCompound(PLAN));
    }

    private static void writeGroups(CompoundTag plan, Map<String, List<String>> groups) {
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
    }

    /**
     * 记录一次规划：控制器位置 + 各分级组候选。
     *
     * <p>⚠️ 这里是「扫描结果」覆盖 {@code plan} 的唯一入口：分组 = <b>这次的扫描结果 ∪ 静态组</b>。
     * 语义与原来「整个替换成扫描结果」相比只有两点不同：
     * <ul>
     *   <li>{@link TerminalStaticGroups} 的 6 类静态组不会被扫描结果清掉（面板要一直有这几类可选）；</li>
     *   <li>上一次扫描留下的、这次没再出现的组会被丢掉（和原来的替换语义一致，不留陈旧分组）。</li>
     * </ul>
     */
    public static void cachePlan(ItemStack stack, BlockPos controller, ResourceLocation dimension,
                                 Map<String, List<String>> groups) {
        CompoundTag root = Objects.requireNonNull(root(stack, true));
        CompoundTag plan = root.contains(PLAN, Tag.TAG_COMPOUND) ? root.getCompound(PLAN).copy() : new CompoundTag();
        plan.putString(PLAN_DIM, dimension.toString());
        plan.putInt(PLAN_X, controller.getX());
        plan.putInt(PLAN_Y, controller.getY());
        plan.putInt(PLAN_Z, controller.getZ());

        Map<String, List<String>> merged = new LinkedHashMap<>(groups);
        TerminalStaticGroups.groups().forEach(merged::putIfAbsent);
        writeGroups(plan, merged);
        root.put(PLAN, plan);
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
