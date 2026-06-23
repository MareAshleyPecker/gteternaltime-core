package rain.gtetcore.gtet.common

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.common.data.GTRecipes
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.GTET.common.data.block.ETBlock
import rain.gtetcore.gtet.api.ETREGISTRATE
import rain.gtetcore.gtet.data.GTETDatagen
import java.util.function.Consumer

open class CommonProxy {

    init {
        @SuppressWarnings("Deprecated")
        val eventBus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
        eventBus.register(this)
        eventBus.addListener(EventPriority.HIGHEST) { event: FMLCommonSetupEvent? -> event?.let { CommonProxy().onCommonSetup(it) } }
        eventBus.addListener<FMLConstructModEvent?>(Consumer { construct: FMLConstructModEvent? -> construct?.let { CommonProxy().modConstruct(it) }})
        init()
    }

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {

    }

    fun modConstruct(construct : FMLConstructModEvent) {
        GTETDatagen.init()
    }

    private fun registerMachines(machines: GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition>) {}

    private fun registerRecipes(recipes: GTCEuAPI.RegisterEvent<GTRecipeBuilder, GTRecipe>) {}

    private fun init() {
        ETREGISTRATE.ETREGISTRATE.registerRegistrate()
        GTETCreativeModeTabs.init()
        ETBlock.init()
    }
}
