package rain.gtetcore.common;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs;
import com.gregtechceu.gtceu.common.data.GTItems;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.CreativeModeTab;
import rain.gtetcore.Gtetcore;
import com.tterrag.registrate.util.entry.RegistryEntry;

import static rain.gtetcore.api.REGISTRATE.REGISTRATE;

public class GTETCreativeModeTabs {
	public static RegistryEntry<CreativeModeTab> MACHINE = REGISTRATE.defaultCreativeTab("machine",
					builder -> builder.displayItems(new GTCreativeModeTabs.RegistrateDisplayItemsGenerator("machine", REGISTRATE))
							.icon(GTBlocks.BATTERY_LAPOTRONIC_LuV)
							.title(REGISTRATE.addLang("itemGroup", Gtetcore.id("machine"),
									Gtetcore.NAME + " Machine Containers"))
							.build())
			.register();
}
