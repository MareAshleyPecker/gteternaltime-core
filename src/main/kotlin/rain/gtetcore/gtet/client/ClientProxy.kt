package rain.gtetcore.gtet.client

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.gtet.common.CommonProxy

/**
 * 放只有客户端需要加载的类
 * */
@OnlyIn(Dist.CLIENT)
open class ClientProxy() : CommonProxy() {
    init {
        @SuppressWarnings("deprecated")//byd这玩意为什么总要提醒，我byd是1.20.1啊，F**k
        val bus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
    }
}
