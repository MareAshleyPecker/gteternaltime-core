package rain.gtetcore.gtet.common.data.item

import com.google.common.collect.Table
import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.gregtechceu.gtceu.common.data.GTMaterialItems
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.world.item.Item

/**
 * 材料物品表 — 直接引用 GTCEu 的 [GTMaterialItems.MATERIAL_ITEMS]。
 * 物品注册由 [rain.gtetcore.GTET.mixin.MixinGTMaterialItems] @Overwrite 接管，不再需要独立生成。
 */
object ETMaterialItems {
    @JvmField val MATERIAL_ITEMS: Table<TagPrefix, Material, ItemEntry<out Item>> = GTMaterialItems.MATERIAL_ITEMS
}
