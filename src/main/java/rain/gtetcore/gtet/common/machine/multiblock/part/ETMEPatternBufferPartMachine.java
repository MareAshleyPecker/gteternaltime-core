package rain.gtetcore.gtet.common.machine.multiblock.part;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.EncodedPatternItem;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.slot.AEPatternViewSlotWidget;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import rain.gtetcore.gtet.integration.ae2.ETPatternBufferCapacities;
import rain.gtetcore.gtet.mixin.GTM.IMEPatternBufferAccess;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「多阶段 ME 样板总成」：容量大于 GTM 原生 27 的样板总成，四个阶段各一件（LuV/UV/UEV/UXV）。
 *
 * <h2>它是什么</h2>
 * 就是 GTM 的 {@link MEPatternBufferPartMachine}（AE2 集成式样板供应器：一盘样板 + 一块共享库存
 * + 一块共享流体仓），**只把样板槽位数从写死的 27 变成按档取值**。四种能力
 * （物品/流体 进/出）、AE 终端交互、数据棒绑定镜像、取回(退款)、Jade 显示等全部沿用父类。
 *
 * <h2>容量怎么变大的（本类只有"收尾"，主戏在 mixin）</h2>
 * 父类的容量是 {@code protected static final int MAX_PATTERN_COUNT = 27}，且被**内联**进父类自己的
 * 三个字段初始化表达式（{@code patternInventory}/{@code internalInventory}/{@code detailsSlotMap}），
 * 那发生在父类构造期 —— 子类此刻还没有任何字段，所以「继承 + 覆写」拿不到更大的容量。
 * 本项目**不复制**父类那 707 行，而是让 {@code MixinMEPatternBufferCapacity} 把构造器里内联的 3 个 27
 * 换成「按本机器的方块定义查表」，三个字段一出生就是本档容量；父类后续一切
 * （构造后段的槽实例填充、{@code InternalSlotRecipeHandler} 建表、{@code onLoad} 的样板解码、
 * 取回、Jade 汇总）自动按真实容量工作。取舍与证据见 {@link ETPatternBufferCapacities}。
 *
 * <h2>本类要补的三件事（父类里唯一还写着 27 的地方）</h2>
 * <ol>
 * <li>{@link #getTerminalPatternInventory()}：父类返回的那个匿名 {@code InternalInventory}
 * 的 {@code size()} 也是内联的 27（AE 终端据此决定能放几盘样板），换成按真实格子数；</li>
 * <li>{@link #createUIWidget()}：父类的面板写死 9×3，换成「9 列一块、最多并 2 块 = 一页 216 格」的网格
 * （**不滚动**：一页铺满、四档全部一眼看全；容量超过 216 就<b>翻页</b>，面板尺寸不变 ——
 * 见该方法的注释与 {@link #createUI(Player)}）；</li>
 * <li>{@link #getTerminalGroup()}：父类在「未成型」分支把图标与名字写死成 GTM 自己的
 * {@code me_pattern_buffer}，换成我们自己这一档的定义。</li>
 * </ol>
 *
 * <p>⚠️ 面板与实际格子数以**实际存储**（{@code getPatternInventory().getSlots()}）为准，
 * 不再相信任何常量：mixin 万一没生效也只是面板变小，不会出现「面板 216 格、实际只能放 27 盘」
 * 这种错位（{@link ETPatternBufferCapacities#verify} 会在日志里吵一次）。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ETMEPatternBufferPartMachine extends MEPatternBufferPartMachine {

    /** 一格样板槽的像素边长（LDLib 标准格）。 */
    private static final int SLOT = 18;

    /** 一个网格块的列数（与 GTM 的面板一致：9 列）。 */
    private static final int BLOCK_COLUMNS = 9;

    /**
     * 一个网格块最多几行（12 行 = 216px）。
     *
     * <p>⚠️ 这个数**不是**"可见行数上限"（面板不滚动），而是"一页最多几行 + 要不要再并一块"的阈值：
     * 行数一多先横向再铺一块 9 列的块（最多 {@link #MAX_BLOCKS} 块），再放不下就翻页
     * （见 {@link #PAGE_CAPACITY}）。取 12 的依据见 {@link #createUIWidget()} 里的高度账。
     */
    private static final int MAX_ROWS_PER_BLOCK = 12;

    /**
     * 面板最多并几块（2 块 = 18 列 = 340px 宽）。
     *
     * <p>宽度也要有上限：GTM 的 fancy UI 把页码侧栏画在窗口**左侧外面**
     * （{@code FancyMachineUIWidget} 的 {@code VerticalTabsWidget} 在 x=-20），窗口一旦宽过屏幕，
     * 侧栏会被挤出屏幕、玩家连翻页都点不到。所以宁可让高度那侧靠翻页解决（见 {@link #PAGE_CAPACITY}），
     * 也不让宽度无限长。
     */
    private static final int MAX_BLOCKS = 2;

    /**
     * 一页能放几格 = {@link #BLOCK_COLUMNS} × {@link #MAX_ROWS_PER_BLOCK} × {@link #MAX_BLOCKS}
     * = 9 × 12 × 2 = <b>216</b>。这就是面板的尺寸上限（340 × 232px）。
     *
     * <p>容量正好 216 时一页铺满、零滚动；**再大就翻页**（面板一个像素都不变），
     * 512 → 3 页、1024 → 5 页；容量不到一页时只渲染实际行数（27 → 1 块 × 3 行、
     * 63 → 1 块 × 7 行、126 → 2 块 × 7 行）。
     *
     * <p>⚠️ 想换成"9 列 × 24 行"那种更高的版式，只改 {@link #MAX_ROWS_PER_BLOCK} /
     * {@link #MAX_BLOCKS} 两个常量即可：本类其余部分（页数、每页块数行数、面板尺寸）全是从它们推出来的。
     * （唯一要顺带看的是翻页控件的横向位置 —— 它是按"满页 340px 宽"摆的，版式变窄就得一起调。）
     */
    private static final int PAGE_CAPACITY = BLOCK_COLUMNS * MAX_ROWS_PER_BLOCK * MAX_BLOCKS;

    /** 网格上方的状态行高度（ME 网络状态 + 改名按钮 + 翻页控件，固定在面板最上一行）。 */
    private static final int HEADER = 14;

    /** 面板左右各留 8px、底边留 2px（沿用 GTM 那一版的边距）。 */
    private static final int PADDING_X = 8;
    private static final int PADDING_BOTTOM = 2;

    /** 右上角改名按钮的宽度（翻页控件要贴着它左边摆，所以抽出来）。 */
    private static final int RENAME_WIDTH = 70;

    /** 翻页按钮边长 / 页号文本的占位宽度 / 控件之间的间隙（都在 {@link #HEADER} 那一行里）。 */
    private static final int PAGE_BUTTON = 12;
    private static final int PAGE_LABEL_WIDTH = 32;
    private static final int PAGE_GAP = 4;

    /** 翻页控件与改名按钮之间的间隙。 */
    private static final int PAGE_RENAME_GAP = 6;

    /**
     * 当前翻到第几页（0 起）。
     *
     * <p>⚠️ 这是**纯界面状态**：不持久化、不写 NBT、也没进任何同步字段。两条理由：
     * <ul>
     * <li>真正必须两端一致的是<b>控件树结构</b>（也就是页数），而页数只由容量推出来，
     * 两端各建一次必然一致；</li>
     * <li>页码本身不需要同步：LDLib 的 {@code ButtonWidget} 回调**两端都会跑**
     * （客户端在 {@code mouseClicked} 里本地跑一次，同时 {@code writeClientAction} 让服务端的
     * {@code handleClientAction} 再跑一次 —— javap 本项目实际编译用的 ldlib jar 实证），
     * 两端各自把自己那份翻到同一页就够了。页码只影响"哪一页可见"，不影响槽位编号，
     * 所以即便某一侧没跟上也不会串槽（见 {@link #createUIWidget()} 的「页怎么藏」）。</li>
     * </ul>
     * 放在机器字段而不是控件里的局部变量：{@code createUIWidget()} 会在 GTM fancy UI 换页时被重新
     * 调用一次（{@code FancyMachineUIWidget#setupFancyUI}），字段能记住玩家翻到哪一页。
     */
    private int uiPage;

    /**
     * AE 终端看到的样板库存视图（尺寸 = 真实格子数）。
     *
     * <p>⚠️ 只能覆写 {@link #getTerminalPatternInventory()}：父类那个匿名实现是私有字段、
     * 而且它的 {@code size()} 返回的是内联常量 27，绕不过去。这里的三段逻辑与父类逐字对应
     * （写槽 → 通知内容变化 → {@code onPatternChange} 更新 AE 索引表），
     * 少了最后一段，第 28 格往后放进去的样板就不会被 {@code pushPattern} 认出来。
     */
    private final InternalInventory terminalPatternInventory = new InternalInventory() {

        @Override
        public int size() {
            return getPatternInventory().getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slotIndex) {
            return getPatternInventory().getStackInSlot(slotIndex);
        }

        @Override
        public void setItemDirect(int slotIndex, ItemStack stack) {
            getPatternInventory().setStackInSlot(slotIndex, stack);
            getPatternInventory().onContentsChanged(slotIndex);
            access().gtet$onPatternChange(slotIndex);
        }
    };

    /**
     * @param holder 方块实体
     * @param args   透传给 {@code MEBusPartMachine}（本档不需要额外参数：容量由方块定义决定）
     */
    public ETMEPatternBufferPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, IO.IN, args);
        // 探针：mixin 没生效时（GTM 版本变化）在日志里吵一次，而不是静默做成 27 格
        ETPatternBufferCapacities.verify(this);
    }

    /** 本机的样板槽位数（**以实际存储为准**，供镜像读容量）。 */
    public int getPatternCapacity() {
        return getPatternInventory().getSlots();
    }

    /**
     * <b>仓室隔离</b>：禁止这一件样板总成被两个多方块同时占用（防串配方）。
     *
     * <h2>为什么本件必须隔离（与上一批给 ME 库存件加的是同一条闸门）</h2>
     * {@code IMultiPart#canShared()} 默认返回 {@code true}，全 GTM 只有一个消费点：
     * {@code BlockPattern#checkPatternAt} 逐格匹配时，
     * {@code if (part.isFormed() && !part.canShared() && !part.hasController(worldState.controllerPos))}
     * → 该格判失败并把错误设成 {@code PatternStringError("multiblocked.pattern.error.share")}，
     * 于是<b>第二个多方块结构成不了型</b>。语义与代价见
     * {@link ETTagFilterStockBusPartMachine#canShared()} 的类注释。
     *
     * <h2>样板总成为什么也在这一列（用户口径 + GTM 自己的假设）</h2>
     * <ul>
     * <li><b>GTM 自己就假设「一件总成只属于一个控制器」</b>：父类
     * {@code MEPatternBufferPartMachine#getTerminalGroup()} 直接取
     * {@code getControllers().first()}（GTM 7.5.3 源码实测）—— 被两个控制器共享时，
     * AE 终端里这块总成的分组名会变成"任取一个控制器"，玩家看不出自己那盘样板归谁；</li>
     * <li><b>推入的原料是"这一件自己的"库存</b>：AE 合成推样板走
     * {@code pushPattern} → {@code patternDetails.pushInputsToExternalInventory(inputHolder, this::add)}，
     * 原料落在这一件自己的 {@code InternalSlot} 库存里，再由多方块的配方逻辑
     * （{@code handleItemInternal} / {@code handleFluidInternal}）从这里取。</li>
     * </ul>
     * 两件控制器都把这件总成收进自己的部件表之后，控制器 A 的合成原料会摆在控制器 B 也能取用的同一个
     * 库存里 —— 谁先跑谁吃掉，这正是「串配方」。
     *
     * <h2>不影响正常用法</h2>
     * <ul>
     * <li><b>「总成当宿主、镜像装在各机器里」</b>（本族的主要用法）不受影响：那时总成压根不在任何
     * 成型的多方块里，{@code isFormed()} 为 false，这道闸门不参与判断；</li>
     * <li>它只挡「同一格方块同时属于两个<b>已成型</b>结构」，同一结构里放两件各自独立的总成照旧允许。</li>
     * </ul>
     *
     * <p>⚠️ 与本项目自己的 ME 库存件一样，这一条同时意味着 tooltip 必须写「禁止共享」
     * （见 {@code ETMEPatternBufferHatches} 里换成 {@code gtceu.part_sharing.disabled}）。
     */
    @Override
    public boolean canShared() {
        return false;
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return terminalPatternInventory;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        // 已成型：沿用父类（控制器名字 + 电路号，或自定义名）
        if (isFormed()) return super.getTerminalGroup();
        // 未成型：父类这一支把图标与名字写死成 GTM 自己的 me_pattern_buffer。
        // 我们这四档常常就是「不组进多方块、只给一堆镜像当宿主」的用法，所以必须显示自己这一档。
        var definition = getDefinition();
        var customName = access().gtet$getCustomName();
        if (!customName.isEmpty()) {
            return new PatternContainerGroup(AEItemKey.of(definition.getItem()), Component.literal(customName),
                    Collections.emptyList());
        }
        return new PatternContainerGroup(AEItemKey.of(definition.getItem()),
                definition.getItem().getDescription(), Collections.emptyList());
    }

    /**
     * 机器 UI：一行与 GTM 的 {@code IFancyUIMachine#createUI} **逐字对应**，只把
     * {@code FancyMachineUIWidget} 换成会"底边贴屏"的子类（见 {@link ETPatternBufferUIWidget}）。
     *
     * <p>为什么必须在这里换、而不能只在 {@code createUIWidget()} 里做：窗口的尺寸与屏幕定位由
     * {@code FancyMachineUIWidget#setupFancyUI} 决定（它按内容算尺寸、再 {@code getGui().setSize()}），
     * 面板控件自己没有屏幕坐标。用户的要求是"面板从物品栏分割线向上/左/右扩"，而"往上长"这件事
     * 在 LDLib 里是**窗口居中**的表现，窗口一高就会上下一起出屏 —— 所以要在窗口这一层兜底。
     * 层级关系（GTM {@code IFancyUIMachine}）：{@code ModularUI.mainGroup} ← 本类（FancyMachineUIWidget）
     * ← {@code pageContainer} ← {@code createUIWidget()} 返回的面板。
     */
    @Override
    public ModularUI createUI(Player entityPlayer) {
        return new ModularUI(176, 166, this, entityPlayer)
                .widget(new ETPatternBufferUIWidget(this, 176, 166));
    }

    /**
     * 样板槽面板：**9 列一块、最多并 2 块 = 一页 216 格；容量超过 216 就翻页**。
     *
     * <h2>布局规则与四档的实际尺寸</h2>
     * <pre>
     * 一页的格数  = 9 × 12 × 2 = 216              （{@link #PAGE_CAPACITY}）
     * 页数        = ⌈容量 / 216⌉                  （512 → 3 页、1024 → 5 页）
     * 某一页的块数 = clamp(⌈这一页的格数 / 108⌉, 1, 2)；行数 = ⌈这一页的格数 / (9 × 块数)⌉
     * 面板(宽×高) = 按"一页铺满"算 ⇒ 容量 ≥ 216 时恒为 340 × 232，**翻页时一个像素都不变**
     *
     * 档位  容量  块数×行数   面板(宽×高)   整个窗口高 = 面板高 + 8(边框) + 86(玩家物品栏)
     * LuV    27   1 × 3      178 ×  70        164
     * UV     63   1 × 7      178 × 142        236
     * UEV   126   2 × 7      340 × 142        236
     * UXV   216   2 × 12     340 × 232        326
     * </pre>
     * ⚠️ 玩家物品栏那 86 是 LDLib {@code PlayerInventoryWidget} 的**默认尺寸 172×86**
     * （javap 本项目实际编译用的 ldlib deobf jar：构造器里 {@code super(0,0,172,86)}）；
     * 8 是 {@code FancyMachineUIWidget#setupFancyUI} 的 {@code border*2}（border 默认 4）。
     *
     * <h2>为什么是"翻页"，而不是"继续并块 / 继续长高 / 滚动"</h2>
     * 用户口径：**面板尺寸就以 216 那一档为上限**（容量 216 时正好铺满、零滚动），再大就翻页；
     * 容量不到 216 的档只渲染实际行数。三条被否掉的路各自撞的墙：
     * <ul>
     * <li><b>滚动</b>：用户明确否掉了（要么靠拖动、要么一屏看不全）；</li>
     * <li><b>继续并块</b>：宽度会被屏幕卡住 —— GTM 的 fancy UI 把页码侧栏画在窗口**左侧外面**
     * （{@code VerticalTabsWidget} 在 x=-20），窗口一宽过屏幕，侧栏就被挤出去、连翻页都点不到
     * （见 {@link #MAX_BLOCKS} 与 {@link ETPatternBufferUIWidget} 的横向说明）；</li>
     * <li><b>继续长高</b>：24 行 = 448px 面板 ⇒ 542px 窗口，1080p 缩放 2（540）都放不下，
     * 只能靠 {@link ETPatternBufferUIWidget} 的底边贴屏去裁顶部 —— 连状态行都会被裁掉。</li>
     * </ul>
     * 翻页还有个白拿的好处：页数与每页尺寸都由 {@link #PAGE_CAPACITY} 推出来，
     * 玩家后来自己加多大的档位（注册上限 4096 ⇒ 19 页）都不用再改这里的代码。
     *
     * <h2>页怎么藏（LDLib/原版实证，都打在**本项目实际编译用的**两个 jar 上）</h2>
     * 每一页都**真的建出来**（控件树结构在界面存活期间两端必须一致，不能"按当前页建树"），
     * 只把非当前页 {@code setVisible(false)}。三条把关的路径：
     * <ol>
     * <li><b>画</b>：{@code WidgetGroup#drawWidgetsBackground} / {@code drawWidgetsForeground} 都跳过
     * {@code isVisible() == false} 的子控件；而槽里的物品本来就是 LDLib 自己画的
     * （{@code SlotWidget#drawInBackground} 直接取 {@code Slot#getItem()} —— LDLib 的
     * {@code ModularUIGuiContainer#render} **不调**原版的 {@code AbstractContainerScreen#render}，
     * javap 里没有这条 invokespecial），所以藏起来的页连物品都画不出来；</li>
     * <li><b>点</b>：{@code WidgetGroup#mouseClicked} 从后往前逐个子控件要求
     * {@code isVisible() && isActive()} ⇒ 藏起来的页里的槽控件根本收不到点击；</li>
     * <li><b>原版那条格子判定（双保险）</b>：LDLib 的 {@code SlotWidget#isEnabled()} 就是
     * {@code isActive() && isVisible()}，而它造的 {@code WidgetSlotItemHandler#isActive()} 正转发到这里；
     * LDLib 落一次槽点击走的是 {@code SlotWidget#mouseClicked} → {@code ModularUIGuiContainer#superMouseClicked}
     * → 原版 {@code AbstractContainerScreen#mouseClicked}，那里**按坐标重新扫 {@code menu.slots}** 并要求
     * {@code Slot#isActive()} ⇒ 即便所有页的格子坐标完全重合（都在同一个 340×232 的网格里），
     * 原版也只会选中当前页的那个格子，不会串页。</li>
     * </ol>
     *
     * <p>⚠️ 页容器**不**跟着 {@code setActive(false)}：LDLib 的 {@code detectAndSendChanges} /
     * {@code updateScreen} 是按 {@code isActive} 过滤子控件的，藏起来的页照旧走同步是**要**的行为
     * （两端的槽内容一致）；真正让格子失效的是上面第 3 条的 {@code isVisible}。
     *
     * <h2>翻页控件</h2>
     * `[◀] 当前页/总页数 [▶]`，摆在最上面那行状态行（{@link #HEADER} = 14px）里、右对齐贴着改名按钮。
     * 选这个位置的依据：**不占网格高度**（另起一行会把面板撑高 12px，正好违反"面板不要再变大"），
     * 而需要翻页时面板必然是满页的 340px 宽，状态行左边只有 ME 状态文本
     * （"Network Status: Online" 这类，约 110px 量级），中间那一大段本来就是空的。箭头用 GTM 现成的
     * {@code GuiTextures.BUTTON_LEFT/BUTTON_RIGHT}（GTMThings 高级终端的线圈步进器就是这个组合）；
     * 页号写成"当前页/总页数"，纯数字，不新增语言文件键（也就不会牵动 datagen）。
     * 点到底再点是循环（与 GTMThings 那边的档位循环一致）。只有页数 &gt; 1 时才加这几个控件，
     * 所以四档现有面板与今天**逐像素相同**。
     *
     * <h2>页内怎么排</h2>
     * 第 p 页放槽位 {@code [p*216, (p+1)*216)}，页内块内**先列后行**
     * （与 GTM 面板一致：{@code x = i%9, y = i/9}）。所以槽号沿着一块从上往下、再换到右面一块、
     * 再翻到下一页，与"总成里的第 N 盘样板"一一对应、不跳号。
     */
    @Override
    public Widget createUIWidget() {
        var inventory = getPatternInventory();
        int capacity = inventory.getSlots();
        int pageCount = Math.max(1, ceilDiv(capacity, PAGE_CAPACITY));
        uiPage = Mth.clamp(uiPage, 0, pageCount - 1);   // 页码兜底（容量变了也不会指到不存在的一页）

        // 面板尺寸按"一页铺满"算（容量不足一页时就用容量本身）：翻页只换页里的内容，尺寸一个像素不动
        int filled = Math.min(capacity, PAGE_CAPACITY);
        int panelBlocks = blocksOf(filled);
        int panelRows = rowsOf(filled, panelBlocks);
        int blockWidth = SLOT * BLOCK_COLUMNS;
        int gridWidth = blockWidth * panelBlocks;
        int gridHeight = SLOT * panelRows;

        int pageWidth = gridWidth + PADDING_X * 2;
        int pageHeight = HEADER + gridHeight + PADDING_BOTTOM;
        var panel = new WidgetGroup(0, 0, pageWidth, pageHeight);

        // 顶部：ME 网络状态 + 改名（固定在面板最上一行，不随网格动）
        panel.addWidget(new LabelWidget(PADDING_X, 2,
                () -> isOnline() ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        panel.addWidget(new AETextInputButtonWidget(pageWidth - PADDING_X - RENAME_WIDTH, 2, RENAME_WIDTH, 10)
                .setText(access().gtet$getCustomName())
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(Component.translatable("gui.gtceu.rename.desc")));

        // 网格区：尺寸固定（= 一页），里面一页一个子容器，只有当前页可见（理由见方法注释）
        var grid = new WidgetGroup(0, 0, gridWidth, gridHeight);
        List<WidgetGroup> pageViews = new ArrayList<>(pageCount);
        for (int p = 0; p < pageCount; p++) {
            int count = Math.min(PAGE_CAPACITY, capacity - p * PAGE_CAPACITY);
            int blocks = blocksOf(count);
            int rows = rowsOf(count, blocks);
            var view = new WidgetGroup(0, 0, gridWidth, gridHeight);
            int index = 0;
            for (int b = 0; b < blocks && index < count; b++) {
                var block = new WidgetGroup(b * blockWidth, 0, blockWidth, gridHeight);
                for (int y = 0; y < rows && index < count; y++) {
                    for (int x = 0; x < BLOCK_COLUMNS && index < count; x++) {
                        int slotIndex = p * PAGE_CAPACITY + index++;
                        block.addWidget(new AEPatternViewSlotWidget(inventory, slotIndex, x * SLOT, y * SLOT)
                                .setOccupiedTexture(GuiTextures.SLOT)
                                .setItemHook(stack -> {
                                    // 编码样板显示成它的产物（与 GTM 的面板一致）
                                    if (!stack.isEmpty() && stack.getItem() instanceof EncodedPatternItem iep) {
                                        ItemStack out = iep.getOutput(stack);
                                        if (!out.isEmpty()) return out;
                                    }
                                    return stack;
                                })
                                .setChangeListener(() -> access().gtet$onPatternChange(slotIndex))
                                .setBackground(GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY));
                    }
                }
                view.addWidget(block);
            }
            view.setVisible(p == uiPage);
            pageViews.add(view);
            grid.addWidget(view);
        }
        grid.setSelfPosition(new Position(PADDING_X, HEADER));
        panel.addWidget(grid);

        // 翻页控件：只有一页时一个都不加（四档现有面板保持原样）
        if (pageCount > 1) {
            // 两个回调都会在两端各跑一次（见 uiPage 的注释）：两端各自把可见页切过去
            Runnable applyPage = () -> {
                for (int i = 0; i < pageViews.size(); i++) pageViews.get(i).setVisible(i == uiPage);
            };
            int nextX = pageWidth - PADDING_X - RENAME_WIDTH - PAGE_RENAME_GAP - PAGE_BUTTON;
            int labelX = nextX - PAGE_GAP - PAGE_LABEL_WIDTH;
            int prevX = labelX - PAGE_GAP - PAGE_BUTTON;
            panel.addWidget(new ButtonWidget(prevX, 1, PAGE_BUTTON, PAGE_BUTTON,
                    new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_LEFT),
                    clickData -> {
                        uiPage = (uiPage + pageCount - 1) % pageCount;
                        applyPage.run();
                    }));
            panel.addWidget(new LabelWidget(labelX, 3, () -> (uiPage + 1) + "/" + pageCount));
            panel.addWidget(new ButtonWidget(nextX, 1, PAGE_BUTTON, PAGE_BUTTON,
                    new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.BUTTON_RIGHT),
                    clickData -> {
                        uiPage = (uiPage + 1) % pageCount;
                        applyPage.run();
                    }));
        }
        return panel;
    }

    /** ⌈a / b⌉（b &gt; 0）。 */
    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** 这么多格子要并几块（1 ~ {@link #MAX_BLOCKS}）。 */
    private static int blocksOf(int count) {
        return Math.max(1, Math.min(MAX_BLOCKS, ceilDiv(count, BLOCK_COLUMNS * MAX_ROWS_PER_BLOCK)));
    }

    /** 这么多格子每块摊几行。 */
    private static int rowsOf(int count, int blocks) {
        return ceilDiv(count, BLOCK_COLUMNS * blocks);
    }

    /**
     * 取父类私有成员的桥（{@code customName} 只有 setter；{@code onPatternChange} 是私有的
     * 唯一变更处理入口）。cast 走 {@code Object} 是因为编译期看不见 mixin 加在父类上的接口。
     */
    private IMEPatternBufferAccess access() {
        return (IMEPatternBufferAccess) this;
    }
}
