package rain.gtetcore.gtet.studio.integration

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.PreparableReloadListener
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import rain.gtetcore.gtet.studio.config.StudioLimits
import rain.gtetcore.gtet.studio.data.StudioLibrary
import rain.gtetcore.gtet.studio.editor.StudioEditor
import rain.gtetcore.gtet.studio.editor.StudioEditorCommands
import rain.gtetcore.gtet.studio.render.StudioRenderCache
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * 工作室的客户端挂点：**资源重载监听** + **`/gtetstudio` 命令**。
 *
 * ## 重载为什么只"标脏"
 * 监听器里**不读文件**，只调 [StudioLibrary.markDirty]；真正重读发生在下一次渲染调用
 * （[StudioLibrary.get] 会自己发现世代变了）。理由有两条：
 * 1. 资源重载过程中做磁盘 IO 不礼貌（那是别人的重载线程），标脏 + 下一帧懒读最省事；
 * 2. M0 时代的硬理由（`ObjLoader` 自己也是重载监听器、会清它的 materialCache，顺序没法保证）
 *    **在 M1a 已经不存在** —— 我们现在自己读 OBJ/MTL，没有任何别人的缓存要等。
 *    ⇒ 于是 `/gtetstudio reload` 会把 **JSON / OBJ / MTL / 磁盘贴图** 全部重读。
 *
 * 想**立刻**看到结果、不想按 F3+T 的话，用 `/gtetstudio reload`。
 *
 * ## 命令
 * - `/gtetstudio reload` —— 重读模型 JSON/OBJ/MTL **以及上限配置 `studio.json`**，逐条回显；
 *   ⚠️ **M2a 起还会退掉编辑状态并清空撤销栈**（避免拿旧的内存值覆盖新文件）；
 * - `/gtetstudio list`   —— 当前索引到的模型 id；
 * - `/gtetstudio limits` —— **当前生效的渲染上限值**（§7.1 纪律 2：设置必须可观测）；
 * - `/gtetstudio edit [modelId|off]`、`save`、`undo`、`redo` —— **M2a 的世界内编辑**，
 *   实现放在 `editor/StudioEditorCommands`，这里只是把它们挂到同一棵命令树上。
 *
 * 注意 F3+T 那条路**只重读模型**（配置属于 studio 自己的文件，走 `/gtetstudio reload`）——
 * 两条路都会让上限继续以"当前生效值"参与校验，只是不会去重读 studio.json。
 *
 * @author rain fox
 */
@OnlyIn(Dist.CLIENT)
object StudioClientHooks {

    /** 注册进 `RegisterClientReloadListenersEvent` 的监听器（F3+T 会触发它）。 */
    @JvmField
    val RELOAD_LISTENER: PreparableReloadListener = object : PreparableReloadListener {

        override fun reload(
            barrier: PreparableReloadListener.PreparationBarrier,
            resourceManager: ResourceManager,
            preparationsProfiler: ProfilerFiller,
            reloadProfiler: ProfilerFiller,
            backgroundExecutor: Executor,
            gameExecutor: Executor,
        ): CompletableFuture<Void> {
            StudioLibrary.markDirty()
            // F3+T 也必须退掉编辑、清掉未保存的活值（与 /gtetstudio reload 同一个理由：
            // 别拿旧的内存值去覆盖刚读进来的文件）。重载回调不保证在主线程 ⇒ 那边上了锁。
            StudioEditor.onLibraryReloaded()
            // 等同步栅栏过了再返回，保持"资源重载"的语义完整（我们本身不做异步活）
            return barrier.wait(Unit).thenRun { }
        }

        override fun getName(): String = "GTET Studio"
    }

    /** 注册 `/gtetstudio ...`（客户端命令）。 */
    @JvmStatic
    fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            // M2a 的 edit / save / undo / redo 挂在同一棵树上，实现放在 editor/StudioEditorCommands
            StudioEditorCommands.decorate(
                Commands.literal("gtetstudio")
                    .then(
                        Commands.literal("reload").executes { ctx ->
                            val source = ctx.source
                            val lines = StudioLibrary.reloadAll()
                            // 模型库已经换新实例了，把旧实例的顶点缓冲立刻还回去（GL 必须在渲染线程上动；
                            // 客户端命令本来就跑在主线程=渲染线程，recordRenderCall 会立即执行）
                            RenderSystem.recordRenderCall { StudioRenderCache.closeAll() }
                            // ⚠️ M2a：重载之后**必须**退掉编辑、清掉撤销栈与未保存的活值。
                            // 理由只有一条：别拿旧的内存值去覆盖刚读进来的文件
                            // （旧目标的实例已经是上一代了，留着它拖拽就是在往空气上写）。
                            StudioEditor.onLibraryReloaded()
                            source.sendSystemMessage(Component.literal("[gtetstudio] 重载完成，共 ${lines.size} 条："))
                            lines.forEach { source.sendSystemMessage(Component.literal("  $it")) }
                            source.sendSystemMessage(
                                Component.literal("[gtetstudio] 已退出编辑状态并清空撤销栈（未保存的内存改动已丢弃）")
                            )
                            1
                        }
                    )
                    .then(
                        Commands.literal("list").executes { ctx ->
                            val ids: Set<ResourceLocation> = StudioLibrary.knownIds()
                            ctx.source.sendSystemMessage(
                                Component.literal("[gtetstudio] 已索引 ${ids.size} 个模型: $ids")
                            )
                            1
                        }
                    )
                    .then(
                        /**
                         * §7.1 纪律 2「超限/设置必须可观测」：光有开关、看不出来等于没有。
                         * 这里把**当前真正生效的**上限打出来（值 + 来处），排查"机器没画出来"时先看它。
                         */
                        Commands.literal("limits").executes { ctx ->
                            val source = ctx.source
                            // 命令可能比第一次渲染更早被执行 ⇒ 先把配置读起来（读过就不再读）
                            StudioLibrary.ensureConfigLoaded()
                            source.sendSystemMessage(
                                Component.literal("[gtetstudio] 当前生效的渲染上限（来自 ${StudioLimits.source}）：")
                            )
                            for (line in StudioLimits.describe()) {
                                source.sendSystemMessage(Component.literal("  $line"))
                            }
                            1
                        }
                    )
            )
        )
    }
}