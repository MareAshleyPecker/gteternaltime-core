package rain.gtetcore.gtet.studio.api

import com.google.common.collect.ImmutableMap
import net.minecraft.core.Direction
import org.joml.Matrix4f
import rain.gtetcore.gtet.studio.api.StudioClip.Companion.DRIVER_GAME_TIME

/**
 * 动画片段 —— 一组 [StudioTrack]，给定时间就能算出「每个部件各自的矩阵」。
 *
 * 输出直接喂给 `net.minecraftforge.client.model.renderable.CompositeRenderable.Transforms.of(...)`，
 * 由 Forge 在渲染时按 **OBJ 的组名**把矩阵叠到对应部件上（这正是「分部件转指针」的官方解法）。
 *
 * @author rain fox
 */
class StudioClip(
    /** 时间来源。M0 只实现 [DRIVER_GAME_TIME]；其余取值是**预留枚举**，加了实现才生效。 */
    val driver: String,
    val tracks: List<StudioTrack>,
) {

    /**
     * 算出时刻 [timeSeconds] 的部件矩阵表。
     *
     * 同一个部件被多条轨道命中时**后者覆盖前者**（不抛异常：手写 JSON 时叠轨道很容易踩到，
     * 让文件还能加载、并在日志里提示，比整个模型消失好）。
     */
    fun evaluate(timeSeconds: Float): ImmutableMap<String, Matrix4f> {
        if (tracks.isEmpty()) return ImmutableMap.of()

        val byPart = LinkedHashMap<String, Matrix4f>(tracks.size)
        for (track in tracks) {
            val radians = Math.toRadians((track.speedDegPerSecond * timeSeconds).toDouble()).toFloat()
            val m = Matrix4f()
            // ⚠️ 1.20.1 的 `Direction.Axis` **没有** x()/y()/z()（那是 1.21 才加的），只能自己分支
            when (track.axis) {
                Direction.Axis.X -> m.rotateX(radians)
                Direction.Axis.Y -> m.rotateY(radians)
                Direction.Axis.Z -> m.rotateZ(radians)
            }
            byPart[track.part] = m
        }
        return ImmutableMap.copyOf(byPart)
    }

    companion object {
        const val DRIVER_GAME_TIME = "gameTime"

        @JvmField
        val EMPTY = StudioClip(DRIVER_GAME_TIME, emptyList())
    }
}