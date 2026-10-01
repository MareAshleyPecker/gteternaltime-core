package rain.gtetcore.gtet.studio.kernel

import kotlin.math.sqrt

/**
 * 轴对齐包围盒（模型局部空间，单位 = 格）。
 *
 * 内核只用 Float（**不 import Minecraft 的 AABB**）；需要 MC 类型时由 `api/` 层自己转换
 * —— 这是「kernel 一行都不许依赖 Minecraft」这条纪律的直接后果。
 *
 * @author rain fox
 */
class StudioBounds(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {

    val sizeX: Float get() = maxX - minX
    val sizeY: Float get() = maxY - minY
    val sizeZ: Float get() = maxZ - minZ

    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f

    /** 八个角里**离原点最远**的那个的距离（格）。渲染器放大 AABB 就是按它（设计文档 §7 第 5 条）。 */
    val maxRadius: Double
        get() {
            var r = 0.0
            for (x in doubleArrayOf(minX.toDouble(), maxX.toDouble())) {
                for (y in doubleArrayOf(minY.toDouble(), maxY.toDouble())) {
                    for (z in doubleArrayOf(minZ.toDouble(), maxZ.toDouble())) {
                        val d = sqrt(x * x + y * y + z * z)
                        if (d > r) r = d
                    }
                }
            }
            return r
        }

    fun union(other: StudioBounds): StudioBounds = StudioBounds(
        minOf(minX, other.minX), minOf(minY, other.minY), minOf(minZ, other.minZ),
        maxOf(maxX, other.maxX), maxOf(maxY, other.maxY), maxOf(maxZ, other.maxZ),
    )

    override fun toString(): String =
        "[($minX, $minY, $minZ) ~ ($maxX, $maxY, $maxZ)] " +
            "尺寸($sizeX, $sizeY, $sizeZ)"

    companion object {

        /**
         * 只按**被索引引用到的**顶点算包围盒。
         *
         * 为什么不是"所有顶点"：OBJ 里经常留着没被任何面用到的孤立顶点（Blender 删面不删点），
         * 那些点不该把 AABB 撑大——AABB 撑大意味着视锥剔除失效、机器一出视锥就整台连模型一起消失。
         */
        @JvmStatic
        fun ofReferenced(vertices: List<StudioVertex>, indices: IntArray): StudioBounds {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (index in indices) {
                val v = vertices[index]
                if (v.x < minX) minX = v.x
                if (v.y < minY) minY = v.y
                if (v.z < minZ) minZ = v.z
                if (v.x > maxX) maxX = v.x
                if (v.y > maxY) maxY = v.y
                if (v.z > maxZ) maxZ = v.z
            }
            return StudioBounds(minX, minY, minZ, maxX, maxY, maxZ)
        }
    }
}