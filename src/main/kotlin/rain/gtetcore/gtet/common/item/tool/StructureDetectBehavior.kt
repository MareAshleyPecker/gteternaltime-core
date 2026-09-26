package rain.gtetcore.gtet.common.item.tool

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.gregtechceu.gtceu.api.item.component.IInteractionItem
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraftforge.registries.ForgeRegistries
import rain.gtetcore.gtet.common.item.tool.StructureDetectBehavior.NO_TIME
import rain.gtetcore.gtet.common.item.tool.StructureDetectBehavior.TIME_KEY
import rain.gtetcore.gtet.util.lang.LangUtil

/**
 * 结构检测工具的行为 —— 右键多方块控制器，当场跑一遍结构检测：
 * 成型成功在聊天栏提示，失败则把错误位置的坐标写进物品 NBT，
 * 由 [rain.gtetcore.gtet.client.StructureOverlayRenderer] 在客户端画成线框。
 *
 * 错误框不会一直挂着：写入坐标时同时记下当时的游戏刻（[TIME_KEY]），
 * 渲染端按配置 `overlay.detectBoxLifetime`（秒；0 = 不自动消失）判断是否过期，
 * 过期就不再画 —— 想看就再右键一次控制器。
 */
object StructureDetectBehavior : IInteractionItem {

    private const val KEY = "error_pos"

    /** 记录"这批错误位置是什么时候检测出来的"（游戏刻），供渲染端判超时。 */
    private const val TIME_KEY = "time"

    private const val L_NO_PATTERN = "gtetcore.structure_detect.no_pattern"

    /**
     * 读不到时间戳时的返回值（老物品、或手写 NBT 的情况）。
     *
     * 渲染端把它当成"早就过期"，于是老物品上残留的错误框不会再画出来。
     */
    const val NO_TIME: Long = -1L

    init {
        LangUtil.add(L_NO_PATTERN, "No structure defined", "无结构定义")
    }

    @JvmStatic
    fun getPos(stack: ItemStack): Array<BlockPos>? {
        // 只读：渲染端每帧都会调这里，不能用 getOrCreate* 去写物品 NBT
        val tag = stack.getTagElement(KEY) ?: return null
        if (!tag.contains("pos", Tag.TAG_LIST.toInt())) return null
        return tag.getList("pos", Tag.TAG_COMPOUND.toInt()).map {
            (it as CompoundTag).let { compoundTag ->
                BlockPos(compoundTag.getInt("x"), compoundTag.getInt("y"), compoundTag.getInt("z"))
            }
        }.toTypedArray()
    }

    /**
     * 这批错误位置写进 NBT 时的游戏刻（服务端 `Level#getGameTime`）。
     *
     * 客户端拿自己的游戏刻相减判断"还在不在停留时间内" —— 两边的游戏刻同步推进
     * （暂停时都不走），所以"停留 N 秒"和玩家直觉一致；没有记录时返回 [NO_TIME]。
     */
    @JvmStatic
    fun getTime(stack: ItemStack): Long {
        val tag = stack.getTagElement(KEY) ?: return NO_TIME
        return if (tag.contains(TIME_KEY, Tag.TAG_LONG.toInt())) tag.getLong(TIME_KEY) else NO_TIME
    }

    @JvmStatic
    fun isItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val item = stack.item
        // 三层回退：引用 → 组件 → 注册名
        if (item is ComponentItem && item.components.contains(this)) return true
        // ForgeRegistries 是 BuiltInRegistries 那批已弃用字段的替代品；道具没注册时取不到 key
        val key = ForgeRegistries.ITEMS.getKey(item) ?: return false
        return "gtetcore" == key.namespace && "structure_detect" == key.path
    }

    /** 记下一个错误位置，并刷新"检测时刻"（渲染端按它算停留时间）。 */
    private fun addPos(stack: ItemStack, pos: BlockPos, gameTime: Long) {
        val tag = stack.getOrCreateTagElement(KEY)
        if (tag.contains("pos", Tag.TAG_LIST.toInt())) {
            tag.getList("pos", Tag.TAG_COMPOUND.toInt()).add(posTag(pos))
        } else {
            val list = ListTag()
            list.add(posTag(pos))
            tag.put("pos", list)
        }
        tag.putLong(TIME_KEY, gameTime)
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
                addPos(stack, err.pos, level.gameTime)
                player.inventoryMenu.broadcastChanges()  // 强制同步 NBT 到客户端
                player.sendSystemMessage(err.errorInfo)
            }
        }

        return InteractionResult.SUCCESS
    }
}
