package rain.gtetcore.common;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

public class CommonProxy {

	public CommonProxy() {
		@SuppressWarnings ("Deprecated")
		IEventBus eventBus = FMLJavaModLoadingContext.get ().getModEventBus();

	}

}
