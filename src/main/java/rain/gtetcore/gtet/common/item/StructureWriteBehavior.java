package rain.gtetcore.gtet.common.item;

import com.google.common.base.Joiner;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.ComponentItem;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import rain.gtetcore.gtet.util.RegistriesUtil;
import rain.gtetcore.gtet.config.GTETConfig;
import rain.gtetcore.gtet.Gtetcore;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author <a href="https://github.com/ialdaiaxiariyay/BetterGregTechAndAppliedEnergistics">ialdaiaxiariyay</a>
 * */
public class StructureWriteBehavior implements IItemUIFactory {

    public static final StructureWriteBehavior INSTANCE = new StructureWriteBehavior();

    /** 结构工具物品引用，注册时赋值，供渲染器直接比对。 */
    public static Item STRUCTURE_TOOLS_ITEM;

    protected StructureWriteBehavior() {}

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder playerInventoryHolder, Player entityPlayer) {
        // --- 动态文本标签 ---
        var scaleLabel = new LabelWidget(7, 7, () -> {
            int x = 0, y = 0, z = 0;
            var pos = getPos(playerInventoryHolder.getHeld());
            if (pos != null) {
                x = 1 + pos[1].getX() - pos[0].getX();
                y = 1 + pos[1].getY() - pos[0].getY();
                z = 1 + pos[1].getZ() - pos[0].getZ();
            }
            return String.format("Size: X:%d Y:%d Z:%d", x, y, z);
        });
        scaleLabel.setColor(0xFAF9F6);

        var orderLabel = new LabelWidget(7, 20, () -> {
            var dirs = DebugBlockPattern.getDir(getDir(playerInventoryHolder.getHeld()));
            return String.format("Dir: +%s | %s | %s", dirs[0].name(), dirs[1].name(), dirs[2].name());
        });
        orderLabel.setColor(0xFAF9F6);

        var startLabel = new LabelWidget(7, 33, () -> {
            var pos = getPos(playerInventoryHolder.getHeld());
            return pos != null ? "§aStart: " + pos[0].toShortString() : "Start: -";
        });
        startLabel.setColor(0xFAF9F6);

        var endLabel = new LabelWidget(7, 46, () -> {
            var pos = getPos(playerInventoryHolder.getHeld());
            return pos != null ? "§cEnd: " + pos[1].toShortString() : "End: -";
        });
        endLabel.setColor(0xFAF9F6);

        // --- 容器 (扩大以容纳起止坐标) ---
        var container = new WidgetGroup(8, 8, 160, 78);
        container.addWidget(new ImageWidget(4, 4, 152, 70, GuiTextures.DISPLAY));
        container.addWidget(scaleLabel);
        container.addWidget(orderLabel);
        container.addWidget(startLabel);
        container.addWidget(endLabel);
        container.setBackground(GuiTextures.BACKGROUND_INVERSE);

        return new ModularUI(176, 138, playerInventoryHolder, entityPlayer)
                .background(GuiTextures.BACKGROUND)
                .widget(container)
                .widget(new ButtonWidget(9, 109, 158, 20, new GuiTextureGroup(
                                GuiTextures.BUTTON,
                                new TextTexture("Export")),
                        clickData -> export(playerInventoryHolder)))
                .widget(new ButtonWidget(9, 86, 50, 20, new GuiTextureGroup(
                                GuiTextures.BUTTON,
                                new TextTexture("Rot X")),
                        clickData -> changeDirX(playerInventoryHolder)))
                .widget(new ButtonWidget(63, 86, 50, 20, new GuiTextureGroup(
                                GuiTextures.BUTTON,
                                new TextTexture("Rot Y")),
                        clickData -> changeDirY(playerInventoryHolder)))
                .widget(new ButtonWidget(117, 86, 50, 20, new GuiTextureGroup(
                                GuiTextures.BUTTON,
                                new TextTexture("Rot Z")),
                        clickData -> changeDirZ(playerInventoryHolder)));
    }

    @SuppressWarnings("all")
    private void export(HeldItemUIFactory.HeldItemHolder playerInventoryHolder) {
        // 配置里关掉导出模式时，导出按钮直接不做事
        if (!GTETConfig.exportModeEnabled()) return;
        if (getPos(playerInventoryHolder.getHeld()) != null &&
                playerInventoryHolder.getPlayer() instanceof ServerPlayer) {
            BlockPos[] blockPos = getPos(playerInventoryHolder.getHeld());
            Direction direction = getDir(playerInventoryHolder.getHeld());
            StringBuilder builder = new StringBuilder();
            DebugBlockPattern blockPattern;
            if (blockPos != null) {
                blockPattern = new DebugBlockPattern(
                        playerInventoryHolder.getPlayer().level(),
                        blockPos[0].getX(),
                        blockPos[0].getY(),
                        blockPos[0].getZ(),
                        blockPos[1].getX(),
                        blockPos[1].getY(),
                        blockPos[1].getZ());
                RelativeDirection[] dirs = DebugBlockPattern.getDir(direction);
                blockPattern.changeDir(dirs[0], dirs[1], dirs[2]);
                builder.append(".pattern(definition -> FactoryBlockPattern.start()\n");
                for (int i = 0; i < blockPattern.pattern.length; i++) {
                    String[] strings = blockPattern.pattern[i];
                    builder.append(".aisle(\"%s\")\n".formatted(Joiner.on("\", \"").join(strings)));
                }
                builder.append(".where(\"~\", Predicates.controller(Predicates.blocks(definition.get())))\n");
                blockPattern.legend.forEach((b, c) -> {
                    if (c.equals(' ')) return;
                    builder.append(".where(\"").append(c).append("\", Predicates.blocks(RegistriesUtil.getBlock(\"")
                            .append(RegistriesUtil.BlockId(b)).append("\")))\n");
                });
            }
            String target = ".where(\"~\", Predicates.blocks(Registries.getBlock(\"minecraft:oak_log\")))";
            int startIndex = builder.indexOf(target);
            if (startIndex != -1) {
                int endIndex = startIndex + target.length() + 1;
                builder.delete(startIndex, endIndex);
            }
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
            String fileName = now.format(formatter) + ".kt";
            File logDir = new File(GTETConfig.exportDirectory());
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            File logFile = new File(logDir, fileName);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile))) {
                writer.write(builder.toString());
            } catch (IOException e) {
                Gtetcore.LOGGER.error("Error writing to log file: {}", e.getMessage());
            }
        }
    }

    // 6 个方向固定循环，避免 getClockWise 在某些轴上不改变方向的问题
    private static final Direction[] CYCLE_X = { Direction.NORTH, Direction.UP, Direction.SOUTH, Direction.DOWN };
    private static final Direction[] CYCLE_Y = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
    private static final Direction[] CYCLE_Z = { Direction.WEST, Direction.UP, Direction.EAST, Direction.DOWN };

    private void rotate(ItemStack stack, Direction[] cycle) {
        Direction cur = getDir(stack);
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] == cur) {
                setDir(stack, cycle[(i + 1) % cycle.length]);
                return;
            }
        }
        setDir(stack, cycle[0]); // 当前方向不在循环列表中时，设为第一个
    }

    private void changeDirX(HeldItemUIFactory.HeldItemHolder holder) {
        if (!(holder.getPlayer() instanceof ServerPlayer)) return;
        rotate(holder.getHeld(), CYCLE_X);
    }

    private void changeDirY(HeldItemUIFactory.HeldItemHolder holder) {
        if (!(holder.getPlayer() instanceof ServerPlayer)) return;
        rotate(holder.getHeld(), CYCLE_Y);
    }

    private void changeDirZ(HeldItemUIFactory.HeldItemHolder holder) {
        if (!(holder.getPlayer() instanceof ServerPlayer)) return;
        rotate(holder.getHeld(), CYCLE_Z);
    }
    @SuppressWarnings("all")
    public static boolean isItemStructureWriter(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var item = stack.getItem();
        // 引用比对（最快）
        if (STRUCTURE_TOOLS_ITEM != null && item == STRUCTURE_TOOLS_ITEM) return true;
        // registry 名比对（最可靠）
        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        if (key != null && "gtetcore".equals(key.getNamespace()) && "structure_tools".equals(key.getPath())) return true;
        // components 回退
        if (item instanceof ComponentItem ci) return ci.getComponents().contains(INSTANCE);
        return false;
    }

    public static Direction getDir(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains("structure_writer", 10)) return Direction.WEST;
        CompoundTag tag = root.getCompound("structure_writer");
        if (!tag.contains("dir")) return Direction.WEST;
        Direction d = Direction.byName(tag.getString("dir"));
        return d != null ? d : Direction.WEST;
    }

    public static void setDir(ItemStack stack, Direction dir) {
        CompoundTag tag = stack.getOrCreateTagElement("structure_writer");
        tag.putString("dir", dir.getName());
    }

    /** 选区对外接口：返回 {最小角, 最大角}。渲染/导出/GUI 都只吃这一对，"起点/终点"只在本类内部存在。 */
    public static BlockPos[] getPos(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains("structure_writer", 10)) return null;
        BlockPos[] corners = readCorners(root.getCompound("structure_writer"));
        if (corners == null) return null;

        BlockPos a = corners[0], b = corners[1];
        return new BlockPos[] {
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))
        };
    }

    /**
     * 读出一对<b>有序</b>角：{起点, 终点}；没有选区返回 null。
     * ⚠️ 旧存档只有 min/max，丢了"谁是起点"，所以兼容取 min 角当起点、max 角当终点（只读，不再写回）。
     */
    private static BlockPos[] readCorners(CompoundTag tag) {
        if (tag.contains("startX") && tag.contains("endX")) {
            return new BlockPos[] {
                    new BlockPos(tag.getInt("startX"), tag.getInt("startY"), tag.getInt("startZ")),
                    new BlockPos(tag.getInt("endX"), tag.getInt("endY"), tag.getInt("endZ"))
            };
        }
        // 旧格式坐标，只读兼容
        if (tag.contains("minX")) {
            return new BlockPos[] {
                    new BlockPos(tag.getInt("minX"), tag.getInt("minY"), tag.getInt("minZ")),
                    new BlockPos(tag.getInt("maxX"), tag.getInt("maxY"), tag.getInt("maxZ"))
            };
        }
        return null;
    }

    /**
     * 把点击位置并入选区：<b>起点定死，只动对角终点</b>（往外点变大、往内点变小）。
     * 第一下建立起点（终点 = 起点，零体积），第二下起形成/调整矩形；
     * 旧格式选区读出来是起点=min、终点=max，所以旧存档的第一次右键也直接按新语义走。
     */
    public static void addPos(ItemStack stack, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTagElement("structure_writer");

        BlockPos[] corners = readCorners(tag);
        // 还没有选区：起点与终点都落在点击处
        if (corners == null) {
            writeCorners(tag, pos, pos);
            return;
        }
        // 起点不动，只把终点挪到点击处
        writeCorners(tag, corners[0], pos);
    }

    /** 写入起点/终点两个角：只写新键并清掉旧键，避免新旧两套坐标同时存在产生歧义。 */
    private static void writeCorners(CompoundTag tag, BlockPos start, BlockPos end) {
        tag.putInt("startX", start.getX());
        tag.putInt("startY", start.getY());
        tag.putInt("startZ", start.getZ());
        tag.putInt("endX", end.getX());
        tag.putInt("endY", end.getY());
        tag.putInt("endZ", end.getZ());

        clearLegacyCorners(tag);
    }

    public static void removePos(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTagElement("structure_writer");
        tag.remove("startX");
        tag.remove("startY");
        tag.remove("startZ");
        tag.remove("endX");
        tag.remove("endY");
        tag.remove("endZ");
        clearLegacyCorners(tag);
    }

    /** 清掉旧格式（min/max）遗留的六个键，保证新旧两套坐标不会同时存在。 */
    private static void clearLegacyCorners(CompoundTag tag) {
        tag.remove("minX");
        tag.remove("maxX");
        tag.remove("minY");
        tag.remove("maxY");
        tag.remove("minZ");
        tag.remove("maxZ");
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack itemStack, UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) return InteractionResult.SUCCESS;
        ItemStack stack = player.getItemInHand(context.getHand());
        if (!player.isShiftKeyDown()) {
            addPos(stack, context.getClickedPos());
            player.containerMenu.broadcastChanges();  // 强制同步 NBT
            if (player instanceof ServerPlayer sp) {
                var pos = getPos(stack);
                int sx = 0, sy = 0, sz = 0;
                if (pos != null) {
                    sx = 1 + pos[1].getX() - pos[0].getX();
                    sy = 1 + pos[1].getY() - pos[0].getY();
                    sz = 1 + pos[1].getZ() - pos[0].getZ();
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "§a" + pos[0].toShortString() + " §7→ §c" + pos[1].toShortString()
                        + " §7| §fSize: §e" + sx + "x" + sy + "x" + sz), true);
                } else {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "§a" + context.getClickedPos().toShortString()), true);
                }
            }
        } else {
            removePos(stack);
            player.containerMenu.broadcastChanges();  // 强制同步 NBT
            if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal("§cCleared"), true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (player.isShiftKeyDown()) {
            removePos(stack);
        } else {
            if (player instanceof ServerPlayer serverPlayer) {
                HeldItemUIFactory.INSTANCE.openUI(serverPlayer, usedHand);
            }
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }
}