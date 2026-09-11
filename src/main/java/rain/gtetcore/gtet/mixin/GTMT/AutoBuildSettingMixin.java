package rain.gtetcore.gtet.mixin.GTMT;

import com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior;
import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.registries.ForgeRegistries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import rain.gtetcore.gtet.config.GTETConfig;
import rain.gtetcore.gtet.common.item.terminal.StructureBuildPlanner;
import rain.gtetcore.gtet.common.item.terminal.TerminalContext;
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings;

import java.util.List;

/**
 * 让 GTMThings 的自动搭建支持「按分级组选方块」。
 *
 * <p>{@code AutoBuildSetting#apply(BlockInfo[])} 原来只对线圈做等级替换，
 * 返回的候选列表里第一条能拿到就用第一条。这里在它返回后，
 * 按<b>同一套分组键</b>（{@link StructureBuildPlanner#groupKey}）查终端 NBT 里的选择，
 * 命中就把选中的方块<b>提到候选列表最前面</b> —— 既保证优先用它，
 * 又保留其余候选作为背包/AE 里的兜底。
 *
 * <p>终端物品取不到（该方法签名里没有），所以走 {@link TerminalContext}
 * 上一步在 {@code useOn} 里记下的当前终端。
 *
 * @author rain fox
 */
@Mixin(value = AdvancedTerminalBehavior.AutoBuildSetting.class, remap = false)
public abstract class AutoBuildSettingMixin {

    @Inject(method = "apply", at = @At("RETURN"), remap = false)
    private void gtetcore$applyTierPreference(BlockInfo[] infos, CallbackInfoReturnable<List<ItemStack>> cir) {
        if (!GTETConfig.tierSelectEnabled()) return;

        ItemStack terminal = TerminalContext.terminal();
        if (terminal.isEmpty()) return;

        List<ItemStack> candidates = cir.getReturnValue();
        if (candidates == null || candidates.size() < 2) return;

        String groupKey = StructureBuildPlanner.groupKey(candidates);
        String wanted = TerminalSettings.getPreferences(terminal).get(groupKey);
        if (wanted == null) return;

        for (ItemStack candidate : candidates) {
            var key = ForgeRegistries.ITEMS.getKey(candidate.getItem());
            if (key != null && key.toString().equals(wanted)) {
                candidates.remove(candidate);
                candidates.add(0, candidate);
                return;
            }
        }
    }
}
