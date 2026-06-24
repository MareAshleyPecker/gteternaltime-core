package rain.gtetcore.gtet.client

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.gtet.common.CommonProxy

@OnlyIn(Dist.CLIENT)
open class ClientProxy : CommonProxy() {
    init {
        @SuppressWarnings("deprecated")
        val eventBus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
    }
}
