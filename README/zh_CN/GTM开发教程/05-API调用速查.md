# 案例五（速查）：这些 API 到底怎么调
> [← 返回目录](<../GTM开发教程(编写中).md>) ｜ [下一节：06 游戏内怎么用 →](06-游戏内怎么用.md)

前四个案例讲的是**设计与原理**，这一节是**抄写清单**：每条都写成「要做什么 → 调什么 → 参数给什么 → ⚠️ 坑」。
括号里的 `文件:行号` 都是核对过的位置（GTM 按 GregTech-Modern 7.5.3，其余是本仓库源码），
升级版本时按行号对一遍就知道有没有漂。

> 原理只在这里**引用**，不重写：四条状态机与配方生命周期见案例一，模块化三条路线见案例二，
> 「结构方块喂数据」见案例三，模块化机器的落地见案例四。

## 1. 注册机器

| 要做什么 | 调什么 | 参数给什么 |
|---|---|---|
| 注册单方块 / 部件仓 | `GTRegistrate#machine(name, IMachineBlockEntity -> MetaMachine)`（`GTRegistrate.java:139`） | 名字（= 注册名，同时决定方块 id 与 `block.<modid>.<name>`）、机器工厂 |
| 注册多方块 | `GTRegistrate#multiblock(name, IMachineBlockEntity -> MultiblockControllerMachine)`（`:154`） | 同上 |
| 自定义 definition / 方块 / 物品 / 方块实体 | 七参重载 `machine(name, definitionFactory, metaMachine, blockFactory, itemFactory, blockEntityFactory)`（`:129`） | 只有要自己替换这四样时才用 |
| 收尾 | `.register()` | 必须调（`MachineBuilder.java:653` / `MultiblockMachineBuilder.java:129`），不调就等于没注册 |

**链上每一项什么时候生效** —— 这就是「顺序为什么重要」的答案：

| 项 | 写在哪 | 什么时候被读 |
|---|---|---|
| `.tier(int)` | `MachineBuilder.java:244` | ① `register()` 里 `definition.setTier(tier)`（`:689`）；② **方块注册回调**里 `ability.register(builder.tier, block)`（`:778`）；③ 物品 tier 染色 `GTValues.VC[tier]`（`:127-128`，延迟求值） |
| `.langValue(String)` | `:314` | `register()` 里 `blockBuilder.lang(...)` + `definition.setLangValue(...)`（`:663-666`） |
| `.rotationState(...)` | `:194` | `register()` → `definition.setRotationState`（`:657`） |
| `.abilities(PartAbility...)` | `:519` | 方块注册回调（`:778`），用**那一刻**的 `builder.tier` |
| `.recipeType(GTRecipeType)` | `:319` | 立即：只在**缺键**时补 `RECIPE_LOGIC_STATUS = IDLE`（`:352-358`） |
| `.modelProperty(Property, T)` | `:524` / `:528` | 立即写 map（`put`，**后写胜**）；`register()` 里用它建默认渲染态（`:637-650`） |
| `.pattern { ... }`（多方块） | `MultiblockMachineBuilder.java:75` | `register()` 里包成 memoize（`:135`）——**图案只构建一次**；没给图案直接抛 `missing pattern while creating multiblock`（`:132-134`） |

⚠️ 三条真正的顺序硬规矩：

1. **`.tier(...)` 不必写在 `.abilities()` 或贴图前面**：两者都只是往 builder 里写字段，`tier` 真正被读是在
   `register()`（`:689`）与方块注册回调（`:778`），那时链已经跑完了。
   **硬规矩是「`.tier(...)` 必须在 `.register()` 之前」**；漏了就是 tier 0（ULV）—— 仓会被登记进 ULV 那一档、
   物品染色也按 ULV 走（`GTValues.VC[0]`）。
2. **模型 helper 会覆盖你的 `modelProperty`**：`workableTieredHullModel` 自己 `put` 一条
   `RECIPE_LOGIC_STATUS = IDLE`（`:464-467`），而 `modelProperty` 也是 `put`。所以**先调模型、再写自己的
   `.modelProperty(...)`**，反过来会被回写成 IDLE。多方块的 `IS_FORMED = false` 由 `MultiblockMachineBuilder`
   构造器在 `:67` 保证，不用自己加。
3. **一套模型都不给 = 紫黑块**：`register()` 发现 `model == null && blockModel == null` 时兜底成
   `block/machine/template/<name>`（`:659-661`）—— 那个模板不存在就是紫黑方块。

⚠️ **能力方块表是「快照」，所以「先注册仓、再注册用到它的多方块」同样是硬顺序**：
`Predicates.abilities(...)`（`Predicates.java:134-137`）在**构造谓词那一刻**就把 `PartAbility#getAllBlocks()`
取了出来，而那个集合是 `GTMemoizer.memoize` 缓存的（`PartAbility.java:61-62`，首取即定、之后不再变）。
先构造谓词、后注册仓 = 谓词里永远没有那些方块 —— **不报错**，只表现为「插上去不成型」。
本项目的落地就是 `MachineRegister#init` 先 `ALLSmahine.init()`（仓）再 `ALLMmchine.init()`（多方块）（`MachineRegister.kt:8-9`）。

**注册窗口 + 监听器**（少一样都不行）：

```kotlin
/** GTM 只在 GTMachines.register() 里发一次这个事件，紧接着就 GTRegistries.MACHINES.freeze()。 */
@SubscribeEvent
fun registerMachines(event: GTCEuAPI.RegisterEvent<*, *>) {
    if (event.genericType != MachineDefinition::class.java) return
    MachineRegister.init()   // 在这里注册 = 冻结之前入表，渲染态由 GTM 那次循环一并登记
}
```

- `RegisterEvent<K, V> extends GenericEvent<V>`（`GTCEuAPI.java:57`）→ EventBus 按 `V` 匹配。
  ⚠️ 参数**必须**写 `RegisterEvent<*, *>`（本项目的写法见 `CommonProxy.kt:92-100`）；写成
  `RegisterEvent<ResourceLocation, MachineDefinition>` 时**一次都不会被调用、也不报错**，机器静默全灭。
- 窗口：`GTMachines.java:1099` 发事件 → `:1101` `freeze()`。冻结之后再 `register` 会抛
  `IllegalStateException("[register] registry ... has been frozen")`（`GTRegistry.java:91-92`）。
  所以注册**只能**在这个事件里做（本条是速查表里少数「写错会响」的地方之一）。

## 2. 结构图案

| 要做什么 | 调什么 | 参数给什么 |
|---|---|---|
| 起头 | `FactoryBlockPattern.start()`（`FactoryBlockPattern.java:110-112`） | 用默认方向 `LEFT / UP / FRONT` |
| 自定义三个轴 | `start(RelativeDirection charDir, stringDir, aisleDir)`（`:114-117`） | 三个轴必须互不相同，否则构造器就抛 `Must have 3 different axes!`（`:43`） |
| 加一层 | `.aisle("XXX", "X X", "XXX")`（`:89-91`） | 参数是**这一层的若干行**；顺序就是各轴顺序 |
| 可重复的层 | `.aisleRepeatable(min, max, "XXX", "XXX", "XXX")`（`:50`；签名是 `(int minRepeat, int maxRepeat, String... aisle)`）/ 改**最后一条** aisle 的重复次数 `.setRepeatable(min, max)`、`.setRepeatable(n)`（`:96-108`） | `setRepeatable` 改的是刚写的那条 aisle |
| 绑方块 | `.where('X', predicate)`（`:123-130`）/ `.where("X", predicate)`（`:119-121`，取首字符） | 字符 + `TraceabilityPredicate` |
| 收尾 | `.build()`（`:132-152`） | 有哪个字符没 `where` 过就抛 `Predicates for character(s) ... are missing`（`:170-181`） |

⚠️ 同一条 aisle 里**行数与行宽都是先定后审**：第一层决定高度与宽度，之后每条 `aisle` 行数不对抛
`Expected aisle with height of ...`（`:57-59`）、某行宽度不对抛 `Not all rows in the given aisle are the correct width`（`:62-65`）。
所以「写歪一格」是**启动期就响**的，别等到成型才发现。

常用谓词与限制器（都在 `Predicates` / `TraceabilityPredicate` 上）：

| 想要 | 写法 | 位置 |
|---|---|---|
| 机壳等具体方块 | `Predicates.blocks(GTBlocks.X.get())` / `blocks(block1, block2)` | `Predicates.java:65` / `:69` |
| 控制器 | `Predicates.controller(Predicates.blocks(definition.block))` | `:50` |
| 空气 | `Predicates.air()` | `:104` |
| 自定义收集型谓词 | `Predicates.custom(Predicate<MultiblockState>, Supplier<BlockInfo[]>)` | `:96`（案例三的「结构喂数据」就用它） |
| 按配方自动开仓 | `Predicates.autoAbilities(recipeTypes...)`（`:143`）/ `autoAbilities(布尔 checkMaintenance, 布尔 checkMuffler, 布尔 checkParallel)`（`:209`） | 前者按配方能力开能源/物品/流体仓，后者开维护仓 + 消音仓 + 并行仓 |
| 线圈 | `Predicates.heatingCoils()` | `:226` |
| GTET 自己的能力 | `Predicates.abilities(ETPartAbility.THREAD_HATCH)` | `:134` |
| 数量限制 | `.setMinGlobalLimited(n)`（`:83`）/ `(n, preview)`（`:92`）、`.setMaxGlobalLimited(n)`（`:99`/`:108`）、`.setMinLayerLimited`（`:115`/`:124`）、`.setExactLimit(n)`（`:149`）、`.setPreviewCount(n)`（`:156`）、`.or(其它谓词)`（`:207`） | `TraceabilityPredicate.java` |

⚠️ **`FactoryBlockPattern.start(definition, ...)` 是 GTO 分叉特有的重载，GTM 7.5.3 上没有**：
GTM 只有 `start()`（`:110`）与 `start(charDir, stringDir, aisleDir)`（`:114`）；
`start(MultiblockMachineDefinition)` 与 `start(definition, charDir, stringDir, aisleDir)` 在 GTO 分叉的
`FactoryBlockPattern.java:101` / `:110` 里（GTO 分叉的 GTM）。
从 GTO / GTOCore 抄图案代码（`GTOCore` 里 `FactoryBlockPattern.start(definition)` 有上百处）时，
把这两个重载换成 GTM 的 `start()`，`definition` 那个参数在 GTM 版本里用不上。

## 3. EMI / JEI 的多方块预览

| 要做什么 | 调什么 | 参数给什么 |
|---|---|---|
| 给 EMI / JEI 预览数据 | `definition.shapes = Supplier { ... }` | 是 `MultiblockMachineDefinition` 上的 `@Setter @Getter Supplier<List<MultiblockShapeInfo>>`（`MultiblockMachineDefinition.java:36-38`）；**必须在 `register()` 之后赋值**：builder 阶段还没有这个对象，而且 `register()` 自己会先塞一份（`MultiblockMachineBuilder.java:136`） |
| 只想在链上给 | `.shapeInfo { }` / `.shapeInfos { }`（`MultiblockMachineBuilder.java:100-108`） | 单页 / 多页；两者等价于写 `shapes`，不用等 `register()` |
| 从图案直接生成预览 | `MultiblockShapeInfo(pattern.getPreview(repetition))` | `MultiblockShapeInfo(BlockInfo[][][])`（`MultiblockShapeInfo.java:19`）；`getPreview` 在 `BlockPattern.java:390` |
| 手写预览 | `MultiblockShapeInfo.builder().where(...).build()`（`:27-61`） | 与 GTM 内部 DFS 出来的形状语义相同 |

预览数据的消费链：`MultiblockMachineDefinition#getMatchingShapes()`（`:64-70`）先 `shapes.get()`，
**非空就用它**；返回空列表则退回 `patternFactory.get()` 的 `repetitionDFS(...)`（`:72-89`）自动枚举。
所以「一套运行时图案 + 多套预览」= `pattern { }` 给兜底的那套、`shapes` 给全部几套（案例四的模块化测试机就是这么做的）。

**能直接抄的写法**（照 `ETModularTestMachine.previewShapes`，`ETModularTestMachine.kt:139-150`）：

```kotlin
// 一页 = 一套图案；repetition 的每一项取该条 aisle 的最小重复数（全是 aisle() 来的就是全 1）
listOf(1, 2, 3).mapNotNull { tier ->
    runCatching {
        val pattern = boxPattern(sizeOfTier(tier), definition)
        val repetition = IntArray(pattern.aisleRepetitions.size) { pattern.aisleRepetitions[it][0] }
        MultiblockShapeInfo(pattern.getPreview(repetition))
    }.getOrElse { e -> GTCEu.LOGGER.error("预览结构 MK{} 生成失败，该页被跳过", tier, e); null }
}
```

⚠️ **`repetition` 数组的长度必须等于图案的 aisle 条数**，这就是那个坑：
`getPreview` 外层是 `for (l = 0; l < fingerLength; l++)`（`BlockPattern.java:399`），内层直接下标
`repetition[l]`（`:400`）；而 `fingerLength = predicatesIn.length`（`:69`）= `aisleRepetitions.length`
（`FactoryBlockPattern.build` 用 `depth.size()` 建数组，`:135`；`aisle()` 每调一次往 `depth` /
`aisleRepetitions` 各加一条，`:75` / `:78`）。**传空数组 = 立刻 AIOOBE**。
本项目**真踩过**（`BlockPattern.getPreview:400`），而且后果不是「少一页」，是**这台机器没有预览 + 把 gtceu 那一整批
EMI 注册一起带走**。实测链路（四步，缺一步都不成立）：

1. 供应商在 `MultiblockMachineDefinition#getMatchingShapes()`（`:64-66`）里被调用；
2. EMI 侧 `MultiblockInfoEmiCategory.registerDisplays`（`MultiblockInfoEmiCategory.java:22-28`）为每台
   `isRenderXEIPreview` 的多方块 `new MultiblockInfoEmiRecipe(definition)`（在 `stream().forEach()` 里）；
3. ⚠️ **本项目跑的 ldlib 1.0.50，`ModularEmiRecipe` 的构造器里就把 `widget.get()` 调了** ——
   `Supplier.get()` 在构造器字节码偏移 43（源码行 `ModularEmiRecipe.java:46`），不是等 `addWidgets()`。
   所以抛出点在**构造期**，异常直接在 `registerDisplays(:28)` 的 `forEach` 里炸出来；
4. 异常一路穿到 `GTEMIPlugin.register(GTEMIPlugin.java:68)`，EMI 捕获后打一行
   `[EMI]: Exception loading plugin provided by gtceu`，**放弃这个插件余下的全部注册**（不再有 `Reloaded` 那一行）。

实测栈（`run/logs/2026-09-13-5.log.gz` 第 5089~5117 行；`2026-09-12-4` 与 `debug-5` 里是同一形状）：

```text
[EMI]: [EMI] Loading plugin from gtceu
[EMI]: Exception loading plugin provided by gtceu
java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
	at com.gregtechceu.gtceu.api.pattern.BlockPattern.getPreview(BlockPattern.java:400)
	at rain.gtetcore...ETModularTestMachine$Companion.previewShapes(ETModularTestMachine.kt:129)
	at rain.gtetcore...ETModularTestMultiblocks.register$lambda$2(ETModularTestMultiblocks.kt:62)
	at ...MultiblockMachineDefinition.getMatchingShapes(MultiblockMachineDefinition.java:65)
	at ...PatternPreviewWidget.lambda$new$2(PatternPreviewWidget.java:175)
	at ...PatternPreviewWidget.<init>(PatternPreviewWidget.java:172)
	at ...PatternPreviewWidget.getPatternWidget(PatternPreviewWidget.java:234)
	at ...MultiblockInfoEmiRecipe.lambda$new$0(MultiblockInfoEmiRecipe.java:25)
	at com.lowdragmc.lowdraglib.emi.ModularEmiRecipe.<init>(ModularEmiRecipe.java:46)   ← 构造器那一帧
	at ...MultiblockInfoEmiRecipe.<init>(MultiblockInfoEmiRecipe.java:25)
	at ...MultiblockInfoEmiCategory.registerDisplays(MultiblockInfoEmiCategory.java:28)
	at com.gregtechceu.gtceu.integration.emi.GTEMIPlugin.register(GTEMIPlugin.java:68)
	at dev.emi.emi.runtime.EmiReloadManager$ReloadWorker.run(EmiReloadManager.java:195)
```

**两条判据缺一不可**（我一开始按 ldlib 1.0.40.b 的字节码判断，结论是错的，记在这里免得后来人再踩）：

1. **版本判据**：本项目用的是 **ldlib 1.0.50** —— 依赖写的是 `curse.maven:ldlib-626676:7809449`
   （`scripts/dependencies.gradle:22`，注释即 `ldlib-1.20.1-1.0.50-forge`），实际参与构建的 jar 是
   `deobf_dependencies/curse/maven/ldlib-626676/7809449_.../ldlib-626676-7809449_....jar`。
   在那个 jar 上 `javap -p -c com.lowdragmc.lowdraglib.emi.ModularEmiRecipe` 能看到构造器里
   `43: invokeinterface ... Supplier.get`（紧接着用 `Widget.getSize()` 填 `width` / `height`）。
   （对照：旧版 ldlib 1.0.40.b 是**构造器只存 Supplier、`addWidgets()` 里才 get**（`addWidgets` 第一条指令
   就是 `Supplier.get()`）—— 那种版本下同样写错只会让这台机器不出预览，不会拖垮同批。**按 1.0.40 的字节码
   推断 1.0.50 的行为，就是我上面犯的错**，看字节码前先确认 jar 是哪一份。）
2. **运行时判据**：上面那段栈（`ModularEmiRecipe.<init>(:46)` → `registerDisplays(:28)` → `GTEMIPlugin.register(:68)`）。

于是症状是「EMI 里配方类别还在，但一整批注册都没了」：`GTEMIPlugin.register` **第 68 行之后的代码全都没执行**
—— `GTRecipeEMICategory.registerDisplays`（`:69`）、矿处理（`:71`）、矿脉（`:72`）、基岩流体（`:73`）、
基岩矿（`:75`）、编程电路 display（`:76`）、各类工作站（`:79-87`）、比较器与药水流体（`:90-105`）；
第 46~65 行注册的那些**类别**还在（类别与 display 是两段），所以界面上是「有分类、里面空」。
日志侧的确认方法最省事：整个会话里 gtceu 的插件**只有** `Loading plugin from gtceu` +
`Exception loading plugin provided by gtceu`，**再也不会出现 `Reloaded plugin from gtceu`**
（`2026-09-13-5.log.gz` 第 5091 行之后，`Reloaded plugin from gtceu` 出现 0 次）；
抛异常那一刻之前已经 `addRecipe` 成功的那几条预览页会留着（`forEach` 断在第一个抛出的元素上）。

另外一个独立的坑：世界内预览（对着未成型的控制器 Shift + 空手右键，`IMultiController.java:247-256`）读的是
`getMatchingShapes().get(0)`（`MultiblockInWorldPreviewRenderer.java:114`），同一个坏供应商也会让它炸。

结论：**每页自己 `runCatching` 兜底**（上面那段就是这么写的），并顺手校验数组长度 —— 这是唯一能把
「某台机器预览写错」限制在「这台机器少一页」的做法。不想让某台机器出现在 EMI 里，
就 `.multiblockPreviewRenderer(false, false)`（`MachineBuilder.java:611-616`，EMI 按 `isRenderXEIPreview` 过滤）。

## 4. 配方逻辑：覆盖点与门禁写在哪

| 你想干的事 | 覆盖什么 | 签名 / 位置 |
|---|---|---|
| 换「该跑哪条配方」 | `findAndHandleRecipe()` / `searchRecipe()` | `RecipeLogic.java:338` / `:334`（委派型写法见案例二末节） |
| 加内容层面的规矩（输出槽必须空、等级上限…） | `matchRecipe(recipe): ActionResult` | `:249`，默认就是 `RecipeHelper.matchContents(machine, recipe)` |
| 加配方自带条件之外的条件 | `checkRecipe(recipe)` | `:253` = `RecipeHelper.checkConditions` + `matchRecipe` |
| 换 tick 输入/输出行为 | `handleTickRecipe(recipe)` | `:377` |
| 开工/收工副作用 | `setupRecipe` / `onRecipeFinish` | `:390` / `:507` |
| 打断、等待原因、标记重算 | `interruptRecipe()`（`:563`）、`setWaiting(Component)`（`:433`）、`markLastRecipeDirty()`（`:443`） | 都会反映到 Jade 与面板 |
| 把失败原因写进 `failureReasons` | `RecipeLogic.putFailureReason(logic, recipe, reason)` | `:696`（静态，`failureReasons` 字段在 `:92`） |

⚠️ **门禁写在 `matchRecipe`，不要指望 `doModifyRecipe`**：GTM 的
`WorkableMultiblockMachine#doModifyRecipe(GTRecipe)` 是 `public final`（`WorkableMultiblockMachine.java:205`），
**多方块里根本覆盖不了**；它内部固定调
`self().getDefinition().getRecipeModifier().applyModifier(self(), recipe)`（`:215`）。
多方块想改配方走向，只能走 definition 上的 `.recipeModifier(...)` / `.recipeModifiers(...)`
（`MachineBuilder.java:579-593`），或者按案例一那套换掉整个 `RecipeLogic`。

⚠️ 失败**只能用 `ActionResult.fail(...)` 表达**，不要返回 `null`：
`ActionResult.fail(@Nullable Component reason, @Nullable RecipeCapability<?> capability, IO io)`
（`ActionResult.java:24-26`；本项目第三个参数传 `null`，见 `ETModularMachine.kt:110-116` 的 `ETModularRecipeLogic`）。
返回 `fail` 会带上原因文本，GTM 把它写进 `failureReasons`、**Jade 与机器面板都能看到「为什么这条配方不跑」**；
返回 `null` 是静默不可用（GTO 的 PCB 工厂就是靠 `getRealRecipe` 返 `null` 卡等级，代价是玩家看不到原因）。

```kotlin
/** 等级不够的配方判为不可用 —— 拦在 matchRecipe，原因才进 failureReasons。 */
class CappedRecipeLogic(private val machine: ETModularMachine) : RecipeLogic(machine) {
    override fun matchRecipe(recipe: GTRecipe): ActionResult {
        val cap = machine.maxRecipeTier()
        if (cap >= 0 && RecipeHelper.getRecipeEUtTier(recipe) > cap) {
            return ActionResult.fail(
                Component.translatable(ETModularMachine.LANG_TIER_TOO_LOW, GTValues.VN[cap]), null, null
            )
        }
        return super.matchRecipe(recipe)
    }
}
```

## 5. 多方块部件

| 要做什么 | 调什么 | 参数给什么 |
|---|---|---|
| 写一个部件 | `class X(holder, tier) : TieredPartMachine(holder, tier)` | 分级部件基类；`IMultiPart` 已经由它实现 |
| 声明一种新能力 | `PartAbility("gtet_xxx")`（`PartAbility.java:67-69`，构造函数就是 public，没有全局注册表） | 名字只用于调试（能力表按对象身份区分）；本项目的 `ETPartAbility.THREAD_HATCH`（`ETPartAbility.kt:42`） |
| 把方块登记进能力 | `.abilities(ETPartAbility.THREAD_HATCH)`（`MachineBuilder.java:519`） | 登记发生在**方块注册回调**里 `ability.register(builder.tier, block)`（`:778`），所以 `.tier()` 必须先设好 |
| 结构里认这个能力 | `Predicates.abilities(能力).setMaxGlobalLimited(1).setPreviewCount(1)` | 见 `ETTestMultiblocks.kt:96-100`；`maxGlobalLimited(1)` = 全局最多一个 |
| 禁止部件共享 | `override fun canShared(): Boolean = false` | `IMultiPart#canShared()` 默认 `true`（`IMultiPart.java:27-29`）；`false` 时物品提示会带 `gtceu.part_sharing.disabled` |
| 面板 | `IFancyUIMachine#createUIWidget(): Widget` | 返回一个 `WidgetGroup`；⚠️ 里面传的方块名要写 **lang key**（`LabelWidget` 在客户端才解析，两种语言各显示各的），见 `ThreadHatchPartMachine.kt:98` |
| 存一个玩家可改的值 | `@Persisted var currentThread: Int` + `getFieldHolder()` 挂上父类 | 见 `ThreadHatchPartMachine.kt:74-76` / `:143` / `:156-160` |

⚠️ **为什么自研能力不能图省事复用 `PARALLEL_HATCH`**：「同一个能力对象」在结构里通常配 `maxGlobalLimited(1)`
（`Predicates.autoAbilities` 就是这么配的，`Predicates.java:221`），复用会让「线程仓」和「并行仓」在结构里**互斥**；
而线程仓的语义恰恰是「在并行仓之上再叠一层」。详见 `ETPartAbility.kt:34-42` 与 `IThreadHatch.kt:16-23`。

⚠️ **`@Persisted` 字段读档后要夹取时，覆写点只能是机器类自己的 `loadCustomPersistedData`**，理由是调用顺序：

```
BlockEntity#load
  └─ IAutoPersistBlockEntity#loadManagedPersistentData(tag)
       ├─ ① IManagedAccessor.writePersistedFields(tag, getRootStorage().getPersistedFields())  // NBT → @Persisted 字段
       └─ ② loadCustomPersistedData(tag)                                                        // 这才轮到你的夹取
```

（①② 的先后是 ldlib 1.0.40.b `IAutoPersistBlockEntity` 字节码实证：`writePersistedFields` 之后才
`invokeinterface loadCustomPersistedData`。）再往下看两层：

- `IMachineBlockEntity#loadCustomPersistedData`（默认方法，`IMachineBlockEntity.java:105-108`）=
  `IAutoPersistBlockEntity.super.loadCustomPersistedData(tag)`（空实现）+ `getMetaMachine().loadCustomPersistedData(tag)`；
- 而 `MetaMachine#loadCustomPersistedData`（`MetaMachine.java:250-254`）**只把调用转发给 traits**。

所以：**写在机器类里的覆写会被调用，写在一个 trait 里则收不到机器自己的那次调用**。先 `super`、再夹：

```kotlin
/** 存档读回来之后把线程数夹回 [MIN_THREAD] .. [maxThreads]：越界值不漏进线程逻辑。 */
override fun loadCustomPersistedData(tag: CompoundTag) {
    super.loadCustomPersistedData(tag)                     // 先让 @Persisted 字段落地
    currentThread = currentThread.coerceIn(MIN_THREAD, maxThreads)
}
```

（`ThreadHatchPartMachine.kt:138-141`。同类还有：旧存档里存的数大于当前上限、手改 NBT、0 / 负数。）

## 6. 数据生成与文案

双语是**成对登记**的两条路：

| 要什么 | 写什么 | 落在哪 |
|---|---|---|
| 机器 / 方块的中文名 | `LangUtil.BLOCK_LANG[id] = "多方块测试机"`（`LangUtil.kt:7`） | `LangHandler` 写进 `zh_cn` 的 `block.<modid>.<id>`（`LangHandler.kt:231`） |
| 同一台的英文名 | 注册链上 `.langValue("Multiblock Test Bench")`（`MachineBuilder.java:314-317`） | Registrate 写进 `en_us` 的同名键（`register()` 里 `blockBuilder.lang(...)`，`:663-666`） |
| 物品 / 创造页中文名 | `LangUtil.ITEM_LANG` / `TAB_LANG`（`ETREGISTRATE.kt:57` / `GTETCreativeModeTabs.kt:28`） | `item.<modid>.<id>` / `itemGroup.<modid>.<id>`（`LangHandler.kt:230`/`:232`） |
| 任意其它双语条目（tooltip、面板文案） | `LangUtil.add(key, en, cn)`（`LangUtil.kt:13-15`） | `CUSTOM_LANG` → `LangHandler.autoGenCustomLang`（`LangHandler.kt:236-239`） |

⚠️ **登记必须赶在数据生成之前**：`GatherDataEvent` 一到，`GTETDatagen.init` 只是把 zh_cn provider 挂上
（`GTETDatagen.kt:17-19`，入口在 `CommonProxy.kt:129-132`），此时 `LangUtil` 里有什么就只生成什么。
所以「机器文案登记」统一放在 `CommonProxy#kotlinInit`（`CommonProxy.kt:52-76`）里那一串 `Xxx.initLang()`，
或者干脆放在注册函数开头（`ETThreadHatches.registerOne` 第一行就 `LangUtil.BLOCK_LANG[...] = ...`）。
挂在界面控件类上 = 赶不上。

⚠️ **`runData` 会自己删陈旧文件**，所以「删掉一个语言键」之后它**真的会从生成目录消失**，不是没生效：
`net.minecraft.data.HashCache#purgeStaleAndWrite` 会 `Files.delete(...)` 并打一行
`Caching: total files: ..., removed stale: ..., written: ...`（字节码实证，含 `Failed to delete file {}` 的 warn）。
本项目就有这种例子：线程仓的 `tooltip.0/1/2` 与面板说明键随显示简化删掉后，`runData` 之后不再出现在
`src/generated` 里（`ETThreadHatches.kt:155-158`）。

⚠️ **跑 `runData` 之前先留一份 `git status` 快照**：`data` 这个 run config 是
`--mod gtetcore --all --output src/generated/resources/ --existing src/main/resources/` 再加
`--existing-mod gtceu`（`scripts/minecraft.gradle:61-86`），而 `src/generated/resources` 是 **sourceSet**
（`scripts/project.gradle:20`）、**被 git 跟踪**。历史上因为堆不够（`data` 单独压到
`-Xms256M -Xmx3G`，理由写在 `minecraft.gradle:78-85`：Gradle 守护进程 3G 与客户端 6G 并存会把系统提交内存打满，
报 `There is insufficient memory for the Java Runtime Environment to continue`）跑到一半失败过，
生成目录只剩半份 —— 而删陈旧文件那一步是**真删**。所以别等出事才发现没得回退。

## 7. 配方与流体数量：本仓库现成的控件 / 工具类

| 类 | 什么时候用它 | 怎么挂 |
|---|---|---|
| `PhantomCountSlotWidget(handler, slotIndex, x, y, onCountChanged)`（`PhantomCountSlotWidget.kt:34-40`） | 需要一个「幽灵物品槽 + 鼠标中键弹框设任意数量」的界面（配方编辑器） | 直接 `addWidget(...)`；⚠️ **不能** `setClientSideWidget()`（`:22-23`），数量必须走控件自己的 client action 才到得了服务端（`:116`）；上限是 `Int.MAX_VALUE`，与物品堆叠上限无关 |
| `FluidCountSlotWidget(fluidTank, tank, x, y, w, h, fluidGetter, fluidSetter, onAmountChanged)`（`FluidCountSlotWidget.kt:29-39`） | 同上，但换成一格流体、单位 mB | 同样 `addWidget(...)`；构造里已经 `setShowAmount(true)`（`:43`） |
| `DraftFluidTanks(capacity)`（`DraftFluidTanks.kt:23-26`） | 一个配方要 4 个流体输入 / 6 个流体输出时（`FluidTank` 是单槽、还会按容量截断） | 实现 Forge 的 `IFluidHandler`，直接当 `TankWidget` / `FluidCountSlotWidget` 的数据源；`setFluid(i, stack, amount)` 改量（`:45`）、`serializeNBT()` / `deserializeNBT()` 存取（`:121` / `:147`，⚠️ 反序列化**不读 `Size`**、不重建槽位，`:131-140`） |
| `RecipeCodeWriter`（`RecipeCodeWriter.kt:42`） | 要把草稿导成能直接粘进 `ALLRecipes` 的 Kotlin 片段 | `toCode(draft)`（`:51`）/ `previewLines(draft)`（`:69`）/ `export(draft): File`（`:243`，目录取 `GTETConfig.recipeExportDirectory()`，默认 `GtetExport/recipes`，`GTETConfig.java:66` / `:325-327`） |

挂法直接照 `RecipeEditorBehavior` 那几处：物品槽 `PhantomCountSlotWidget(draft.inputs, i, 0, 0) { touch() }`
（`RecipeEditorBehavior.kt:231` / `:235`）、流体槽 `FluidCountSlotWidget(...)`（`:239` / `:247`）。
⚠️ 新增控件的对话框文案（``PhantomCountSlotWidget.initLang()`` / ``FluidCountSlotWidget.initLang()``）
同样要在数据生成之前调 —— `CommonProxy.kt:60-61` 就是干这个的。

## 8. 排错对照表

| 现象 | 最可能的原因 | 怎么确认 |
|---|---|---|
| 材质紫黑块 | 缺 blockstate / model json：① 注册链一个模型都没给，走了不存在的 `block/machine/template/<name>` 兜底（`MachineBuilder.java:659-661`）；② 这份 jar 只跑过 `build` 没跑过 `runData`（jar 里根本没有那些 json） | 看 `src/generated/resources/assets/<modid>/blockstates` 与 `models/`；打包进内嵌 jar 的那份跑 `gradlew verifyPatchedJarjar`（`scripts/patches.gradle:382-408`，它会探 assets 条目数与 `models/block/` 是否存在，`:95-117`） |
| 开机日志里 `Exception loading blockstate definition: '<modid>:blockstates/xxx.json' missing model for variant` | 同上（缺 json 的表现就是这个） | 同上；GTMThings 那侧的历史记录与基线数字在 `scripts/patches.gradle:277-288` |
| EMI 里没有这台机器的多方块预览 | ① `.multiblockPreviewRenderer(false, ...)` 关掉了（EMI 按 `isRenderXEIPreview` 过滤，`MultiblockInfoEmiCategory.java:26`）；② `shapes` 供应商抛异常（`getPreview` 的 AIOOBE，见第 3 节） | 日志搜 `getPreview` / `ArrayIndexOutOfBounds`；把 `definition.shapes` 换成真的打印一遍页数 |
| EMI 里**一整批**东西不见了：配方类别在、里面是空的，多方块信息 / 矿脉图 / 编程电路全都没有 | 某台机器的 `shapes` 供应商在**构造期**炸了：ldlib 1.0.50 的 `ModularEmiRecipe` 构造器就调 `widget.get()`（`ModularEmiRecipe.java:46`），异常从 `MultiblockInfoEmiCategory.registerDisplays(:28)` 穿到 `GTEMIPlugin.register:68`，EMI 放弃该插件余下注册 | 日志里搜 `Exception loading plugin provided by gtceu`；确认这个会话**再也没有** `Reloaded plugin from gtceu`（见第 3 节的实测栈） |
| 机器完全不出现（创造页 / EMI / `GTRegistries` 里都没有，且**无任何报错**） | 注册监听器的泛型写错了（不是 `RegisterEvent<*, *>`），方法一次都没被调用 | 断点或日志打在 `registerMachines` 第一行；数一下 `GTRegistries.MACHINES.registry().size()`（`CommonProxy.kt:104-107` 就是这行的日志） |
| 机器注册直接抛 `[register] registry ... has been frozen` | 注册发生在 `GTRegistries.MACHINES.freeze()` 之后（`GTMachines.java:1101`）—— 例如在 `FMLCommonSetupEvent` 里注册 | 把注册挪回 `RegisterEvent` 里 |
| 仓能放上去，但多方块不成型 / 该槽位「没识别」 | 能力的方块表是**首取即定的快照**，谓词构造早于仓注册（`PartAbility.java:61-62`） | 确认注册顺序：仓在前、多方块在后（`MachineRegister.kt:8-9`） |
| 「能插仓但没效果」 | 多方块的图案没有走 `autoAbilities(..., checkParallel=true)` 那一支（超频仓靠 mixin 追加，`MixinPredicatesAutoAbilities.java:59-77`）；或结构里超频仓不止一个（只取第一个命中的，`MixinOverclockingLogic.java:193-199`） | 看这台机器图案里 `autoAbilities` 的第三个参数；JEI 预览里看几个预览格 |
| 玩家改的设定重载存档就丢 / 越界值漏进逻辑 | 字段没 `@Persisted`、`getFieldHolder()` 没接父类，或者夹取写在了 trait 里（`MetaMachine` 那层只转发 traits，`MetaMachine.java:250-254`） | 覆写机器类的 `loadCustomPersistedData` 并先 `super`（见第 5 节） |
| dev 里一切正常，打包后异常 | 三件事分别查：① **SRG ↔ Mojmap**：dev 是 Mojmap、产物走 `reobfJar`，对第三方补丁类的 mixin 要 `remap = false`（照 `AdvancedTerminalBehaviorMixin.java:41`）；② **jarjar 内嵌版本写死**，与编译用的版本必须一致（`build.gradle:57-76`、`scripts/patches.gradle:26-41`），不一致就是「编译过、装包里是另一份」；③ **`ContainedDeps` 是无条件加载**，整合包里**不能**再单独放 GTM / GTMThings，否则同一个 mod 两份（`build.gradle:67-68`、`:125-132`） | `gradlew verifyPatchedJarjar` 校验内嵌的两个 jar 是不是补丁版；`build/jarjar/` 看实际嵌进去的文件名与 sha256 |
| 内嵌 jar 是官方原件（GTMThings 界面看起来没打过补丁） | `collectJarjarDeps` 找不到补丁产物时**只 warn 不失败**（`build.gradle:100-113`），会把 curse 上的原件嵌进去 | 跑 `gradlew verifyPatchedJarjar`，或看构建日志里的 `[gtetcore] 本次退回 curse 官方原件` |

---

[← 上一节：04 模块化机器代码案例](04-模块化机器代码案例.md) ｜ [返回目录](<../GTM开发教程(编写中).md>) ｜ [下一节：06 游戏内怎么用 →](06-游戏内怎么用.md)
