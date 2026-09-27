package rain.gtetcore.gtet.common.item.terminal;

import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 结构规划器 —— 把 {@link BlockPattern} 摊成「每个格子要放什么」的清单。
 *
 * <p>逻辑取自 GTCEu 自己的 {@code BlockPattern#autoBuild}（候选挑选那段），
 * 但不直接改世界：只算出每个位置的世界坐标与<b>候选方块列表</b>，
 * 并把这些候选按「同一组候选」归类 —— 这样线圈、聚变玻璃、火箱、分级机壳
 * 都会被自动识别成一个个「分级组」，玩家在终端里按组挑具体方块即可，
 * 不需要为每种方块硬编码等级枚举。
 *
 * <p>{@code BlockPattern} 的 {@code blockMatches} 等字段是 protected，用反射读取。
 *
 * @author rain fox
 */
public final class StructureBuildPlanner {

    /** pattern 内部结构（反射缓存）。 */
    private static final Field F_BLOCK_MATCHES;
    private static final Field F_FINGER;
    private static final Field F_THUMB;
    private static final Field F_PALM;
    private static final Field F_CENTER_OFFSET;

    static {
        try {
            F_BLOCK_MATCHES = BlockPattern.class.getDeclaredField("blockMatches");
            F_BLOCK_MATCHES.setAccessible(true);
            F_FINGER = BlockPattern.class.getDeclaredField("fingerLength");
            F_FINGER.setAccessible(true);
            F_THUMB = BlockPattern.class.getDeclaredField("thumbLength");
            F_THUMB.setAccessible(true);
            F_PALM = BlockPattern.class.getDeclaredField("palmLength");
            F_PALM.setAccessible(true);
            F_CENTER_OFFSET = BlockPattern.class.getDeclaredField("centerOffset");
            F_CENTER_OFFSET.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private StructureBuildPlanner() {}

    /** 一个待放置（或已符合）的格子。 */
    public record Slot(BlockPos pos, List<ItemStack> candidates, @Nullable String groupKey) {

        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }

    /** 一组共享同一候选列表的格子（通常就是一个「分级组」）。 */
    public record Group(String key, List<ItemStack> candidates) {

        /** 是否真的有多档可选（多档才是「分级方块」）。 */
        public boolean tiered() {
            return candidates.size() > 1;
        }

        /** 默认取第一个候选。 */
        public ItemStack defaultChoice() {
            return candidates.isEmpty() ? ItemStack.EMPTY : candidates.get(0);
        }
    }

    /** 规划结果。 */
    public record Plan(List<Slot> slots, Map<String, Group> groups) {

        /** 需要放置的格子（世界为空的位置）。 */
        public List<Slot> emptySlots() {
            return slots;
        }

        public List<Group> tieredGroups() {
            return groups.values().stream().filter(Group::tiered).toList();
        }
    }

    /**
     * 规划选项（{@link #planCells} 用）。
     *
     * @param repeatCount     可重复层的重复次数；只对「最小 ≠ 最大」的层生效
     * @param flip            坐标映射的镜像位（与机器自身的 {@code isFlipped()} 取或）
     * @param includeOccupied 是否把「已经有方块」的格子也收进清单
     */
    public record Options(int repeatCount, boolean flip, boolean includeOccupied) {

        /** 默认：按最小重复数展开、不额外镜像、只收空格子。 */
        public static final Options DEFAULT = new Options(0, false, false);
    }

    /**
     * 一个格子。
     *
     * @param occupied 这一格世界上已经有方块（{@code candidates} 此时是「谓词的普通候选 ∪ 限次候选」）
     * @param required 这一格的候选是「限次谓词的最小数量」逼出来的（必须放），而不是兜底挑出来的
     */
    public record Cell(BlockPos pos, List<ItemStack> candidates, @Nullable String groupKey, boolean occupied,
                       boolean required) {}

    /** {@link #planCells} 的结果。 */
    public record CellPlan(List<Cell> cells, Map<String, Group> groups) {}

    /**
     * 扫描结构，产出规划。
     *
     * @param pattern 控制器上的结构
     * @param state   该控制器的 {@link MultiblockState}
     * @param level   世界（用于判断哪些格子已经是空的）
     */
    public static Plan plan(BlockPattern pattern, MultiblockState state, net.minecraft.world.level.Level level) {
        CellPlan cells = planCells(pattern, state, level, Options.DEFAULT);
        List<Slot> slots = new ArrayList<>();
        for (Cell cell : cells.cells()) {
            if (cell.occupied()) continue;
            slots.add(new Slot(cell.pos(), cell.candidates(), cell.groupKey()));
        }
        return new Plan(List.copyOf(slots), cells.groups());
    }

    /**
     * 与 {@link #plan} 同一套遍历顺序与坐标映射，额外支持三件事（都要靠选项开启）：
     * <ul>
     *   <li>把「已经有方块」的格子也收进清单 —— 线圈替换模式与拆除模式必须看到这些格子，
     *       否则换不了线圈、也拆不掉方块；</li>
     *   <li>可重复层的实际重复数 = 把 {@code repeatCount} 夹到 [最小, 最大]（最小 == 最大时用该固定值）；</li>
     *   <li>坐标映射的镜像位 = 选项 <b>或</b> 机器自身的 {@code isFlipped()}。</li>
     * </ul>
     */
    public static CellPlan planCells(BlockPattern pattern, MultiblockState state,
                                     net.minecraft.world.level.Level level, Options options) {
        List<Cell> cells = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();

        try {
            TraceabilityPredicate[][][] blockMatches = (TraceabilityPredicate[][][]) F_BLOCK_MATCHES.get(pattern);
            int finger = F_FINGER.getInt(pattern);
            int thumb = F_THUMB.getInt(pattern);
            int palm = F_PALM.getInt(pattern);
            int[] centerOffset = (int[]) F_CENTER_OFFSET.get(pattern);

            var controller = state.getController();
            if (controller == null) return new CellPlan(List.of(), Map.of());
            BlockPos centerPos = controller.self().getPos();
            Direction facing = controller.self().getFrontFacing();
            Direction upwardsFacing = controller.self().getUpwardsFacing();
            boolean flipped = controller.self().isFlipped() || options.flip();
            RelativeDirection[] structureDir = pattern.structureDir;

            var cacheGlobal = state.getGlobalCount();
            var cacheLayer = state.getLayerCount();

            int minZ = -centerOffset[4];
            for (int c = 0, z = minZ++; c < finger; c++) {
                // 可重复层：最小 == 最大时恒用该固定值；否则把设置里的重复次数夹到 [最小, 最大]
                int repMin = pattern.aisleRepetitions[c][0];
                int repMax = pattern.aisleRepetitions[c][1];
                int reps = repMin == repMax ? repMin
                        : Math.max(repMin, Math.min(options.repeatCount(), Math.max(repMax, repMin)));
                for (int rep = 0; rep < reps; rep++) {
                    cacheLayer.clear();
                    for (int b = 0, y = -centerOffset[1]; b < thumb; b++, y++) {
                        for (int a = 0, x = -centerOffset[0]; a < palm; a++, x++) {
                            TraceabilityPredicate predicate = blockMatches[c][b][a];
                            if (predicate == null) continue;

                            BlockPos local = setActualRelativeOffset(
                                    x, y, z, facing, upwardsFacing, flipped, structureDir);
                            BlockPos pos = local.offset(centerPos.getX(), centerPos.getY(), centerPos.getZ());

                            // 已经有方块的位置不用管（和 autoBuild 一致：只补空格）；
                            // 但线圈替换 / 拆除要看这些格子，所以选项开着时也收进清单
                            if (!level.isEmptyBlock(pos)) {
                                state.update(pos, predicate);
                                for (SimplePredicate limit : predicate.limited) {
                                    limit.testLimited(state);
                                }
                                if (options.includeOccupied()) {
                                    List<ItemStack> raw = rawCandidates(predicate);
                                    if (!raw.isEmpty()) {
                                        String key = groupKey(raw);
                                        groups.computeIfAbsent(key, k -> new Group(k, raw));
                                        cells.add(new Cell(pos, raw, key, true, false));
                                    }
                                }
                                continue;
                            }

                            state.update(pos, predicate);
                            Picked picked = candidatesOf(predicate, state, cacheGlobal, cacheLayer);
                            List<ItemStack> candidates = picked.candidates();
                            if (candidates.isEmpty()) continue;

                            String key = groupKey(candidates);
                            groups.computeIfAbsent(key, k -> new Group(k, candidates));
                            cells.add(new Cell(pos, candidates, key, false, picked.required()));
                        }
                    }
                    z++;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 BlockPattern 内部结构", e);
        }

        return new CellPlan(List.copyOf(cells), Map.copyOf(groups));
    }

    /**
     * 谓词的「普通候选 ∪ 限次候选」—— 不按限次顺序挑，就是要这一格<b>接受的全集</b>。
     * 拆除模式的防误删判定与线圈替换都用它。
     */
    private static List<ItemStack> rawCandidates(TraceabilityPredicate predicate) {
        Set<net.minecraft.world.level.block.Block> blocks = new LinkedHashSet<>();
        collect(blocks, predicate.common);
        collect(blocks, predicate.limited);
        List<ItemStack> candidates = new ArrayList<>();
        for (net.minecraft.world.level.block.Block block : blocks) {
            if (block == Blocks.AIR) continue;
            ItemStack stack = block.asItem().getDefaultInstance();
            if (!stack.isEmpty()) candidates.add(stack);
        }
        return candidates;
    }

    private static void collect(Set<net.minecraft.world.level.block.Block> out, List<SimplePredicate> predicates) {
        for (SimplePredicate predicate : predicates) {
            if (predicate.candidates == null) continue;
            BlockInfo[] infos = predicate.candidates.get();
            if (infos == null) continue;
            for (BlockInfo info : infos) out.add(info.getBlockState().getBlock());
        }
    }

    /**
     * 从谓词里挑出的候选。
     *
     * @param required 这批候选是「限次谓词的最小数量」逼出来的（这一格<b>必须</b>放），不是兜底挑的
     */
    public record Picked(List<ItemStack> candidates, boolean required) {}

    /** 从谓词里挑出这一格的候选物品（与 GTCEu autoBuild 同序）。 */
    private static Picked candidatesOf(TraceabilityPredicate predicate, MultiblockState state,
                                       Reference2IntOpenHashMap<SimplePredicate> cacheGlobal,
                                       Reference2IntOpenHashMap<SimplePredicate> cacheLayer) {
        BlockInfo[] infos = null;
        boolean find = false;

        for (SimplePredicate limit : predicate.limited) {
            if (limit.minLayerCount > 0) {
                int curr = cacheLayer.getInt(limit);
                if (curr < limit.minLayerCount && (limit.maxLayerCount == -1 || curr < limit.maxLayerCount)) {
                    cacheLayer.addTo(limit, 1);
                } else {
                    continue;
                }
            } else {
                continue;
            }
            infos = limit.candidates == null ? null : limit.candidates.get();
            find = true;
            break;
        }
        if (!find) {
            for (SimplePredicate limit : predicate.limited) {
                if (limit.minCount > 0) {
                    int curr = cacheGlobal.getInt(limit);
                    if (curr < limit.minCount && (limit.maxCount == -1 || curr < limit.maxCount)) {
                        cacheGlobal.addTo(limit, 1);
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
                infos = limit.candidates == null ? null : limit.candidates.get();
                find = true;
                break;
            }
        }
        if (!find) {
            for (SimplePredicate limit : predicate.limited) {
                if (limit.maxLayerCount != -1 &&
                        cacheLayer.getOrDefault(limit, Integer.MAX_VALUE) == limit.maxLayerCount) {
                    continue;
                }
                if (limit.maxCount != -1 &&
                        cacheGlobal.getOrDefault(limit, Integer.MAX_VALUE) == limit.maxCount) {
                    continue;
                }
                cacheLayer.addTo(limit, 1);
                cacheGlobal.addTo(limit, 1);
                infos = ArrayUtils.addAll(infos, limit.candidates == null ? null : limit.candidates.get());
            }
            for (SimplePredicate common : predicate.common) {
                infos = ArrayUtils.addAll(infos, common.candidates == null ? null : common.candidates.get());
            }
        }

        List<ItemStack> candidates = new ArrayList<>();
        if (infos != null) {
            for (BlockInfo info : infos) {
                if (info.getBlockState().getBlock() != Blocks.AIR) {
                    ItemStack stack = info.getItemStackForm();
                    if (!stack.isEmpty()) candidates.add(stack);
                }
            }
        }
        // find = true 表示这次是被「层 / 全局最小数量」逼出来的一格：这一格必须放，不能算作「多出来的仓室」
        return new Picked(candidates, find);
    }

    /** 一组候选的稳定标识：把所有候选的物品 id 排序后拼起来。 */
    public static String groupKey(List<ItemStack> candidates) {
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack stack : candidates) {
            String id = itemId(stack);
            ids.add(id == null ? "minecraft:air" : id);
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.naturalOrder());
        return String.join("|", sorted);
    }

    /** 物品注册名；拿不到（例如空气、未注册物品）时返回 null。 */
    @Nullable
    public static String itemId(ItemStack stack) {
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : key.toString();
    }

    /** 注册名 → 物品栈（面板里存的偏好是注册名字符串，搭建时要换回物品）。取不到返回 null。 */
    @Nullable
    public static ItemStack itemStackOf(String itemId) {
        var location = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (location == null) return null;
        var item = ForgeRegistries.ITEMS.getValue(location);
        return item == null ? null : item.getDefaultInstance();
    }

    /**
     * 坐标换算 —— 与结构检测使用的 {@code setActualRelativeOffset}（枚举版）算法保持一致，
     * 这样算出来的世界坐标才能和结构检测完全对得上。
     */
    private static BlockPos setActualRelativeOffset(int x, int y, int z, Direction facing,
                                                    Direction upwardsFacing, boolean isFlipped,
                                                    RelativeDirection[] structureDir) {
        int[] c0 = new int[] { x, y, z };
        int[] c1 = new int[3];
        if (facing == Direction.UP || facing == Direction.DOWN) {
            Direction of = facing == Direction.DOWN ? upwardsFacing : upwardsFacing.getOpposite();
            for (int i = 0; i < 3; i++) {
                switch (structureDir[i].getActualDirection(of)) {
                    case UP -> c1[1] = c0[i];
                    case DOWN -> c1[1] = -c0[i];
                    case WEST -> c1[0] = -c0[i];
                    case EAST -> c1[0] = c0[i];
                    case NORTH -> c1[2] = -c0[i];
                    case SOUTH -> c1[2] = c0[i];
                }
            }
            int xOffset = upwardsFacing.getStepX();
            int zOffset = upwardsFacing.getStepZ();
            int tmp;
            if (xOffset == 0) {
                tmp = c1[2];
                c1[2] = zOffset > 0 ? c1[1] : -c1[1];
                c1[1] = zOffset > 0 ? -tmp : tmp;
            } else {
                tmp = c1[0];
                c1[0] = xOffset > 0 ? c1[1] : -c1[1];
                c1[1] = xOffset > 0 ? -tmp : tmp;
            }
            if (isFlipped) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    c1[0] = -c1[0];
                } else {
                    c1[2] = -c1[2];
                }
            }
        } else {
            int ordinal = facing.ordinal();
            for (int i = 0; i < 3; i++) {
                switch (structureDir[i].getActualDirection(Direction.from3DDataValue(ordinal))) {
                    case UP -> c1[1] = c0[i];
                    case DOWN -> c1[1] = -c0[i];
                    case WEST -> c1[0] = -c0[i];
                    case EAST -> c1[0] = c0[i];
                    case NORTH -> c1[2] = -c0[i];
                    case SOUTH -> c1[2] = c0[i];
                }
            }
            boolean east = upwardsFacing == Direction.EAST;
            if (east || upwardsFacing == Direction.WEST) {
                Direction clockwise = facing.getClockWise();
                int xOffset = east ? clockwise.getStepX() : clockwise.getOpposite().getStepX();
                int tmp;
                if (xOffset == 0) {
                    tmp = c1[2];
                    int zOffset = east ? clockwise.getStepZ() : clockwise.getOpposite().getStepZ();
                    c1[2] = zOffset > 0 ? -c1[1] : c1[1];
                    c1[1] = zOffset > 0 ? tmp : -tmp;
                } else {
                    tmp = c1[0];
                    c1[0] = xOffset > 0 ? -c1[1] : c1[1];
                    c1[1] = xOffset > 0 ? tmp : -tmp;
                }
            } else if (upwardsFacing == Direction.SOUTH) {
                c1[1] = -c1[1];
                if (facing.getStepX() == 0) {
                    c1[0] = -c1[0];
                } else {
                    c1[2] = -c1[2];
                }
            }
            if (isFlipped) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    if (ordinal == 2 || ordinal == 3) {
                        c1[0] = -c1[0];
                    } else {
                        c1[2] = -c1[2];
                    }
                } else {
                    c1[1] = -c1[1];
                }
            }
        }
        return new BlockPos(c1[0], c1[1], c1[2]);
    }
}
