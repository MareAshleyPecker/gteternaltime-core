package rain.gtetcore.gtet.studio.data

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraftforge.fml.loading.FMLPaths
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * **"读一份资产"的抽象** —— M1a 的核心：模型（以及它的 MTL、贴图）到底从哪来。
 *
 * ## 为什么要有这层
 * M0 里 `source.model` 只能是资源路径（`gtetcore:models/obj/clock.obj`），于是
 * "在 Blender 里导出一个新 OBJ 想看看"这件事的流程是：**丢进 `src/main/resources` → 重新编译 → 重启**。
 * 这不是工具，这是脚手架。M1a 让同一段解析代码既能读资源包、也能读磁盘：
 *
 * ```jsonc
 * "source": {
 *   "format": "obj",
 *   "model": "gtetcore:models/obj/clock.obj",        // ① 资源路径（M0 的写法，继续可用）
 *   "file":  "config/gtetstudio/models/clock.obj"    // ② 磁盘路径（★ M1a 主推）
 * }
 * ```
 *
 * ② 的相对路径**相对游戏目录**（开发环境就是 `run/`），也允许绝对路径；`file` 优先于 `model`。
 *
 * ## 两个实现
 * - [ResourceAsset]：走 [ResourceManager]（`assets/<ns>/<path>`）；
 * - [DiskAsset]：走 `java.nio.file`。
 *
 * [sibling] 是这一层的关键设计：OBJ 里的 `mtllib clock.mtl`、MTL 里的 `map_Kd clock.png`
 * 都是**相对本文件的路径**，资源来源与磁盘来源的解析规则不同 —— 由各自的实现说了算，
 * 解析器（`format/`）只管喊"给我个兄弟文件"。
 *
 * @author rain fox
 */
interface StudioAsset {

    /** 来源种类：`resource` / `disk`（日志与诊断用）。 */
    val kind: String

    /** 本文件名（含扩展名）—— 按扩展名认领解析器时用它。 */
    val name: String

    /** 完整人话路径（日志用）。 */
    val display: String

    fun exists(): Boolean

    /** 读全部字节；读不出来抛 [StudioLoadException]，消息必须写清"去哪找"。 */
    fun readBytes(): ByteArray

    /**
     * 相对**本文件**解析另一个文件（MTL / 贴图）。
     *
     * @return null = 这个来源解析不了（例如磁盘资产遇到 `gtetcore:block/xxx` 这种资源 id），
     *         由调用方决定要不要回退到资源包
     */
    fun sibling(ref: String): StudioAsset?

    companion object {

        private val LOGGER: Logger = LogUtils.getLogger()

        /** 资源包里的资产。 */
        @JvmStatic
        fun ofResource(resourceManager: ResourceManager, location: ResourceLocation): StudioAsset =
            ResourceAsset(resourceManager, location)

        /**
         * 磁盘上的资产。[raw] 可以是相对**游戏目录**的路径（`config/gtetstudio/models/clock.obj`），
         * 也可以是绝对路径。
         */
        @JvmStatic
        fun ofDisk(raw: String): StudioAsset = DiskAsset(resolveGamePath(raw))

        /** 相对游戏目录解析成绝对路径（绝对路径原样返回）。 */
        @JvmStatic
        fun resolveGamePath(raw: String): Path {
            val path = Path.of(raw)
            return if (path.isAbsolute) path.normalize() else FMLPaths.GAMEDIR.get().resolve(path).normalize()
        }

        /**
         * 把 OBJ/MTL 里写的一个引用解析成资产。
         *
         * 规则（与 M0 的行为保持一致，这样老文件不会因为换了加载器而改变含义）：
         * 1. 带 `:` 的按**资源路径**（`gtetcore:models/obj/clock.mtl`）；
         * 2. 不带的先问**本文件的兄弟**（磁盘资产在磁盘上找、资源资产在 `assets/<ns>/<同目录>/` 找）；
         * 3. 兄弟找不到时退回资源路径，用**本文件所在命名空间 + 同目录**拼一个
         *    （这是 M0 对资源 OBJ 的处理方式，保留下来做兜底）。
         *
         * @param base 提出这个引用的文件（OBJ 或 MTL）
         * @param rm   资源管理器（只有回退到资源包时才用到）
         */
        @JvmStatic
        fun resolveRef(base: StudioAsset, ref: String, rm: ResourceManager): StudioAsset? {
            if (ref.isBlank()) return null
            if (ref.contains(':')) {
                val rl = ResourceLocation.tryParse(ref)
                if (rl == null) {
                    LOGGER.error("[studio] 引用 \"{}\" 不是合法的资源路径（要形如 gtetcore:models/obj/clock.mtl）", ref)
                    return null
                }
                if (base is ResourceAsset) return ResourceAsset(rm, rl)
                // 磁盘资产 + 资源 id：贴图回退到资源包是**正常**用法（OBJ 在磁盘、贴图在资源包）
                return ResourceAsset(rm, rl)
            }
            base.sibling(ref)?.let { if (it.exists()) return it }
            if (base is ResourceAsset) {
                // 同目录拼资源路径（M0 的行为）
                val objPath = base.location.path
                val slash = objPath.lastIndexOf('/')
                val dir = if (slash < 0) "" else objPath.substring(0, slash + 1)
                val rl = ResourceLocation.tryBuild(base.location.namespace, "$dir$ref")
                if (rl != null) return ResourceAsset(rm, rl)
            }
            return null
        }
    }
}

/** 资源包来源：`assets/<ns>/<path>`。 */
class ResourceAsset(
    private val resourceManager: ResourceManager,
    val location: ResourceLocation,
) : StudioAsset {

    override val kind: String get() = "resource"

    override val name: String get() = location.path.substringAfterLast('/')

    override val display: String get() = "assets/${location.namespace}/${location.path}"

    override fun exists(): Boolean = resourceManager.getResource(location).isPresent

    override fun readBytes(): ByteArray {
        val resource = resourceManager.getResource(location).orElseThrow {
            StudioLoadException(
                "找不到资源 $location —— 它必须落在 assets/${location.namespace}/${location.path}" +
                    "（也可以改用 \"file\" 指向磁盘上的文件，见 studio 的数据格式说明）"
            )
        }
        return try {
            resource.open().use { it.readBytes() }
        } catch (e: Exception) {
            throw StudioLoadException("读取资源 $location 失败", e)
        }
    }

    /**
     * 资源包里的兄弟文件：按 OBJ 所在目录拼（`models/obj/clock.obj` + `clock.mtl`
     * → `gtetcore:models/obj/clock.mtl`）—— 与 M0 的解析规则逐字一致。
     */
    override fun sibling(ref: String): StudioAsset? {
        if (ref.contains(':')) {
            val rl = ResourceLocation.tryParse(ref) ?: return null
            return ResourceAsset(resourceManager, rl)
        }
        val ownPath = location.path
        val slash = ownPath.lastIndexOf('/')
        val dir = if (slash < 0) "" else ownPath.substring(0, slash + 1)
        val rl = ResourceLocation.tryBuild(location.namespace, "$dir$ref") ?: return null
        return ResourceAsset(resourceManager, rl)
    }

    override fun toString(): String = "resource:$location"
}

/** 磁盘来源（相对游戏目录或绝对路径）。 */
class DiskAsset(val path: Path) : StudioAsset {

    override val kind: String get() = "disk"

    override val name: String get() = path.fileName.toString()

    /** 能把游戏目录去掉就显示相对路径 —— 日志里比一长串绝对路径好读，也不泄露用户名。 */
    override val display: String
        get() {
            val gameDir = FMLPaths.GAMEDIR.get()
            return try {
                val rel = gameDir.relativize(path).toString()
                if (rel.startsWith("..")) path.toString() else rel
            } catch (_: Exception) {
                path.toString()
            }
        }

    override fun exists(): Boolean = Files.isRegularFile(path)

    override fun readBytes(): ByteArray {
        if (!Files.isRegularFile(path)) {
            throw StudioLoadException(
                "找不到文件 $display —— 路径相对**游戏目录**（开发环境是 run/），也可以写绝对路径"
            )
        }
        return try {
            Files.readAllBytes(path)
        } catch (e: Exception) {
            throw StudioLoadException("读取文件 $display 失败", e)
        }
    }

    /**
     * 磁盘上的兄弟文件：按本文件所在目录拼。
     *
     * 带 `:` 的引用**不认**（那是资源 id）—— 交给 [StudioAsset.resolveRef] 回退到资源包，
     * 这正是"OBJ 在磁盘、贴图还在资源包里"这种混合用法的通路。
     */
    override fun sibling(ref: String): StudioAsset? {
        if (ref.contains(':')) return null
        val parent = path.parent ?: return null
        return DiskAsset(parent.resolve(ref).normalize())
    }

    override fun toString(): String = "disk:$display"
}

/**
 * 磁盘 PNG 贴图在**贴图管理器里的资源名**。
 *
 * 磁盘图片不是资源包条目，不能直接当 `ResourceLocation` 用，但 `RenderType` 需要一个 id
 * 才能在渲染时把纹理绑上去 —— 所以给它**造一个稳定的 id**，再由渲染层把图片动态注册进去
 * （见 `render/StudioTextures`）。
 *
 * 稳定性要求：同一个文件每次都要算出同一个 id（重新注册时靠它顶掉旧的、顺手关掉旧纹理）；
 * 不同文件不能撞车（所以把相对游戏目录的路径整个编进去，编不出来时才退回"文件名 + 路径哈希"）。
 */
object StudioDiskTextureIds {

    /** 磁盘贴图的命名空间（与资源包命名空间隔离，不会和 assets 撞名）。 */
    const val NAMESPACE = "gtetstudio"

    /** 兜底 id（正常路径永远不会用到它；用它是因为 `ResourceLocation` 的构造器已废弃）。 */
    private val FALLBACK: ResourceLocation = ResourceLocation.tryBuild(NAMESPACE, "disk/texture")!!

    @JvmStatic
    fun of(path: Path): ResourceLocation {
        val gameDir = FMLPaths.GAMEDIR.get()
        val key = try {
            val rel = gameDir.relativize(path).toString().replace('\\', '/')
            if (rel.startsWith("..")) fallbackKey(path) else rel
        } catch (_: Exception) {
            fallbackKey(path)
        }
        val sanitized = sanitize(key)
        return ResourceLocation.tryBuild(NAMESPACE, "disk/$sanitized") ?: FALLBACK
    }

    private fun fallbackKey(path: Path): String =
        path.fileName.toString().replace('\\', '/') + "_" + Integer.toHexString(path.toString().hashCode())

    /** 只留资源路径允许的字符（小写字母、数字、`_ - . /`），其余替换成 `_`。 */
    private fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            sb.append(
                when (c) {
                    in 'a'..'z', in '0'..'9', '_', '-', '.', '/' -> c
                    else -> '_'
                }
            )
        }
        return sb.toString().trim('/').ifEmpty { "texture" }
    }
}