package rain.gtetcore.gtet.common.data.machine

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import rain.gtetcore.gtet.api.registrate.OnlyETreg
import rain.gtetcore.gtet.common.data.GTETCreativeModeTabs
import rain.gtetcore.gtet.common.data.machine.multiblock.ETMasterTower
import rain.gtetcore.gtet.common.data.machine.multiblock.ETTestMultiblocks
import rain.gtetcore.gtet.common.data.machine.multiblock.modular.ETModularTestMultiblocks

/**
 * 多方块机器的注册入口（六种部件仓在 `hatch.ALLSmachine`），进 [rain.gtetcore.gtet.common.data.GTETCreativeModeTabs.MULTIBLOCK] 页。
 *
 * 归属哪个创造页由 [rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate] 的「当前默认页」在注册那一刻决定（GTET 的创造页用
 * `RegistrateDisplayItemsGenerator` 扫自己那一份）。
 *
 * ⚠️ 要在部件仓注册**之后**调用：测试机的能力方块表依赖线程仓已经注册。
 *
 * @author rain fox
 */
object ALLMmachine {

    /** 多方块测试机定义。 */
    var TEST_MULTIBLOCK: MultiblockMachineDefinition? = null
        private set

    /** 模块化测试机定义（模块物品 → 等级 → 结构 + 配方上限）。 */
    var MODULAR_TEST_MACHINE: MultiblockMachineDefinition? = null
        private set

    /** 主控塔定义（时间流 TF 的收储与换汇，见 [rain.gtetcore.gtet.common.machine.multiblock.timeflow.MasterTowerMachine]）。 */
    var MASTER_TOWER: MultiblockMachineDefinition? = null
        private set

    /** 注册全部多方块机器。 */
    fun init() {
        registerMultiblocks()

    }

    /** 多方块机器 → [rain.gtetcore.gtet.common.data.GTETCreativeModeTabs.MULTIBLOCK]。 */
    fun registerMultiblocks() {
        OnlyETreg.ETRegistrate.creativeModeTab(GTETCreativeModeTabs.MULTIBLOCK)
        TEST_MULTIBLOCK = ETTestMultiblocks.register(OnlyETreg.ETRegistrate)
        MODULAR_TEST_MACHINE = ETModularTestMultiblocks.register(OnlyETreg.ETRegistrate)
        MASTER_TOWER = ETMasterTower.register(OnlyETreg.ETRegistrate)
    }
}