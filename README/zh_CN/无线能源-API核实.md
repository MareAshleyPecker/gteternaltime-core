# 无线能源（时间流 TF）· GTM API 契约核实报告

> 只读核查，未改任何代码。结论一律以**代码**为准，与 `无线能源链接仓设定.md` 冲突处直接标出。
> 日期：2026-09-27 · 目标版本：Forge 1.20.1 + GTCEu/GTM **7.5.3** + LDLib 1.0.50 + Kotlin/Java 混编。

## 0. 证据源与读法

| 源 | 本报告中的写法 | 说明 |
|---|---|---|
| GTM 7.5.3 源码树 | `com/gregtechceu/gtceu/...` | 相对其 `src/main/java/`；源码在独立检出目录 |
| 打过本仓补丁的 GTM jar | `<gtm jar>` | 本仓 `patchJar/maven/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/gtceu-1.20.1-7.5.3.jar` |
| LDLib 1.0.50 jar | `<ldlib jar>` | curse.maven `ldlib-626676:7809449` 的 Gradle 缓存产物 |
| 本仓代码 | `src/...`、`README/...` | 相对仓库根 |

javap 命令模板（把 `<jar>` 换成上表的占位）：

```
javap -p -classpath <gtm jar>   com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
javap -p -classpath <gtm jar>   com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer
javap    -classpath <ldlib jar> com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder
javap    -classpath <ldlib jar> com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory
```

**方法说明**：全部为静态读码 + javap 反编译；**没有跑 runClient**，无运行时验证。所有"未确认"项集中列在第 11 节。

## 0.1 八条一行结论

| # | 主题 | 一行结论 | 能不能用 |
|---|---|---|---|
| 1 | 部件成为能量来源 | `PartAbility.register(int tier, **Block block**)`，**没有 amperage 参数**；`Predicates.abilities` 的快照发生在**首次 `getAllBlocks()`**（懒记忆化），"先仓后多方块"这条硬顺序成立 | **可用**，但**不需要** amperage 那套假设 |
| 2 | `IEnergyContainer` 谁调谁 | 配方耗电走 `handleRecipeInner(IO.IN,…)` → 从**仓自己的缓冲区** `changeEnergy(-n)`；外部灌电才走 `acceptEnergyFromNetwork` | **设定被推翻**：不能在耗电方法上"当场拉电"，必须先把缓冲区喂满 |
| 3 | 远处取电 | `GTCapabilityHelper.getEnergyContainer(Level, BlockPos, @Nullable Direction)`，取不到返回 `null` | **可直接用** |
| 4 | 扣 TF 的挂点 | `RecipeLogic` 的 tick 链**全是 public/protected、无 private**；但只能影响"用我们自己的逻辑类"的机器 | **自有机器的子类可直接用；任意 GTM 多方块要 mixin** |
| 5 | 推进配方进度 | 只有 `public void setProgress(int)`；**没有 `setDuration`**（javap 实证）。EU 是 tick 输入，跳 tick = **白省 EU + 跳过 tick 输入/输出** | **能用，但有漏洞**，必须自己补扣 |
| 6 | 绑定手势 | `onDataStickUse(Player, ItemStack)` / `onDataStickShiftUse(...)`；闪存键 `targetX/targetY/targetZ/face/dim`（**含维度**） | **可直接抄** |
| 7 | 物品存自定义单位 | 物品**用不了** LDLib 的 `ManagedFieldHolder`（要 `Class<? extends IManaged>`）；`IElectricItem` 是 EU 语义硬编码 | **手写 NBT + `IAddInformation`**，无现成容器接口 |
| 8 | 全服唯一 / 拒绝成型 | 全服单例抄 `VirtualEnderRegistry`（`server.overworld().getDataStorage()`）；拒绝成型抄 `LargeMinerMachine#checkPattern()` | **可用，但唯一性判定不能放进 `checkPattern()`**（异步线程） |

---

## 1. 部件怎么成为多方块的能量来源

### 1.1 确切签名

```java
// com/gregtechceu/gtceu/api/machine/multiblock/PartAbility.java:71-73
public void register(int tier, Block block) {
    registry.computeIfAbsent(tier, T -> new HashSet<>()).add(block);
}
```

- **不是** `register(int tier, int amperage, MachineDefinition...)`。**只有 `(tier, block)` 两个参数，没有 amperage、没有 MachineDefinition。**
- **打过补丁的那个 jar 二次确认**（这是真正编译期生效的字节码）：

```
javap -p -classpath <gtm jar> com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
  private final java.util.function.Supplier<java.util.Collection<net.minecraft.world.level.block.Block>> allBlocks;
  public void register(int, net.minecraft.world.level.block.Block);
  public java.util.Collection<net.minecraft.world.level.block.Block> getAllBlocks();
```

  另外：本仓 `modpatch/gtceu-7.5.3/` 下的 8 个补丁文件**不包含** `PartAbility.java` → 该文件源码与 patched jar 一致，不存在"源码与产物不一致"的风险。
- 调用点唯一：`com/gregtechceu/gtceu/api/registry/registrate/MachineBuilder.java:778`

```java
.onRegister(b -> Arrays.stream(builder.abilities).forEach(a -> a.register(builder.tier, b)));
```

- `builder.tier` 来自 `.tier(...)`；`b` 是注册出来的方块本体。
- **结论：tier 是能力表里唯一的维度。amperage 不在能力层**——它在机器定义/机器类自己的构造参数里，见 §2.1。

### 1.2 常量表（写"能流转换仓"要用的那几个）

| 常量 | 名字字符串 | 声明位置 |
|---|---|---|
| `INPUT_ENERGY` | `input_energy` | `PartAbility.java:29` |
| `OUTPUT_ENERGY` | `output_energy` | `:30` |
| `SUBSTATION_INPUT_ENERGY` | `substation_input_energy` | `:31` |
| `SUBSTATION_OUTPUT_ENERGY` | `substation_output_energy` | `:32` |
| `INPUT_LASER` | `input_laser` | `:43` |
| `OUTPUT_LASER` | `output_laser` | `:44` |

本仓自建能力同理（`src/main/kotlin/rain/gtetcore/gtet/api/capability/ETPartAbility.kt:31,41`）——`PartAbility(String name)` 是 `public` 构造函数（`:67`），直接 new 即可，**不需要 mixin 塞静态字段**。

### 1.3 "快照"到底发生在哪一刻（**本仓文档措辞需修正**）

| 事实 | 证据 |
|---|---|
| `allBlocks` 是懒记忆化缓存 | `PartAbility.java:61-62` `GTMemoizer.memoize(() -> registry.values().stream().flatMap(...).toList())` |
| 缓存实现：首次 `get()` 才求值，之后永不变 | `com/gregtechceu/gtceu/utils/memoization/MemoizedSupplier.java:16-22` |
| `getAllBlocks()` 只读这个缓存 | `PartAbility.java:75-77` |
| `Predicates.abilities(...)` **在构造谓词那一刻**就取 | `com/gregtechceu/gtceu/api/pattern/Predicates.java:134-137` |

- **结论（能否用）**：本仓 `README/zh_CN/GTM开发教程/05-API调用速查.md:45-49` 的**结论成立**（"先注册仓、再注册用到它的多方块"是硬顺序），但机制表述不精确：
  - 说"构造那一刻的快照"→ 准确说法是"**首次 `getAllBlocks()` 那一刻**的快照"。
  - 差别在实践上不影响那条顺序规矩，因为 `Predicates.abilities(...)` 是唯一入口且立刻取值。
- **补充事实**：`MemoizedSupplier.invalidate()`（`:24-27`）是公开的，但 **GTM 全仓没有任何地方调用它**（全量 grep 0 命中）→ 一旦取过，本进程内再也无法刷新，**没有"重载后重扫"的口子**。
- **模式谓词是懒求值的**，所以"谁先取"是可推断的：多方块图案在 `MultiblockMachineBuilder.java:135` 被包成 `GTMemoizer.memoize(...)`，只有 `getPattern()`（`IMultiController.java:134-136`）与物品 tooltip（`MetaMachineBlock.java:196`）会触发求值 → 都发生在所有机器注册完之后。

### 1.4 坑

1. **能力表登记 ≠ 结构能插**。部件自己的 `MachineBuilder.abilities(...)` 只说"它属于哪个能力"；**结构检测是拿多方块图案里的 `Predicates.abilities(...)` 去匹配方块**。两条都要有。
   - 本仓已有先例：`src/main/java/rain/gtetcore/gtet/mixin/GTM/MixinPredicatesAutoAbilities.java:55-74` 在 `Predicates.autoAbilities(ZZZ)` 的 `RETURN` 处追加 `ETPartAbility.OVERCLOCK_HATCH`。
   - ⚠️ **该 mixin 只追加了 `OVERCLOCK_HATCH`**。我们的"时流转换能源仓/供应仓/时流仓"若要在**任意 GTM/GCYM 多方块**里插得上，**必须在这条链上再追加一次**（或另写一条 mixin），否则只能在 GTET 自己的多方块上插。
2. **`setMaxGlobalLimited(1)` 的语义**：超频仓用的是它，所以一台多方块只能插 1 个。时流仓如果允许多个（多仓叠加 TF 吞吐），**不能**抄这个限制。
3. **tier 漏写 = ULV**：`builder.tier` 默认 0（本仓 05 文档第 36-37 行的说法），能力表会把它记进 ULV 档。
4. **未确认**：`GTRegistries.MACHINES.freeze()` 与 `PartAbility` 无关（能力表不是 `GTRegistry`），所以**冻结后仍可 `register`**。但我没核"运行期动态给能力表加方块"会不会有并发问题（结构检测在异步线程）。

---

## 2. `IEnergyContainer` 到底谁调谁

### 2.1 接口面（`com/gregtechceu/gtceu/api/capability/IEnergyContainer.java`）

| 方法 | 行 | 语义 |
|---|---|---|
| `long acceptEnergyFromNetwork(Direction side, long voltage, long amperage)` | `:17` | **跨方块传输唯一入口**。返回"用掉几安" |
| `boolean inputsEnergy(Direction side)` | `:22` | 本面是否能收 |
| `long changeEnergy(long differenceAmount)` | `:39` | **只给内部用**（工作时扣、发电时充） |
| `default long addEnergy(long)` | `:47-49` | → `changeEnergy(+n)` |
| `default long removeEnergy(long)` | `:57-59` | → `-changeEnergy(-n)` |
| `long getEnergyStored()` / `getEnergyCapacity()` | `:71,76` | |
| `long getInputVoltage()` / `getInputAmperage()` | `:111,105` | |
| `IEnergyContainer DEFAULT` | `:129-165` | 全 0 的空实现 |

### 2.2 多方块怎么把部件聚成一个能量源

调用链（全部为 GTM 源码行号）：

| 步 | 位置 | 做什么 |
|---|---|---|
| 1 | `api/machine/multiblock/WorkableMultiblockMachine.java:120-158` | `onStructureFormed()` 里遍历 `getParts()`，对每个部件 `part.getRecipeHandlers()` → `addHandlerList(...)` |
| 2 | `api/capability/recipe/IRecipeCapabilityHolder.java:37-47` | `addHandlerList` 把 handler 按 `IO` 与 `RecipeCapability` 铺进 `getCapabilitiesProxy()` / `getCapabilitiesFlat()` |
| 3 | `api/machine/multiblock/WorkableElectricMultiblockMachine.java:237-247` | `getEnergyContainer()` = `getCapabilitiesFlat(IO.IN, EURecipeCapability.CAP)` 里挑出 `IEnergyContainer`，**新建 `EnergyContainerList`** |
| 4 | `api/machine/multiblock/WorkableElectricMultiblockMachine.java:76-80` | `onStructureFormed()` 里缓存成字段 `energyContainer`；`onStructureInvalid()`/`onPartUnload()` 置 `null` |
| 5 | `api/misc/EnergyContainerList.java:12-68` | **就是那个聚合器**。构造时把所有成员的 `voltage*amperage` 累加后**压缩档位**（`:78-107`） |

- 聚合器类名就是 **`EnergyContainerList`**，建在 `WorkableElectricMultiblockMachine#getEnergyContainer()`（`:237`），**每次调用都新建**（不是常驻单例；控制器只缓存结果）。
- 还有另一处独立聚合：`api/blockentity/MetaMachineBlockEntity.java:195` 把方块实体的能力表包成 `EnergyContainerList`（单成员时直接返回该成员）——那是**面向上层 Forge 能力**的，不是配方用的。
- 还有别的多方块自己再建一层（`FusionReactorMachine:139`、`CleanroomMachine:207`、`LargeMinerMachine:157`、`BedrockOreMinerMachine:61`、`FluidDrillMachine:63`、`PowerSubstationMachine:127-128`）——**这些机器不吃 `WorkableElectricMultiblockMachine#getEnergyContainer()` 的默认聚合**，接线时要注意。

### 2.3 配方耗电到底调哪个方法（**核心**）

| 步 | 位置 | 事实 |
|---|---|---|
| 1 | `api/machine/trait/RecipeLogic.java:206-247` | `serverTick()` |
| 2 | `:278-326` | `handleRecipeWorking()` |
| 3 | `:377-388` | `handleTickRecipe(recipe)` → `matchTickRecipe`（模拟）→ `handleTickRecipeIO(recipe, IO.IN)` → `handleTickRecipeIO(recipe, IO.OUT)` |
| 4 | `:552-558` | `handleRecipeIO` / `handleTickRecipeIO` → `RecipeHelper.handleRecipeIO(...)` |
| 5 | `api/recipe/RecipeHelper.java:198-237` | 新建 `RecipeRunner`，`simulated` 由调用点决定 |
| 6 | `api/recipe/RecipeRunner.java:62-85` | 按 `capabilityProxies` 找 handler |
| 7 | `api/machine/trait/NotifiableEnergyContainer.java:307-334` | **`handleRecipeInner(IO.IN, recipe, left, simulate)`** |

第 7 步的实体（`NotifiableEnergyContainer.java:316-322`）：

```java
long totalEU = stack.getTotalEU();
long canTransfer = Math.min(totalEU, (io == IO.IN ? this.getEnergyStored() :
        this.getEnergyCapacity() - this.getEnergyStored()));
if (!simulate) {
    // invert the EU value if we're doing inputs (inputting *to the recipe* -> removing from handlers)
    this.changeEnergy(io == IO.IN ? -canTransfer : canTransfer);
}
```

**结论（设定被推翻）**：

- 配方消耗能量**既不是 `removeEnergy`、也不是 `acceptEnergyFromNetwork`**，而是 `handleRecipeInner` 内部**读自己缓冲区的 `getEnergyStored()`，再 `changeEnergy(-n)`**。
- 外部电网灌电才走 `acceptEnergyFromNetwork`：
  - 相邻供能方的 `NotifiableEnergyContainer.serverTick()`（`:163-186`）→ `GTCapabilityHelper.getEnergyContainer(...)` → `dest.acceptEnergyFromNetwork(oppositeSide, V, A)`；
  - 或线缆网络 `common/pipelike/cable/EnergyNetHandler.java:43-109` → `dest.acceptEnergyFromNetwork(facing, pathVoltage, amps)`（`:91`）；
  - 该文件 `:139-140` 还专门警告：**"Do not use changeEnergy() for cables! Use acceptEnergyFromNetwork()"**。
- ⇒ **我们想要的"多方块要电时，无线仓当场从塔/链路拉电"不能挂在配方耗电方法上**——那条路径只会从仓自己的缓冲区里扣。要做出"当场拉"的效果，只有三条路：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A** | 让无线仓的缓冲区在配方扣费**之前**就是满的：`subscribeServerTick` 每 tick 从塔灌满自己的 `energyStored` | 最简单、不改任何 GTM 语义。代价是"按 tick 粒度"而非"按需求瞬间"，且缓冲容量决定单 tick 吞吐上限 |
| **B** | 无线仓用 `NotifiableEnergyContainer` 的**子类**，覆写 `handleRecipeInner(IO.IN, …)`（**public，可覆写**，javap 实证），先补货再 `super` | 精确"用到才拉"。**推荐**：这是唯一能表达"按需求拉"的挂点 |
| **C** | 覆写 `getEnergyStored()` / `changeEnergy()` 去代理塔 | ⚠️ 不推荐：`getEnergyStored()` 同时喂 UI、`getContents()`（`:336-340`）与并行的库存口径，代理化会污染一大片语义 |

### 2.4 两个必须知道的连带语义

1. **多方块的 tier 与超频基准由能量仓的 `getInputVoltage()` 决定，不是控制器自己定的**：
   - `WorkableElectricMultiblockMachine.java:79` `tier = GTUtil.getFloorTierByVoltage(getMaxVoltage())`
   - `:250-278` `getMaxVoltage()`：非发电机取 `energyContainer.getHighestInputVoltage()`；**同最高电压的仓 ≥2 个时允许 tier+1**（`:269-277`）
   - `:206-231` `getOverclockVoltage()`
   - ⇒ 无线仓必须给出合理的 `getInputVoltage()/getInputAmperage()`，否则整机 tier 与超频全错。
2. **仓的缓冲容量会进"整机储能"汇总**：`EnergyContainerList.java:156-163` 把成员的 `getEnergyStored()` 求和。它出现在 `MultiblockDisplayText.addEnergyUsageLine(energyContainer)`（`WorkableElectricMultiblockMachine.java:122`），并且 `EURecipeCapability.getMaxParallelByInput(tick=false)`（`:114-120`）会把 IN 侧 EU handler 的 `getContents()` 当库存来算并行上限（`NotifiableEnergyContainer.getContents()` `:336-340` 返回的就是"储能 + 安培"）。
   - ⇒ 无线仓给**巨大缓冲**会虚增显示、并可能放大非 tick 并行预算。建议容量贴着 `getHatchEnergyCapacity(tier, amperage) = V[tier]*64*A`（`EnergyHatchPartMachine.java:112-114`）这个既有口径来定。

### 2.5 对照实现（可抄）

| 类 | 位置 | 可抄的点 |
|---|---|---|
| `EnergyHatchPartMachine` | `common/machine/multiblock/part/EnergyHatchPartMachine.java` | `createEnergyContainer`（`:51-65`）：IN 用 `receiverContainer(this, V*16*A, V, A)`、OUT 用 `emitterContainer(this, V*64*A, V, A)`；`setSideInputCondition(s -> s == getFrontFacing() && isWorkingEnabled())`；`setCapabilityValidator(...)`；`amperage` 是 `@Getter protected int`（`:34-35`）放在**机器构造参数**里 |
| `LaserHatchPartMachine` | `common/machine/multiblock/part/LaserHatchPartMachine.java` | 激光仓同构，容器换 `NotifiableLaserContainer`（`:39-45`）；`canShared() == false`（`:54-57`）；容器字段是 `@Persisted`（`:33-34`）。**IEnergyContainer 也由它暴露**（`NotifiableLaserContainer` 与 `EnergyContainerList.acceptEnergyFromNetwork` 都实现该接口，`api/misc/LaserContainerList.java:18-22`） |

---

## 3. 从远处方块读电

```java
// com/gregtechceu/gtceu/api/capability/GTCapabilityHelper.java:43-46
@Nullable
public static IEnergyContainer getEnergyContainer(Level level, BlockPos pos, @Nullable Direction side) {
    return getBlockEntityCapability(GTCapability.CAPABILITY_ENERGY_CONTAINER, level, pos, side);
}
```

- **side 语义**：直接透传给 `blockEntity.getCapability(capability, side)`（`:126-135`）。`null` = 不带面（无面查询）。**不传 `null` 时，取到的是"那一面"的容器**——同一台机器不同面可能给出不同的容器实现。
- **取不到时返回 `null`**（`:131` `.resolve().orElse(null)`；方块没有 BlockEntity 也返回 `null`，`:128-134`）。**不会抛异常，也没有 `Optional`**。
- **另外两个可用入口**：
  - `getEnergyInfoProvider(...)`（`:48-51`）
  - `getRecipeLogic(Level, BlockPos, @Nullable Direction)`（`:73-76`）——**时序之瓶要的入口就是它**，直接拿 `RecipeLogic`。
- **能否用**：可直接用。**坑**：它**不检查区块是否加载**——`level.getBlockState(pos)` 对未加载区块会触发同步加载/返回空。时序之瓶要求"塔所在区块已加载"（设定 §5），需要**自己先 `level.isLoaded(pos)`**。

---

## 4. 配方执行链里扣 TF 的挂点

### 4.1 `RecipeLogic`（`api/machine/trait/RecipeLogic.java`）访问级别全表

`javap -p -classpath <gtm jar> com.gregtechceu.gtceu.api.machine.trait.RecipeLogic` 实证：

| 成员 | 修饰符 | 行 | 备注 |
|---|---|---|---|
| `serverTick()` | **public** | `:206` | tick 主循环 |
| `handleRecipeWorking()` | **public** | `:278` | 每 tick 的工作判定 + `progress++` |
| `handleTickRecipe(GTRecipe)` | **public** | `:377` | tick 输入/输出 |
| `handleTickRecipeIO(GTRecipe, IO)` | **protected** | `:556` | 实际扣 tick 内容 |
| `handleRecipeIO(GTRecipe, IO)` | **protected** | `:552` | 实际扣非 tick 内容 |
| `setupRecipe(GTRecipe)` | **public** | `:390` | 开配方 |
| `onRecipeFinish()` | **public** | `:507` | 结算 |
| `interruptRecipe()` | **public** | `:563` | 打断、进度清零 |
| `regressRecipe()` | **protected** | `:328` | 等待时退进度 |
| `matchRecipe(GTRecipe)` | **protected** | `:249` | 内容匹配（本仓 `ETModularRecipeLogic` 已覆写） |
| `checkRecipe(GTRecipe)` | **protected** | `:253` | 条件 + 匹配 |
| `markLastRecipeDirty()` | **public** | `:443` | 强制重选配方 |
| `updateTickSubscription()` | **public** | `:184` | 重挂 tick 订阅 |
| `setStatus(Status)` / `setWaiting(Component)` | **public** | `:414` / `:433` | |
| `progress` | **protected** `@Persisted @DescSynced` + `@Setter @Getter` | `:117-121` | ⇒ 有 `public setProgress(int)` |
| `duration` | **protected** `@Persisted @DescSynced` + `@Getter`（**无 `@Setter`**） | `:122-125` | ⇒ **无 `setDuration`**（javap 二次确认） |
| `lastRecipe` | **protected** `@Persisted @DescSynced` `@Getter` | `:99-103` | |
| `isActive` | **protected** `@Persisted @DescSynced` | `:79-82` | |
| `status` | **private** + `@Getter`；写入口是 `public setStatus` | `:73-77` | |
| `chanceCaches` | **protected** `@Getter` | `:137-138` | |
| `subscription` | **protected** | `:139` | |
| `makeChanceCaches()` | **protected** | `:631` | |

**`RecipeLogic` 里 tick 链上一个 `private` 方法都没有**（javap 全量列表确认）→ **子类覆写完全可行，不需要 mixin**。

### 4.2 现成回调

| 回调 | 位置 | 语义 |
|---|---|---|
| `IRecipeLogicMachine#onWorking()` | `api/machine/feature/IRecipeLogicMachine.java:88-89` | `default` → `getDefinition().getOnWorking().test(this)`。在 `RecipeLogic.java:285` 被调：**返回 false → `interruptRecipe()`（进度清零、料不退）** |
| `beforeWorking(recipe)` | `:81-83` | 开配方前；false → `setupRecipe` 直接放弃（`:391-398`） |
| `afterWorking()` | `:102-104` | 结算后 |
| `onWaiting()` | `:95-97` | 进 WAITING 时 |
| `regressWhenWaiting()` | `:109` | 等待时是否退进度 |
| 定义侧 | `api/registry/registrate/MachineBuilder.java:137-148, 274-295, 699-703` | `.beforeWorking/.onWorking/.onWaiting/.afterWorking/.regressWhenWaiting(...)` 写进 `MachineDefinition` |

### 4.3 TF 扣费的可行落点（按"要不要 mixin"分档）

| 方案 | 做法 | 覆盖范围 | 要 mixin？ |
|---|---|---|---|
| **甲：TF 变成一等 `RecipeCapability`** | 注册 `TFCapability extends RecipeCapability<TFStack>`；在**已有的** `MixinOverclockingLogic` 里，让 `OverclockHatchHelper.modify(...)` 返回一个 `ModifierFunction`，往 `copied.tickInputs` 里 `put` 一条 TF 内容；仓侧提供 `handlerIO=IN`、`getCapability()==TFCapability.CAP` 的 `NotifiableRecipeHandlerTrait` | **任意装了超频仓的 GTM 多方块** | **不用**（复用了已有的 mixin） |
| **乙：自有机器用自定义 RecipeLogic** | 在 `ETModularRecipeLogic`（`ETModularMachine.kt:119`）或 `ThreadedRecipeLogic`（`ThreadedRecipeLogic.kt:189`）里覆写 `handleRecipeWorking()` / `handleTickRecipeIO()` | **只有接线到这两个类的 GTET 机器** | 不用 |
| **丙：mixin `RecipeLogic`** | 直接注入 `handleRecipeWorking` / `handleTickRecipeIO` | 全服所有 GTM 机器 | **要** |

**甲方案的可行性证据（关键，值得单独说）**：

1. **自定义 `RecipeCapability` 可以向 addon 开放**：`common/data/GTRecipeCapabilities.java:23-36` 的顺序是 `unfreeze()` → 注册 5 个内置 → `AddonFinder.getAddons().forEach(IGTAddon::registerRecipeCapabilities)` → `postEvent(new GTCEuAPI.RegisterEvent<>(GTRegistries.RECIPE_CAPABILITIES, RecipeCapability.class))` → `freeze()`。
2. `RecipeCapability` 是 `public abstract`、构造函数 `protected`（`api/capability/recipe/RecipeCapability.java:35,49-56`）→ 外部包可继承。
3. `RecipeLogic.makeChanceCaches()` 遍历 `GTRegistries.RECIPE_CAPABILITIES.values()`（`:631-637`）→ 自定义能力**自动拿到概率缓存**，不用改 GTM。
4. 序列化只要实现 `IContentSerializer<T>`（`api/recipe/content/IContentSerializer.java`：`of` / `defaultValue` / `contentClass` / `codec` 四个必需项，其余有 default）。**NBT / 网络 / JSON 三路都是 default 实现**，跟着自动通。
5. `ModifierFunction` 是 `@FunctionalInterface`（`api/recipe/modifier/ModifierFunction.java:37-38`），可以直接写 lambda 包住超频结果：
   - `FunctionBuilder.build()`（`:173-203`）里 tick 内容走 `applyAllButEU(...)`（`:205-225`），**identity 时也返回 `new HashMap<>(contents)`** → 拿到的 map 是**可变**的。
   - `GTRecipe` 构造函数把 map **按引用存**（`api/recipe/GTRecipe.java:105` `this.tickInputs = tickInputs;`）→ 可以安全 `put` 一条新能力。
   - ⚠️ **但 `FunctionBuilder` 没有任何"新增一种能力"的 builder 方法**（只有 `modifyAllContents` / `eutMultiplier` / `durationMultiplier` / `conditions`）→ 必须**自己在 lambda 里 `copied.tickInputs.put(...)`**，或者用 `compose`/`andThen`（`:81-93`）串一个自己的 modifier。

**甲方案的代价 / 坑**：

- **"不足就退回标准超频"做不到每 tick 粒度**。TF 不足时 GTM 只会让配方进 `WAITING`（`:291-319`）；自动 SUSPEND 那条分支的判定是 **`handleTick.io() == IO.IN && handleTick.capability() == EURecipeCapability.CAP`**（`:295`）→ **只认 EU，TF 不足不会触发 SUSPEND、不会加 `runDelay`**，只会一直等待 +（按 `regressWhenWaiting()`）退进度。
  - ⇒ 想"**不够就退回标准超频**"，要么只做"每配方开始判定一次"（在 `OverclockHatchHelper.modify` 里查仓的 TF 存量，不足就不加超频 → 无 mixin），要么**必须 mixin `RecipeLogic`** 做每 tick 降级。设定 §4 明确要"每 tick 按比例扣、耗尽了当 tick 起降级"→ **这条设定与"零新 mixin"冲突**。
- `ModifierFunction` 的 `recipe.data` 是**按引用传**（`:186`）→ 往 `data` 里写标记会**污染原配方对象**（`GTRecipe` 是共享的、非每机器一份）。要标记"这条配方吃过 TF"，应另找字段或自己 `copy()`。
- **未确认**：`RegisterEvent<GTRegistry.String<RecipeCapability<?>>, RecipeCapability<?>>` 的 EventBus 泛型匹配。本仓 05 文档第 63-64 行明确警告过"参数写成具体泛型时一次都不会被调用、也不报错"（那是 `MachineDefinition` 那次）。TF 这次**动手第一件事就是验证回调真的进得来**。

### 4.4 本仓现有逻辑类能不能挂

| 类 | 位置 | 能不能挂 |
|---|---|---|
| `ETModularRecipeLogic` | `src/main/kotlin/rain/gtetcore/gtet/common/machine/multiblock/modular/ETModularMachine.kt:119` | **能**。它已覆写 `matchRecipe`（protected），同一套手法可覆写 `handleRecipeWorking`。但**只对用它的机器生效** |
| `ThreadedRecipeLogic` | `src/main/kotlin/rain/gtetcore/gtet/common/machine/multiblock/thread/ThreadedRecipeLogic.kt:189` | **能**。它 `override fun serverTick()`（`:329`）自己完全接管了 tick 推进，扣 TF 的点在 `advanceThread`（`:391-410`）与 `handleThreadTickRecipe`（`:417-429`）。⚠️ 但它的 KDoc 第 6 条（`:142-144`）明确说"**机器级 `onWorking()` 返回 false 时只「等」，不「打断」**"→ **超频仓不能靠 `onWorking()` 在它身上做降级** |

**结论**：`ThreadedRecipeLogic.kt:189` 里的 `mirrorToBaseFields()`（`:765-777`）证明基类的 `progress`/`duration`/`isActive`/`lastRecipe` **可以直接从 Kotlin 子类赋值**（protected 可见），这同时验证了 §5。

---

## 5. 推进配方进度（时序之瓶）

### 5.1 可写字段全表

| 字段/方法 | 修饰符 | 能安全改？ | 证据 |
|---|---|---|---|
| `public void setProgress(int)` | **public** | **能，这是唯一现成的公开入口** | `RecipeLogic.java:117-121` `@Setter`；javap 实证 `public void setProgress(int)` |
| `progress` | protected，`@Persisted @DescSynced` | 子类可直接赋值 | 同上 + `ThreadedRecipeLogic.kt:769` |
| `duration` | protected，`@Persisted @DescSynced`，**只有 `@Getter`** | **只能子类赋值；外部没有 setter** | `:122-125`；javap 无 `setDuration` |
| `GTRecipe.duration` | **public int** | 能改，但它是**共享的配方对象** | `api/recipe/GTRecipe.java:52` |
| `markLastRecipeDirty()` | public | 能，强制下轮重选配方 | `:443-445` |
| `updateTickSubscription()` | public | 能，重挂 tick 订阅 | `:184-193` |

### 5.2 推进进度的最小正确写法

```
在服务端，对目标 RecipeLogic：
1. 若 !isActive() 或 getLastRecipe() == null → 不动（没有在跑的配方）
2. setProgress(getProgress() + N)            // N = 要推进的 tick 数
3. 若机器当前是 SUSPEND/订阅已退订 → updateTickSubscription()
```

- 为什么够：`serverTick()` 里 `if (progress < duration) handleRecipeWorking();` **然后** `if (progress >= duration) onRecipeFinish();`（`:209-218`）。所以把 `progress` 一次性写到 `>= duration` 会让它在**下一个 tick** 走 `onRecipeFinish()` 结算。**不需要** `markLastRecipeDirty()`。
- `progress` 是 `@DescSynced`，进度条会同步到客户端。
- **拿目标机器入口**：`GTCapabilityHelper.getRecipeLogic(level, pos, side)`（§3）；或 `MetaMachine` 判 `instanceof IRecipeLogicMachine` → `getRecipeLogic()`（`IRecipeLogicMachine.java:44`）。
- **未确认**：`@DescSynced` 字段被"外部直接赋值"时，LDLib 是否需要额外的 dirty 通知。反证：`NotifiableEnergyContainer.setEnergyStored`（`:142-152`）在写完后显式 `notifyListeners()`，说明 LDLib 侧存在监听器机制；但 `progress` 的写入路径 GTM 自己就是裸赋值（`RecipeLogic.java:289` `progress++`），**GTM 自己也没通知** → 大概率无事，但没实测。

### 5.3 ⚠️ 被加速的机器要不要补 EU（**设定被推翻，这是本报告最重要的一条**）

- EU 是 **tick 输入**：`GTRecipe.java:59-60` `inputEUt = calculateEUt(tickInputs)`；`:38` `tickInputs`。每 tick 在 `handleTickRecipeIO(recipe, IO.IN)` 里**真扣**（`:377-388` → `:556-558` → §2.3 第 7 步）。
- `setupRecipe` 扣的是**非 tick 输入**（`:399` `handleRecipeIO(recipe, IO.IN)`），EU 不在里面。

**⇒ 推进 N tick 会：**

| 后果 | 说明 |
|---|---|
| **白省 N × EU/t 的 EU** | 这 N 个 tick 的 `handleTickRecipeIO(IO.IN)` 一次都没跑 |
| **跳过 N 次 tick 输入** | tick 输入里的**所有**能力（不只 EU）都被跳过 |
| **跳过 N 次 tick 输出** | `handleTickRecipeIO(recipe, IO.OUT)`（`:386`）同样被跳过 → 靠 tick 产出中间产物的机器会**少产** |
| 最终产物照给 | `onRecipeFinish()` → `handleRecipeIO(lastRecipe, IO.OUT)`（`:513`） |

**所以设定 §5 的公式"`(因这次加速多出的 EU ÷ 8.192E+3) × K_hand`"语义上不成立**——加速**不会**"多出 EU"，只会**少花 EU**。

**两条改法（必须二选一，否则是免费加速 + 绕过 tick 成本的漏洞）**：

| 改法 | 做法 |
|---|---|
| **补扣式** | 加速时自己算 `N × recipe.getInputEUt().getTotalEU()`，从机器的能量仓扣（`GTCapabilityHelper.getEnergyContainer` + `removeEnergy` / `handleRecipeInner` 同款路径）；扣不满就不加速。同时明确"tick 输入/输出跳过"是**有意的**还是也要补 |
| **改口径式** | 把"推进进度"改成"给这台机器一个瞬时加速倍率，仍然每 tick 走一次 `handleTickRecipe`"。这样 EU 与 tick 内容都照扣，只省时间。代价是要维护持续状态（设定 §5 的"方案 B"，已被你否掉） |

---

## 6. 部件绑定手势（闪存）

### 6.1 接口

```java
// com/gregtechceu/gtceu/api/machine/feature/IDataStickInteractable.java:7-16
public interface IDataStickInteractable {
    default InteractionResult onDataStickUse(Player player, ItemStack dataStick)      { return InteractionResult.PASS; }
    default InteractionResult onDataStickShiftUse(Player player, ItemStack dataStick) { return InteractionResult.PASS; }
}
```

- 两个都是 `default`，**返回 `InteractionResult.PASS`**（= 不接管，继续往下传）。接管的实现返回 `InteractionResult.SUCCESS`。

### 6.2 分发（`common/item/DataItemBehavior.java:146-178`）

| 手势 | 调谁 |
|---|---|
| 右键（非潜行） | `onDataStickUse(player, itemStack)`（`:158`） |
| **潜行**右键（`context.isSecondaryUseActive()`） | `onDataStickShiftUse(player, itemStack)`（`:154`） |
| 作用对象 | ① 面上的 Cover 且 `instanceof IDataStickInteractable`（`:150-151`）；② 方块实体是 `MetaMachineBlockEntity` 且机器 `instanceof IDataStickInteractable`（`:161-166`） |
| 门禁 | 机器侧先过 `MachineOwner.canOpenOwnerMachine(player, machine)`，不过返回 `FAIL`（`:163-165`） |
| 拦截面 | 闪存是 `onItemUseFirst`（`:147`）→ **先于**方块的正常 `use` |
| 特例 | 潜行 + 闪存里有研究 id（`ResearchManager.readResearchId != null`）时**不转发**，直接 `sidedSuccess`（`:152-156`、`:167-171`） |

### 6.3 `WirelessTransmitterCover` 的写法（**可抄**）

```java
// com/gregtechceu/gtceu/common/cover/WirelessTransmitterCover.java:47-55
@Override
public InteractionResult onDataStickUse(Player player, ItemStack dataStick) {
    dataStick.getOrCreateTag().putInt("targetX", coverHolder.getPos().getX());
    dataStick.getOrCreateTag().putInt("targetY", coverHolder.getPos().getY());
    dataStick.getOrCreateTag().putInt("targetZ", coverHolder.getPos().getZ());
    dataStick.getOrCreateTag().putString("face", attachedSide.getName());
    dataStick.getOrCreateTag().putString("dim", coverHolder.getLevel().dimension().location().toString());
    return InteractionResult.SUCCESS;
}
```

| 键 | 类型 | 值 |
|---|---|---|
| `targetX` / `targetY` / `targetZ` | int | 坐标 |
| `face` | String | `Direction#getName()`（`"north"` 等小写名） |
| `dim` | String | `level.dimension().location().toString()`（如 `"minecraft:overworld"`） |

- **要不要维度：要，而且 GTM 自己就存了**（`dim`）。→ 设定里"跨维度绑定"这条有现成键可复用。
- **读取方**：`common/machine/multiblock/electric/monitor/MonitorGroup.java:131` 读 `targetX`；物品 tooltip 也读（`DataItemBehavior.java:77-80`）。
- 全部写在**闪存自己的根 tag**（`getOrCreateTag()`），**没有命名空间隔离**——我们的 TF 绑定键（如 `tf_tower`）要注意别跟 GTM 的键撞名，建议带前缀。
- **坑**：这是**"写闪存"**手势（把目标写进手里那根棍）。我们要的是**"绑定到塔"**——两种语义：
  - 抄 `WirelessTransmitterCover`：右键塔 → 塔把自己的坐标写进**瓶子/闪存的 NBT**（瓶子随身携带，等价于绑定）→ **推荐**，与闪存键完全同构。
  - 抄 `MEPatternBufferProxyPartMachine.java:120`：那是反向的（被绑的部件读闪存）。
- **未确认**：`WirelessEnergyCover`/`WirelessTransmitterCover` 之外 GTM 是否还有"无线能量"的成对实现——我查了全仓文件名，只有 `WirelessTransmitterCover`（那是给监视器/占位符用的，**不是能量**）。**GTM 里没有现成的无线输能仓可抄**；最接近的长距离输能结构是 `api/pipenet/longdistance/`（`LongDistanceNetwork` + `LongDistanceEndpointMachine`），见 §8.3。

---

## 7. 物品上存能量 / 自定义单位

### 7.1 现成做法盘点

| 做法 | 能不能用在物品上 | 证据 |
|---|---|---|
| LDLib `ManagedFieldHolder` + `@Persisted` | **不能** | javap 实证构造函数签名是 `ManagedFieldHolder(Class<? extends IManaged>)` + `ManagedFieldHolder(Class<? extends IManaged>, ManagedFieldHolder)`。`Item` 不是 `IManaged`（那是 BlockEntity/trait 体系）。全仓 grep：`api/item/`、`common/item/` 下 **`ManagedFieldHolder` 零命中** |
| `@DescSynced` / `@Persisted` 注解 | **不能** | 同上，注解处理器只认 `IManaged` 的实现 |
| LDLib `ItemStackPayload` | 不相关 | 那是"同步一个 ItemStack 类型字段"的网络负载，不是物品侧存储 |
| GTM `IElectricItem` | **语义不适配** | `api/capability/IElectricItem.java`：`charge(long amount, ...)` / `discharge(...)` / `getCharge()` / `getMaxCharge()` / `getTransferLimit()` 全部硬编码 EU 语义，`getTier()` 是整数电压档。**当 TF 用会污染电池/充电器/放电槽语义**（它由 `GTCapability.CAPABILITY_ELECTRIC_ITEM` 暴露，`GTCapabilityHelper.getElectricItem` `:23-26` 直接取用） |
| **手写物品 NBT** | **能，且这是唯一正路** | 见 7.2 |

### 7.2 可抄的实现：`IntCircuitBehaviour`（**最贴近时序之瓶**）

`com/gregtechceu/gtceu/common/item/IntCircuitBehaviour.java`：

| 能力 | 位置 | 说明 |
|---|---|---|
| 存一个自定义 int | `:51-56` `setCircuitConfiguration(ItemStack, int)` → `itemStack.getOrCreateTag().putInt("Configuration", v)` | 键名 `"Configuration"`，无命名空间 |
| 读 | `:58-65` `getCircuitConfiguration(ItemStack)` | tag 为 null 时返回 0 |
| 静态工厂 | `:40-44` `stack(int)` | |
| 物品 UI（LDLib） | `:101-137` `createUI(HeldItemUIFactory.HeldItemHolder, Player)`，接口 `api/item/component/IItemUIFactory.java:14-25` | `use(...)` default 直接 `HeldItemUIFactory.INSTANCE.openUI(serverPlayer, usedHand)`（`:18-25`） |
| **动态 tooltip** | `:94-99` `appendHoverText(ItemStack, Level, List<Component>, TooltipFlag)` | 见 7.3 |
| 右键方块 | `:139-154` `useOn(UseOnContext)` | 用 `context.isSecondaryUseActive()` 自判潜行 |

- 接口位置：`api/item/component/IAddInformation.java:12-16`（`appendHoverText`），由 `ComponentItem.appendHoverText` **逐个 component 转发**（`api/item/ComponentItem.java:92-100`）。
- 本仓已有同构先例：`src/main/java/rain/gtetcore/gtet/common/item/terminal/AdvancedTerminalBehavior.java:33`（`implements IItemUIFactory`）、`:40` `createUI`、`:45-47` `useOn` + `player.isShiftKeyDown()`、`:60` `InteractionResult.sidedSuccess(level.isClientSide)`。

### 7.3 动态 tooltip：每帧重算 vs 缓存

- **机制**：`appendHoverText` 每次渲染 tooltip 都被调（`ComponentItem.java:93-99` 无缓存）→ **每帧重算**。
- **设定 §5 想要的效果正好合拍**："汇率是 `f(gameTime)` 的纯函数 ⇒ 客户端本地算，不需要同步包"。每帧重算 = 天然实时。
- **缓存建议**：不要自己加缓存。昂贵的只有 `Component.translatable(...)`；`Component` 本身是惰性 + 有 `I18n` 缓存，`Component.literal` 才会每帧新建对象。把 tooltip 行做成 `Component.translatable(key, args...)` 就够。
- LDLib 侧还有 `WidgetTooltipComponent` / `ClientTooltipComponent`（`<ldlib jar>` 内 `com/lowdragmc/lowdraglib/gui/util/WidgetTooltipComponent`）——那是给 **GUI widget** 的 tooltip 用的，物品 tooltip 用不着。

### 7.4 能否与时流仓/塔对接

- **能，但只能走自定义 Forge Capability 或裸 NBT 约定。** 物品侧没有任何 `IRecipeHandlerTrait` 接口（那套是 `MachineTrait`，必须挂在机器上）。
- 建议：给"时序之瓶"注册一个自己的 `Capability<TFItemStorage>`（`ItemStack#getCapability`），塔/仓通过 `GTCapabilityHelper` 同款的静态 helper 取用；内部实现就是 NBT 读写。这样将来"塔 → 瓶充装"可以走能力而不是 `if (stack.hasTag())` 硬编码。

---

## 8. 主控塔"全服唯一"与拒绝成型

### 8.1 全服单例的现成做法

| 模板 | 位置 | 要点 |
|---|---|---|
| **`VirtualEnderRegistry`（推荐抄这个）** | `api/misc/virtualregistry/VirtualEnderRegistry.java:18-51` | `extends SavedData` + `static volatile` 实例 + `ServerLifecycleHooks.getCurrentServer()` + **`server.overworld().getDataStorage().computeIfAbsent(load, create, DATA_ID)`**（`:36-37`）→ **真正的"全服"（跨维度）**；另有 `release()` 在服务器停止事件里清静态引用（`:44-50`） |
| `WorldIDSaveData` | `common/capability/WorldIDSaveData.java:32-35` | 同款：`world.getDataStorage().computeIfAbsent(WorldIDSaveData::new, () -> new WorldIDSaveData(world), "gtceu_world_id")`。给的是 `ServerLevel` 参数，但调用方传的是主世界 |
| `LongDistanceNetwork.WorldData` | `api/pipenet/longdistance/LongDistanceNetwork.java:313-337` | **按维度**的 SavedData（`serverLevel.getDataStorage()`，`:331-337`），键 `"gtceu_long_dist_pipe"` |
| `BedrockFluidVeinSavedData` / `BedrockOreVeinSavedData` / `EnvironmentalHazardSavedData` / `LevelEnergyNet` 等 | 各自 `getOrCreate(level)` | 都是"按维度 + 静态 `computeIfAbsent`" |

**`computeIfAbsent` 签名**（Forge `DimensionDataStorage`）：

```java
<T extends SavedData> T computeIfAbsent(Function<CompoundTag, T> load, Supplier<T> create, String name);
```

- **要"全服唯一"就必须用 `server.overworld().getDataStorage()`**；用 `serverLevel.getDataStorage()` 得到的是**每个维度一份**（`LongDistanceNetwork.WorldData` 就是后者）。
- **生命周期**：`SavedData#setDirty()`（`WorldIDSaveData.java:19`）才会落盘；存档删除 = 随主世界 region 一起没；多人服并发成型要在**服务端线程**上做读-改-写（`asyncCheckPattern` 已经把形成回调扔回主线程，见 8.2）。

### 8.2 结构成型检查里能不能"拒绝成型"（**能，但有 2 个坑**）

| 方法 | 签名 | 位置 |
|---|---|---|
| `IMultiController#checkPattern()` | `default boolean` | `api/machine/feature/multiblock/IMultiController.java:94-97` |
| `IMultiController#checkPatternWithLock()` | `default boolean` | `:102-110` |
| `IMultiController#checkPatternWithTryLock()` | `default boolean` | `:117-128` |
| `MultiblockControllerMachine#checkPattern()` | `public boolean`（带失败冷却） | `api/machine/multiblock/MultiblockControllerMachine.java:176-194` |
| `onStructureFormed()` | `void`（`IMultiController` 声明 `:169`；实现在 `MultiblockControllerMachine.java:251-274`） | 设 `isFormed=true`、收集 `parts`、`part.addedToController(this)` |
| `onStructureInvalid()` | `void`（接口 `:180`；实现 `:276-289`） | 设 `isFormed=false`、`part.removedFromController(this)`、清 `parts` |

**可抄的"拒绝成型"实例**：`common/machine/multiblock/electric/LargeMinerMachine.java:129-133`

```java
@Override
public boolean checkPattern() {
    return super.checkPattern() &&
            (this.getUpwardsFacing() == Direction.NORTH || this.getUpwardsFacing() == Direction.SOUTH);
}
```

⇒ 在 `checkPattern()` 里 `&&` 上自己的条件，条件不满足就**根本不成型**。这是 GTM 官方用法。

**坑 1（严重）：`checkPattern()` 会在异步线程被调用。**

- `IMultiController.java:85-97` 的 javadoc 原文：*"You can override it but it's unsafe for calling. because it will also be called in an async thread."*
- 证据链：`api/pattern/MultiblockWorldSavedData.java:110-113` 建**单线程 scheduled executor**；`:149-169` `searchingTask()` 在**那个后台线程**里调 `controller.asyncCheckPattern(periodID)`；`MultiblockControllerMachine.java:201-223` `asyncCheckPattern` → `checkPatternWithTryLock()` → **`checkPattern()`**；只有 `onStructureFormed()` 被 `serverLevel.getServer().execute(...)` 扔回主线程（`:206-220`）。
- ⇒ **唯一性判定里读 `SavedData` 是数据竞争**（`DimensionDataStorage` 不在服务端线程上安全）。正确做法：
  - `checkPattern()` 里只读一个 **`static volatile`（或 `AtomicReference<GlobalPos>`）内存持有者**——无 I/O、无 level 访问；
  - `SavedData` 只在服务端线程读（服务器启动 / `onStructureFormed`）与写。

**坑 2：`checkPattern()` 返回 false 对"已成型"的塔也会拆掉它。**

- `requestCheck()`（`MultiblockControllerMachine.java:228-249`）在已成型状态下重检，失败就 `onStructureInvalid()`。
- ⇒ 设定 §6.1 的"从 `false` 改成 `true` 时，已存在的多座塔**不追溯**"**不能**靠简单的"不是唯一者就返回 false"实现——那样会在下一次方块变化/重检时把既有的第二座塔**拆掉**。
- 要实现"不追溯"，唯一性门必须写成"**已经成型过的塔不再检查唯一性**"（例如 `if (isFormed()) return super.checkPattern();`），并且只有真正**新成型**的塔去抢那个位子。

### 8.3 跨维度连接塔的可抄结构

`api/pipenet/longdistance/`（`LongDistanceNetwork` + `LongDistanceEndpointMachine`）是 GTM 现成的"**两两配对 + 跨维度 + 区块卸载后重连**"实现：

| 机制 | 位置 | 说明 |
|---|---|---|
| 端点 | `common/machine/storage/LongDistanceEndpointMachine.java` | `ILDEndpoint`；`getLink()`（`:188-207`）**懒解析**配对端，失效则 `invalidateLink()` 并重试 |
| 网络 | `api/pipenet/longdistance/LongDistanceNetwork.java` | 按维度的 `WorldData`；`getEndpointAmount() > 1` 才 `isValid()`（`:306-308`） |
| 重连 | `LongDistanceEndpointMachine.java:60-74, 103-118` | `onLoad` 排一个 `TickTask(0, this::updateRefreshNetSubscription)` 回主线程；未连线时每 20 tick 重试 |
| 载入/卸载 | `:120-133` | `onUnload` 里 `link.invalidateLink()` + 从网络摘除 |

⇒ 设定 §2 的"连接塔跨维度 1 对 1 配对"直接抄这套即可，**不需要 SavedData 存配对**（配对由网络+端点在运行期解析）。

---

## 9. 被推翻的假设 / 需要你改设计的地方

按"影响面 × 必须改"排序。

### 9.1 【必须改】"无线仓在多方块要电时当场从链路拉电" —— 挂点选错了

- **原假设**：多方块耗电走某个"消耗"方法，我们的仓挂上去就能当场拉。
- **实际**：配方耗电走 `handleRecipeInner(IO.IN,…)` → 从**仓自己的 `energyStored` 缓冲区** `changeEnergy(-n)`（`NotifiableEnergyContainer.java:307-334`）。外部灌电才走 `acceptEnergyFromNetwork`。
- **要改成**：无线仓 = `NotifiableEnergyContainer` 的**子类**，覆写 `handleRecipeInner` 先补货（§2.3 方案 B）；或者退而求其次用每 tick 灌满缓冲区（方案 A）。
- **连带**：`EURecipeCapability#matchTickRecipe` 走的是 `simulated=true`（`RecipeHelper.java:186-196`），**匹配阶段也要读同一份 `energyStored`** → 缓冲区不满时，配方**连匹配都过不了**，会进 `WAITING` 而不是"去拉电再跑"。这进一步说明必须走"先补货"。

### 9.2 【必须改】时序之瓶"推进进度"会白省 EU、并跳过 tick 输入/输出

- **原假设**：设定 §5 的 `(因加速多出的 EU ÷ 8.192E+3) × K_hand`。
- **实际**：EU 是 tick 输入（`GTRecipe.java:59-60`），推进进度 = 少跑 N tick = **少花** N×EU/t，不是"多出"。（§5.3）
- **要改成**：补扣式（自己算 `N × getInputEUt().getTotalEU()` 并扣能量仓）或改口径式（瞬时倍率、每 tick 照扣）。同时决定"tick 输入/输出被跳过"是有意还是漏洞。
- **另一个真实的漏洞面**：推进进度跳过 tick 输出 → 若某台机器的产物在 tick 输出里，加速会**少给**产物；若产物在最终 `outputs` 而成本全在 tick 输入里，加速就是**净值作弊**。

### 9.3 【必须改】"超频仓不足时每 tick 退回标准超频" 与"零新 mixin" 冲突

- **实际**：TF 作为自定义 capability 注入后，不足只会 `setWaiting()`（`RecipeLogic.java:291-319`）；自动 SUSPEND 分支**只认 `EURecipeCapability.CAP`**（`:295`）。
- **两个选择**：
  - 妥协为"**每个配方开始时判定一次**"（在已有的 `OverclockHatchHelper.modify` 里查 TF 存量）→ **零新 mixin**，但做不到"燃尽当 tick 降级"。
  - 坚持"每 tick 降级"→ **必须 mixin `RecipeLogic`**（注入 `handleRecipeWorking` 或 `handleTickRecipeIO`）。
- 设定 §4 最后一句"倾向每 tick 按比例扣（耗尽了当 tick 起降级）"→ **按现设计需要新增一条 mixin**。

### 9.4 【必须改】主控塔唯一性不能写在 `checkPattern()` 里查 SavedData

- **实际**：`checkPattern()` 跑在 `MultiblockWorldSavedData` 的**后台单线程执行器**上（§8.2 坑 1）。
- **要改成**：`checkPattern()` 只读一个 `static volatile`/`AtomicReference` 内存持有者；SavedData 只在服务端线程读写。

### 9.5 【必须改】"配置切 true 后既有塔不追溯" 需要额外一行判断

- **实际**：`checkPattern()` 返回 false 会把**已成型**的塔 `onStructureInvalid()` 掉（`requestCheck()` `:228-249`）。
- **要改成**：已经成型的塔跳过唯一性门。

### 9.6 【口误级，不必改设计】`PartAbility.register` 没有 amperage 参数

- 实际是 `register(int tier, Block block)`（`PartAbility.java:71`）。
- "能流转换能源仓的安培数"这类信息**不在能力层**，只能放在机器类构造参数/`MachineDefinition`（照 `EnergyHatchPartMachine.java:34-41` 的 `@Getter protected int amperage` 写法）。

### 9.7 【需要补】时流系列的三个仓要在任意 GTM 多方块里插得上，得多追加一次能力谓词

- 现成的 `MixinPredicatesAutoAbilities.java:69-73` **只追加了 `OVERCLOCK_HATCH`**。
- 若"时流转换能源仓/供应仓/时流仓"依赖"能插进 GTM/GCYM 的多方块"，必须在这条链上追加（或另写 mixin）。**否则它们只会在 GTET 自己的多方块上生效**。

### 9.8 【需要补】物品侧没有容器接口可套，得自己定一个

- `ManagedFieldHolder` 物品用不了（javap 实证要 `Class<? extends IManaged>`）；`IElectricItem` 是 EU 语义。
- 设定 §8 那句"时序之瓶的容器实现：物品 NBT 记账，还是套 Forge 能力 / GTM 容器接口" → **答案：手写 NBT +（建议）自定义 Forge Capability；GTM 没有能套的容器接口。**

### 9.9 【提示】无线仓的容量会污染多方块的显示与并行预算

- `EnergyContainerList` 把成员储能求和（`:156-163`），且 `EURecipeCapability#getMaxParallelByInput(tick=false)`（`:114-120`）把 IN 侧 EU handler 的 `getContents()` 当库存算。
- 建议无线仓容量照 `getHatchEnergyCapacity(tier, amperage) = V[tier]*64*A`（`EnergyHatchPartMachine.java:112-114`）对齐，别给超大缓冲。

---

## 10. 本仓代码 / 文档与 GTM 实际不符之处

| 位置 | 本仓写法 | 实际 | 严重度 |
|---|---|---|---|
| `README/zh_CN/GTM开发教程/05-API调用速查.md:46` | "**在构造谓词那一刻**就把 `PartAbility#getAllBlocks()` 取了出来，而那个集合是 `GTMemoizer.memoize` 缓存的（`PartAbility.java:61-62`，**首取即定**、之后不再变）" | 引言部分完全正确（`:61-62` 行号也对）；"构造那一刻"应改为"**首次 `getAllBlocks()` 那一刻**"（`MemoizedSupplier.java:16-22` 是懒求值）。**结论不变** | 低（措辞） |
| `src/main/kotlin/.../ETPartAbility.kt:9-12` | "`PartAbility` 的构造函数就是普通的 `public PartAbility(String name)`，内部只持有一个 `name` 与一个「tier → 方块集合」的注册表" | 构造函数正确（`PartAbility.java:67`）；但**漏了第三个成员 `allBlocks`（记忆化集合，`：61-62`）** —— 那正是 §1.3 那条硬顺序的来源 | 低（可补） |
| `src/main/kotlin/.../ETPartAbility.kt:14-15` | "真正把这批方块登记进能力表的是 `MachineBuilder.abilities(...)`：它在方块注册回调里执行 `ability.register(tier, block)`" | **正确**，与 `MachineBuilder.java:778` 逐字一致，连参数名都对 | —（已核实无误） |
| `README/zh_CN/无线能源链接仓设定.md:187-188` | "`PartAbility.INPUT_ENERGY` 注册签名"列在待核清单 | 已核：`register(int tier, Block block)`，**无 amperage / 无 MachineDefinition** | 已解决 |
| `README/zh_CN/无线能源链接仓设定.md:192` | "时序之瓶的容器实现：物品 NBT 记账，还是套 Forge 能力 / GTM 容器接口" | 物品侧**没有**可套的 GTM/LDLib 容器接口（§7.1） | 已解决 |
| `README/zh_CN/无线能源链接仓设定.md:98` | "扣费时机待核：倾向每 tick 按比例扣" | 每 tick 扣**技术上可行**，但"不足 → 退回标准超频"这一步要 mixin（§9.3） | 已解决（有代价） |
| `src/main/java/.../MixinOverclockingLogic.java:38-48` | "全类一共 5 处该调用" | 我未逐条反汇编 `GTRecipeModifiers` 复核这 5 处；但该注释描述的 `lambda$static$0` / `crackerOverclock` / `ebfOverclock` / `pyrolyseOvenOverclock` / `multiSmelterParallel` 与 `api/recipe/modifier/` 包的现状不冲突 | **未复核** |
| `src/main/kotlin/.../OverclockHatchPartMachine.kt:26-27` | "没有持久化字段…`MANAGED_FIELD_HOLDER` 只是为了让 ldlib 把父类那些 `@DescSynced` 字段串起来" | 未逐字段核对 `MultiblockPartMachine.MANAGED_FIELD_HOLDER` 的内容；写法本身合法 | **未复核** |

---

## 11. 我这次没核成的点（诚实清单）

1. **`RegisterEvent` 对 `RecipeCapability` 的泛型匹配是否真的回调**。我确认了 `GTRecipeCapabilities.java:33-35` 会 `postEvent` 再 `freeze`，但没有实测我们的监听器能收到。本仓 05 文档 `:63-64` 警告过"写成具体泛型时一次都不会被调用、也不报错" → **建议动手第一步就写个日志验证**。
2. **`RecipeRunner` 内部如何回填 `ActionResult.capability()` / `.io()`**。我只读到 `RecipeRunner.java:62-85`。§4.3 里"TF 不足不会触发自动 SUSPEND"这条，依赖的是 `RecipeLogic.java:295` 那个 `handleTick.capability() == EURecipeCapability.CAP` 比较成立——**我没有进 `RecipeRunner.handleContents()` 核实 capability 是否被原样回填**。
3. **`setProgress` 之后 LDLib 的 `@DescSynced` 是否额外需要 dirty 通知**。`NotifiableEnergyContainer.setEnergyStored` 显式 `notifyListeners()`，而 `RecipeLogic` 自己 `progress++` 不通知 → 大概率无事，**未实测**。
4. **混合装仓时 `EnergyContainerList.calculateVoltageAmperage` 的档位压缩结果**。算法读全了（`EnergyContainerList.java:78-107`），但**没算过**"GTM 8A 能源仓 + 我们的 1A 无线仓"混装会得到什么 `inputVoltage/inputAmperage`，以及是否会命中 `getMaxVoltage()` 的 "tier+1" 分支（`:269-277`）。这是"要不要允许混装"的设计前提，动手前必须实测。
5. **`GTRecipe.data` 被 `ModifierFunction` 按引用传递（`:186`）的实际后果**。我确认了是引用传递，但没核 GTM 里是否有"往 data 写东西"的既有约定/谁在清它。
6. **vanilla `onItemUseFirst` 与 `useOn` 的调用先后**。我确认了闪存走 `onItemUseFirst`（`DataItemBehavior.java:147`），但没核它的确定顺序；也没核本仓 `AdvancedTerminalBehavior.useOn` 与闪存手势在同一方块上会不会互抢。时序之瓶若也用 `useOn`，与闪存（不同物品）不冲突，但**如果将来想让瓶支持闪存绑定就会冲突**。
7. **`MixinOverclockingLogic` 声称的 5 个 `@Redirect` 目标点**是否仍与当前 patched jar 一致（没反汇编复核）。
8. **`ThreadedRecipeLogic` 会不会被接到"装了超频仓的多方块"上**——这是本仓接线问题，不在 GTM 契约内，我没查。
9. **没有任何运行时验证**。本报告 100% 是静态读码 + javap，**没跑 `runClient`**，所以"结构能不能真的成型""配方能不能真的跑"这类事全都没验。
