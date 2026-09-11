package rain.gtetcore.gtet.common.item.recipe

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity
import com.gregtechceu.gtceu.api.gui.GuiTextures
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget
import com.gregtechceu.gtceu.api.gui.widget.LongInputWidget
import com.gregtechceu.gtceu.api.gui.widget.PhantomSlotWidget
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.api.registry.GTRegistries
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory
import com.lowdragmc.lowdraglib.gui.modular.ModularUI
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup
import com.lowdragmc.lowdraglib.gui.texture.TextTexture
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup
import com.lowdragmc.lowdraglib.gui.widget.custom.PlayerInventoryWidget

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

import org.lwjgl.glfw.GLFW

import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.config.GTETConfig

import java.util.function.Consumer
import java.util.function.Supplier

/**
 * 配方编辑器 —— 手持本物品右键空气打开；对着工作站 / GT 控制器右键则直接切到对应配方类型。
 *
 * 界面是**三页**（顶部按钮切换，靠 [Widget.setVisible] 切可见性，不重开界面）：
 *  1. **配方**：字段（配方 id、时间、耗电、电压+快捷选择、幽灵电路）+ 幽灵槽（数量按配方类型
 *     的真实能力算，`GTRecipeType.getMaxInputs(ItemRecipeCapability.CAP)`）+ 玩家物品栏 + 导出按钮；
 *  2. **类型**：配方种类单独一页 —— 原版六种 + 从 [GTRegistries.RECIPE_TYPES] 枚举的全部 GT 类型，
 *     三列可拖动滚动；
 *  3. **代码**：整页可滚动的代码预览 + 导出 / 复制。
 *
 * 所有状态存手持物品 NBT（见 [RecipeDraft]），导出走 [RecipeCodeWriter]。
 *
 * @author rain fox
 */
object RecipeEditorBehavior : IItemUIFactory {

    private const val WIDTH = 396
    private const val HEIGHT = 268

    override fun createUI(holder: HeldItemUIFactory.HeldItemHolder, player: Player): ModularUI {
        val draft = RecipeDraft.load(holder.held)
        val preview = PreviewCache()
        val ui = ModularUI(WIDTH, HEIGHT, holder, player).background(GuiTextures.BACKGROUND)

        fun touch() {
            draft.save(holder.held)
            holder.markAsDirty()
            preview.invalidate()
        }

        // ── 各页容器（先建好，最后按页切换可见性）──
        val pageRecipe = WidgetGroup(0, 0, WIDTH, HEIGHT)
        val pageTypes = WidgetGroup(0, 0, WIDTH, HEIGHT)
        pageTypes.isVisible = false
        val pageCode = WidgetGroup(0, 0, WIDTH, HEIGHT)
        pageCode.isVisible = false
        val pages = listOf(pageRecipe, pageTypes, pageCode)

        fun showPage(index: Int) {
            pages.forEachIndexed { i, group -> group.isVisible = i == index }
        }

        // ── 顶部导航（放在页容器之外，切页时不动）──
        val navNames = listOf("① 配方", "② 类型", "③ 代码")
        navNames.forEachIndexed { index, name ->
            ui.widget(button(8 + index * 64, 4, 60, 14, { name }) { showPage(index) })
        }
        ui.widget(label(200, 7) { "§f◤ 配方编辑器§7  ${RecipeCodeWriter.describe(draft)}" })

        // ==================== 页 1：配方 ====================

        // 配方 id
        pageRecipe.addWidget(label(8, 26) { "§7配方 id" })
        pageRecipe.addWidget(
            TextFieldWidget(64, 24, 150, 14, { draft.recipeId }, { text ->
                draft.recipeId = text
                touch()
            }),
        )

        // 电压等级：点一下弹出 LV~MAX 的快捷选择
        val tierPicker = WidgetGroup(64, 42, 190, 120)
        tierPicker.setBackground(GuiTextures.DISPLAY)
        tierPicker.isVisible = false
        var tierIndex = 1 // GTValues.VN 里 0 是 ULV，快捷选择从 LV 开始
        var row = 0
        while (tierIndex < GTValues.VN.size) {
            for (col in 0 until 4) {
                if (tierIndex >= GTValues.VN.size) break
                val tier = tierIndex
                tierPicker.addWidget(
                    button(col * 46, row * 14, 44, 12, { GTValues.VN[tier] }) {
                        draft.tier = tier
                        if (tier < GTValues.VA.size) draft.eut = GTValues.VA[tier].toLong()
                        tierPicker.isVisible = false
                        touch()
                    },
                )
                tierIndex++
            }
            row++
        }

        pageRecipe.addWidget(
            button(218, 24, 60, 14, { "§b${RecipeCodeWriter.tierName(draft.tier)} ▾" }) {
                tierPicker.isVisible = !tierPicker.isVisible
            },
        )
        pageRecipe.addWidget(tierPicker)

        // 幽灵电路
        pageRecipe.addWidget(label(284, 26) { "§7幽灵电路" })
        pageRecipe.addWidget(PhantomSlotWidget(draft.circuitSlot, 0, 284, 42).setBackground(GuiTextures.SLOT))
        pageRecipe.addWidget(button(302, 42, 16, 14, { "◀" }) {
            draft.circuit = if (draft.circuit <= 0) -1 else draft.circuit - 1
            syncCircuit(draft)
            touch()
        })
        pageRecipe.addWidget(button(320, 42, 16, 14, { "▶" }) {
            draft.circuit = if (draft.circuit < 0) 0 else minOf(RecipeDraft.CIRCUIT_MAX, draft.circuit + 1)
            syncCircuit(draft)
            touch()
        })
        pageRecipe.addWidget(label(340, 46) { if (draft.circuit < 0) "§8off" else "§f${draft.circuit}" })

        // 时间 / 耗电
        pageRecipe.addWidget(label(8, 42) { "§7时间 (tick)" })
        pageRecipe.addWidget(
            IntInputWidget(8, 54, 56, 14, { draft.duration }, { value ->
                draft.duration = maxOf(0, value)
                touch()
            }),
        )
        pageRecipe.addWidget(label(72, 42) { "§7基础耗电 (EU/t)" })
        pageRecipe.addWidget(
            LongInputWidget(72, 54, 74, 14, { draft.eut }, { value ->
                draft.eut = maxOf(0L, value)
                touch()
            }),
        )

        // 幽灵槽：数量按配方类型真实能力算
        pageRecipe.addWidget(label(8, 74) { "§7输入 §f${draft.inputSlots()} §8(点槽放物品 / 右键清空)" })
        for (i in 0 until draft.inputSlots()) {
            pageRecipe.addWidget(
                PhantomSlotWidget(draft.inputs, i, 8 + (i % 8) * 18, 86 + (i / 8) * 18)
                    .setBackground(GuiTextures.SLOT) as Widget,
            )
        }
        val outY = 86 + ((draft.inputSlots() + 7) / 8) * 18 + 12
        pageRecipe.addWidget(label(8, outY - 12) { "§7输出 §f${draft.outputSlots()}" })
        for (i in 0 until draft.outputSlots()) {
            pageRecipe.addWidget(
                PhantomSlotWidget(draft.outputs, i, 8 + (i % 9) * 18, outY).setBackground(GuiTextures.SLOT) as Widget,
            )
        }

        // 玩家物品栏（方便往里拖东西）
        pageRecipe.addWidget(
            PlayerInventoryWidget().apply {
                setPlayer(player)
                setSelfPosition(190, outY - 26)
            },
        )

        // 按钮 + 迷你预览
        pageRecipe.addWidget(button(8, 220, 60, 18, { "刷新" }) { preview.invalidate() })
        pageRecipe.addWidget(button(72, 220, 76, 18, { "导出到目录" }) { export(holder, draft) })
        pageRecipe.addWidget(button(152, 220, 76, 18, { "复制代码" }) { copyToClipboard(holder, draft) })
        pageRecipe.addWidget(button(232, 220, 76, 18, { "看完整代码" }) { showPage(2) })
        for (line in 0 until 4) {
            val label = label(8, 242 + line * 8) {
                val lines = preview.lines(draft)
                if (line < lines.size) lines[line] else ""
            }
            label.setColor(0xA0FFA0)
            pageRecipe.addWidget(label)
        }

        // ==================== 页 2：配方种类（单独一页，三列滚动）====================

        pageTypes.addWidget(label(8, 26) { "§7配方种类（原版六种 + GT 全部已注册类型，含附属）" })
        val typeList = DraggableScrollableWidgetGroup(8, 40, 380, 218)
        val entries: MutableList<Pair<String, () -> Unit>> = mutableListOf()
        for (kind in RecipeDraft.Kind.entries) {
            entries += ("§f${kind.cn}" to {
                draft.kind = kind
                syncCircuit(draft)
                touch()
                showPage(0)
            })
        }
        for (entry in gtTypes()) {
            entries += ("§fGT: ${entry.id.path}" to {
                draft.kind = RecipeDraft.Kind.GT
                draft.gtType = entry.id.toString()
                if (draft.tier < 1) draft.tier = 2
                if (draft.tier < GTValues.VA.size) draft.eut = GTValues.VA[draft.tier].toLong()
                syncCircuit(draft)
                touch()
                showPage(0)
            })
        }
        entries.forEachIndexed { index, (name, action) ->
            val col = index % 3
            val rowIdx = index / 3
            typeList.addWidget(
                button(col * 127, rowIdx * 14, 124, 12, {
                    val current = currentTypeLabel(draft)
                    (if (name == current) "§a▶ " else "   ") + name
                }) { action() },
            )
        }
        pageTypes.addWidget(typeList)

        // ==================== 页 3：代码（整页可滚动）====================

        pageCode.addWidget(label(8, 26) { "§7代码预览（导出为 ${GTETConfig.recipeExportDirectory()}/<id>.kt）" })
        val codeList = DraggableScrollableWidgetGroup(8, 40, 380, 192)
        for (line in 0 until 80) {
            val label = label(0, line * 9) {
                val lines = preview.lines(draft)
                if (line < lines.size) lines[line] else ""
            }
            label.setColor(0xA0FFA0)
            codeList.addWidget(label)
        }
        pageCode.addWidget(codeList)
        pageCode.addWidget(button(8, 238, 60, 18, { "刷新" }) { preview.invalidate() })
        pageCode.addWidget(button(72, 238, 76, 18, { "导出到目录" }) { export(holder, draft) })
        pageCode.addWidget(button(152, 238, 76, 18, { "复制代码" }) { copyToClipboard(holder, draft) })

        // ── 装配 ──
        ui.widget(pageRecipe)
        ui.widget(pageTypes)
        ui.widget(pageCode)
        return ui
    }

    // ======================== 小工具 ========================

    private fun label(x: Int, y: Int, text: () -> String) = LabelWidget(x, y, Supplier(text))

    private fun button(x: Int, y: Int, w: Int, h: Int, text: () -> String, onClick: () -> Unit) = ButtonWidget(
        x, y, w, h,
        GuiTextureGroup(GuiTextures.BUTTON, TextTexture(Supplier(text))),
    ) { onClick() }

    /** 当前选中类型的显示名，用来在列表里打高亮标记。 */
    private fun currentTypeLabel(draft: RecipeDraft): String = when (draft.kind) {
        RecipeDraft.Kind.GT -> "§fGT: ${ResourceLocation.tryParse(draft.gtType)?.path ?: draft.gtType}"
        else -> "§f${draft.kind.cn}"
    }

    /** 全部已注册的 GT 配方类型（含各附属注册的），按 id 排序。 */
    private fun gtTypes(): List<GtTypeEntry> = GTRegistries.RECIPE_TYPES
        .mapNotNull { type -> GTRegistries.RECIPE_TYPES.getKey(type)?.let { GtTypeEntry(it, type) } }
        .sortedBy { it.id.toString() }

    private data class GtTypeEntry(val id: ResourceLocation, val type: GTRecipeType)

    /** 幽灵电路：把当前配置号同步到显示槽里的电路物品上。 */
    private fun syncCircuit(draft: RecipeDraft) {
        if (draft.circuit < 0) {
            draft.circuitSlot.setStackInSlot(0, ItemStack.EMPTY)
            return
        }
        val value = minOf(RecipeDraft.CIRCUIT_MAX, draft.circuit)
        draft.circuit = value
        val display = draft.circuitSlot.getStackInSlot(0)
        if (display.isEmpty || !IntCircuitBehaviour.isIntegratedCircuit(display)) {
            draft.circuitSlot.setStackInSlot(0, IntCircuitBehaviour.stack(value))
        } else {
            IntCircuitBehaviour.setCircuitConfiguration(display, value)
        }
    }

    /** 代码预览缓存：草稿变了才重新生成（标签每帧都会取一次）。 */
    private class PreviewCache {
        private var dirty = true
        private var cache: Array<String> = arrayOf("")

        fun invalidate() {
            dirty = true
        }

        fun lines(draft: RecipeDraft): Array<String> {
            if (dirty) {
                cache = RecipeCodeWriter.toCode(draft).split("\n").toTypedArray()
                dirty = false
            }
            return cache
        }
    }

    // ======================== 导出 / 复制 ========================

    private fun export(holder: HeldItemUIFactory.HeldItemHolder, draft: RecipeDraft) {
        val player = holder.player
        if (player.level().isClientSide) return // 写文件只在服务端
        runCatching { RecipeCodeWriter.export(draft) }
            .onSuccess { file ->
                player.displayClientMessage(Component.literal("§a[配方编辑器] 已导出：§f${file.name}"), false)
            }
            .onFailure { e ->
                Gtetcore.LOGGER.error("[gtetcore] 配方导出失败", e)
                player.displayClientMessage(Component.literal("§c[配方编辑器] 导出失败：${e.message}"), false)
            }
    }

    private fun copyToClipboard(holder: HeldItemUIFactory.HeldItemHolder, draft: RecipeDraft) {
        val player = holder.player
        if (!player.level().isClientSide) return // 剪贴板只在客户端
        GLFW.glfwSetClipboardString(Minecraft.getInstance().window.window, RecipeCodeWriter.toCode(draft))
        player.displayClientMessage(Component.literal("§a[配方编辑器] 代码已复制到剪贴板"), false)
    }

    // ======================== 物品行为 ========================

    /**
     * 对着工作站 / GT 机器控制器右键：自动切到对应配方类型再打开。
     * 认不出来就 PASS（右键空气仍按上次选的类型打开）。
     */
    override fun onItemUseFirst(stack: ItemStack, context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val pos = context.clickedPos
        val level = context.level

        val gtType = gtRecipeTypeAt(level, pos)
        val vanillaKind = if (gtType == null) vanillaKind(level.getBlockState(pos).block) else null
        if (gtType == null && vanillaKind == null) return InteractionResult.PASS

        val draft = RecipeDraft.load(stack)
        if (gtType != null) {
            draft.kind = RecipeDraft.Kind.GT
            draft.gtType = gtType.toString()
        } else if (vanillaKind != null) {
            draft.kind = vanillaKind
        }
        syncCircuit(draft)
        draft.save(stack)

        if (player is ServerPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(player, context.hand)
        }
        return InteractionResult.SUCCESS
    }

    /** 原版工作站方块 → 固定配方种类。 */
    private fun vanillaKind(block: Block): RecipeDraft.Kind? = when (block) {
        Blocks.CRAFTING_TABLE -> RecipeDraft.Kind.CRAFTING_SHAPED
        Blocks.FURNACE -> RecipeDraft.Kind.SMELTING
        Blocks.BLAST_FURNACE -> RecipeDraft.Kind.BLASTING
        Blocks.SMOKER -> RecipeDraft.Kind.SMOKING
        Blocks.SMITHING_TABLE -> RecipeDraft.Kind.SMITHING
        Blocks.STONECUTTER -> RecipeDraft.Kind.STONECUTTING
        else -> null
    }

    /** 方块所在位置的 GT 元机器暴露的第一个配方类型（不是 GT 机器就返回 null）。 */
    private fun gtRecipeTypeAt(level: Level, pos: BlockPos): GTRecipeType? {
        val blockEntity = level.getBlockEntity(pos) as? MetaMachineBlockEntity ?: return null
        val machine = blockEntity.metaMachine
        if (machine is IRecipeLogicMachine) {
            return machine.recipeTypes.firstOrNull()
        }
        return null
    }

    override fun use(item: Item, level: Level, player: Player, usedHand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(usedHand)
        if (player is ServerPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(player, usedHand)
        }
        return InteractionResultHolder(InteractionResult.SUCCESS, stack)
    }
}
