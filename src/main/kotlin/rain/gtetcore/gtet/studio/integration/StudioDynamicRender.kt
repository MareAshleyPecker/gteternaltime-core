package rain.gtetcore.gtet.studio.integration

import com.gregtechceu.gtceu.api.machine.feature.IMachineFeature
import com.gregtechceu.gtceu.client.renderer.machine.DynamicRender
import com.gregtechceu.gtceu.client.renderer.machine.DynamicRenderType
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.serialization.Codec
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import rain.gtetcore.gtet.studio.api.StudioAnchor
import rain.gtetcore.gtet.studio.config.StudioLimits
import rain.gtetcore.gtet.studio.data.StudioLibrary
import rain.gtetcore.gtet.studio.editor.StudioEditor
import rain.gtetcore.gtet.studio.interaction.StudioInteractionEvents
import rain.gtetcore.gtet.studio.render.StudioRenderer
import kotlin.math.max

/**
 * 把工作室的模型接进 GTM 的 BER 管线 —— **这是"画在机器上"的全部机关**。
 *
 * ## 为什么必须是 `DynamicRender`（而不是 `MachineBuilder` 上的什么东西）
 * GTM 7.5.3 里机器的挂载点是
 * `MachineModelBuilder#addDynamicRenderer(Supplier<DynamicRender>)`（`MachineModelBuilder.java:173`），
 * 由 `MachineBuilder#model(...)` 的 `ModelInitializer.andThen { it.addDynamicRenderer { ... } }` 接上去
 * （GTM 的用法见 `GTMultiMachines.java:736-739` 的聚变堆）。而能**进 BER**（而不是进静态 quad）
 * 的只有 `DynamicRender` —— `IMachineRendererModel.isBlockEntityRenderer()` 在 `DynamicRender` 里恒为 true
 * （`DynamicRender.java:37-40`）。
 *
 * ⚠️ 两个必须同时做到、漏一个就静默坏掉的点：
 * 1. **`DynamicRenderType` 必须先注册**（[StudioDynamicRenders.register]），否则 datagen 编码 /
 *    运行时解码会报 `Dynamic render type with ID ... does not exist`（`DynamicRenderManager.java:25`）；
 * 2. **机器要 `.hasBER(true)`**，否则根本没有 BER 去调 `MachineModel.render`（`MachineBuilder.java:682-684`）。
 *
 * ## 矩阵与坐标系
 * `render(...)` 拿到的 `PoseStack` **原点 = 方块最小角、且已扣相机**（`LevelRenderer.java:1271` 起）。
 * 具体摆放交给 [StudioRenderer]。
 *
 * @author rain fox
 */
class StudioDynamicRender(
    /** 指向 `config/gtetstudio/` 下那份定义的 `id`。 */
    val modelId: ResourceLocation,
) : DynamicRender<IMachineFeature, StudioDynamicRender>() {

    override fun getType(): DynamicRenderType<IMachineFeature, StudioDynamicRender> = TYPE

    override fun render(
        machine: IMachineFeature,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val self = machine.self()
        val level = self.level ?: return
        if (!StudioMachineHost.isFormed(self)) return

        // ★ M2a：登记「机器 → studio 实例」。编辑模式（/gtetstudio edit）与拾取都从这张表查，
        //   所以"哪些机器带着 studio 模型"这件事**只**在 integration 里被知道。
        StudioMachineRegistry.register(self, modelId)

        val instance = StudioLibrary.get(modelId) ?: return

        StudioRenderer.render(
            host = StudioMachineHost(self, level, partialTick),
            instance = instance,
            anchor = previewAnchor(instance.model.anchor),
            poseStack = poseStack,
            buffer = buffer,
            packedLight = packedLight,
            packedOverlay = packedOverlay,
        )

        // ★ M2a：正在编辑这台机器时，**用刚画完模型的那份姿态栈**画 gizmo。
        //
        // 为什么必须在这里画（而不是在 `RenderLevelStageEvent` 里）：
        // 关卡阶段事件给的姿态栈与 BER 这份**不是同一个坐标系** —— M2a 第一版就在那儿画，
        // 实机表现为"编辑模式只有一行字、gizmo 看不见也点不到"。而这份姿态栈是模型正在用的、
        // 已经实机验证过的（模型正确出现在机器上），两者共用 ⇒ 结构上不可能错位。
        //
        // 视图旋转取"进来时"那份姿态栈的 3×3：BER 的矩阵 = 视图旋转 · translate(方块 − 相机)，
        // 平移不影响线性部分，所以上左 3×3 就是**含视角摇晃**的视图旋转。
        // （`StudioRenderer.render` 内部自己 push/pop，返回时姿态栈已还原。）
        val target = StudioEditor.target()
        if (target != null && target.pos == self.pos && target.modelId == modelId) {
            StudioInteractionEvents.drawGizmo(
                poseStack = poseStack,
                target = target,
                viewRotation = Matrix3f(poseStack.last().pose()),
            )
        }
    }

    /**
     * 编辑中的**实时预览**：把 `anchor.offset` 换成内存里那个活值。
     *
     * ## 为什么这样就是"实时"
     * `anchor` **只参与渲染矩阵**（`StudioRenderer` 里那几行 translate/scale），
     * 顶点缓冲里烘的只有几何与光照（`StudioRenderCache.build`）⇒
     * 改一个数字，**下一帧**就是新位置，**不需要重烘 VBO、不需要重载模型、不需要写文件**。
     * （M1a 定的这条结构在 M2a 拿到了回报：拖拽是零成本的。）
     *
     * 只在真编辑过（有未落盘的活值）时才新建对象，平时直接用模型文件里那一份。
     */
    private fun previewAnchor(base: StudioAnchor): StudioAnchor {
        val live = StudioEditor.previewOffset(modelId) ?: return base
        return StudioAnchor(base.mode, base.face, live.x, live.y, live.z, base.scale)
    }

    override fun shouldRender(machine: IMachineFeature, cameraPos: Vec3): Boolean {
        val self = machine.self()
        if (!StudioMachineHost.isFormed(self)) return false
        // 这里也登记一次：模型没载入成功 / 还没画到时，编辑命令也应该能找到这台机器
        StudioMachineRegistry.register(self, modelId)
        return Vec3.atCenterOf(self.pos).closerThan(cameraPos, getViewDistance().toDouble())
    }

    /**
     * ⚠️ **这不是"无视视锥"**（与直觉相反）：它唯一的作用是把 BE 放进 `globalBlockEntities`，
     * 让它不随区块可见性走，**渲染时照样过视锥剔除**（`ChunkRenderDispatcher.java:669-677`）。
     * 所以这里保持 false —— 真正管用的是 [getRenderBoundingBox] 放大 AABB。
     */
    override fun shouldRenderOffScreen(machine: IMachineFeature): Boolean = false

    /**
     * 视距：模型 JSON 自己写了 `viewDistance` 的以**它**为准；没写就用 `studio.json` 的
     * `defaultViewDistance`（[StudioLimits.defaultViewDistance]，运行时值，改配置 reload 就生效）。
     * 连实例都取不到时（模型还没载入/载入失败）也退回那个默认值 ——
     * 视距是剔除用的，不该因为模型没载入就不给值。
     */
    override fun getViewDistance(): Int =
        StudioLibrary.get(modelId)?.model?.viewDistance ?: StudioLimits.defaultViewDistance

    /**
     * 把包围盒放大到能包住整个模型。
     *
     * **不 override 就等于没有**：默认只有"方块 ±1"（`IMachineRendererModel.java:61-64`），
     * 而这个时钟横跨十几格 —— 控制器一出视锥，整台机器连同时钟一起消失。
     * 注意 `getRenderBoundingBox` **只喂视锥剔除、不裁三角片**，所以放大它不会把模型切掉，
     * 只是让"什么时候该画"变准（`LevelRenderer.java:1267/1296`）。
     *
     * 放大量取自模型自己的包围盒 × anchor 缩放（[rain.gtetcore.gtet.studio.api.StudioModel.reachRadius]），
     * 而不是抄 GTM 聚变堆那个写死的 `inflate(viewDistance/2)`（`FusionRingRender.java:110-112`）——
     * 后者会给出 16 格的巨大 AABB，对剔选不划算。
     *
     * ⚠️ **编辑中还要额外补一段**：内存里的偏移可能比文件里那份更长，
     * 不补的话"拖远一点模型就被视锥剔除切掉"（看起来像渲染器坏了）。
     * 这个方法**可能被区块构建工作线程调用**，所以 `StudioEditor.previewOffsetLength` 那边上了锁。
     */
    override fun getRenderBoundingBox(machine: IMachineFeature): AABB {
        val pos = machine.self().pos
        val instance = StudioLibrary.get(modelId) ?: return AABB(pos)
        val previewExtra = StudioEditor.previewOffsetLength(modelId)
            ?.let { max(0.0, it - instance.model.anchor.offsetLength) }
            ?: 0.0
        return AABB(pos).inflate(instance.model.reachRadius + previewExtra + 1.0)
    }

    companion object {

        /**
         * 编解码器。
         *
         * 编码出来的 JSON 长这样（datagen 会把它写进机器模型的 `dynamic_renders` 数组）：
         * ```json
         * { "type": "gtetcore:studio", "model": "gtet:test_clock" }
         * ```
         * `type` 由 GTM 的 `DynamicRender.CODEC`（`dispatchStable`）加上，`model` 是本 codec 的字段。
         * 用 `Codec` 而不是 `Codec.unit`，是为了**换模型只改一行**（改机器注册里那个 id），
         * 不用碰 Java/Kotlin 代码。
         */
        @JvmField
        val CODEC: Codec<StudioDynamicRender> =
            ResourceLocation.CODEC.fieldOf("model")
                .xmap({ StudioDynamicRender(it) }, { it.modelId })
                .codec()

        @JvmField
        val TYPE: DynamicRenderType<IMachineFeature, StudioDynamicRender> = DynamicRenderType(CODEC)
    }
}