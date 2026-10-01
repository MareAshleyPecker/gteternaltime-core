package rain.gtetcore.gtet.init

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.gtet.client.GTETClientCommands
import rain.gtetcore.gtet.client.StructureOverlayRenderer
import rain.gtetcore.gtet.studio.integration.StudioClientHooks
import rain.gtetcore.gtet.studio.integration.StudioDynamicRenders

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
        // 机器渲染工作室：DynamicRenderType 必须赶在「datagen 编码机器模型」与
        // 「运行时解码机器模型」之前登记，而客户端 mod 构造阶段早于两者（GTM 自己也是在这个阶段登记的）。
        StudioDynamicRenders.register()
    }

    /**
     * 资源重载（F3+T）时把工作室的模型标脏。
     *
     * 这里**只标脏、不读文件**：`ObjLoader` 自己也是资源重载监听器、会清它的 materialCache，
     * 监听器之间的先后顺序没有保证，早读会拿到清缓存之前的旧 MTL。真正重读推迟到下一次渲染。
     */
    @SubscribeEvent
    fun onRegisterClientReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(StudioClientHooks.RELOAD_LISTENER)
    }

    @OnlyIn(Dist.CLIENT)
    object ClientForgeEvents {
        @SubscribeEvent
        fun onClientCommands(event: RegisterClientCommandsEvent) {
            GTETClientCommands.register(event.dispatcher)
            // 工作室的 /gtetstudio reload | list
            StudioClientHooks.registerCommands(event.dispatcher)
        }
    }

}