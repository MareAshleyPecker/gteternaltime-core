package rain.gtetcore.gtet.api.capability

import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility

/**
 * GTET 自己的多方块部件能力（[PartAbility]）表。
 *
 * ## 为什么能直接 new
 * 查过 GTM 7.5.3 源码：`PartAbility` 的构造函数就是普通的
 * `public PartAbility(String name)`，内部只持有一个 `name` 与一个「tier → 方块集合」的注册表，
 * 没有全局注册表、也没有单例限制。所以 GTET 侧直接 `PartAbility("gtet_overclock_hatch")`
 * 即可新增能力，**不需要**用 mixin 往 `PartAbility` 里塞静态字段，也不需要借用 GTM 的注册方式。
 *
 * 真正把这批方块登记进能力表的是 `MachineBuilder.abilities(...)`：
 * 它在方块注册回调里执行 `ability.register(tier, block)`。
 *
 * ## 思路来源
 * - 【照抄】GTCEu `com.gregtechceu.gtceu.api.machine.multiblock.PartAbility` 的用法 —— 构造函数就是 `public PartAbility(String)`（内部只持有 `name` 与「tier → 方块集合」的注册表，没有全局注册表、也没有单例限制），能力表由 `MachineBuilder.abilities(...)` 在方块注册回调里 `ability.register(tier, block)` 填。本对象完全沿用这套用法，只换了一个 `gtet_` 前缀的名字。
 * - 【借鉴形状】GTCEu `GCYMMachines#PARALLEL_HATCH` —— 借「一个专用能力 + 一个与之配对的分级 part machine」的书写形状；这里只保留能力声明那半边，配对的分级部件换成 GTET 自己的 `OverclockHatchPartMachine`。
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
}
