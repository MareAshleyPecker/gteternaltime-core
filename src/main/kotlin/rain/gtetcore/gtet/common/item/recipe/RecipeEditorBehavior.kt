@file:Suppress("UNCHECKED_CAST", "DEPRECATION", "unused")

package rain.gtetcore.gtet.common.item.recipe

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
import com.lowdragmc.lowdraglib.gui.widget.*
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
import net.minecraftforge.fluids.FluidStack
import org.lwjgl.glfw.GLFW
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.common.item.recipe.RecipeEditorBehavior.fluidSetter
import rain.gtetcore.gtet.config.GTETConfig
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * 配方编辑器 —— 手持物品右键空气打开；对着工作站 / GT 控制器右键则直接切到对应配方类型。
 *
 * 界面三页（顶部按钮切换，靠 [Widget.setVisible] 切可见性，不重开界面）：
 * ① **配方**：字段（id / 时间 / 耗电 / 电压 / 幽灵电路）+ 幽灵槽（物品与流体的输入输出四段，
 *    每段几个槽按当前配方类型的真实能力算）+ 玩家物品栏 + 导出按钮；
 * ② **类型**：原版六种 + [GTRegistries.RECIPE_TYPES] 里全部 GT 类型，三列滚动；
 * ③ **代码**：整页代码预览 + 导出 / 复制。
 *
 * 状态存手持物品 NBT（见 [RecipeDraft]），导出走 [RecipeCodeWriter]。
 *
 * @author rain fox
 */
object RecipeEditorBehavior : IItemUIFactory {

    private const val WIDTH = 462
    private const val HEIGHT = 300

    // ── 幽灵槽区布局（页 1）──
    // 起始 y 见 SLOT_TOP；下面要给按钮行留位置，每段的具体坐标由 relayout() 摆。

    /** 槽区左边距。 */
    private const val SLOT_LEFT = 8

    /** 槽区顶边（第一段标题的 y）。 */
    private const val SLOT_TOP = 74

    /** 每段标题占的高度。 */
    private const val LABEL_H = 12

    /** 物品槽：一行 9 个、行距 18（槽本身 18×18，正好无缝）。 */
    private const val ITEM_COLS = 9
    private const val ITEM_PITCH = 18

    /** 流体槽：一行 8 个、行距 20（槽 18×18 留 2px 缝，免得相邻两罐的液面贴在一起看不出分界）。 */
    private const val FLUID_COLS = 8
    private const val FLUID_PITCH = 20
    private const val FLUID_SLOT_SIZE = 18

    /** 电压快捷弹层：一行 4 个档（横 4 × 46 = 184 ≤ 190），行距 14（见 createUI 里的排版注释）。 */
    private const val TIER_COLS = 4
    private const val TIER_PICKER_W = 190
    private const val TIER_PICKER_H = 120

    /** 玩家物品栏（`PlayerInventoryWidget` 固有尺寸 172×86）的落点，避开槽区与按钮行。 */
    private const val PLAYER_INV_X = 286
    private const val PLAYER_INV_Y = 120

    /**
     * 代码预览的文字颜色：**不透明黑**（代码区坐在 GT 浅色面板底纹上，黑字对比度够；原淡绿看长代码伤眼）。
     *
     * ⚠️ LDLib 的 `setColor(int)` 收的是 ARGB，所以「黑」要写 `0xFF000000`（`0x00000000` 是全透明、字看不见）；
     * 它超过 `Int.MAX_VALUE`，字面量得走 `.toInt()` 才能进 `const`。
     */
    private const val CODE_TEXT_COLOR: Int = 0xFF000000.toInt()

    /** 建界面。槽区各段由 `relayout()` 顺次往下排，按钮行压在下方 —— 槽位上限见 [RecipeDraft.MAX_INPUTS]。 */
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
        ui.widget(label(200, 7) { "§f配方编辑器§7  ${RecipeCodeWriter.describe(draft)}" })

        // ==================== 页 1：配方 ====================

        // 配方 id
        pageRecipe.addWidget(label(8, 26) { "§7配方 id" })
        pageRecipe.addWidget(
            TextFieldWidget(64, 24, 150, 14, { draft.recipeId }, { text ->
                draft.recipeId = text
                touch()
            }),
        )

        // 电压等级：点一下弹出 LV~MAX 的快捷选择，后 4 行是 GTET 的特殊档 MAX+1~MAX+16（见 VoltageTiers）。
        // GTM 档与特殊档**各自**按 4 个一行分块（`chunked` 天然断行），所以特殊档一定从新的一行开头，
        // 不会和 OpV / MAX 挤在同一行里。总行数 = 4（LV..MAX 共 14 个）+ 4（特殊档 16 个）= 8，
        // 8 × 14 = 112 ≤ 弹层高度 120，不用加滚动。
        val tierPicker = WidgetGroup(64, 42, TIER_PICKER_W, TIER_PICKER_H)
        tierPicker.setBackground(GuiTextures.DISPLAY)
        tierPicker.isVisible = false
        val tierRows = ((1 until VoltageTiers.GTM_TIERS).toList() + VoltageTiers.SPECIAL_RANGE.toList())
            .chunked(TIER_COLS)
        tierRows.forEachIndexed { row, tiersInRow ->
            tiersInRow.forEachIndexed { col, tier ->
                tierPicker.addWidget(
                    // 档名直接是 `ULV`…`MAX`（GTM 的 VN）与 `MAX+1`…`MAX+16`（VoltageTiers 拼的），
                    // 不走翻译键 —— 和 GTM 自己 VNF 里那 16 个 "MAX+n" 是同一套写法。
                    button(col * 46, row * 14, 44, 12, { VoltageTiers.name(tier) }) {
                        draft.tier = tier
                        // 选档就把耗电摆到该档的默认值：GTM 档是 VA（= V × 30/32，原行为不变），
                        // 特殊档是那档电压本身（VA 装不下 2^33 那种量级）。
                        draft.eut = VoltageTiers.defaultEut(tier)
                        tierPicker.isVisible = false
                        touch()
                    },
                )
            }
        }

        pageRecipe.addWidget(
            button(218, 24, 60, 14, { "§b${RecipeCodeWriter.tierName(draft.tier)} ▾" }) {
                tierPicker.isVisible = !tierPicker.isVisible
            },
        )

        // 幽灵电路
        pageRecipe.addWidget(label(340, 32) { "§7幽灵电路" })
        pageRecipe.addWidget(PhantomSlotWidget(draft.circuitSlot, 0, 340, 42).setBackground(GuiTextures.SLOT))
        pageRecipe.addWidget(button(362, 42, 16, 16, { "◀" }) {
            draft.circuit = if (draft.circuit <= 0) -1 else draft.circuit - 1
            syncCircuit(draft)
            touch()
        })
        pageRecipe.addWidget(button(380, 42, 16, 16, { "▶" }) {
            draft.circuit = if (draft.circuit < 0) 0 else minOf(RecipeDraft.CIRCUIT_MAX, draft.circuit + 1)
            syncCircuit(draft)
            touch()
        })
        pageRecipe.addWidget(label(400, 46) { if (draft.circuit < 0) "§8off" else "§f${draft.circuit}" })

        // 时间 / 耗电
        pageRecipe.addWidget(label(8, 42) { "§7时间 (tick)" })
        pageRecipe.addWidget(
            IntInputWidget(8, 54, 108, 14, { draft.duration }, { value ->
                draft.duration = maxOf(0, value)
                touch()
            }),
        )
        pageRecipe.addWidget(label(116, 42) { "§7基础耗电 (EU/t)" })
        pageRecipe.addWidget(
            LongInputWidget(116, 54, 108, 14, { draft.eut }, { value ->
                draft.eut = maxOf(0L, value)
                touch()
            }),
        )

        // 流体量：拖进来的流体自带的量通常是一桶 1000，但 GT 配方里 144 / 576 / 2000 都常见，
        // 所以给个字段，往槽里放流体时按它覆盖（已放好的槽不受影响：要单独改某一个槽的量就中键点它）。
        pageRecipe.addWidget(label(232, 42) { "§7流体量 (mB)" })
        pageRecipe.addWidget(
            IntInputWidget(232, 54, 108, 14, { draft.fluidAmount }, { value ->
                draft.fluidAmount = maxOf(1, value)
                touch()
            }),
        )

        // ── 幽灵槽 ──
        // 物品 / 流体槽用 GTET 自己的控件（[PhantomCountSlotWidget] / [FluidCountSlotWidget]，只多一路中键改数量），
        // 幽灵电路槽仍用 GTM 原控件。「用几个槽」是配方类型的属性，所以这里一次性把每段都建满，
        // 之后只改可见性与位置（见 relayout），不增删控件。
        //
        // ⚠️ 不能改成按数量动态增删控件：LDLib 的控件同步按「父组里 widgets 的下标」路由，
        // 数量或顺序一变，后面所有槽的内容就会串位；而且界面两端各建一份（客户端 `initClientUI`
        // 会自己再跑一遍 `createUI`），只有控件表恒定两边才对得上。
        val inputWidgets: Array<Widget> = Array(RecipeDraft.MAX_INPUTS) { i ->
            PhantomCountSlotWidget(draft.inputs, i, 0, 0, onCountChanged = { touch() })
                .setBackground(GuiTextures.SLOT) as Widget
        }
        val outputWidgets: Array<Widget> = Array(RecipeDraft.MAX_OUTPUTS) { i ->
            PhantomCountSlotWidget(draft.outputs, i, 0, 0, onCountChanged = { touch() })
                .setBackground(GuiTextures.SLOT) as Widget
        }
        val fluidInputWidgets: Array<Widget> = Array(RecipeDraft.MAX_FLUID_INPUTS) { i ->
            FluidCountSlotWidget(
                draft.fluidInputs, i, 0, 0, FLUID_SLOT_SIZE, FLUID_SLOT_SIZE,
                fluidGetter(draft.fluidInputs, i),
                fluidSetter(draft.fluidInputs, i, { draft.fluidAmount }) { touch() },
                amountSetter = fluidAmountSetter(draft.fluidInputs, i),
                onAmountChanged = { touch() },
            ).setBackground(GuiTextures.FLUID_SLOT)
        }
        val fluidOutputWidgets: Array<Widget> = Array(RecipeDraft.MAX_FLUID_OUTPUTS) { i ->
            FluidCountSlotWidget(
                draft.fluidOutputs, i, 0, 0, FLUID_SLOT_SIZE, FLUID_SLOT_SIZE,
                fluidGetter(draft.fluidOutputs, i),
                fluidSetter(draft.fluidOutputs, i, { draft.fluidAmount }) { touch() },
                amountSetter = fluidAmountSetter(draft.fluidOutputs, i),
                onAmountChanged = { touch() },
            ).setBackground(GuiTextures.FLUID_SLOT)
        }

        // 四段标题：文本走 Supplier（每帧重新取，切类型 / 改数量时自己就刷新了），位置由 relayout 摆。
        // 物品与流体都支持「中键改数量」；流体那个单位是 mB，拖入时的量另由上面的「流体量 (mB)」字段给。
        val inputLabel = label(SLOT_LEFT, SLOT_TOP) {
            "§7物品输入 §f${draft.inputSlots()} §8(点槽放物品 / 右键清空 / 中键改数量)"
        }
        val fluidInputLabel = label(SLOT_LEFT, SLOT_TOP) {
            "§7流体输入 §f${draft.fluidInputSlots()} §8(拖入流体 / 点槽用容器装填 / 右键清空 / 中键改量)"
        }
        val outputLabel = label(SLOT_LEFT, SLOT_TOP) {
            "§7物品输出 §f${draft.outputSlots()} §8(右键清空 / 中键改数量)"
        }
        val fluidOutputLabel = label(SLOT_LEFT, SLOT_TOP) {
            "§7流体输出 §f${draft.fluidOutputSlots()} §8(右键清空 / 中键改量)"
        }
        for (widget in listOf(inputLabel, fluidInputLabel, outputLabel, fluidOutputLabel)) {
            pageRecipe.addWidget(widget)
        }
        for (widget in inputWidgets + fluidInputWidgets + outputWidgets + fluidOutputWidgets) {
            pageRecipe.addWidget(widget)
        }

        /**
         * 按「当前配方类型实际用几个槽」重排幽灵槽区：只改可见性与位置。
         *
         * ② 类型 页的按钮在**客户端与服务端都会执行**回调（`ButtonWidget` 客户端点了直接调，
         * 服务端收到 client action 后再调一次），所以两边重排的结果一致，控件表也一致。
         */
        fun relayout() {
            val itemIn = draft.inputSlots()
            val itemOut = draft.outputSlots()
            val fluidIn = draft.fluidInputSlots()
            val fluidOut = draft.fluidOutputSlots()

            // 有序合成按 3×3 摆：导出代码时 `shaped()` 正是按 row * 3 + col 读槽位的，
            // 摆成一排的话用户没法对着生成的图案放东西。其它种类统一 9 个一行。
            val itemCols = if (draft.kind == RecipeDraft.Kind.CRAFTING_SHAPED) 3 else ITEM_COLS

            var y = SLOT_TOP

            inputLabel.isVisible = itemIn > 0
            inputLabel.setSelfPosition(SLOT_LEFT, y)
            if (itemIn > 0) y += LABEL_H
            inputWidgets.forEachIndexed { i, widget ->
                widget.isVisible = i < itemIn
                if (i < itemIn) {
                    widget.setSelfPosition(SLOT_LEFT + (i % itemCols) * ITEM_PITCH, y + (i / itemCols) * ITEM_PITCH)
                }
            }
            y += slotRows(itemIn, itemCols) * ITEM_PITCH

            fluidInputLabel.isVisible = fluidIn > 0
            fluidInputLabel.setSelfPosition(SLOT_LEFT, y)
            if (fluidIn > 0) y += LABEL_H
            fluidInputWidgets.forEachIndexed { i, widget ->
                widget.isVisible = i < fluidIn
                if (i < fluidIn) {
                    widget.setSelfPosition(
                        SLOT_LEFT + (i % FLUID_COLS) * FLUID_PITCH,
                        y + (i / FLUID_COLS) * FLUID_PITCH
                    )
                }
            }
            y += slotRows(fluidIn, FLUID_COLS) * FLUID_PITCH

            outputLabel.isVisible = itemOut > 0
            outputLabel.setSelfPosition(SLOT_LEFT, y)
            if (itemOut > 0) y += LABEL_H
            outputWidgets.forEachIndexed { i, widget ->
                widget.isVisible = i < itemOut
                if (i < itemOut) {
                    widget.setSelfPosition(SLOT_LEFT + (i % ITEM_COLS) * ITEM_PITCH, y + (i / ITEM_COLS) * ITEM_PITCH)
                }
            }
            y += slotRows(itemOut, ITEM_COLS) * ITEM_PITCH

            fluidOutputLabel.isVisible = fluidOut > 0
            fluidOutputLabel.setSelfPosition(SLOT_LEFT, y)
            if (fluidOut > 0) y += LABEL_H
            fluidOutputWidgets.forEachIndexed { i, widget ->
                widget.isVisible = i < fluidOut
                if (i < fluidOut) {
                    widget.setSelfPosition(
                        SLOT_LEFT + (i % FLUID_COLS) * FLUID_PITCH,
                        y + (i / FLUID_COLS) * FLUID_PITCH,
                    )
                }
            }
        }
        relayout()

        pageRecipe.addWidget(
            PlayerInventoryWidget().apply {
                setPlayer(player)
                setSelfPosition(PLAYER_INV_X, PLAYER_INV_Y)
            },
        )

        // 按钮 + 迷你预览
        pageRecipe.addWidget(button(8, 270, 60, 18, { "刷新" }) {
            // 刷新也顺手重排一次：万一草稿被别处改过（例如对着别的机器右键换了类型再打开、
            // 或者手改过物品 NBT），点一下就能让槽区跟上当前类型。
            relayout()
            preview.invalidate()
        })
        pageRecipe.addWidget(button(72, 270, 76, 18, { "导出到目录" }) { export(holder, draft) })
        pageRecipe.addWidget(button(152, 270, 76, 18, { "复制代码" }) { copyToClipboard(holder, draft) })
        pageRecipe.addWidget(button(232, 270, 76, 18, { "看完整代码" }) { showPage(2) })
//        for (line in 0 until 4) {
//            val label = label(8, 242 + line * 8) {
//                val lines = preview.lines(draft)
//                if (line < lines.size) lines[line] else ""
//            }
//            label.setColor(CODE_TEXT_COLOR)
//            pageRecipe.addWidget(label)
//        }

        // 电压快捷选择弹层放在页 1 的**最后**才加入：
        // LDLib 的绘制是按下标顺序画的（后加的在上层），鼠标事件则反过来从最后一个开始找
        // （WidgetGroup.drawWidgetsBackground / mouseClicked），所以"最后加入"= 画在最上面 + 点击优先。
        // 它的矩形（y 42~162）会和槽区前两行重叠，压在槽位下面的话既看不清、按钮也点不到。
        pageRecipe.addWidget(tierPicker)

        // ==================== 页 2：配方种类（单独一页，三列滚动）====================

        pageTypes.addWidget(label(8, 26) { "§7配方种类（原版六种 + GT 全部已注册类型，含附属）" })
        val typeList = DraggableScrollableWidgetGroup(8, 40, 452, 218)
        val entries: MutableList<Pair<String, () -> Unit>> = mutableListOf()
        for (kind in RecipeDraft.Kind.entries) {
            entries += ("§f${kind.cn}" to {
                draft.kind = kind
                syncCircuit(draft)
                // 换种类必须重排幽灵槽区：槽位数量跟着类型走，不重排的话界面还停在
                // 打开界面那一刻的数量上（这正是"一直只显示 3 个"的根因）。
                relayout()
                touch()
                showPage(0)
            })
        }
        for (entry in gtTypes()) {
            entries += ("§fGT: ${entry.id.path}" to {
                draft.kind = RecipeDraft.Kind.GT
                draft.gtType = entry.id.toString()
                if (draft.tier < 1) draft.tier = 2
                draft.eut = VoltageTiers.defaultEut(draft.tier)
                syncCircuit(draft)
                relayout()
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
        val codeList = DraggableScrollableWidgetGroup(8, 40, 452, 192)
        for (line in 0 until 80) {
            val label = label(0, line * 9) {
                val lines = preview.lines(draft)
                if (line < lines.size) lines[line] else ""
            }
            label.setColor(CODE_TEXT_COLOR)
            codeList.addWidget(label)
        }
        pageCode.addWidget(codeList)
        pageCode.addWidget(button(8, 270, 60, 18, { "刷新" }) { preview.invalidate() })
        pageCode.addWidget(button(72, 270, 76, 18, { "导出到目录" }) { export(holder, draft) })
        pageCode.addWidget(button(152, 270, 76, 18, { "复制代码" }) { copyToClipboard(holder, draft) })

        // ── 装配 ──
        ui.widget(pageRecipe)
        ui.widget(pageTypes)
        ui.widget(pageCode)
        return ui
    }

    // ======================== 小工具 ========================

    private fun label(x: Int, y: Int, text: () -> String) = LabelWidget(x, y, Supplier(text))

    /** 一列 [cols] 个槽要占几行（0 个占 0 行；用于把槽区各段顺次往下排）。 */
    private fun slotRows(count: Int, cols: Int): Int = if (count <= 0) 0 else (count + cols - 1) / cols

    /** 幽灵流体槽的「取当前值」：必须实时读草稿（控件没有服务端同步数据时就是拿它画液面）。 */
    private fun fluidGetter(tanks: DraftFluidTanks, index: Int): Supplier<FluidStack> =
        Supplier { tanks.getFluid(index) }

    /**
     * 幽灵流体槽的「设值」：拖入 / 点击后由控件调用，改完立刻 [touch] 让 NBT 与代码预览跟上。
     *
     * ⚠️ 这些控件**不能**设成 `setClientSideWidget()`：那样 `writeClientAction` 直接 return，
     * 拖流体 / 点容器都到不了服务端，草稿里存不下来。
     */
    private fun fluidSetter(
        tanks: DraftFluidTanks,
        index: Int,
        amount: () -> Int,
        onChanged: () -> Unit,
    ): Consumer<FluidStack> = Consumer { stack ->
        tanks.setFluid(index, stack, amount())
        onChanged()
    }

    /**
     * 幽灵流体槽的「**只改量**」写回 —— 中键输入框确认时用（见 `FluidCountSlotWidget#applyAmount`）。
     *
     * ⚠️ 不能复用 [fluidSetter]：那个是给"点击 / 拖入装填"用的，会按「流体量 (mB)」字段的值
     * **覆盖**掉刚写进去的量，中键填的数字当场被打回原样 —— 这就是"中键改不了流体量"的根因。
     * 这里走 [DraftFluidTanks.setFluid] 的 `amount` 形参按指定量写同一个槽
     * （`setFluid` 内部 `copy()`，不会把草稿里那份共享实例改脏）。
     */
    private fun fluidAmountSetter(tanks: DraftFluidTanks, index: Int): (Int) -> Unit =
        { value -> tanks.setFluid(index, tanks.getFluid(index), value) }

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

    override fun use(
        item: Item,
        level: Level,
        player: Player,
        usedHand: InteractionHand
    ): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(usedHand)
        if (player is ServerPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(player, usedHand)
        }
        return InteractionResultHolder(InteractionResult.SUCCESS, stack)
    }
}
