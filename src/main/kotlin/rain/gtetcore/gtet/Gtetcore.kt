package rain.gtetcore.gtet

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.common.Mod
import org.slf4j.Logger
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.data.GTETDatagen
import rain.gtetcore.gtet.init.ClientProxy
import rain.gtetcore.gtet.init.CommonProxy
import java.util.function.Supplier

/**
 * GTET Core 主模组类。
 *
 * @author rain fox
 */
@Mod(Gtetcore.MODID)
class Gtetcore {

    companion object {
        const val MODID = "gtetcore"
        const val NAME = "GregTech Eternal Time"

        @JvmField
        val LOGGER: Logger = LogUtils.getLogger()

        /**
         * 根据名称构建 [ResourceLocation]。
         *
         * 用于 `assets/gtetcore/textures/` 下的材质路径，
         * 或 `assets/gtetcore/` 下的任意资源。
         */
        @JvmStatic
        fun id(name: String): ResourceLocation {
            return ResourceLocation.tryBuild(MODID, name)!!
        }
    }

    init {
        // 在 mod 构造阶段挂载 LangHandler 到 Registrate LANG provider（en_us）
        // 必须先 registerRegistrate 注册事件监听器（含 GatherDataEvent），再 addDataGenerator
        // 参照 GTCEu CommonProxy.init()：registerRegistrate() → initPost()
        ETRegistrate.registerRegistrate()
        GTETDatagen.initRegistrate()
        DistExecutor.unsafeRunForDist(
            { Supplier { ClientProxy() } },
            { Supplier { CommonProxy() } }
        )
    }
}
