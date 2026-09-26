package rain.gtetcore.gtet.api.capability

import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility

/**
 * GTET 自己的多方块部件能力（[PartAbility]）表。
 *
 * ## 为什么能直接 new
 * 查过 GTM 7.5.3 源码：`PartAbility` 的构造函数就是普通的
 * `public PartAbility(String name)`，内部只持有一个 `name` 与一个「tier → 方块集合」的注册表，
 * 没有全局注册表、也没有单例限制。所以 GTET 侧直接 `PartAbility("gtet_thread_hatch")`
 * 即可新增能力，**不需要**用 mixin 往 `PartAbility` 里塞静态字段，也不需要借用 GTM 的注册方式。
 *
 * 真正把这批方块登记进能力表的是 `MachineBuilder.abilities(...)`：
 * 它在方块注册回调里执行 `ability.register(tier, block)`。
 *
 * ⚠️ 新增 `THREAD_HATCH` 而不是复用 `OVERCLOCK_HATCH` / `PARALLEL_HATCH`：「超频」与「线程」是两件
 * 正交的事（可以只装其一、也可以都装），复用一个能力会让它们在结构里互斥。
 *
 * @author rain fox
 */
object ETPartAbility {

    /**
     * 超频仓专用能力。
     *
     * 名字带 `gtet_` 前缀，避免和 GTM / 其它 addon 的能力重名（能力表按对象身份区分，
     * 名字只用于调试与日志）。
     */
    @JvmField
    val OVERCLOCK_HATCH: PartAbility = PartAbility("gtet_overclock_hatch")

    /**
     * 线程仓专用能力。
     *
     * **必须是独立能力**，不得复用 [com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.PARALLEL_HATCH]：
     * 复用会让线程仓和并行仓在结构里互斥，而线程仓的语义恰恰是「**在并行仓之上**再叠一层线程」。
     * 名称同样带 `gtet_` 前缀。
     */
    @JvmField
    val THREAD_HATCH: PartAbility = PartAbility("gtet_thread_hatch")
}
