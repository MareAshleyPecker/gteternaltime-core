package rain.gtetcore.gtet.common

import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.ETREGISTRATE
import rain.gtetcore.gtet.common.config.ETConfig
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.material.ETElementMaterials
import rain.gtetcore.gtet.data.GTETDatagen

open class CommonProxy {

    init {
        @SuppressWarnings("deprecated")
        val eventBus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
        eventBus.register(this)

        Gtetcore.LOGGER.info("===== GTET Init Start =====")
        kotlinInit()
        Gtetcore.LOGGER.info("===== GTET Init Done  =====")

        @SuppressWarnings("deprecated")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ETConfig.spec)
    }

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
    }

    @SubscribeEvent
    fun modConstruct(construct: FMLConstructModEvent) {
        GTETDatagen.init()
    }

    private fun kotlinInit() {
        ETREGISTRATE.etreg.registerRegistrate()
        GTETCreativeModeTabs.init()
        ETElementMaterials.register()
        ETBlock.init()
    }
}
