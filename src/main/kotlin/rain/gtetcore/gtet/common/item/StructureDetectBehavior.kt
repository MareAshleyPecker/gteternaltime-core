package rain.gtetcore.gtet.common.item

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.gregtechceu.gtceu.api.item.component.IInteractionItem
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import rain.gtetcore.gtet.util.lang.LangUtil

object StructureDetectBehavior : IInteractionItem {

    private const val KEY = "error_pos"
    private const val L_NO_PATTERN = "gtetcore.structure_detect.no_pattern"

    init {
        LangUtil.add(L_NO_PATTERN, "No structure defined", "无结构定义")
    }

    @JvmStatic
    fun getPos(stack: ItemStack): Array<BlockPos>? {
        val tag = stack.getOrCreateTagElement(KEY)
        if (!tag.contains("pos", Tag.TAG_LIST.toInt())) return null
        return tag.getList("pos", Tag.TAG_COMPOUND.toInt()).map { (it as CompoundTag).let { compoundTag ->
            BlockPos(compoundTag.getInt("x"), compoundTag.getInt("y"), compoundTag.getInt("z"))
        } }.toTypedArray()
    }

    @JvmStatic
    fun isItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val item = stack.item
        // 三层回退：引用 → 组件 → 注册名
        if (item is ComponentItem && item.components.contains(this)) return true
        @Suppress("DEPRECATION") val key = BuiltInRegistries.ITEM.getKey(item)
        return !(key == null || "gtetcore" != key.namespace || "structure_detect" != key.path)
    }

    private fun addPos(stack: ItemStack, pos: BlockPos) {
        val tag = stack.getOrCreateTagElement(KEY)
        if (tag.contains("pos", Tag.TAG_LIST.toInt())) {
            tag.getList("pos", Tag.TAG_COMPOUND.toInt()).add(posTag(pos))
        } else {
            val list = ListTag()
            list.add(posTag(pos))
            tag.put("pos", list)
        }
    }

    private fun posTag(pos: BlockPos) = CompoundTag().also {
        it.putInt("x", pos.x); it.putInt("y", pos.y); it.putInt("z", pos.z)
    }

    override fun onItemUseFirst(stack: ItemStack, context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        if (level.isClientSide) return InteractionResult.PASS

        val machine = MetaMachine.getMachine(level, context.clickedPos)
        if (machine !is IMultiController) return InteractionResult.PASS

        stack.removeTagKey(KEY)

        val pattern = machine.pattern ?: run {
            player.sendSystemMessage(Component.translatable(L_NO_PATTERN).withStyle(ChatFormatting.RED))
            return InteractionResult.FAIL
        }

        val state = machine.multiblockState
        state.clean()
        val formed = pattern.checkPatternAt(state, true)

        if (formed) {
            player.sendSystemMessage(
                Component.translatable("gtceu.top.valid_structure").withStyle(ChatFormatting.GREEN)
            )
        } else {
            val err = state.error
            if (err != null) {
                addPos(stack, err.pos)
                player.inventoryMenu.broadcastChanges()  // 强制同步 NBT 到客户端
                player.sendSystemMessage(err.errorInfo)
            }
        }

        return InteractionResult.SUCCESS
    }
}
