@file:Suppress("DEPRECATION", "unused")

package rain.gtetcore.gtet

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import org.openjdk.nashorn.internal.codegen.Namespace
import org.slf4j.Logger
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.data.GTETDatagen
import rain.gtetcore.gtet.init.ClientProxy
import rain.gtetcore.gtet.init.CommonProxy
import java.util.function.Supplier

/**
 * GTET Core 主模组类。
 *
 * 配置不在这里注册：走 Forge 自带的 ForgeConfigSpec（`config/gtetcore/gtetcore-common.toml`），
 * 由 [CommonProxy] 构造时调 [rain.gtetcore.gtet.config.GTETConfig.init] 完成（registerConfig 必须在这个阶段调用）。
 *
 * 构造器参数是 Forge 注入的 [FMLJavaModLoadingContext]：1.20.1 后期版本把
 * `ModLoadingContext.get()` / `FMLJavaModLoadingContext.get()` 都标成了「待删除」，
 * 官方推荐的替代就是把它注入 mod 构造器，再用它的实例方法注册配置、取事件总线。
 *
 * @author rain fox
 */
@Mod(Gtetcore.MODID)
class Gtetcore(context: FMLJavaModLoadingContext) {

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

        @JvmStatic
        fun id(pNamespace: String,name: String): ResourceLocation {
            return ResourceLocation.tryBuild(pNamespace, name)!!
        }
    }

    init {
        // 在 mod 构造阶段挂载 LangHandler 到 Registrate LANG provider（en_us）
        // 必须先 registerRegistrate 注册事件监听器（含 GatherDataEvent），再 addDataGenerator
        ETRegistrate.registerRegistrate()
        GTETDatagen.initRegistrate()
        // 注入进来的 context 一路带到代理：配置注册、mod 事件总线都不再走已弃用的 Xxx.get()
        DistExecutor.unsafeRunForDist(
            { Supplier { ClientProxy(context) } },
            { Supplier { CommonProxy(context) } }
        )
    }
}
