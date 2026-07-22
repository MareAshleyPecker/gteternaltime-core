package rain.gtetcore.GTET.common;

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
import rain.gtetcore.GTET.util.RegistriesUtil;
import rain.gtetcore.gtet.Gtetcore;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class StructureWriteBehavior implements IItemUIFactory {

    public static final StructureWriteBehavior INSTANCE = new StructureWriteBehavior();

    /** 结构工具物品引用，注册时赋值，供渲染器直接比对。 */
    public static Item STRUCTURE_TOOLS_ITEM = null;

    protected StructureWriteBehavior() {}

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder playerInventoryHolder,
                              Player entityPlayer) {
        var scaleLabel = new LabelWidget(7, 7, () -> {
            int x = 0, y = 0, z = 0;
            var pos = getPos(playerInventoryHolder.getHeld());
            if (pos != null) {
                x = 1 + pos[1].getX() - pos[0].getX();
                y = 1 + pos[1].getY() - pos[0].getY();
                z = 1 + pos[1].getZ() - pos[0].getZ();
            }
            return String.format("Scale: X:%d Y:%d Z:%d", x, y, z);
        });
        scaleLabel.setColor(0xFAF9F6);

        var orderLabel = new LabelWidget(7, 20, () -> {
            var dirs = DebugBlockPattern.getDir(getDir(playerInventoryHolder.getHeld()));
            return String.format("方向: 正:%s 侧:%s 竖:%s", dirs[0].name(), dirs[1].name(), dirs[2].name());
        });
        orderLabel.setColor(0xFAF9F6);

        var container = new WidgetGroup(8, 8, 160, 54);
        container.addWidget(new ImageWidget(4, 4, 152, 46, GuiTextures.DISPLAY));
        container.addWidget(scaleLabel);
        container.addWidget(orderLabel);
        container.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return new ModularUI(176, 120, playerInventoryHolder, entityPlayer)
                .background(GuiTextures.BACKGROUND)
                .widget(container)
                .widget(new ButtonWidget(9, 91, 158, 20, new GuiTextureGroup(
                                GuiTextures.BUTTON,
                                new TextTexture("Export")),
                        clickData -> export(playerInventoryHolder)))
                .widget(new ButtonWidget(9, 68, 50, 20, new GuiTextureGroup(
                                GuiTextures.BUTTON,
                                new TextTexture("Rot X")),
                        clickData -> changeDirX(playerInventoryHolder)))
                .widget(new ButtonWidget(63, 68, 50, 20, new GuiTextureGroup(
                                GuiTextures.BUTTON,
                                new TextTexture("Rot Y")),
                        clickData -> changeDirY(playerInventoryHolder)))
                .widget(new ButtonWidget(117, 68, 50, 20, new GuiTextureGroup(
                                GuiTextures.BUTTON,
                                new TextTexture("Rot Z")),
                        clickData -> changeDirZ(playerInventoryHolder)));
    }

    @SuppressWarnings("all")
    private void export(HeldItemUIFactory.HeldItemHolder playerInventoryHolder) {
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
            File logDir = new File("logs/bp");
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
        // 优先直接用引用比对
        if (STRUCTURE_TOOLS_ITEM != null && stack.getItem() == STRUCTURE_TOOLS_ITEM) return true;
        // 回退：检查 components 列表（兼容旧版或异常场景）
        if (stack.getItem() instanceof ComponentItem item) {
            return item.getComponents().contains(INSTANCE);
        }
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

    public static BlockPos[] getPos(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains("structure_writer", 10)) return null;
        CompoundTag tag = root.getCompound("structure_writer");
        if (!tag.contains("minX")) return null;
        return new BlockPos[] {
                new BlockPos(tag.getInt("minX"), tag.getInt("minY"), tag.getInt("minZ")),
                new BlockPos(tag.getInt("maxX"), tag.getInt("maxY"), tag.getInt("maxZ"))
        };
    }

    public static void addPos(ItemStack stack, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTagElement("structure_writer");
        if (!tag.contains("minX") || tag.getInt("minX") > pos.getX()) {
            tag.putInt("minX", pos.getX());
        }
        if (!tag.contains("maxX") || tag.getInt("maxX") < pos.getX()) {
            tag.putInt("maxX", pos.getX());
        }

        if (!tag.contains("minY") || tag.getInt("minY") > pos.getY()) {
            tag.putInt("minY", pos.getY());
        }
        if (!tag.contains("maxY") || tag.getInt("maxY") < pos.getY()) {
            tag.putInt("maxY", pos.getY());
        }

        if (!tag.contains("minZ") || tag.getInt("minZ") > pos.getZ()) {
            tag.putInt("minZ", pos.getZ());
        }
        if (!tag.contains("maxZ") || tag.getInt("maxZ") < pos.getZ()) {
            tag.putInt("maxZ", pos.getZ());
        }
    }

    public static void removePos(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTagElement("structure_writer");
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
            if (player instanceof ServerPlayer sp) {
                var pos = getPos(stack);
                int sx = 0;if (pos != null) {sx = 1 + pos[1].getX() - pos[0].getX();}
                int sy = 0;if (pos != null) {sy = 1 + pos[1].getY() - pos[0].getY();}
                int sz = 0;if (pos != null) {sz = 1 + pos[1].getZ() - pos[0].getZ();}
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§aPos: " + context.getClickedPos().toShortString() + " | Size: " + sx + "x" + sy + "x" + sz), true);
            }
        } else {
            removePos(stack);
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