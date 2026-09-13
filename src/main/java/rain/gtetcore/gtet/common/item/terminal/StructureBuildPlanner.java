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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构规划器 —— 把 {@link BlockPattern} 摊成「每个格子要放什么」的清单。
 *
 * <p>逻辑取自 GTCEu 自己的 {@code BlockPattern#autoBuild}（候选挑选那段），
 * 但不直接改世界：只算出每个位置的世界坐标与<b>候选方块列表</b>，
 * 并把这些候选按「同一组候选」归类 —— 这样线圈、聚变玻璃、火箱、分级机壳
 * 都会被自动识别成一个个「分级组」，玩家在终端里按组挑具体方块即可，
 * 不需要为每种方块硬编码等级枚举。
 *
 * <p>{@code BlockPattern} 的 {@code blockMatches} 等字段是 protected，
 * 与 GTMThings 同样的办法：反射读取。
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
     * 扫描结构，产出规划。
     *
     * @param pattern 控制器上的结构
     * @param state   该控制器的 {@link MultiblockState}
     * @param level   世界（用于判断哪些格子已经是空的）
     */
    public static Plan plan(BlockPattern pattern, MultiblockState state, net.minecraft.world.level.Level level) {
        List<Slot> slots = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();

        try {
            TraceabilityPredicate[][][] blockMatches = (TraceabilityPredicate[][][]) F_BLOCK_MATCHES.get(pattern);
            int finger = F_FINGER.getInt(pattern);
            int thumb = F_THUMB.getInt(pattern);
            int palm = F_PALM.getInt(pattern);
            int[] centerOffset = (int[]) F_CENTER_OFFSET.get(pattern);

            var controller = state.getController();
            if (controller == null) return new Plan(List.of(), Map.of());
            BlockPos centerPos = controller.self().getPos();
            Direction facing = controller.self().getFrontFacing();
            Direction upwardsFacing = controller.self().getUpwardsFacing();
            boolean flipped = controller.self().isFlipped();
            RelativeDirection[] structureDir = pattern.structureDir;

            var cacheGlobal = state.getGlobalCount();
            var cacheLayer = state.getLayerCount();

            int minZ = -centerOffset[4];
            for (int c = 0, z = minZ++; c < finger; c++) {
                for (int rep = 0; rep < pattern.aisleRepetitions[c][0]; rep++) {
                    cacheLayer.clear();
                    for (int b = 0, y = -centerOffset[1]; b < thumb; b++, y++) {
                        for (int a = 0, x = -centerOffset[0]; a < palm; a++, x++) {
                            TraceabilityPredicate predicate = blockMatches[c][b][a];
                            if (predicate == null) continue;

                            BlockPos local = setActualRelativeOffset(
                                    x, y, z, facing, upwardsFacing, flipped, structureDir);
                            BlockPos pos = local.offset(centerPos.getX(), centerPos.getY(), centerPos.getZ());

                            // 已经有方块的位置不用管（和 autoBuild 一致：只补空格）
                            if (!level.isEmptyBlock(pos)) {
                                state.update(pos, predicate);
                                for (SimplePredicate limit : predicate.limited) {
                                    limit.testLimited(state);
                                }
                                continue;
                            }

                            state.update(pos, predicate);
                            List<ItemStack> candidates = candidatesOf(predicate, state, cacheGlobal, cacheLayer);
                            if (candidates.isEmpty()) continue;

                            String key = groupKey(candidates);
                            groups.computeIfAbsent(key, k -> new Group(k, candidates));
                            slots.add(new Slot(pos, candidates, key));
                        }
                    }
                    z++;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 BlockPattern 内部结构", e);
        }

        return new Plan(List.copyOf(slots), Map.copyOf(groups));
    }

    /** 从谓词里挑出这一格的候选物品（与 GTCEu autoBuild 同序）。 */
    private static List<ItemStack> candidatesOf(TraceabilityPredicate predicate, MultiblockState state,
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
        return candidates;
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
