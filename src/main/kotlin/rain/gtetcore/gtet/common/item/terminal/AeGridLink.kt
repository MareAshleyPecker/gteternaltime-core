package rain.gtetcore.gtet.common.item.terminal

import appeng.api.config.Actionable
import appeng.api.implementations.blockentities.IWirelessAccessPoint
import appeng.api.networking.GridHelper
import appeng.api.networking.IGrid
import appeng.api.networking.IGridNode
import appeng.api.networking.IInWorldGridNodeHost
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.AEItemKey
import appeng.api.storage.MEStorage
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import rain.gtetcore.gtet.common.item.terminal.AeGridLink.LOCAL_RANGE

/**
 * 终端的 AE 链接 —— **只取不存**。
 *
 * 链接方式与 AE2 无线终端同源：把终端绑到一个**无线接入点**（存其 `GlobalPos`），
 * 用的时候解析成 [IGrid]。区别是解析由本类自己做，
 * **不需要玩家背包里带着 AE 的无线终端**。
 *
 * 所有取物都走 [MEStorage.extract]，整个类里没有任何 insert 路径。
 *
 * @author rain fox
 */
object AeGridLink {

    /** 直连 ME 方块时允许的最大距离（无线接入点走它自己的覆盖范围）。 */
    private const val LOCAL_RANGE = 16.0

    /**
     * 解析出可用的 AE 网络。
     *
     * 支持两种绑定目标：
     * 1. **无线接入点**：按接入点自身的覆盖范围判定（同 AE2 无线终端）；
     * 2. **直连的 ME 方块**（接口、线缆等实现了 `IInWorldGridNodeHost` 的方块）：
     *    要求玩家在 [LOCAL_RANGE] 格以内。
     *
     * @return 可用的网络；不可用时为 `null`
     */
    @JvmStatic
    fun resolveGrid(terminal: ItemStack, level: Level, player: Player): IGrid? {
        val link = TerminalSettings.getAeLink(terminal) ?: return null
        if (level.dimension() != link.dimension()) return null

        val pos = link.pos()
        val distanceSqr = player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

        // 1) 无线接入点
        val accessPoint = level.getBlockEntity(pos) as? IWirelessAccessPoint
        if (accessPoint != null) {
            if (!accessPoint.isActive) return null
            val range = accessPoint.range
            if (distanceSqr > range * range) return null
            return accessPoint.grid
        }

        // 2) 直连的 ME 方块
        val host: IInWorldGridNodeHost = GridHelper.getNodeHost(level, pos) ?: return null
        if (distanceSqr > LOCAL_RANGE * LOCAL_RANGE) return null
        for (direction in Direction.entries) {
            val node: IGridNode = host.getGridNode(direction) ?: continue
            if (node.isActive) return node.grid
        }
        return null
    }

    /** 只做一次模拟提取，判断网络里够不够。 */
    @JvmStatic
    fun canExtract(grid: IGrid?, player: Player, wanted: ItemStack, count: Int): Boolean {
        if (grid == null || wanted.isEmpty) return false
        val storage = storageOf(grid) ?: return false
        val key = AEItemKey.of(wanted) ?: return false
        return storage.extract(key, count.toLong(), Actionable.SIMULATE, IActionSource.ofPlayer(player)) >= count
    }

    /**
     * 从网络里真正取走物品（[Actionable.MODULATE]）。
     *
     * @return 实际取到的物品；取不到则为 [ItemStack.EMPTY]
     */
    @JvmStatic
    fun extract(grid: IGrid?, player: Player, wanted: ItemStack, count: Int): ItemStack {
        if (grid == null || wanted.isEmpty) return ItemStack.EMPTY
        val storage = storageOf(grid) ?: return ItemStack.EMPTY
        val key = AEItemKey.of(wanted) ?: return ItemStack.EMPTY

        val extracted = storage.extract(key, count.toLong(), Actionable.MODULATE, IActionSource.ofPlayer(player))
        if (extracted <= 0) return ItemStack.EMPTY
        val result = wanted.copy()
        result.count = minOf(extracted, Int.MAX_VALUE.toLong()).toInt()
        return result
    }

    private fun storageOf(grid: IGrid): MEStorage? {
        val service = grid.storageService ?: return null
        return service.inventory
    }
}