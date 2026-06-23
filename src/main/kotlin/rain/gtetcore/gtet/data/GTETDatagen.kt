package rain.gtetcore.gtet.data

import net.minecraftforge.data.event.GatherDataEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object GTETDatagen {

    @JvmStatic
    @SubscribeEvent
    fun gatherData(event: GatherDataEvent) {
    }

    @JvmStatic
    fun init() {}
}
