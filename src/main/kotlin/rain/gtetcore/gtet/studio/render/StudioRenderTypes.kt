package rain.gtetcore.gtet.studio.render

import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.studio.render.StudioRenderTypes.CUTOUT

/**
 * 材质 → [RenderType] 的映射表。
 *
 * JSON 的 `materials.<名字>.render` 取值就是这里的三个常量。选型依据（设计文档 §7 第 3 条）：
 *
 * | kind | RenderType | 什么时候用 |
 * |---|---|---|
 * | `cutout` | [RenderType.entityCutoutNoCull] | **默认**。镂空结构必开双面（`NoCull`），用 alpha 剪裁而不是半透明，省掉深度排序 |
 * | `eyes` | [RenderType.eyes] | 自发光。这个 shader 不带光照图，等价于「全亮」 |
 * | `translucent` | [RenderType.entityTranslucent] | 真半透明（要排序，能不用就不用） |
 *
 * ## 贴图路径的形状（M1a 复核过，仍然容易踩）
 * 传给 `RenderType` 的必须是**指向文件的完整资源路径**，形如 `gtetcore:textures/block/foo.png`。
 * 证据：1.20.1 的 `SimpleTexture.getTextureImage` 就是 `TextureImage.load(rm, this.location)`，
 * 而 `TextureImage.load` 直接 `rm.getResource(location)` —— **全程没有任何路径拼接**
 * （已用 `javap -c` 看过 `SimpleTexture` 与 `SimpleTexture$TextureImage` 的字节码）。
 * 所以 `gtetcore:block/foo` 这种简写**必须先补成** `gtetcore:textures/block/foo.png`
 * （补的地方在 `data/StudioModelLoader.resourceTextureLocation`），千万别指望原版帮你拼。
 *
 * M0 走 Forge 的 `CompositeRenderable` 时，这个完整路径是 `ObjModel.ModelMesh.bake` 拼好交出来的；
 * M1a 换自有网格之后，拼接由我们自己做 —— 这是"换加载器"时必须自己接住的一环。
 *
 * @author rain fox
 */
object StudioRenderTypes {

    const val CUTOUT = "cutout"
    const val EYES = "eyes"
    const val TRANSLUCENT = "translucent"

    /**
     * 兜底贴图：模型没写 `map_Kd`（或贴图找不到）时用它 —— 原版的紫黑缺省纹理。
     *
     * ⚠️ 路径必须**写全**（`textures/` 前缀 + `.png`）：1.20.1 的 `SimpleTexture` 是
     * `rm.getResource(location)` 原样查（`SimpleTexture$TextureImage#load` 的字节码里没有拼接），
     * 所以 `RenderType` 拿到的 id 就是**文件路径本身**。
     */
    @JvmField
    val MISSING_TEXTURE: ResourceLocation =
        ResourceLocation.tryBuild("minecraft", "textures/missingno.png")!!

    /** 按 [kind] 造一个渲染类型；[kind] 不认识时退回 [CUTOUT]（调用方负责提前校验取值）。 */
    @JvmStatic
    fun of(kind: String, texture: ResourceLocation): RenderType = when (kind) {
        EYES -> RenderType.eyes(texture)
        TRANSLUCENT -> RenderType.entityTranslucent(texture)
        else -> RenderType.entityCutoutNoCull(texture)
    }
}