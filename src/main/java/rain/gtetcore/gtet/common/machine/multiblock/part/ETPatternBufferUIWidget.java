package rain.gtetcore.gtet.common.machine.multiblock.part;

import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * 「ME 样板总成」的机器 UI 外壳：GTM 的 {@link FancyMachineUIWidget} + **底边贴屏**兜底。
 *
 * <h2>为什么需要它（用户口径："面板向上扩、底边固定在物品栏分割线"）</h2>
 * 总成的样板槽面板会随容量变高，最高的那一档 12 行 = 232px（见
 * {@link ETMEPatternBufferPartMachine#createUIWidget()}；容量超过一页的 216 格之后面板**不再变高**，
 * 改由翻页呈现，所以这里的上限就是 232px）。而 LDLib 的窗口是**垂直居中**的：
 * {@code ModularUI#getGuiTop()} 的实现是 {@code (screenHeight - height) / 2}（javap 本项目实际
 * 编译用的 ldlib deobf jar 可复核），{@code ModularUI#updateScreenSize} 再把它当成
 * {@code mainGroup} 的 {@code parentPosition}。于是窗口一高，**上下两头一起出屏**：
 * 底部的物品栏会被推到屏幕外——正是用户明确不要的行为。
 *
 * <h2>做法（不动 LDLib，只用它自己给的钩子）</h2>
 * {@code ModularUI#updateScreenSize} 在设完 {@code mainGroup} 的居中位置之后会调
 * {@code mainGroup.onScreenSizeUpdate(screenW, screenH)}，而 {@code WidgetGroup} 会把这一调用
 * **递归转发给所有子控件**（javap 可见）。本类就在这个回调里把**自己**（整个窗口的根控件）
 * 沿 Y 挪一段，使窗口**底边正好落在屏幕底边**上：
 * <pre>
 * 目标绝对 y = screenH - height            （底边贴屏底）
 * 当前父偏移 = (screenH - gui.getHeight()) / 2  （LDLib 刚设的居中量）
 * 需要设置 selfPosition.y = 目标绝对 y - 当前父偏移
 * </pre>
 * ⚠️ 只在**真的放不下**（height &gt; screenH）时挪；放得下就设回 (0,0)，把布局交还给 GTM/LDLib
 * 原来的垂直居中（这样绝大多数字号/窗口下外观与 GTM 其它机器**完全一致**）。
 *
 * <h2>为什么这是"两端一致"的做法（而不是客户端自适应尺寸）</h2>
 * 机器 UI 是**服务端与客户端各建一次**的：{@code MachineUIFactory#createUITemplate} 两边都走
 * {@code IUIMachine#createUI}（客户端靠 {@code readHolderFromSyncData} 拿到机器再建一遍），
 * 控件树靠"控件路径"同步（{@code SPacketUIWidgetUpdate}）。所以**尺寸/结构必须两端一致**，
 * 本类只改**位置**、且只在客户端（{@code updateScreenSize} 只被
 * {@code ModularUIGuiContainer#init()} 调用，服务端根本拿不到屏幕尺寸、也就不会走这个回调），
 * 树结构与所有 {@code size} 完全不变 —— 这是 LDLib 自己给客户端布局用的钩子，不是我们私改。
 *
 * <h2>已知代价（仅在这种"放不下"的兜底场景里出现）</h2>
 * 挪窗口不会同步移动 {@code net.minecraft.world.inventory.Slot} 的 x/y（那是
 * {@code SlotWidget#initWidget} 时期按**居中**坐标算好、给 JEI/EMI 之类看的），所以兜底生效时
 * JEI 对着**玩家物品栏**画的覆盖层会偏一段（面板里的样板槽是 AE2 自己的 Widget、不是原版 Slot，
 * 不受影响）。正常窗口（放得下）时偏移为 0，没有任何影响。
 *
 * <h2>各分辨率/缩放下的实际行为（按 216 档 = 326px 高 / 348px 宽的窗口算）</h2>
 * <ul>
 * <li>1080p + 缩放 2（960×540）：装得下，垂直居中，**与 GTM 其它机器外观一致**；</li>
 * <li>1080p + 缩放 3（640×360）：装得下（余 34px）；</li>
 * <li>1080p + 缩放 4 / 自动（480×270）：高度差 56px ⇒ 触发兜底，底边贴屏、**上面 56px 被裁**，
 * 物品栏与下面的行都在；宽度 348+左侧页栏 20 = 368 ≤ 480，横向不受影响；</li>
 * <li>更小的窗口（逻辑高度 &lt; 326）：同上，一律"保底边、裁顶"。想全看全就得减小 GUI 缩放。</li>
 * </ul>
 * ⚠️ **横向没有兜底**：窗口比屏幕宽时 LDLib 仍然居中，两侧（含画在窗口左侧外面的页栏）会被裁。
 * 这里不做横向偏移，是因为偏移之后"露出来的那一侧"要牺牲另一侧，不如保持 GTM 的居中语义；
 * 代价已用 {@code ETMEPatternBufferPartMachine#MAX_BLOCKS} 压住 —— 面板最宽只并 2 块（340px），
 * 于是只需要逻辑宽度 ≥ 368 才不会横向出屏（1080p 的缩放 2/3/4 都满足）。
 *
 * <h2>生效范围</h2>
 * 只有**直接右键总成/镜像**打开的 UI 用这个外壳（{@code ETMEPatternBufferPartMachine#createUI}）。
 * 在**多方块控制器**的 UI 里翻到总成那一页时，外壳是控制器自己的 {@code FancyMachineUIWidget}，
 * 本类的底边贴屏不生效（那一页是控制器窗口里的一页，尺寸由控制器的窗口管）。这是有意为之：
 * 我们不该去改别人机器的 UI。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ETPatternBufferUIWidget extends FancyMachineUIWidget {

    /**
     * @param mainPage 主页面提供者（样板总成本机）
     * @param width    初始尺寸（与 GTM 的 {@code IFancyUIMachine#createUI} 一致传 176/166；
     *                 真正的尺寸在 {@code setupFancyUI} 里按内容重算）
     * @param height   初始高度
     */
    public ETPatternBufferUIWidget(IFancyUIProvider mainPage, int width, int height) {
        super(mainPage, width, height);
    }

    @Override
    public void onScreenSizeUpdate(int screenWidth, int screenHeight) {
        super.onScreenSizeUpdate(screenWidth, screenHeight);
        bottomAnchor(screenHeight);
    }

    /**
     * 放不下就把窗口下移到底边贴屏底；放得下就复位成"居中"。
     *
     * <p>⚠️ 复位这一步不能省：{@code setupFancyUI} 每次翻页都会重算尺寸并再次触发
     * {@code updateScreenSize}，同一个控件实例会被反复使用（换到更小的页面时必须能挪回去）。
     */
    private void bottomAnchor(int screenHeight) {
        var gui = getGui();
        int height = getSize().height;
        if (gui == null || screenHeight <= 0 || height <= screenHeight) {
            setSelfPosition(0, 0);
            return;
        }
        int parentTop = (screenHeight - gui.getHeight()) / 2;
        setSelfPosition(0, screenHeight - height - parentTop);
    }
}
