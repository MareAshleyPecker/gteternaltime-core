package rain.gtetcore.gtet.common

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry
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

    @SuppressWarnings("all")
    lateinit var MATERIAL_REGISTRY : MaterialRegistry

    init {
        @SuppressWarnings("all")
        val bus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
        bus.register(this)
        kotlinInit()
        // bus.addGenericListener(GTRecipeType::class.java,) { _ :GTCEuAPI.RegisterEvent<ResourceLocation?, GTRecipeType?>? ->
        // }
        // bus.addGenericListener(RecipeConditionType::class.java,) { _ : GTCEuAPI.RegisterEvent<ResourceLocation?, MachineDefinition?> ->
        // }
        // bus.addGenericListener(MachineDefinition::class.java,) { _ : GTCEuAPI.RegisterEvent<String?, RecipeConditionType<*>?>? ->
        // }
        // bus.addGenericListener(RecipeCapability::class.java, ){ _ :GTCEuAPI.RegisterEvent.String<RecipeCapability<*>?> ->
        // }
    }

    protected fun kotlinInit() {
        GTETCreativeModeTabs.init()
        ETBlock.init()
        GTETDatagen.init()
        ETREGISTRATE.ETRegistrate.registerRegistrate()
    }

    @SubscribeEvent
    fun onCommonSetup(common: FMLCommonSetupEvent) {

    }

    // GT注册部分
    @SubscribeEvent
    fun registerMaterialRegistry(event: MaterialRegistryEvent?) {
        MATERIAL_REGISTRY = GTCEuAPI.materialManager.createRegistry(Gtetcore.MODID);
    }
    @SubscribeEvent
    fun registerMaterials(event: MaterialEvent?) {
        ETMaterial.init()
    }

}
