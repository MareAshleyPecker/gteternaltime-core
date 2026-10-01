package rain.gtetcore.gtet.studio.integration

import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.IMachineFeature
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import rain.gtetcore.gtet.studio.api.StudioHost

/**
 * [StudioHost] 的 GTM 实现 —— **整个 studio 包里唯一读 GTM 机器状态的地方**。
 *
 * `api / data / render` 只认 [StudioHost] 这个接口，所以"宿主是谁"在这里被隔离干净：
 * 以后挂到别的机器（比如 ETV 那台）上，这个类一行都不用改。
 *
 * @author rain fox
 */
class StudioMachineHost(
    private val machine: MetaMachine,
    private val level: Level,
    override val partialTick: Float,
) : StudioHost {

    override val pos: BlockPos get() = machine.pos

    override val facing: Direction get() = machine.frontFacing

    /**
     * 宿主是否"活着"。
     *
     * 现在的口径是**机器已成型**（多方块控制器才谈得上成型；单方块机器恒为 true）。
     * 设计文档 §7 第 6 条还提到"没在工作时也别画"—— 要不要再收紧成"只在跑配方时显示"，
     * 是宿主自己的口味（时钟这种东西一直挂着更好看），所以**只改这一个方法**即可。
     */
    override val isActive: Boolean get() = isFormed(machine)

    override val gameTime: Long get() = level.gameTime

    companion object {

        /** 复用时避免每个渲染帧都新建一个 host 之外的判断逻辑（`shouldRender` 也要问这个）。 */
        @JvmStatic
        fun isFormed(machine: IMachineFeature): Boolean = isFormed(machine.self())

        @JvmStatic
        fun isFormed(machine: MetaMachine): Boolean =
            (machine as? IMultiController)?.isFormed ?: true
    }
}