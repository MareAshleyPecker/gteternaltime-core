package com.hepdd.gtmthings.api.pattern;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.common.block.CoilBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.items.tools.powered.WirelessTerminalItem;
import com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.IntObjectPair;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import oshi.util.tuples.Triplet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

public class AdvancedBlockPattern extends BlockPattern {

    static Direction[] FACINGS = { Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST, Direction.UP,
            Direction.DOWN };
    static Direction[] FACINGS_H = { Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST };

    public final int[][] aisleRepetitions;
    public final RelativeDirection[] structureDir;
    protected final TraceabilityPredicate[][][] blockMatches; // [z][y][x]
    protected final int fingerLength; // z size
    protected final int thumbLength; // y size
    protected final int palmLength; // x size
    protected final int[] centerOffset; // x, y, z, minZ, maxZ

    public AdvancedBlockPattern(TraceabilityPredicate[][][] predicatesIn, RelativeDirection[] structureDir, int[][] aisleRepetitions, int[] centerOffset) {
        super(predicatesIn, structureDir, aisleRepetitions, centerOffset);
        this.blockMatches = predicatesIn;
        this.fingerLength = predicatesIn.length;
        this.structureDir = structureDir;
        this.aisleRepetitions = aisleRepetitions;

        if (this.fingerLength > 0) {
            this.thumbLength = predicatesIn[0].length;

            if (this.thumbLength > 0) {
                this.palmLength = predicatesIn[0][0].length;
            } else {
                this.palmLength = 0;
            }
        } else {
            this.thumbLength = 0;
            this.palmLength = 0;
        }

        this.centerOffset = centerOffset;
    }

    public static AdvancedBlockPattern getAdvancedBlockPattern(BlockPattern blockPattern) {
        try {
            Class<?> clazz = BlockPattern.class;
            // blockMatches
            Field blockMatchesField = clazz.getDeclaredField("blockMatches");
            blockMatchesField.setAccessible(true);
            TraceabilityPredicate[][][] blockMatches = (TraceabilityPredicate[][][]) blockMatchesField.get(blockPattern);
            // structureDir
            Field structureDirField = clazz.getDeclaredField("structureDir");
            structureDirField.setAccessible(true);
            RelativeDirection[] structureDir = (RelativeDirection[]) structureDirField.get(blockPattern);
            // aisleRepetitions
            Field aisleRepetitionsField = clazz.getDeclaredField("aisleRepetitions");
            aisleRepetitionsField.setAccessible(true);
            int[][] aisleRepetitions = (int[][]) aisleRepetitionsField.get(blockPattern);
            // centerOffset
            Field centerOffsetField = clazz.getDeclaredField("centerOffset");
            centerOffsetField.setAccessible(true);
            int[] centerOffset = (int[]) centerOffsetField.get(blockPattern);

            return new AdvancedBlockPattern(blockMatches, structureDir, aisleRepetitions, centerOffset);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 按终端设置实际摆放/拆除结构。
     *
     * <p>
     * 本次新增的三项搭建行为（对齐 GTO {@code AdvancedBlockPattern#autoBuild}）：
     * <ul>
     * <li><b>镜像搭建</b>（{@code IsFlip}）：{@code setActualRelativeOffset(..., isFlipped)} 的
     * {@code isFlipped} 改成「终端设置 {@code ||} 机器自身 {@code isFlipped()}}；</li>
     * <li><b>拆除模式</b>（{@code Demolition}）：进入「只拆不建」分支（见下面的拆除分支与
     * {@link #isStructureBlock}/{@link #demolish}）；</li>
     * <li><b>模块搭建</b>（{@code Module}）：<b>不在这里</b> —— 选哪一套图案由调用方
     * {@code AdvancedTerminalBehavior#resolvePattern} 决定后传进来（本类只认图案，不认控制器有哪些套）。</li>
     * </ul>
     */
    public void autoBuild(Player player, MultiblockState worldState,
                          AdvancedTerminalBehavior.AutoBuildSetting autoBuildSetting) {
        Level world = player.level();
        int minZ = -centerOffset[4];
        clearWorldState(worldState);
        IMultiController controller = worldState.getController();
        BlockPos centerPos = controller.self().getPos();
        Direction facing = controller.self().getFrontFacing();
        Direction upwardsFacing = controller.self().getUpwardsFacing();
        // 镜像搭建：终端设置里开了镜像就镜像；否则沿用机器自身朝向（isFlipped 本来就是这么来的）。
        // GTM 7.5.3 的 BlockPattern#setActualRelativeOffset 是 private（带 isFlipped，但外部调不到），
        // 本类下面有自己的一份拷贝，签名里本来就有 isFlipped，所以这里只是换一个来源。
        boolean isFlipped = autoBuildSetting.isFlipMode() || controller.self().isFlipped();
        Object2IntOpenHashMap<SimplePredicate> cacheGlobal = worldState.getGlobalCount();
        Object2IntOpenHashMap<SimplePredicate> cacheLayer = worldState.getLayerCount();
        Map<BlockPos, Object> blocks = new HashMap<>();
        Set<BlockPos> placeBlockPos = new HashSet<>();
        blocks.put(centerPos, controller);

        int[] repeat = new int[this.fingerLength];
        for (int h = 0; h < this.fingerLength; h++) {
            var minH = aisleRepetitions[h][0];
            var maxH = aisleRepetitions[h][1];
            if (minH != maxH) {
                repeat[h] = Math.max(minH, Math.min(maxH, autoBuildSetting.getRepeatCount()));
            } else {
                repeat[h] = minH;
            }
        }

        for (int c = 0, z = minZ++, r; c < this.fingerLength; c++) {
            for (r = 0; r < repeat[c]; r++) {
                cacheLayer.clear();
                for (int b = 0, y = -centerOffset[1]; b < this.thumbLength; b++, y++) {
                    for (int a = 0, x = -centerOffset[0]; a < this.palmLength; a++, x++) {
                        TraceabilityPredicate predicate = this.blockMatches[c][b][a];
                        BlockPos pos = setActualRelativeOffset(x, y, z, facing, upwardsFacing, isFlipped)
                                .offset(centerPos.getX(), centerPos.getY(), centerPos.getZ());
                        updateWorldState(worldState, pos, predicate);

                        // 拆除模式：这一格「只拆不建」。照 GTO 的 demolition 分支：
                        // 空气谓词（结构本来就要求是空气）与控制器谓词直接跳过；只有当这一格现在放着的方块
                        // **本来就是该位置的结构候选方块之一**时才拆 —— 玩家在别处、或在这一格放的不属于本结构
                        // 的方块一律不动（这是防「误删玩家方块」的那道闸）。
                        if (autoBuildSetting.isDemolitionMode()) {
                            if (predicate != null && !predicate.isAir() && !predicate.isController &&
                                    !world.isEmptyBlock(pos) &&
                                    isStructureBlock(predicate, world.getBlockState(pos).getBlock())) {
                                demolish(world, player, pos);
                            }
                            continue;
                        }

                        ItemStack coilItemStack = null;
                        if (!world.isEmptyBlock(pos)) {
                            if (world.getBlockState(pos).getBlock() instanceof CoilBlock coilBlock && autoBuildSetting.isReplaceCoilMode()) {
                                coilItemStack = coilBlock.asItem().getDefaultInstance();
                            } else {
                                blocks.put(pos, world.getBlockState(pos));
                                for (SimplePredicate limit : predicate.limited) {
                                    limit.testLimited(worldState);
                                }
                                continue;
                            }

                        }

                        boolean find = false;
                        BlockInfo[] infos = new BlockInfo[0];
                        for (SimplePredicate limit : predicate.limited) {
                            if (limit.minLayerCount > 0 && autoBuildSetting.isPlaceHatch(limit.candidates.get())) {
                                int curr = cacheLayer.getInt(limit);
                                if (curr < limit.minLayerCount &&
                                        (limit.maxLayerCount == -1 || curr < limit.maxLayerCount)) {
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
                                if (limit.minCount > 0 && autoBuildSetting.isPlaceHatch(limit.candidates.get())) {
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
                        if (!find) { // no limited
                            for (SimplePredicate limit : predicate.limited) {
                                if (!autoBuildSetting.isPlaceHatch(limit.candidates.get())) {
                                    continue;
                                }
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
                                infos = ArrayUtils.addAll(infos,
                                        limit.candidates == null ? null : limit.candidates.get());
                            }
                            for (SimplePredicate common : predicate.common) {
                                if (common.candidates != null && predicate.common.size() > 1 && !autoBuildSetting.isPlaceHatch(common.candidates.get())) {
                                    continue;
                                }
                                infos = ArrayUtils.addAll(infos,
                                        common.candidates == null ? null : common.candidates.get());
                            }
                        }

                        List<ItemStack> candidates = autoBuildSetting.apply(infos);

                        if (autoBuildSetting.isReplaceCoilMode() && coilItemStack != null && ItemStack.isSameItem(candidates.get(0), coilItemStack)) continue;

                        // check inventory
                        Triplet<ItemStack, IItemHandler, Integer> result = foundItem(player, candidates, autoBuildSetting.getIsUseAE());
                        ItemStack found = result.getA();
                        IItemHandler handler = result.getB();
                        int foundSlot = result.getC();

                        if (found == null) continue;

                        // check can get old coilBlock
                        IItemHandler holderHandler = null;
                        int holderSlot = -1;
                        if (autoBuildSetting.isReplaceCoilMode() && coilItemStack != null) {
                            Pair<IItemHandler, Integer> holderResult = foundHolderSlot(player, coilItemStack);
                            holderHandler = holderResult.getFirst();
                            holderSlot = holderResult.getSecond();

                            if (holderHandler != null && holderSlot < 0) {
                                continue;
                            }
                        }

                        if (autoBuildSetting.isReplaceCoilMode() && coilItemStack != null) {
                            world.removeBlock(pos, true);
                            if (holderHandler != null) holderHandler.insertItem(holderSlot, coilItemStack, false);
                        }

                        BlockItem itemBlock = (BlockItem) found.getItem();
                        BlockPlaceContext context = new BlockPlaceContext(world, player, InteractionHand.MAIN_HAND,
                                found, BlockHitResult.miss(player.getEyePosition(0), Direction.UP, pos));
                        InteractionResult interactionResult = itemBlock.place(context);
                        if (interactionResult != InteractionResult.FAIL) {
                            placeBlockPos.add(pos);
                            if (handler != null) {
                                handler.extractItem(foundSlot, 1, false);
                            }
                        }
                        if (world.getBlockEntity(pos) instanceof IMachineBlockEntity machineBlockEntity) {
                            blocks.put(pos, machineBlockEntity.getMetaMachine());
                        } else {
                            blocks.put(pos, world.getBlockState(pos));
                        }

                    }
                }
                z++;
            }
        }
        Direction frontFacing = controller.self().getFrontFacing();
        blocks.forEach((pos, block) -> { // adjust facing
            if (!(block instanceof IMultiController)) {
                if (block instanceof BlockState && placeBlockPos.contains(pos)) {
                    resetFacing(pos, (BlockState) block, frontFacing, (p, f) -> {
                        Object object = blocks.get(p.relative(f));
                        return object == null ||
                                (object instanceof BlockState && ((BlockState) object).getBlock() == Blocks.AIR);
                    }, state -> world.setBlock(pos, state, 3));
                } else if (block instanceof MetaMachine machine) {
                    resetFacing(pos, machine.getBlockState(), frontFacing, (p, f) -> {
                        Object object = blocks.get(p.relative(f));
                        if (object == null || (object instanceof BlockState blockState && blockState.isAir())) {
                            return machine.isFacingValid(f);
                        }
                        return false;
                    }, state -> world.setBlock(pos, state, 3));
                }
            }
        });
    }

    /**
     * 这一格现在放着的方块，是不是「该位置本来就该是这个结构方块」之一（GTO demolition 分支里的 {@code contain}）。
     *
     * <p>
     * 判定集合 = 该位置谓词的 {@code common} 候选 ∪ {@code limited} 候选，逐个比 {@link Block} 引用
     * （拿的是 {@link BlockInfo#getBlockState()} 的方块，GTM 的 {@code candidates} 是 {@code Supplier<BlockInfo[]>}，
     * 不像 GTO 分叉那样直接是 {@code Block[]}）。
     *
     * <p>
     * <b>为什么必须做这个判定</b>：拆除模式如果对「结构位置上的任何方块」都清成空气，
     * 就会把玩家顺手放在上面的箱子/机器一起吃掉。加上这一层之后，只有「本来就是本结构要求的方块」
     * 会被拆，别的方块原地不动（代价是：如果玩家把结构方块拆掉后原地放了别的方块，拆除模式不会清理它）。
     */
    private static boolean isStructureBlock(TraceabilityPredicate predicate, Block block) {
        for (SimplePredicate common : predicate.common) {
            if (matchesAnyCandidate(common, block)) return true;
        }
        for (SimplePredicate limited : predicate.limited) {
            if (matchesAnyCandidate(limited, block)) return true;
        }
        return false;
    }

    /** 单个 {@link SimplePredicate} 的候选里有没有这个方块。 */
    private static boolean matchesAnyCandidate(SimplePredicate predicate, Block block) {
        BlockInfo[] candidates = predicate.candidates == null ? null : predicate.candidates.get();
        if (candidates == null) return false;
        for (BlockInfo candidate : candidates) {
            if (candidate != null && candidate.getBlockState().getBlock() == block) return true;
        }
        return false;
    }

    /**
     * 拆掉一格：置为空气，并把方块还给玩家。
     *
     * <p>
     * ⚠️ 与 GTO 的<b>唯一</b>差别：GTO 在 {@code isUseAE} 时先试着塞进 AE 网络，塞不进才给玩家；
     * 本补丁统一给玩家（背包满则掉在方块原地），把「AE 取/存物品」整体留给后续排期
     * —— 和「使用 AE 物品」那一项一起做，避免在这里引入 AE 依赖。
     *
     * <p>
     * 用 {@code setBlockAndUpdate} 而不是 {@code removeBlock(pos, true)}：后者会自己掉落，
     * 前者不掉落、由这里显式给，语义与 GTO 一致（也就不会出现「掉落物 + 玩家背包」双份）。
     */
    private static void demolish(Level world, Player player, BlockPos pos) {
        ItemStack drop = new ItemStack(world.getBlockState(pos).getBlock(), 1);
        world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        if (drop.isEmpty()) return;
        if (!player.addItem(drop)) Block.popResource(world, pos, drop);
    }

    private Triplet<ItemStack, IItemHandler, Integer> foundItem(Player player, List<ItemStack> candidates, int isUseAE) {
        ItemStack found = null;
        IItemHandler handler = null;
        int foundSlot = -1;
        if (!player.isCreative()) {
            var foundHandler = getMatchStackWithHandler(candidates,
                    player.getCapability(ForgeCapabilities.ITEM_HANDLER), player, isUseAE);
            if (foundHandler != null) {
                foundSlot = foundHandler.firstInt();
                handler = foundHandler.second();
                found = handler.getStackInSlot(foundSlot).copy();
            }
        } else {
            for (ItemStack candidate : candidates) {
                found = candidate.copy();
                if (!found.isEmpty() && found.getItem() instanceof BlockItem) {
                    break;
                }
                found = null;
            }
        }
        return new Triplet<>(found, handler, foundSlot);
    }

    /**
     * 清掉 {@link MultiblockState} 里被自动搭建写脏的计数 / 方块缓存。
     *
     * <p>对应 GTO 在 {@code useOn} 里那句 {@code controller.getMultiblockState().cleanCache()}：
     * GTO 那个 {@code cleanCache()} 是它自己分叉加在 {@code MultiblockState} 上的方法，
     * GTM 7.5.3 里没有；7.5.3 的等价物是 {@code MultiblockState#clearCache()}（所以本方法也叫 clearCache）。
     *
     * <p>⚠️ <b>为什么要反射</b>（和 {@link #clearWorldState}/{@link #updateWorldState} 同一类理由）：
     * GTMThings 1.6.0 编译期对齐的是 <b>GTM 7.5.2</b>（`gradle.properties` 的 {@code gtceu_version=7.5.2}），
     * javap 实证 7.5.2 的 {@code MultiblockState} <b>只有 {@code clean()}、没有 {@code clearCache()}</b>；
     * 而运行期装的 GTM 7.5.3 两者都有（{@code clearCache()} 清 globalCount/layerCount/blockStateCache 与
     * predicate/blockState，{@code clean()} 是它的超集，还多清 matchContext）。
     * 所以这里先试 GTO 语义的 {@code clearCache()}，取不到再退到 {@code clean()}。
     *
     * <p>顺带说明「为什么清一下就够了」：GTM 7.5.3 的 {@code BlockPattern.checkPatternAt} 第一句就是
     * {@code worldState.clean()}，所以下一次真正的结构检测本来就会清干净 —— 这次清是卫生动作（照 GTO），
     * 不是正确性的必要条件。失败时静默忽略（照本类其它反射辅助方法的写法）。
     */
    public static void clearCache(MultiblockState worldState) {
        try {
            Method method = MultiblockState.class.getDeclaredMethod("clearCache");
            method.setAccessible(true);
            method.invoke(worldState);
            return;
        } catch (Exception ignored) {
            // GTM 7.5.2 没有 clearCache()：退到下一条
        }
        try {
            Method method = MultiblockState.class.getDeclaredMethod("clean");
            method.setAccessible(true);
            method.invoke(worldState);
        } catch (Exception ignored) {}
    }

    private Pair<IItemHandler, Integer> foundHolderSlot(Player player, ItemStack coilItemStack) {
        IItemHandler handler = null;
        int foundSlot = -1;
        if (!player.isCreative()) {
            handler = player.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            for (int i = 0; i < handler.getSlots(); i++) {
                @NotNull
                ItemStack stack = handler.getStackInSlot(i);
                if (stack.isEmpty()) {
                    if (foundSlot < 0) {
                        foundSlot = i;
                    }
                } else if (ItemStack.isSameItemSameTags(coilItemStack, stack) && (stack.getCount() + 1) <= stack.getMaxStackSize()) {
                    foundSlot = i;
                }
            }
        }

        return new Pair<>(handler, foundSlot);
    }

    private void clearWorldState(MultiblockState worldState) {
        try {
            Class<?> clazz = Class.forName("com.gregtechceu.gtceu.api.pattern.MultiblockState");
            // Object obj = clazz.newInstance();
            Method method = clazz.getDeclaredMethod("clean");
            method.setAccessible(true);
            method.invoke(worldState);
        } catch (Exception ignored) {}
    }

    private void updateWorldState(MultiblockState worldState, BlockPos posIn, TraceabilityPredicate predicate) {
        try {
            Class<?> clazz = Class.forName("com.gregtechceu.gtceu.api.pattern.MultiblockState");
            Method method = clazz.getDeclaredMethod("update", BlockPos.class, TraceabilityPredicate.class);
            method.setAccessible(true);
            method.invoke(worldState, posIn, predicate);
        } catch (Exception ignored) {}
    }

    private BlockPos setActualRelativeOffset(int x, int y, int z, Direction facing, Direction upwardsFacing,
                                             boolean isFlipped) {
        int[] c0 = new int[] { x, y, z }, c1 = new int[3];
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
                    c1[0] = -c1[0]; // flip X-axis
                } else {
                    c1[2] = -c1[2]; // flip Z-axis
                }
            }
        } else {
            for (int i = 0; i < 3; i++) {
                switch (structureDir[i].getActualDirection(facing)) {
                    case UP -> c1[1] = c0[i];
                    case DOWN -> c1[1] = -c0[i];
                    case WEST -> c1[0] = -c0[i];
                    case EAST -> c1[0] = c0[i];
                    case NORTH -> c1[2] = -c0[i];
                    case SOUTH -> c1[2] = c0[i];
                }
            }
            if (upwardsFacing == Direction.WEST || upwardsFacing == Direction.EAST) {
                int xOffset = upwardsFacing == Direction.EAST ? facing.getClockWise().getStepX() :
                        facing.getClockWise().getOpposite().getStepX();
                int zOffset = upwardsFacing == Direction.EAST ? facing.getClockWise().getStepZ() :
                        facing.getClockWise().getOpposite().getStepZ();
                int tmp;
                if (xOffset == 0) {
                    tmp = c1[2];
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
                    if (facing == Direction.NORTH || facing == Direction.SOUTH) {
                        c1[0] = -c1[0]; // flip X-axis
                    } else {
                        c1[2] = -c1[2]; // flip Z-axis
                    }
                } else {
                    c1[1] = -c1[1]; // flip Y-axis
                }
            }
        }
        return new BlockPos(c1[0], c1[1], c1[2]);
    }

    @Nullable
    private static IntObjectPair<IItemHandler> getMatchStackWithHandler(
                                                                        List<ItemStack> candidates,
                                                                        LazyOptional<IItemHandler> cap,
                                                                        Player player, int isUseAE) {
        IItemHandler handler = cap.resolve().orElse(null);
        if (handler == null) {
            return null;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            @NotNull
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            @NotNull
            LazyOptional<IItemHandler> stackCap = stack.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (stackCap.isPresent()) {
                var rt = getMatchStackWithHandler(candidates, stackCap, player, isUseAE);
                if (rt != null) {
                    return rt;
                }
            } else if (isUseAE == 1 && stack.getItem() instanceof WirelessTerminalItem terminalItem && stack.hasTag() && stack.getTag().contains("accessPoint", 10)) {
                IGrid grid = terminalItem.getLinkedGrid(stack, player.level(), player);
                if (grid != null) {
                    MEStorage storage = grid.getStorageService().getInventory();
                    for (ItemStack candidate : candidates) {
                        if (storage.extract(AEItemKey.of(candidate), 1, Actionable.MODULATE, null) > 0) {
                            NonNullList<ItemStack> stacks = NonNullList.withSize(1, candidate);
                            IItemHandler handler1 = new ItemStackHandler(stacks);
                            return IntObjectPair.of(0, handler1);
                        }
                    }
                }

            } else if (candidates.stream().anyMatch(candidate -> ItemStack.isSameItemSameTags(candidate, stack)) &&
                    !stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                        return IntObjectPair.of(i, handler);
                    }
        }
        return null;
    }

    private void resetFacing(BlockPos pos, BlockState blockState, Direction facing,
                             BiPredicate<BlockPos, Direction> checker, Consumer<BlockState> consumer) {
        if (blockState.hasProperty(BlockStateProperties.FACING)) {
            tryFacings(blockState, pos, checker, consumer, BlockStateProperties.FACING,
                    facing == null ? FACINGS : ArrayUtils.addAll(new Direction[] { facing }, FACINGS));
        } else if (blockState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            tryFacings(blockState, pos, checker, consumer, BlockStateProperties.HORIZONTAL_FACING,
                    facing == null || facing.getAxis() == Direction.Axis.Y ? FACINGS_H :
                            ArrayUtils.addAll(new Direction[] { facing }, FACINGS_H));
        }
    }

    private void tryFacings(BlockState blockState, BlockPos pos, BiPredicate<BlockPos, Direction> checker,
                            Consumer<BlockState> consumer, Property<Direction> property, Direction[] facings) {
        Direction found = null;
        for (Direction facing : facings) {
            if (checker.test(pos, facing)) {
                found = facing;
                break;
            }
        }
        if (found == null) {
            found = Direction.NORTH;
        }
        consumer.accept(blockState.setValue(property, found));
    }
}
