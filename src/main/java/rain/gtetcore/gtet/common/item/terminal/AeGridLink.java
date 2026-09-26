package rain.gtetcore.gtet.common.item.terminal;

import appeng.api.config.Actionable;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 终端的 AE 链接 —— <b>只取不存</b>。
 *
 * <p>链接方式与 AE2 无线终端同源：把终端绑到一个<b>无线接入点</b>（存其 {@link GlobalPos}），
 * 用的时候解析成 {@link IGrid}。区别是解析由本类自己做，
 * <b>不需要玩家背包里带着 AE 的无线终端</b>。
 *
 * <p>所有取物都走 {@link MEStorage#extract}，整个类里没有任何 insert 路径。
 *
 * @author rain fox
 */
public final class AeGridLink {

    private AeGridLink() {}

    /** 直连 ME 方块时允许的最大距离（无线接入点走它自己的覆盖范围）。 */
    private static final double LOCAL_RANGE = 16.0;

    /**
     * 解析出可用的 AE 网络。
     *
     * <p>支持两种绑定目标：
     * <ol>
     *   <li><b>无线接入点</b>：按接入点自身的覆盖范围判定（同 AE2 无线终端）；</li>
     *   <li><b>直连的 ME 方块</b>（接口、线缆等实现了 {@code IInWorldGridNodeHost} 的方块）：
     *       要求玩家在 {@value #LOCAL_RANGE} 格以内。</li>
     * </ol>
     *
     * @return 可用的网络；不可用时为 {@code null}
     */
    @Nullable
    public static IGrid resolveGrid(ItemStack terminal, Level level, Player player) {
        GlobalPos link = TerminalSettings.getAeLink(terminal);
        if (link == null) return null;
        if (!level.dimension().equals(link.dimension())) return null;

        BlockPos pos = link.pos();
        double distanceSqr = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        // 1) 无线接入点
        if (level.getBlockEntity(pos) instanceof IWirelessAccessPoint accessPoint) {
            if (!accessPoint.isActive()) return null;
            double range = accessPoint.getRange();
            if (distanceSqr > range * range) return null;
            return accessPoint.getGrid();
        }

        // 2) 直连的 ME 方块
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host == null || distanceSqr > LOCAL_RANGE * LOCAL_RANGE) return null;
        for (Direction direction : Direction.values()) {
            IGridNode node = host.getGridNode(direction);
            if (node != null && node.isActive()) return node.getGrid();
        }
        return null;
    }

    /** 只做一次模拟提取，判断网络里够不够。 */
    public static boolean canExtract(@Nullable IGrid grid, Player player, ItemStack wanted, int count) {
        if (grid == null || wanted.isEmpty()) return false;
        MEStorage storage = storageOf(grid);
        if (storage == null) return false;
        AEItemKey key = AEItemKey.of(wanted);
        if (key == null) return false;
        return storage.extract(key, count, Actionable.SIMULATE, IActionSource.ofPlayer(player)) >= count;
    }

    /**
     * 从网络里真正取走物品（{@link Actionable#MODULATE}）。
     *
     * @return 实际取到的物品；取不到则为 {@link ItemStack#EMPTY}
     */
    public static ItemStack extract(@Nullable IGrid grid, Player player, ItemStack wanted, int count) {
        if (grid == null || wanted.isEmpty()) return ItemStack.EMPTY;
        MEStorage storage = storageOf(grid);
        if (storage == null) return ItemStack.EMPTY;
        AEItemKey key = AEItemKey.of(wanted);
        if (key == null) return ItemStack.EMPTY;

        long extracted = storage.extract(key, count, Actionable.MODULATE, IActionSource.ofPlayer(player));
        if (extracted <= 0) return ItemStack.EMPTY;
        ItemStack result = wanted.copy();
        result.setCount((int) Math.min(extracted, Integer.MAX_VALUE));
        return result;
    }

    @Nullable
    private static MEStorage storageOf(IGrid grid) {
        var service = grid.getStorageService();
        return service == null ? null : service.getInventory();
    }
}
