package rain.gtetcore.GTET.mixin;

import com.gregtechceu.gtceu.common.data.GTMaterialBlocks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import rain.gtetcore.gtet.api.registrate.OnlyETreg;
import rain.gtetcore.gtet.common.GTETCreativeModeTabs;

/**
 * 在 GTCEu 生成矿石方块前设置 GTET 的 ORE 标签，确保矿石方块物品进入正确物品栏。
 */
@Mixin(value = GTMaterialBlocks.class, remap = false)
public abstract class MixinGTMaterialOreBlocks {

    @Inject(method = "generateOreBlocks", at = @At("HEAD"))
    private static void onGenerateOreBlocks(CallbackInfo ci) {
        OnlyETreg.INSTANCE.getETRegistrate().creativeModeTab(GTETCreativeModeTabs.ORE);
    }
}
