package rain.gtetcore.gtet.util.lang

import com.gregtechceu.gtceu.api.data.chemical.material.Material
import rain.gtetcore.gtet.data.lang.LangHandler

// ======================== Material 扩展（buildAndRegister 之后） ========================

/**
 * 注册材料中文名，返回 Material 自身。
 *
 * ```kotlin
 * val mat = Material.Builder(id).buildAndRegister().cn("示例材料")
 * ```
 */
fun Material.cn(name: String): Material {
    LangHandler.addMaterialName(this.name, name)
    return this
}

// ======================== Builder 扩展（buildAndRegister 之前） ========================

/**
 * 在 Builder 链上注册中文名，返回 Builder 自身，可在 [Material.Builder.buildAndRegister] 前调用。
 *
 * ```kotlin
 * Material.Builder(Gtetcore.id("materialname"))
 *     .cn("示例材料")
 *     .buildAndRegister()
 * ```
 */
fun Material.Builder.cn(name: String): Material.Builder {
    LangHandler.addMaterialName(this.id.path, name)
    return this
}
