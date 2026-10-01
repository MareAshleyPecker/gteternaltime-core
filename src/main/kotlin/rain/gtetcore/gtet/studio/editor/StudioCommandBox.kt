package rain.gtetcore.gtet.studio.editor

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.ClientCommandHandler
import net.minecraftforge.client.ClientCommandSourceStack
import rain.gtetcore.gtet.studio.editor.StudioCommandBox.ROOT

/**
 * **M2c 的命令输入框**：把面板里敲的一行字当成**客户端命令**执行，并把输出收回来画在面板上。
 *
 * ## 为什么走"客户端命令"，而不是把文本发给服务端（这是本类存在的唯一理由）
 * `/gtetstudio …` 这棵树是用 `RegisterClientCommandsEvent` 注册的（`init/ClientProxy.kt:47-50`
 * → `integration/StudioClientHooks.registerCommands`）⇒ **它只活在客户端的命令表里**，
 * 服务端根本不认识 `gtetstudio`。所以照聊天那样 `connection.sendCommand(text)` 发出去，
 * 在单人/服务器上都会得到"未知命令"。
 *
 * 正确入口是 Forge 的 [ClientCommandHandler.getDispatcher]：那就是客户端命令表本身
 * （`ClientCommandHandler.mergeServerCommands` 里 `copy(commandsTemp.getRoot(), commands.getRoot())`
 * 建的那一棵，只含客户端命令，没有掺服务端命令）。
 *
 * ## 为什么要把输出"截胡"（这是第二件必须做的事）
 * 编辑模式开着时 `options.hideGui = true`（`StudioEditorScreen.tick`），而原版
 * **聊天栏也在 `!hideGui` 里面画**（`Gui.java:252` 起的那段，`this.chat.render(...)` 在 `:340`）
 * ⇒ 命令回执进了聊天栏等于**看不见**。所以这里换掉 `CommandSourceStack` 的消息出口：
 * 用一个只做收集的 [CommandSource]，执行完把每一行交回面板。
 *
 * ## 为什么命令表里的命令能安全地在客户端跑
 * 它们本来就是客户端命令（读 `config/gtetstudio/` 的文件、改内存活值、重载模型库），
 * 一行服务端逻辑都没有。真正只在服务端成立的命令**不在这张表里**，
 * 所以"随便敲什么都在本地跑"这件事不会发生 —— 认不出的命令会得到一句明确的提示。
 *
 * ⚠️ 这一点与 `editor/` 的分层纪律不冲突：这里只 import 原版与 Forge **客户端** API，
 * 没有 GTM、没有 LDLib、也没有往 `integration/` 反向依赖。
 *
 * @author rain fox
 */
@OnlyIn(Dist.CLIENT)
object StudioCommandBox {

    /**
     * 执行前先补的根命令名。
     *
     * 允许**省略根名**：编辑器里天天敲的是 `save` / `undo` / `edit`，
     * 每次都写全 `gtetstudio save` 没意义。只有当第一个词不是命令表里已有的根时才补
     * （所以别人的客户端命令、以及以后新增的根，都不会被这个前缀影响）。
     */
    private const val ROOT = "gtetstudio"

    /** 一次命令执行的结果：面板要画的每一行，以及"成没成"。 */
    class Result(val lines: List<String>, val ok: Boolean)

    /** 只做收集的消息出口：命令的 `sendSystemMessage` / `sendSuccess` / `sendFailure` 全落进 [sink]。 */
    private class CollectingSource(private val sink: MutableList<String>) : CommandSource {

        override fun sendSystemMessage(message: Component) {
            sink += message.string
        }

        /** 收成功消息（`CommandSourceStack.sendSuccess` 第一条分支就是问它）。 */
        override fun acceptsSuccess(): Boolean = true

        /** 收失败消息（`sendFailure` 同样先问它）。 */
        override fun acceptsFailure(): Boolean = true

        /**
         * **必须 false**：真了的话 `sendSuccess` 会去走 `broadcastToAdmins`，
         * 而那条路要解引用 `server`（客户端命令里是 null）⇒ 当场 NPE。
         */
        override fun shouldInformAdmins(): Boolean = false
    }

    /**
     * 跑一行命令。
     *
     * @param text 已经把前导 `/` 去掉的命令（见 [StudioEditorInput.classifyCommandLine]）
     */
    @JvmStatic
    fun run(text: String): Result {
        val mc = Minecraft.getInstance()
        val player = mc.player
            ?: return Result(listOf("还没进世界，命令跑不了"), false)
        val dispatcher = ClientCommandHandler.getDispatcher()
            ?: return Result(listOf("客户端命令表还没建好（刚进世界？过一下再试）"), false)

        val line = resolve(dispatcher, text.trim().removePrefix("/").trim())
        if (line.isEmpty()) return Result(listOf("这一行是空的"), false)

        val sink = ArrayList<String>()
        val source = sourceFor(player, sink)
        return try {
            dispatcher.execute(StringReader(line), source)
            Result(sink.ifEmpty { listOf("（跑完了，没有输出）") }, true)
        } catch (e: CommandSyntaxException) {
            // 认不出的命令：把"能用什么"直接列出来（命令表是现成的，别让用户去翻文档）
            Result(listOf("命令没通过：${e.rawMessage.string}", "· ${usage(dispatcher)}"), false)
        } catch (e: Exception) {
            Result(listOf("命令执行出错：${e.message ?: e.javaClass.simpleName}"), false)
        }
    }

    /** 当前可用的命令（给提示用，也是"这个框到底认什么"的答案）。 */
    @JvmStatic
    fun usage(dispatcher: CommandDispatcher<CommandSourceStack>? = ClientCommandHandler.getDispatcher()): String {
        if (dispatcher == null) return "客户端命令表还没建好"
        val roots = dispatcher.root.children.map { it.name }.sorted()
        val subs = dispatcher.root.getChild(ROOT)?.children?.map { it.name }?.sorted().orEmpty()
        return if (subs.isEmpty()) {
            "可用命令：${roots.joinToString("、")}"
        } else {
            "可用：$ROOT（${subs.joinToString("、")}）｜也可以只敲子命令，例如 save"
        }
    }

    /** 省略根名时补上（见 [ROOT]）。 */
    private fun resolve(dispatcher: CommandDispatcher<CommandSourceStack>, text: String): String {
        if (text.isEmpty()) return text
        val first = text.substringBefore(' ')
        if (dispatcher.root.getChild(first) != null) return text
        return "$ROOT $text"
    }

    /**
     * 与 Forge 自己跑客户端命令时**同一份来源**（`ClientCommandHandler.getSource()`），
     * 只把"消息往哪写"换掉：
     * ```
     * ClientCommandSourceStack(player, player.position(), player.rotationVector,
     *                         player.permissionLevel, player.name.string, player.displayName, player)
     * ```
     * 这样实体/坐标/权限/各客户端查询（`getUnsidedLevel`、`getRecipeManager` 等）都与原版一致，
     * 只有 `sendSystemMessage` 落到我们手里。
     */
    private fun sourceFor(player: LocalPlayer, sink: MutableList<String>): CommandSourceStack =
        ClientCommandSourceStack(
            CollectingSource(sink),
            player.position(),
            player.rotationVector,
            player.permissionLevel,
            player.name.string,
            player.displayName,
            player,
        )
}