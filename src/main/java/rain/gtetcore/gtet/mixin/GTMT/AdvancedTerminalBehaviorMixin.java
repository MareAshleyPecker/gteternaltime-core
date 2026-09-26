package rain.gtetcore.gtet.mixin.GTMT;

import com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rain.gtetcore.gtet.common.item.terminal.StructureBuildPlanner;
import rain.gtetcore.gtet.common.item.terminal.TerminalContext;
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings;
import rain.gtetcore.gtet.config.GTETConfig;

import java.util.List;

/**
 * GTMThings 高级终端的扩展（只剩补丁做不到的部分）。
 *
 * <p>界面本身已经改成在 GTMThings 补丁里直接画（见 {@code modpatch/gtmthings-1.6.0/ui/}）：
 * 补丁新增了右侧「分级方块（切换）/（勾选）」两块列表，直接读写终端 NBT 里的分级组数据。
 * 所以这里不再注入 {@code createWidget} 追加组件，也不再需要 {@code createUI} 记录 ItemStack。
 *
 * <p>留下的两处都是「必须夹在 GTMThings 自己的调用栈里」的事：
 * <ol>
 *   <li>{@code useOn} HEAD：把当前终端物品记进 {@link TerminalContext}
 *       —— {@code AutoBuildSetting#apply} 是在这次调用里同步跑的，那里拿不到 ItemStack；</li>
 *   <li>{@code useOn} RETURN：把这次规划出的分级组写进终端 NBT（供界面显示、供搭建时按选择替换方块）。</li>
 * </ol>
 *
 * <p>⚠️ 补丁里画的界面读的就是这里缓存的 NBT 结构，两边字段名必须一致
 * （{@link TerminalSettings} 的 {@code plan.groups} / {@code group_prefs}）。
 *
 * @author rain fox
 */
@Mixin(value = AdvancedTerminalBehavior.class, remap = false)
public abstract class AdvancedTerminalBehaviorMixin {

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
        if (GTETConfig.tierSelectEnabled()) return;
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
}
