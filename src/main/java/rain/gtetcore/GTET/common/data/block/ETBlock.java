package rain.gtetcore.GTET.common.data.block;

import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.world.level.block.Block;

import rain.gtetcore.gtet.Gtetcore;
import rain.gtetcore.gtet.api.ETREGISTRATE;
import rain.gtetcore.gtet.common.GTETCreativeModeTabs;

public class ETBlock {

    public static void init() {}

    static {
        ETREGISTRATE.getETREGISTRATE().creativeModeTab(GTETCreativeModeTabs.BLOCK);
    }

    public static final BlockEntry<Block> machine_coil_cupronickel = ETREGISTRATE.createCasingBlock("machine_coil_cupronickel",
            Gtetcore.id("block/casings/machine_coil_cupronickel"));
}
