package rain.gtetcore.gtet.common.machine.multiblock.part.ae
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider
import net.minecraft.MethodsReturnNonnullByDefault
import javax.annotation.ParametersAreNonnullByDefault

/**
 * 「ME 样板总成」的机器 UI 外壳：GTM 的 [FancyMachineUIWidget] + **底边贴屏**兜底。
 *
 * 总成的面板会随容量变高，最高一档 7 行 = 142px（见
 * [ETMEPatternBufferPartMachine.createUIWidget]；更大的容量改由翻页呈现，所以上限就是它）。
 * 而 LDLib 的窗口是**垂直居中**的（`ModularUI#getGuiTop()` = `(screenHeight - height) / 2`），
 * 窗口一高就上下一起出屏，底部的物品栏会被推出屏幕 —— 这正是用户不要的行为。
 *
 * 做法：`ModularUI#updateScreenSize` 设完居中量之后会递归调
 * `mainGroup.onScreenSizeUpdate(screenW, screenH)`，本类在这个回调里把自己（窗口根控件）沿 Y
 * 挪到"底边贴屏底"：
 * ```
 * selfPosition.y = (screenH - height) - (screenH - gui.getHeight()) / 2
 * ```
 * 只在**真的放不下**（height > screenH）时挪，放得下就设回 (0,0) 交还给 LDLib 的居中，所以正常情况
 * 外观与 GTM 其它机器完全一致。只改位置、不改尺寸，而且这个回调只在客户端跑
 * （`ModularUIGuiContainer#init()` 调用），两端控件树仍然一致。
 *
 * 窗口高 236px（面板 142 + 边框 8 + 玩家物品栏 86）：1080p 的缩放 2/3/4（逻辑高 540/360/270）
 * 都装得下、居中显示；逻辑高 < 236 时才触发兜底（例如 720p + 缩放 4 = 180），那时"保底边、裁顶"，
 * 想全看全得减小 GUI 缩放。
 *
 * ⚠️ 兜底生效时 JEI/EMI 对着**玩家物品栏**画的覆盖层会偏一段：原版 `Slot` 的 x/y 是
 * `initWidget` 时按居中坐标算好的，不会跟着窗口挪。**横向没有兜底**：窗口比屏幕宽时两侧会被裁，
 * 这是有意为之（否则要牺牲另一侧），宽度已由 [ETMEPatternBufferPartMachine] 的 `MAX_BLOCKS`
 * 压到 340px。
 *
 * 只有**直接右键总成/镜像**打开的 UI 用这个外壳；在多方块控制器 UI 里翻到总成那一页时，外壳是控制器
 * 自己的，本类的兜底不生效 —— 有意不碰别人机器的 UI。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class ETPatternBufferUIWidget : FancyMachineUIWidget {

    /**
     * @param mainPage 主页面提供者（样板总成本机）
     * @param width    初始尺寸（与 GTM 的 `IFancyUIMachine#createUI` 一致传 176/166；
     *                 真正的尺寸在 `setupFancyUI` 里按内容重算）
     * @param height   初始高度
     */
    constructor(mainPage: IFancyUIProvider, width: Int, height: Int) : super(mainPage, width, height)

    override fun onScreenSizeUpdate(screenWidth: Int, screenHeight: Int) {
        super.onScreenSizeUpdate(screenWidth, screenHeight)
        bottomAnchor(screenHeight)
    }

    /**
     * 放不下就把窗口下移到底边贴屏底；放得下复位成"居中"。
     *
     * ⚠️ 复位不能省：`setupFancyUI` 每次翻页都会重算尺寸并再触发一次本回调，同一个控件实例
     * 会被反复使用，换到更小的页面时必须能挪回去。
     */
    private fun bottomAnchor(screenHeight: Int) {
        val gui = getGui()
        val height = size.height
        if (gui == null || screenHeight <= 0 || height <= screenHeight) {
            setSelfPosition(0, 0)
            return
        }
        val parentTop = (screenHeight - gui.height) / 2
        setSelfPosition(0, screenHeight - height - parentTop)
    }
}