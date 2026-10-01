package rain.gtetcore.gtet.studio.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import com.mojang.logging.LogUtils
import net.minecraft.client.renderer.RenderType
import org.slf4j.Logger
import rain.gtetcore.gtet.studio.config.StudioLimits
import rain.gtetcore.gtet.studio.data.StudioInstance
import rain.gtetcore.gtet.studio.data.StudioLibrary
import rain.gtetcore.gtet.studio.data.StudioTextureRef
import rain.gtetcore.gtet.studio.kernel.StudioMesh
import rain.gtetcore.gtet.studio.render.StudioRenderCache.closeAll
import rain.gtetcore.gtet.studio.render.StudioRenderCache.obtain
import java.util.*

/**
 * **网格 → 显存**：把工作室自己的 [StudioMesh] 烘成 `VertexBuffer(Usage.STATIC)`，并管它们的生死。
 *
 * ## 分桶规则（设计文档 §7 第 1 条：静态合并决定性能）
 * ```
 * 桶 = (部件名 or null, RenderType)
 *   ├─ 部件**没有**动画轨道 → 部件名 = null  ⇒ 所有这类部件**合并进同一张 VBO**
 *   └─ 部件**有**动画轨道   → 各占一张 VBO（每帧要用各自的矩阵单独 bind/draw）
 * ```
 * 为什么桶里还带 `RenderType`：一张 VBO 一次 draw 只能配一个渲染类型，
 * 而材质不同（cutout / eyes / translucent）+ 贴图不同就是不同的 `RenderType`。
 * 所以"静态部分一张 VBO"的准确说法是"**每种渲染类型一张**"—— 时钟的静态部分只有 body 一个材质，
 * 实际就是一张；材质一多就必须分开，这是 GL 的硬约束，不是偷懒。
 *
 * ## 线程
 * 建 VBO 是 GL 操作，**只能在渲染线程**（`RenderSystem.recordRenderCall` 或等价的线程约束，
 * 见 GTM `MultiblockInWorldPreviewRenderer.java:395-404`）。这里的做法是：
 * [obtain] 只允许在渲染线程调用，其它线程直接报错返回 —— 因为唯一的调用点是渲染路径，
 * 与其把上传排队（排了队这帧就没得画），不如把约束写死、让它一坏就现形。
 *
 * ## 重载（`/gtetstudio reload` 会被反复敲）
 * - 每次重载 `StudioLibrary` 会产出**新的** [StudioInstance] 对象 ⇒ 这里按实例身份缓存，
 *   旧实例的 VBO 在下一次 [obtain] 时被 [PartBuffer.close] 释放（并在 reload 命令里立刻 [closeAll]）；
 * - 灯光变了（宿主那一格的光照被挡/被点亮）也要重烘：顶点里的 `uv2` 是烘进去的，
 *   不重烘的话模型亮度会永远冻在第一次建 VBO 的那一刻。
 *
 * @author rain fox
 */
object StudioRenderCache {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** 每个顶点预留的字节数（`NEW_ENTITY` 实际约 36 字节，这里留点余量；不够时 BufferBuilder 会自己扩容）。 */
    private const val BYTES_PER_VERTEX = 40

    /**
     * 一张 VBO + 它的身份。
     *
     * @param part       非空 = 这是某个**带动画**部件的专属 VBO；null = 静态合并的那张
     * @param material   这张 VBO 用的材质名（没有材质的部件为 null）
     * @param renderType 渲染类型（决定 shader / 贴图 / 混合）
     */
    class PartBuffer(
        val part: String?,
        val material: String?,
        val renderType: RenderType,
        val buffer: VertexBuffer,
        val triangleCount: Int,
    ) {
        fun close() = buffer.close()

        override fun toString(): String =
            "${part ?: "(静态合并)"}/${material ?: "-"}/$triangleCount 三角形"
    }

    /** 一个模型烘完之后的全部 GL 资源。 */
    class Built(
        /** 烘这份 VBO 时用的光照（宿主那一格）。变了就重烘。 */
        val light: Int,
        /** 同理：overlay 也是烘进顶点的（BER 里正常恒为 `NO_OVERLAY`，但别写死假设）。 */
        val overlay: Int,
        val parts: List<PartBuffer>,
    ) {
        val triangleCount: Int get() = parts.sumOf { it.triangleCount }

        fun close() {
            for (part in parts) part.close()
        }

        override fun toString(): String = "Built(光=$light, overlay=$overlay, ${parts.joinToString()})"
    }

    private val cache = IdentityHashMap<StudioInstance, Built>()

    /** 烘失败过的模型：**只报一次错、不每帧重试**（否则一个坏模型会把日志刷爆）。 */
    private val failed: MutableSet<StudioInstance> =
        Collections.newSetFromMap(IdentityHashMap<StudioInstance, Boolean>())

    /**
     * 取（必要时现烘）一份模型的 GL 资源。
     *
     * @return null = 不该画（总开关关了 / 不在渲染线程 / 烘不出来）
     */
    @JvmStatic
    fun obtain(instance: StudioInstance, packedLight: Int, packedOverlay: Int): Built? {
        if (!StudioLimits.enabled) return null
        if (instance in failed) return null
        if (!RenderSystem.isOnRenderThread()) {
            LOGGER.error("[studio] StudioRenderCache.obtain 被非渲染线程调用了（那是 GL 操作，必须上渲染线程）")
            return null
        }

        cache[instance]?.let { if (it.light == packedLight && it.overlay == packedOverlay) return it }

        // 旧的要先放掉：不 close 就是泄漏显存（重载会被反复敲，泄漏会越滚越大）
        cache.remove(instance)?.let {
            LOGGER.info("[studio] 释放 {} 的旧顶点缓冲（{} 张 VBO，{} 三角形）", instance.model.id, it.parts.size, it.triangleCount)
            it.close()
        }

        val fresh = try {
            build(instance, packedLight, packedOverlay)
        } catch (e: Exception) {
            // GL 出错不该把游戏带崩：报一次、记下来、这台模型这一代就不画了
            failed += instance
            LOGGER.error("[studio] 烘 {} 的顶点缓冲失败，本模型这一代不再尝试", instance.model.id, e)
            return null
        } ?: return null

        cache[instance] = fresh
        prune()
        return fresh
    }

    /** 立刻释放**全部**缓存的 VBO（`/gtetstudio reload` 后调，把显存马上还回去）。 */
    @JvmStatic
    fun closeAll() {
        if (cache.isEmpty() && failed.isEmpty()) return
        LOGGER.info("[studio] 释放全部顶点缓冲（{} 个模型）", cache.size)
        cache.values.forEach { it.close() }
        cache.clear()
        failed.clear()
    }

    /** 当前缓存规模（诊断用）。 */
    @JvmStatic
    fun cachedModels(): Int = cache.size

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 丢掉**已经不在模型库里**的实例缓存（重载会产出新实例，老的在这里退休）。
     *
     * 为什么放在这里而不是让 `data/` 层回调：`data/` 是纯 CPU 层，不该知道显存的存在；
     * 而渲染路径每帧都会经过 [obtain]，顺手清一遍的代价可以忽略（模型数量是个位数）。
     */
    private fun prune() {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!StudioLibrary.isCurrent(entry.key)) {
                entry.value.close()
                iterator.remove()
            }
        }
    }

    private fun build(instance: StudioInstance, packedLight: Int, packedOverlay: Int): Built? {
        val mesh = instance.model.mesh
        val animatedParts = HashSet<String>(instance.clip.tracks.size)
        for (track in instance.clip.tracks) animatedParts += track.part

        // ── 1. 材质 → RenderType。磁盘贴图在这里注册（必须在任何绘制之前，也必须在建 RenderType 之前）──
        val fallback = StudioRenderTypes.of(
            StudioRenderTypes.CUTOUT,
            StudioRenderTypes.MISSING_TEXTURE,
        )
        val renderTypes = HashMap<String, RenderType>(instance.materials.size * 2)
        for (material in instance.materials) {
            val textureId = when (val ref = material.texture) {
                is StudioTextureRef.Resource -> ref.location
                is StudioTextureRef.Disk -> StudioTextures.ensure(ref) ?: StudioRenderTypes.MISSING_TEXTURE
            }
            renderTypes[material.name] = StudioRenderTypes.of(material.renderKind, textureId)
        }

        // ── 2. 分桶 ──
        val buckets = LinkedHashMap<BucketKey, MutableList<StudioMesh.Group>>()
        for (group in mesh.groups) {
            val renderType = group.material?.let { renderTypes[it] } ?: fallback
            val part = if (group.name in animatedParts) group.name else null
            buckets.getOrPut(BucketKey(part, renderType)) { ArrayList() } += group
        }

        // ── 3. 每个桶一张 VBO ──
        val parts = ArrayList<PartBuffer>(buckets.size)
        for ((key, groups) in buckets) {
            val triangles = groups.sumOf { it.triangleCount }
            val vertices = triangles * 3
            val builder = BufferBuilder(vertices * BYTES_PER_VERTEX + 1024)
            builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY)
            for (group in groups) {
                emitGroup(builder, mesh, group, packedLight, packedOverlay)
            }
            val buffer = VertexBuffer(VertexBuffer.Usage.STATIC)
            buffer.bind()
            buffer.upload(builder.end())
            VertexBuffer.unbind()
            parts += PartBuffer(key.part, groups.first().material, key.type, buffer, triangles)
        }

        if (parts.isEmpty()) {
            LOGGER.error("[studio] {} 一个可画的部件都没有", instance.model.id)
            return null
        }
        LOGGER.info(
            "[studio] 已烘 {}：{} 张 VBO / {} 三角形（静态合并 {} 张，动画部件 {} 个）",
            instance.model.id, parts.size, parts.sumOf { it.triangleCount },
            parts.count { it.part == null }, parts.count { it.part != null },
        )
        return Built(packedLight, packedOverlay, parts)
    }

    /**
     * 把一个组的三角形按索引顺序吐进缓冲区。
     *
     * ⚠️ 顶点调用顺序不能改：`顶点 → 颜色 → UV → overlay → 光照 → 法线`
     * （设计文档 §10.1 记的就是这条；`NEW_ENTITY` 的格式就是这个顺序）。
     * 光照这里用**宿主那一格**的 `packedLight`（M0 的取舍，整块模型统一光照）——
     * 逐顶点采样要 `LevelRenderer.getLightColor(level, pos)`，那需要宿主提供 level，
     * 是 §7 第 4 条留给后面的事。
     */
    private fun emitGroup(
        consumer: VertexConsumer,
        mesh: StudioMesh,
        group: StudioMesh.Group,
        light: Int,
        overlay: Int,
    ) {
        val indices = mesh.indices
        val vertices = mesh.vertices
        var i = group.firstIndex
        val end = group.firstIndex + group.indexCount
        while (i < end) {
            val v = vertices[indices[i]]
            // ⚠️ 位置是 (double, double, double)：1.20.1 的 `VertexConsumer` 只声明了 double 版本
            consumer.vertex(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
                .color(255, 255, 255, 255)
                .uv(v.u, v.v)
                .overlayCoords(overlay)
                .uv2(light)
                .normal(v.nx, v.ny, v.nz)
                .endVertex()
            i++
        }
    }

    /**
     * 分桶键：部件名按值比（同一个部件名的多段要合到一起），
     * [RenderType] 按**身份**比（它没重写 equals，而"同材质"本来就意味着同一个实例）。
     * 少了 equals/hashCode 的话每个组都会各开一张 VBO —— 静态合并就废了。
     */
    private class BucketKey(val part: String?, val type: RenderType) {
        override fun equals(other: Any?): Boolean =
            other is BucketKey && other.part == part && other.type === type

        override fun hashCode(): Int = (part?.hashCode() ?: 0) * 31 + System.identityHashCode(type)
    }
}