package rain.gtetcore.gtet.studio.interaction

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW
import rain.gtetcore.gtet.Gtetcore
import rain.gtetcore.gtet.studio.editor.StudioEditorScreen
import rain.gtetcore.gtet.studio.interaction.StudioKeyMappings.CATEGORY

/**
 * **工作室自己的热键**（`Ctrl+Z` 撤销 / `Ctrl+Y` 重做）。
 *
 * ## 为什么用 `@Mod.EventBusSubscriber` 而不是改宿主主类
 * 注册热键要在 **mod 总线**上收 `RegisterKeyMappingsEvent`，而"往总线注册"这件事
 * Forge 允许用注解自动完成（`@Mod.EventBusSubscriber(bus = MOD, value = CLIENT)`）。
 * 于是 studio 可以**一行宿主代码都不动**地把自己挂上去 —— 这正是 M2a 的接线纪律
 * （`GTETConfig.java` / `init/CommonProxy.kt` 一个字都没改）。
 *
 * ## 为什么触发不写在 `consumeClick()`
 * 热键的**实际触发**在 [StudioInteractionEvents] 里用原始按键事件判（`InputEvent.Key` +
 * `isActiveAndMatches`），这样有两点好处：
 * 1. 需要 `Ctrl` 修饰键 —— `KeyMapping` 本身不表达修饰键，`consumeClick()` 会把裸按 `Z`
 *    也当成撤销；在原始事件里判 `Ctrl` 才准确；
 * 2. 就算哪天注解注册因为环境原因没生效，**默认键位照样能用**（`KeyMapping` 对象还在，
 *    它自己记着（可能被玩家改过的）键位）。
 *
 * ## 名字直接用中文
 * `I18n` 查不到就会原样显示 key，而**不动 lang 文件**是这次的纪律
 * （`src/generated/` 里与 lang 相关的产物属于别人的在制品）。所以直接给中文，
 * 控制界面里显示的就是"撤销（Ctrl+Z）"。
 *
 * @author rain fox
 */
@Mod.EventBusSubscriber(modid = Gtetcore.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object StudioKeyMappings {

    /** 控制界面里的分类名。 */
    const val CATEGORY: String = "机器渲染工作室"

    /** 撤销：`Ctrl+Z`（修饰键由 [StudioInteractionEvents] 检查）。 */
    @JvmField
    val UNDO: KeyMapping = KeyMapping("撤销（Ctrl+Z）", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY)

    /** 重做：`Ctrl+Y`（也接受 `Ctrl+Shift+Z`）。 */
    @JvmField
    val REDO: KeyMapping = KeyMapping("重做（Ctrl+Y）", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY)

    /**
     * 精细拖动（**不吸附**）修饰键：默认 `Shift`。
     *
     * ⚠️ 它同时也是编辑器飞行的"下降"键 —— 这个冲突在 [StudioEditorScreen] 那边用
     * **「拖拽期间不飞行」**解决（拖东西的时候按 WASD/Shift 本来就不该把玩家开走）。
     * 想换键就在 选项 → 控制 → 按键绑定 → 「[CATEGORY]」里换。
     */
    @JvmField
    val SNAP_FINE: KeyMapping =
        KeyMapping("精细拖动（不吸附）", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT, CATEGORY)

    /** 整格吸附修饰键：默认 `Ctrl`。同样可绑定。 */
    @JvmField
    val SNAP_STEP: KeyMapping =
        KeyMapping("整格吸附（1 格）", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_CONTROL, CATEGORY)

    /**
     * 修饰键是否按住。
     *
     * **问 GLFW，不问 `KeyMapping.isDown`**：编辑器屏幕开着时按键事件先经过 `Screen`，
     * `KeyMapping` 的"按下"状态在这一层不可靠；而 `InputConstants.isKeyDown` 读的是
     * GLFW 自己的按键状态，跟屏幕无关 —— 编辑器飞行轮询（`StudioEditorScreen.axis`）
     * 用的就是同一条口径，实机验证过有效。
     *
     * 除了 [mapping] 自己绑的键，**只在玩家没改过绑定时**再认一次右侧同名键
     * （默认值都是左边的 Shift / Ctrl，但很多人习惯按右边那个）。
     * 玩家一旦改绑，就完全听他的 —— 不再拿右侧键兜底，免得"改到别的键上却还被旧键触发"。
     *
     * 绑成鼠标键之类的少见情况没法用 GLFW 键盘查询，退回 [KeyMapping.isDown]。
     */
    @JvmStatic
    fun modifierDown(mapping: KeyMapping, defaultKey: Int, mirrorKey: Int): Boolean {
        if (mapping.key.type != InputConstants.Type.KEYSYM) return mapping.isDown

        val window = Minecraft.getInstance().window.window
        if (InputConstants.isKeyDown(window, mapping.key.value)) return true
        if (mapping.key.value != defaultKey) return false
        return InputConstants.isKeyDown(window, mirrorKey)
    }

    /** 精细（不吸附）键是否按住。 */
    @JvmStatic
    fun isFineDown(): Boolean = modifierDown(SNAP_FINE, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)

    /** 整格吸附键是否按住。 */
    @JvmStatic
    fun isStepDown(): Boolean = modifierDown(SNAP_STEP, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)

    @JvmStatic
    @SubscribeEvent
    fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(UNDO)
        event.register(REDO)
        event.register(SNAP_FINE)
        event.register(SNAP_STEP)
    }
}