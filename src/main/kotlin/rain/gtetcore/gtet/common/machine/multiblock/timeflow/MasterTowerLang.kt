package rain.gtetcore.gtet.common.machine.multiblock.timeflow

import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 主控塔的双语条目。
 *
 * 分两类：
 * - **静态行**（注册时就能定）：`gtetcore.machine.master_tower.tooltip.<序号>`，在注册函数里用 `LangUtil.add` 登记；
 * - **运行时行**（带数字：储量 / 容量 / 汇率 / 所有者）：这里定义键名，必须 `Component.translatable(键, 参数...)`。
 *
 * [init] 由注册函数 [rain.gtetcore.gtet.common.data.machine.multiblock.ETMasterTower.register] 调用 ——
 * 机器注册发生在数据生成之前（GTM 的机器注册窗口），所以赶得上写进 lang 文件。
 */
object MasterTowerLang {

    private const val PREFIX = "gtetcore.machine.master_tower."

    /** 储备（TF）与折算 EU。 */
    const val STORED: String = PREFIX + "stored"

    /** 容量（TF）：段数 × 每段容量。 */
    const val CAPACITY: String = PREFIX + "capacity"

    /** 当前潮汐汇率与相位。 */
    const val RATE: String = PREFIX + "rate"

    /** 所有者行。 */
    const val OWNER: String = PREFIX + "owner"

    /** 无所有者（还没被右键认领）时的所有者行。 */
    const val OWNER_NONE: String = PREFIX + "owner_none"

    /** 因「全服唯一」而没能成型时，控制器面板里的红字提示。 */
    const val DUPLICATE: String = PREFIX + "duplicate"

    /** 掉落物（塔芯）上显示的封存储备。 */
    const val SEALED: String = PREFIX + "sealed"

    /** 由注册函数调用一次。 */
    @JvmStatic
    fun init() {
        LangUtil.add(
            STORED,
            "Stored %s TF (= %s EU)",
            "储备 %s TF（= %s EU）"
        )
        LangUtil.add(
            CAPACITY,
            "Capacity %s TF (%s segments x %s TF)",
            "容量 %s TF（%s 段 × %s TF/段）"
        )
        LangUtil.add(
            RATE,
            "Tide rate %sx (phase %s)",
            "潮汐汇率 %s×（相位 %s）"
        )
        LangUtil.add(OWNER, "Owner: %s", "所有者：%s")
        LangUtil.add(OWNER_NONE, "Owner: unclaimed", "所有者：尚未认领")
        LangUtil.add(
            DUPLICATE,
            "Not formed: masterTowerUnique is on and another master tower already exists on this server.",
            "未成型：masterTowerUnique 已开启，本服务器上已经有另一座主控塔了。"
        )
        LangUtil.add(
            SEALED,
            "Sealed reserve: %s TF (= %s EU)",
            "封存储备：%s TF（= %s EU）"
        )
    }
}
