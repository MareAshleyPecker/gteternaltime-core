package rain.gtetcore.gtet.studio.integration

import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder
import com.gregtechceu.gtceu.client.renderer.machine.DynamicRenderManager
import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import org.slf4j.Logger
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.studio.integration.StudioDynamicRenders.register
import java.util.function.Supplier

/**
 * 工作室对 GTM 的**注册入口** —— `DynamicRenderType` 的登记 + 把模型挂到机器上的一行辅助。
 *
 * `DynamicRenderType` 必须在 **GTM 编码/解码机器模型之前**登记好：
 * - **datagen 时** `MachineModelBuilder.toJson()` 会 `DynamicRender.CODEC.encodeStart(...)`
 *   （`MachineModelBuilder.java:96-104`），type 没登记就抛
 *   `Dynamic render type ... is not registered`（`DynamicRenderManager.java:32`）；
 * - **运行时** `MachineModelLoader` 反过来解码（`MachineModelLoader.java:145`），同理。
 *
 * 所以 [register] 放在客户端的 **mod 构造阶段**（`init/ClientProxy.kt`）——这与 GTM 自己
 * 登记 `fusion_ring` 等类型的时机一致（`ClientProxy.init()` → `initializeDynamicRenders()`，
 * `gtceu/client/ClientProxy.java:85-93`），datagen 那次也会走到。
 *
 * @author rain fox
 */
object StudioDynamicRenders {

    private val LOGGER: Logger = LogUtils.getLogger()

    /** 注册 id：机器模型 JSON 里 `dynamic_renders[].type` 就是它。 */
    @JvmField
    val TYPE_ID: ResourceLocation = Gtetcore.id("studio")

    /** 登记渲染类型。**只能在客户端阶段调，且只能调一次。** */
    @JvmStatic
    fun register() {
        DynamicRenderManager.register(TYPE_ID, StudioDynamicRender.TYPE)
        // M2a：顺手把「机器 → studio 实例」的宿主查询装进编辑器。
        // 放在这里是因为它**早于一切渲染**（mod 构造阶段），而且仍然是 studio 自己的代码 ——
        // 宿主那边（ClientProxy）本来就会调本方法，**不需要新增任何宿主接线**。
        StudioMachineRegistry.install()
        LOGGER.info("[studio] 已注册 DynamicRenderType {}（模型 id 写在机器模型 JSON 的 dynamic_renders[].model 里）", TYPE_ID)
    }

    /**
     * 把一份 studio 模型挂到机器的模型上。
     *
     * 用法（**换机器 / 换模型只改下面这一行**）：
     * ```kotlin
     * .model(createWorkableCasingMachineModel(机壳贴图, 覆盖层贴图)
     *     .andThen(StudioDynamicRenders.attach("gtet:test_clock")))
     * .hasBER(true)
     * ```
     * `modelId` 是 `config/gtetstudio/` 下 JSON 里那个 `id` 字段，**不是文件名**。
     */
    @JvmStatic
    fun attach(modelId: String): MachineBuilder.ModelInitializer {
        val id = ResourceLocation.tryParse(modelId)
            ?: throw IllegalArgumentException("[studio] attach(\"$modelId\") 不是合法资源路径，要形如 gtet:test_clock")
        return MachineBuilder.ModelInitializer { _, _, builder ->
            builder.addDynamicRenderer(Supplier { StudioDynamicRender(id) })
        }
    }
}