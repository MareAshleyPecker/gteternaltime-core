package rain.gtetcore.common;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.SingleTickProfiler;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import rain.gtetcore.data.GTETDatagen;

public class CommonProxy {

	public CommonProxy() {
		@SuppressWarnings ("Deprecated")
		IEventBus eventBus = FMLJavaModLoadingContext.get ().getModEventBus ();
		eventBus.register (this);
		init ();
	}

	@SubscribeEvent
	public void onCommonSetup(FMLCommonSetupEvent event) {}

	public static void init(){

	}

	private void registerMachines(GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition> event) {

	}


}
