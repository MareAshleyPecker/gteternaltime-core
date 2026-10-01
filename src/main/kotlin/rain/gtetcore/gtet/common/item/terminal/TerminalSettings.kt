package rain.gtetcore.gtet.common.item.terminal

import com.gregtechceu.gtceu.common.block.CoilBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings.resolve

/**
 * 高级终端的设置（存在物品 NBT 里）。
 *
 * 两类内容：
 *
 * - AE 链接：一个指向**无线接入点**的 [GlobalPos]（与 AE2 无线终端同一套思路，
 *   但本终端自己解析网络，不依赖背包里带 AE 无线终端）；
 * - 分级组偏好：[StructureBuildPlanner.Plan.groups] 里每个组选了哪个方块
 *   （组键 → 物品注册名）。线圈、聚变玻璃、火箱、分级机壳都走这一套。
 *
 * @author rain fox
 */
object TerminalSettings {

    private const val ROOT = "gtet_terminal"
    private const val AE_LINK = "ae_link"
    private const val PREFS = "group_prefs"
    private const val PREF_KEY = "group"
    private const val PREF_ITEM = "item"
    private const val UI_GROUP = "ui_group"

    private fun root(stack: ItemStack, create: Boolean): CompoundTag? {
        var tag = stack.tag
        if (tag == null) {
            if (!create) return null
            tag = CompoundTag()
            stack.tag = tag
        }
        if (!tag.contains(ROOT, Tag.TAG_COMPOUND.toInt())) {
            if (!create) return null
            tag.put(ROOT, CompoundTag())
        }
        return tag.getCompound(ROOT)
    }

    // ======================== AE 链接 ========================

    /** 绑定到某个无线接入点。 */
    @JvmStatic
    fun linkAe(stack: ItemStack, pos: GlobalPos) {
        val link = CompoundTag()
        link.putString("dim", pos.dimension().location().toString())
        link.putInt("x", pos.pos().x)
        link.putInt("y", pos.pos().y)
        link.putInt("z", pos.pos().z)
        root(stack, true)!!.put(AE_LINK, link)
    }

    /** 解绑。 */
    @JvmStatic
    fun unlinkAe(stack: ItemStack) {
        val tag = root(stack, false)
        tag?.remove(AE_LINK)
    }

    /** 当前绑定的无线接入点位置；未绑定时为 null。 */
    @JvmStatic
    fun getAeLink(stack: ItemStack): GlobalPos? {
        val tag = root(stack, false)
        if (tag == null || !tag.contains(AE_LINK, Tag.TAG_COMPOUND.toInt())) return null
        val link = tag.getCompound(AE_LINK)
        val dimension = ResourceLocation.tryParse(link.getString("dim")) ?: return null
        return GlobalPos.of(
            ResourceKey.create(Registries.DIMENSION, dimension),
            BlockPos(link.getInt("x"), link.getInt("y"), link.getInt("z"))
        )
    }

    /** 是否已绑定 AE 网络。 */
    @JvmStatic
    fun hasAeLink(stack: ItemStack): Boolean {
        return getAeLink(stack) != null
    }

    // ======================== 分级组偏好 ========================

    /** 读取全部组偏好。 */
    @JvmStatic
    fun getPreferences(stack: ItemStack): Map<String, String> {
        val prefs = LinkedHashMap<String, String>()
        val tag = root(stack, false)
        if (tag == null || !tag.contains(PREFS, Tag.TAG_LIST.toInt())) return prefs
        val list = tag.getList(PREFS, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            prefs[entry.getString(PREF_KEY)] = entry.getString(PREF_ITEM)
        }
        return prefs
    }

    /** 为某个分级组选定方块（传 null 表示恢复默认）。 */
    @JvmStatic
    fun setPreference(stack: ItemStack, groupKey: String, itemId: String?) {
        val prefs = LinkedHashMap(getPreferences(stack))
        if (itemId == null) {
            prefs.remove(groupKey)
        } else {
            prefs[groupKey] = itemId
        }
        val list = ListTag()
        prefs.forEach { (key, value) ->
            val entry = CompoundTag()
            entry.putString(PREF_KEY, key)
            entry.putString(PREF_ITEM, value)
            list.add(entry)
        }
        root(stack, true)!!.put(PREFS, list)
    }

    /** 清空全部组偏好。 */
    @JvmStatic
    fun clearPreferences(stack: ItemStack) {
        val tag = root(stack, false)
        tag?.remove(PREFS)
    }

    /**
     * 按偏好在这格的候选里挑一个方块；没设偏好就用第一个候选。
     *
     * ⚠️ 每次调用都会重读一遍物品 NBT。逐格调用（一次搭建几百格）请改用
     * [resolve] 的三参重载：把偏好表与组表在循环外读一次。
     */
    @JvmStatic
    fun resolve(terminal: ItemStack, slot: StructureBuildPlanner.Slot): ItemStack {
        return resolve(slot, getPreferences(terminal), plannedGroups(terminal))
    }

    /**
     * 同 `resolve(terminal, slot)`，但偏好表与已规划组表由调用方
     * 预先读好传进来 —— 一次搭建里只读一次 NBT。
     */
    @JvmStatic
    fun resolve(slot: StructureBuildPlanner.Slot, prefs: Map<String, String>,
                planned: Map<String, List<String>>): ItemStack {
        if (slot.candidates.isEmpty()) return ItemStack.EMPTY
        val wanted = lookupPreference(slot.groupKey, slot.candidates, prefs, planned)
        val hit = findById(slot.candidates, wanted)
        if (hit != null) return hit
        // 偏好不在这一格的候选里：只有「整格候选都是线圈」才把它补回来。
        // 线圈谓词本来就接受任何一级线圈（搭建侧按线圈等级砍过候选，见 AdvancedTerminalBuilder），
        // 补回来是安全的；其它情况一律回退第一档：把谓词不接受的方块放到那格里只会让结构永远不成型。
        if (wanted != null && allCoils(slot.candidates)) {
            val extra = StructureBuildPlanner.itemStackOf(wanted)
            if (extra != null && !extra.isEmpty) return extra
        }
        return slot.candidates[0]
    }

    // ======================== 右下那块面板当前显示哪一组 ========================

    /** 右下「分级方块（勾选）」当前显示哪一组（组键）；没设过时为 null。 */
    @JvmStatic
    fun getUiGroup(stack: ItemStack): String? {
        val tag = root(stack, false)
        if (tag == null || !tag.contains(UI_GROUP, Tag.TAG_STRING.toInt())) return null
        return tag.getString(UI_GROUP)
    }

    /** 写「右下显示哪一组」；传 null 等于清掉（界面会退回第 1 组）。 */
    @JvmStatic
    fun setUiGroup(stack: ItemStack, groupKey: String?) {
        val tag = root(stack, true)!!
        if (groupKey == null) {
            tag.remove(UI_GROUP)
        } else {
            tag.putString(UI_GROUP, groupKey)
        }
    }

    // ======================== 组键 → 偏好 ========================

    /**
     * 找出「这一格该用哪一档」的偏好物品 id（注册名）；没有可用偏好时返回 `null`。
     *
     * **第一步：组键精确命中** —— 面板 / 扫描 / 搭建三处用同一套
     * [StructureBuildPlanner.groupKey] 算键，键一样就直接取。
     *
     * ⚠️ **第二步：候选集回退**（这一步是必须的，否则「面板里选了却不生效」）。
     * 静态表（[TerminalStaticGroups]）与结构扫描谓词给的候选集不保证完全一致：
     * 搭建侧按「线圈等级」收窄过候选时，它的键与面板里那一组的键就不相等。
     * 回退规则（按「同类」判定，避免误配到别的格）：
     *
     * - 偏好所属的组（`plan.groups` 里的候选集 `G`）与本格候选集 `S`
     *   必须有交集，**且**满足 `G ⊆ S` 或 `S ⊆ G`；
     * - 满足多个时取交集最大的那个（并列取 NBT 里的先后顺序，保证确定性）。
     *
     * 为什么要那个「包含关系」的闸：光看「偏好物品在不在 `S` 里」会把
     * 「能源仓那一组选的档」误配到别的也接受仓室的位置上（例如某个笼统的「任意仓室」格）。
     * 加上包含关系之后，只有「同一类部件的更具体 / 更宽泛版本」才会命中 ——
     * 例如线圈（`S` 缺一档，`S ⊆ G`）与只收某一档的仓室格（`S ⊆ G`）。
     *
     * ⚠️ 两条都取不到就返回 `null`（不猜）：偏好的组键在 `plan.groups` 里找不到
     * （例如旧 NBT 里留下的、已被后来扫描覆盖掉的组）时不做回退匹配。
     */
    @JvmStatic
    fun lookupPreference(terminal: ItemStack, groupKey: String?, candidates: List<ItemStack>): String? {
        return lookupPreference(groupKey, candidates, getPreferences(terminal), plannedGroups(terminal))
    }

    /** 同 `lookupPreference(terminal, groupKey, candidates)`，但偏好表与已规划组表由调用方预先读好。 */
    @JvmStatic
    fun lookupPreference(groupKey: String?, candidates: List<ItemStack>,
                         prefs: Map<String, String>, planned: Map<String, List<String>>): String? {
        if (candidates.isEmpty()) return null
        if (prefs.isEmpty()) return null

        val slotIds = idsOf(candidates)
        if (groupKey != null) {
            val wanted = prefs[groupKey]
            if (wanted != null && slotIds.contains(wanted)) return wanted
        }

        if (planned.isEmpty()) return null
        var best: String? = null
        var bestOverlap = -1
        for ((key, value) in prefs) {
            val group = planned[key]
            if (group == null || group.size < 2) continue
            val groupIds: Set<String> = LinkedHashSet(group)
            var overlap = 0
            for (id in groupIds) {
                if (slotIds.contains(id)) overlap++
            }
            if (overlap == 0) continue
            val related = slotIds.containsAll(groupIds) || groupIds.containsAll(slotIds)
            if (!related) continue
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = value
            }
        }
        return best
    }

    // ======================== 小工具 ========================

    /** 候选里的物品 id 集合。 */
    private fun idsOf(candidates: List<ItemStack>): Set<String> {
        val ids = LinkedHashSet<String>()
        for (candidate in candidates) {
            val id = StructureBuildPlanner.itemId(candidate)
            if (id != null) ids.add(id)
        }
        return ids
    }

    /** 候选里注册名等于 `itemId` 的那一档；没有就返回 null。 */
    private fun findById(candidates: List<ItemStack>, itemId: String?): ItemStack? {
        if (itemId == null) return null
        for (candidate in candidates) {
            if (itemId == StructureBuildPlanner.itemId(candidate)) return candidate
        }
        return null
    }

    /** 整格候选是不是全是线圈（见 `resolve` 里「把最后一档补回来」那条的准入条件）。 */
    private fun allCoils(candidates: List<ItemStack>): Boolean {
        for (candidate in candidates) {
            val blockItem = candidate.item as? BlockItem ?: return false
            if (blockItem.block !is CoilBlock) return false
        }
        return candidates.isNotEmpty()
    }

    // ======================== 上次规划（供界面显示分级组、供"开始搭建"复用） ========================

    private const val PLAN = "plan"
    private const val PLAN_DIM = "dim"
    private const val PLAN_X = "x"
    private const val PLAN_Y = "y"
    private const val PLAN_Z = "z"
    private const val PLAN_GROUPS = "groups"
    private const val GROUP_KEY = "key"
    private const val GROUP_CANDIDATES = "candidates"

    /**
     * 界面上展示的一个分级组。
     *
     * 分量访问器用 `@get:JvmName` 保持与 Java record 同名，Java 调用方无需改动。
     */
    data class GroupView(
        @get:JvmName("key") val key: String,
        @get:JvmName("candidates") val candidates: List<String>,
        @get:JvmName("chosen") val chosen: String,
    )

    /**
     * 把 [TerminalStaticGroups] 的 6 类静态组预置进终端 NBT，让界面**不扫描**也能列出可选部件。
     *
     * ⚠️ **只在服务端调**（见 [TerminalGroupSeeder]）：客户端写自己背包物品的 NBT
     * 不会同步回服务端，写了也白写，还会让两端界面用不同的数据建树。
     *
     * 合并规则（两条都必要）：
     *
     * - 已有的组（上次扫描出来的真实分级组）原样保留 —— 静态组与它们**共存**；
     * - 已有的组偏好 `group_prefs` **一个都不动**（本方法只写 `plan`）。
     *
     * @return 是否真的改动了 NBT（没改就不写，免得每 tick 都触发一次物品同步）
     */
    @JvmStatic
    fun installStaticGroups(stack: ItemStack): Boolean {
        val statics = TerminalStaticGroups.groups()
        if (statics.isEmpty()) return false
        var rootTag = root(stack, false)
        val existing = rootTag?.getCompound(PLAN)
        val merged = mergedGroups(existing, statics)
        if (existing != null && readGroups(existing) == merged) return false
        if (rootTag == null) rootTag = root(stack, true)
        val plan = if (existing == null) CompoundTag() else existing.copy()
        writeGroups(plan, merged)
        rootTag!!.put(PLAN, plan)
        return true
    }

    /** 静态组与已有组合并：已有的优先（同键就是同一组候选，不用替换）。 */
    private fun mergedGroups(plan: CompoundTag?, statics: Map<String, List<String>>): Map<String, List<String>> {
        val merged = LinkedHashMap<String, List<String>>()
        if (plan != null) merged.putAll(readGroups(plan))
        statics.forEach { (key, value) -> merged.putIfAbsent(key, value) }
        return merged
    }

    /** 读 `plan` 里的全部组（key → 候选 id，不按档数过滤）。 */
    @JvmStatic
    fun readGroups(plan: CompoundTag?): Map<String, List<String>> {
        val groups = LinkedHashMap<String, List<String>>()
        if (plan == null || !plan.contains(PLAN_GROUPS, Tag.TAG_LIST.toInt())) return groups
        val list = plan.getList(PLAN_GROUPS, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            val ids = entry.getList(GROUP_CANDIDATES, Tag.TAG_STRING.toInt())
            val candidates = ArrayList<String>()
            for (j in 0 until ids.size) candidates.add(ids.getString(j))
            groups[entry.getString(GROUP_KEY)] = candidates
        }
        return groups
    }

    /** 终端 NBT 里当前记录的分级组（key → 候选 id）—— 静态组 + 上次扫描的组。 */
    @JvmStatic
    fun plannedGroups(stack: ItemStack): Map<String, List<String>> {
        val rootTag = root(stack, false)
        return readGroups(rootTag?.getCompound(PLAN))
    }

    private fun writeGroups(plan: CompoundTag, groups: Map<String, List<String>>) {
        val list = ListTag()
        groups.forEach { (key, candidates) ->
            val entry = CompoundTag()
            entry.putString(GROUP_KEY, key)
            val ids = ListTag()
            for (id in candidates) ids.add(StringTag.valueOf(id))
            entry.put(GROUP_CANDIDATES, ids)
            list.add(entry)
        }
        plan.put(PLAN_GROUPS, list)
    }

    /**
     * 记录一次规划：控制器位置 + 各分级组候选。
     *
     * ⚠️ 这里是「扫描结果」覆盖 `plan` 的唯一入口：分组 = **这次的扫描结果 ∪ 静态组**。
     * 语义与原来「整个替换成扫描结果」相比只有两点不同：
     *
     * - [TerminalStaticGroups] 的 6 类静态组不会被扫描结果清掉（面板要一直有这几类可选）；
     * - 上一次扫描留下的、这次没再出现的组会被丢掉（和原来的替换语义一致，不留陈旧分组）。
     *
     * ⚠️ 内容与控制器位置都没变时**一个字节都不写**：写 NBT 会触发一次物品同步，
     * 每次 Shift+右键都白发一次没必要。
     */
    @JvmStatic
    fun cachePlan(stack: ItemStack, controller: BlockPos, dimension: ResourceLocation,
                  groups: Map<String, List<String>>) {
        val rootTag = root(stack, true)!!

        val merged = LinkedHashMap<String, List<String>>(groups)
        TerminalStaticGroups.groups().forEach { (key, value) -> merged.putIfAbsent(key, value) }

        val existing = if (rootTag.contains(PLAN, Tag.TAG_COMPOUND.toInt())) rootTag.getCompound(PLAN) else null
        if (existing != null &&
            dimension.toString() == existing.getString(PLAN_DIM) &&
            controller.x == existing.getInt(PLAN_X) &&
            controller.y == existing.getInt(PLAN_Y) &&
            controller.z == existing.getInt(PLAN_Z) &&
            readGroups(existing) == merged
        ) {
            return
        }

        val plan = if (existing == null) CompoundTag() else existing.copy()
        plan.putString(PLAN_DIM, dimension.toString())
        plan.putInt(PLAN_X, controller.x)
        plan.putInt(PLAN_Y, controller.y)
        plan.putInt(PLAN_Z, controller.z)

        writeGroups(plan, merged)
        rootTag.put(PLAN, plan)
    }

    /** 上次规划涉及的控制器位置。 */
    @JvmStatic
    fun getPlanTarget(stack: ItemStack): GlobalPos? {
        val tag = root(stack, false)
        if (tag == null || !tag.contains(PLAN, Tag.TAG_COMPOUND.toInt())) return null
        val plan = tag.getCompound(PLAN)
        val dimension = ResourceLocation.tryParse(plan.getString(PLAN_DIM)) ?: return null
        return GlobalPos.of(
            ResourceKey.create(Registries.DIMENSION, dimension),
            BlockPos(plan.getInt(PLAN_X), plan.getInt(PLAN_Y), plan.getInt(PLAN_Z))
        )
    }

    @JvmStatic
    fun hasPlan(stack: ItemStack): Boolean {
        return getPlanTarget(stack) != null
    }

    /** 上次规划里的分级组（只有多候选的组会进这个列表）。 */
    @JvmStatic
    fun cachedGroups(stack: ItemStack): List<GroupView> {
        val groups = ArrayList<GroupView>()
        val tag = root(stack, false)
        if (tag == null || !tag.contains(PLAN, Tag.TAG_COMPOUND.toInt())) return groups
        val plan = tag.getCompound(PLAN)
        if (!plan.contains(PLAN_GROUPS, Tag.TAG_LIST.toInt())) return groups

        val prefs = getPreferences(stack)
        val list = plan.getList(PLAN_GROUPS, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            val key = entry.getString(GROUP_KEY)
            val candidates = ArrayList<String>()
            val ids = entry.getList(GROUP_CANDIDATES, Tag.TAG_STRING.toInt())
            for (j in 0 until ids.size) candidates.add(ids.getString(j))
            if (candidates.size < 2) continue
            groups.add(GroupView(key, candidates, prefs[candidates[0]] ?: candidates[0]))
        }
        return groups
    }

    /** 在某个分级组里循环切换到下一个候选。 */
    @JvmStatic
    fun cycleChoice(stack: ItemStack, group: GroupView) {
        val candidates = group.candidates
        if (candidates.isEmpty()) return
        val index = candidates.indexOf(group.chosen)
        val next = candidates[(index + 1) % candidates.size]
        setPreference(stack, group.key, next)
    }
}