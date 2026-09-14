# ME 集成部件（设计 / 实现方案）

> 这套部件的目标：让多方块机器直接吃 **AE2 网络**里的物品与流体，并且能按**标签**挑选要拉什么。
> 本文只写「要做哪几件、每件怎么落地、哪些坑必须绕开」；具体代码在同名的部件类里。
>
> ⚠️ 明确**不做**的事：**远程 / 无线 ME 连接**（部件只跟贴着的 AE 网络打交道，不做跨维度/无线拉取）。

## 1. GTM 已经自带的（直接用，不要重做）

GTCEu 7.5.3 在 `GTAEMachines` 里已经注册了整套 ME 部件，类在 `integration/ae2/machine/`：

| 注册名 | 类 | 能力 | tier |
|---|---|---|---|
| `me_input_bus` | `MEInputBusPartMachine` | `IMPORT_ITEMS` | EV |
| `me_stocking_input_bus` | `MEStockingBusPartMachine` | `IMPORT_ITEMS`（库存拉取） | LuV |
| `me_output_bus` | `MEOutputBusPartMachine` | `EXPORT_ITEMS` | EV |
| `me_input_hatch` | `MEInputHatchPartMachine` | `IMPORT_FLUIDS` | EV |
| `me_stocking_input_hatch` | `MEStockingHatchPartMachine` | `IMPORT_FLUIDS`（库存拉取） | LuV |
| `me_output_hatch` | `MEOutputHatchPartMachine` | `EXPORT_FLUIDS` | EV |
| `me_pattern_buffer` | `MEPatternBufferPartMachine` | 物品/流体 进出四种 | LuV |
| `me_pattern_buffer_proxy` | `MEPatternBufferProxyPartMachine` | 同上 | LuV |

所以「ME 样板总成」「ME 输入总线 / 输入仓」「库存输入总线 / 仓」**GTM 已经有了**，本项目的四件都是在它们之上补缺。

## 2. 要做的四件

### 2.1 标签过滤的库存输入总线 / 输入仓（第一批）

- **语义**：在库存拉取的基础上加**白名单 / 黑名单两个标签表达式**（例如 `#forge:ingots`，多条用逗号分隔）。
  只有「匹配白名单且不匹配黑名单」的物品/流体才会被从 AE 网络拉进来。
  面板提供两个输入框 + 一个**幻影槽**（往里放一个物品/流体就自动填出它所属的标签），拆方块时配置能存进物品 NBT。
- **参考**：GTOCore 的 `ITagFilterPartMachine`（接口 + 面板）、`METagFilterStockBusPartMachine`（物品，`extends MEStockingBusPartMachine`）、
  `METagFilterStockHatchPartMachine`（流体）。三者均为 LGPL-3.0，本项目只借鉴形状、在注释里点名来源。
- ⚠️ **技术关口（已核实；与最初推断不同）**：GTCEu 7.5.3 的库存部件**没有** `test(AEKey)` 这种判定钩子
  （源码全量 grep 与字节码双向核实的结论：零命中）。GTOCore 那两件之所以"看起来像覆写"，是因为它把
  `MEStockingBusPartMachine`（约 275 行）**整份复制**进了自己的包，再在**副本**里声明 `test(AEKey)` —— 而且它复制的是
  GTCEu **7.0.0 时代**的 API（`syncME()` / `getCachedInventory()` 等），与本项目用的 7.5.3 并不相同。
  所以本项目走**公开扩展点**：`IAutoPullPart#setAutoPullTest(Predicate<GenericStack>)`（public 接口方法），
  它在 `refreshList()` 里决定「哪些 key 允许被备货」（bus 与 hatch 各一处）。
  ⚠️ 陷阱：`IMEStockingPart#addedToController` 的默认实现会把 `autoPullTest` 覆盖成「不同仓去重」，
  因此必须在 `super.addedToController(...)` **之后**重新装上自己的判定，否则会被静默顶掉。

#### 2.1.1 同一件部件还要支持「定量拉取模式」

项目计划（`计划.md`）里把库存输入总线写成「包括**标签模式**、**定量拉取模式**」两种，语义是：

- **标签模式**：上面那套白/黑名单标签过滤；
- **定量拉取模式**：⚠️ **「每次拉 N 个」——一批一批拉**，**不是** GTCEu 现成的「保底 N」（低于 N 就补到 N）。
  GTCEu 的库存总线已经有保底语义（`min_item_count` / `min_fluid_count`，见 `AutoStockingFancyConfigurator`），
  本项目要的是「每次从 AE 取数时就只取固定的 N 个」。N 必须可配（面板给一个合理范围）。

⚠️ 实现要点（已定位到具体落点）："能拉多少就拉多少"发生在**两处**——
① **备货量**：`syncME()` 用 `extract(key, Long.MAX_VALUE, SIMULATE)` 把**全网存量**写进 slot 的 stock；
② **真取数**：`ExportOnlyAEStockingItemSlot#extractItem` / `ExportOnlyAEStockingFluidSlot#drain`，用**调用方给的 amount** 直接 `extract(key, amount, MODULATE)`。
所以只改「要不要这个 key」的判定（`setAutoPullTest`）**管不到数量**。
⚠️ 更关键：GT 的配方匹配是靠**模拟抽取**判定的（`handleRecipe(..., simulate = true)`），
只在 `MODULATE` 一侧卡量会让配方"看到足量 → 开起来 → 实际拿不到 → 卡配方"，
**必须模拟与真实两条路统一卡**。
代价评估（实测，不是估计）：不需要复制整份库存实现（约 400 行）—— 但 GTM 的 slot 是 `private` 内部类、跨包不能继承，
所以取数点只能靠**换掉 inventory** 拿到控制权：`ExportOnlyAEItemList` / `ExportOnlyAEFluidList` 有 public 的
`(MetaMachine, int, Supplier<Slot>)` 构造器，`ExportOnlyAEItemSlot` / `ExportOnlyAEFluidSlot` 是 public 可继承的，
覆写 `createInventory` / `createTank` + 重抄那段 AE 抽取体，合计约 **150 行**。
（"换 inventory"对存档安全：LDLib 的 `ArrayAccessor.writeManagedField` 在元素类型非 `IManaged` 时按**已有元素**写入、
不会按字段声明类型重建数组，所以构造函数里造出来的 slot 子类实例在存档读回后仍然保留。）


### 2.2 多阶段样板总成（第二批）

- **容量档位**（用户指定，按 9 列面板规整：余数不足半行则舍去、达到半行则补满整行）：

  | 阶段 | tier | 容量 | 面板行数 |
  |---|---|---|---|
  | 一 | LuV | **27** | 9×3 |
  | 二 | UV | **63** | 9×7（原定 64 = 7×9+1，余 1 舍去） |
  | 三 | UEV | **126** | 9×14（原定 125 = 13×9+8，余 8 补满） |
  | 四 | UXV | **216** | 9×24 |

- ⚠️ **容量必须复制实现，不能继承**：GTM 的 `MAX_PATTERN_COUNT = 27` 是 `protected static final`，而且**用在父类的字段初始化里**
  （`patternInventory = new CustomItemStackHandler(27)`、`internalInventory = new InternalSlot[27]`、`detailsSlotMap`）——
  子类无论怎么写都还是 27。要做更大容量只能把那份实现（约 600 行）复制过来参数化。
- ⚠️ **代理版要一起处理**：`MEPatternBufferProxyPartMachine` 用 `instanceof MEPatternBufferPartMachine` 找宿主，
  本项目的复制版它认不出来 → 代理版要么一并复制、要么用 mixin 让它认同本项目的类。两条路都要给出证据。

### 2.3 本项目自己的库存输入总线 / 库存输入仓（第二批）

沿用 GTM 的类作为父类，注册到本项目自己的命名空间与创造页，行为与 GTM 保持一致（便于以后独立调数值 / 换贴图）。

### 2.4 二合一库存输入总成（第二批）

- **一个方块**，同时挂 `IMPORT_ITEMS` + `IMPORT_FLUIDS`，并且**物品与流体都走「库存拉取」**（不是把两个现成部件硬拼在一起）。
- 先例：GTM 的 `me_pattern_buffer` 就是一块挂四种能力的部件，所以「一个方块多种能力」在结构里是成立的。
- 它同样带标签过滤（物品/流体各一套白黑名单）。

## 3. 参考物（只点名，不写本机路径）

| 来源 | 用到的类 | 说明 |
|---|---|---|
| GTCEu / GTM 7.5.3 | `GTAEMachines`、`MEStockingBusPartMachine`、`MEStockingHatchPartMachine`、`MEInputBusPartMachine`、`MEInputHatchPartMachine`、`MEPatternBufferPartMachine`、`MEPatternBufferProxyPartMachine`、`IMEStockingPart`、`IGridConnectedMachine` | 本体与父类 |
| GTOCore（LGPL-3.0） | `ITagFilterPartMachine`、`METagFilterStockBusPartMachine`、`METagFilterStockHatchPartMachine`、`MESimplePatternBufferPartMachine`、`MEInputBufferPartMachine` | 标签过滤与样板总成变体的形状 |
| GTCA（LGPL-3.0） | 部件与结构谓词的写法 | 可读的同版本参考 |

## 4. 批次安排

两批都要改同一批注册文件（`common/data/machine/hatch/`）并各跑一次 `runData`，所以**串行推进**，不并行。
