package rain.gtetcore;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import rain.gtetcore.client.ClientProxy;
import rain.gtetcore.common.CommonProxy;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Gtetcore.MODID)
public class Gtetcore {

	// Define mod id in a common place for everything to reference
	public static final String MODID = "gtetcore";
	private static final Logger LOGGER = LogUtils.getLogger ();

	public Gtetcore () {
		DistExecutor.unsafeRunForDist(() -> ClientProxy::new, () -> () -> new CommonProxy ());
	}
}
