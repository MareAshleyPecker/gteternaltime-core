# GTM 开发教程（编写中）

## 案例一：一台机器是怎么「跑起来」的

> **本教程里说的「机器逻辑」，就是一句话：跑这个配方需要什么条件。**
> 代码里也照这个写：每个机器的 KDoc 先把这句话写在最上面（例：净化单元是「附近 ≤32 格有成型的主机
> + 配方等级不超过主机 + 付得起每 tick 的电 → 才跑」），再往下才是怎么实现。
> GTO 的净化水系统就是这么分工的：条件写在单元里、`IdleReason` 把"为什么没跑"报给玩家。

场景：做一个**催化研磨机**（`catalyst_macerator`）—— 继承 GTM 现成的单方块电力机器，
只多一条自己的规矩：**机器里必须放一个催化剂，没有就不开工；每跑完一个配方催化剂掉 1 点耐久，用坏停机。**

读完这一节你应该能回答三个问题：**它怎么运作、什么条件下才跑、除了写这个类还得干什么。**
所有 API 都按 GTM 7.5.3 源码核对过（方法名后面的行号是 `GregTech-Modern` 里的位置）。

---

## 一、如何运作

### 1. 三条线各管一段

| 谁 | 管什么 | 在本项目里的例子 |
|---|---|---|
| `MetaMachine` 子类 | 方块实体本体：字段同步、能力容器、UI、生命周期 | `ThreadHatchPartMachine` |
| `MachineTrait` 子类 | 挂在机器上的「器官」：物品/流体/能量/配方逻辑 | `NotifiableItemStackHandler`、`RecipeLogic` |
| `RecipeLogic` | 配方状态机：找配方 → 扣料 → 计时 → 出料 | `ThreadedRecipeLogic`（GTET 的多线程内核） |

机器**不自己 tick 配方逻辑**：`RecipeLogic` 是 trait，机器每 tick 调 `serverTick()`（`RecipeLogic.java:206`）。

### 2. 四个状态

```java
IDLE     // 空转：没在跑，也没找到能跑的配方
WORKING  // 正在跑：progress 每 tick +1
WAITING  // 找到了配方但这一刻跑不动（没电 / tick 输入不够 / 输出满），progress 暂停甚至回退
SUSPEND  // 挂起：多方块连续 5 次没电，或玩家/红石手动停机
```
状态是 `@Persisted @DescSynced` 的，`setStatus()`（:414）里还会顺手做三件事：刷新渲染态
（`RECIPE_LOGIC_STATUS` 模型属性 → 机器贴图切 working 动画）、重订 tick、通知机器 `notifyStatusChanged`。

### 3. 一个配方的完整生命周期

`serverTick()`（:206）每 tick 只看三件事，对应三条分支：

```
progress < duration  → handleRecipeWorking()   // 干活：判条件 + 扣 tick 输入 + progress++
progress >= duration → onRecipeFinish()        // 结算：出料 + 立刻尝试下一个
lastRecipe == null   → findAndHandleRecipe()   // 找活干（keepSubscribing() 为真时每 5 tick 一次）
```

顺着走一遍：

1. **找候选** `findAndHandleRecipe()`（:338）→ 从 `RecipeManager` 里按机器的配方类型与输入筛出可能匹配的配方；
2. **套修改器** `machine.fullModifyRecipe(match)`（:261）→ 这里是**超频 / 并行 / 其他改动配方**的入口
   （`IRecipeLogicMachine#doModifyRecipe` 默认调 `definition.getRecipeModifier()`）。返回 `null` = 这条配方被判不可用；
3. **判条件 + 判内容** `checkRecipe(modified)`（:253）= `RecipeHelper.checkConditions(...)`（配方自带的 `RecipeCondition`）
   ＋ `RecipeHelper.matchContents(...)`（**模拟**扣料：输入够不够、输出放不放得下、tick 输入输出也一并模拟）；
4. **开工** `setupRecipe(recipe)`（:390）→ 先问 `machine.beforeWorking(recipe)`，返回 `false` 就回 IDLE；
   通过则 `handleRecipeIO(recipe, IO.IN)` **真扣料**，然后 `lastRecipe = recipe`、`progress = 0`、
   `duration = recipe.duration`、状态置 WORKING；
5. **每 tick 干活** `handleRecipeWorking()`（:278）→ 重新 `checkConditions` → `handleTickRecipe()`（tick 输入/输出，
   **EU 消耗就在这一层**）→ `machine.onWorking()`（返回 `false` 直接 `interruptRecipe()` 打断）→ `progress++`；
6. **结算** `onRecipeFinish()`（:507）→ `machine.afterWorking()` → `handleRecipeIO(recipe, IO.OUT)` **真出料**
   → `alwaysTryModifyRecipe()` 为真时用 `lastOriginRecipe` 重新套一遍修改器 → 立刻 `checkRecipe(lastRecipe)`，
   通过了就再 `setupRecipe`（**连续跑，不经过 IDLE**）；
7. **退订**：不需要 tick 时 `serverTick` 会退订自己（:230-246），由 `updateTickSubscription()`（:184）
   在状态变化 / 输入变化（`WorkableTieredMachine#onLoad` 里把各类 handler 的变更订阅到这个方法）时重新订上。
   `keepSubscribing()` 返回 `false` 的机器要自己记得在合适的时机喊它——否则会「有料也不动」。

### 4. 「找的时候判」和「跑的时候判」是两套

同一个配方会被判两遍，而且判的东西不同：

- 找的时候：`checkConditions` + `matchContents`（模拟）——决定**要不要开工**；
- 跑的时候：`checkConditions` + `handleTickRecipe`（真实扣 EU / tick 输入）+ `onWorking()`——决定**要不要继续**。

所以「我的条件」要挂哪，取决于「不满足时应该不开工，还是中途停下」。

---

## 二、运作条件

### 1. 三道关卡

| 关卡 | 代码位置 | 不满足的结果 |
|---|---|---|
| 配方类型对不对 | `getRecipeTypes()` / `getRecipeType()`（`WorkableTieredMachine` 从 definition 读） | 候选集为空，永远 IDLE |
| 配方自带条件 | `recipe.conditions` → `RecipeHelper.checkConditions`（:253） | WAITING/IDLE，原因进 `failureReasons`（Jade 会显示） |
| 内容匹配 | `RecipeHelper.matchContents`（:239） | 同上，原因是 `insufficient_in` / `insufficient_out` |

配方条件支持「与 / 或」两组（`RecipeCondition#isOr()`），GTM 自带的有电压等级、研究、维度、生物群系、清洁室等。

### 2. 运行时条件（每 tick）

- **电**：由 EU 能力走 **tick 输入**。没电 → `setWaiting(reason)`；且如果失败的是
  `IO.IN + EURecipeCapability`，会累计 `runAttempt`（上限 5），并从 `runDelay = runAttempt * 60` 开始退避
  （:295-318）。多方块在 5 次之后还会直接进 `SUSPEND`——除非装了「防断电」的机器控制覆盖板。
- **`machine.onWorking()`**：每 tick 都被问一次，返回 `false` 就 `interruptRecipe()`（:285-288）。
- **`isWorkingEnabled()`**：软锤 / 红石 / UI 的开关，`false` 时整个逻辑不动。
- **`isRecipeLogicAvailable()`**：`false` 时机器自己决定「先别跑」。
- **`regressWhenWaiting()`**：等待时进度是否回退（默认跟着 definition，多方块通常是 true）。

### 3. 想加自己的条件，挂在哪

| 你想表达的 | 挂哪 | 行为 |
|---|---|---|
| 不满足就不开工 | `beforeWorking(recipe)` | `setupRecipe` 里判，`false` → 状态回 IDLE，不扣料 |
| 跑到一半不满足了要停下 | `onWorking()` | `false` → `interruptRecipe()`，**已扣的料不退** |
| 开工/收工时的副作用（耗材、播音效、统计） | `beforeWorking` / `afterWorking` | `afterWorking` 在**出料之前**调用 |
| 内容层面的额外规矩（例如「输出槽必须空」） | 覆盖 `RecipeLogic#matchRecipe` / `checkRecipe` | 属于「内容匹配」的一部分 |
| 要改流程本身（多条线程、冷却、批量） | 换掉整个 `RecipeLogic` | 见下面第三节第 3 点 |

---

## 三、需要额外干什么

### 1. 选基类

| 基类 | 拿到什么 | 什么时候用 |
|---|---|---|
| `SimpleTieredMachine` | EU 容器、物品输入/输出、流体罐、自动输出、幽灵电路、FancyUI、**现成的机器界面** | 单方块配方机器（**本案例**） |
| `WorkableTieredMachine` | 上面去掉 UI/自动输出 | 想要自己画界面 |
| 自己实现 `IRecipeLogicMachine` | 什么都没有 | 机器不是「耗电跑配方」这套（泵、采矿机等） |

### 2. 机器类里必须交代的东西

```kotlin
class CatalystProcessorMachine(holder: IMachineBlockEntity, tier: Int) :
    SimpleTieredMachine(holder, tier, TANK_SIZE) {

    /** 催化剂槽：机器自己用，不参与配方 IO（IO.NONE）。构造期建好就自动挂到机器上
     *  —— `MachineTrait` 的构造器里有一句 `machine.attachTraits(this)`。 */
    val catalystSlot = NotifiableItemStackHandler(this, 1, IO.NONE)

    /** 催化剂剩余耐久：`@Persisted` 才进 NBT（拆机重装、存档重载都不丢）。 */
    @Persisted
    var catalystLife: Int = 0
        private set

    /** 换配方逻辑：父类构造期就会调到这里，所以**不能**读上面那两个字段（还没初始化）。 */
    override fun createRecipeLogic(vararg args: Any?): RecipeLogic = CatalystRecipeLogic(this)

    /** 有 `@Persisted` 字段就要接上字段持有者，否则父类的 `@DescSynced` 字段会一起失效。 */
    override fun getFieldHolder(): ManagedFieldHolder = MANAGED_FIELD_HOLDER

    companion object {
        /** 流体罐容量（本机没有流体配方，但基类构造器要这个函数）。 */
        private val TANK_SIZE = object : Int2IntFunction {
            override fun applyAsInt(tier: Int): Int = 16000 * (1 shl tier)
        }

        /** 挂在 `SimpleTieredMachine.MANAGED_FIELD_HOLDER` 后面（写法照 `ThreadHatchPartMachine`）。 */
        @JvmField
        val MANAGED_FIELD_HOLDER: ManagedFieldHolder =
            ManagedFieldHolder(CatalystProcessorMachine::class.java, SimpleTieredMachine.MANAGED_FIELD_HOLDER)
    }
}
```

> ⚠️ Kotlin 写 `@Persisted` 直接标在属性上即可（LDLib 那个注解的 `@Target` 是 `FIELD`，Kotlin 会自动落到幕后字段）。
> 字段持有者不接父类 = 状态同步与存档静默失效，这是最常见的「看着能跑、一重载就丢」的原因。

### 3. 自己的条件与自己的流程

条件（不满足就不开工 / 中途停下）写在**机器**上，因为 `RecipeLogic` 调的就是机器的钩子：

```kotlin
class CatalystProcessorMachine /* 同上面 */ {
    /** 开工前：催化剂槽空着、或耐久用光 → 不开工。 */
    override fun beforeWorking(recipe: GTRecipe?): Boolean {
        if (catalystSlot.getStackInSlot(0).isEmpty) return false
        if (catalystLife <= 0) return false
        return super.beforeWorking(recipe)
    }

    /** 跑的中途：催化剂被拿走 → 停下（返回 false 会被 interruptRecipe，不产出）。 */
    override fun onWorking(): Boolean {
        if (catalystSlot.getStackInSlot(0).isEmpty) {
            // recipeLogic 是基类的 public final 字段，直接可用
            recipeLogic.setWaiting(Component.translatable("gtetcore.machine.catalyst_macerator.no_catalyst"))
            return false
        }
        return super.onWorking()
    }

    /** 收工：出料之前跑，这里扣 1 点耐久。 */
    override fun afterWorking() {
        super.afterWorking()
        if (catalystLife > 0) catalystLife--
    }
}
```

要改的如果是**流程本身**（不是条件），就得换 `RecipeLogic`。本案例用「跑完一个配方必须停机冷却 60 tick」举例，
其余照抄 GTM 的 `RecipeLogic`：

```kotlin
class CatalystRecipeLogic(machine: CatalystProcessorMachine) : RecipeLogic(machine) {

    /** 收工后强制冷却：`progress >= duration` 那条分支走的就是这里，改完下一轮会自己走。 */
    override fun onRecipeFinish() {
        super.onRecipeFinish()
        runDelay = COOLDOWN_TICKS // protected 字段，子类可写
    }

    companion object {
        private const val COOLDOWN_TICKS = 60
    }
}
```

GTET 自己更彻底的例子是 `ThreadedRecipeLogic`：它覆盖 `serverTick()` 自己维护一张线程表，
把「一台机器一条配方」改成「一台机器 N 条线程各自计时、各自出料」——**换流程就到这个量级**。

反过来：要变的如果只是「**该跑哪条配方**」（不是流程），那就别换 `RecipeLogic`，
用**委派型逻辑**（GTO 的写法）——见案例二最后一节。

### 4. 注册（少一样都不行）

```kotlin
// ALLSmahine.registerMachines() 里，切到 MACHINE 页之后：
val CATALYST_MACERATOR = ETRegistrate
    .machine("catalyst_macerator") { holder -> CatalystProcessorMachine(holder, GTValues.HV) }
    .tier(GTValues.HV)                                  // 必须最先设：abilities 与分级外壳贴图都读它
    .langValue("HV Catalyst Macerator")                 // 英文名（进 en_us）
    .rotationState(RotationState.NON_Y_AXIS)            // 机器旋转规则
    .recipeType(GTRecipeTypes.MACERATOR_RECIPES)        // 配方类型（决定槽位数、界面、候选集）
    .editableUI(                                        // 现成的机器界面（GTM 的简单机器界面生成器）
        SimpleTieredMachine.EDITABLE_UI_CREATOR.apply(Gtetcore.id("catalyst_macerator"), GTRecipeTypes.MACERATOR_RECIPES)
    )
    .recipeModifier(GTRecipeModifiers.OC_NON_PERFECT)    // 超频等配方修改器
    .workableTieredHullModel(GTCEu.id("block/machines/macerator")) // 贴图（占位可先借 GTM 的）
    .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE) // 状态贴图属性
    .register()

// 中文名走 LangUtil（由 LangHandler 写进 zh_cn）：
// LangUtil.BLOCK_LANG["catalyst_macerator"] = "HV 催化研磨机"
// 停机原因的文案同理：LangUtil.add("gtetcore.machine.catalyst_macerator.no_catalyst", "No catalyst", "没有催化剂")
```

另外三件常被忘的：

1. **注册时机**：机器必须注册在 GTM 的机器注册窗口里（见 `CommonProxy#registerMachines`），
   ⚠️ 监听器参数必须写 `RegisterEvent<*, *>`，否则一次都不会被调用；
2. **语言键**：`LangUtil` 的东西要在**数据生成之前**登记（挂在界面控件类上会赶不上 `GatherDataEvent`）；
3. **配方**：机器只是容器，配方要用 datagen 写。GTET 的配方编辑器导出片段就是这一套：

```kotlin
GTRecipeTypes.MACERATOR_RECIPES.recipeBuilder("catalyst_macerator_iron")
        .inputItems(new ItemStack(Items.IRON_INGOT))
        .outputItems(ChemicalHelper.get(TagPrefix.dust, GTMaterials.Iron))
        .duration(40)
        .EUt(VA[HV])
        .save(provider);
```

### 5. 要和 GTET 现有机制对接的话

| 机制 | 接入点 | 参考 |
|---|---|---|
| 超频（含超频仓） | `doModifyRecipe` / definition 的 `recipeModifier` | `OverclockingLogics`、`MixinOverclockingLogic` |
| 并行（并行仓） | `ParallelLogic.getParallelAmount(...)` 或 `recipeModifier` | `ETParallelHatchPartMachine` |
| 多线程（线程仓） | 换 `RecipeLogic`（覆盖 `serverTick`） | `ThreadedRecipeLogic`、`TestMultiblockMachine` |
| 部件能力（多方块） | `@SubscribeEvent Predicates.autoAbilities` / 自定义 `PartAbility` | `ETPartAbility`、`ETTestMultiblocks` |

---

## 四、排错清单

| 症状 | 大概率原因 |
|---|---|
| 一直 IDLE | 配方类型不对 / 输入没匹配上 / `RecipeCondition` 不满足（看 Jade 的 `failureReasons`）/ `isRecipeLogicAvailable()` 返回 false |
| 一直 WAITING | 没电、tick 输入不够、输出满、或自己的 `beforeWorking` 返回了 false |
| 跑着跑着停了 | `onWorking()` 返回了 false（被 `interruptRecipe`），已扣的料不会退 |
| 进度不动也不报错 | `keepSubscribing()` 为 false 且没人调 `updateTickSubscription()` |
| 进度倒退 | `regressWhenWaiting()` 为 true，等待期间回退是设计行为 |
| 跑完了不出料 | `onRecipeFinish` 里 `handleRecipeIO(OUT)` 失败（输出槽/罐满了） |
| 重载存档后设定全丢 | 字段没标 `@Persisted`，或没把 `MANAGED_FIELD_HOLDER` 接上父类 |
| 客户端看不到状态 / 没有 working 动画 | `@DescSynced` 缺失，或没设 `RECIPE_LOGIC_STATUS` 模型属性 |
| 换了 RecipeLogic 就崩 | `createRecipeLogic` 在**父类构造期**被调用，里面不能读子类字段 |
| 换配方后还跑旧配方 | 需要主动告知：`markLastRecipeDirty()`（或让机器自己喊 `updateTickSubscription()`） |

---

## 案例二：模块化的多方块

「模块化」在 GT 生态里其实是**三种不同的东西**，先分清再动手，否则会做成四不像。
参考实现：GTO（`D:\java\GTOCore` + 它的 `libs/gtolib-1.0.jar`）与同源的 StarT-Core（`D:\java\STR\StarT-Core-main`，**可读源码**）。

### 三种模块化

| 路线 | 是什么 | 参考实现 | 适合 |
|---|---|---|---|
| **A. 一控制器、多套结构** | 同一台机器按条件换结构图案 | GTO `IMultiStructureMachine`：`getMultiPattern()` 给全部结构、`getPattern()` 按当前配方类型挑、`setActiveRecipeType` 里 `updateCheck()`（见 `CompoundExtremeCoolingMachine`） | 同一台机器的不同等级/模式 |
| **B. 核心 + 模块** | 模块是**独立成型的多方块**，挂到核心上，核心聚合能力 | GTO `Core` / `Extension` / `RecipeExtension`（`space.spacestaion` 包）：`ILargeSpaceStationMachine#getRoot/setRoot` + `ConnectType.MODULE`；**可读且同版本**的参考是 GTCA 的电梯模块（见下） | 太空站那种「一个大结构 + 一堆小模块」 |
| **C. 控制器 + 接口仓** | 控制器把「支持的模块」下发给专用接口仓，模块从仓口接入 | StarT `StarTModularControllerMachine` + `StarTModularInterfaceHatchPartMachine`（控制器持有 `supportedMultiblockIds`，成型时下发） | 想让模块通过仓口接入/由玩家指定 |

### 路线 A：一控制器多结构（最省事）

```java
public final class TwoFormMachine extends WorkableElectricMultiblockMachine implements IMultiStructureMachine {

    @Override public List<BlockPattern> getMultiPattern() {   // JEI 预览与检测都用这个列表
        return List.of(patternSmall(getDefinition()), patternBig(getDefinition()));
    }
    @Override public BlockPattern getPattern() {              // 这一次该用哪套
        return getRecipeType() == GTRecipeTypes.X ? patternSmall(getDefinition()) : patternBig(getDefinition());
    }
    @Override public void setActiveRecipeType(int type) {     // 换结构 = 要重检
        if (this.activeRecipeType != type) { updateCheck(); super.setActiveRecipeType(type); }
    }
}
```

⚠️ `getPattern()` 返回的必须是 `getMultiPattern()` 里的一员（否则 JEI 预览与实际不符）；
换结构会让已成型状态失效，别在 `onStructureFormed` 里假设自己能立刻跑配方；
`updateCheck()` 是 gtolib 的东西，GTET 里对应「改完配方类型 → `recipeLogic.markLastRecipeDirty()`
＋ 需要重检时 `requestCheck()`」（GTET 的结构刷新工具就是这么触发重检的）。

### 路线 B：核心 + 模块（真模块化，也最费工程）

GTO 的协议就三条：模块认一个核心（`getRoot()` / `setRoot()`）、声明自己是模块
（`getConnectType() = ConnectType.MODULE`）、成型与失效时通知核心（`markDirty(true)` → 核心重扫）。
核心那边是一张模块表 + 聚合逻辑。GTET 版骨架（**只用 GTM 的东西**）：

```kotlin
/** 核心：普通多方块 + 一张模块表。模块挂上/掉下都从这里过。 */
abstract class ModuleHostMachine(holder: IMachineBlockEntity) : WorkableElectricMultiblockMachine(holder) {

    private val modules = linkedSetOf<ESTModuleMachine>()

    fun attachModule(module: ESTModuleMachine) { if (modules.add(module)) onModulesChanged() }
    fun detachModule(module: ESTModuleMachine) { if (modules.remove(module)) onModulesChanged() }

    /** 模块表变了（成型 / 失效 / 拆机）：重算聚合值、重判配方、刷 UI。 */
    protected open fun onModulesChanged() {
        recipeLogic.markLastRecipeDirty()
        recipeLogic.updateTickSubscription()
    }

    /** 聚合示例：模块提供的并行加起来。 */
    protected fun totalModuleParallel(): Long = modules.sumOf { it.providedParallel() }
}

/** 模块：一台自己成型的多方块，认一个核心。 */
abstract class ESTModuleMachine(holder: IMachineBlockEntity) : WorkableElectricMultiblockMachine(holder) {

    /** ⚠️ 核心引用别存机器对象再 `@Persisted`：存**坐标**，加载后自己重新解析。 */
    var host: ModuleHostMachine? = null
        private set

    fun bindHost(host: ModuleHostMachine?) { this.host = host }

    override fun onStructureFormed() {
        super.onStructureFormed()
        findHost()?.attachModule(this)
    }

    override fun onStructureInvalid() {
        host?.detachModule(this)
        host = null
        super.onStructureInvalid()
    }

    /** ⚠️ 区块卸载也要解绑，否则核心表里会留悬空引用。 */
    override fun onUnload() {
        host?.detachModule(this)
        host = null
        super.onUnload()
    }

    /** 这个模块给核心贡献多少并行（核心不认识具体模块类型，只认这个数）。 */
    open fun providedParallel(): Long = 0
}
```

「模块怎么找到核心」三种做法都行：按结构几何关系找（GTO 的做法，模块构造时带位置函数）、
核心成型时**反向扫描**一定范围内的模块机器（`MetaMachine.getMachine(level, pos)`）、
或者做接口仓让玩家手动绑定（路线 C）。**唯一不能省的是两头都清理绑定关系。**

### 路线 B 的可读参考实现：GTCA 的电梯模块（**同一个 GTM 版本**）

先纠正一个前提：**GTCA 的 `7.5.x` 分支就是 GTM 7.5.3** —— `gradle/forge.versions.toml` 里写着
`gtceu = "7.5.3"`（该分支最后一次提交 `02cbd6d` "bump to 7.5.x"），mod 2.1.1，许可证 **LGPL-3.0**
（与 GTET 相同）。所以这套实现**不是只能远看**，它的 API 与 GTET 完全同版本，可以照结构落地。
（会让人以为"不支持 7.5.3"的是老分支：`gtm7.x.x` 是 `gtceu = "7.1.3"`。）

- 仓库：<https://github.com/mordgren/GTCA>（作者 mordgren，模块系统作者 sensesgone）；
- 代码位置：`net.mordgren.gtca.common.machine.multiblock.electric.elevator` 包 ——
  `IElevatorModule`(17 行)、`ElevatorLinkedModuleMachine`(87)、`ElevatorModuleManager`(217)、
  `ElevatorEnergyManager`(96)、`ElevatorModuleKind`(7)，加核心 `SpaceElevatorMachine`(220)。
- 抄结构时按项目既有习惯在文件头注明来源（GTET 处理 GTOCore 素材就是这么做的）。

它把「模块化」拆成了五件很清楚的事：

**1. 槽位是写死的相对坐标表，核心按朝向旋转**（`MODULE_SLOTS_LOCAL` 12 个 `BlockPos` + `rotateLocalX/Z(facing)`）
→ **位置即契约**：模块不用知道自己属于谁，核心也不用玩家手动绑定。

**2. 核心每 20 tick 重扫一次，槽位数由结构决定**

```java
// SpaceElevatorMachine
private void onServerTickSubscribed() {
    distributeWirelessEnergy();                       // 每 tick 给模块分电
    if (++scanTimer >= 20) { scanTimer = 0; rescanAll(); }
}
private void rescanAll() {
    recalcMotorTierAndSlots();                        // 结构里的马达等级 → 解锁几个槽
    moduleManager.rescanSlots(unlockedModuleSlots);
    moduleManager.applyEnabling();                    // 开关下发
    // 再把 modulesFound / Valid / Active 缓存进 @Persisted @DescSynced 字段给 UI 与 Jade
}
```

**3. 判定链 present → formed → valid → active**：`MetaMachine.getMachine(level, pos)` →
`instanceof IElevatorModule` → `isFormed()` → `isValidForElevator()`；激活数取
`min(解锁槽数, 有效模块数)`，并**按槽位顺序**取前 N 个。`SlotInfo` 是只带坐标与状态的**快照**
（不持有机器引用 → 不会漏引用，也方便同步）。

**4. 开关下发：先全关、再开激活的**（`applyEnabling()` 两趟循环；`onStructureInvalid()` 里
`disableAllModulesInSlots()` 全关）——避免上一轮的启用状态残留。

**5. 模块侧：三行契约 + 一个看门狗 + 自带无线能量**

```java
public abstract class ElevatorLinkedModuleMachine extends WorkableElectricMultiblockMachine
        implements IElevatorModule {

    protected boolean enabledByElevator = false;

    @Override public void setEnabledByElevator(boolean enabled) {
        this.enabledByElevator = enabled;
        if (getLevel() != null && !getLevel().isClientSide) recipeLogic.setWorkingEnabled(enabled);
    }

    @Override public void onLoad() {   // ⚠️ 看门狗：别处（UI / 红石 / 软锤）又把它打开时，强制关回去
        super.onLoad();
        gateSub = subscribeServerTick(() -> {
            if (!enabledByElevator && recipeLogic.isWorkingEnabled()) recipeLogic.setWorkingEnabled(false);
        });
    }
}
```

模块**自己带一个无线能量容器**（`NotifiableEnergyContainer.receiverContainer(this, cap, maxV, 64)`
＋ `attachTraits`）并通过 `getWirelessEnergyContainer()` 暴露，核心用 `ElevatorEnergyManager`
按需求比例分电（最后一份吃余数、插不进去的电退回核心）——所以**模块不需要自己的能源仓**，
这是"模块化"手感的关键。

两个值得学的细节：`enabledByElevator` 默认 `false` 且**不持久化**，靠「默认关 + 看门狗 +
≤20 tick 重扫」保证加载后不会误开（方向是安全的，不用加 `@Persisted`）；
`IElevatorModule#getWirelessEnergyContainer()` 在接口里声明成 `IEnergyContainer`、实现里协变返回
`NotifiableEnergyContainer`，抄的时候别把类型写反。

**6. 与 GTO 那套的对照**

| | GTO（`Extension` / `Core`） | GTCA（电梯模块） |
|---|---|---|
| 模块怎么找到核心 | 模块认核心：`getRoot()/setRoot()` + `ConnectType.MODULE`，成型/失效 `markDirty(true)` 通知重扫 | 核心按**固定相对坐标**扫描，模块无感 |
| 谁决定模块数量 | 核心结构 / 研究 | 核心结构里的马达等级 → `slotsForMotorTier` |
| 模块开关 | 核心聚合 + trait | `setEnabledByElevator` + 看门狗 |
| 能量 | `CrossRecipeTrait` 里的无线能量 trait | 模块自带能量容器 + 核心按比例分电 |
| 工程量 | 高（引用清理、时序、trait 划分） | 低（位置即契约、判定链清晰） |

所以 GTET 真要做模块化，**建议直接照 GTCA 这套来**（同版本 API、工程量小、坑它都踩过），
GTO 那套留给「模块要参与核心配方 / 并行聚合」的场景。

### GTO 式的机器逻辑写法（不限于模块化）

GTO 不在 `RecipeLogic` 子类里堆 `override`，而是两条：

1. **入口仍是 `createRecipeLogic`**（GTM 预留的虚方法），但返回一个「委派型」逻辑：
   GTO 写的是 `new CustomRecipeLogic(this, this::getRecipe, false)` —— **「该跑哪条配方」由机器自己的
   `getRecipe()` 回答**，逻辑类只管流程（`RecipeExtension` 就是这么写的）；
2. **重型状态放 trait**：`CrossRecipeTrait(machine, …, parallelFn)` 装上多配方/跨配方、并行、线程、
   无线能量，`ICrossRecipeMachine` 只是一层接口 —— 这样逻辑能被复用，也能被核心/模块查询。

GTET 现在走的是第三条路（直接换 `RecipeLogic`）。三种写法对照：

| 写法 | 怎么找配方 | 适合 | 代价 |
|---|---|---|---|
| GTM 默认 | `RecipeManager` 按 `recipeType` 自动搜 | 普通机器 | 想改「搜什么」就得覆盖 `findAndHandleRecipe` |
| **GTO 式委派** | 机器给 `getRecipe()` / `Supplier<Recipe>` | 配方来自计算或多来源（跨配方、按模块拼） | 多一层间接，要自己保证配方「配得上」机器 |
| GTET 现状 | 覆盖 `serverTick` 自己维护线程表 | 一台机器多条配方并行 | 进度/IO/并行的全部契约都自己接管 |

GTET 版委派逻辑骨架（直接可用）：

```kotlin
/** 「跑什么配方由机器说了算」的逻辑；其余流程照走 GTM 的。 */
class DelegatingRecipeLogic(
    machine: IRecipeLogicMachine,
    private val recipeSupplier: () -> GTRecipe?,
) : RecipeLogic(machine) {

    override fun findAndHandleRecipe() {
        val custom = recipeSupplier()
        if (custom == null) {           // 机器没给出配方 → 退回默认搜索
            super.findAndHandleRecipe()
            return
        }
        // 仍然走 GTM 的「套修改器 → 判条件 → 开工」，超频 / 并行 / 条件一个都不会漏
        checkMatchedRecipeAvailable(custom)
    }
}
```

⚠️ 两个保命点：

- `checkMatchedRecipeAvailable` **不等于直接开工**：里面还会跑 `machine.fullModifyRecipe(...)`（超频/并行
  修改器）与 `checkRecipe(...)`（条件 + 内容匹配），失败了会写进 `failureReasons`（Jade 看得到）；
- 委派逻辑最常见的 bug 是**返回了一条机器跑不了的配方**（槽位不够、等级不够），症状是永远 IDLE、
  Jade 却不说原因 —— 自己给的配方要按 `getRecipeType().getMaxInputs/getMaxOutputs(...)` 先对一遍。

再偷 GTO 一个设计：**空转原因用枚举**（`IdleReason`：`LACK_MATERIAL` / `NO_EU` / `OUTPUT_FULL` /
`INSUFFICIENT_TEMPERATURE` …），而不是自由文本。GTET 现在用的是 `Component`，要做分类显示
（Jade/UI 分组、统计哪种原因最多）时值得换过来。

### GTO 那套能抄到什么程度（诚实说明）

- gtolib 里 `CustomRecipeLogic`、`CrossRecipeTrait`、`IdleReason` 的**方法体全是 `native`**，实现被抽到
  `native0/native/*.bin`（加密库）——GTET 的 `IThreadedRecipeMachine` KDoc 里也记着这条。
  所以**只能学形状**（接口怎么切、谁委派给谁、trait 怎么分），拿不到实现；
- GTOCore 自己源码里可读的是**用法**：`RecipeExtension` 怎么建 trait、怎么用
  `ToLongFunction<RecipeExtension> parallel` 让核心**覆盖**模块的并行、`onPartScan(IMultiPart)` 怎么扫
  部件能力（激光 / 超频仓 / 线程仓）；
- 想整体照抄就得把 gtolib 当依赖。GTET 现在没有这个依赖，也没必要：GTET 的线程仓已经覆盖了
  「一台机器多配方」，缺的只是**模块化**，那部分按上面的骨架自研即可。

---

## 案例三：模块化 PCB 工厂，与「嵌套多方块」的四张脸

案例二的三种模块化之外还有**路线 D：模块是插进存储槽的物品** —— 它同时决定「用哪套结构」和
「配方等级上限」。主角是 **GTO 的 `PCBFactoryMachine`**（`com.gtocore.common.machine.multiblock.electric.PCBFactoryMachine`，254 行），
对照实现是 **GTCA 的 PCB**（`GTCA-7.5.x\...\electric\PCBFactoryMachine.java`，18 行 + `PCBRecipeCondition`）。

### 1. 主参考：GTO 的 PCB 工厂

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

### 2. 同一个需求，两种「等级卡配方」

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

### 3. 「嵌套多方块」的四张脸

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

### 4. GTET 落地的推荐顺序

1. **形态 ④**（一控制器多套结构）：改动最小，`getPattern()` + 换档后 `requestCheck()` 就能跑；
2. **形态 ③**（结构方块喂数据）：用一个自己的数据键 + 自定义谓词，等级/倍率全部在结构里表达；
3. **形态 ②**（核心 + 模块）：照 GTCA 那套（位置即契约 + 判定链 + 开关下发 + 分电，见案例二）；
4. **形态 ①**（部件是控制器）：最后做，它要求把上面的绑定/解绑/唤醒路径都理顺。

---

## 案例四（代码案例）：怎么写一台模块化机器

需求：一个**电解水厂** —— 一台**主机**（只管供电与等级）+ 一台**电解单元**（真正跑配方）。
单元的一句话机器逻辑：**附近 32 格内有成型主机 + 配方等级不超过主机 + 每 tick 的电付得起 → 才跑。**

### 1. 主机（只写结构图案与注册，逻辑全在基类）

```kotlin
/** 一句话机器逻辑：主机自己**不跑配方**，它只提供「电量」与「等级」。 */
class WaterPlantHostMachine(holder: IMachineBlockEntity) : ETModuleHostMachine(holder)
```

单元表、`hostTier()`、`availableEu()`、`consumeHostEu()`、以及成型/失效/卸载三处的登记注销，
都在 `ETModuleHostMachine` 里；子类只写图案（照案例三的 `boxPattern`）和注册。

### 2. 单元（一句话条件 + 扣电）

```kotlin
class ElectrolysisUnitMachine(holder: IMachineBlockEntity) : ETModuleMachine(holder) {

    /** 结构里收集到的电极数（形态 ③）。 */
    private var electrodes = 0

    /** 每 tick 要的电（默认从主机取，见 [ETPowerSource]）。 */
    override fun euPerTick(): Long = GTValues.VA[GTValues.HV] * 4

    /** **一句话机器逻辑**：主机那条由基类给（没接上主机就先报主机），这里只追加本单元自己的条件。 */
    override fun recipeRequirement(): Component? {
        super.recipeRequirement()?.let { return it }
        if (electrodes <= 0) return Component.translatable("gtetcore.machine.electrolysis_unit.no_electrodes")
        return null
    }

    /** ⚠️ 扣电放这里：每工作 tick 一次（`recipeRequirement` / `matchRecipe` 是**模拟**层，一 tick 问好几次）。 */
    override fun onWorking(): Boolean {
        if (consumeEu() < euPerTick()) return false // 付不起 → 返回 false 会被 interruptRecipe
        return super.onWorking()
    }

    override fun onStructureFormed() {
        super.onStructureFormed()
        electrodes = ETStructureData.moduleBlockCount(multiblockState)
    }
}
```

等级分工不用自己写：`processingTier()` 是**本单元**结构的电压档位（超频 / 并行按它），
`recipeTier()` 是**主机**的档位（决定这条配方配不配跑）。

### 3. 注册（两台各自注册，主机与单元可以放同一个创造页）

```kotlin
// 主机：自己不吃配方，recipeType 给个空的/占位类型即可
registrate.multiblock("water_plant_host") { WaterPlantHostMachine(it) }
    .langValue("Water Plant Host")
    .tier(GTValues.HV)
    .rotationState(RotationState.ALL)
    .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
    .pattern { definition -> /* 你的图案 */ }
    .workableCasingModel(GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                         GTCEu.id("block/multiblock/gcym/large_maceration_tower"))
    .register()

// 单元：跑真正的配方，等级上限由主机给
registrate.multiblock("electrolysis_unit") { ElectrolysisUnitMachine(it) }
    .langValue("Electrolysis Unit")
    .tier(GTValues.LV)                 // 自己只是 LV —— 但主机是 HV 时就能跑 HV 配方
    .recipeType(GTRecipeTypes.ELECTROLYZER_RECIPES)
    .recipeModifier(GTRecipeModifiers.OC_NON_PERFECT)
    .register()
```

### 4. 写完会在游戏里看到什么（这就是 #2 补的可调试性）

| 位置 | 显示 |
|---|---|
| **主机面板** | `单元 2 台（已成型 1）` / `主机等级 HV（单元的配方等级以它为准）` / `主机缓存 1,234,567 EU（单元可以从这里取电）` |
| **单元面板** | `已对接模块主机（距离 7 格）` 或 `附近 32 格内没有模块主机`；`处理等级 LV（以自己算）· 配方等级 HV（以主机算）`；可点的 `重新对接主机 [↻]` |
| **Jade** | 条件不满足时**直接显示原因**：`附近 32 格内没有模块主机` / `配方的电压等级高于主机（HV）` |

「重新对接主机」那颗按钮是给调试用的：正常情况每 80 tick 会自动重找一次，但摆好机器想马上验证时点一下更省事。

### 5. 写这类机器的三条硬规矩

1. **一句话条件只写在 `recipeRequirement()` 一处** —— 不要散在 `matchRecipe` / `serverTick` 里；写在一处，
   Jade 与面板才有原因可显示（GTO 的 `IdleReason` 干的就是这件事）；
2. **扣电放 `onWorking()`**（每工作 tick 一次）；模拟层（`recipeRequirement` / `matchRecipe`）里扣电会重复扣；
3. **登记/注销必须成对**：基类已经处理了主机与单元各自的成型/失效/卸载三处；自己加字段时覆写这些方法**要先 `super`**。

---

## 下一步（后续案例）

- 案例四（待写）：**把 `ThreadedRecipeLogic` 拆开讲** —— 线程表、同配方 fan-out、每线程并行契约；
- 动手做模块化的建议顺序（与案例三第 4 节一致）：**形态 ④ 一控制器多结构** → **形态 ③ 结构方块喂数据**
  → **形态 ② 核心 + 模块**（照 GTCA）→ **形态 ① 部件是控制器**；
- 手边可查的参考实现（都已读通，路径即用）：

| 参考 | 路径 | 特点 |
|---|---|---|
| GTO | `GTOCore`（+ `libs/gtolib-1.0.jar`） | 功能最全；gtolib 的方法是 `native`，**只能学形状** |
| StarT-Core | `StarT-Core-main` | 可读；控制器 + 接口仓（路线 C） |
| GTCA | `GTCA-7.5.x` | **gtceu 7.5.3、可读**；电梯模块（路线 B）+ PCB（自定义 `RecipeCondition`） |
| GTM 本体 | `GregTech-Modern-7.5.3-1.20.1` | `RecipeLogic` / `IRecipeLogicMachine` / `SimpleTieredMachine` / `SimplePredicate` 的原文 |


