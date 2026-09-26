package rain.gtetcore.gtet.common.data.machine.multiblock.modular

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.gregtechceu.gtceu.api.gui.GuiTextures
import com.gregtechceu.gtceu.api.gui.widget.SlotWidget
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.pattern.BlockPattern
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo
import com.gregtechceu.gtceu.api.pattern.Predicates
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTItems
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.data.recipe.CustomTags
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.lowdragmc.lowdraglib.utils.BlockInfo
import net.minecraft.network.chat.Component
import rain.gtetcore.gtet.common.data.machine.multiblock.modular.ETModularTestMachine.Companion.MODULE_SLOT_AREA
import rain.gtetcore.gtet.common.machine.multiblock.ETStructureData
import rain.gtetcore.gtet.common.machine.multiblock.modular.ETModularMachine
import rain.gtetcore.gtet.common.machine.multiblock.modular.ETModuleTiers
import rain.gtetcore.gtet.util.lang.LangUtil
import java.util.function.Supplier

/**
 * 模块化多方块试验台 —— [ETModularMachine] 的活样本，同时也是「结构喂数据」的样本。
 *
 * - **模块物品决定等级**：金锭 = MK1、钛锭 = MK2、中子素锭 = MK3，换模块会换结构（3³ / 5³ / 7³）。
 *   判定不写在这里了，而是登记进全局规则表 [ETModuleTiers]（见 `companion object` 的 `init`）——
 *   那里把**三种判定方式各登记了一份**当示例：
 *   ① `item` 具体物品（LV 电动马达）、② `prefix` 材料 + 形态（金 / 钛 / 中子素锭）、
 *   ③ `tag` 标签（GT 的 `#gtceu:circuits/lv|hv|iv` 电路板）；
 * - **结构决定规模**：中间层机壳墙会被 [ETStructureData] 收集成「模块方块」，数量每 8 个把配方电压等级上限抬 1 档
 *   （3³ 有 8 个 → +1、5³ 有 16 个 → +2、7³ 有 24 个 → +3）；
 * - **面板右侧有模块槽**（[createUIWidget]），不然玩家没地方放模块。
 *
 * ⚠️ tagprefix 那一组是**有意收窄**过的：旧实现直接比 `ChemicalHelper.getMaterialStack(stack).material()`，
 * 同一种材料的**任意形态**（粉 / 粒 / 块…）都会命中；规则表的三种登记方式里没有「只看材料」这一种，
 * 所以现在只有**登记过的形态**算模块。要让别的形态也算，在 `init` 里继续 `prefix(...)` 登记即可。
 *
 * @author rain fox
 */
class ETModularTestMachine(holder: IMachineBlockEntity) : ETModularMachine(holder) {

    /** 结构里检出的模块方块数量（成型时读一次，结构失效清零）。 */
    var moduleBlocks: Int = 0
        private set

    private val patterns = HashMap<Int, BlockPattern>()

    /** 基准档位 + 模块方块数带来的加成（结构越大，允许的配方电压越高）。 */
    override fun maxRecipeTier(): Int {
        val base = when (moduleTier) {
            1 -> GTValues.LV
            2 -> GTValues.HV
            3 -> GTValues.IV
            else -> return -1
        }
        return (base + moduleBlocks / MODULE_BLOCKS_PER_TIER).coerceAtMost(GTValues.MAX)
    }

    override fun patternOfTier(tier: Int): BlockPattern =
        patterns.getOrPut(tier) { boxPattern(sizeOfTier(tier), definition) }

    override fun onStructureFormed() {
        super.onStructureFormed()
        // ⚠️ 结构数据只在成型时读得到（PatternMatchContext 每次检测前都会 reset）
        moduleBlocks = ETStructureData.moduleBlockCount(multiblockState)
    }

    override fun onStructureInvalid() {
        super.onStructureInvalid()
        moduleBlocks = 0
    }

    override fun addDisplayText(textList: MutableList<Component>) {
        super.addDisplayText(textList)
        textList += Component.translatable("gtetcore.machine.$ID.tier", moduleTier)
        textList += Component.translatable("gtetcore.machine.$ID.modules", moduleBlocks)
        val cap = maxRecipeTier()
        if (cap >= 0) {
            textList += Component.translatable(
                "gtetcore.machine.$ID.cap",
                GTValues.VN[cap.coerceIn(0, GTValues.VN.size - 1)]
            )
        }
    }

    /**
     * 标准多方块面板 + 右侧一个**模块槽**。
     *
     * GTM 的面板本体是 `WidgetGroup(0,0,190,125)`、里面那层可滚动面板已经占满，所以这里把分组加宽
     * [MODULE_SLOT_AREA] 像素，在右边空白处放槽（`super` 拿到的对象就是那个 `WidgetGroup`）。
     */
    override fun createUIWidget(): Widget {
        val group = super.createUIWidget() as WidgetGroup
        val slotX = group.size.width + 4
        group.setSize(group.size.width + MODULE_SLOT_AREA, group.size.height)
        group.addWidget(
            SlotWidget(moduleSlot, 0, slotX, 6)
                .setBackground(GuiTextures.SLOT)
                .setHoverTooltips(Component.translatable("gtetcore.machine.$ID.slot_tooltip")),
        )
        return group
    }

    companion object {

        /** 机器 id（注册与语言键共用）。 */
        const val ID: String = "modular_test_machine"

        /**
         * 模块 → 等级登记：**三种判定方式各来一份**，等于 [ETModuleTiers] 的活示例。
         *
         * 1. **物品** —— 具体物品直接当模块（参数用 `Supplier`，理由见下）；
         * 2. **tagprefix** —— 「材料 + 形态」；⚠️ 反查出来的是两个维度，形态必须一起登记，
         *    所以只登记锭的话，金粉 / 金粒这类同材料物品**不算**模块（旧实现按材料匹配时它们算，见类注释）；
         * 3. **标签** —— 交给整合包按标签喂（MC 标签自带层级，`#gtceu:circuits` 这种父标签也会一并命中）。
         *
         * ⚠️ 登记放在 `companion object` 的 `init` 里，Kotlin 会把它编进**外层类的 `<clinit>`**，
         * 而本类首次被加载就是机器注册期（[ETModularTestMultiblocks.register] 第一行的 `initLang()`）——
         * 那时 `GTItems` 还没进注册表（GTM 是 `GTMachines.init()` 在 `GTItems.init()` 之前），
         * 直接写 `GTItems.X.asItem()` 会抛 `Registry entry not present`。
         * 所以 ① 用 `Supplier` 包一层，把取物品推迟到机器运行时。
         */
        init {
            ETModuleTiers
                // ① 物品：一个具体物品直接当模块（Supplier 是必须的：登记期读不到物品注册表）
                .item(1, Supplier { GTItems.ELECTRIC_MOTOR_LV.asItem() })
                // ② tagprefix：材料 + 形态
                .prefix(1, TagPrefix.ingot, GTMaterials.Gold)
                .prefix(2, TagPrefix.ingot, GTMaterials.Titanium)
                .prefix(3, TagPrefix.ingot, GTMaterials.Neutronium)
                // ③ 标签：GT 的电路标签（LV / HV / IV 电路板）
                .tag(1, CustomTags.LV_CIRCUITS)
                .tag(2, CustomTags.HV_CIRCUITS)
                .tag(3, CustomTags.IV_CIRCUITS)
        }

        /** 每多少个「模块方块」把配方电压等级上限抬一档。 */
        private const val MODULE_BLOCKS_PER_TIER: Int = 8

        /** 模块槽那一列的占宽（像素）。 */
        private const val MODULE_SLOT_AREA: Int = 26

        private fun sizeOfTier(tier: Int): Int = when (tier) {
            3 -> 7
            2 -> 5
            else -> 3
        }

        /**
         * EMI / JEI 预览用的**三套结构**（MK1 3³ / MK2 5³ / MK3 7³）。
         *
         * GTM 的预览只认 `MultiblockMachineDefinition#shapes`（`List<MultiblockShapeInfo>`），
         * 而一个 definition 只存**一套运行时图案**（`patternFactory`）—— 所以注册完要额外把三套 shape 塞进
         * `shapes`，EMI/JEI 里才会出现 `P:` 翻页按钮、三页结构都能看。
         *
         * shape 不手写：直接用图案自己的 [`BlockPattern#getPreview`]（GTM 内部就是这么从 pattern 生成 shape 的）。
         *
         * ⚠️ **repetition 数组的长度必须等于图案的 aisle 条数**：`getPreview` 的外层循环是
         * `for (l = 0; l < fingerLength; l++)`，而 `fingerLength = predicatesIn.length`
         * （`BlockPattern.java:399` / `:69`）= `aisleRepetitions.length`（`FactoryBlockPattern#build` 第 135~151 行
         * 用 `depth.size()` 建数组，`aisle()` 每调一次就往 `depth` / `aisleRepetitions` 各加一条，见
         * `FactoryBlockPattern.java:89-90` 与 `:75-78`）。传空数组会在内层 `repetition[0]`
         * （`BlockPattern.java:400`）直接 AIOOBE —— 而这个供应商是在
         * `MultiblockMachineDefinition#getMatchingShapes()`（`:64-66`）里被调用的，异常会一路穿到
         * EMI 的 `PatternPreviewWidget`（`:172-179` 的 `CACHE.computeIfAbsent`），结果是整台机器在
         * 「多方块信息」里**完全不出现**，而不是少一页。
         *
         * `[0]` 取每条 aisle 的最小重复数（本机全是 `aisle(...)` 得来的 `{1,1}`，所以就是全 1）。
         */
        @JvmStatic
        fun previewShapes(definition: MultiblockMachineDefinition): List<MultiblockShapeInfo> =
            listOf(1, 2, 3).mapNotNull { tier ->
                // 逐页兜底：某一页生成失败就只丢这一页，别让整台机器从 EMI 里消失
                runCatching {
                    val pattern = boxPattern(sizeOfTier(tier), definition)
                    val repetition = IntArray(pattern.aisleRepetitions.size) { pattern.aisleRepetitions[it][0] }
                    MultiblockShapeInfo(pattern.getPreview(repetition))
                }.getOrElse { e ->
                    GTCEu.LOGGER.error("gtetcore: 模块化测试机 MK{} 的预览结构生成失败，该页被跳过", tier, e)
                    null
                }
            }

        /**
         * 程序生成一套 `size³` 的空心机壳盒（比手写三层 `aisle` 好维护，改尺寸只改一个数）。
         *
         * 字符表：`X` 机壳（+ 自动能力仓）、`M` 中间层的机壳墙（会被 [ETStructureData] 收集）、
         * `S` 控制器（中间层前墙正中）、空格 = 内部空气。
         *
         * ⚠️ 控制器放在**中间层**是为了不依赖 `aisle` 的上下顺序（对称层翻过来也一样）。
         */
        @JvmStatic
        fun boxPattern(size: Int, definition: MultiblockMachineDefinition): BlockPattern {
            val builder = FactoryBlockPattern.start()
            val mid = size / 2
            val casing = GTBlocks.CASING_STEEL_SOLID

            for (y in 0 until size) {
                val rows = Array(size) { z ->
                    var row = buildString {
                        for (x in 0 until size) {
                            val wall = x == 0 || x == size - 1 || z == 0 || z == size - 1 || y == 0 || y == size - 1
                            append(
                                when {
                                    !wall -> ' '
                                    y == mid -> 'M'
                                    else -> 'X'
                                },
                            )
                        }
                    }
                    if (y == mid && z == size - 1) {
                        row = row.substring(0, mid) + 'S' + row.substring(mid + 1)
                    }
                    row
                }
                builder.aisle(*rows)
            }

            return builder
                .where('S', Predicates.controller(Predicates.blocks(definition.block)))
                .where('X', Predicates.blocks(casing.get())
                        .setMinGlobalLimited(1)
                        .or(Predicates.autoAbilities(*definition.recipeTypes)),
                )
                .where('M', ETStructureData.collectingModuleBlocks(
                        { it.`is`(casing.get()) },
                        arrayOf(BlockInfo.fromBlockState(casing.get().defaultBlockState())),
                    ),
                )
                .where(' ', Predicates.air())
                .build()
        }

        /** 登记本机器自己的语言键（注册时调用，早于数据生成）。 */
        @JvmStatic
        fun initLang() {
            LangUtil.BLOCK_LANG[ID] = "模块化测试机"
            LangUtil.add(
                "gtetcore.machine.$ID.tooltip.0",
                "Put a module item in the module slot: Gold = MK1, Titanium = MK2, Neutronium = MK3.",
                "往模块槽里放模块物品：金 = MK1、钛 = MK2、中子素 = MK3。",
            )
            LangUtil.add(
                "gtetcore.machine.$ID.tooltip.1",
                "The module changes the structure (3³ / 5³ / 7³); every 8 module casings raise the recipe voltage cap by one tier.",
                "模块决定结构（3³ / 5³ / 7³）；每 8 个模块方块把配方电压等级上限抬一档。",
            )
            LangUtil.add("gtetcore.machine.$ID.tier", "Module tier: %s", "模块等级：%s")
            LangUtil.add("gtetcore.machine.$ID.modules", "Module casings: %s", "模块方块：%s")
            LangUtil.add("gtetcore.machine.$ID.cap", "Recipe voltage cap: %s", "配方电压上限：%s")
            LangUtil.add("gtetcore.machine.$ID.slot_tooltip", "Module slot", "模块槽")
        }
    }
}
