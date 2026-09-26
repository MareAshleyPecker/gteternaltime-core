package rain.gtetcore.gtet.util.lang

import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.chemical.material.Material.Builder
import rain.gtetcore.gtet.data.lang.LangHandler

// ======================== Material 扩展（buildAndRegister 之后） ========================
/**
 * 注册材料中文名
 * @return [Material]
 */
@Suppress("unused")
fun Material.cn(name: String): Material {
    LangHandler.addMaterialName(this.name, name)
    return this
}
// ======================== Builder 扩展（buildAndRegister 之前） ========================
/**
 * 在 Builder 链上注册中文名,可在 [Material.Builder.buildAndRegister] 前调用。
 * @return [Builder]
 */
fun Builder.cn(name: String): Builder {
    LangHandler.addMaterialName(this.id.path, name)
    return this
}



