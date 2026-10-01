package rain.gtetcore.gtet.common.item.terminal

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.common.block.CoilBlock
import com.gregtechceu.gtceu.common.data.GTMachines
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import rain.gtetcore.gtet.common.data.machine.ALLSmachine
import rain.gtetcore.gtet.common.item.terminal.TerminalStaticGroups.stacks

/**
 * 高级终端右侧两块列表面板**默认**列出的 6 类可选部件（不依赖扫描）。
 *
 * 为什么要有这张静态表：面板数据原来只来自「上次 Shift+右键控制器扫描出来的分级组」
 * （`gtet_terminal.plan.groups`），没扫过结构就只显示「先 Shift+右键控制器扫描结构」。
 * 这张表让面板一打开就能直接列出这几类候选，玩家不必先扫一次：
 *
 * | 面板上的组 | 候选来源 |
 * | --- | --- |
 * | 线圈 | [GTCEuAPI.HEATING_COILS] 的各级线圈方块（按等级排序，与 GTCEu `Predicates.heatingCoils()` 同序） |
 * | 能源仓 | [GTMachines.ENERGY_INPUT_HATCH] / `_4A` / `_16A` / [GTMachines.SUBSTATION_ENERGY_INPUT_HATCH] |
 * | 超频仓 | [ALLSmachine.OVERCLOCK_HATCHES] |
 * | 线程仓 | [ALLSmachine.THREAD_HATCHES] |
 * | 并行仓 | [ALLSmachine.PARALLEL_HATCHES]（GTET 自己的分级并行仓，IV ~ MAX） |
 * | 维护仓 | [GTMachines.MAINTENANCE_HATCH] / `CONFIGURABLE_MAINTENANCE_HATCH` / `CLEANING_MAINTENANCE_HATCH` / `AUTO_MAINTENANCE_HATCH` |
 *
 * **顺序**就是面板上的行序（线圈 / 能源仓 / 超频仓 / 线程仓 / 并行仓 / 维护仓）：
 * GTET 自己的三种分级仓（超频 / 线程 / 并行）排在一起、维护仓垫底。
 *
 * 输入/输出总线、输入/输出仓**故意不在表里**（用户明确要求：这两类不需要出现在列表里）。
 *
 * ⚠️ **组键必须和结构扫描用同一套算法**（[StructureBuildPlanner.groupKey]：把候选物品 id
 * 排序后拼起来）。面板是按组键把玩家的选择写进 `group_prefs` 的，搭建时再按键取用 ——
 * 两边算出来的键不一样，玩家在面板里选了就等于没选。这张表算出来的键与「同一套候选被扫描出来时」
 * 的键天然相同；但静态表与谓词给的候选集**并不保证完全一致**（典型是线圈：
 * GTCEu 的 `Predicates.heatingCoils()` 给全部线圈，而自动搭建组装候选时会砍掉最后一档），所以
 * [TerminalSettings.lookupPreference] 还有一层「按候选集包含关系回退匹配」的兜底。
 *
 * @author rain fox
 */
object TerminalStaticGroups {

    /** 静态表的构建结果（懒加载 + 缓存）。 */
    private var cache: LinkedHashMap<String, List<ItemStack>>? = null

    /** 「6 类都齐了才算建好」的那个数（见 [stacks] 里的 ⚠️）。 */
    private const val CATEGORY_COUNT = 6

    /**
     * 6 类部件的候选（组键 → 物品 id），写进终端 NBT 的 `gtet_terminal.plan.groups`。
     *
     * 返回的顺序就是界面上的显示顺序（线圈 / 能源仓 / 超频仓 / 线程仓 / 并行仓 / 维护仓）；
     * 每个组内部的候选顺序 = 上表里的来源顺序（线圈按等级、仓按档位）。
     */
    @JvmStatic
    @Synchronized
    fun groups(): Map<String, List<String>> {
        val groups = LinkedHashMap<String, List<String>>()
        for (entry in stacks()) {
            val ids = idsOf(entry.value)
            // 只有多档的组才进面板（与 TerminalSettings.cachedGroups / 补丁 TierGroups.read 的过滤一致）
            if (ids.size > 1) groups[entry.key] = ids
        }
        return groups
    }

    /** 组键 → 候选物品栈。 */
    @JvmStatic
    @Synchronized
    fun stacks(): Map<String, List<ItemStack>> {
        // ⚠️ 只在「6 类都齐」时才缓存：本类可能在注册还没跑完的时候被第一次调用
        //    （例如 GTET 的机器注册窗口之前），那时超频仓/线程仓/并行仓还是空表；
        //    缓存住空表就永远补不回来了，所以拿不齐就每次重算。
        val cached = cache
        if (cached != null) return cached
        val built = build()
        if (built.size >= CATEGORY_COUNT) cache = built
        return built
    }

    // ======================== 各类候选 ========================

    private fun build(): LinkedHashMap<String, List<ItemStack>> {
        val all = LinkedHashMap<String, List<ItemStack>>()
        addIfTiered(all, coils())
        addIfTiered(all, energyHatches())
        addIfTiered(all, definitions(ALLSmachine.OVERCLOCK_HATCHES))
        addIfTiered(all, definitions(ALLSmachine.THREAD_HATCHES))
        addIfTiered(all, definitions(ALLSmachine.PARALLEL_HATCHES))
        addIfTiered(all, maintenanceHatches())
        return all
    }

    private fun addIfTiered(all: LinkedHashMap<String, List<ItemStack>>, stacks: List<ItemStack>) {
        if (stacks.size > 1) all[StructureBuildPlanner.groupKey(stacks)] = stacks
    }

    /** 线圈：与 GTCEu `Predicates.heatingCoils()` 同样按等级升序。 */
    private fun coils(): MutableList<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        GTCEuAPI.HEATING_COILS.entries
            .sortedBy { it.key.tier }
            .forEach { entry ->
                val coil: CoilBlock? = entry.value?.get()
                if (coil != null) addBlock(stacks, coil)
            }
        return stacks
    }

    /** 能源仓：分级能源仓（2A）、4A、16A、以及变电站用的 64A 能源仓。 */
    private fun energyHatches(): MutableList<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        addDefinitions(stacks, GTMachines.ENERGY_INPUT_HATCH)
        addDefinitions(stacks, GTMachines.ENERGY_INPUT_HATCH_4A)
        addDefinitions(stacks, GTMachines.ENERGY_INPUT_HATCH_16A)
        addDefinitions(stacks, GTMachines.SUBSTATION_ENERGY_INPUT_HATCH)
        return stacks
    }

    /**
     * 维护仓：普通 / 可配置（= 用户说的「配置仓」）/ 自动清理 / 自动维护。
     *
     * 对应 GTCEu 那一条 `Predicates.abilities(PartAbility.MAINTENANCE)`：多方块里的
     * 「维护仓那一格」接的就是这几个方块。
     */
    private fun maintenanceHatches(): MutableList<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        addDefinition(stacks, GTMachines.MAINTENANCE_HATCH)
        addDefinition(stacks, GTMachines.CONFIGURABLE_MAINTENANCE_HATCH)
        addDefinition(stacks, GTMachines.CLEANING_MAINTENANCE_HATCH)
        addDefinition(stacks, GTMachines.AUTO_MAINTENANCE_HATCH)
        return stacks
    }

    // ======================== 小工具 ========================

    /**
     * 把 [MachineDefinition] 换成物品栈。
     *
     * ⚠️ 分级注册的数组里**有空位**（没登记的档位是 `null`，例如 4A/16A 仓只从 EV 起、
     * 高 Tier 关闭时更是大片空），必须逐个判空。
     */
    private fun definitions(definitions: List<MachineDefinition>?): MutableList<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        if (definitions == null) return stacks
        for (definition in definitions) addDefinition(stacks, definition)
        return stacks
    }

    private fun addDefinitions(stacks: MutableList<ItemStack>, definitions: Array<MachineDefinition>?) {
        if (definitions == null) return
        for (definition in definitions) addDefinition(stacks, definition)
    }

    private fun addDefinition(stacks: MutableList<ItemStack>, definition: MachineDefinition?) {
        if (definition == null) return
        val stack = try {
            definition.asStack()
        } catch (ignored: Throwable) {
            // 物品还没注册（理论上不会；拿不到就当这一档不存在，别让面板整块挂掉）
            return
        }
        if (!stack.isEmpty) addStack(stacks, stack)
    }

    private fun addBlock(stacks: MutableList<ItemStack>, block: Block) {
        val stack = ItemStack(block.asItem())
        if (!stack.isEmpty) addStack(stacks, stack)
    }

    private fun addStack(stacks: MutableList<ItemStack>, stack: ItemStack) {
        val id = StructureBuildPlanner.itemId(stack) ?: return
        for (existing in stacks) {
            if (id == StructureBuildPlanner.itemId(existing)) return   // 去重（同一档只出现一次）
        }
        stacks.add(stack.copy())
    }

    private fun idsOf(stacks: List<ItemStack>): MutableList<String> {
        val ids = ArrayList<String>()
        for (stack in stacks) {
            val id = StructureBuildPlanner.itemId(stack)
            if (id != null) ids.add(id)
        }
        return ids
    }
}