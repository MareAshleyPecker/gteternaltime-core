package rain.gtetcore.gtet.common.item.terminal;

import appeng.api.networking.IGrid;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 结构搭建执行器 —— 按 {@link StructureBuildPlanner} 的规划把方块放到世界上。
 *
 * <p>骨架取自 GTCEu {@code BlockPattern#autoBuild}（放置 + 朝向修正那两段），
 * 两处不同：
 * <ol>
 *   <li>方块来源是「背包 → 已链接的 AE 网络」，AE 只提取；</li>
 *   <li>每格放什么由终端里的<b>分级组偏好</b>决定（见 {@link TerminalSettings#resolve}）。</li>
 * </ol>
 *
 * @author rain fox
 */
public final class StructureBuilder {

    private static final Direction[] FACINGS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final Direction[] FACINGS_H = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** 一次搭建的结果。 */
    public record Result(int placed, int missing, List<ItemStack> missingItems, boolean aeLinked) {}

    private StructureBuilder() {}

    /**
     * 对控制器执行一次自动搭建。
     *
     * @param player   操作者
     * @param pattern  控制器的结构
     * @param state    控制器的 {@link MultiblockState}
     * @param terminal 手上的高级终端（设置与 AE 链接都在它的 NBT 里）
     */
    public static Result build(Player player, BlockPattern pattern, MultiblockState state, ItemStack terminal) {
        Level level = player.level();
        IGrid grid = player.isCreative() ? null : AeGridLink.resolveGrid(terminal, level, player);

        StructureBuildPlanner.Plan plan = StructureBuildPlanner.plan(pattern, state, level);

        Set<BlockPos> placed = new HashSet<>();
        List<ItemStack> missing = new ArrayList<>();

        for (StructureBuildPlanner.Slot slot : plan.slots()) {
            ItemStack wanted = TerminalSettings.resolve(terminal, slot);
            if (wanted.isEmpty() || !(wanted.getItem() instanceof BlockItem blockItem)) continue;

            Source source = Source.find(player, wanted, grid);
            if (source == null) {
                missing.add(wanted);
                continue;
            }

            BlockPlaceContext context = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND,
                    source.stack(), BlockHitResult.miss(player.getEyePosition(0), Direction.UP, slot.pos()));
            InteractionResult result = blockItem.place(context);
            if (result == InteractionResult.FAIL) {
                missing.add(wanted);
                continue;
            }

            source.consume();
            placed.add(slot.pos());
        }

        // 朝向修正：让线缆/仓室等朝向空位（与 GTCEu autoBuild 一致）
        Direction frontFacing = state.getController() == null ? Direction.NORTH
                : state.getController().self().getFrontFacing();
        for (BlockPos pos : new ArrayList<>(placed)) {
            fixFacing(level, pos, frontFacing, placed);
        }

        return new Result(placed.size(), missing.size(), List.copyOf(missing), grid != null);
    }

    /** 把缺失的物品按种类合并，便于提示玩家。 */
    public static Map<ItemStack, Integer> summarizeMissing(List<ItemStack> missing) {
        Map<ItemStack, Integer> summary = new LinkedHashMap<>();
        for (ItemStack stack : missing) {
            boolean merged = false;
            for (Map.Entry<ItemStack, Integer> entry : summary.entrySet()) {
                if (ItemStack.isSameItemSameTags(entry.getKey(), stack)) {
                    entry.setValue(entry.getValue() + 1);
                    merged = true;
                    break;
                }
            }
            if (!merged) summary.put(stack.copy(), 1);
        }
        return summary;
    }

    // ======================== 方块来源 ========================

    /** 一份待放置的方块，以及放好之后怎么扣。 */
    private record Source(ItemStack stack, @Nullable IItemHandler handler, int slot,
                          @Nullable IGrid grid, @Nullable Player player) {

        static Source find(@NotNull Player player, ItemStack wanted, @Nullable IGrid grid) {
            if (player.isCreative()) {
                return new Source(wanted.copy(), null, -1, null, null);
            }

            IItemHandler handler = player.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack inSlot = handler.getStackInSlot(i);
                if (!inSlot.isEmpty() && ItemStack.isSameItemSameTags(inSlot, wanted)) {
                    return new Source(inSlot.copy(), handler, i, null, null);
                }
            }

            // AE：先只做模拟检查，放成功后才真正提取（避免放了失败还白扣）
            if (AeGridLink.canExtract(grid, player, wanted, 1)) {
                return new Source(wanted.copy(), null, -1, grid, player);
            }
            return null;
        }

        /** 放置成功后扣物品。 */
        void consume() {
            if (handler != null && slot >= 0) {
                handler.extractItem(slot, 1, false);
            } else if (grid != null && player != null) {
                AeGridLink.extract(grid, player, stack, 1);
            }
        }
    }

    // ======================== 朝向修正 ========================

    private static void fixFacing(Level level, BlockPos pos, Direction frontFacing, Set<BlockPos> placed) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.FACING)) {
            tryFacings(level, pos, state, BlockStateProperties.FACING,
                    ArrayUtils.addAll(new Direction[] { frontFacing }, FACINGS), placed);
        } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction[] order = frontFacing.getAxis() == Direction.Axis.Y
                    ? FACINGS_H
                    : ArrayUtils.addAll(new Direction[] { frontFacing }, FACINGS_H);
            tryFacings(level, pos, state, BlockStateProperties.HORIZONTAL_FACING, order, placed);
        }
    }

    private static void tryFacings(Level level, BlockPos pos, BlockState state, Property<Direction> property,
                                   Direction[] order, Set<BlockPos> placed) {
        Direction found = null;
        for (Direction direction : order) {
            if (isFacingUsable(level, pos, direction, placed)) {
                found = direction;
                break;
            }
        }
        if (found == null) found = Direction.NORTH;
        level.setBlock(pos, state.setValue(property, found), 3);
    }

    private static boolean isFacingUsable(Level level, BlockPos pos, Direction direction, Set<BlockPos> placed) {
        BlockPos neighbor = pos.relative(direction);

        // 机器类方块：朝向要合法，且前方为空
        if (level.getBlockEntity(pos) instanceof IMachineBlockEntity blockEntity) {
            MetaMachine machine = blockEntity.getMetaMachine();
            if (machine != null) {
                return level.isEmptyBlock(neighbor) && machine.isFacingValid(direction);
            }
        }

        // 普通方块：前方要么是空气，要么是我们这次刚放下的（那就算被挡）
        return !placed.contains(neighbor) && level.isEmptyBlock(neighbor);
    }
}
