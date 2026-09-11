package rain.gtetcore.gtet

import com.gregtechceu.gtceu.api.addon.GTAddon
import com.gregtechceu.gtceu.api.addon.IGTAddon
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.data.block.ETBlock
import rain.gtetcore.gtet.common.data.item.ETItems
import rain.gtetcore.gtet.common.data.machine.muiltmachine.ALLMmchine
import rain.gtetcore.gtet.common.data.machine.samplemachine.ALLSmahine
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
 * 3. [initializeAddon] — 注册物品 [ETItems]、方块 [ETBlock]、机器 [ALLMmchine] / [ALLSmahine]
 * 4. [registerElements] — 注册自定义化学元素 [ETElements]
 *
 * ## 生命周期注意
 * 物品、方块与机器都在 [initializeAddon] 中注册（这是 GTCEu 官方回调，位置在 GTM
 * `CommonProxy.init()` 末尾，材料/机器/模型都已就绪，且早于 Forge 的 RegisterEvent）；
 * 材料物品的自动生成延迟到 Forge
 * [net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent]，因为此时 GTCEu 材料注册表才完全可用。
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
        // 机器（含多方块部件「超频仓」）**只**在这里注册。
        //
        // 这里曾经是「双入口」：CommonProxy 里还挂着一个 `registerMachines` 监听器，谁先跑到谁注册。
        // 那个监听器已按「注册必须确定」的理由删掉（完整原因写在 CommonProxy 里那段注释中）：
        // GTM 的 `RegisterEvent<ResourceLocation, MachineDefinition>` 是 GTM 在自己 mod 构造期间
        // （GTCEu() → ClientProxy/CommonProxy → CommonProxy.init() → GTMachines.init() → postEvent）
        // 发出的，而 Forge 是**并行构造 mod**的，本 mod 的 CommonProxy 不一定赶得上 —— 这个竞态会让
        // 渲染态 id 的分配顺序在服务端/客户端两个 JVM 之间错位（id 顺序 = 网络协议的一部分）。
        //
        // 这里则是 GTCEu 官方的 addon 回调，位置在 GTM `CommonProxy.init()` 的最末尾
        // （`AddonFinder.getAddons().forEach(IGTAddon::initializeAddon)`），此时材料、机器、模型
        // 都已经准备好，同时仍远早于 Forge 的 RegisterEvent，Registrate 能正常收下这些条目。
        // 调用顺序固定 ⇒ 渲染态 id 顺序在两端一致。
        //
        // 幂等保护的证据：`ALLMmchine.init()` 开头就是 `if (initialized) return`
        // （ALLMmchine 第 22-23 行的 `initialized` 标志 + 第 112-113 行），重复调用只会空转，
        // 不会重复注册；`ALLSmahine.init()` 目前是空实现。
        ALLMmchine.init()
        ALLSmahine.init()
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