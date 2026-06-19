package rain.gtetcore;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import rain.gtetcore.client.ClientProxy;
import rain.gtetcore.common.CommonProxy;

import static net.minecraft.resources.ResourceLocation.tryBuild;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Gtetcore.MODID)
public class Gtetcore {

	// Define mod id in a common place for everything to reference
	public static final String MODID = "gtetcore";
	public static final String NAME = "GTETCORE";
	private static final Logger LOGGER = LogUtils.getLogger ();

	public Gtetcore () {
		DistExecutor.unsafeRunForDist(() -> ClientProxy::new, () -> CommonProxy::new);
	}
	public static ResourceLocation id(String name) {
		return tryBuild(MODID, name);
	}
}
