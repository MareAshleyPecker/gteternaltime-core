package rain.gtetcore.gtet

import com.gregtechceu.gtceu.api.addon.GTAddon
import com.gregtechceu.gtceu.api.addon.IGTAddon
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.data.item.ETItems
import rain.gtetcore.gtet.common.material.ETElements

/**
 * GTET 的 GTCEu 插件入口。
 *
 * 通过 [@GTAddon][GTAddon] 注解注册到 GTCEu 的插件系统，
 * 在 GTCEu 加载时自动回调各生命周期方法。
 *
 * ## 初始化流程
 * 1. [addonModId] — 返回模组 ID
 * 2. [getRegistrate] — 提供统一的 [GTRegistrate] 注册实例
 * 3. [initializeAddon] — 注册物品 [ETItems] 和方块 [ETBlock]
 * 4. [registerElements] — 注册自定义化学元素 [ETElements]
 *
 * ## 生命周期注意
 * 物品和方块在 [initializeAddon] 中注册，材料物品的自动
 * 生成延迟到 Forge [net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent]，
 * 因为此时 GTCEu 材料注册表才完全可用。
 *
 * @see OnlyETreg 全局注册表单例
 */
@GTAddon
@SuppressWarnings("all")
open class ETGTAddon : IGTAddon {

    /** 返回模组共享的 [GTRegistrate] 注册实例。 */
    override fun getRegistrate(): GTRegistrate {
        return OnlyETreg.ETRegistrate
    }

    /** 初始化物品和方块注册。此方法由 GTCEu 在加载时回调。 */
    override fun initializeAddon() {
        ETItems.init()
        ETBlock.init()
    }

    /** 返回本模组的 MODID。 */
    override fun addonModId(): String {
        return Gtetcore.MODID
    }

    /** 注册自定义化学元素。 */
    override fun registerElements() {
        ETElements.init()
    }
}