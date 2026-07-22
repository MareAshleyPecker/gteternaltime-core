package rain.gtetcore.gtet.api.registrate

import rain.gtetcore.gtet.Gtetcore.Companion.MODID

/**
 * 全局 [ETREGISTRATE] 单例持有者。
 * @see ETREGISTRATE GTET 增强版注册器
 */
object OnlyETreg {
    /** 模组唯一的 ETREGISTRATE 实例。 */
    val ETRegistrate: ETREGISTRATE = ETREGISTRATE(MODID)
}