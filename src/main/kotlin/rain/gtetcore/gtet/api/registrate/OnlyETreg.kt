package rain.gtetcore.gtet.api.registrate

import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import rain.gtetcore.gtet.Gtetcore.Companion.MODID

object OnlyETreg {
    @JvmStatic
    val ETRegistrate : GTRegistrate = GTRegistrate.create(MODID)
}