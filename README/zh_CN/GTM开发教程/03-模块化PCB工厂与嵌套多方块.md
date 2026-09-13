# 案例三：模块化 PCB 工厂，与「嵌套多方块」的四张脸
> [← 返回目录](<../GTM开发教程(编写中).md>) ｜ [下一节：04 模块化机器代码案例 →](04-模块化机器代码案例.md)

案例二的三种模块化之外还有**路线 D：模块是插进存储槽的物品** —— 它同时决定「用哪套结构」和
「配方等级上限」。主角是 **GTO 的 `PCBFactoryMachine`**（`com.gtocore.common.machine.multiblock.electric.PCBFactoryMachine`，254 行），
对照实现是 **GTCA 的 PCB**（`GTCA-7.5.x\...\electric\PCBFactoryMachine.java`，18 行 + `PCBRecipeCondition`）。

## 1. 主参考：GTO 的 PCB 工厂

```java
public final class PCBFactoryMachine extends StorageMultiblockMachine implements IMultiStructureMachine {

    @Persisted @SyncToClient private int machineTier = 1;

    public PCBFactoryMachine(MetaMachineBlockEntity holder) {
        // 存储槽只收「纳米核心」这类物品：ChemicalHelper.getPrefix(item) == GTOTagPrefix.NANITES
        super(holder, 1, i -> ChemicalHelper.getPrefix(i.getItem()) == GTOTagPrefix.NANITES);
    }

    @Override public void onMachineChanged() {          // 存储槽内容变化
        machineTier = 0;
        MaterialStack stack = ChemicalHelper.getMaterialStack(getStorageStack());
        if (stack.isEmpty()) return;
        Material m = stack.material();
        if      (m == GTMaterials.Gold)        machineTier = 1;
        else if (m == GTOMaterials.Orichalcum) machineTier = 2;
        else if (m == GTOMaterials.Enderium)   machineTier = 3;
        updateCheck();                                  // ⚠️ 换结构全靠这一句
    }

    @Override public BlockPattern getPattern() { return getBlockPattern(machineTier, getDefinition()); }
    @Override public List<BlockPattern> getMultiPattern() {
        return List.of(getBlockPattern(1, getDefinition()), getBlockPattern(2, getDefinition()), getBlockPattern(3, getDefinition()));
    }

    @Override protected Recipe getRealRecipe(@NotNull Recipe recipe) {   // 等级 = 配方上限
        if (machineTier < 2) { if (recipe.getInputEUt() > 30719)  return null; }
        else if (machineTier < 3) { if (recipe.getInputEUt() > 491519) return null; }
        return RecipeModifierFunction.overclocking(this, RecipeModifierFunction.hatchParallel(this, recipe));
    }

    @Override public void customText(List<Component> textList) { /* 面板显示 "PCB工厂等级：X" */ }
}
```

四条要点：

1. **模块物品 = 等级**：`onMachineChanged()` 是「存储槽内容变化」的钩子（GTO 的 `StorageMultiblockMachine`
   提供，构造参数 `(holder, 槽数, ItemStack 过滤器)`）；只认三种材料，其它一律 `machineTier = 0`
   （于是结构检测直接不合格 —— **等级不合法就是"结构不成立"**，不用另外写报错）；
2. **等级决定结构**：三套图案缓存在 `Int2ObjectOpenHashMap` 里（`computeIfAbsent` 懒构建），
   `getPattern()` 按 `machineTier` 取当前那套、`getMultiPattern()` 三套都交给 JEI 预览；
   **改完 tier 必须 `updateCheck()`**，否则 GTM 不会重判结构；
3. **等级决定配方上限**：在 `getRealRecipe(...)` 里「超了就返回 `null`」——配方照常存在、
   在 JEI 里照常显示、装进机器也匹配得上，只是这一档机器跑不了（`null` = 这条配方对本机不可用）；
4. **修改器是函数式链**：`RecipeModifierFunction.overclocking(this, hatchParallel(this, recipe))` ——
   超频与并行各自是一个可组合的函数，比一个巨型 `recipeModifier` 好读得多。

**GTET 里的等价实现**（不依赖 gtolib）：GTO 的 `StorageMultiblockMachine` 换成自己挂一个
`NotifiableItemStackHandler(this, 1, IO.NONE).setFilter { … }`，并**覆盖它的 `onContentsChanged()`**
（`NotifiableItemStackHandler#onContentsChanged` 是 public）当作「模块槽变化」的钩子；
`machineTier` / `getPattern()` 的逻辑照搬，`updateCheck()` 换成 GTET 这边的 `requestCheck()`
（结构刷新工具就是这么触发重检的）。

## 2. 同一个需求，两种「等级卡配方」

GTCA 的 PCB 走另一条路：机器只有 18 行（`extends WorkableElectricMultiblockMachine implements ITieredMachine`，
tier 由注册时的构造参数定死），等级需求交给一条**自定义 `RecipeCondition`**（`PCBRecipeCondition`，
MK I/II/III 对应 8/9/10）：

```java
@Override protected boolean testCondition(@NotNull GTRecipe recipe, @NotNull RecipeLogic logic) {
    if (logic.machine instanceof PCBFactoryMachine pcb) return pcb.getTier() >= tier;
    return false;
}
```

| | GTO：逻辑层返回 `null` | GTCA：自定义 `RecipeCondition` |
|---|---|---|
| JEI 里看到什么 | 配方照常显示 | 配方带条件（可显示「需要 MK2 机壳」） |
| 玩家能看到的原因 | 得靠 `customText` 自己写等级 | 进 `failureReasons`，Jade 直接显示 |
| 适合 | 「等级 = 产能上限」这类内部规则 | 「等级 = 配方需求」这种要写给玩家看的东西 |

GTET 的选择：**要进 Jade 的原因就用 `RecipeCondition`**（GTM 的 `RecipeCondition` 自带序列化与 JEI 显示），
**纯内部上限就在 `getRealRecipe` / `doModifyRecipe` 里返回 `null`**。

## 3. 「嵌套多方块」的四张脸

| 形态 | 是什么 | 参考实现 |
|---|---|---|
| ① 部件本身是控制器 | 核心把「另一个多方块的控制器」当部件扫描 | GTO 太空站模块（`DataKeys.SPACE_MACHINE` 收集的 `IWorkInSpaceMachine`） |
| ② 模块是独立多方块 | 模块自己成型，挂到核心上 | GTO `Extension`/`Core`；GTCA 电梯模块（案例二） |
| ③ 结构里的「模块方块」喂数据 | 结构里的方块/管道/组件数量决定等级与倍率 | GTO `ME_STORAGE_CORE`、`WIRELESS_ENERGY_UNIT`、`FISSION_COMPONENT`、`SPEED_PIPE`、`STEEL_FRAME`、`COMPUTER_CASING_TIER`… |
| ④ 一台控制器多套结构 | 同一台机器按条件换图案 | GTO `IMultiStructureMachine`（PCB 工厂、`CompoundExtremeCoolingMachine`、`NanoForgeMachine`…） |

**① 和 ③ 用的是同一个机制，也是 GTO 最值得偷的一招**：在图案里收集数据 → 成型时从 `matchContext` 读出来，
不需要坐标表、也不需要双方握手。GTO 把它扩展成了任意类型的 `DataComponentKey`：

```java
// 1) 声明数据键（GTOPredicates.DataKeys）
public static final DataComponentKey<Collection<IWorkInSpaceMachine>> SPACE_MACHINE = createCollection("spaceMachine");

// 2) 图案里的 predicate 负责收集；3) 机器成型时读出来
default void onFormed() {
    if (getSpaceMachines() != null) {                        // ⚠️ 先清掉上一次的绑定
        getSpaceMachines().forEach(r -> { r.setCleanroom(null); r.setWorkspaceProvider(null); });
        setSpaceMachines(null);
    }
    setSpaceMachines(getMultiblockState().getMatchContext()
            .getOrDefault(GTOPredicates.DataKeys.SPACE_MACHINE, Collections.emptyList()));
    getSpaceMachines().forEach(receiver -> {
        receiver.setCleanroom(this);
        receiver.setWorkspaceProvider(this);
        if (receiver instanceof IEnhancedRecipeLogicMachine enhanced) {
            enhanced.getRecipeLogic().updateTickSubscription();   // ⚠️ 唤醒被绑定方的配方逻辑
        }
    });
}
default void onInvalid() { /* 逐个解绑，再置空 */ }
```

⚠️ **最容易漏的是最后那句 `updateTickSubscription()`**：GTM 的配方逻辑在「没活干」时会**自己退订** tick
（`keepSubscribing()` 返回 false 的机器尤其明显），你把它当模块绑定、给它供能供料之后**必须喊它一声**，
否则它会一直躺着不动 —— GTCA 是用「模块看门狗每 tick 检查开关」解决同一件事的。

**GTET 侧怎么落地（GTM 7.5.3 的原生 API 就够，不需要 GTO 的注解体系）**：

- 数据容器是 `PatternMatchContext`（`MultiblockState#getMatchContext()`，**每次检测前会 `reset()`**），
  它有 `set(key, value)` / `get(key)` / `getOrCreate(key, creator)` / `increment(key, n)`；
  GTM 自己在 `SimplePredicate` 里就是这么用的（`SimplePredicate.java:124/146` 的 `renderMask`、`slots`）；
- 收集方：写一个自定义 `SimplePredicate(Predicate<MultiblockState>, Supplier<BlockInfo[]>)` 挂进图案，
  在它的 `test(...)` 里 `state.getMatchContext().getOrCreate("gtet_modules", …)` 收集；
- 读取方：`onStructureFormed()` 里 `getMultiblockState().getMatchContext().get("gtet_modules")`；
- **能只收坐标/数值就别收实例**：只存 `BlockPos` / `Integer` 的键不会持有机器引用，
  解绑工作直接省一半（GTO 里 `SPACE`、`SPEED_PIPE`、`STEEL_FRAME` 就是这种"只存数据"的键）。

## 4. GTET 落地的推荐顺序

1. **形态 ④**（一控制器多套结构）：改动最小，`getPattern()` + 换档后 `requestCheck()` 就能跑；
2. **形态 ③**（结构方块喂数据）：用一个自己的数据键 + 自定义谓词，等级/倍率全部在结构里表达；
3. **形态 ②**（核心 + 模块）：照 GTCA 那套（位置即契约 + 判定链 + 开关下发 + 分电，见案例二）；
4. **形态 ①**（部件是控制器）：最后做，它要求把上面的绑定/解绑/唤醒路径都理顺。

---

[← 上一节：02 模块化的多方块](02-模块化的多方块.md) ｜ [返回目录](<../GTM开发教程(编写中).md>) ｜ [下一节：04 模块化机器代码案例 →](04-模块化机器代码案例.md)
