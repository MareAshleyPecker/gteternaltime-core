package rain.gtetcore.gtet.common.data.machine

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.common.machine.multiblock.ETModularTestMachine
import java.util.function.Supplier

/**
 * 「模块化测试机」的注册。
 *
 * ⚠️ GTM 一个 `MultiblockMachineDefinition` 只存**一套运行时图案**，运行时换结构靠机器自己重写
 * `getPattern()`（见 [rain.gtetcore.gtet.common.machine.multiblock.modular.ETModularMachine]）。
 * 所以这里 `pattern { ... }` 给的是最小的 MK1 3³（运行时兜底 / GTM 自动 DFS 兜底都用它），
 * 而 EMI/JEI 预览用的三套结构是在 `register()` 之后另外塞进 `definition.shapes` 的。
 *
 * @author rain fox
 */
object ETModularTestMultiblocks {

    /** 机器 id。 */
    const val ID: String = ETModularTestMachine.ID

    @JvmStatic
    fun register(registrate: GTRegistrate): MultiblockMachineDefinition {
        // 名字 / tooltip / 面板文案都在机器类那边登记（同一处改动，避免两处漂移）
        ETModularTestMachine.initLang()

        val definition = registrate
            .multiblock(ID) { holder -> ETModularTestMachine(holder) }
            .langValue("Modular Test Bench")
            .tier(GTValues.IV)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.MACERATOR_RECIPES)
            .recipeModifiers(GTRecipeModifiers.OC_NON_PERFECT_SUBTICK, GTRecipeModifiers.BATCH_MODE)
            .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
            // 运行时图案：definition 只存一套（这里给最小的 MK1）；实际用哪套由机器按模块等级重写 getPattern()
            .pattern { definition -> ETModularTestMachine.boxPattern(3, definition) }
            .tooltips(
                Component.translatable("gtceu.multiblock.parallelizable.tooltip"),
                Component.translatable(
                    "gtceu.machine.available_recipe_map_1.tooltip",
                    Component.translatable("gtceu.macerator"),
                ),
                Component.translatable("gtetcore.machine.$ID.tooltip.0"),
                Component.translatable("gtetcore.machine.$ID.tooltip.1"),
            )
            .workableCasingModel(
                GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                GTCEu.id("block/multiblock/gcym/large_maceration_tower"),
            )
            .register()

        // EMI / JEI 预览：三套结构都挂上去，预览里就会出现 P: 翻页（MK1 / MK2 / MK3）。
        // ⚠️ 必须在 register() 之后设：`shapes` 是 definition 上的 setter，builder 阶段还没有这个对象。
        definition.shapes = Supplier { ETModularTestMachine.previewShapes(definition) }
        return definition
    }
}
