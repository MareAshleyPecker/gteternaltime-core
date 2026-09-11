@file:Suppress("DEPRECATION","unused")
package rain.gtetcore.gtet.util

import com.gregtechceu.gtceu.api.machine.multiblock.*
import com.gregtechceu.gtceu.api.pattern.*
import rain.gtetcore.gtet.config.GTETConfig

/**
 * 把 [GTETConfig] 的 `multiblock` 配置桥接给 GTM 多方块系统。
 *
 * GTM 补丁类（[MultiblockControllerMachine]、[MultiblockWorldSavedData]）在运行时
 * 用 `Class.forName("rain.gtetcore.gtet.util.MultiblockConfigHook").getMethod(名字).invoke(null)`
 * 读取配置；**方法名与那里的反射名一一对应，改名会直接让配置静默失效**，
 * 反射失败时 GTM 会回退到自带默认值。
 *
 * 每次调用都现读 Forge 配置的当前值，所以改完 toml 热重载后立刻生效；
 * 配置尚未加载时 [GTETConfig] 会退回默认值，不会抛异常。
 */
object MultiblockConfigHook {

    /** 获取配置的检测失败等待 tick 数。 */
    @JvmStatic
    fun getFailedWaitingTicks() = GTETConfig.checkFailedWaitingTime()

    /** 获取配置的放置后首次检测延迟 tick 数。 */
    @JvmStatic
    fun getPlacementDelayTicks() = GTETConfig.placementCheckDelay()

    /** 获取配置的卸载等待 tick 数。 */
    @JvmStatic
    fun getUnloadWaitingTicks() = GTETConfig.unloadWaitingTime()

    /** 获取配置的异步检测调度间隔（毫秒）。 */
    @JvmStatic
    fun getAsyncCheckInterval() = GTETConfig.asyncCheckInterval()

    /** 是否发送成型错误信息。 */
    @JvmStatic
    fun shouldSendErrorMessage() = GTETConfig.sendFormErrorMessage()
}
