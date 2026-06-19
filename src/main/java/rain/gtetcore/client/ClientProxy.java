package rain.gtetcore.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import rain.gtetcore.common.CommonProxy;


@OnlyIn(Dist.CLIENT)
public class ClientProxy extends CommonProxy {
	public ClientProxy() {
		super();
		@SuppressWarnings ("Deprecated")
		IEventBus eventBus =FMLJavaModLoadingContext.get ().getModEventBus ();

	}
}
