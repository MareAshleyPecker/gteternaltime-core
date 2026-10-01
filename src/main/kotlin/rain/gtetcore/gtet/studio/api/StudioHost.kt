package rain.gtetcore.gtet.studio.api

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * 宿主接口 —— 工作室向「挂它的那个东西」问信息。
 *
 * ## 为什么要有这层接口
 * `studio` 包的硬纪律是：`api / data / render` **一行都不许 import `com.gregtechceu.*`**
 * （整包以后要能抽出去当独立库）。所以「现在几点、这台机器在不在干活」这类问题不能直接问 GTM 的机器，
 * 一律走这个接口；实现放在 [rain.gtetcore.gtet.studio.integration]（全包唯一认识 GTM 的地方）。
 *
 * M0 只需要四个问题：**在哪、朝哪、活着吗、几点了**。
 *
 * @author rain fox
 */
interface StudioHost {

    /** 宿主方块坐标（渲染姿态的原点就是这一格的最小角）。 */
    val pos: BlockPos

    /** 宿主正面朝向 —— 模型默认贴在这一面上。 */
    val facing: Direction

    /** 宿主是否「在工作」：false 时渲染器直接早退，一帧都不画（设计文档 §7 第 6 条）。 */
    val isActive: Boolean

    /** 世界时间（tick）。 */
    val gameTime: Long

    /** 帧内插值（0~1），让指针在两 tick 之间也平滑。 */
    val partialTick: Float

    /**
     * 时间轴读数，单位**秒**。
     *
     * OBJ 里的 `speed` 是「度 / 秒」，所以这里必须是秒；用 tick 直接乘会慢 20 倍。
     */
    fun timeSeconds(): Float = (gameTime + partialTick) / 20.0f
}