package rain.gtetcore.gtet.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.item.ItemStack
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/** 客户端调试命令。`/get nbt` 获取手持物品 NBT 并复制到剪贴板。 */
@OnlyIn(Dist.CLIENT)
object GTETClientCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("get")
                .then(nbt())
        )
    }

    private fun nbt(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("nbt")
            .executes { ctx ->
                val mc = Minecraft.getInstance()
                val player = mc.player ?: return@executes 0
                val stack = player.mainHandItem
                if (stack.isEmpty) {
                    ctx.source.sendFailure(Component.literal("Nothing in main hand"))
                    return@executes 0
                }
                val id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.item)?.toString() ?: "unknown"
                val nbtStr = formatNbt(stack)
                val output = if (nbtStr.isEmpty()) id else "$id$nbtStr"

                // 复制到系统剪贴板（1.20.1 用 GLFW）
                org.lwjgl.glfw.GLFW.glfwSetClipboardString(mc.window.window, output)

                // 聊天栏输出（可点击填入聊天框）
                ctx.source.sendSystemMessage(
                    Component.literal("Copied to clipboard: ")
                        .append(Component.literal(output).withStyle {
                            it.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, output))
                                .withHoverEvent(
                                    HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Click to fill chat")
                                    )
                                )
                        })
                )
                1
            }

    private fun formatNbt(stack: ItemStack): String {
        val tag = stack.tag ?: return ""
        return tag.toString()
    }
}
