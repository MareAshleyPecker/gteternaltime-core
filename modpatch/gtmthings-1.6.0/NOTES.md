# gtmthings-1.6.0 补丁说明

- **来源**：liansishen/GTMThings @ 1.6.0（LGPLv3.0），原作者 **liansishen**。
- **本补丁（第一轮）**：GTET 把「高级终端」的设置界面从「一列文本输入框」重画成**左侧设置面板 + 右侧两块方块列表**，
  并把原来由 GTET mixin 追加的「分级方块」列表改成在补丁里直接画。**只动界面，不动搭建逻辑。**
- **本补丁（第二轮，见 §6）**：把 GTO 高级终端里剩下的 **3 项「搭建行为」设置**补齐 ——
  `module` 模块搭建 / `isFlip` 镜像搭建 / `demolition` 拆除模式，并把「重复结构次数」上限
  从 99 提到 GTO 的 1000。**这一轮是真的改搭建逻辑**：`useOn`、`AutoBuildSetting`、
  `AdvancedBlockPattern#autoBuild` 都动了。
- **应用方式**：见 `../README.md`。`ui/`、`logic/` 下的文件覆盖 GTMThings 仓库同名文件即可
  （`lang/` 覆盖 `src/main/resources/assets/gtmthings/lang/`）。
- ⚠️ **构建时先跑 `spotlessApply`**：GTMThings 自己带 Spotless（eclipse formatter）检查，
  它会把 javadoc 里的 `* <p>` 与下一行合并。归档里的 `logic/.../AdvancedBlockPattern.java`
  保留的是作者原始写法（`<p>` 单独一行），而检出里那份是**格式化后的**产物 ——
  两者只差这几处换行，**不是损坏**（`gradlew spotlessApply build` 会先把它格式化掉再编，
  `patches.gradle` 也正是这么调的）。本轮新增 / 改过的 `AdvancedTerminalBehavior.java`
  在 `spotlessApply` 前后与检出一致，没有这种漂移。

---

## 1. 原版 `createWidget()` 到底画了什么

原版结构：`ModularUI(176, 166)` → 根 `WidgetGroup(0,0,190,125)`（背景 `BACKGROUND_INVERSE`）
→ 内层 `DraggableScrollableWidgetGroup(4, 4, 182, 117)`（背景 `DISPLAY`，y 滚动条宽 2）
→ 标题 + 5 行设置。行 y 由 `rowIndex` 递增：`y = 5 + 16 * rowIndex`，`rowIndex` 从 1 起。

| 行    | y（原版） | lang 键（label / tooltip）                        | 控件（原版）                      | 控件坐标 / 尺寸                                        | 对应 NBT 键 → `AutoBuildSetting` 字段      |
|------|-------|------------------------------------------------|-----------------------------|--------------------------------------------------|---------------------------------------|
| 标题   | 5     | `…setting.title`                               | `AlignLabelWidget(89,5)` 居中 | —                                                | —                                     |
| 1    | 21    | `…setting.1` / `.1.tooltip`（tooltip 里拼了全部线圈等级） | `TerminalInputWidget`（文本框）  | (140, 21) 20×16，min 0 max `HEATING_COILS.size()` | `CoilTier` → `coilTier`               |
| 2    | 37    | `…setting.2` / `.2.tooltip`                    | `TerminalInputWidget`       | (140, 37) 20×16，min 0 max 99                     | `RepeatCount` → `repeatCount`         |
| 3    | 53    | `…setting.3`（无仓室模式）/ `.3.tooltip`              | `TerminalInputWidget`       | (140, 53) 20×16，min 0 max 1                      | `NoHatchMode` → `noHatchMode`（默认 1）   |
| 4    | 69    | `…setting.4`（线圈替换模式）/ `.4.tooltip`             | `TerminalInputWidget`       | (140, 69) 20×16，min 0 max 1                      | `ReplaceCoilMode` → `replaceCoilMode` |
| 5    | 85    | `…setting.5`（使用AE物品）/ `.5.tooltip`             | `TerminalInputWidget`       | (140, 85) 20×16，min 0 max 1                      | `IsUseAE` → `isUseAE`                 |

原版的问题：5 行全是「数字填 0/1」的文本框，没有关闭按钮，UI 宽 176 但内部滚动组宽到 186（略微溢出）。

## 2. 本补丁的界面布局（对照 §1）

窗口 `ModularUI(372, 274)`，根 `WidgetGroup(0,0,372,274)` 背景 `BACKGROUND_INVERSE`。
左侧设置面板 `WidgetGroup(4, 4, 160, 266)`（背景 `DISPLAY`）；
右侧两块列表面板各宽 198、x = 170：上 (170, 20, 198, 114)（标题在 y = 8）、下 (170, 154, 198, 114)（标题在 y = 142）。
设置面板内：标签 x = 10、控件统一右边缘 x = 152；8 行的**行顶** y = 28 / 54 / 88 / 114 / 140 / 166 / 192 / 218
（行距 26，第 2 行之后多留 8 像素的空档，把「线圈等级 / 重复次数」这两个数值项与后面 6 个开关项分成两组）。

> ⚠️ **2026-09-13 放大过一次**（用户：原来的 300×172「太小了」，右侧每块只能显示 3~4 行）：
> 窗口 300×172 → **372×274**，左侧 148×164 → **160×266**，右侧两块 140 宽 → **198 宽**、
> 每块可见行数 3~4 → **7**。下表是放大后的坐标（面板内相对坐标；窗口绝对 y = 面板 y + 4）。
> ⚠️ 尺寸 / 坐标一改，这张表和 §3 那张表必须一起改（`AdvancedTerminalBehavior` 顶部那组常量就是它们）。

| 行           | 坐标（面板内）                                                                       | 控件                          | 说明                                              |
|-------------|-------------------------------------------------------------------------------|-----------------------------|-----------------------------------------------|
| 标题          | `AlignLabelWidget(84, 8)` 居中（窗口坐标；84 = 4 + 160/2，即面板中线）                       | 不变                          | 面板中线上居中                                       |
| 关闭 X        | `ButtonWidget(354, 7, 12, 12, CLOSE_ICON)`（窗口坐标；354 = 372 - 18）               | **新增**                      | 服务端 `player.closeContainer()` → 客户端界面随之关闭     |
| 1 线圈等级      | label (10, 30)；`◀`(110, 28, 12×12)、值(130, 30, 居中)、`▶`(140, 28, 12×12)         | 文本框 → **步进器 `[◀] 值 [▶]`**   | 档位有限（= 线圈等级数），左右点一下就换档；值只读显示，避免手输越界            |
| 2 重复结构次数    | label (10, 56)；`TerminalInputWidget(116, 55, 36×14)` min 0 max **1000**       | 仍是**文本输入框**                 | 档数不定，文本框更合适；上限由 99 提到 1000                     |
| （空行）        | 54 → 88 之间留 34 像素（≈ 一整行 + 空档）                                                 | —                           | 把上面两个数值项与下面 6 个开关 / 输入项分成两组                     |
| 3 无仓室模式     | label (10, 90)；`SwitchWidget(138, 88, 14×14)`                                 | 文本框 → **✓ 复选框**             | 布尔开关，复选框比填 0/1 直观                            |
| 4 线圈替换模式    | label (10, 116)；`SwitchWidget(138, 114, 14×14)`                               | 文本框 → **✓ 复选框**             | 同上                                            |
| 5 使用AE物品    | label (10, 142)；`SwitchWidget(138, 140, 14×14)`                               | 文本框 → **✓ 复选框**             | 同上                                            |
| 6 镜像搭建      | label (10, 168)；`SwitchWidget(138, 166, 14×14)`                               | **✓ 复选框**                   | 第二轮新增；布尔开关，同上                                 |
| 7 模块搭建      | label (10, 194)；`TerminalInputWidget(116, 193, 36×14)` min 0 max 100          | **文本输入框**                   | 第二轮新增；档数不定（模块序号），故用文本框 0~100                  |
| 8 拆除模式      | label (10, 220)；`SwitchWidget(138, 218, 14×14)`                               | **✓ 复选框**                   | 第二轮新增；布尔开关，同上                                 |

复选框外观：未选中 = `GuiTextures.BUTTON`（空框）；选中 = `BUTTON` + 金黄色（`0xFFAA00`）`✔`。

## 3. 右侧两块「方块列表」

数据来源：**GTET 存在终端 NBT 里的分级方块组** —— 一份是 GTET 预置的 6 类静态组（线圈 / 能源仓 /
超频仓 / 线程仓 / 并行仓 / 维护仓，见 §9），一份是上次 Shift+右键扫描出来的真实分级组，两者在 NBT 里共存。

| 面板   | 位置                 | 标题 lang 键                 | 每行                     | 行高 / 控件                                                                                                     |
|------|--------------------|---------------------------|------------------------|-------------------------------------------------------------------------------------------------------------|
| 上：切换 | (170, 20) 198×114  | `…panel.cycle`（分级方块（切换））  | 每组一行：方块图标 + 名字 + `[▶]` | 16px；**整行**铺一个透明 `ButtonWidget`（0, y, 170×16，tooltip `…panel.pick.tooltip`）= 选中这一组（见 §10）；图标 (4, y) 16×16、名字 (24, y+4)（行首带 `▶ ` 标记当前组）、`ButtonWidget(174, y+1, 18×14)`（`BUTTON`+`BUTTON_RIGHT`），点一下把这组设为"右下显示的那一组"并 `cycle` 到下一档 |
| 下：勾选 | (170, 154) 198×114 | `…panel.choose`（分级方块（勾选）） | **只列右上选中的那一组**的每一档：方块图标 + 名字 + `✓`   | 16px；图标 (4, y)、名字 (24, y+4)、`SwitchWidget(174, y+1, 14×14)`，勾上即 `setPreference(该组, 该档)`                     |

两块都是 `DraggableScrollableWidgetGroup`：背景 `DISPLAY`、y 滚动条宽 2、`setDraggable(false)`、
`setUseScissor(true)`，行数多时自动出滚动条（原来 mixin 只是 `if (y > 150) break;` 硬截断）。
**可见 7 行**（114 / 16 = 7.125；放大之前是 3~4 行）；名字列宽 174 - 24 = 150 像素，
超过 `NAME_MAX_CHARS = 14` 个字符就截断（完整名字在图标 tooltip 里）。
一个组都没读到（NBT 里既没有静态组也没有扫描结果）时，面板里显示 `…panel.empty`（“先 Shift+右键控制器扫描结构”）。

> ⚠️ **2026-09-13 第四轮**：下方那块从「所有组的所有候选混排成一长串」改成「只显示上方当前选中的那一组」，
> 两块联动（点上面某一行 = 下面换成那一组）。状态、布局与 LDLib 的三条约束见 §10；坐标/尺寸**没有变**。

### 3.1 补丁读写的 GTET NBT 契约（⚠️ 两边必须一致）

```
gtet_terminal: {
  plan:        { groups: [ { key: "<组键>", candidates: ["<物品id>", ...] } ] }   // 上次 Shift+右键扫描的结果
  group_prefs: [ { group: "<组键>", item: "<物品id>" } ]                          // 玩家选中的那一档
  ui_group:    "<组键>"                                                           // 右下「勾选」块当前显示哪一组（第四轮新增）
}
```

- 写这套 NBT 的是 GTET：`rain.gtetcore.gtet.common.item.terminal.TerminalSettings`。
- 补丁里手写了一遍（`AdvancedTerminalBehavior.TierGroups`），**不引用 GTET 的类**：
  补丁 jar 由 GTMThings 自己 build，编译期不能依赖 GTET；反过来 GTET 编译期也不能依赖补丁新增的类。
- 只显示 `candidates.size() > 1` 的组（和 `TerminalSettings.cachedGroups` 的过滤一致）。
- `ui_group` 由**补丁**读写（GTET 侧不碰它，`installStaticGroups` / `cachePlan` 都只写 `plan`，
  所以这条不会被人顺手抹掉）；键在 `plan.groups` 里找不到时补丁退回第一组，不报错。

## 4. `AutoBuildSetting` 字段 ↔ 设置项 ↔ NBT

| 字段（Lombok `@Getter/@Setter`） | NBT 键             | 设置项                | 取值范围                                |
|------------------------------|-------------------|--------------------|-------------------------------------|
| `coilTier`                   | `CoilTier`        | `setting.1` 线圈等级   | 0 ~ `GTCEuAPI.HEATING_COILS.size()` |
| `repeatCount`                | `RepeatCount`     | `setting.2` 重复结构次数 | 0 ~ **1000**（UI 上限；GTM 自身再按 `aisleRepetitions` 夹） |
| `noHatchMode`                | `NoHatchMode`     | `setting.3` 无仓室模式  | 0/1（默认 1）                           |
| `replaceCoilMode`            | `ReplaceCoilMode` | `setting.4` 线圈替换模式 | 0/1                                 |
| `isUseAE`                    | `IsUseAE`         | `setting.5` 使用AE物品 | 0/1                                 |
| `isFlip`                     | `IsFlip`          | `setting.6` 镜像搭建   | 0/1（默认 0）                           |
| `module`                     | `Module`          | `setting.7` 模块搭建   | 0 ~ 100（0 = 主结构，N = 第 N 套结构）          |
| `demolition`                 | `Demolition`      | `setting.8` 拆除模式   | 0/1（默认 0）                           |

⚠️ 读取沿用原有那条「以 `CoilTier` 是否存在为准」的写法：`tag.contains("CoilTier") ? tag.getInt("<键>") : <默认>`，
新增的三个键**照抄同一模式**（不是新发明，是为了和上面 5 个键保持完全一致）。

方法：`apply(BlockInfo[])`（线圈按 `coilTier` 换档，其它返回候选）、`isPlaceHatch(BlockInfo[])`、
`isReplaceCoilMode()`、以及第二轮新增的 `isFlipMode()` / `isDemolitionMode()`。

## 5. 与 GTET mixin 的一致性

### 5.1 第一轮已经确认过的（本轮未变）

`AutoBuildSettingMixin` 依赖的名字，**两轮都没改**（javac 产物 javap 可验）：

- 目标类 `AdvancedTerminalBehavior.AutoBuildSetting`（嵌套类名不变）；
- 注入方法 `apply(com.lowdragmc.lowdraglib.utils.BlockInfo[])` → `java.util.List<ItemStack>`，`@At("RETURN")`；
- `get/setCoilTier`、`get/setRepeatCount`、`get/setNoHatchMode`、`get/setReplaceCoilMode`、`get/setIsUseAE` 全部保留。

`AdvancedTerminalBehaviorMixin`：`createWidget` 里「追加分级方块」那一段已经删掉（改由补丁画），
`createUI` 记录 ItemStack 也不需要了（补丁直接读玩家主手物品），**只保留** `useOn` 的 HEAD/RETURN
（`TerminalContext` 缓存 + 扫描结果写进终端 NBT）。

### 5.2 第二轮（本轮）同样**没有改任何 mixin**

`src/main/java/rain/gtetcore/gtet/mixin/GTMT/` 三个 mixin 依赖的签名/注入点，本轮全部照旧
（`javap -p` / `javap -c` 对 javac 产物实证，见 §6.4）：

| mixin | 依赖的东西 | 本轮是否受影响 |
|---|---|---|
| `AdvancedTerminalBehaviorMixin` | `useOn(UseOnContext)` 的 HEAD/RETURN | ❌ 没变：方法签名与 `@Override` 返回类型不变，只是方法体里多了分支 |
| `AutoBuildSettingMixin` | `AutoBuildSetting#apply(BlockInfo[])` 的 RETURN | ❌ 没变：`apply` 签名/行为一字未动（只是类里多了 3 个字段 + 2 个方法） |
| `AdvancedBlockPatternMixin` | `autoBuild(Player, MultiblockState, AutoBuildSetting)` 里 **2 处**旧描述符 `getGlobalCount()/getLayerCount()` 调用 | ❌ 没变：`autoBuild` 描述符不变；字节码里那两处 INVOKE 仍是**恰好 2 处**（新增的拆除分支不碰这两张计数表） |

所以 GTET 侧（`AutoBuildSettingMixin` / `AdvancedBlockPatternMixin` / `TerminalSettings` / `TerminalLang`）
**一个文件都不用改**，也就不需要为这次补丁重跑 `gradlew classes`（`modpatch/**` 不参与 GTET 的编译）。

### 5.3 第三轮（放大 + 面板不依赖扫描）——**注入点与签名依旧一个都没变**

第三轮改的是：
- 补丁侧：只动 `AdvancedTerminalBehavior.java` 的布局常量与控件坐标（§2/§3），**没有新增 / 删除 / 改名任何方法**；
- GTET 侧：`AutoBuildSettingMixin` 的**方法体**改了（`apply` 的 RETURN 回调里改成走
  `TerminalSettings.lookupPreference`，多了一条「不在候选里就按线圈格补回来」的分支），
  但**注入目标、注入点、回调方法签名全都原样**：
  `@Inject(method = "apply", at = @At("RETURN"))` → `gtetcore$applyTierPreference(BlockInfo[], CallbackInfoReturnable<List<ItemStack>>)`。
  另外新增了两个 GTET 类（`TerminalStaticGroups` / `TerminalGroupSeeder`），都是 GTET 自己的新东西，
  与 mixin 契约无关。

证据（`javap -p` / `javap -v` 打在**本轮编译出来的**产物上，见 §9.4）：

```
# GTET 编译产物 build/classes/java/main：回调方法签名不变，注解里仍是 method="apply" / at=@At("RETURN")
javap -p -classpath build/classes/java/main rain.gtetcore.gtet.mixin.GTMT.AutoBuildSettingMixin
  private void gtetcore$applyTierPreference(com.lowdragmc.lowdraglib.utils.BlockInfo[],
      org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.List<net.minecraft.world.item.ItemStack>>);
javap -v -p ... | 常量池： #134 = Utf8 Lorg/spongepowered/asm/mixin/injection/Inject;
                                      #136 = Utf8 apply
                                      #138 = Utf8 Lorg/spongepowered/asm/mixin/injection/At;
                                      #140 = Utf8 RETURN

# 补丁 jar patchJar/gtmthings-1.6.0-forge.jar（本轮重建）：AutoBuildSetting 的公开面一字未动
javap -p -classpath patchJar/gtmthings-1.6.0-forge.jar 'com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior$AutoBuildSetting'
  public java.util.List<net.minecraft.world.item.ItemStack> apply(com.lowdragmc.lowdraglib.utils.BlockInfo[]);
  public boolean isPlaceHatch(com.lowdragmc.lowdraglib.utils.BlockInfo[]);
  public boolean isReplaceCoilMode();  public boolean isFlipMode();  public boolean isDemolitionMode();
  public void setModule(int); public void setIsFlip(int); public void setDemolition(int);
  public int getModule(); public int getIsFlip(); public int getDemolition();
  private static boolean lambda$apply$0(com.lowdragmc.lowdraglib.utils.BlockInfo);   ← apply 的方法体也没动

# AdvancedTerminalBehaviorMixin 的目标：useOn 描述符不变
javap -p -classpath patchJar/gtmthings-1.6.0-forge.jar com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior
  public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext);

# AdvancedBlockPatternMixin 的目标：autoBuild 描述符不变，旧描述符计数调用**仍是恰好 2 处**（@Redirect require=1）
javap -p -classpath ... com.hepdd.gtmthings.api.pattern.AdvancedBlockPattern
  public void autoBuild(net.minecraft.world.entity.player.Player, ...MultiblockState, ...AutoBuildSetting);
javap -p -c ... | Select-String 'getGlobalCount|getLayerCount'
        90: invokevirtual  MultiblockState.getGlobalCount:()Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;
        96: invokevirtual  MultiblockState.getLayerCount:()Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;
```

界面改动本身也核过字节码（确认 jar 里的类就是本轮源码）：

```
javap -p -c ... com.hepdd.gtmthings.common.item.AdvancedTerminalBehavior
  <clinit>: sipush 8 / newarray int → 28, 54, 88, 114, 140, 166, 192, 218      ← SET_ROW_Y（放大后的 8 行行顶）
  createWidget: sipush 372 / sipush 274 → WidgetGroup.<init>(IIII)V            ← 新窗口尺寸
                bipush 84 / bipush 8 → AlignLabelWidget 标题（居中）
                sipush 354 / 7 / 12 / 12 → 关闭按钮
```

## 6. 第二轮：三项「搭建行为」设置 + 重复次数上限（**逻辑改动**）

参照物是 GTO（GTOCore `20cd1384`）的 `integration/gtmt` 三件套，归档在
`../reference/gtocore-20cd1384/`：`GTO_integration_gtmt_AdvancedTerminalBehavior.java`、
`GTO_AdvancedBlockPattern.java`。**只在 GTM 7.5.3 真实存在的 API 上落地**，GTO 依赖的分叉 API
（`getSubPattern` / `cleanCache` / `BlockMap` + 注解处理器）一律没有照抄。

### 6.1 四项改动一览

| 设置 | `AutoBuildSetting` 字段 | NBT 键 | lang 键（label / tooltip） | UI 行 | 行为落在哪 |
|---|---|---|---|---|---|
| **模块搭建** | `module`（Lombok `getModule/setModule`） | `Module` | `item.gtmthings.advanced_terminal.setting.7` / `.7.tooltip` | 文本框 `TerminalInputWidget(104,125,36×14)` min 0 max 100 | `AdvancedTerminalBehavior#resolvePattern` / `#resolveSubPattern` 选图案 |
| **镜像搭建** | `isFlip`（`getIsFlip/setIsFlip`） | `IsFlip` | `…setting.6` / `.6.tooltip` | ✓ 复选框 | `AdvancedBlockPattern#autoBuild` 里 `isFlipped` 的来源 |
| **拆除模式** | `demolition`（`getDemolition/setDemolition`） | `Demolition` | `…setting.8` / `.8.tooltip` | ✓ 复选框 | `useOn` 的新分支 + `AdvancedBlockPattern#autoBuild` 的拆除分支 |
| 重复结构次数上限 | `repeatCount`（**字段/键都没动**） | `RepeatCount`（没动） | 键没动 | `.setMax(99)` → `.setMax(1000)` | 仅 UI 上限；实际重复数仍由 `aisleRepetitions` 夹 |

另有 3 个便利方法：`AutoBuildSetting#isFlipMode()`、`#isDemolitionMode()`（照 `isReplaceCoilMode()` 的写法）、
`AdvancedBlockPattern#clearCache(MultiblockState)`（见 6.3）。

### 6.2 「主结构搭建」选了哪种语义

**没有单独的键、没有单独的行**：`module == 0` 就是**主结构搭建**（= `controller.getPattern()`），
`module == N > 0` 才是第 N 套结构。理由：

- GTO（20cd1384）本身**没有**「主结构搭建」这个键（那一版 ATB 里的 `@RegisterLanguage` 只有
  拆除/模块/镜像/替换/等级方块 5 条，没有主结构），它的写法就是
  `module > 0 && subPattern != null ? subPattern[min(len, module) - 1].get() : controller.getPattern()` ——
  即「0 = 走 getPattern()」。照抄这个语义最省事，也一眼能看懂；
- 真要改成「主结构单独一行」，只需在 UI 上加一行 `module = 0` 的按钮 + 一句文案，语义不用动。

### 6.3 「第 N 套结构」到底从哪取（⚠️ 本节是本轮最需要知道的一件事）

GTO 的 `controller.getSubPattern()` 在 **GTM 7.5.3 里不存在**（见 6.4 证据 1），所以按两级降级：

1. **反射探 `getSubPattern()`**（GTO 分叉/将来的版本才有）：返回 `Supplier<BlockPattern>[]`，
   取 `subs[min(len, module) - 1].get()`（下标算法照 GTO）。在 GTM 7.5.3 上这条永远取不到，
   留着是为了「别的整合包换成 GTO 那种分叉 GTM 时自动就能用」，代价只有一次 `getMethod` 失败。
2. **反射探 `patternOfTier(int)`**（GTET 自己的多结构 API，`ETModularMachine`）：
   GTET 的模块化多方块里「第 N 套结构」= 「第 N 档模块对应的结构」
   （`ETModularMachine#getPattern()` 就是 `patternOfTier(moduleTier)`；GTET 没有别的方法能枚举结构）。
   只探 `rain.gtetcore.` 包下的类，避免误撞别的 mod 的同名方法；用反射是因为
   **补丁 jar 由 GTMThings 自己 build，编译期不能依赖 GTET**（同 §3.1 里 `TierGroups` 手写 NBT 的理由）。
3. **都取不到 → 退回主结构**（`controller.getPattern()`），和 GTO 在 `subPattern == null` 时一样是**静默降级**，
   不报错、不影响主结构的正常搭建。

已知代价/风险：`patternOfTier` 是 Kotlin `protected`（反射要 `setAccessible(true)`），
且它对「不支持的档位」没有契约 —— 目前唯一实现 `ETModularTestMachine#patternOfTier` 里
`sizeOfTier(tier)` 的 `else` 分支把任何非法档位都当 3³，不会爆规模；其它子类若实现得激进，
外层 `catch (Throwable)` 会兜住并退回主结构。

### 6.4 GTM 7.5.3 的三个 API 事实（带证据）

> ⚠️ 先把一件事说清楚：**GTMThings 1.6.0 编译期对齐的是 GTM 7.5.2**，不是 7.5.3
> （GTMThings 1.6.0 补丁检出的 `gradle.properties`: `gtceu_version=7.5.2`）。
> 两个版本在下面这三点上**并不完全一样**，所以每条都给了两版数据。

| 事实 | GTM 7.5.2（编译期） | GTM 7.5.3（运行期） | 结论 |
|---|---|---|---|
| `IMultiController#getSubPattern()` | **不存在** | **不存在** | GTO 的模块搭建路子照搬不了，只能降级（6.3） |
| `BlockPattern#setActualRelativeOffset(..., Direction, boolean)` | 有，**private** | 有，**private** | 签名里**确实有 `isFlipped`**，但是 private、GTMThings 调不到；GTMThings 的 `AdvancedBlockPattern` 自带一份私有拷贝，本来就用它（所以镜像只改了「`isFlipped` 从哪来」） |
| `MultiblockState#cleanCache()` | **不存在**（只有 `clean()`） | **不存在**；有 `clean()` 与 `clearCache()` | GTO 的 `cleanCache()` 是分叉加的方法；7.5.3 的等价物是 `clearCache()`，但它在 7.5.2 上编不过 → 只能反射（`AdvancedBlockPattern#clearCache`，先试 `clearCache()` 再退 `clean()`） |

证据命令与关键输出（javap 打在**真 jar** 上，源码 grep 佐证）。
下面 `<gtceu-1.20.1-7.5.3.jar>` 指仓内 vendored 的那份：
`patchJar/maven/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/gtceu-1.20.1-7.5.3.jar`（补丁 GTM，dev 与打包均取此处）。

```
# 1) getSubPattern：两个版本都没有
javap -p -classpath <gtceu-1.20.1-7.5.3.jar> com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController
  public default com.gregtechceu.gtceu.api.pattern.BlockPattern getPattern();
  public abstract com.gregtechceu.gtceu.api.pattern.MultiblockState getMultiblockState();
  ...（无 getSubPattern）
# 7.5.2 的同一命令输出完全一致；另外 GTM 源码树全仓 grep "SubPattern|subPattern" 零命中

# 2) setActualRelativeOffset 的签名
javap -p -classpath <gtceu-1.20.1-7.5.3.jar> com.gregtechceu.gtceu.api.pattern.BlockPattern
  private net.minecraft.core.BlockPos setActualRelativeOffset(int, int, int, net.minecraft.core.Direction, int, net.minecraft.core.Direction, boolean);
  private net.minecraft.core.BlockPos setActualRelativeOffset(int, int, int, net.minecraft.core.Direction, net.minecraft.core.Direction, boolean);
# 7.5.2 只有后者（同样 private，同样带 boolean）

# 3) cleanCache 在哪一层
javap -p -classpath <gtceu-1.20.1-7.5.3.jar> com.gregtechceu.gtceu.api.pattern.MultiblockState
  public void clean();
  public void clearCache();
  ...（无 cleanCache）
javap -p -classpath <gtceu-1.20.1-7.5.2.jar> com.gregtechceu.gtceu.api.pattern.MultiblockState
  public void clean();
  ...（既无 cleanCache 也无 clearCache；globalCount/getLayerCount 返回 Object2IntOpenHashMap<SimplePredicate>）
```

补充：7.5.3 的 `BlockPattern#checkPatternAt` 第一句就是 `worldState.clean()`，
所以拆除后那次 `clearCache` 只是「照 GTO 的卫生动作」，不是正确性的必要条件。

### 6.5 拆除模式的行为与「误删玩家方块」的闸

`AdvancedBlockPattern#autoBuild` 的每格循环里，**在原来「要不要放置」的逻辑之前**插入：

```java
if (autoBuildSetting.isDemolitionMode()) {
    if (predicate != null && !predicate.isAir() && !predicate.isController &&
            !world.isEmptyBlock(pos) && isStructureBlock(predicate, world.getBlockState(pos).getBlock())) {
        demolish(world, player, pos);
    }
    continue;   // 拆除模式下这一格只拆不建
}
```

- **只拆「这一格本来就该是本结构方块」的方块**：`isStructureBlock` 把该位置谓词的
  `common` ∪ `limited` 候选全列出来，逐个比 `Block` 引用（GTO 的 `contain` 判断）。
  这样**玩家在这一格放的、不属于本结构的方块一律不动** —— 这就是防误删的那道闸；
- 空气谓词 / 控制器谓词直接跳过（GTO 同款：不把「本来就该是空气」的位置乱清、不动控制器本体）；
- 配套地 `useOn` 里拆除分支**不调 `requestCheck()`**（GTO 也是），拆到一半的结构不该被判成型；
  分支条件是「成型与否都执行」，否则一个已成型机器拆不动；
- `demolish()` 用 `setBlockAndUpdate(pos, AIR)` 换掉方块，再 `player.addItem(...)`；
  背包塞不下就 `Block.popResource(...)` 掉在方块原地（**不会凭空消失**）。

**已知风险（只能在游戏里最终验证）**：

1. 判定用的是「该位置的候选方块集合」，所以**符合本结构谓词的分级机壳/线圈确实会被拆掉** ——
   这正是拆除模式的意图，但如果玩家把本结构的机壳挪到别处当装饰、或把机械外壳当建筑材料，
   又在别处恰好落在同一个谓词的位置上，就会被拆走。GTO 也是这个语义，本补丁未做额外限制。
2. 结构方块被拆掉后**原地放了别的方块**时，拆除模式不会清理它（因为不匹配候选），
   会留下「非结构方块卡在结构位」的残留 —— 这是拿安全性换的，避免误删。
3. 掉落的物品进的是**玩家背包**，不走 AE：GTO 会先试着塞进 AE 网络，塞不进才给玩家。
   **这一半（拆除掉落）目前仍未实现**，是本补丁与 GTO 的**唯一行为差异**。
   ⚠️ 别和「**建造取料**」混为一谈 —— 那一半（`isUseAE` 时从 AE 网络提取方块）**已经实现**了，
   见 §7 第一条与 `AdvancedBlockPattern.java:587-598`。
   注意 GTO 那边无论创造/生存都会给物品，本补丁照抄（创造模式会把方块放进背包）。

### 6.6 镜像搭建的落点

```java
boolean isFlipped = autoBuildSetting.isFlipMode() || controller.self().isFlipped();
```

只是把 `isFlipped` 的来源从「机器自身朝向」扩成「机器自身朝向**或**终端设置」，
`setActualRelativeOffset(x, y, z, facing, upwardsFacing, isFlipped)` 与 `resetFacing` 一行没改
（GTO 也是只改这一处）。机器自带 `allowFlip` 的那套（`checkPattern` 会正反各试一次）不受影响。

### 6.7 编译期校验（javac，不跑 Gradle）

用 GTMThings 真实编译期的 GTM 7.5.2 + LDLib + AE2 + Forge(47.4.23 mapped) + fastutil/oshi/commons-lang3
+ lombok + mergetool(distmarker) + brigadier/netty 组成 classpath，编译改过的两个文件：

```
javac -encoding UTF-8 -nowarn -proc:full -d <out> -cp <cp> \
  src/main/java/com/hepdd/gtmthings/common/item/AdvancedTerminalBehavior.java \
  src/main/java/com/hepdd/gtmthings/api/pattern/AdvancedBlockPattern.java
=== EXIT=0，输出 0 行 ===
```

> 注意：**不能**拿 GTM 7.5.3 当 classpath 编译这个文件 —— 7.5.3 把
> `MultiblockState.getGlobalCount()/getLayerCount()` 的返回类型从 `Object2IntOpenHashMap`
> 换成了 `Reference2IntOpenHashMap`，GTMThings 1.6.0 的原版源码在 7.5.3 上**本来就编不过**
> （这正是 GTET 要写 `AdvancedBlockPatternMixin` 那个 `@Redirect` 适配桥的原因，见该 mixin 的类注释）。
> 用 7.5.3 编译会稳定地在这两行报错，与本次改动无关。

产物上再核一遍 mixin 依赖点（`javap -p` / `javap -c`）：

```
# AutoBuildSetting：apply / isPlaceHatch / isReplaceCoilMode 原样，新增 3 组 getter/setter + 2 个 isXxxMode
public java.util.List<net.minecraft.world.item.ItemStack> apply(com.lowdragmc.lowdraglib.utils.BlockInfo[]);
public boolean isPlaceHatch(com.lowdragmc.lowdraglib.utils.BlockInfo[]);
public boolean isReplaceCoilMode(); public boolean isFlipMode(); public boolean isDemolitionMode();
public void setModule(int); public void setIsFlip(int); public void setDemolition(int);
public int getModule(); public int getIsFlip(); public int getDemolition();

# AdvancedTerminalBehavior.useOn 描述符不变（AdvancedTerminalBehaviorMixin 的注入目标）
public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext);

# AdvancedBlockPattern.autoBuild 描述符不变，且旧描述符计数调用**仍是恰好 2 处**（@Redirect require=1 的目标）
public void autoBuild(Player, MultiblockState, AdvancedTerminalBehavior$AutoBuildSetting);
  90: invokevirtual // Method MultiblockState.getGlobalCount:()Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;
  96: invokevirtual // Method MultiblockState.getLayerCount:()Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;
  （未改的归档版是 75 / 81 两条同样的调用，数量一致）
```

## 7. 有意没做的部分

- 「使用 AE 物品」的**建造取料**：**已经实现**（本节旧文写的"留给后续排期"已过期，已更正）。
  `AdvancedBlockPattern.java:587-598` 在 `isUseAE == 1` 且手上是**已绑接入点（`accessPoint`）的 AE2 无线终端**时，
  用 `storage.extract(AEItemKey.of(candidate), 1, Actionable.MODULATE, null)` 从 AE 网络取方块。
  ⚠️ GTO 那种「把 `AutoBuildSetting#apply` 改成返回 `List<AEKey>`」的改法**没有采用** —— GTET 这边按候选方块
  逐个 `extract`，不动返回类型，因此也没有牵连 AE2 依赖。
  **仍未实现的只有另一半**：拆除掉落物直接进 AE 网络（`demolish()` 现在是 `player.addItem(...)`，
  塞不下就 `Block.popResource(...)` 掉在原地，见 §6.5 风险 3）。
- **GTET 自己那套终端 AE 绑定链暂未接线**（已确认：保持现状，既不接线也不删除）：`TerminalSettings.linkAe/unlinkAe`、
  `AeGridLink`、`StructureBuilder` 目前**全项目没有调用点** —— 「把高级终端绑到某个无线接入点」这条路径
  **没有游戏内入口**。实际可用的是上面那条 `isUseAE` 分支：背包里带着已绑好接入点的 AE2 无线终端即可。
  将来若要接，入口大概是「Shift+右键无线接入点即绑定」的物品行为（`AeGridLink` 里的解析逻辑已经写好）。
- 不抄 GTO 的 `BlockMapSelector` / `BlockMap` + 注解处理器（那要引入 GTO 自己的 `BlockMap` 与
  `@DataGeneratorScanned` 那套数据生成）；GTET 这边的「分级方块」走的是自己那套
  `gtet_terminal.plan.groups` NBT + 右侧两块列表面板（§3）。
- 三个复选框的 tooltip 去掉了「(0:不启用,1:启用)」这种数字提示（控件已经不是数字框了），文案其余不动。

## 8. ⚠️ 补丁 jar 必须包含 datagen 产物（缺了 → 机器模型全变紫黑块）

> 这一节讲的是**打包 / 资源**坑，与 §1~§7 的界面、逻辑改动无关。2026-09-13 定位并修复。

### 8.1 现象

dev 进游戏后 **GTMT 的模型与材质全部变成紫黑块**，`run/logs/latest.log` 里满屏：

```
Exception loading blockstate definition: 'gtmthings:blockstates/creative_fluid_input_hatch.json' missing model for variant: ...
```

### 8.2 根因

GTMThings 的 `build.gradle` 第 14 行：

```groovy
sourceSets.main.resources { srcDir 'src/generated/resources' }
```

`blockstates/*.json` 与 `models/block|item/*.json` **全是 datagen 产物**，落在 `src/generated/resources`
（见 `gradle/scripts/moddevgradle.gradle` 的 `runs.data`：`--output src/generated/resources/`、
`--existing src/main/resources/`）。而那个目录在检出里**默认不存在** —— 只要没跑过 `runData`，
`gradlew spotlessApply build` 产出的 jar 就天然缺这些 json。

而 `patchJar/gtmthings-1.6.0-forge.jar` 正是由 `<GTMThings 补丁检出>/build/libs/gtmthings-1.6.0.jar`
改名而来。实测修复前那份 jar 与该 build 产物**逐字节相同**
（`SHA256 = 4B9C674E18919238374AEC31754BC89B8391CE58619116125E34CBC5DE7110A1`）——
确证补丁 jar 就是「没跑 datagen 的 build」的直接产物，不是别的地方拷错了。

### 8.3 数字实证

| 对象                                                                                       | 总条目    | `assets/**` | `*.class` | `models/block/**` | `models/item/**` | `blockstates/**` |
|------------------------------------------------------------------------------------------|--------|-------------|-----------|-------------------|------------------|------------------|
| 残缺补丁 jar（修复前 = `GTMThings-1.6.0\build\libs\gtmthings-1.6.0.jar`）                        | 456    | 310         | 101       | 18                | 39               | 0                |
| curse 原件（raw 与 deobf 两份，**资源完全相同**）                                                       | 1237   | 1092        | 98        | 278               | 299              | 261              |
| 修复后补丁 jar                                                                                 | 1238   | **1092**    | 101       | 278               | 299              | 261              |

两者差异 **784 条，全部是资源**（`*.class` 反而少 3 个 —— 那 3 个正是我们的补丁内部类）。
784 条的精确拆解 = **781 个 json 文件 + 2 个目录条目 + 1 个无扩展名文件**：

| 缺失内容                                                          | 条数  |
|---------------------------------------------------------------|-----|
| `assets/gtmthings/blockstates/*.json`（**json 文件**）              | 260 |
| `assets/gtmthings/models/block/machine/**/*.json`             | 260 |
| `assets/gtmthings/models/item/*.json`                         | 260 |
| `assets/gtmthings/lang/en_ud.json`（上下颠倒英文，上游自带，不影响显示）            | 1   |
| `assets/gtmthings/blockstates/`（**目录条目**，不是 json）               | 1   |
| `.cache/`（目录条目）、`.cache/9c309e0e...`（无扩展名，ForgeGradle 缓存垃圾，**不要拷**） | 2   |
| 合计                                                            | 784 |

⚠️ 两个容易数错的点：
① `blockstates` 下**需要补的 json 是 260 个**，不是 261 —— 第 261 条是目录条目 `assets/gtmthings/blockstates/`
自己在内的「匹配 `blockstates/*` 的条目数」（8.3 第一张表的 `blockstates/**` 列用的是这个口径，所以那里写着 261）；
② `models/block` 下缺的 260 条**全在 `models/block/machine/` 子目录里**，`models/block/` 直属的 18 个手写 json
本来就一直在补丁 jar 里（所以残缺版的「`models/block/**` = 18」不是 0）。

因此**实际需要补 782 条**（781 json + `blockstates/` 目录条目），另外 2 条 `.cache` 主动跳过。

反向差异只有 **3 条**，正是我们的补丁：
`AdvancedTerminalBehavior$ItemIconWidget.class`、`$TierGroups.class`、`$TierGroups$Group.class`。

### 8.4 怎么修（**已并入构建流程，不用手工做**）

```
gradlew buildPatchedGtmThings      # 外部 spotlessApply + build → 自动补齐 → 体检通过才覆盖 patchJar
gradlew resfixGtmtJar              # 只做「补齐 + 体检 + 落盘」（不跑外部构建）
```

`buildPatchedGtmThings` 的 `doLast` 现在按固定顺序做四件事（实现在 `scripts/patches.gradle` 的
`applyGtmtResfix` / `resfixAndVerifyGtmt`）：

1. 跑外部构建 `spotlessApply build`（步骤、JDK、参数见 `../README.md`）；
2. 拿 build 产物（`<检出目录>/build/libs/gtmthings-1.6.0.jar`）跑
   **`scripts/gtmt-resfix.ps1`**（Linux/macOS 是 **`scripts/gtmt-resfix.sh`**，参数与防线完全一致，
   由 `scripts/patches.gradle` 按 `os.name` 选，见 `../README.md`）：把仓内 vendored 的资源参考里缺的条目补进去，输出到
   `build/tmp/gtmt-resfix/resfix-out.jar`；
3. 体检这份输出（`jarAssetsProbe`：`assets/** ≥ 1080` 且存在 `assets/gtmthings/models/block/` 条目）；
4. **只有体检通过**才把它拷成 `patchJar/gtmthings-1.6.0-forge.jar`；不通过就 `GradleException`
   让任务失败，并明确写出「patchJar 里那份没有被覆盖，仍是上一份通过体检的产物」。

⚠️ 所以「只 build 忘了补资源」这条路已经堵死了 —— 体检不过就没有 jar 落盘，不会静默降级。

`scripts/gtmt-resfix.ps1`（Linux/macOS 用 `scripts/gtmt-resfix.sh`，参数与防线完全一致，需 Python 3）
的三条硬约束（**改脚本时一条都不能松**）：

- ⚠️ **输入 jar 里已有的条目一律原样拷贝、绝不覆盖** —— 这就天然保护了我们改过的
  `assets/gtmthings/lang/en_us.json` / `zh_cn.json`（补丁版 **418** 键，第四轮加了
  `…panel.pick.tooltip`；原件只有 407 键），也保护补丁类；
- ⚠️ **绝不从参考里带进任何 `*.class`** —— 会覆盖补丁类；
- ⚠️ **跳过 `.cache/`**（ForgeGradle 留在原件里的缓存垃圾）。

脚本还有一条「绊线」：如果**输入 jar 里缺** `assets/gtmthings/lang/en_us.json` 或 `zh_cn.json`，
直接报错而不是从参考里补 —— 那说明 build 产物本身就不对（这两个文件本来就是手写的
`src/main/resources`，不是 datagen 产物）。

#### 8.4.1 资源参考为什么是仓内 vendored 的（原来用 curse 缓存路径，缓存一清就失效）

原来那次修复用的是 `<GRADLE_USER_HOME>\caches\forge_gradle\...\curse\maven\gtmthings-1104310\7712957_...`
这个 **Gradle 缓存**路径，`gradlew clean` / 换机器 / 清缓存就没了。现在固化在仓库里：

```
libs/gtmt/gtmthings-1.6.0-datagen-resources.zip     （782 条目 / 302 KB，只有资源、没有任何 .class）
```

| 项 | 值 |
|---|---|
| 来源 | CurseForge 上的 GTMThings 1.6.0 官方发布件：`gtmthings-1104310-7712957.jar`（file id 7712957） |
| 该原件 SHA256 | `72FDC64E0AF5093C7C4B4697ECF3186ED487D6368B68543EEFB65B8FE2852F`（本地缓存 `modules-2\files-2.1\curse.maven\gtmthings-1104310\7712957\`） |
| 本 zip 的选法 | 取原件里 **「没跑 datagen 的 build 产物」缺的那些条目**（= §8.3 里那 782 条：781 个 json + `assets/gtmthings/blockstates/` 目录条目），并去掉 `*.class` 与 `.cache/` |
| 本 zip SHA256 | `5a76715a00ac3aa78515e6283119a3cc13cb206b5c0ad7e866a54ddbb9f5d2be` |

**为什么只留这 782 条、不留整份原件**：体积（302 KB vs 960 KB）只是次要理由，
主要理由是**范围最小** —— 这份参考只能补「datagen 产物」，不可能把别的东西（尤其是
我们改过的资源）悄悄换回去；而且它一个 `.class` 都没有，「绝不带进 class」这条约束
连误操作的余地都没有。代价是它是个**派生文件**：要重新生成，就用上面那支原件与那个选法
（原件还在缓存里时，`add-missing-resources` 的那条规则即可复现；原件不在就先从 CurseForge 下
file id 7712957，校验 SHA256 后再选）。

⚠️ 为什么跨映射安全：这 782 条全是 JSON 资源，**与 SRG / Mojmap 映射无关**
（raw 与 deobf 两份原件的资源逐条 SHA256 完全相同，只有 `META-INF/jarjar/mixinsquared-*.jar`
那一条不同，而它本来就在补丁 jar 里）。

#### 8.4.2 正路（长期推荐，仍然有效）

先在 GTMThings 检出把 datagen 产物跑出来，再 build：

```
cd <GTMThings 补丁检出>
gradlew.bat runData                    # 生成 src/generated/resources/
gradlew.bat spotlessApply build
```

这样产出的 jar 本来就有这 782 条，后面的补齐步骤变成空操作（脚本会报 `to add = 0 entries`，
体检照样通过）—— 补齐流程与 runData **不冲突**，是「无论如何都不会缺资源」的那道保险。

### 8.5 校验方法（已持久化，防再犯）

`scripts/patches.gradle` 里新增了探针 `jarAssetsProbe`，并接进唯一的判据 `jarjarPatchProblems`。
`verifyPatchedJarjar` 现在会检查补丁 GTMT jar：

- `assets/**` 条目数必须 **≥ 1080**（正确基线 1092，留 12 条余量）；
- 必须存在以 `assets/gtmthings/models/block/` 开头的条目。

不满足 → `verifyPatchedJarjar` 直接抛 `GradleException`（`collectJarjarDeps` 也是同一份判据，至少 warn），
文案写明「补丁 jar 缺 datagen 资源；跑 `gradlew resfixGtmtJar` 从仓内参考补齐，或先跑 GTMThings 的 datagen」。
`buildPatchedGtmThings` / `resfixGtmtJar` 用的是**同一个探针**，而且是**硬失败**（不通过就不覆盖
`patchJar`，见 8.4）。

```
gradlew verifyPatchedJarjar        # 只校验，不构建，不碰外部仓库
```

⚠️ 注意：数 `assets` 条目才是**有效**判据。残缺 jar 里其实还有 18 条手写的
`models/block/*.json`，所以「有没有 `models/block/` 条目」这一条对这次的残缺版**照样通过** ——
真正拦住它的是条目数（实测 310 < 1080）。

改完 jar / 换机器 / 升版本后，照 8.3 的表核对基线（总条目 1238、`assets/**` 1092、`*.class` 101、
`blockstates/**` 261、`models/block/**` 278、`models/item/**` 299）。

手工数（PowerShell 一行）：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead('patchJar\gtmthings-1.6.0-forge.jar')
$n = $z.Entries | ForEach-Object { $_.FullName }; $z.Dispose()
"total = $($n.Count)"; "assets = $(($n -like 'assets/*').Count)"; "class = $(($n -like '*.class').Count)"
```

### 8.6 ⚠️ 换了 jar 内容但坐标没变 → dev 会用旧缓存（会白修）

`scripts/dependencies.gradle` 用的是 flatDir 坐标 `jarjar:gtmthings-1.6.0:forge`。坐标没变、
只有文件内容变了，**Gradle / ForgeGradle 的 deobf 缓存不会自动失效**（它按坐标缓存）：

```
<GRADLE_USER_HOME>\caches\forge_gradle\deobf_dependencies\jarjar\gtmthings-1.6.0\
  └─ forge_mapped_parchment_2023.09.03-1.20.1\
       └─ gtmthings-1.6.0-forge_mapped_parchment_2023.09.03-1.20.1.jar   ← 就是这个
```

实测：源 jar 换成 1092 条的新版之后，这里仍然是 6:22 生成的旧产物
（**456 条 / `assets/**` 310**）。所以**必须手工删掉 `...\jarjar\gtmthings-1.6.0\` 整个目录**
（或 `--refresh-dependencies`），否则 dev 里用的还是缺资源的旧 deobf 产物，照样紫黑块。
删完跑一次 `gradlew classes`，再把新生成的 deobf jar 数一遍：`assets/**` ≥ 1092
才是「dev 里不再紫黑块」的直接证据。

### 8.7 怎么验证「补齐流程」本身（不用跑外部构建）

拿一份**残缺 jar** 当输入，看流程会不会把它补成完整的 —— 这也是唯一能在 CI / 本机快速复现的验法
（`-PgtmtResfixOut` 指到临时文件，不会动 `patchJar`）：

```
gradlew resfixGtmtJar "--PgtmtBuiltJar=<检出目录>\build\libs\gtmthings-1.6.0.jar" "-PgtmtResfixOut=build/tmp/gtmt-resfix-verify/gradle-resfix-out.jar"
```

2026-09-13 实测输出（节选，输入正是「只 build 没跑 runData」的那份 456 条目产物）：

```
gtmt-resfix: input   = <检出目录>\build\libs\gtmthings-1.6.0.jar  (456 entries)
gtmt-resfix: ref     = <项目根>\libs\gtmt\gtmthings-1.6.0-datagen-resources.zip
gtmt-resfix: to add  = 782 entries
   ext '.json' = 781
   ext '' = 1
gtmt-resfix: wrote ...\gradle-resfix-out.jar  (1238 entries, assets=1092, class=101)
[gtetcore] 补丁 GTMThings 已就绪（资源补齐后）：...  assets=1092 sha256=1b45a707899336fd6d874f53aa9e2b01e2474bbfbb39cf467b5b3073e512051b
```

把结果与那次手工修好的 `patchJar/gtmthings-1.6.0-forge.jar` **逐条目比内容 SHA256**：
1238 条全中、**0 条不同 / 0 条缺失**；`class=101` 说明补丁那三个内部类一个不少，
`assets=1092` 正是 §8.3 的正确基线。也就是说：**今天再走一遍这条流程，产出的就是当初手工修好的那一份。**

⚠️ **负例（证明它真的会拦，不是只会「补齐成功」）**：把输入换成一份「什么都缺」的 jar
（只有 `META-INF/MANIFEST.MF` 的 163 字节 zip），参考 zip 里可补的就只有那 782 条：

```
gradlew resfixGtmtJar "-PgtmtBuiltJar=build/tmp/gtmt-resfix-verify/tiny-input.jar" "-PgtmtResfixOut=build/tmp/gtmt-resfix-verify/should-not-be-trusted.jar"
> Task :resfixGtmtJar FAILED
gtmt-resfix: to add  = 782 entries
gtmt-resfix: wrote ...resfix-out.jar  (783 entries, assets=782, class=0)
* What went wrong:
Execution failed for task ':resfixGtmtJar'.
> [gtetcore] 补齐后的 GTMThings jar 仍然缺 datagen 资源：assets 条目 = 782（要求 ≥ 1080），方块模型目录存在 = true
    ... patchJar 里那份**没有被覆盖**，仍是上一份通过体检的产物。
```

实测事后核对：`patchJar/gtmthings-1.6.0-forge.jar` 的 SHA256 前后完全相同
（`A5444DBD...7430`），`-PgtmtResfixOut` 指的那个文件**根本没被写出来** —— 体检不过就什么都不落盘。

## 9. 右侧两块面板「不依赖扫描」直接列出 5 类（2026-09-13 第三轮）

> 用户要求：「这边直接显示可选的（线圈，能源仓，超频仓，线程仓，配置仓（输入，输出不需要））」
> 「把这个终端的大小改大点，这个太小了」。§2/§3 是放大后的坐标表；这一节讲数据从哪来、以及那个
> **「面板里选了却搭建不生效」的坑**怎么解。

### 9.1 现状与目标

原来两块列表的数据只有一份来源：**上次 Shift+右键控制器扫描出来的分级组**
（`gtet_terminal.plan.groups`）—— 没扫过就只显示 `…panel.empty`。目标：一打开终端就能直接选这几类。

### 9.2 静态组写在哪、谁来写（GTET 侧）

> 第三轮是 **5 类**；第四轮把 **并行仓**补成第 6 类（用户要求，见 §10.3）。下表是当前的 6 类，
> 顺序 = 面板上的行序。

| 面板里的组 | 候选来源（都是现成的注册表 / 定义表，不扫描） |
|---|---|
| 线圈 | `GTCEuAPI.HEATING_COILS` 各等级线圈（按 tier 升序，与 GTCEu `Predicates.heatingCoils()` 同序） |
| 能源仓 | `GTMachines.ENERGY_INPUT_HATCH` / `_4A` / `_16A` / `SUBSTATION_ENERGY_INPUT_HATCH` |
| 超频仓 | `rain.gtetcore...ALLSmahine.OVERCLOCK_HATCHES`（`List<MachineDefinition>`，8 档） |
| 线程仓 | `ALLSmahine.THREAD_HATCHES`（8 档） |
| 并行仓（第四轮新增） | `ALLSmahine.PARALLEL_HATCHES`（`ETParallelHatches.VARIANTS`，IV ~ MAX 共 13 档） |
| 维护仓 | `GTMachines.MAINTENANCE_HATCH` / `CONFIGURABLE_MAINTENANCE_HATCH`（= 用户说的「配置仓」）/ `CLEANING_MAINTENANCE_HATCH` / `AUTO_MAINTENANCE_HATCH` |

- 实现：`src/main/java/rain/gtetcore/gtet/common/item/terminal/TerminalStaticGroups.java`
  （组键 → 候选 id，组键仍用 `StructureBuildPlanner.groupKey` 算，两边必须同一套）。
- **输入总线/输出总线、输入仓/输出仓故意不在表里**（用户明确说不需要）。
- 播种：`TerminalGroupSeeder`（`@Mod.EventBusSubscriber(bus = FORGE)`）在 **服务端**每 tick
  看主手物品是不是 `gtmthings:advanced_terminal`，是就 `TerminalSettings.installStaticGroups(held)`。

**⚠️ 为什么必须服务端写、为什么用 tick 而不是「打开界面时补一次」**：

1. 客户端改自己背包物品的 NBT 不会同步回服务端，写了等于没写；
2. 界面（LDLib）是**服务端与客户端各建一棵控件树**、按控件路径同步数据的（补丁里那条
   ⚠️ 注释就是这个意思）—— 两边必须读同一份 NBT。如果在「界面打开」那一刻才由服务端补 NBT，
   客户端那一次建树拿到的还是旧 NBT（**第一次打开必然对不齐**）；提前在 tick 里写好，
   客户端随物品 NBT 同步自然拿到同一份数据。
3. `installStaticGroups` 发现「该有的组都在」就返回 false、**一个字节都不改**，所以每 tick 调用
   不会反复触发物品同步；`group_prefs`（玩家在面板里选好的档）**一个都不动**。

合并语义（两个地方都改了）：
- `installStaticGroups`（每 tick 的被动播种）：已有组 + 静态组，**只增不减**；
- `cachePlan`（Shift+右键扫描后的写入）：`这次的扫描结果 ∪ 静态组` —— 扫描结果照旧替换上一次扫描
  留下的陈旧组，但**静态组永远不会被扫描清掉**（这就是「静态组与真实组共存」）。

### 9.3 ⚠️⚠️ 组键对不上 → 「面板里选了却不生效」（本轮真正要解决的坑）

链路上有两处独立算组键，**它们不保证一致**：

| 位置 | 候选从哪来 | 键怎么算 |
|---|---|---|
| 面板（补丁 `TierGroups`） / 扫描（`StructureBuildPlanner.plan`） | 静态表 / 结构谓词的 `candidates` | NBT 里存的那个 key（= 建表时用 `groupKey` 算的） |
| 搭建（`AutoBuildSetting#apply` → `AutoBuildSettingMixin`） | 谓词的 `BlockInfo[]` 经 `apply` 加工后 | `StructureBuildPlanner.groupKey(apply 的返回值)` |

典型反例（**线圈**）：`Predicates.heatingCoils()` 给的候选是**全部**线圈，而 GTMThings 的
`apply` 组装线圈候选时写的是 `for (int i = 0; i < blockInfos.length - 1; i++)` ——
**把最后一档（最高级线圈）砍掉了**。于是搭建时的键 =「全部线圈去掉最高档」，
而面板/扫描那侧的键 =「全部线圈」，两者不等 → 玩家选了线圈等于没选（最高档更是取都取不到）。

**解法（两层）**，实现在 GTET 侧 `TerminalSettings.lookupPreference(terminal, groupKey, candidates)`，
搭建路径（`AutoBuildSettingMixin`）与 `TerminalSettings.resolve` 共用同一份：

1. **组键精确命中**：照旧（扫描出来的组、以及候选集与静态表完全一致的组，键本来就相同）；
2. **回退：按候选集包含关系匹配**（只在键对不上时走）。对每个偏好 `(组键 → 物品)`：
   取它在 `plan.groups` 里的候选集 `G`（没有这一条就**跳过、不猜**），与本格候选集 `S` 比：
   - 必须 `G ∩ S ≠ ∅`，**且** `G ⊆ S` 或 `S ⊆ G`；
   - 命中多个时取**交集最大**的（并列取 NBT 里的先后顺序 → 结果确定）。
   例：线圈 `S ⊂ G` ✓；只收某一档的仓室格 `S ⊆ G` ✓。
3. 命中之后**怎么用**：`S` 里有那一档 → 把它提到候选最前面（原来是这个行为）；
   `S` 里没有（只可能是线圈那种「被砍掉最后一档」的情况）→ 只有当**整格候选都是线圈**时才把它
   插到最前面。

**为什么选「回退匹配」而不是「让两边用同一来源构造候选使键天然一致」**：

- 让两边同源在**物理上做不到**：面板要在**任何扫描之前**就能显示候选，那时根本没有任何控制器的
  谓词候选可用；而补丁 jar 由 GTMThings 自己 build，**编译期不能依赖 GTET 的类**
  （`TierGroups` 手写 NBT 契约就是这个原因），反过来 GTET 也不能调补丁新增的类。
  两边唯一的共同语言只有「候选 id 列表」本身 —— 于是就用它做匹配键（`groupKey`），并加一层包含关系回退。
- 只按「偏好物品在不在 `S` 里」匹配（更简单的写法）**不安全**：某个笼统的「任意仓室」格也会
  含能源仓那一档，玩家给「能源仓」选的东西就会顺手改掉那个格 —— 所以加了 `G ⊆ S` / `S ⊆ G`
  这道闸，只认「同一类部件的更具体 / 更宽泛版本」。
- 「整格候选都是线圈」才允许**插入**候选：线圈谓词本来就接受任何一级线圈，插进去结构照样成型；
  别的格子若插入谓词不接受的方块，只会让结构永远不成型 —— 宁可那一次不生效，也不放错方块。

**已知限制（没做的部分）**：

- 某组的键在 `plan.groups` 里找不到时（例如 NBT 是别的版本/别处写坏的）不做回退匹配 —— 宁可不生效；
- 若同时存在两个「同类」组（理论上可能，实际没遇到），按交集最大挑，仍可能不是玩家的本意 ——
  这种情况下用「组键精确命中」那条路才是可靠的；
- 面板里「切换」那块一个组只占一行，所以 6 类静态组就是 6 行（其余行是扫描出来的组）；
  「勾选」那块（第四轮起只显示**当前选中的那一组**）是**每个候选一行**，靠滚动条看 ——
  一次可见 7 行。

### 9.4 怎么验证（本轮实际做了的）

- **编译期**：GTET `gradlew classes` 通过（新类 `TerminalStaticGroups` / `TerminalGroupSeeder` +
  `TerminalSettings` / `StructureBuildPlanner` / `AutoBuildSettingMixin` 的改动全部编译通过）；
- **字节码**：`javap -p` / `javap -c` 核 `AutoBuildSettingMixin` 与补丁的 `AutoBuildSetting` ——
  `apply(BlockInfo[])` 描述符与 `@At("RETURN")` 注入点不变（§5.2 那张表照旧成立，
  详细清单见 §5.3）；
- ⚠️ **没能在游戏里跑**（本轮不允许起客户端）：所以「面板真的列出这几类」「选了之后搭建真的用那一档」
  这两条**只有编译期与逻辑层证据，没有运行时证据**。要真验，需要一次 `runClient`：
  Shift+右键/右键开终端看右侧两块是否有这几类、选一档后搭建、看放置的是不是选中的那个方块。

### 9.5 这一轮**没有**新增 lang 键

面板上的显示仍然是「图标 + 候选物品名」（补丁原有做法），6 类部件的名字就是方块自己的名字，
所以 `en_us` / `zh_cn` **一个键都没加**：本轮仍是 417 键（与 §8.4 里的数字一致，两边键数保持相等）。
lang 文件在本轮构建前后与归档逐字节一致（`spotlessApply` 也没动它）。
**第四轮加了一个键**（`…panel.pick.tooltip`，见 §10.4），两份都变成 418 键。

---

## 10. 第四轮：右侧两块面板联动 + 补「并行仓」（2026-09-13）

> 用户实机验收后原话：**「该成选择后继续在右边弹出对应的分类，或者在右下块只显示选择的种类，
> 把并行仓补上」**，并确认选「右下块只显示右上当前选中的那一组」这一种做法。

### 10.1 改了什么

| 位置 | 改动 |
|---|---|
| 补丁 `AdvancedTerminalBehavior`（**界面**） | 上方那块每行铺一个透明整行按钮 = 选中该组；`[▶]` 也顺手把该组设为"当前组"；下方那块**只显示当前组的候选**；行首用 `▶ ` 标出当前组 |
| 补丁 `TierGroups`（NBT 读写） | 新读写 `gtet_terminal.ui_group`（当前组），见 §10.2 |
| 补丁 lang（`lang/assets/gtmthings/lang/*.json`） | 新增 `…panel.pick.tooltip`（中英各一条，417 → 418 键） |
| GTET `TerminalStaticGroups` | 补第 6 类**并行仓**：`ALLSmahine.PARALLEL_HATCHES`（IV ~ MAX 13 档），插在线程仓与维护仓之间；"几类都齐才缓存"的判据随之从 5 改成常量 `CATEGORY_COUNT = 6` |

补丁侧**没有**硬编码任何类别（`TierGroups.read` 一直是"NBT 里有什么组就显示什么组"），
所以补并行仓这一件事**只需要改 GTET 侧那一张静态表** —— 面板下次打开就多出一行「并行仓」。

### 10.2 「当前选中的那一组」存在终端 NBT（服务端权威），不是界面本地状态

```
gtet_terminal.ui_group : "<组键>"      // 与 plan.groups 里的 key 同一套；空/找不到 → 退回第一组
```

- 点行 / 点 `[▶]` 时**只在服务端**写这个键（`TierGroups.setActive`；客户端的回调被
  `isClientSide()` 挡掉，和本文件里其它所有写 NBT 的回调同一套写法）。
  客户端在服务端写完后的下一个同步周期（物品 NBT 走槽位同步）就能看到新值，
  这一点与本界面**既有**的 ✓ 勾选态是**同一条链路**（`TierGroups.chosen` 本来就在客户端读 NBT）。
- **为什么不做成界面本地状态**：LDLib 的界面是**服务端与客户端各建一次**的（§9.2 第 2 条）。
  本地字段只有点的那一端会变 —— 服务端那份永远停在第一组，两块面板就会各自显示不同的组；
  而且它跨不了界面开关（关了再开又回到第一组）、也没法给「两个终端各选各组」提供独立性。
  写 NBT 则天然是"服务端权威 + 随物品同步"，顺带解决上面两条。
- **为什么不用"重建控件树"来实现联动**：控件树两端按**控件路径**同步数据，树结构在界面存活期间
  必须一致（§10.2.1 第 1 条）。所以下面那块的做法是"每组建一个子容器、全都建出来"，
  再按 `ui_group` 决定谁可见、谁被挪走。

#### 10.2.1 三条 LDLib 事实（javap 打在**本项目实际编译用的** ldlib deobf jar 上）

| 事实（证据） | 对写法的约束 |
|---|---|
| 控件树两端各建一次、数据按控件路径同步（既有结论，见 §9.2） | 树结构**任何时候**都得一致 ⇒ 不能"按选中的组建树"，只能"全建出来 + 只换可见性/位置" |
| `WidgetGroup#detectAndSendChanges` 遍历子控件时只判 `isActive()`，**不看 `isVisible()`**（字节码） | `setVisible(false)` 不会掐断藏起来那几行的数据同步（✓ 勾选态照旧更新） |
| `DraggableScrollableWidgetGroup#computeMax` 对**所有**子控件取 `height + selfY + scrollYOffset` 的最大值，**同样不看可见性**（字节码） | 光隐藏不挪位置 ⇒ 滚动条仍按"所有组加起来"的高度给出一大段空白。所以藏起来的容器被挪到 `HIDDEN_Y = -10000`，取 max 时贡献为负、自然被忽略 |
| `WidgetGroup#mouseClicked` 从**后往前**遍历，遇到第一个"吃掉点击"的子控件就返回（`isVisible() && isActive()` 才会被考虑；字节码） | 整行透明按钮放在**最前面**：图标/文字不吃点击 ⇒ 整行可点；`[▶]` 按钮后加 ⇒ 它自己的点击不被抢 |
| `ModularUIGuiContainer#containerTick()` → `mainGroup.updateScreen()`（字节码） | 客户端每 tick 能重算布局；服务端那条是 `ModularUIContainer` 的 `detectAndSendChanges` —— 两边都会走到 `TierListPanel#applyLayout` |
| `DraggableScrollableWidgetGroup#computeMax` 的自动调用点只有"子控件尺寸/位置变化"（且被 `isInitialized()` 挡在 initWidget 之前；字节码） | 第一次布局发生在 `initWidget` 之前 ⇒ `applyLayout` 里必须**自己补算一次** `computeMax()`，否则滚动区最大高度停在 0，候选一多就滚不动（看得见、够不着） |

#### 10.2.2 布局与"不顶出屏幕"

窗口尺寸与两块面板的坐标**一个字都没改**（仍是 372×274 / 198×114，见 §2 §3），
所以这次联动不会让面板或窗口长大。下方那块的内容高度从"所有组所有候选"降到"当前组"，
滚动范围随之变小；换组时 `applyLayout` 会顺手把滚动条拉回顶部（否则会停在上一个组的滚动位置上）。

### 10.3 并行仓（GTET 侧第 6 类）

- 表里插在**线程仓与维护仓之间**（GTET 自己的三种分级仓排在一起、维护仓垫底）；
- 来源 `ALLSmahine.PARALLEL_HATCHES`（Kotlin `var ... private set` ⇒ Java 侧 `getPARALLEL_HATCHES()`，
  与已有的 `getOVERCLOCK_HATCHES()` / `getTHREAD_HATCHES()` 同一写法），13 档 IV ~ MAX；
- ⚠️ `TerminalStaticGroups.stacks()` 里"拿齐了才缓存"的那个数必须跟着改成 6：
  它本来就是为了"注册还没跑完时别把空表缓存住"，5 改成 6 之后语义不变（少一类就每次重算）；
- 组键仍由 `StructureBuildPlanner.groupKey` 算（排序后的候选 id），与扫描/搭建两侧同一套（§9.3）。

### 10.4 lang：只加了 1 条

`item.gtmthings.advanced_terminal.panel.pick.tooltip`
（en：`Click to show this tier group in the panel below.` / zh：`点击后下方面板改为显示这一组的分级方块。`），
给整行按钮做 tooltip。两份 lang 从 417 → **418** 键（§8.4 里那个数字已同步改）。

### 10.5 这一轮**没有改任何 mixin**（§5.2 / §5.3 那两张表照旧成立）

- 补丁侧只加/改**私有**方法与一个**新的私有静态嵌套类** `TierListPanel`，
  `createWidget` / `AutoBuildSetting` 的公开面一个字没动；
- GTET 侧这一轮改的是 `TerminalStaticGroups`（静态表）与两个 `canShared()` 覆写（见 §10.6），
  与三个 mixin 的注入目标无关。

### 10.6 顺带：样板总成 / 镜像也加了 `canShared() = false`（**GTET 侧**，不在补丁里）

`ETMEPatternBufferPartMachine` 与 `ETMEPatternBufferProxyPartMachine` 各加一条
`canShared() = false`，`ETMEPatternBufferHatches.kt` 里那两处 tooltip 由 `gtceu.part_sharing.enabled`
改成 `gtceu.part_sharing.disabled`。依据（GTM 7.5.3 源码）：

- `MEPatternBufferPartMachine#getTerminalGroup()` 直接取 `getControllers().first()` ——
  GTM 自己就假设「一件总成只属于一个控制器」，被两台共享时 AE 终端分组名会变成"任取一个"；
- `pushPattern` 把原料推进**这一件自己**的 `InternalSlot` 库存
  （`pushInputsToExternalInventory(inputHolder, this::add)`），而两个控制器的部件表都能取用这份库存 ⇒
  谁先跑谁吃掉 = 串配方；镜像侧同理（转发链指向同一个宿主库存）。
- 不影响主要用法：总成当宿主（不组进任何成型结构）时 `isFormed()` 为 false，这道闸门不参与判断；
  「一个宿主 + 多台机器各自的镜像」也完全不碰它。

⚠️ 这条闸门触发时 GTM 设的错误键是 `multiblocked.pattern.error.share` —— 该键 GTM/LDLib/GTMThings
的语言文件里**都不存在**（GTM 那 7 份 lang 全库 grep `multiblocked` 零命中）。GTET 侧补它的位置与
「GTM 7.5.3 里其实没有任何调用点会渲染这个键」这条**纠正**，写在
`src/main/kotlin/rain/gtetcore/gtet/data/lang/Lang.kt` 的 KDoc 里（不在本补丁的 lang 里 ——
补丁侧只管 `gtmthings` 自己那套键）。

### 10.7 怎么验证（本轮实际做了的）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 补丁源码编译 | 外部 `gradlew spotlessApply build`（GTMThings 补丁检出） | `BUILD SUCCESSFUL`，`spotlessCheck` 通过（补丁源码格式合规） |
| 资源补齐 + 体检 + 落盘 | `gradlew resfixGtmtJar` | `to add = 782`，输出 `1239 entries, assets=1092, class=102`，体检通过后覆盖 `patchJar` |
| 产物自检 | `gradlew verifyPatchedJarjar` | `[OK] 补丁 GTMThings`（补丁内部类 + `assets ≥ 1080` 两条都过） |
| 补丁类还在 | `javap` 打在**最终**那份 jar 上 | `AdvancedTerminalBehavior$TierListPanel` 在，且有 `detectAndSendChanges` / `updateScreen` / `applyLayout` |
| 三个 mixin 的签名 | 同上 | `useOn(UseOnContext)` 不变；`AutoBuildSetting#apply(BlockInfo[])` 不变；`AdvancedBlockPattern#autoBuild(...)` 不变，且 `getGlobalCount`/`getLayerCount` **各 1 处共 2 处**（`@Redirect require=1` 的目标）不变 |
| dev 实际加载的那份 | `gradlew classes` 后数 deobf 缓存 jar | 1239 / **assets=1092** / class=102 / 含 `TierListPanel` —— dev 里不再紫黑块、且是补丁版（内容是扁平坐标，指纹变了会自动重做，实测会） |
| GTET 侧 | `gradlew classes` / `runData` / `build` | 三条都 `BUILD SUCCESSFUL` |

⚠️ **没能在游戏里验证的部分**（本轮不允许起客户端）：联动交互本身（点上面某一行、下面换组、
`▶` 同时换组与切档、滚动条回到顶部）、`ui_group` 经由物品 NBT 同步到客户端的那一拍延迟、
并行仓那一行是否真的出现、`canShared()=false` 在双子结构下的实际表现、
以及 `multiblocked.pattern.error.share` 的显示（GTM 7.5.3 里根本没有调用点会渲染它，
这一点已在 §10.6 说明）。这些都需要一次 `runClient`。

