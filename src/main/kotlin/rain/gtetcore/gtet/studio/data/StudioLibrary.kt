package rain.gtetcore.gtet.studio.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.loading.FMLPaths
import org.slf4j.Logger
import rain.gtetcore.gtet.studio.config.StudioConfig
import rain.gtetcore.gtet.studio.config.StudioLimits
import rain.gtetcore.gtet.studio.config.stripJsonComments
import rain.gtetcore.gtet.studio.data.StudioLibrary.configLoaded
import rain.gtetcore.gtet.studio.data.StudioLibrary.get
import rain.gtetcore.gtet.studio.data.StudioLibrary.loadConfigIfNeeded
import rain.gtetcore.gtet.studio.data.StudioLibrary.markDirty
import rain.gtetcore.gtet.studio.data.StudioLibrary.reloadAll
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 工作室的**运行期模型库** —— 把 `config/gtetstudio/` 下的 `*.json` 读成 [StudioInstance] 并缓存。
 * <p>
 * ## 热重载怎么走（M1a 之后简单多了）
 * M0 之所以要"标脏 + 下一次渲染时懒重读"，是因为 `ObjLoader` 自己也是资源重载监听器、
 * 会清它自己的 materialCache，监听器之间的先后顺序没法保证 —— 早读会拿到旧 MTL。
 * **M1a 换掉 Forge 的 OBJ/MTL 加载器之后，这个顺序问题整个消失了**（我们自己读文件，
 * 没有任何缓存要等谁先清），所以：
 * - **F3+T / 资源重载** → 监听器里 [markDirty]（只是让下一帧重读，避免在重载过程中做 IO）；
 * - **`/gtetstudio reload`** → [reloadAll] 同步重读（**JSON / OBJ / MTL 全都会重读**，
 *   连上限配置 `studio.json` 也在里面 —— §7.1 要求"改完就生效、不用重启"，
 *   M0 那条"改 MTL 得按 F3+T"的限制到此结束）。
 * <p>
 * ## 文件从哪来
 * - 定义：`config/gtetstudio/` 下的 json（首次运行会把 mod 内置的示例释放进去，之后以 config 里那份为准）；
 * - 模型：**磁盘**（`source.file`，相对游戏目录）或**资源包**（`source.model`）都行。
 *   首次运行还会把内置的示例模型释放到 `config/gtetstudio/models/`，
 *   这样"改成磁盘来源"只要把 JSON 里的一行从 `model` 换成 `file`，不用自己找文件。
 * - **上限配置**：`config/gtetstudio/studio.json`（[StudioConfig]，设计文档 §7.1）也由这里读写 ——
 *   它跟模型定义同目录、同一套"缺了才释放"的机制，但**绝不是模型**（没有 `id`，会被索引跳过）。
 *
 * @author rain fox
 */
object StudioLibrary {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** 配置目录名（相对 `.minecraft/config/`）。 */
    const val CONFIG_DIR = "gtetstudio"

    /** mod 内自带的**示例定义**（打包在 jar 根下）：`jar 内资源 → config/gtetstudio/ 下的文件名`。 */
    private val DEFAULT_DEFINITIONS = listOf(
        "/gtetstudio/clock.json" to "clock.json",
        // ★ M3a：CAD 示例（features 参数化历史）。与 clock.json 并排挂在测试机上，
        //   删掉这个文件 = 那台机器上少画一块表盘，别处没有任何影响。
        "/gtetstudio/dial.json" to "dial.json",
    )

    /** 内置的**上限配置**（同样是 jar 根下；首次运行释放到 config 目录）。 */
    private const val DEFAULT_CONFIG_RESOURCE = "/gtetstudio/studio.json"

    /** 配置文件名。它躺在模型目录里，但**不是模型定义**。 */
    private const val DEFAULT_CONFIG_FILE = StudioConfig.FILE_NAME

    /**
     * 内置示例模型：`jar 内资源路径 → config/gtetstudio/ 下的相对路径`。
     *
     * 释放它们是为了让**磁盘来源**开箱即可试：JSON 里把 `model` 换成 `file` 就能用，
     * 不必先自己去仓库里翻出那个 OBJ。缺了才写，绝不覆盖用户改过的文件。
     */
    private val DEFAULT_MODEL_FILES = listOf(
        "/assets/gtetcore/models/obj/clock.obj" to "models/clock.obj",
        "/assets/gtetcore/models/obj/clock.mtl" to "models/clock.mtl",
    )

    /** 每 [markDirty] 一次加一，用来判断"缓存是不是上一代的了"。 */
    private var generation = 0
    private var indexedGeneration = -1

    /** 上限配置**这次进程里读过了没有**（第一次用到模型时才读，见 [loadConfigIfNeeded]）。 */
    @Volatile
    private var configLoaded = false

    /** `id → json 文件`。 */
    private val index = HashMap<ResourceLocation, Path>()

    private val loaded = HashMap<ResourceLocation, StudioInstance>()

    /** `id → 上次失败的原因`；用来**每个 id 只报一次错**，不在渲染循环里刷屏。 */
    private val failed = HashMap<ResourceLocation, String>()

    /** 资源重载（F3+T）用：只标脏。 */
    @JvmStatic
    @Synchronized
    fun markDirty() {
        generation++
    }

    /**
     * 取一份模型。**每个渲染帧都会调**，所以：命中缓存直接返回；这个 id 上次失败过就直接返回 null，
     * 不重复读盘、也不重复刷日志。
     */
    @JvmStatic
    @Synchronized
    fun get(id: ResourceLocation): StudioInstance? {
        ensureIndexed()
        loaded[id]?.let { return it }
        if (failed.containsKey(id)) return null

        val path = index[id]
        if (path == null) {
            val reason = "config/$CONFIG_DIR/ 下没有 id=$id 的 JSON"
            failed[id] = reason
            LOGGER.error("[studio] 找不到模型 {} —— {}（当前已索引到的 id：{}）", id, reason, index.keys)
            return null
        }

        return try {
            loadFrom(path).also { loaded[id] = it }
        } catch (e: StudioLoadException) {
            failed[id] = e.message ?: "载入失败"
            LOGGER.error("[studio] 载入 {} 失败：{}", path, e.message)
            null
        } catch (e: Exception) {
            failed[id] = e.toString()
            LOGGER.error("[studio] 载入 {} 时发生意外错误（这不是数据格式问题，带堆栈）", path, e)
            null
        }
    }

    /**
     * 这个实例是不是当前库里那一份（渲染层用它判断"缓存的 VBO 是不是已经过期"）。
     *
     * 重载会产出**全新的** [StudioInstance] 对象，所以身份比较就足以判断世代 —— 不需要额外发号。
     */
    @JvmStatic
    @Synchronized
    fun isCurrent(instance: StudioInstance): Boolean = loaded.containsValue(instance)

    /**
     * 立刻重读全部 JSON（以及它们引用的 OBJ/MTL/贴图）—— 给 `/gtetstudio reload` 用。
     *
     * **上限配置 `studio.json` 也一起重读**（§7.1：改完就生效，不用重启）。
     *
     * @return 逐条人话状态（成功/失败原因），命令直接把它们打到聊天栏
     */
    @JvmStatic
    @Synchronized
    fun reloadAll(): List<String> {
        generation++

        // 先读配置：它决定这一轮"要不要画 / 单模型能有多大"，模型再过一遍载入期校验才是新上限下的结果
        val report = ArrayList<String>(index.size + 2)
        report += reloadConfig()

        ensureIndexed()

        if (index.isEmpty()) {
            report += "config/$CONFIG_DIR/ 里一个 JSON 都没有（目录：${configDir()}）"
            return report
        }
        for ((id, path) in index.toSortedMap(compareBy { it.toString() })) {
            try {
                val instance = loadFrom(path)
                loaded[id] = instance
                failed.remove(id)
                report += "OK   $id  (${path.fileName})  来源 ${instance.source}，" +
                    "三角形 ${instance.model.mesh.triangleCount}，组 ${instance.model.partNames.size} 个，" +
                    "材质 ${instance.materials.size} 个"
            } catch (e: StudioLoadException) {
                failed[id] = e.message ?: "载入失败"
                report += "FAIL $id  ${e.message}"
                LOGGER.error("[studio] 重载 {} 失败：{}", path, e.message)
            } catch (e: Exception) {
                failed[id] = e.toString()
                report += "FAIL $id  $e"
                LOGGER.error("[studio] 重载 {} 时发生意外错误", path, e)
            }
        }
        return report
    }

    /** 当前索引到的全部 id（诊断用）。 */
    @JvmStatic
    @Synchronized
    fun knownIds(): Set<ResourceLocation> {
        ensureIndexed()
        return index.keys.toSet()
    }

    /**
     * 这个 id 的**定义文件路径**（`/gtetstudio save` 要往它写回）。
     *
     * 注意写回的一定是**这份 config 里的 json**：就算 `source.file` 指的是磁盘 OBJ、
     * 或者定义最初是从 mod 内置资源释放出来的，`anchor` 也永远只存在于这个文件里
     * （资源包里的那份模板**一个字节都不会动**）。
     *
     * @return null = 没索引到这个 id
     */
    @JvmStatic
    @Synchronized
    fun pathOf(id: ResourceLocation): Path? {
        ensureIndexed()
        return index[id]
    }

    /**
     * **只重读一份** —— `/gtetstudio save` 写回之后立刻让它生效（不惊动别的模型）。
     *
     * 与 [reloadAll] 的区别只有范围：这份的新 [StudioInstance] 会顶掉旧的，
     * 旧的那些顶点缓冲由 `render/StudioRenderCache` 在下一帧按"实例身份"自动退休。
     *
     * @return 一行人话状态（成功/失败原因）
     */
    @JvmStatic
    @Synchronized
    fun reloadOne(id: ResourceLocation): String {
        ensureIndexed()
        val path = index[id]
            ?: return "FAIL 找不到 $id 的定义（config/$CONFIG_DIR/ 下没有它的 json）"
        return try {
            val instance = loadFrom(path)
            loaded[id] = instance
            failed.remove(id)
            "OK   已重载 $id（三角形 ${instance.model.mesh.triangleCount}，来源 ${instance.source}）"
        } catch (e: StudioLoadException) {
            failed[id] = e.message ?: "载入失败"
            LOGGER.error("[studio] 重载 {} 失败：{}", path, e.message)
            "FAIL $id  ${e.message}"
        } catch (e: Exception) {
            failed[id] = e.toString()
            LOGGER.error("[studio] 重载 {} 时发生意外错误", path, e)
            "FAIL $id  $e"
        }
    }

    /**
     * 确保上限配置**至少读过一次**（没读过就读，读过就什么都不做）。
     *
     * 给 `/gtetstudio limits` 用：那条命令可能在第一帧渲染之前就被敲，
     * 那时渲染路径还没触发过懒加载，不读的话会打印"内置默认值"而不是文件里的值。
     */
    @JvmStatic
    @Synchronized
    fun ensureConfigLoaded() {
        loadConfigIfNeeded()
    }

    // ────────────────────────── 上限配置（studio.json，§7.1）──────────────────────────

    /**
     * 重读 `config/gtetstudio/studio.json` 并把它灌成当前生效的上限（[StudioLimits]）。
     *
     * **任何情况下都不抛异常**：文件读不出来 / 不是合法 JSON / 字段类型不对 ⇒
     * 逐条打日志（错误用 error、回退用 warn、认不出的键用 warn）并按默认值走，
     * 决不让一份写坏的配置把客户端带走。
     *
     * @return 一行人话状态（给 `/gtetstudio reload` 的聊天栏）
     */
    @JvmStatic
    @Synchronized
    fun reloadConfig(): String {
        configLoaded = true

        val dir = configDir()
        val path = dir.resolve(DEFAULT_CONFIG_FILE)
        val where = "config/$CONFIG_DIR/$DEFAULT_CONFIG_FILE"

        // 文件不在就先释放（与 clock.json 同一套机制）；目录也要在，不然读的路径都不对
        ensureDirectoryAndDefault(dir)

        val parsed = try {
            StudioConfig.parseText(Files.readString(path, StandardCharsets.UTF_8), where)
        } catch (e: Exception) {
            LOGGER.error("[studio] 读 {} 失败（{}），本次按内置默认值走；修好文件后敲 /gtetstudio reload 即可", where, e.toString())
            StudioConfig.parseText("{}", where)
        }

        for (problem in parsed.problems) {
            if (problem.error) LOGGER.error("[studio] {}", problem.message)
            else LOGGER.warn("[studio] {}", problem.message)
        }

        StudioLimits.applyValues(parsed.values, where)
        LOGGER.info("[studio] 已应用渲染上限（来自 {}）：{}", where, StudioLimits.summary())

        val problems = if (parsed.problems.isEmpty()) "" else "；${parsed.problems.size} 条问题见日志"
        return "CONF $where  ${StudioLimits.summary()}$problems"
    }

    /**
     * 第一次要用到模型时才读配置（渲染路径每帧都会经过 [get]，靠 [configLoaded] 保证只读一次）。
     */
    private fun loadConfigIfNeeded() {
        if (configLoaded) return
        reloadConfig()
    }

    // ────────────────────────── 内部 ──────────────────────────

    private fun ensureIndexed() {
        if (indexedGeneration == generation) return
        indexedGeneration = generation
        index.clear()
        loaded.clear()
        failed.clear()

        val dir = configDir()
        ensureDirectoryAndDefault(dir)
        loadConfigIfNeeded()

        if (!Files.isDirectory(dir)) {
            LOGGER.error("[studio] 配置目录 {} 不存在也建不出来，工作室没有模型可用", dir)
            return
        }

        try {
            Files.newDirectoryStream(dir, "*.json").use { stream ->
                for (path in stream) {
                    // studio.json 是**上限配置**，不是模型定义：它没有 id，进索引只会白报一条错
                    if (path.fileName.toString() == DEFAULT_CONFIG_FILE) continue
                    try {
                        val id = peekId(path)
                        val previous = index.put(id, path)
                        if (previous != null) {
                            LOGGER.error(
                                "[studio] {} 与 {} 的 id 都是 {}，后者被忽略 —— 同一个 id 只能有一份定义",
                                previous.fileName, path.fileName, id
                            )
                            index[id] = previous
                        }
                    } catch (e: Exception) {
                        LOGGER.error("[studio] 索引 {} 失败：{}", path, e.message)
                    }
                }
            }
        } catch (e: Exception) {
            LOGGER.error("[studio] 列不出配置目录 {}：{}", dir, e.toString())
        }
    }

    private fun configDir(): Path = FMLPaths.CONFIGDIR.get().resolve(CONFIG_DIR)

    /** 目录/示例文件不在就补上（只在缺失时写，绝不覆盖用户改过的文件）。 */
    private fun ensureDirectoryAndDefault(dir: Path) {
        try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            LOGGER.error("[studio] 建不出配置目录 {}：{}", dir, e.toString())
            return
        }
        for ((resource, fileName) in DEFAULT_DEFINITIONS) {
            releaseIfMissing(dir.resolve(fileName), resource)
        }
        releaseIfMissing(dir.resolve(DEFAULT_CONFIG_FILE), DEFAULT_CONFIG_RESOURCE)
        for ((resource, relative) in DEFAULT_MODEL_FILES) {
            releaseIfMissing(dir.resolve(relative), resource)
        }
    }

    /** 把 jar 里的内置文件释放到磁盘（已存在就跳过）。 */
    private fun releaseIfMissing(target: Path, resource: String) {
        if (Files.exists(target)) return
        try {
            val stream = StudioLibrary::class.java.getResourceAsStream(resource)
            if (stream == null) {
                LOGGER.warn("[studio] mod 里没有内置的 {}，需要的话请自己往 {} 放", resource, target)
                return
            }
            target.parent?.let { Files.createDirectories(it) }
            stream.use { Files.copy(it, target) }
            LOGGER.info("[studio] 已释放内置示例 {}（之后以这个文件为准，随便改）", target)
        } catch (e: Exception) {
            LOGGER.error("[studio] 释放 {} 到 {} 失败：{}", resource, target, e.toString())
        }
    }

    /** 只为了拿 `id` 而粗读一遍（索引阶段用）。 */
    private fun peekId(path: Path): ResourceLocation {
        val json = readJson(path)
        val id = json.get("id")?.asString
            ?: throw StudioLoadException("${path.fileName}: 缺字段 \"id\"")
        return ResourceLocation.tryParse(id)
            ?: throw StudioLoadException("${path.fileName}: id \"$id\" 不是合法资源路径")
    }

    private fun loadFrom(path: Path): StudioInstance {
        val json = readJson(path)
        val where = path.fileName.toString()
        val def = StudioModelDef.parse(json, where)
        val rm = Minecraft.getInstance().resourceManager
            ?: throw StudioLoadException("$where: 资源管理器还没就绪，稍后再试")
        return StudioModelLoader.load(def, rm, where)
    }

    private fun readJson(path: Path): JsonObject {
        val text = try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw StudioLoadException("${path.fileName}: 读不出来（$e）", e)
        }
        val cleaned = stripJsonComments(text)
        val element = try {
            JsonParser.parseString(cleaned)
        } catch (e: JsonSyntaxException) {
            throw StudioLoadException("${path.fileName}: 不是合法 JSON —— ${e.message}", e)
        }
        if (!element.isJsonObject) {
            throw StudioLoadException("${path.fileName}: 根节点必须是 JSON 对象")
        }
        return element.asJsonObject
    }
}