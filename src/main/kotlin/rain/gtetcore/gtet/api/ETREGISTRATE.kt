package rain.gtetcore.gtet.api

import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import rain.gtetcore.gtet.Gtetcore

/**
 * GTET 统一注册器。
 *
 * 所有方块 / 物品 / 标签的创建请走 [Create]。
 */
object ETREGISTRATE {

    @JvmStatic
    val etreg: GTRegistrate = GTRegistrate.create(Gtetcore.MODID)
}
