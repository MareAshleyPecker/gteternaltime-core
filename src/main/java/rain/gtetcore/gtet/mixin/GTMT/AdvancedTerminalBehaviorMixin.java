package rain.gtetcore.gtet.mixin.GTMT;

import com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior;

import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import rain.gtetcore.gtet.config.GTETConfig;
import rain.gtetcore.gtet.common.item.terminal.StructureBuildPlanner;
import rain.gtetcore.gtet.common.item.terminal.TerminalContext;
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings;

import java.util.List;

/**
 * GTMThings 高级终端的扩展（纯 Mixin，基于它现有 UI 追加）。
 *
 * <p>两处注入：
 * <ol>
 *   <li>{@code createUI}：把当前终端物品记下来（{@code createWidget} 拿不到 ItemStack）；</li>
 *   <li>{@code createWidget}：在它原有设置项下面追加「分级方块」一栏 —— 每个分级组一行
 *       （当前选择 + 循环切换按钮），控件沿用 LDLib，和原 UI 一致；</li>
 *   <li>{@code useOn}：右键控制器跑完原逻辑后，把这次规划的分级组缓存进终端 NBT
 *       （供界面显示；搭建时按选择替换方块）。</li>
 * </ol>
 *
 * <p>原来的「线圈等级」只认线圈，这里的分级组直接来自 pattern 的真实候选，
 * 所以聚变玻璃、火箱、分级机壳同样可选。
 *
 * @author rain fox
 */
@Mixin(value = AdvancedTerminalBehavior.class, remap = false)
public abstract class AdvancedTerminalBehaviorMixin {

    @Unique
    private ItemStack gtetcore$terminal = ItemStack.EMPTY;

    /** 打开界面时记下终端物品。 */
    @Inject(method = "createUI", at = @At("RETURN"), remap = false)
    private void gtetcore$captureStack(com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory.HeldItemHolder holder,
                                       Player player,
                                       CallbackInfoReturnable<com.lowdragmc.lowdraglib.gui.modular.ModularUI> cir) {
        this.gtetcore$terminal = holder.getHeld();
    }

    /** 在原有 UI 下面追加分级方块选择栏。 */
    @Inject(method = "createWidget", at = @At("RETURN"), remap = false)
    private void gtetcore$addTierRows(Player player, CallbackInfoReturnable<Widget> cir) {
        if (!(cir.getReturnValue() instanceof WidgetGroup root)) return;
        ItemStack terminal = this.gtetcore$terminal;
        if (terminal.isEmpty() || !GTETConfig.tierSelectEnabled()) return;

        List<TerminalSettings.GroupView> groups = TerminalSettings.cachedGroups(terminal);

        var title = new LabelWidget(8, 96, () -> Component.translatable("gtetcore.terminal.ui.groups").getString());
        title.setColor(0xFAF9F6);
        root.addWidget(title);

        if (groups.isEmpty()) {
            var hint = new LabelWidget(8, 108, () -> Component.translatable("gtetcore.terminal.ui.no_groups").getString());
            hint.setColor(0x808080);
            root.addWidget(hint);
            return;
        }

        int y = 108;
        for (TerminalSettings.GroupView group : groups) {
            if (y > 150) break;
            final int rowY = y;
            var label = new LabelWidget(8, rowY, () -> gteternaltime_core$shortName(group.chosen()));
            label.setColor(0xE0E0E0);
            root.addWidget(label);
            root.addWidget(new ButtonWidget(120, rowY - 4, 50, 14,
                    new GuiTextureGroup(com.gregtechceu.gtceu.api.gui.GuiTextures.BUTTON,
                            new TextTexture(Component.translatable("gtetcore.terminal.ui.cycle").getString())),
                    clickData -> TerminalSettings.cycleChoice(terminal, group)));
            y += 16;
        }
    }

    /** 进入 useOn 时先记下终端：AutoBuildSetting#apply 在它的调用栈里同步跑，要读这份偏好。 */
    @Inject(method = "useOn", at = @At("HEAD"), remap = false)
    private void gtetcore$captureTerminal(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Player player = context.getPlayer();
        TerminalContext.set(player == null ? ItemStack.EMPTY : player.getMainHandItem());
    }

    /** 原有的 useOn 跑完后缓存本次规划的分级组。 */
    @Inject(method = "useOn", at = @At("RETURN"), remap = false)
    private void gtetcore$cachePlan(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        TerminalContext.clear();
        if (!GTETConfig.tierSelectEnabled()) return;
        Player player = context.getPlayer();
        if (player == null || context.getLevel().isClientSide) return;

        ItemStack stack = player.getMainHandItem();
        var machine = com.gregtechceu.gtceu.api.machine.MetaMachine.getMachine(context.getLevel(), context.getClickedPos());
        if (!(machine instanceof com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController controller)) return;
        var pattern = controller.getPattern();
        if (pattern == null) return;

        StructureBuildPlanner.Plan plan =
                StructureBuildPlanner.plan(pattern, controller.getMultiblockState(), context.getLevel());
        java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (StructureBuildPlanner.Group group : plan.tieredGroups()) {
            List<String> ids = new java.util.ArrayList<>();
            for (ItemStack candidate : group.candidates()) {
                var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(candidate.getItem());
                ids.add(key == null ? "minecraft:air" : key.toString());
            }
            groups.put(group.key(), ids);
        }
        TerminalSettings.cachePlan(stack, context.getClickedPos(), context.getLevel().dimension().location(), groups);
    }

    @Unique
    private static String gteternaltime_core$shortName(String itemId) {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(net.minecraft.resources.ResourceLocation.tryParse(itemId));
        return item == null || item == net.minecraft.world.item.Items.AIR
                ? itemId
                : item.getDescription().getString();
    }
}
