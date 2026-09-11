package rain.gtetcore.gtet.init

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.gtet.client.GTETClientCommands
import rain.gtetcore.gtet.client.StructureOverlayRenderer

/**
 * 客户端 Forge 总线监听器（独立类，避免和 mod 总线混用）。 */


/** 客户端专用代理。 */
@OnlyIn(Dist.CLIENT)
open class ClientProxy(context: FMLJavaModLoadingContext) : CommonProxy(context) {
    init {
        val bus: IEventBus = context.modEventBus
        bus.register(this)
        MinecraftForge.EVENT_BUS.register(ClientForgeEvents)
        StructureOverlayRenderer.register()
        // 对着空气 Shift+滚轮 切换结构工具工作模式
        rain.gtetcore.gtet.client.ToolScrollHandler.register()
    }

    @OnlyIn(Dist.CLIENT)
    object ClientForgeEvents {
        @SubscribeEvent
        fun onClientCommands(event: RegisterClientCommandsEvent) {
            GTETClientCommands.register(event.dispatcher)
        }
    }

}
