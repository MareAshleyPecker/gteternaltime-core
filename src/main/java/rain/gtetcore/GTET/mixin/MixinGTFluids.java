package rain.gtetcore.GTET.mixin;

import com.gregtechceu.gtceu.common.data.GTFluids;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import rain.gtetcore.gtet.api.registrate.OnlyETreg;
import rain.gtetcore.gtet.common.GTETCreativeModeTabs;

/**
 * 在 GTCEu 注册流体容器（桶）前设置 GTET 的 FLUID 标签，确保流体桶进入正确物品栏。
 */
@Mixin(value = GTFluids.class, remap = false)
public abstract class MixinGTFluids {

    @Inject(method = "init", at = @At("HEAD"))
    private static void onInit(CallbackInfo ci) {
        OnlyETreg.INSTANCE.getETRegistrate().creativeModeTab(GTETCreativeModeTabs.FLUID);
    }
}
