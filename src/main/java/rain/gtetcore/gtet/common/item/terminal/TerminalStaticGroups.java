package rain.gtetcore.gtet.common.item.terminal;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.common.block.CoilBlock;
import com.gregtechceu.gtceu.common.data.GTMachines;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import rain.gtetcore.gtet.common.data.machine.samplemachine.ALLSmahine;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高级终端右侧两块列表面板<b>默认</b>列出的 5 类可选部件（不依赖扫描）。
 *
 * <p>为什么要有这张静态表：面板数据原来只来自「上次 Shift+右键控制器扫描出来的分级组」
 * （{@code gtet_terminal.plan.groups}），没扫过结构就只显示「先 Shift+右键控制器扫描结构」。
 * 这张表让面板一打开就能直接列出这几类候选，玩家不必先扫一次：
 *
 * <table>
 * <caption>5 类与候选来源</caption>
 * <tr><th>面板上的组</th><th>候选来源</th></tr>
 * <tr><td>线圈</td><td>{@link GTCEuAPI#HEATING_COILS} 的各级线圈方块（按等级排序，与
 * GTCEu {@code Predicates.heatingCoils()} 同序）</td></tr>
 * <tr><td>能源仓</td><td>{@link GTMachines#ENERGY_INPUT_HATCH} / {@code _4A} / {@code _16A} /
 * {@link GTMachines#SUBSTATION_ENERGY_INPUT_HATCH}</td></tr>
 * <tr><td>超频仓</td><td>{@link ALLSmahine#getOVERCLOCK_HATCHES()}</td></tr>
 * <tr><td>线程仓</td><td>{@link ALLSmahine#getTHREAD_HATCHES()}</td></tr>
 * <tr><td>维护仓</td><td>{@link GTMachines#MAINTENANCE_HATCH} / {@code CONFIGURABLE_MAINTENANCE_HATCH} /
 * {@code CLEANING_MAINTENANCE_HATCH} / {@code AUTO_MAINTENANCE_HATCH}</td></tr>
 * </table>
 *
 * <p>输入/输出总线、输入/输出仓<b>故意不在表里</b>（用户明确要求：这两类不需要出现在列表里）。
 *
 * <p>⚠️ <b>组键必须和结构扫描用同一套算法</b>（{@link StructureBuildPlanner#groupKey}：把候选物品 id
 * 排序后拼起来）。面板是按组键把玩家的选择写进 {@code group_prefs} 的，搭建时再按键取用 ——
 * 两边算出来的键不一样，玩家在面板里选了就等于没选。这张表算出来的键与「同一套候选被扫描出来时」
 * 的键天然相同；但静态表与谓词给的候选集**并不保证完全一致**（典型是线圈：
 * GTCEu 的 {@code Predicates.heatingCoils()} 给全部线圈，而 GTMThings 的
 * {@code AutoBuildSetting#apply} 组装候选时会砍掉最后一档），所以
 * {@link TerminalSettings#lookupPreference} 还有一层「按候选集包含关系回退匹配」的兜底。
 *
 * @author rain fox
 */
public final class TerminalStaticGroups {

    /** 静态表的构建结果（懒加载 + 缓存）。 */
    private static Map<String, List<ItemStack>> cache;

    private TerminalStaticGroups() {}

    /**
     * 5 类部件的候选（组键 → 物品 id），写进终端 NBT 的 {@code gtet_terminal.plan.groups}。
     *
     * <p>返回的顺序就是界面上的显示顺序（线圈 / 能源仓 / 超频仓 / 线程仓 / 维护仓）；
     * 每个组内部的候选顺序 = 上表里的来源顺序（线圈按等级、仓按档位）。
     */
    public static synchronized Map<String, List<String>> groups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<ItemStack>> entry : stacks().entrySet()) {
            List<String> ids = idsOf(entry.getValue());
            // 只有多档的组才进面板（与 TerminalSettings.cachedGroups / 补丁 TierGroups.read 的过滤一致）
            if (ids.size() > 1) groups.put(entry.getKey(), ids);
        }
        return groups;
    }

    /** 组键 → 候选物品栈。 */
    public static synchronized Map<String, List<ItemStack>> stacks() {
        if (cache == null) {
            Map<String, List<ItemStack>> built = build();
            // ⚠️ 只在「5 类都齐」时才缓存：本类可能在注册还没跑完的时候被第一次调用
            //    （例如 GTET 的机器注册窗口之前），那时超频仓/线程仓还是空表；
            //    缓存住空表就永远补不回来了，所以拿不齐就每次重算。
            if (built.size() >= 5) cache = built;
            return built;
        }
        return cache;
    }

    // ======================== 各类候选 ========================

    private static Map<String, List<ItemStack>> build() {
        Map<String, List<ItemStack>> all = new LinkedHashMap<>();
        addIfTiered(all, coils());
        addIfTiered(all, energyHatches());
        addIfTiered(all, definitions(ALLSmahine.INSTANCE.getOVERCLOCK_HATCHES()));
        addIfTiered(all, definitions(ALLSmahine.INSTANCE.getTHREAD_HATCHES()));
        addIfTiered(all, maintenanceHatches());
        return all;
    }

    private static void addIfTiered(Map<String, List<ItemStack>> all, List<ItemStack> stacks) {
        if (stacks.size() > 1) all.put(StructureBuildPlanner.groupKey(stacks), stacks);
    }

    /** 线圈：与 GTCEu {@code Predicates.heatingCoils()} 同样按等级升序。 */
    private static List<ItemStack> coils() {
        List<ItemStack> stacks = new ArrayList<>();
        GTCEuAPI.HEATING_COILS.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().getTier()))
                .forEach(entry -> {
                    CoilBlock coil = entry.getValue() == null ? null : entry.getValue().get();
                    if (coil != null) addBlock(stacks, coil);
                });
        return stacks;
    }

    /** 能源仓：分级能源仓（2A）、4A、16A、以及变电站用的 64A 能源仓。 */
    private static List<ItemStack> energyHatches() {
        List<ItemStack> stacks = new ArrayList<>();
        addDefinitions(stacks, GTMachines.ENERGY_INPUT_HATCH);
        addDefinitions(stacks, GTMachines.ENERGY_INPUT_HATCH_4A);
        addDefinitions(stacks, GTMachines.ENERGY_INPUT_HATCH_16A);
        addDefinitions(stacks, GTMachines.SUBSTATION_ENERGY_INPUT_HATCH);
        return stacks;
    }

    /**
     * 维护仓：普通 / 可配置（= 用户说的「配置仓」）/ 自动清理 / 自动维护。
     *
     * <p>对应 GTCEu 那一条 {@code Predicates.abilities(PartAbility.MAINTENANCE)}：多方块里的
     * 「维护仓那一格」接的就是这几个方块。
     */
    private static List<ItemStack> maintenanceHatches() {
        List<ItemStack> stacks = new ArrayList<>();
        addDefinition(stacks, GTMachines.MAINTENANCE_HATCH);
        addDefinition(stacks, GTMachines.CONFIGURABLE_MAINTENANCE_HATCH);
        addDefinition(stacks, GTMachines.CLEANING_MAINTENANCE_HATCH);
        addDefinition(stacks, GTMachines.AUTO_MAINTENANCE_HATCH);
        return stacks;
    }

    // ======================== 小工具 ========================

    /**
     * 把 {@link MachineDefinition} 换成物品栈。
     *
     * <p>⚠️ 分级注册的数组里**有空位**（没登记的档位是 {@code null}，例如 4A/16A 仓只从 EV 起、
     * 高 Tier 关闭时更是大片空），必须逐个判空。
     */
    private static List<ItemStack> definitions(List<MachineDefinition> definitions) {
        List<ItemStack> stacks = new ArrayList<>();
        if (definitions == null) return stacks;
        for (MachineDefinition definition : definitions) addDefinition(stacks, definition);
        return stacks;
    }

    private static void addDefinitions(List<ItemStack> stacks, MachineDefinition[] definitions) {
        if (definitions == null) return;
        for (MachineDefinition definition : definitions) addDefinition(stacks, definition);
    }

    private static void addDefinition(List<ItemStack> stacks, @Nullable MachineDefinition definition) {
        if (definition == null) return;
        ItemStack stack;
        try {
            stack = definition.asStack();
        } catch (Throwable ignored) {
            // 物品还没注册（理论上不会；拿不到就当这一档不存在，别让面板整块挂掉）
            return;
        }
        if (!stack.isEmpty()) addStack(stacks, stack);
    }

    private static void addBlock(List<ItemStack> stacks, Block block) {
        ItemStack stack = new ItemStack(block.asItem());
        if (!stack.isEmpty()) addStack(stacks, stack);
    }

    private static void addStack(List<ItemStack> stacks, ItemStack stack) {
        String id = StructureBuildPlanner.itemId(stack);
        if (id == null) return;
        for (ItemStack existing : stacks) {
            if (id.equals(StructureBuildPlanner.itemId(existing))) return;   // 去重（同一档只出现一次）
        }
        stacks.add(stack.copy());
    }

    private static List<String> idsOf(List<ItemStack> stacks) {
        List<String> ids = new ArrayList<>();
        for (ItemStack stack : stacks) {
            String id = StructureBuildPlanner.itemId(stack);
            if (id != null) ids.add(id);
        }
        return ids;
    }
}
