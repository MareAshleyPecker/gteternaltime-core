package rain.gtetcore.gtet.studio.data

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import org.slf4j.Logger
import rain.gtetcore.gtet.studio.api.StudioClip
import rain.gtetcore.gtet.studio.api.StudioModel
import rain.gtetcore.gtet.studio.api.StudioTrack
import rain.gtetcore.gtet.studio.config.StudioLimits
import rain.gtetcore.gtet.studio.format.*
import rain.gtetcore.gtet.studio.kernel.StudioMesh
import rain.gtetcore.gtet.studio.kernel.StudioMeshBuilder
import rain.gtetcore.gtet.studio.kernel.StudioMeshException
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadEvaluator
import rain.gtetcore.gtet.studio.kernel.cad.StudioCadException
import rain.gtetcore.gtet.studio.render.StudioRenderTypes

/**
 * `StudioModelDef`（JSON）→ [StudioInstance]（自有网格 + 材质绑定 + 动画）。
 *
 * ## M1a 走的路子（不再是 Forge 的 `forge:obj`）
 * ```
 * config/gtetstudio/models/clock.obj   （磁盘，主推）   —— 或 ——   assets/<ns>/models/obj/clock.obj（资源包）
 *   → StudioAsset（资源 / 磁盘两种实现）
 *   → StudioFormats 按 format 或扩展名挑解析器
 *   → ObjFormat.parse(bytes, ctx) → kernel.StudioMesh + 材质表 + 问题汇总
 *   → 组名校验 / 材质绑定 / 动画片段 → StudioInstance
 * ```
 *
 * ## 这里只做 CPU 的事
 * 本类**不碰 GL**（不建 VBO、不注册贴图）：那两件事必须在**渲染线程**上做，统一由
 * `render/StudioRenderCache` 在第一次要画的时候完成。所以载入可以在任何线程上跑，
 * `/gtetstudio reload` 也能同步返回"读没读成"。
 *
 * ## 与 M0 的关系
 * 组名校验、材质名校验、包围盒、动画轨道解析的口径**一律不变** —— M0 那份 `clock.json`
 * 换到这套加载器上必须还是同一个时钟（M1a 的硬要求：M0 不回退）。
 *
 * @author rain fox
 */
object StudioModelLoader {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** 没有 `map_Kd` 时用的兜底贴图 —— 与渲染层共用一个常量，避免两处定义漂移。 */
    private val MISSING_TEXTURE: ResourceLocation = StudioRenderTypes.MISSING_TEXTURE

    /**
     * 载入一份定义。
     *
     * ## 两条**互斥**的路（M3a 起）
     * ```
     * def.features 非空 ⇒ CAD 路径：网格由 kernel/cad 求值 features 生成，不读任何来源文件
     * def.features 为空 ⇒ OBJ 路径：原来那一套（source → StudioAsset → StudioFormat → StudioMesh）
     * ```
     * 两条路**共用**后面的每一道闸：单模型三角形上限（§7.1）、组名校验、材质绑定、包围盒、
     * 动画轨道 —— 所以 CAD 模型不可能绕过任何一条检查。
     *
     * @param rm    当前资源管理器（**磁盘来源也需要它**：贴图可能仍然在资源包里）
     * @param where 出错时写进日志的「这是哪份文件」（一般是 JSON 文件名）
     * @throws StudioLoadException 数据本身有问题（文件缺失 / 组名对不上 / 字段非法 / 超过三角形上限）
     */
    @JvmStatic
    fun load(def: StudioModelDef, rm: ResourceManager, where: String): StudioInstance =
        if (def.features.isNotEmpty()) loadFromFeatures(def, rm, where) else loadFromSource(def, rm, where)

    // ────────────────────────── 路径 ①：CAD（features）──────────────────────────

    /**
     * **`features` → 网格**：设计文档 §4 :173 留的那个字段，到这里真正生效。
     *
     * 求值全在 `kernel/cad`（纯几何，能脱机自检）；这里只负责把它接进载入管线：
     * 建组 → 建议网格 → 过三角形闸 → 校组名 → 绑材质。
     */
    private fun loadFromFeatures(def: StudioModelDef, rm: ResourceManager, where: String): StudioInstance {
        val src = def.source
        if (src?.file != null || src?.model != null) {
            LOGGER.warn(
                "[studio] {}: 这份 JSON 同时写了 features 与 source.file/model —— " +
                    "CAD 路径**不读来源文件**，那一行被忽略（要回到 OBJ 就把 features 整个删掉）",
                where
            )
        }

        val display = "features(${def.features.size} 条特征)"
        val builder = StudioMeshBuilder(where)
        val evaluated = try {
            StudioCadEvaluator.evaluate(def.features, builder, where)
        } catch (e: StudioCadException) {
            throw StudioLoadException(e.message ?: "$where: features 求值失败", e)
        } catch (e: StudioMeshException) {
            throw StudioLoadException("$where: features 解出来的网格不合法 —— ${e.message}", e)
        }

        val mesh = try {
            builder.build()
        } catch (e: StudioMeshException) {
            throw StudioLoadException("$where: features 解出来的网格不合法 —— ${e.message}", e)
        }

        enforceTriangleLimit(mesh, where, display)
        validateGroupNames(def, mesh, where, display)
        val materials = bindFeatureMaterials(def, where)

        if (evaluated.unusedFeatures.isNotEmpty()) {
            LOGGER.info(
                "[studio] {}: 这些 2D 特征没有被任何 extrude 用到，不产生几何：{}",
                where, evaluated.unusedFeatures
            )
        }

        return assemble(
            def = def,
            mesh = mesh,
            materialNames = materials.map { it.name },
            materials = materials,
            sourceLabel = display,
            where = where,
            extra = "组 ${evaluated.groupNames}，" +
                evaluated.trianglesByGroup.entries.joinToString("、") { "${it.key} ${it.value} 面" },
        )
    }

    /**
     * CAD 路径的材质：**贴图写在 JSON 的 `materials.<名字>.texture`**（那条路没有 MTL）。
     *
     * `extrude` 里写了的材质名必须在 `materials` 段里声明 —— 没声明就**报错**，
     * 而不是让它悄悄画成紫黑缺省贴图（§7.1 的精神：拒绝，不静默降级）。
     */
    private fun bindFeatureMaterials(def: StudioModelDef, where: String): List<StudioMaterialBinding> {
        val used = LinkedHashSet<String>()
        for (f in def.features) {
            val m = f.material ?: continue
            used += m
        }
        val unknown = used.filter { it !in def.materials }
        if (unknown.isNotEmpty()) {
            throw StudioLoadException(
                "$where: features 里有 extrude 用了材质 $unknown，但 materials 段里没有声明它们；" +
                    "已声明的是 ${def.materials.keys}（CAD 路径没有 MTL，材质与贴图都得写在 JSON 里）"
            )
        }

        val out = ArrayList<StudioMaterialBinding>(used.size)
        for (name in used) {
            val declared = def.materials.getValue(name)
            val ref = declared.texture
            val texture = if (ref == null) {
                LOGGER.warn(
                    "[studio] {}: 材质 {} 没写 texture，会画成紫黑缺省贴图（CAD 路径的贴图写在 materials.{}.texture）",
                    where, name, name
                )
                StudioTextureRef.Resource(MISSING_TEXTURE)
            } else {
                featureTexture(ref, where, name)
            }
            out += StudioMaterialBinding(name, declared.render, texture)
        }
        return out
    }

    /**
     * CAD 路径的贴图**只能是资源包路径**（`gtetcore:block/xxx` 或写全的 `...textures/....png`）。
     *
     * 相对路径（`clock.png`）在这条路上没有意义：没有"本文件"可以相对。
     * 所以直接拒绝，并说清替代做法 —— 不猜、不静默。
     */
    private fun featureTexture(ref: String, where: String, material: String): StudioTextureRef {
        if (!ref.contains(':')) {
            throw StudioLoadException(
                "$where: materials.$material.texture=\"$ref\" 不是资源路径 —— " +
                    "CAD（features）路径没有来源文件，相对路径无从解析；" +
                    "请写成 \"gtetcore:block/xxx\"（会自动补 textures/ 与 .png），" +
                    "或改用 source+OBJ 走 MTL"
            )
        }
        val rl = ResourceLocation.tryParse(ref)
            ?: throw StudioLoadException("$where: materials.$material.texture=\"$ref\" 不是合法的资源路径")
        return StudioTextureRef.Resource(resourceTextureLocation(rl))
    }

    // ────────────────────────── 路径 ②：OBJ（source）──────────────────────────

    private fun loadFromSource(def: StudioModelDef, rm: ResourceManager, where: String): StudioInstance {
        val source = def.source
            ?: throw StudioLoadException("$where: 既没有 features，也没有 source —— 没有任何东西可以生成网格")
        val asset = openSource(source, rm, where)
        val format = pickFormat(source, asset, where)

        if (source.ignoredOptions.isNotEmpty()) {
            LOGGER.info(
                "[studio] {}: source 里的 {} 是 M0 传给 Forge `forge:obj` 的选项，自写解析器**不再使用**" +
                    "（外面留着不报错，但别指望它生效；只有 flip_v 仍然由我们自己实现）",
                where, source.ignoredOptions
            )
        }
        if (def.materials.values.any { it.texture != null }) {
            LOGGER.info(
                "[studio] {}: materials 里的 texture 只有 CAD（features）路径才生效；" +
                    "OBJ 路径的贴图一律来自 MTL 的 `map_Kd`",
                where
            )
        }

        val ctx = StudioImportContext(
            where = "$where（${asset.display}）",
            textureAliases = source.textures,
            flipV = source.flipV,
            mtlOverride = source.mtlOverride,
            openSibling = { ref ->
                val sibling = StudioAsset.resolveRef(asset, ref, rm)
                if (sibling == null) {
                    LOGGER.error("[studio] {}: 解析不出 `{}` 指向的文件（相对本文件找，或写成资源路径）", where, ref)
                    null
                } else {
                    try {
                        sibling.readBytes()
                    } catch (e: Exception) {
                        LOGGER.error("[studio] {}: 读 {} 失败：{}", where, sibling.display, e.message)
                        null
                    }
                }
            },
        )

        // ── 1. 解析（坏行不炸，最后报汇总）──
        val result = try {
            format.parse(asset.readBytes(), ctx)
        } catch (e: StudioFormatException) {
            throw StudioLoadException(e.message ?: "$where: 用 ${format.id} 解析 ${asset.display} 失败", e)
        } catch (e: StudioMeshException) {
            throw StudioLoadException("$where: ${asset.display} 解出来的网格不合法 —— ${e.message}", e)
        }
        logNotes(where, asset, result)

        val mesh = result.mesh

        // ── 2. 载入期上限（设计文档 §7.1：超限**拒绝**，不静默削面）──
        enforceTriangleLimit(mesh, where, asset.display)

        // ── 3. 组名校验：JSON 里写的部件名必须在网格里真的存在 ──
        validateGroupNames(def, mesh, where, asset.display)

        // ── 4. 材质 → 贴图 → 渲染方式 ──
        val materials = bindMaterials(def, result, asset, rm, where)

        // ── 5. 组装 ──
        return assemble(
            def = def,
            mesh = mesh,
            materialNames = result.materials.map { it.name },
            materials = materials,
            sourceLabel = "${asset.kind}:${asset.display}",
            where = where,
            extra = null,
        )
    }

    // ────────────────────────── 两条路共用的闸 ──────────────────────────

    /**
     * 载入期上限（设计文档 §7.1：超限**拒绝**，不静默削面）。
     *
     * 这里读的是**运行时的值**（config/gtetstudio/studio.json 的 `maxTrianglesPerModel`，
     * 由 `StudioLibrary` 读盘后灌进 `StudioLimits`；配置缺失/坏值才是内置默认）。
     * 所以改完配置敲 `/gtetstudio reload` 再走一遍这里，新上限立刻生效。
     *
     * ⚠️ **CAD 路径也过这道闸**：`features` 生成的网格不会因为"是参数化的"就被网开一面。
     */
    private fun enforceTriangleLimit(mesh: StudioMesh, where: String, display: String) {
        val limit = StudioLimits.maxTrianglesPerModel
        if (mesh.triangleCount > limit) {
            throw StudioLoadException(
                "$where: $display 有 ${mesh.triangleCount} 个三角形，超过单模型上限 $limit" +
                    "（上限来自 ${StudioLimits.source} 的 maxTrianglesPerModel，见设计文档 §7.1；" +
                    "模型太大请先减面，或把该值调大后 /gtetstudio reload）"
            )
        }
    }

    /** 组名校验：JSON 里写的 `parts` 与 `animation.tracks[].part` 必须在网格里真的存在。 */
    private fun validateGroupNames(def: StudioModelDef, mesh: StudioMesh, where: String, display: String) {
        val available = mesh.groupNames
        val declaredParts = if (def.parts.isEmpty()) available else def.parts.map { it.name }
        for (name in declaredParts) {
            if (name !in available) {
                throw StudioLoadException(
                    "$where: 部件 \"$name\" 在 $display 里不存在；可用的组名是 $available" +
                        "（OBJ 的组名来自 `g`/`o`；CAD 模型的组名就是每个 extrude 的 name；" +
                        "没有分组的三角形落在 \"${StudioMesh.DEFAULT_GROUP}\"）"
                )
            }
        }
        def.animation?.tracks?.forEach { track ->
            if (track.part !in available) {
                throw StudioLoadException(
                    "$where: animation 里给部件 \"${track.part}\" 配了轨道，但这个组名在 $display 里不存在；" +
                        "可用组名是 $available"
                )
            }
        }
    }

    /**
     * 收尾：建 [StudioModel] + 动画片段 + 一行载入日志。**两条路径共用**，
     * 所以 CAD 模型与 OBJ 模型在渲染器眼里**完全一样** —— 渲染路径一行都不用改。
     */
    private fun assemble(
        def: StudioModelDef,
        mesh: StudioMesh,
        materialNames: Collection<String>,
        materials: List<StudioMaterialBinding>,
        sourceLabel: String,
        where: String,
        extra: String?,
    ): StudioInstance {
        val model = StudioModel(
            id = def.id,
            // ⚠️ 这里是网格的**全部组名**，不是 JSON parts 里那一小撮：
            //    部件矩阵是按组名叠的，暴露全集才能让调用方知道"我还能动哪些部件"。
            partNames = LinkedHashSet(mesh.groupNames),
            materialNames = LinkedHashSet(materialNames),
            bounds = mesh.bounds,
            anchor = def.anchor,
            mesh = mesh,
            viewDistance = def.viewDistance,
        )
        val clip = buildClip(def)

        LOGGER.info(
            "[studio] {}: 已载入 {} —— 来源 {}，组名 {}，材质 {}，三角形 {}，包围盒 {}，轨道 {} 条，视距 {}{}",
            where, def.id, sourceLabel, mesh.groupNames, materialNames,
            mesh.triangleCount, mesh.bounds, clip.tracks.size, def.viewDistance,
            if (extra == null) "" else "，$extra",
        )

        return StudioInstance(def, model, clip, materials, sourceLabel)
    }

    // ────────────────────────── 来源 ──────────────────────────

    private fun openSource(source: StudioSourceDef, rm: ResourceManager, where: String): StudioAsset {
        val file = source.file
        if (file != null) {
            return StudioAsset.ofDisk(file)
        }
        val location = source.model
            ?: throw StudioLoadException("$where: source 里既没有 file 也没有 model")
        return StudioAsset.ofResource(rm, location)
    }

    private fun pickFormat(
        source: StudioSourceDef,
        asset: StudioAsset,
        where: String,
    ): StudioFormat {
        source.format?.let { id ->
            return StudioFormats.byId(id)
                ?: throw StudioLoadException("$where: source.format=\"$id\" 没有对应的导入器（现有：${StudioFormats.ids()}）")
        }
        return StudioFormats.forFileName(asset.name)
            ?: throw StudioLoadException(
                "$where: 认不出 ${asset.name} 的格式（扩展名没注册任何导入器）；" +
                    "请显式写 source.format，现有格式：${StudioFormats.ids()}"
            )
    }

    // ────────────────────────── 材质 ──────────────────────────

    /**
     * 材质绑定：MTL 材质名 → 渲染方式（JSON 的 `materials`）+ 贴图来源。
     *
     * 两种贴图来源分开处理（**这是 M1a 最容易被糊过去的地方，所以写清楚**）：
     * - **资源包贴图**：`ns:block/xxx` → `ns:textures/block/xxx.png`。
     *   ⚠️ 必须补全 `textures/` 前缀与 `.png` 后缀：1.20.1 的 `SimpleTexture` 是
     *   `rm.getResource(location)` **原样查**（`SimpleTexture$TextureImage#load` 的字节码里没有拼接），
     *   所以传给 `RenderType` 的必须就是**文件路径**本身。写成 `ns:textures/xxx.png` 也行，我们两种都接。
     * - **磁盘 PNG**：相对本 OBJ 的文件名（`clock.png`）或绝对路径。它不是资源包条目，
     *   所以走"造一个 id + 动态注册"（`StudioTextureRef.Disk`，注册在 `render/StudioTextures`）。
     */
    private fun bindMaterials(
        def: StudioModelDef,
        result: StudioImportResult,
        base: StudioAsset,
        rm: ResourceManager,
        where: String,
    ): List<StudioMaterialBinding> {
        val declared = def.materials.keys
        val available = result.materials.map { it.name }
        val unknown = declared.filter { it !in available }
        if (unknown.isNotEmpty()) {
            throw StudioLoadException(
                "$where: JSON 的 materials 里声明了 $unknown，但模型里没有这些材质名；可用的材质名是 $available" +
                    "（材质名来自 MTL 的 `newmtl` 与 OBJ 的 `usemtl`）"
            )
        }

        val out = ArrayList<StudioMaterialBinding>(result.materials.size)
        for (material in result.materials) {
            val kind = def.materials[material.name]?.render
            if (kind == null) {
                LOGGER.info(
                    "[studio] {}: 材质 {} 没在 JSON 的 materials 里登记渲染方式，按 {} 画" +
                        "（要让它发光/半透明就按 MTL 里的材质名声明一行）",
                    where, material.name, StudioRenderTypes.CUTOUT
                )
            }
            val ref = material.texture
            val texture = if (ref == null) {
                LOGGER.warn(
                    "[studio] {}: 材质 {} 没有 `map_Kd`（或 MTL 没读到），会画成紫黑缺省贴图",
                    where, material.name
                )
                StudioTextureRef.Resource(MISSING_TEXTURE)
            } else {
                resolveTexture(ref, base, rm, where, material.name)
            }
            out += StudioMaterialBinding(material.name, kind ?: StudioRenderTypes.CUTOUT, texture)
        }
        return out
    }

    /** 把 MTL 里写的贴图引用解析成"资源包贴图"或"磁盘 PNG"。 */
    private fun resolveTexture(
        ref: String,
        base: StudioAsset,
        rm: ResourceManager,
        where: String,
        material: String,
    ): StudioTextureRef {
        val asset = StudioAsset.resolveRef(base, ref, rm)
        if (asset == null) {
            LOGGER.error(
                "[studio] {}: 材质 {} 的贴图 \"{}\" 找不到（相对 {} 找；资源包贴图要写成 ns:block/xxx）",
                where, material, ref, base.display
            )
            return StudioTextureRef.Resource(MISSING_TEXTURE)
        }
        return when (asset) {
            is DiskAsset -> StudioTextureRef.Disk(StudioDiskTextureIds.of(asset.path), asset.path)
            is ResourceAsset -> StudioTextureRef.Resource(resourceTextureLocation(asset.location))
            else -> StudioTextureRef.Resource(MISSING_TEXTURE)
        }
    }

    /**
     * 资源路径 → **贴图管理器里那个 id**。
     *
     * - 已经带 `textures/` 与 `.png` 的：原样用；
     * - `ns:block/xxx` 这种简写：补成 `ns:textures/block/xxx.png`（M0 的写法，保持兼容）。
     */
    private fun resourceTextureLocation(location: ResourceLocation): ResourceLocation {
        var path = location.path
        if (path.endsWith(".png")) {
            // 已经指向真实文件（资源包里的 PNG 也可能直接放在 OBJ 旁边），原样使用
            return location
        }
        if (!path.startsWith("textures/")) path = "textures/$path"
        return ResourceLocation.tryBuild(location.namespace, "$path.png") ?: location
    }

    // ────────────────────────── 动画 ──────────────────────────

    private fun buildClip(def: StudioModelDef): StudioClip {
        val anim = def.animation ?: return StudioClip.EMPTY

        if (anim.driver != StudioClipDriver.GAME_TIME) {
            LOGGER.warn(
                "[studio] {}: animation.driver=\"{}\" 还没实现（预留枚举：{}），本模型不会动",
                def.id, anim.driver, StudioClipDriver.RESERVED
            )
            return StudioClip(anim.driver, emptyList())
        }

        val tracks = ArrayList<StudioTrack>(anim.tracks.size)
        for (t in anim.tracks) {
            if (t.hasKeys) {
                LOGGER.info(
                    "[studio] {}: 部件 {} 的轨道带了 keys（关键帧，M4 才实现），现在忽略它、按 speed={}/s 匀速转",
                    def.id, t.part, t.speed
                )
            }
            tracks += StudioTrack(t.part, t.axis, t.speed)
        }
        return StudioClip(anim.driver, tracks)
    }

    // ────────────────────────── 日志 ──────────────────────────

    /** 把解析问题按严重程度打出来 —— 坏行计数一定要落地到日志，否则用户只会看到"模型少了半边"。 */
    private fun logNotes(where: String, asset: StudioAsset, result: StudioImportResult) {
        val sink = result.noteSink
        if (sink.errorCount + sink.warnCount > 0) {
            LOGGER.warn("[studio] {}: {} 解析时 {}", where, asset.display, sink.summary())
        }
        for (note in result.notes) {
            logNote(where, note)
        }
    }

    private fun logNote(where: String, note: StudioImportNote) {
        when (note.severity) {
            StudioNoteSeverity.ERROR -> LOGGER.error("[studio] {}: {}", where, note)
            StudioNoteSeverity.WARN -> LOGGER.warn("[studio] {}: {}", where, note)
            StudioNoteSeverity.INFO -> LOGGER.info("[studio] {}: {}", where, note)
        }
    }
}