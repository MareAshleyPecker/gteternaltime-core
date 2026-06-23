package rain.gtetcore.gtet

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.common.Mod
import org.slf4j.Logger
import rain.gtetcore.gtet.client.ClientProxy
import rain.gtetcore.gtet.common.CommonProxy
import java.util.function.Supplier

@Mod(Gtetcore.MODID)
class Gtetcore {

    companion object {
        const val MODID = "gtetcore"
        const val NAME = "GTETCORE"
        private val LOGGER: Logger = LogUtils.getLogger()

        @JvmStatic
        fun id(name: String): ResourceLocation {
            return ResourceLocation.tryBuild(MODID, name)!!
        }
    }

    init {
        DistExecutor.unsafeRunForDist({ Supplier { ClientProxy() } }, { Supplier { CommonProxy() } })
    }

}
