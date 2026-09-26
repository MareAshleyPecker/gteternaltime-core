package rain.gtetcore.gtet.mixin.GTMT;

import com.gregtechceu.gtceu.common.block.CoilBlock;
import com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rain.gtetcore.gtet.common.item.terminal.StructureBuildPlanner;
import rain.gtetcore.gtet.common.item.terminal.TerminalContext;
import rain.gtetcore.gtet.common.item.terminal.TerminalSettings;
import rain.gtetcore.gtet.config.GTETConfig;

import java.util.List;

/**
 * 让 GTMThings 的自动搭建支持「按分级组选方块」。
 *
 * <p>{@code AutoBuildSetting#apply(BlockInfo[])} 原来只对线圈做等级替换，
 * 返回的候选列表里第一条能拿到就用第一条。这里在它返回后，按<b>同一套分组键</b>
 * （{@link StructureBuildPlanner#groupKey}）查终端 NBT 里的选择，
 * 命中就把选中的方块<b>提到候选列表最前面</b> —— 既保证优先用它，
 * 又保留其余候选作为背包/AE 里的兜底。
 *
 * <p>⚠️ 只按组键取是不够的：终端右侧面板里那 6 类静态组（{@link TerminalSettings} 写入的
 * 线圈 / 能源仓 / 超频仓 / 线程仓 / 并行仓 / 维护仓）算出来的键，与这里拿到的候选集算出来的键
 * <b>不保证相同</b>（典型是线圈：本方法上游会把候选的最后一档砍掉）。所以这里统一走
 * {@link TerminalSettings#lookupPreference} —— 它先试组键、再按「候选集包含关系」回退匹配。
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
        if (candidates == null || candidates.isEmpty()) return;

        String groupKey = StructureBuildPlanner.groupKey(candidates);
        String wanted = TerminalSettings.lookupPreference(terminal, groupKey, candidates);
        if (wanted == null) return;

        int index = gteternaltime_core$indexOf(candidates, wanted);
        if (index == 0) return;                 // 已经在最前面，不用动
        if (index > 0) {
            candidates.add(0, candidates.remove(index));
            return;
        }

        // 偏好的那一档不在候选里：只有「整格候选都是线圈」才把它补回候选列表最前面。
        // ⚠️ 为什么只对线圈格这么做：GTMThings 的 apply 组装线圈候选时写的是
        // `for (i = 0; i < blockInfos.length - 1; i++)`，也就是**把最后一档（最高级线圈）砍掉**，
        // 于是玩家在面板里选了最高级线圈，这里根本取不到那一档 —— 不补回来就等于没选。
        // 线圈谓词（GTCEu 的 Predicates.heatingCoils）本来就接受任何一级线圈，补回来是安全的；
        // 其它格子的候选是谓词给的完整列表，往里塞谓词不接受的方块只会让结构永远不成型。
        if (gteternaltime_core$allCoils(candidates)) {
            ItemStack extra = StructureBuildPlanner.itemStackOf(wanted);
            if (extra != null && !extra.isEmpty()) candidates.add(0, extra);
        }
    }

    /** 候选里注册名等于 {@code itemId} 的下标；没有返回 -1。 */
    @Unique
    private static int gteternaltime_core$indexOf(List<ItemStack> candidates, String itemId) {
        for (int i = 0; i < candidates.size(); i++) {
            if (itemId.equals(StructureBuildPlanner.itemId(candidates.get(i)))) return i;
        }
        return -1;
    }

    /** 整格候选是不是全是线圈方块。 */
    @Unique
    private static boolean gteternaltime_core$allCoils(List<ItemStack> candidates) {
        for (ItemStack candidate : candidates) {
            if (!(candidate.getItem() instanceof BlockItem blockItem) ||
                    !(blockItem.getBlock() instanceof CoilBlock)) {
                return false;
            }
        }
        return true;
    }
}
