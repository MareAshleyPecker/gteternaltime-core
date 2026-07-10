package rain.gtetcore.gtet

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.common.Mod
import org.slf4j.Logger
import rain.gtetcore.gtet.client.ClientProxy
import rain.gtetcore.gtet.common.CommonProxy
import java.util.function.Supplier

/**
 * @author rain fox
 *
 * */
@Mod(Gtetcore.MODID)
class Gtetcore {

    companion object {
        const val MODID = "gtetcore"
        const val NAME = "GregTech Eternal Time"

        val LOGGER: Logger = LogUtils.getLogger()

        /**
         * 资源名或者材质路径
         *
         * 在resources的assets.gtetcore.textures
         * */
        fun id(name: String): ResourceLocation {
            return ResourceLocation.tryBuild(MODID, name)!!
        }
    }
    //总注册
    init {
        DistExecutor.unsafeRunForDist(
        { Supplier { ClientProxy() } },
        { Supplier { CommonProxy() } })
    }

}
