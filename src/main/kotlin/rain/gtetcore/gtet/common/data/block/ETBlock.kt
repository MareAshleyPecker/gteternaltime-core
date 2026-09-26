package rain.gtetcore.gtet.common.data.block

import com.gregtechceu.gtceu.api.GTCEuAPI
import rain.gtetcore.gtet.api.registrate.OnlyETreg.ETRegistrate
import rain.gtetcore.gtet.common.block.ETBlockReg.createCoilBlock
import rain.gtetcore.gtet.common.data.GTETCreativeModeTabs

/**
 * 方块注册入口。
 *
 * 负责注册本模组的所有方块，当前主要提供线圈方块的创建与注册。
 * 所有方块自动归入 [GTETCreativeModeTabs.BLOCK] 选项卡。
 *
 * @see CoilType 线圈类型枚举
 * @see GTCEuAPI.HEATING_COILS GTCEu 线圈注册表
 */
object ETBlock {

    private fun coilBlock(){
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.BLOCK)
        createCoilBlock(CoilType.NAME)
    }

    private fun gto(){
        ETRegistrate.creativeModeTab(GTETCreativeModeTabs.GTOBLOCK)
        ETGtoCasingBlocks.init()
    }
    /**
     * 方块初始化入口，由 [rain.gtetcore.gtet.init.CommonProxy.kotlinInit] 调用。
     * ⚠️ [rain.gtetcore.gtet.init.CommonProxy] 与 [rain.gtetcore.gtet.ETGTAddon] 都会调，注册动作必须幂等。
     */
    fun init() {
        coilBlock()
        gto()
        // 随本 mod 分发的 GTOCore 机壳贴图 → 批量注册为机壳方块（数据表见 ETGtoCasingBlocks）
    }

}
