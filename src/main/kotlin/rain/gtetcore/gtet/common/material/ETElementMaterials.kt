package rain.gtetcore.gtet.common.material

import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.chemical.material.info.MaterialIconSet
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.material.ETMaterial.TEST

object ETElementMaterials {

    fun register() {
        // 用 Gtetcore.id() 而非 GTCEu.id() — 材料会被注册到 gtetcore 自己的 namespace，
        // 绕开 GTCEu 注册表对非 gtceu mod 的 freeze 限制。
        TEST = Material.Builder(Gtetcore.id("test_materials"))
            .color(0xC3D1FF).secondaryColor(0x397090).iconSet(MaterialIconSet.METALLIC)
            .element(ETElements.TEST)
            .buildAndRegister()
    }

}