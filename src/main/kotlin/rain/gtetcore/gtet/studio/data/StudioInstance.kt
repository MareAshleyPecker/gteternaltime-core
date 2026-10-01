package rain.gtetcore.gtet.studio.data

import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.studio.api.StudioClip
import rain.gtetcore.gtet.studio.api.StudioModel
import java.nio.file.Path

/**
 * 一份材质最后指向哪张**贴图**。
 *
 * ## 为什么必须分成两种（这是 M1a 最容易糊过去的地方）
 * M0 里贴图一律是资源路径（`gtetcore:block/xxx` → `gtetcore:textures/block/xxx.png`），
 * 渲染时把 `ResourceLocation` 交给 `RenderType` 就行 —— 原版管线会去资源包里找。
 *
 * **磁盘上的 PNG 不行**：它不是资源包条目，`ResourceLocation` 只是"贴图管理器里的一把钥匙"，
 * 拿一个没注册过的 id 去建 `RenderType`，原版会给你**紫黑格**（`TextureManager.getTexture`
 * 找不到就塞一张 missing 纹理，而且会缓存住这个失败）。
 * 正确的做法是：自己把 PNG 读成 `NativeImage`，建一个 `DynamicTexture`，
 * 再 `Minecraft.getInstance().getTextureManager().register(自己造的 id, 它)` ——
 * 之后这个 id 就和资源包贴图一样能用了（GTLAdditions 的机器特效贴图就是这么做的）。
 *
 * ⇒ 于是贴图分成两种来源，**各自有明确的实现**，不假装统一：
 * - [Resource]：资源包，照旧；
 * - [Disk]：磁盘 PNG，走上面那条动态注册。
 *
 * 注册动作在渲染层（`render/StudioTextures`），因为它必须发生在**渲染线程**上（要建 GL 纹理）；
 * 这一层只负责"说清是哪一种、路径是什么"。
 */
sealed class StudioTextureRef {

    /** 贴图管理器里的 id（`RenderType` 用它）。 */
    abstract val location: ResourceLocation

    /** 资源包贴图。 */
    class Resource(override val location: ResourceLocation) : StudioTextureRef() {
        override fun toString(): String = "resource:$location"
    }

    /**
     * 磁盘 PNG。
     *
     * @param location 自己造出来的稳定 id（见 [StudioDiskTextureIds]）
     * @param path     PNG 的绝对路径
     */
    class Disk(override val location: ResourceLocation, val path: Path) : StudioTextureRef() {
        override fun toString(): String = "disk:$path"
    }
}

/**
 * 一个材质（MTL 的 `newmtl`）→ 渲染方式 + 贴图。
 *
 * @param name       材质名（JSON 的 `materials` 段按它声明）
 * @param renderKind `cutout` / `eyes` / `translucent`（见 `render/StudioRenderTypes`）
 * @param texture    贴图来源
 */
class StudioMaterialBinding(
    val name: String,
    val renderKind: String,
    val texture: StudioTextureRef,
)

/**
 * **一份载入完成的模型**（M0 里这个类叫 `StudioAsset`）。
 *
 * M1a 把 `StudioAsset` 这个名字让给了"资产读取"抽象（`data/StudioAsset.kt`，资源 / 磁盘两种实现），
 * 所以这份"JSON + OBJ 读完之后的东西"改叫 `StudioInstance`：
 * [model] 是静态部分（几何 + 材质名 + 包围盒），[clip] 是动画部分，[materials] 把材质接到贴图上。
 * **几何是工作室自己的**（`kernel.StudioMesh`），不再依赖 Forge 的 `CompositeRenderable`。
 *
 * @param def      原始 JSON（字段级信息，诊断/调试用）
 * @param model    静态模型（几何 + 包围盒 + 锚点）
 * @param clip     动画片段
 * @param materials 材质绑定表
 * @param source   模型文件的来源与路径（日志用）
 */
class StudioInstance(
    val def: StudioModelDef,
    val model: StudioModel,
    val clip: StudioClip,
    val materials: List<StudioMaterialBinding>,
    val source: String,
) {

    private val byName: Map<String, StudioMaterialBinding> = materials.associateBy { it.name }

    fun material(name: String?): StudioMaterialBinding? = if (name == null) null else byName[name]
}