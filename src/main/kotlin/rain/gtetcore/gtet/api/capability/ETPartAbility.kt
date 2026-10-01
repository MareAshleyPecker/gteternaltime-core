package rain.gtetcore.gtet.api.capability

import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import rain.gtetcore.gtet.api.capability.ETPartAbility.OVERCLOCK_HATCH

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

    /**
     * 时序仓（TF 供给仓）专用能力 —— **「这台多方块支持 TF」的标记位**。
     *
     * 设定 §2.3 的投放规则是「TF 只给支持 TF 的机器或含支持 TF 的多方块」，判定就落在这条能力上：
     * 结构谓词里写了 `Predicates.abilities(TF_HATCH)` 的多方块才吃「含时序仓」这套结构，
     * 因此也才可能拿到 TF。
     *
     * ⚠️ **必须独立**，不得复用 [OVERCLOCK_HATCH]：超频仓是「让配方吃 TF」的那一环（下一轮才做），
     * 与时序仓是两件正交的事 —— 复用会让「装了超频仓」被当成「这台机器支持 TF」，
     * 而两者可以只装其一。
     *
     * ⚠️ 能力方块表是**首取即定**的快照（`PartAbility#getAllBlocks` 内部记忆化），所以本仓必须在
     * 「用到它的多方块」之前注册 —— 实际顺序由 `MachineRegister` 保证
     * （`ALLSmachine.init()` 在 `ALLMmachine.init()` 之前），见 `ETTimeFlowHatches` 的类注释。
     *
     * 名称同样带 `gtet_` 前缀。
     */
    @JvmField
    val TF_HATCH: PartAbility = PartAbility("gtet_tf_hatch")
}