package rain.gtetcore.gtet.common

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.api.registrate.ETREGISTRATE
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.material.ETMaterial
import rain.gtetcore.gtet.data.GTETDatagen


/**
 * 放客户端和服务端都需要加载的类
 * */
open class CommonProxy() {

    lateinit var MATERIAL_REGISTRY : MaterialRegistry

    init {
        @SuppressWarnings("deprecated")
        val bus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
        bus.register(this)
        kotlinInit()
        bus.addGenericListener(GTRecipeType::class.java, this::registerRecipeTypes)
        bus.addGenericListener(RecipeConditionType::class.java, this::registerRecipeConditions)
        bus.addGenericListener(MachineDefinition::class.java, this::registerMachines)
        bus.addGenericListener(RecipeCapability::class.java, this::registerRecipeCapabilities)
    }


    private fun kotlinInit() {
        GTETCreativeModeTabs.init()
        ETBlock.init()
        GTETDatagen.init()
        ETREGISTRATE.ETRegistrate.registerRegistrate()
    }

    @SubscribeEvent
    private fun onCommonSetup(common: FMLCommonSetupEvent) {

    }

    // GT注册部分
    @SubscribeEvent
    private fun registerMaterialRegistry(event: MaterialRegistryEvent?) {
        MATERIAL_REGISTRY = GTCEuAPI.materialManager.createRegistry(Gtetcore.MODID);
    }
    @SubscribeEvent
    private fun registerMaterials(event: MaterialEvent?) {
        ETMaterial.init()
    }
    private fun registerRecipeTypes(recipe_types: GTCEuAPI.RegisterEvent<ResourceLocation?, GTRecipeType?>?) {

    }
    private fun registerMachines(machine: GTCEuAPI.RegisterEvent<ResourceLocation?, MachineDefinition?>) {

    }
    private fun registerRecipeConditions(recipe_conditions: GTCEuAPI.RegisterEvent<String?, RecipeConditionType<*>?>?) {

    }
    private fun registerRecipeCapabilities(recipe_capabilities: GTCEuAPI.RegisterEvent.String<RecipeCapability<*>?>) {

    }


}
