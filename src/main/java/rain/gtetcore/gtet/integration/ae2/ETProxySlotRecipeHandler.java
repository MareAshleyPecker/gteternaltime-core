package rain.gtetcore.gtet.integration.ae2;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerGroupDistinctness;
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerList;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine.InternalSlot;
import com.lowdragmc.lowdraglib.syncdata.ISubscription;
import lombok.Getter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import rain.gtetcore.gtet.common.machine.multiblock.part.ETMEPatternBufferPartMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「ME 样板总成镜像」的配方处理代理表：**每个样板槽一份 {@link RecipeHandlerList}**，
 * 把多方块要吃的输入转发到宿主总成对应槽里。
 *
 * <h2>为什么要每个槽一份（不能合并成一份）</h2>
 * 总成本体是用 {@code InternalSlotRecipeHandler} 一格一份 RHL 建的表，并声明
 * {@code isDistinct() == true} / 分组 {@code BUS_DISTINCT}：多方块的配方逻辑据此把**每个样板槽**
 * 当成一条独立的输入总线（GTM 的样板总成就是靠这个「一格一份」来同时供多种输入的）。
 * 镜像若把 N 个槽并成一份，distinct 语义就变了，某些需要「来自不同总线」的配方会判不出来。
 * 所以这里与 GTM 的 {@code ProxySlotRecipeHandler} 同构：一份 RHL 里放 5 个转发器
 * （电路槽 / 共享库存 / 本槽物品 / 共享流体仓 / 本槽流体）。
 *
 * <h2>与 GTM 那版唯一的实现差别：转发目标下沉到 InternalSlot</h2>
 * GTM 的镜像转发到它自己的 {@code InternalSlotRecipeHandler.SlotRHL#getItemRecipeHandler()}，
 * 但 {@code SlotRHL} 是 **protected 嵌套类**，跨包既不能点名也不能继承
 * （GTOCore / BetterGregTechAndAppliedEnergistics 那两家的解法是**连 InternalSlotRecipeHandler
 * 一起复制一份**，我们不做）。这里改成直接吃 public 的 {@link InternalSlot}：
 * {@code handleItemInternal}/{@code handleFluidInternal}/{@code getItems}/{@code getFluids}
 * 都是 public，行为与 GTM 的 {@code SlotItemRecipeHandler}/{@code SlotFluidRecipeHandler}
 * 逐个对应（含 {@code HIGH + 槽号 + 1} 的优先级与「空槽直接放过」的短路）。
 * 坏处是多了一层自己的转发器对象，好处是一行 GTM 代码都不用复制。
 *
 * <h2>表为什么按「已登记的最大容量」一次建死（**本类的核心取舍**）</h2>
 * 镜像只有 LuV 一件，却要能连 27 / 63 / 126 / 216 任何一档的总成，所以表长在**构造期**就得
 * 取一个"够所有档位用"的值（{@link ETPatternBufferCapacities#maxCapacity()}）。
 * 为什么不能在「连上宿主的时刻」按宿主的实际容量重建表 —— 这是本轮专门核实的点，证据链：
 * <ol>
 * <li>{@code WorkableMultiblockMachine#onStructureFormed}（源码 121-141 行）在**成型那一刻**
 * 遍历 {@code getParts()}、拿走每个部件的 {@code getRecipeHandlers()}，逐个
 * {@code this.addHandlerList(handlerList)} 并订阅；</li>
 * <li>{@code IRecipeCapabilityHolder#addHandlerList}（源码 37-47 行）把**这一批 RHL 对象与它们
 * 内部的 {@code IRecipeHandler} 引用**拷进控制器的 {@code capabilitiesProxy} /
 * {@code capabilitiesFlat} 两张表；</li>
 * <li>多方块侧全项目只有 {@code onStructureFormed} 这一处调用它（`grep addHandlerList` 只有
 * WorkableMultiblockMachine:138 / 153 两行是多方块路径），{@code onStructureInvalid} 才会清表。
 * 也就是说：**成型之后再改我们返回的 List，控制器不会重新收集**，只会继续拿旧对象。</li>
 * </ol>
 * 好消息是"收集的是对象、不是快照值"：所以只要**对象本身**在，绑定时改它们的目标指针就立刻生效
 * （这也是 GTM 自己 {@code ProxySlotRecipeHandler} 的做法）。
 * 于是方案是：表按最大容量建死，绑定时前 N 个指到宿主的 N 个槽，**宿主用不到的格子整条解绑**
 * （与 GTM 未绑定 ProxyRHL 同语义：{@code handleRecipeInner} 原样返回、{@code getSize()==0}、
 * {@code getContents()} 为空、优先级 LOW），于是它们既不会转发也不会订阅任何东西。
 *
 * <h3>代价（诚实记账）</h3>
 * 低档总成（27）配这件镜像时，控制器里会多出 216-27=189 条**空** RHL —— 每次配方匹配/并行检查
 * 都会各自被"试一次然后跳过"。同样的量级 GTM 自己也吃：{@code InternalSlotRecipeHandler}
 * 是**一槽一条 RHL**，所以 216 档总成本来就有 216 条。语义上无影响（空 RHL 在
 * {@code RecipeRunner#handleContents} 的 BUS_DISTINCT 循环里永远返回未消耗完的 left → continue，
 * 在 {@code ItemRecipeCapability#getInputContents} 里因内容为空被跳过）。
 * <p>
 * 被否掉的另一条路：绑定时重建一个"正好等于宿主容量"的表，然后手动让控制器重收集
 * （{@code MultiblockControllerMachine#checkPattern()} 是 public、{@code onStructureFormed()}
 * 会 clear 两张表再收）。不做，因为：它要在**玩家插闪存**这一刻跑一次完整结构校验并重放
 * 部件的成型/失效回调（{@code onStructureInvalid} → {@code recipeLogic.resetRecipeLogic()} 等），
 * 而镜像**未必已经成型**（先绑后建是常规玩法），两条时序都得兜；相比之下"建大表"只是一点空遍历。
 *
 * @author rain fox
 */
public final class ETProxySlotRecipeHandler {

    @Getter
    private final List<RecipeHandlerList> proxySlotHandlers;

    /**
     * 按 {@link ETPatternBufferCapacities#maxCapacity()}（= 所有已登记档位的最大样板槽位数）
     * 建好整张表。
     *
     * @param machine 镜像机器（转发器要挂在它身上：配方逻辑是按部件找 handler 的）
     */
    public ETProxySlotRecipeHandler(MetaMachine machine) {
        int slots = ETPatternBufferCapacities.maxCapacity();
        proxySlotHandlers = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            proxySlotHandlers.add(new ProxyRHL(machine, i));
        }
    }

    /** 本表能转发多少个样板槽（= 已登记的最大容量，也是 {@code getProxySlotHandlers().size()}）。 */
    public int getSlotCount() {
        return proxySlotHandlers.size();
    }

    /**
     * 绑定宿主：第 i 个镜像槽 → 宿主第 i 个槽；**宿主没有的格子（i ≥ 宿主容量）整条解绑**。
     *
     * <p>因为表按最大容量建死，低档宿主天然只用到前面一段，剩下的格子保持"空代理"，
     * 这正是 GTM 未绑定 ProxyRHL 的语义，不再需要按档警告（见
     * {@code ETMEPatternBufferProxyPartMachine} 的类注释）。
     *
     * @param buffer 宿主总成（本 mod 的多阶段样板总成）
     */
    public void updateProxy(ETMEPatternBufferPartMachine buffer) {
        var slots = buffer.getInternalInventory();
        for (int i = 0; i < proxySlotHandlers.size(); i++) {
            ((ProxyRHL) proxySlotHandlers.get(i)).bind(buffer, i < slots.length ? slots[i] : null);
        }
    }

    /** 解绑：全部退回空代理（清闪存绑定 / 拆方块时用）。 */
    public void clearProxy() {
        for (var handler : proxySlotHandlers) {
            ((ProxyRHL) handler).unbind();
        }
    }

    /**
     * 一份「槽级」代理：5 个转发器 + 挂在宿主槽上的内容变化回调。
     */
    private static final class ProxyRHL extends RecipeHandlerList {

        private final ProxyItemHandler circuit;
        private final ProxyItemHandler sharedItem;
        private final SlotItemHandler slotItem;
        private final ProxyFluidHandler sharedFluid;
        private final SlotFluidHandler slotFluid;

        /** 当前绑定的宿主槽（解绑时要按它还原回调）。 */
        private @Nullable InternalSlot boundSlot;
        /** 绑定前宿主槽上的内容变化回调，解绑时原样装回去。 */
        private @Nullable Runnable boundSlotPreviousCallback;
        /** 我们装上去的那层回调，用来判断「链顶还是不是自己」。 */
        private @Nullable Runnable boundSlotCallback;

        ProxyRHL(MetaMachine machine, int index) {
            super(IO.IN);
            circuit = new ProxyItemHandler(machine);
            sharedItem = new ProxyItemHandler(machine);
            slotItem = new SlotItemHandler(machine, index);
            sharedFluid = new ProxyFluidHandler(machine);
            slotFluid = new SlotFluidHandler(machine, index);
            addHandlers(circuit, sharedItem, slotItem, sharedFluid, slotFluid);
            setGroup(RecipeHandlerGroupDistinctness.BUS_DISTINCT);
        }

        void bind(ETMEPatternBufferPartMachine buffer, @Nullable InternalSlot slot) {
            // 宿主没有这一格（低档总成配这件通用镜像的常规情形）：整条解绑 ——
            // 共享设施也不挂，免得给宿主的电路槽/共享库存白加几百个内容变化订阅。
            if (slot == null) {
                unbind();
                return;
            }
            // 宿主总成的三件共享设施：电路槽、共享库存、共享流体仓（与 GTM 的 SlotRHL 装的是同一批对象）
            circuit.setProxy(buffer.getCircuitInventory());
            sharedItem.setProxy(buffer.getShareInventory());
            sharedFluid.setProxy(buffer.getShareTank());

            restoreSlotCallback(); // 重新绑定时先还原，避免回调层层叠加
            boundSlot = slot;
            slotItem.setSlot(slot);
            slotFluid.setSlot(slot);

            // ⚠️ InternalSlot 只有一个 onContentsChanged 回调位，GTM 自己的
            // SlotItemRecipeHandler/SlotFluidRecipeHandler 也在这里装了回调（后装的把先装的顶掉）。
            // 我们**套娃**而不是覆盖：先跑原有回调，再通知本镜像的两个转发器；
            // 解绑时把这一层摘掉（GTM 那版是直接覆盖、解绑也不还原）。
            var previous = slot.getOnContentsChanged();
            Runnable layer = () -> {
                previous.run();
                slotItem.notifyListeners();
                slotFluid.notifyListeners();
            };
            boundSlotPreviousCallback = previous;
            boundSlotCallback = layer;
            slot.setOnContentsChanged(layer);
        }

        void unbind() {
            circuit.setProxy(null);
            sharedItem.setProxy(null);
            sharedFluid.setProxy(null);
            restoreSlotCallback();
            boundSlot = null;
            slotItem.setSlot(null);
            slotFluid.setSlot(null);
        }

        /**
         * 摘掉本层回调。
         * <p>
         * ⚠️ 只在「链顶仍是自己」时还原：同一个总成上可以挂多个镜像，它们会在**同一批槽**上层层套娃，
         * 若某个先解绑就无条件还原，会把后来者的那一层一起抹掉（它就不再收到内容变化通知，
         * 多方块可能要等到别的触发点才重新判定配方）。不是链顶就留着不管 —— 本层转到空槽后
         * 只是空通知，不再有任何副作用。
         */
        private void restoreSlotCallback() {
            if (boundSlot != null && boundSlotPreviousCallback != null && boundSlotCallback != null &&
                    boundSlot.getOnContentsChanged() == boundSlotCallback) {
                boundSlot.setOnContentsChanged(boundSlotPreviousCallback);
            }
            boundSlotPreviousCallback = null;
            boundSlotCallback = null;
        }

        @Override
        public boolean isDistinct() {
            return true;
        }

        /** 与 GTM 一致：镜像槽**永远**是 distinct 总线，配方逻辑不许改这一位。 */
        @Override
        public void setDistinct(boolean ignored, boolean notify) {}
    }

    /**
     * 转发到宿主总成**现成的**配方处理器（电路槽 / 共享库存 / 共享流体仓）。
     *
     * <p>这三个都是 {@code NotifiableRecipeHandlerTrait}，所以照 GTM 的做法订阅它们的
     * 内容变化（多方块据此重新判定配方），绑定/解绑时换目标指针即可，表中对象不重建。
     */
    private static final class ProxyItemHandler extends NotifiableRecipeHandlerTrait<Ingredient> {

        private @Nullable IRecipeHandlerTrait<Ingredient> proxy;
        private @Nullable ISubscription proxySub;

        ProxyItemHandler(MetaMachine machine) {
            super(machine);
        }

        void setProxy(@Nullable IRecipeHandlerTrait<Ingredient> newProxy) {
            if (proxySub != null) {
                proxySub.unsubscribe();
                proxySub = null;
            }
            proxy = newProxy;
            if (newProxy != null) {
                proxySub = newProxy.addChangedListener(this::notifyListeners);
            }
        }

        @Override
        public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> left, boolean simulate) {
            if (proxy == null) return left;
            return proxy.handleRecipeInner(io, recipe, left, simulate);
        }

        @Override
        public int getSize() {
            return proxy == null ? 0 : proxy.getSize();
        }

        @Override
        public @NotNull List<Object> getContents() {
            return proxy == null ? Collections.emptyList() : proxy.getContents();
        }

        @Override
        public double getTotalContentAmount() {
            return proxy == null ? 0 : proxy.getTotalContentAmount();
        }

        @Override
        public RecipeCapability<Ingredient> getCapability() {
            return ItemRecipeCapability.CAP;
        }

        @Override
        public IO getHandlerIO() {
            return IO.IN;
        }

        @Override
        public int getPriority() {
            return proxy == null ? IFilteredHandler.LOW : proxy.getPriority();
        }

        @Override
        public boolean isDistinct() {
            return true;
        }
    }

    /** {@link ProxyItemHandler} 的流体版。 */
    private static final class ProxyFluidHandler extends NotifiableRecipeHandlerTrait<FluidIngredient> {

        private @Nullable IRecipeHandlerTrait<FluidIngredient> proxy;
        private @Nullable ISubscription proxySub;

        ProxyFluidHandler(MetaMachine machine) {
            super(machine);
        }

        void setProxy(@Nullable IRecipeHandlerTrait<FluidIngredient> newProxy) {
            if (proxySub != null) {
                proxySub.unsubscribe();
                proxySub = null;
            }
            proxy = newProxy;
            if (newProxy != null) {
                proxySub = newProxy.addChangedListener(this::notifyListeners);
            }
        }

        @Override
        public List<FluidIngredient> handleRecipeInner(IO io, GTRecipe recipe, List<FluidIngredient> left,
                                                      boolean simulate) {
            if (proxy == null) return left;
            return proxy.handleRecipeInner(io, recipe, left, simulate);
        }

        @Override
        public int getSize() {
            return proxy == null ? 0 : proxy.getSize();
        }

        @Override
        public @NotNull List<Object> getContents() {
            return proxy == null ? Collections.emptyList() : proxy.getContents();
        }

        @Override
        public double getTotalContentAmount() {
            return proxy == null ? 0 : proxy.getTotalContentAmount();
        }

        @Override
        public RecipeCapability<FluidIngredient> getCapability() {
            return FluidRecipeCapability.CAP;
        }

        @Override
        public IO getHandlerIO() {
            return IO.IN;
        }

        @Override
        public int getPriority() {
            return proxy == null ? IFilteredHandler.LOW : proxy.getPriority();
        }

        @Override
        public boolean isDistinct() {
            return true;
        }
    }

    /**
     * 转发到宿主总成**某个样板槽**（{@link InternalSlot}）。
     *
     * <p>行为对齐 GTM 的 {@code InternalSlotRecipeHandler.SlotItemRecipeHandler}：
     * 优先级 {@code IFilteredHandler.HIGH + 槽号 + 1}、只吃 {@code IO.IN}、空槽直接放过、
     * {@code getSize()} 恒为 81（GTM 那个常量原样对齐，它在"能看到的条目数"语义上用）。
     */
    private static final class SlotItemHandler extends NotifiableRecipeHandlerTrait<Ingredient> {

        /** GTM {@code SlotItemRecipeHandler#size} 的取值，原样对齐。 */
        private static final int REPORTED_SIZE = 81;

        private final int priority;
        private @Nullable InternalSlot slot;

        SlotItemHandler(MetaMachine machine, int index) {
            super(machine);
            this.priority = IFilteredHandler.HIGH + index + 1;
        }

        void setSlot(@Nullable InternalSlot newSlot) {
            this.slot = newSlot;
        }

        @Override
        public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> left, boolean simulate) {
            if (io != IO.IN || slot == null || slot.isItemEmpty()) return left;
            return slot.handleItemInternal(left, simulate);
        }

        @Override
        public int getSize() {
            return slot == null ? 0 : REPORTED_SIZE;
        }

        @Override
        public @NotNull List<Object> getContents() {
            return slot == null ? Collections.emptyList() : new ArrayList<>(slot.getItems());
        }

        @Override
        public double getTotalContentAmount() {
            if (slot == null) return 0;
            return slot.getItems().stream().mapToLong(ItemStack::getCount).sum();
        }

        @Override
        public RecipeCapability<Ingredient> getCapability() {
            return ItemRecipeCapability.CAP;
        }

        @Override
        public IO getHandlerIO() {
            return IO.IN;
        }

        @Override
        public int getPriority() {
            return slot == null ? IFilteredHandler.LOW : priority;
        }

        @Override
        public boolean isDistinct() {
            return true;
        }
    }

    /** {@link SlotItemHandler} 的流体版。 */
    private static final class SlotFluidHandler extends NotifiableRecipeHandlerTrait<FluidIngredient> {

        private static final int REPORTED_SIZE = 81;

        private final int priority;
        private @Nullable InternalSlot slot;

        SlotFluidHandler(MetaMachine machine, int index) {
            super(machine);
            this.priority = IFilteredHandler.HIGH + index + 1;
        }

        void setSlot(@Nullable InternalSlot newSlot) {
            this.slot = newSlot;
        }

        @Override
        public List<FluidIngredient> handleRecipeInner(IO io, GTRecipe recipe, List<FluidIngredient> left,
                                                      boolean simulate) {
            if (io != IO.IN || slot == null || slot.isFluidEmpty()) return left;
            return slot.handleFluidInternal(left, simulate);
        }

        @Override
        public int getSize() {
            return slot == null ? 0 : REPORTED_SIZE;
        }

        @Override
        public @NotNull List<Object> getContents() {
            return slot == null ? Collections.emptyList() : new ArrayList<>(slot.getFluids());
        }

        @Override
        public double getTotalContentAmount() {
            if (slot == null) return 0;
            return slot.getFluids().stream().mapToLong(FluidStack::getAmount).sum();
        }

        @Override
        public RecipeCapability<FluidIngredient> getCapability() {
            return FluidRecipeCapability.CAP;
        }

        @Override
        public IO getHandlerIO() {
            return IO.IN;
        }

        @Override
        public int getPriority() {
            return slot == null ? IFilteredHandler.LOW : priority;
        }

        @Override
        public boolean isDistinct() {
            return true;
        }
    }
}
