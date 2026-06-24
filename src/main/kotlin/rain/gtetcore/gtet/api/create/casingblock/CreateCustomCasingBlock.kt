package rain.gtetcore.gtet.api.create.casingblock

import com.gregtechceu.gtceu.common.data.models.GTModels
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.tterrag.registrate.util.entry.BlockEntry
import com.tterrag.registrate.util.nullness.NonNullFunction
import com.tterrag.registrate.util.nullness.NonNullSupplier
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import rain.gtetcore.gtet.api.ETREGISTRATE
import java.util.function.Supplier


/**
 * 自定义外壳方块（可指定方块工厂和属性）。
 */
open class CreateCustomCasingBlock(
    name: String,
    blockSupplier: NonNullFunction<BlockBehaviour.Properties, Block>,
    texture: ResourceLocation,
    properties: NonNullSupplier<out Block>,
    type: Supplier<Supplier<RenderType>>
) {
    val entry: BlockEntry<Block> = ETREGISTRATE.etreg.block(name, blockSupplier)
        .initialProperties(properties)
        .properties { p -> p.isValidSpawn { _, _, _, _ -> false } }
        .addLayer(type)
        .exBlockstate(GTModels.cubeAllModel(texture))
        .tag(CustomTags.MINEABLE_WITH_CONFIG_VALID_PICKAXE_WRENCH)
        .item { block, _ -> BlockItem(block, net.minecraft.world.item.Item.Properties()) }
        .build()
        .register()
}