package rain.gtetcore.gtet.common.item.terminal;

import appeng.api.networking.IGrid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 方块来源（背包 / AE）—— 一次搭建里把「查」和「扣」都做成批量操作。
 *
 * <p>两件事：
 * <ol>
 *   <li>{@link Inventory}：循环<b>前</b>把玩家背包扫一遍建索引（含背包里各物品自带的容器），
 *       之后每种物品 O(1) 查存量，扣的时候直接落到具体槽位 —— 不再每格重扫背包；</li>
 *   <li>{@link AeDemand}：按<b>物品类型</b>聚合需求，每种类型只做 1 次 SIMULATE
 *       （{@link AeGridLink#canExtract}）与 1 次 MODULATE（{@link AeGridLink#extract}），
 *       并在整轮里复用同一个 {@code IActionSource}。</li>
 * </ol>
 *
 * <p>扣物品的时机仍然是「放置成功之后」：预留 → 放置 → 成功则 commit、失败则 release。
 *
 * @author rain fox
 */
public final class TerminalItemSource {

    /** 嵌套容器只往下找一层（背包里那件物品自带的容器）。 */
    private static final int MAX_NESTING = 1;

    private TerminalItemSource() {}

    /** 物品键：与 {@link ItemStack#isSameItemSameTags} 等价的哈希键（物品同一性 + NBT 深比较）。 */
    public record Key(Item item, @Nullable CompoundTag tag) {

        @Nullable
        public static Key of(ItemStack stack) {
            return stack.isEmpty() ? null : new Key(stack.getItem(), stack.getTag());
        }
    }

    // ======================== 背包索引 ========================

    /** 玩家背包（含一层嵌套容器）的索引。 */
    public static final class Inventory {

        private final Map<Key, List<SlotRef>> byKey = new LinkedHashMap<>();
        private final Map<Key, Integer> totals = new HashMap<>();
        private int slotReads;

        private Inventory() {}

        /**
         * 建索引。每个槽位只调<b>一次</b> {@code getStackInSlot}。
         *
         * @param player 玩家；{@code null} 时返回空索引
         */
        public static Inventory of(@Nullable Player player) {
            if (player == null) return new Inventory();
            return ofHandler(player.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null));
        }

        /**
         * 从一个物品栏处理器建索引（{@link #of(Player)} 最终也走这里；也方便脱离玩家单独测）。
         */
        public static Inventory ofHandler(@Nullable IItemHandler handler) {
            Inventory inventory = new Inventory();
            if (handler != null) inventory.scan(handler, 0);
            return inventory;
        }

        /** 一个可扣的槽位，以及它还剩几个可扣。 */
        public static final class SlotRef {

            private final Key key;
            private final IItemHandler handler;
            private final int index;
            private int remaining;

            private SlotRef(Key key, IItemHandler handler, int index, int remaining) {
                this.key = key;
                this.handler = handler;
                this.index = index;
                this.remaining = remaining;
            }

            public int index() {
                return index;
            }

            public int remaining() {
                return remaining;
            }
        }

        private void scan(IItemHandler handler, int depth) {
            int slots = handler.getSlots();
            for (int i = 0; i < slots; i++) {
                ItemStack stack = handler.getStackInSlot(i);
                this.slotReads++;
                if (stack.isEmpty()) continue;

                // 嵌套容器：先看这一件物品自带的容器（只往下找一层）
                if (depth < MAX_NESTING) {
                    IItemHandler nested = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
                    if (nested != null && nested != handler) scan(nested, depth + 1);
                }

                Key key = Key.of(stack);
                if (key == null) continue;
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new SlotRef(key, handler, i, stack.getCount()));
                totals.merge(key, stack.getCount(), Integer::sum);
            }
        }

        /** 这种物品一共还能拿出几个（已扣掉本轮预留/已扣的）。 */
        public int count(ItemStack wanted) {
            Key key = Key.of(wanted);
            Integer total = key == null ? null : totals.get(key);
            return total == null ? 0 : total;
        }

        /** 索引建立时的 {@code getStackInSlot} 调用次数（诊断用）。 */
        public int slotReads() {
            return slotReads;
        }

        /**
         * 预留一个（<b>不</b>真扣）：放置成功后再 {@link #commit}，失败就 {@link #release}。
         *
         * @return 预留到的槽位；没有存量时为 {@code null}
         */
        @Nullable
        public SlotRef reserve(ItemStack wanted) {
            Key key = Key.of(wanted);
            if (key == null) return null;
            List<SlotRef> slots = byKey.get(key);
            if (slots == null) return null;
            for (SlotRef ref : slots) {
                if (ref.remaining > 0) {
                    ref.remaining--;
                    totals.merge(key, -1, Integer::sum);
                    return ref;
                }
            }
            return null;
        }

        /** 放置成功：真正从槽位里扣掉一个。 */
        public void commit(SlotRef ref) {
            ref.handler.extractItem(ref.index, 1, false);
        }

        /** 放置失败：把预留还回去（这一格没消耗物品，也不需要再读一次槽位）。 */
        public void release(SlotRef ref) {
            ref.remaining++;
            totals.merge(ref.key, 1, Integer::sum);
        }
    }

    // ======================== AE 需求聚合 ========================

    /**
     * 一轮搭建的 AE 取料计划。
     *
     * <p>用法：{@link #simulate} 一次性为<b>每种</b>物品类型做一次模拟提取；
     * 逐格放置时用 {@link #reserve} 领额度、成功后 {@link #commit}；
     * 整轮结束调 {@link #flush}，把实际用掉的量<b>每种类型一次</b>真扣掉。
     */
    public static final class AeDemand {

        private final IGrid grid;
        private final Player player;
        private final AeOps ops;
        private final Map<Key, Integer> allowance = new LinkedHashMap<>();
        private final Map<Key, Integer> taken = new LinkedHashMap<>();
        private final Map<Key, ItemStack> samples = new LinkedHashMap<>();

        private AeDemand(@Nullable IGrid grid, @Nullable Player player, AeOps ops) {
            this.grid = grid;
            this.player = player;
            this.ops = ops;
        }

        /** @param grid 已解析出的网络；{@code null} 表示没绑定 / 解析不出来，这个计划取不到任何东西 */
        public static AeDemand of(@Nullable IGrid grid, @Nullable Player player) {
            return of(grid, player, AeOps.DEFAULT);
        }

        /** 同上，但换一套取料入口（默认那套就是 {@link AeGridLink}）。 */
        public static AeDemand of(@Nullable IGrid grid, @Nullable Player player, AeOps ops) {
            return new AeDemand(grid, player, ops);
        }

        /** 这一轮到底能不能动 AE：没网络、或者玩家在创造模式（创造不消耗物品）都不动。 */
        private boolean usable() {
            return grid != null && (player == null || !player.isCreative());
        }

        /**
         * 按类型聚合需求：每种类型最多一次 SIMULATE。
         *
         * @param demand  物品键 → 总需求
         * @param fromBag 物品键 → 背包能提供的数量（剩下的缺口才问 AE 要）
         * @param stacks  物品键 → 该键的代表物品栈
         */
        public void simulate(Map<Key, Integer> demand, Map<Key, Integer> fromBag, Map<Key, ItemStack> stacks) {
            if (!usable()) return;
            for (Map.Entry<Key, Integer> entry : demand.entrySet()) {
                int shortfall = entry.getValue() - fromBag.getOrDefault(entry.getKey(), 0);
                if (shortfall <= 0) continue;
                ItemStack sample = stacks.get(entry.getKey());
                if (sample == null || sample.isEmpty()) continue;
                samples.put(entry.getKey(), sample);
                if (ops.canExtract(grid, player, sample, shortfall)) {
                    allowance.put(entry.getKey(), shortfall);
                }
            }
        }

        /** 领一个额度（不真扣）。 */
        public boolean reserve(ItemStack wanted) {
            Key key = Key.of(wanted);
            if (key == null) return false;
            int left = allowance.getOrDefault(key, 0);
            if (left <= 0) return false;
            allowance.put(key, left - 1);
            return true;
        }

        /** 放置成功：记一笔，等整轮结束后一次性真扣。 */
        public void commit(ItemStack wanted) {
            Key key = Key.of(wanted);
            if (key != null) taken.merge(key, 1, Integer::sum);
        }

        /** 整轮结束：每种用过 AE 的类型各一次 MODULATE。 */
        public void flush() {
            if (!usable()) return;
            for (Map.Entry<Key, Integer> entry : taken.entrySet()) {
                ItemStack sample = samples.get(entry.getKey());
                if (sample != null && entry.getValue() > 0) {
                    ops.extract(grid, player, sample, entry.getValue());
                }
            }
            taken.clear();
        }

        /** 是否绑定了可用的 AE 网络。 */
        public boolean linked() {
            return grid != null;
        }
    }

    /**
     * AE 取料的两个入口。
     *
     * <p>存在的意义是把「怎么取」与「取多少」分开：{@link AeDemand} 只管按类型聚合，
     * 真正的网络操作永远只有这两下。默认实现直接转发到 {@link AeGridLink}。
     */
    public interface AeOps {

        /** 默认：本终端自带链接的 AE 实现（只取不存）。 */
        AeOps DEFAULT = new AeOps() {

            @Override
            public boolean canExtract(IGrid grid, Player player, ItemStack wanted, int count) {
                return AeGridLink.canExtract(grid, player, wanted, count);
            }

            @Override
            public ItemStack extract(IGrid grid, Player player, ItemStack wanted, int count) {
                return AeGridLink.extract(grid, player, wanted, count);
            }
        };

        /** 模拟提取：够不够。 */
        boolean canExtract(IGrid grid, Player player, ItemStack wanted, int count);

        /** 真提取。 */
        ItemStack extract(IGrid grid, Player player, ItemStack wanted, int count);
    }

    // ======================== 物品键收集 ========================

    /** 物品键 → 该键的代表栈（AE 取料与提示都要用到栈本身）。 */
    public static final class Demand {

        private final Map<Key, Integer> counts = new LinkedHashMap<>();
        private final Map<Key, ItemStack> stacks = new LinkedHashMap<>();

        public void add(ItemStack stack) {
            Key key = Key.of(stack);
            if (key == null) return;
            counts.merge(key, 1, Integer::sum);
            stacks.putIfAbsent(key, stack);
        }

        public Map<Key, Integer> counts() {
            return counts;
        }

        public Map<Key, ItemStack> stacks() {
            return stacks;
        }

        public int total() {
            int sum = 0;
            for (int value : counts.values()) sum += value;
            return sum;
        }
    }
}
