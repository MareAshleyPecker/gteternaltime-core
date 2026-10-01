package rain.gtetcore.gtet.common.item.tool

import com.google.common.base.Joiner
import com.gregtechceu.gtceu.api.gui.GuiTextures
import com.gregtechceu.gtceu.api.item.ComponentItem
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup
import com.lowdragmc.lowdraglib.gui.texture.TextTexture
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraftforge.registries.ForgeRegistries
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.config.GTETConfig
import rain.gtetcore.gtet.util.RegistriesUtil
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * @author [ialdaiaxiariyay](https://github.com/ialdaiaxiariyay/BetterGregTechAndAppliedEnergistics)
 */
class StructureWriteBehavior private constructor() : IItemUIFactory {

    override fun createUI(
        playerInventoryHolder: HeldItemUIFactory.HeldItemHolder,
        entityPlayer: Player
    ): ModularUI {
        // --- 动态文本标签 ---
        val scaleLabel = LabelWidget(7, 7) {
            var x = 0
            var y = 0
            var z = 0
            val pos = getPos(playerInventoryHolder.held)
            if (pos != null) {
                x = 1 + pos[1].x - pos[0].x
                y = 1 + pos[1].y - pos[0].y
                z = 1 + pos[1].z - pos[0].z
            }
            String.format("Size: X:%d Y:%d Z:%d", x, y, z)
        }
        scaleLabel.setColor(0xFAF9F6)

        val orderLabel = LabelWidget(7, 20) {
            val dirs = DebugBlockPattern.getDir(getDir(playerInventoryHolder.held))
            String.format("Dir: +%s | %s | %s", dirs[0].name, dirs[1].name, dirs[2].name)
        }
        orderLabel.setColor(0xFAF9F6)

        val startLabel = LabelWidget(7, 33) {
            val pos = getPos(playerInventoryHolder.held)
            if (pos != null) "§aStart: " + pos[0].toShortString() else "Start: -"
        }
        startLabel.setColor(0xFAF9F6)

        val endLabel = LabelWidget(7, 46) {
            val pos = getPos(playerInventoryHolder.held)
            if (pos != null) "§cEnd: " + pos[1].toShortString() else "End: -"
        }
        endLabel.setColor(0xFAF9F6)

        // --- 容器 (扩大以容纳起止坐标) ---
        val container = WidgetGroup(8, 8, 160, 78)
        container.addWidget(ImageWidget(4, 4, 152, 70, GuiTextures.DISPLAY))
        container.addWidget(scaleLabel)
        container.addWidget(orderLabel)
        container.addWidget(startLabel)
        container.addWidget(endLabel)
        container.setBackground(GuiTextures.BACKGROUND_INVERSE)

        return ModularUI(176, 138, playerInventoryHolder, entityPlayer)
            .background(GuiTextures.BACKGROUND)
            .widget(container)
            .widget(
                ButtonWidget(
                    9, 109, 158, 20, GuiTextureGroup(
                        GuiTextures.BUTTON,
                        TextTexture("Export")
                    )
                ) { export(playerInventoryHolder) }
            )
            .widget(
                ButtonWidget(
                    9, 86, 50, 20, GuiTextureGroup(
                        GuiTextures.BUTTON,
                        TextTexture("Rot X")
                    )
                ) { changeDirX(playerInventoryHolder) }
            )
            .widget(
                ButtonWidget(
                    63, 86, 50, 20, GuiTextureGroup(
                        GuiTextures.BUTTON,
                        TextTexture("Rot Y")
                    )
                ) { changeDirY(playerInventoryHolder) }
            )
            .widget(
                ButtonWidget(
                    117, 86, 50, 20, GuiTextureGroup(
                        GuiTextures.BUTTON,
                        TextTexture("Rot Z")
                    )
                ) { changeDirZ(playerInventoryHolder) }
            )
    }

    @Suppress("all")
    private fun export(playerInventoryHolder: HeldItemUIFactory.HeldItemHolder) {
        // 配置里关掉导出模式时，导出按钮直接不做事
        if (!GTETConfig.exportModeEnabled()) return
        if (getPos(playerInventoryHolder.held) != null &&
            playerInventoryHolder.player is ServerPlayer
        ) {
            val blockPos = getPos(playerInventoryHolder.held)
            val direction = getDir(playerInventoryHolder.held)
            val builder = StringBuilder()
            if (blockPos != null) {
                val blockPattern = DebugBlockPattern(
                    playerInventoryHolder.player.level(),
                    blockPos[0].x,
                    blockPos[0].y,
                    blockPos[0].z,
                    blockPos[1].x,
                    blockPos[1].y,
                    blockPos[1].z
                )
                val dirs = DebugBlockPattern.getDir(direction)
                blockPattern.changeDir(dirs[0], dirs[1], dirs[2])
                builder.append(".pattern(definition -> FactoryBlockPattern.start()\n")
                for (i in blockPattern.pattern.indices) {
                    val strings = blockPattern.pattern[i]
                    builder.append(String.format(".aisle(\"%s\")\n", Joiner.on("\", \"").join(strings)))
                }
                builder.append(".where(\"~\", Predicates.controller(Predicates.blocks(definition.get())))\n")
                blockPattern.legend.forEach { (b, c) ->
                    if (c == ' ') return@forEach
                    builder.append(".where(\"").append(c).append("\", Predicates.blocks(RegistriesUtil.getBlock(\"")
                        .append(RegistriesUtil.BlockId(b)).append("\")))\n")
                }
            }
            val target = ".where(\"~\", Predicates.blocks(Registries.getBlock(\"minecraft:oak_log\")))"
            val startIndex = builder.indexOf(target)
            if (startIndex != -1) {
                val endIndex = startIndex + target.length + 1
                builder.delete(startIndex, endIndex)
            }
            val now = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            val fileName = now.format(formatter) + ".kt"
            val logDir = File(GTETConfig.exportDirectory())
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val logFile = File(logDir, fileName)
            try {
                BufferedWriter(FileWriter(logFile)).use { writer -> writer.write(builder.toString()) }
            } catch (e: IOException) {
                Gtetcore.LOGGER.error("Error writing to log file: {}", e.message)
            }
        }
    }

    private fun rotate(stack: ItemStack, cycle: Array<Direction>) {
        val cur = getDir(stack)
        for (i in cycle.indices) {
            if (cycle[i] === cur) {
                setDir(stack, cycle[(i + 1) % cycle.size])
                return
            }
        }
        setDir(stack, cycle[0]) // 当前方向不在循环列表中时，设为第一个
    }

    private fun changeDirX(holder: HeldItemUIFactory.HeldItemHolder) {
        if (holder.player !is ServerPlayer) return
        rotate(holder.held, CYCLE_X)
    }

    private fun changeDirY(holder: HeldItemUIFactory.HeldItemHolder) {
        if (holder.player !is ServerPlayer) return
        rotate(holder.held, CYCLE_Y)
    }

    private fun changeDirZ(holder: HeldItemUIFactory.HeldItemHolder) {
        if (holder.player !is ServerPlayer) return
        rotate(holder.held, CYCLE_Z)
    }

    override fun onItemUseFirst(itemStack: ItemStack, context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.SUCCESS
        val stack = player.getItemInHand(context.hand)
        if (!player.isShiftKeyDown) {
            addPos(stack, context.clickedPos)
            player.containerMenu.broadcastChanges() // 强制同步 NBT
            if (player is ServerPlayer) {
                val pos = getPos(stack)
                var sx = 0
                var sy = 0
                var sz = 0
                if (pos != null) {
                    sx = 1 + pos[1].x - pos[0].x
                    sy = 1 + pos[1].y - pos[0].y
                    sz = 1 + pos[1].z - pos[0].z
                    player.displayClientMessage(
                        Component.literal(
                            "§a" + pos[0].toShortString() + " §7→ §c" + pos[1].toShortString() +
                                " §7| §fSize: §e" + sx + "x" + sy + "x" + sz
                        ), true
                    )
                } else {
                    player.displayClientMessage(
                        Component.literal(
                            "§a" + context.clickedPos.toShortString()
                        ), true
                    )
                }
            }
        } else {
            removePos(stack)
            player.containerMenu.broadcastChanges() // 强制同步 NBT
            if (player is ServerPlayer) {
                player.displayClientMessage(Component.literal("§cCleared"), true)
            }
        }
        return InteractionResult.SUCCESS
    }

    override fun use(
        item: Item,
        level: Level,
        player: Player,
        usedHand: InteractionHand
    ): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(usedHand)
        if (player.isShiftKeyDown) {
            removePos(stack)
        } else {
            if (player is ServerPlayer) {
                HeldItemUIFactory.INSTANCE.openUI(player, usedHand)
            }
        }
        return InteractionResultHolder(InteractionResult.SUCCESS, stack)
    }

    companion object {

        @JvmField
        val INSTANCE: StructureWriteBehavior = StructureWriteBehavior()

        /** 结构工具物品引用，注册时赋值，供渲染器直接比对。 */
        @JvmField
        var STRUCTURE_TOOLS_ITEM: Item? = null

        // 6 个方向固定循环，避免 getClockWise 在某些轴上不改变方向的问题
        private val CYCLE_X = arrayOf(Direction.NORTH, Direction.UP, Direction.SOUTH, Direction.DOWN)
        private val CYCLE_Y = arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)
        private val CYCLE_Z = arrayOf(Direction.WEST, Direction.UP, Direction.EAST, Direction.DOWN)

        @Suppress("all")
        @JvmStatic
        fun isItemStructureWriter(stack: ItemStack): Boolean {
            if (stack.isEmpty) return false
            val item = stack.item
            // 引用比对（最快）
            if (STRUCTURE_TOOLS_ITEM != null && item === STRUCTURE_TOOLS_ITEM) return true
            // registry 名比对（最可靠）
            val key = ForgeRegistries.ITEMS.getKey(item)
            if (key != null && "gtetcore" == key.namespace && "structure_tools" == key.path) return true
            // components 回退
            if (item is ComponentItem) return item.components.contains(INSTANCE)
            return false
        }

        @JvmStatic
        fun getDir(stack: ItemStack): Direction {
            val root = stack.tag
            if (root == null || !root.contains("structure_writer", 10)) return Direction.WEST
            val tag = root.getCompound("structure_writer")
            if (!tag.contains("dir")) return Direction.WEST
            val d = Direction.byName(tag.getString("dir"))
            return d ?: Direction.WEST
        }

        @JvmStatic
        fun setDir(stack: ItemStack, dir: Direction) {
            val tag = stack.getOrCreateTagElement("structure_writer")
            tag.putString("dir", dir.name)
        }

        /** 选区对外接口：返回 {最小角, 最大角}。渲染/导出/GUI 都只吃这一对，"起点/终点"只在本类内部存在。 */
        @JvmStatic
        fun getPos(stack: ItemStack): Array<BlockPos>? {
            val root = stack.tag
            if (root == null || !root.contains("structure_writer", 10)) return null
            val corners = readCorners(root.getCompound("structure_writer")) ?: return null

            val a = corners[0]
            val b = corners[1]
            return arrayOf(
                BlockPos(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z)),
                BlockPos(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z))
            )
        }

        /**
         * 读出一对**有序**角：{起点, 终点}；没有选区返回 null。
         *
         * ⚠️ 旧存档只有 min/max，丢了"谁是起点"，所以兼容取 min 角当起点、max 角当终点（只读，不再写回）。
         */
        @JvmStatic
        private fun readCorners(tag: CompoundTag): Array<BlockPos>? {
            if (tag.contains("startX") && tag.contains("endX")) {
                return arrayOf(
                    BlockPos(tag.getInt("startX"), tag.getInt("startY"), tag.getInt("startZ")),
                    BlockPos(tag.getInt("endX"), tag.getInt("endY"), tag.getInt("endZ"))
                )
            }
            // 旧格式坐标，只读兼容
            if (tag.contains("minX")) {
                return arrayOf(
                    BlockPos(tag.getInt("minX"), tag.getInt("minY"), tag.getInt("minZ")),
                    BlockPos(tag.getInt("maxX"), tag.getInt("maxY"), tag.getInt("maxZ"))
                )
            }
            return null
        }

        /**
         * 把点击位置并入选区：**起点定死，只动对角终点**（往外点变大、往内点变小）。
         *
         * 第一下建立起点（终点 = 起点，零体积），第二下起形成/调整矩形；
         * 旧格式选区读出来是起点=min、终点=max，所以旧存档的第一次右键也直接按新语义走。
         */
        @JvmStatic
        fun addPos(stack: ItemStack, pos: BlockPos) {
            val tag = stack.getOrCreateTagElement("structure_writer")

            val corners = readCorners(tag)
            // 还没有选区：起点与终点都落在点击处
            if (corners == null) {
                writeCorners(tag, pos, pos)
                return
            }
            // 起点不动，只把终点挪到点击处
            writeCorners(tag, corners[0], pos)
        }

        /** 写入起点/终点两个角：只写新键并清掉旧键，避免新旧两套坐标同时存在产生歧义。 */
        @JvmStatic
        private fun writeCorners(tag: CompoundTag, start: BlockPos, end: BlockPos) {
            tag.putInt("startX", start.x)
            tag.putInt("startY", start.y)
            tag.putInt("startZ", start.z)
            tag.putInt("endX", end.x)
            tag.putInt("endY", end.y)
            tag.putInt("endZ", end.z)

            clearLegacyCorners(tag)
        }

        @JvmStatic
        fun removePos(stack: ItemStack) {
            val tag = stack.getOrCreateTagElement("structure_writer")
            tag.remove("startX")
            tag.remove("startY")
            tag.remove("startZ")
            tag.remove("endX")
            tag.remove("endY")
            tag.remove("endZ")
            clearLegacyCorners(tag)
        }

        /** 清掉旧格式（min/max）遗留的六个键，保证新旧两套坐标不会同时存在。 */
        @JvmStatic
        private fun clearLegacyCorners(tag: CompoundTag) {
            tag.remove("minX")
            tag.remove("maxX")
            tag.remove("minY")
            tag.remove("maxY")
            tag.remove("minZ")
            tag.remove("maxZ")
        }
    }
}
