package rain.gtetcore.gtet.init

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

/**
 * 客户端专用代理 —— 仅在物理客户端加载。
 * 所有仅客户端需要初始化的逻辑放在此处。
 */
@OnlyIn(Dist.CLIENT)
open class ClientProxy : CommonProxy() {

    init {
        @Suppress("DEPRECATION")
        val bus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
    }
}