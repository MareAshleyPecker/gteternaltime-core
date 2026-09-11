package rain.gtetcore.gtet.integration.jade

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import rain.gtetcore.gtet.integration.jade.provider.ThreadedRecipeLogicProvider
import snownee.jade.api.IWailaClientRegistration
import snownee.jade.api.IWailaCommonRegistration
import snownee.jade.api.IWailaPlugin
import snownee.jade.api.WailaPlugin

/**
 * GTET 的 Jade 插件：只负责**注册** GTET 自己的 provider。
 *
 * ## 注册入口为什么是注解、而不是在 `CommonProxy` / `ETGTAddon` 里手调一次
 * 客户端专属注册不能自己拉一条初始化链 —— Jade 的插件发现机制本来就是一整套：
 * `snownee.jade.util.CommonProxy#loadComplete` 在 `FMLLoadCompleteEvent` 里遍历
 * `ModList.get().getAllScanData()`，筛出**类上带 `@WailaPlugin` 注解**的类
 * （注解 `value()` 为空则无条件加载，非空则要求同名 mod 已加载），反射 new 出来，然后
 * 先调 [IWailaPlugin.register]（两端），**只有物理客户端**才调 [IWailaPlugin.registerClient]
 * （判据是 `CommonProxy.isPhysicallyClient()`）。
 * 也就是说：这个类加上 [WailaPlugin] 注解就等于注册完成，
 * 在 `CommonProxy` 里再手写一次注册会变成**重复注册**（同一个 uid 注册两遍），不是「配合现有链路」。
 *
 * GTCEu 自己的 Jade 集成（`com.gregtechceu.gtceu.integration.jade.GTJadePlugin`）用的就是同一个注解，
 * 本类的注册写法（先 `registerBlockDataProvider`、再 `registerBlockComponent`，
 * 且两者注册**同一个 provider 类的不同实例**）与它保持一致。
 *
 * ## 客户端安全
 * 本类与 [ThreadedRecipeLogicProvider] 都只依赖 Jade 的 API 与 MC 的**通用**类
 * （`Block` / `BlockEntity` / `Component` / `CompoundTag`），没有任何 `net.minecraft.client.*`，
 * 所以专用服务端加载本类也不会因为缺类而炸；[IWailaPlugin.registerClient] 在服务端根本不会被调到。
 *
 * ## 思路来源
 * - 【借鉴形状】GTM 7.5.3 的 `GTJadePlugin`（`@WailaPlugin` + 两条注册各来一遍）——
 *   注册的**结构**照它写；provider 本身（线程口径）见 [ThreadedRecipeLogicProvider] 的类注释。
 * - 【自研】把「为什么不在 CommonProxy 里注册」这段结论写进注释（Jade 的注解扫描在
 *   `CommonProxy#loadComplete` 里，见上）—— GTM 那边没有对应说明。
 *
 * @author rain fox
 */
@WailaPlugin
class GTETJadePlugin : IWailaPlugin {

    /** 两端都注册一次「服务端数据」：客户端要按 uid 知道该向服务端要哪些数据。 */
    override fun register(registration: IWailaCommonRegistration) {
        registration.registerBlockDataProvider(ThreadedRecipeLogicProvider(), BlockEntity::class.java)
    }

    /** 客户端注册「提示渲染」：与上面那个 provider 是同一个 uid。 */
    override fun registerClient(registration: IWailaClientRegistration) {
        registration.registerBlockComponent(ThreadedRecipeLogicProvider(), Block::class.java)
    }
}
