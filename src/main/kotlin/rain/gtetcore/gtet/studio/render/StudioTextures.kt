package rain.gtetcore.gtet.studio.render

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import org.slf4j.Logger
import rain.gtetcore.gtet.studio.data.StudioTextureRef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * **磁盘 PNG 的动态注册**（M1a 里唯一"必须动 GL"的新东西）。
 *
 * ## 为什么不能直接把磁盘路径当 ResourceLocation 用
 * `ResourceLocation` 是**贴图管理器里的一把钥匙**，不是文件句柄。原版 `SimpleTexture` 拿它去
 * `ResourceManager` 里查（`SimpleTexture$TextureImage#load` 的字节码就是一句 `rm.getResource(location)`，
 * **不做任何路径拼接**）—— 资源包之外的 PNG 根本不在 ResourceManager 里，查不到就是紫黑格，
 * 而且 `TextureManager` 会把这个失败**缓存**住。
 *
 * 正确做法（GTLAdditions 的机器贴图也是这么做的）：
 * ```
 * Files.newInputStream(png) → NativeImage.read(...) → DynamicTexture(image)
 *   → Minecraft.getInstance().getTextureManager().register(自己造的稳定 id, texture)
 * ```
 * 注册完之后，那个 id 就和资源包贴图完全一样能用（`RenderType` 只认 id）。
 *
 * ## 三个必须守住的点
 * 1. **必须在渲染线程**：`DynamicTexture` 的构造函数里就会 `TextureUtil.prepareImage` + 上传像素，
 *    离开 GL 上下文会直接炸。所有调用点都在渲染路径上（见 [StudioRenderCache]）。
 * 2. **id 必须稳定**：同一个文件每次都要算出同一个 id（[rain.gtetcore.gtet.studio.data.StudioDiskTextureIds]），
 *    这样重新注册时能把旧的**顶掉**，而 `TextureManager.register` 会顺手关掉旧纹理 —— 不泄漏。
 * 3. **别在每次重建 VBO 时重读 PNG**：改了 `VertexBuffer` 的灯光、或者同一帧重建多次，
 *    都不该再读一遍盘。所以这里记**文件的 (大小, 修改时间)** 当指纹，指纹没变就直接返回。
 *
 * ## 已知取舍（写清楚，不假装）
 * - `DynamicTexture` 不带 mipmap（`prepareImage(id, w, h)` 的 maxLevel 是 0），
 *   所以磁盘贴图在远处会比资源包贴图更容易闪。要做 mipmap 得自己建 `AbstractTexture` 子类
 *   逐级上传（MC 的 `MipmapGenerator` 生成 4 级），属于以后的事。
 * - 同一个 PNG 注册出来的纹理**不会**在模型被删掉时回收（`TextureManager` 里留着一份），
 *   代价是几 KB/张，换来的是"同一个文件被多个模型引用时不用重复读盘"。
 *
 * @author rain fox
 */
object StudioTextures {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** 文件指纹：大小 + 修改时间。变了才重读。 */
    private class Stamp(val size: Long, val modified: FileTime)

    private val registered = HashMap<ResourceLocation, Stamp>()

    /**
     * 保证 [ref] 指向的贴图**已经注册**，返回它的 id。
     *
     * @return 成功返回贴图 id；读不出来时返回 null（调用方退回缺省贴图，绝不让整台机器不画）
     */
    @JvmStatic
    fun ensure(ref: StudioTextureRef.Disk): ResourceLocation? {
        val path = ref.path
        val stamp = stampOf(path)
        if (stamp != null && registered[ref.location] == stamp) return ref.location

        if (!Files.isRegularFile(path)) {
            LOGGER.error("[studio] 磁盘贴图 {} 不存在（相对游戏目录找）", path)
            return null
        }
        return try {
            val image = Files.newInputStream(path).use { NativeImage.read(it) }
            val texture = DynamicTexture(image)
            // register 会顶掉同 id 的旧纹理并 close 它 —— 重载时不会泄漏
            Minecraft.getInstance().textureManager.register(ref.location, texture)
            registered[ref.location] = stamp ?: Stamp(0L, FileTime.fromMillis(0L))
            LOGGER.info("[studio] 已把磁盘贴图 {} 注册成 {}（{}×{}）", path, ref.location, image.width, image.height)
            ref.location
        } catch (e: Exception) {
            LOGGER.error("[studio] 读磁盘贴图 {} 失败：{}", path, e.toString())
            null
        }
    }

    /** 丢掉指纹记录（模型重载时调；纹理本体交给 `TextureManager` 顶替/自然存活）。 */
    @JvmStatic
    fun forget(location: ResourceLocation) {
        registered.remove(location)
    }

    private fun stampOf(path: Path): Stamp? = try {
        Stamp(Files.size(path), Files.getLastModifiedTime(path))
    } catch (_: Exception) {
        null
    }
}