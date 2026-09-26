package rain.gtetcore.gtet.util

import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture
import net.minecraft.resources.ResourceLocation
import rain.gtetcore.gtet.util.GtocoreAssets.gtocoreTexture

/**
 * 取用随本 mod 的 jar 一起分发的 GTOCore 贴图副本（`src/main/resources/assets/gtocore/textures/`）。
 *
 * 这批素材来自 GTOCore 项目、**版权归 GTOCore 作者**；副本在 jar 里照原样挂在 **`gtocore`**
 * 命名空间下（不是 `gtetcore`），所以凡是按 `gtocore:...` 引用它们的地方（例如同批 `.mcmeta`
 * 里的 CTM `connection`）都无需改动。
 */
object GtocoreAssets {

    /** 副本所在命名空间。 */
    const val NAMESPACE: String = "gtocore"

    /**
     * 贴图相对路径 → 贴图用 [ResourceLocation]。
     *
     * [path] 相对 `assets/gtocore/textures/` 写，**不带** `textures/` 前缀、**不带** `.png`；
     * 误传完整的 `textures/xxx.png` 也能容忍（会自动去重，不会拼成 `textures/textures/`）。
     *
     * ```kotlin
     * GtocoreAssets.gtocoreTexture("block/casings/abs_black_casing")
     * // -> gtocore:textures/block/casings/abs_black_casing.png
     * ```
     */
    @JvmStatic
    fun gtocoreTexture(path: String): ResourceLocation {
        return ResourceLocation.tryBuild("gtocore", path)!!
    }

    /**
     * 同 [gtocoreTexture]，但直接给出 LDLib 的 UI 贴图对象，写 GUI 时少包一层。
     *
     * ```kotlin
     * GtocoreAssets.gtocoreGuiTexture("gui/sort")   // gtocore:textures/gui/sort.png
     * ```
     *
     * 注意：[ResourceTexture] 内部把 `ResourceLocation` 原样交给
     * `RenderSystem.setShaderTexture`，因此路径必须**带** `textures/` 与 `.png`
     * ——这与方块/物品模型 JSON 里 `"textures": {"all": "gtocore:block/xxx"}`
     * 那种不带前缀后缀的写法是两套规则。
     */
    @JvmStatic
    fun gtocoreGuiTexture(path: String): ResourceTexture = ResourceTexture(gtocoreTexture(path))
}
