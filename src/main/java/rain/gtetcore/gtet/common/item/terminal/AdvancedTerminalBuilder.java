package rain.gtetcore.gtet.common.item.terminal;

import appeng.api.networking.IGrid;
import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.common.block.CoilBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.Nullable;
import rain.gtetcore.gtet.common.machine.multiblock.modular.ETModularMachine;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * 高级终端的搭建驱动 —— 潜行右键控制器后走完整条「选图案 → 规划 → 放置/拆除」链路。
 *
 * <p>设置只在开头读一次（{@link AdvancedTerminalSettings#read}），偏好表与已规划组表也各读一次，
 * 之后整轮都不再碰物品 NBT。
 *
 * <p>与「只补空格」的规划器的关系：{@link StructureBuildPlanner#planCells} 只在
 * 「线圈替换模式 / 拆除模式」下才把<b>已经有方块</b>的格子也吐出来，其余情况一律只处理空格子。
 *
 * @author rain fox
 */
public final class AdvancedTerminalBuilder {

    /**
     * 单次搭建处理的格子数上限。
     *
     * <p>⚠️ <b>取舍说明</b>：这里<b>不</b>做分 tick 的渐进式搭建 —— 那需要跨 tick 保存
     * 「还没放完的清单」，玩家中途退出 / 区块卸载 / 控制器被拆都会让这份状态悬空，
     * 反而更容易出问题。改成「一次放完，超过上限就整体拒绝并提示」，
     * 上限内是一 tick 完成，超过就让玩家自己分几次搭（或改大这个常量）。
     */
    public static final int MAX_BLOCKS_PER_BUILD = 512;

    private static final Direction[] FACINGS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final Direction[] FACINGS_H = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /**
     * 一次搭建的结果。
     *
     * @param placed   放下的方块数
     * @param removed  拆掉的方块数
     * @param missing  没找到来源（背包 + AE 都没有）的格子数
     * @param rejected 因为超过 {@link #MAX_BLOCKS_PER_BUILD} 而整体没执行
     */
    public record Result(int placed, int removed, int missing, boolean rejected) {

        /** 什么都没做。 */
        public static final Result NONE = new Result(0, 0, 0, false);
    }

    private AdvancedTerminalBuilder() {}

    /**
     * 入口：对控制器执行一次搭建（或拆除）。
     *
     * @param player         操作者
     * @param terminal       手上的高级终端（设置 / 偏好 / AE 链接都在它的 NBT 里）
     * @param controllerPos  被潜行右键的方块位置
     */
    public static Result run(Player player, ItemStack terminal, BlockPos controllerPos) {
        Level level = player.level();
        MetaMachine machine = MetaMachine.getMachine(level, controllerPos);
        // 不是控制器：什么都不做（交互已经被吃掉，见行为组件）
        if (!(machine instanceof IMultiController controller)) return Result.NONE;

        AdvancedTerminalSettings.Snapshot settings = AdvancedTerminalSettings.read(terminal);
        BlockPattern pattern = selectPattern(controller, settings.module());
        // 图案取不到（控制器没有图案 / 读不到图案内部字段）：整次调用什么都不做
        if (pattern == null) return Result.NONE;
        MultiblockState state = controller.getMultiblockState();
        if (state == null) return Result.NONE;

        boolean formed = controller.isFormed();

        // 拆除模式：成型与否都执行，结束后清一次计数缓存，不请求重检
        if (settings.demolition()) {
            Result result = execute(player, terminal, settings, controller, pattern, state, true, false);
            state.clearCache();
            return result;
        }
        // 未成型：直接搭
        if (!formed) {
            return execute(player, terminal, settings, controller, pattern, state, false, false);
        }
        // 已成型 + 线圈替换：搭完额外让部件重新挂载
        if (machine instanceof WorkableMultiblockMachine workable && settings.replaceCoil()) {
            Result result = execute(player, terminal, settings, controller, pattern, state, false, true);
            workable.onPartUnload();
            return result;
        }
        // 已成型又没开线圈替换：整次调用空转
        return Result.NONE;
    }

    /**
     * 选这次的图案。
     *
     * <p>{@code module == 0} 用控制器当前的图案；{@code module == N > 0} 取模块化多方块的第 N 档结构，
     * 取不到（不是模块机 / 档位非法 / 实现内部抛异常）就静默退回主结构。
     */
    @Nullable
    private static BlockPattern selectPattern(IMultiController controller, int module) {
        if (module > 0 && controller instanceof ETModularMachine modular) {
            try {
                BlockPattern tiered = modular.patternForTier(module);
                if (tiered != null) return tiered;
            } catch (Throwable ignored) {
                // 静默退回主结构
            }
        }
        try {
            return controller.getPattern();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ======================== 规划 + 执行 ========================

    private static Result execute(Player player, ItemStack terminal, AdvancedTerminalSettings.Snapshot settings,
                                  IMultiController controller, BlockPattern pattern, MultiblockState state,
                                  boolean demolition, boolean replaceCoil) {
        Level level = player.level();

        boolean includeOccupied = demolition || replaceCoil;
        StructureBuildPlanner.Options options =
                new StructureBuildPlanner.Options(settings.repeatCount(), settings.flip(), includeOccupied);
        StructureBuildPlanner.CellPlan plan = StructureBuildPlanner.planCells(pattern, state, level, options);

        // 设置与偏好都只解析一次（见类注释）
        Map<String, String> prefs = TerminalSettings.getPreferences(terminal);
        Map<String, List<String>> planned = TerminalSettings.plannedGroups(terminal);

        // 扫描写入：这次的规划结果 ∪ 静态组（静态组由 TerminalSettings 自己保证不被清掉）
        TerminalSettings.cachePlan(terminal, state.getPos(), level.dimension().location(), idGroups(plan));

        return demolition
                ? demolish(player, level, plan, state.getPos())
                : place(player, terminal, settings, controller, state, level, plan, prefs, planned, replaceCoil);
    }

    private static Map<String, List<String>> idGroups(StructureBuildPlanner.CellPlan plan) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        plan.groups().forEach((key, group) -> {
            List<String> ids = new ArrayList<>();
            for (ItemStack candidate : group.candidates()) {
                String id = StructureBuildPlanner.itemId(candidate);
                if (id != null) ids.add(id);
            }
            if (!ids.isEmpty()) groups.put(key, ids);
        });
        return groups;
    }

    // ======================== 放置 ========================

    private static Result place(Player player, ItemStack terminal, AdvancedTerminalSettings.Snapshot settings,
                                IMultiController controller, MultiblockState state, Level level,
                                StructureBuildPlanner.CellPlan plan, Map<String, String> prefs,
                                Map<String, List<String>> planned, boolean replaceCoil) {
        boolean creative = player.isCreative();

        // ① 逐格决议：这一格要放什么（候选加工 → 偏好 → 拆除旧线圈）
        List<BlockPos> targets = new ArrayList<>();
        List<ItemStack> wanted = new ArrayList<>();
        for (StructureBuildPlanner.Cell cell : plan.cells()) {
            BlockState current = level.getBlockState(cell.pos());
            if (cell.occupied()) {
                // 只有「线圈替换模式」才碰已有方块，而且只换线圈
                if (!replaceCoil || !(current.getBlock() instanceof CoilBlock)) continue;
            }
            List<ItemStack> candidates = effectiveCandidates(cell.candidates(), cell.required(), settings);
            if (candidates.isEmpty()) continue;
            // 组键仍用「谓词原始候选」算（面板键与搭建键才能对上），见 TerminalSettings.lookupPreference
            StructureBuildPlanner.Slot slot =
                    new StructureBuildPlanner.Slot(cell.pos(), candidates, cell.groupKey());
            ItemStack want = TerminalSettings.resolve(slot, prefs, planned);
            if (want.isEmpty()) continue;
            targets.add(cell.pos());
            wanted.add(want);
        }

        if (targets.size() > MAX_BLOCKS_PER_BUILD) {
            player.displayClientMessage(Component.translatable(AdvancedTerminalLang.BUILD_TOO_MANY,
                    targets.size(), MAX_BLOCKS_PER_BUILD), true);
            return new Result(0, 0, 0, true);
        }
        if (targets.isEmpty()) return Result.NONE;

        // ② 来源：先按物品类型聚合需求，再一次性建背包索引 / 一次性问 AE
        TerminalItemSource.Demand demand = new TerminalItemSource.Demand();
        if (!creative) {
            for (ItemStack stack : wanted) demand.add(stack);
        }
        TerminalItemSource.Inventory bag = creative
                ? TerminalItemSource.Inventory.of(null)
                : TerminalItemSource.Inventory.of(player);
        Map<TerminalItemSource.Key, Integer> fromBag = new HashMap<>();
        if (!creative) {
            demand.counts().forEach((key, count) ->
                    fromBag.put(key, Math.min(count, bag.count(demand.stacks().get(key)))));
        }

        IGrid grid = null;
        if (!creative && settings.useAe() && TerminalSettings.hasAeLink(terminal)) {
            grid = AeGridLink.resolveGrid(terminal, level, player);
        }
        TerminalItemSource.AeDemand ae = TerminalItemSource.AeDemand.of(grid, player);
        ae.simulate(demand.counts(), fromBag, demand.stacks());

        // ③ 逐格放置（顺序与规划一致）
        int placed = 0;
        int missing = 0;
        Set<BlockPos> placedPositions = new LinkedHashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            BlockPos pos = targets.get(i);
            ItemStack want = wanted.get(i);
            if (!(want.getItem() instanceof BlockItem blockItem)) continue;

            boolean fromAe = false;
            TerminalItemSource.Inventory.SlotRef reserved = null;
            if (!creative) {
                reserved = bag.reserve(want);
                if (reserved == null) {
                    if (!ae.reserve(want)) {
                        missing++;
                        continue;
                    }
                    fromAe = true;
                }
                // 线圈替换：先把旧线圈收进背包（收不进就跳过这一格），再放新的 ——
                // 旧线圈占着那一格，不先挪走的话放置会被「位置不可替换」挡下来
                if (replaceCoil) {
                    BlockState current = level.getBlockState(pos);
                    if (current.getBlock() instanceof CoilBlock) {
                        ItemStack old = new ItemStack(current.getBlock().asItem());
                        if (!canPickUp(player, old)) {
                            if (reserved != null) bag.release(reserved);
                            missing++;
                            continue;
                        }
                        level.removeBlock(pos, false);
                        give(player, level, pos, old);
                    }
                }
            }

            BlockPlaceContext context = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND,
                    want.copy(), BlockHitResult.miss(player.getEyePosition(0), Direction.UP, pos));
            InteractionResult result;
            try {
                result = blockItem.place(context);
            } catch (Throwable ignored) {
                result = InteractionResult.FAIL;
            }

            if (result == InteractionResult.FAIL) {
                // 放失败：把预留还回去，物品一个都不扣
                if (reserved != null) bag.release(reserved);
                missing++;
                continue;
            }

            if (!creative) {
                if (reserved != null) bag.commit(reserved);
                if (fromAe) ae.commit(want);
            }
            placed++;
            placedPositions.add(pos);
        }

        // ④ 整轮结束后才真正从 AE 扣（每种类型一次 MODULATE）
        if (!creative) ae.flush();

        Direction frontFacing = controller.self() == null ? Direction.NORTH : controller.self().getFrontFacing();
        fixFacings(level, placedPositions, frontFacing);

        return new Result(placed, 0, missing, false);
    }

    /**
     * 候选加工：线圈等级 + 无仓室模式。
     *
     * <p>⚠️ 线圈等级为 0 时<b>不</b>砍掉最高档（给出全部档位）—— 这样「面板里选的档」与
     * 「搭建时算出来的组键」天然一致，不需要靠回退匹配兜底。
     *
     * <p>⚠️ 无仓室模式只作用于「多出来的」仓室格：{@code required} 表示这一格的候选是
     * 限次谓词的<b>最小数量</b>逼出来的（结构必须要有它），那种格子照放 ——
     * 否则一台机器会因为缺必须的仓室而永远不成型。
     */
    private static List<ItemStack> effectiveCandidates(List<ItemStack> candidates, boolean required,
                                                       AdvancedTerminalSettings.Snapshot settings) {
        if (candidates.isEmpty()) return candidates;
        List<ItemStack> result = candidates;

        if (settings.coilTier() > 0 && anyCoil(candidates)) {
            int index = Math.min(settings.coilTier(), candidates.size()) - 1;
            result = List.of(candidates.get(Math.max(index, 0)));
        }
        // 无仓室模式：默认不主动放仓室（看候选第 1 项是不是多方块部件）
        if (settings.noHatch() && !required && isHatch(result.get(0))) return List.of();
        return result;
    }

    private static boolean anyCoil(List<ItemStack> candidates) {
        for (ItemStack candidate : candidates) {
            if (candidate.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof CoilBlock) {
                return true;
            }
        }
        return false;
    }

    /** 候选第 1 项是不是「多方块部件」（各种仓 / 总线 / 维护仓）。 */
    private static boolean isHatch(ItemStack first) {
        if (!(first.getItem() instanceof BlockItem blockItem)) return false;
        Block block = blockItem.getBlock();
        return block instanceof IMachineBlock && partBlocks().contains(block);
    }

    @Nullable
    private static Set<Block> partBlocks;

    /**
     * 全部「多方块部件」方块。
     *
     * <p>来源是 {@link PartAbility} 各能力上登记过的方块（GTM 注册部件时都会登记能力值），
     * 只算一次并缓存。
     *
     * <p>⚠️ 这是个近似：只在 {@code PartAbility} 自己的静态字段里找能力实例，
     * 别的模组自建的能力实例收不到。本环境里部件的注册都走 GTM 这套常量。
     */
    private static Set<Block> partBlocks() {
        if (partBlocks == null) {
            Set<Block> blocks = new HashSet<>();
            for (Field field : PartAbility.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != PartAbility.class) continue;
                try {
                    PartAbility ability = (PartAbility) field.get(null);
                    if (ability != null) blocks.addAll(ability.getAllBlocks());
                } catch (ReflectiveOperationException ignored) {
                    // 个别能力取不到不影响其余
                }
            }
            partBlocks = blocks;
        }
        return partBlocks;
    }

    // ======================== 拆除 ========================

    private static Result demolish(Player player, Level level, StructureBuildPlanner.CellPlan plan,
                                   BlockPos controllerPos) {
        List<BlockPos> targets = new ArrayList<>();
        for (StructureBuildPlanner.Cell cell : plan.cells()) {
            // 谓词为空 / 谓词是空气：候选被剔干净了，直接跳过
            if (cell.candidates().isEmpty()) continue;
            // 谓词是控制器：控制器自己那一格永远不拆
            if (cell.pos().equals(controllerPos)) continue;

            BlockState current = level.getBlockState(cell.pos());
            if (current.isAir()) continue;
            // 不属于这一格谓词候选的方块一律不动 —— 防误删玩家方块的那道闸
            if (!matches(cell.candidates(), current)) continue;
            targets.add(cell.pos());
        }

        if (targets.size() > MAX_BLOCKS_PER_BUILD) {
            player.displayClientMessage(Component.translatable(AdvancedTerminalLang.BUILD_TOO_MANY,
                    targets.size(), MAX_BLOCKS_PER_BUILD), true);
            return new Result(0, 0, 0, true);
        }

        int removed = 0;
        for (BlockPos pos : targets) {
            BlockState current = level.getBlockState(pos);
            if (current.isAir()) continue;
            ItemStack drop = new ItemStack(current.getBlock().asItem());
            if (level.removeBlock(pos, false)) {
                removed++;
                give(player, level, pos, drop);
            }
        }
        return new Result(0, removed, 0, false);
    }

    private static boolean matches(List<ItemStack> candidates, BlockState current) {
        Block block = current.getBlock();
        for (ItemStack candidate : candidates) {
            if (candidate.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == block) {
                return true;
            }
        }
        return false;
    }

    // ======================== 掉落 / 背包 ========================

    /** 拆下来的方块给玩家：先塞背包，塞不下就掉在原地（不会凭空消失）。 */
    private static void give(Player player, Level level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack leftover = stack.copy();
        if (!player.addItem(leftover) && !leftover.isEmpty()) {
            Block.popResource(level, pos, leftover);
        }
    }

    /** 先模拟塞一遍再决定要不要真塞（塞不下就什么都不做）。 */
    private static boolean canPickUp(Player player, ItemStack stack) {
        IItemHandler handler = player.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (handler == null) return false;
        ItemStack probe = stack.copy();
        for (int i = 0; i < handler.getSlots() && !probe.isEmpty(); i++) {
            probe = handler.insertItem(i, probe, true);
        }
        return probe.isEmpty();
    }

    // ======================== 朝向修正 ========================

    /**
     * 让线缆 / 仓室这些带朝向的方块朝向空位（与 GTCEu 自己的自动搭建一致）。
     *
     * <p>两处省着来（不改变放置结果）：
     * <ol>
     *   <li>方块没有 {@code FACING} / {@code HORIZONTAL_FACING} 属性时直接跳过 ——
     *       一次 {@code getBlockEntity} 都不查；</li>
     *   <li>有属性时整格只查一次 {@code getBlockEntity}（原来每次试探都要查，最多 6 次）。</li>
     * </ol>
     */
    private static void fixFacings(Level level, Collection<BlockPos> placed, Direction frontFacing) {
        for (BlockPos pos : placed) {
            BlockState state = level.getBlockState(pos);
            Property<Direction> property;
            Direction[] order;
            if (state.hasProperty(BlockStateProperties.FACING)) {
                property = BlockStateProperties.FACING;
                order = ArrayUtils.addAll(new Direction[] { frontFacing }, FACINGS);
            } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                property = BlockStateProperties.HORIZONTAL_FACING;
                order = frontFacing.getAxis() == Direction.Axis.Y
                        ? FACINGS_H
                        : ArrayUtils.addAll(new Direction[] { frontFacing }, FACINGS_H);
            } else {
                continue;
            }

            MetaMachine machine = level.getBlockEntity(pos) instanceof IMachineBlockEntity holder
                    ? holder.getMetaMachine()
                    : null;

            Direction found = null;
            for (Direction direction : order) {
                if (isFacingUsable(level, pos, direction, machine, placed)) {
                    found = direction;
                    break;
                }
            }
            if (found == null) found = Direction.NORTH;
            if (state.getValue(property) != found) {
                level.setBlock(pos, state.setValue(property, found), 3);
            }
        }
    }

    private static boolean isFacingUsable(Level level, BlockPos pos, Direction direction,
                                          @Nullable MetaMachine machine, Collection<BlockPos> placed) {
        BlockPos neighbor = pos.relative(direction);
        // 机器类方块：朝向要合法，且前方为空
        if (machine != null) {
            return level.isEmptyBlock(neighbor) && machine.isFacingValid(direction);
        }
        // 普通方块：前方要么是空气，要么是我们这次刚放下的（那就算被挡）
        return !placed.contains(neighbor) && level.isEmptyBlock(neighbor);
    }
}
