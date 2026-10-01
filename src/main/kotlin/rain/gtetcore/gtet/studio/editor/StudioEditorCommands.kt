package rain.gtetcore.gtet.studio.editor

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.logging.LogUtils
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import rain.gtetcore.gtet.studio.data.StudioAnchorWriteException
import rain.gtetcore.gtet.studio.data.StudioAnchorWriter
import rain.gtetcore.gtet.studio.data.StudioLibrary
import rain.gtetcore.gtet.studio.editor.StudioEditorCommands.decorate
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * **`/gtetstudio edit | save | undo | redo`**（M2a 新加的子命令）。
 *
 * 命令树仍然是宿主已经注册好的那一棵 `gtetstudio`（注册点在 `integration/StudioClientHooks`，
 * 那边只多调一次 [decorate]）—— **不动宿主 mod 主类**，也不新建第二个根命令。
 *
 * ## 每个子命令的反馈口径（**失败不许静默**）
 * | 命令 | 成功 | 失败 |
 * |---|---|---|
 * | `edit` | 进了哪个实例（模型 id + 坐标 + 当前偏移），并**打开编辑器界面**（M2b：独占输入） | 准星没对着/没找到那个 id —— 并列出可选 id |
 * | `edit off` | 退出了谁、有没有未保存改动 | 本来就没在编辑 |
 * | `save` | 写回了哪个文件、写成了什么值、重载结果 | 没改动 / 找不到文件 / 文本级改写做不到（**原样保留文件**） |
 * | `undo` `redo` | 撤销/重做的是哪一步 | 没有可撤销/可重做的动作 |
 *
 * @author rain fox
 */
object StudioEditorCommands {

    private val LOGGER: Logger = LogUtils.getLogger()

    private const val PREFIX = "[gtetstudio] "

    /** 把这四个子命令挂到现有的 `gtetstudio` 命令树上，返回同一个 builder（链式）。 */
    @JvmStatic
    fun decorate(root: LiteralArgumentBuilder<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> =
        root
            .then(
                Commands.literal("edit")
                    /**
                     * `/gtetstudio edit` —— 取**准星指向**的那台机器上的 studio 实例。
                     *
                     * 口径（两条，按顺序）：
                     * 1. 原版的方块拾取 `Minecraft.getInstance().hitResult` 所在的那一格；
                     * 2. 兜底：准星射线打「机器方块 ± 模型包围盒」—— 因为模型明显比一个方块大，
                     *    指着伸出去的时钟盘面时第 1 条是打不到的。
                     */
                    .executes { ctx -> editByCrosshair(ctx.source) }
                    .then(
                        Commands.literal("off").executes { ctx ->
                            say(ctx.source, StudioEditor.exit())
                            1
                        }
                    )
                    .then(
                        /**
                         * `/gtetstudio edit <modelId>` —— 取**离玩家最近**的那个该 id 实例。
                         * 准星不好对准（机器在拐角、模型特别大）时用它。
                         */
                        Commands.argument("model", ResourceLocationArgument.id()).executes { ctx ->
                            val id = ResourceLocationArgument.getId(ctx, "model")
                            val source = StudioEditor.sourceOrNull()
                                ?: return@executes fail(
                                    ctx.source,
                                    "studio 还没接上宿主查询 —— 先看一台带 studio 模型的机器（让渲染器登记它）"
                                )
                            val target = source.nearest(id)
                                ?: return@executes fail(
                                    ctx.source,
                                    "世界里没找到 $id 的实例（机器要成型、且在视距内；" +
                                        "可用 /gtetstudio list 看当前索引到的模型 id）"
                                )
                            say(ctx.source, StudioEditor.enter(target))
                            // ★ M2b：进编辑就**打开编辑器界面**（独占输入：解锁光标、屏蔽原版操作、
                            //   自己驱动飞行/相机）。不开它的话，原版锁着鼠标，8 像素宽的轴根本戳不中。
                            StudioEditorScreen.open()
                            1
                        }
                    )
            )
            .then(Commands.literal("save").executes { ctx -> save(ctx.source) })
            .then(
                Commands.literal("undo").executes { ctx ->
                    val done = StudioEditor.undo()
                    if (done == null) {
                        fail(ctx.source, "没有可撤销的动作")
                    } else {
                        say(ctx.source, "撤销：${done.describe()}（还可撤销 ${StudioEditor.history.undoDepth} 步）")
                        1
                    }
                }
            )
            .then(
                Commands.literal("redo").executes { ctx ->
                    val done = StudioEditor.redo()
                    if (done == null) {
                        fail(ctx.source, "没有可重做的动作")
                    } else {
                        say(ctx.source, "重做：${done.describe()}")
                        1
                    }
                }
            )

    // ────────────────────────── edit ──────────────────────────

    private fun editByCrosshair(source: CommandSourceStack): Int {
        val registry = StudioEditor.sourceOrNull()
            ?: return fail(
                source,
                "studio 还没接上宿主查询 —— 先看一台带 studio 模型的机器（让渲染器登记它）"
            )
        val target = registry.byCrosshair()
            ?: return fail(
                source,
                "准星没对着带 studio 模型的机器（指着机器正面再试；" +
                    "也可以 /gtetstudio edit <modelId> 取最近的那台，或用 /gtetstudio list 看有哪些 id）"
            )
        say(source, StudioEditor.enter(target))
        // ★ M2b：进编辑就打开编辑器界面（理由见 `edit <modelId>` 那一支）
        StudioEditorScreen.open()
        return 1
    }

    // ────────────────────────── save ──────────────────────────

    /**
     * `/gtetstudio save` —— 把**所有**未保存的编辑值写回各自模型的 JSON。
     *
     * ## 写回怎么做（★ M2a 最容易翻车的地方）
     * **绝不**用 gson 把整份 JSON 序列化回写 —— 我们的模型 JSON 是**带大量注释的 JSONC**，
     * 那样会把注释全部丢光（等于毁掉文件里的说明）。这里走
     * [StudioAnchorWriter]：文本级只替换 `anchor.offset` 那个数组，
     * 其余字节（包括注释、缩进、字段顺序）一字不动；任何做不到的情况都抛异常，
     * **文件保持原样**（宁可不写，也不许写坏）。
     *
     * 写盘用"临时文件 + 原子替换"：写到一半崩了也不会留下半个文件。
     *
     * 写完之后**立刻**重载这个模型（[StudioLibrary.reloadOne]），所以画面马上就是新位置，
     * 而且不会产生重复键/破坏格式 —— 因为文件本来就是"原地改了几个数字"。
     */
    private fun save(source: CommandSourceStack): Int {
        val dirty = StudioEditor.dirtyModels()
        if (dirty.isEmpty()) {
            return fail(source, "没有未保存的改动（进入编辑拖一下再存）")
        }

        var written = 0
        for (id in dirty) {
            val live = StudioEditor.previewOffset(id)
            if (live == null) {
                fail(source, "$id 标了未保存但取不到值（内部状态异常，已跳过）")
                continue
            }
            val path = StudioLibrary.pathOf(id)
            if (path == null) {
                fail(source, "找不到 $id 的定义文件（应该落在 config/${StudioLibrary.CONFIG_DIR}/ 下的 json 里）")
                continue
            }

            val original = try {
                Files.readString(path, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                fail(source, "读不出 ${path.fileName}：${e.message}")
                continue
            }

            val updated = try {
                StudioAnchorWriter.replaceOffset(original, live.x, live.y, live.z)
            } catch (e: StudioAnchorWriteException) {
                // ★ 走到这里 = 一个字节都没写盘
                fail(source, "${path.fileName}: ${e.message}")
                continue
            } catch (e: Exception) {
                LOGGER.error("[studio] 改写 {} 时发生意外错误", path, e)
                fail(source, "${path.fileName}: 改写时出错（${e.message}），文件未改动")
                continue
            }

            try {
                writeAtomically(path, updated)
            } catch (e: Exception) {
                LOGGER.error("[studio] 写 {} 失败", path, e)
                fail(source, "写 ${path.fileName} 失败：${e.message}（文件未被替换）")
                continue
            }

            // 立刻按新文件重载这一份（只重载它，不惊动别的模型）
            val status = StudioLibrary.reloadOne(id)
            StudioEditor.markSaved(id)
            written++
            say(
                source,
                "已写回 config/${StudioLibrary.CONFIG_DIR}/${path.fileName}：" +
                    "anchor.offset = ${live.format()}；$status"
            )
        }
        return if (written > 0) 1 else 0
    }

    /**
     * 原子写：先写同目录的 `.tmp`，再 [StandardCopyOption.ATOMIC_MOVE] 换过去。
     *
     * 为什么不用 `Files.write` 直接覆盖：写到一半崩了/断电，原文件就毁了 ——
     * 而这是**用户的模型源文件**，宁可多一次 move。
     */
    private fun writeAtomically(path: Path, text: String) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, text, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // 某些文件系统不支持 ATOMIC_MOVE：退回普通替换（仍然先落临时文件再 move）
            LOGGER.warn("[studio] ATOMIC_MOVE 不可用（{}），退回普通替换", e.message)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ────────────────────────── 反馈 ──────────────────────────

    private fun say(source: CommandSourceStack, text: String) {
        source.sendSystemMessage(Component.literal(PREFIX + text))
    }

    /** 失败**必须**说话（返回 0 ⇒ 聊天栏里是红色，用户一眼能看出没成）。 */
    private fun fail(source: CommandSourceStack, text: String): Int {
        source.sendSystemMessage(Component.literal(PREFIX + text))
        return 0
    }
}