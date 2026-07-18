package rain.gtetcore.gtet.init

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent
import com.gregtechceu.gtceu.api.data.chemical.material.registry.MaterialRegistry
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import rain.gtetcore.GTET.common.data.ETMaterial
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.muiltmachine.ALLMmchine
import rain.gtetcore.gtet.common.data.machine.samplemachine.ALLSmahine

/**
 * 通用代理 —— 客户端和服务端都需要加载的初始化逻辑。
 */
open class CommonProxy {

    @Suppress("unused")
    private lateinit var materialRegistry: MaterialRegistry

    init {
        @SuppressWarnings("all")
        val bus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
        bus.register(this)
        kotlinInit()
    }

    protected fun kotlinInit() {
        // 子类可重写以注入额外初始化逻辑
        GTETCreativeModeTabs.init()
    }

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        // 通用设置阶段
    }

    @SubscribeEvent
    fun registerMaterialRegistry(event: MaterialRegistryEvent?) {
        materialRegistry = GTCEuAPI.materialManager.createRegistry(Gtetcore.MODID)
    }

    @SubscribeEvent
    fun registerMaterials(event: MaterialEvent?) {
        ETMaterial.init()
    }

    @SubscribeEvent
    fun registerMachines(event: GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition>) {
        ALLMmchine.init()
        ALLSmahine.init()
    }
}

