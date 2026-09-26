# modpatch —— 对第三方 mod 的源码级补丁（不是 mixin 的部分）

这里放的是**直接改源码**的补丁：补丁文件按原包路径完整归档，用的时候覆盖到对应 mod 的源码树，
由那个 mod 自己 build 出打过补丁的 jar，再由本仓库的 `build.gradle` 把 jar 内嵌分发。

## 目录约定

```
modpatch/
  <mod-id>-<version>/          ← 外层：被补丁的 mod + 版本（对齐依赖里写的版本号）
    NOTES.md                   ← 这个 mod 的补丁说明 / 对照表
    ui/                        ← 内层：改动性质分类（界面 / 交互）
    lang/                      ←        文案（只有真的改了它自带 lang json 才放）
    logic/                     ←        机器逻辑 / 搭建逻辑
```

内层的分类目录下**原包路径完整保留**（例如 `ui/com/hepdd/gtmthings/common/item/Xxx.java`），
目标仓库里就是 `src/main/java/com/hepdd/gtmthings/common/item/Xxx.java`；
`lang/` 下保留 `assets/<modid>/lang/...`（对应 `src/main/resources/assets/<modid>/lang/...`）。
一个文件只出现在**一个**分类里，跨分类的改动按“主要性质”归档并在该目录 `NOTES.md` 里注明。

## 现有内容

| 目录                           | 被补丁的 mod                | 版本    | 来源 / 许可                                       | 内容                                                                       |
|------------------------------|-------------------------|-------|-----------------------------------------------|--------------------------------------------------------------------------|
| `gtmthings-1.6.0/`           | GTMThings               | 1.6.0 | liansishen/GTMThings（LGPLv3.0，原作者 liansishen） | 高级终端：界面重画成左侧设置面板 + 右侧两块分级方块列表（`ui/`+`lang/`）；再把 GTO 的**模块搭建 / 镜像搭建 / 拆除模式**三项搭建行为与重复次数上限补进逻辑（`logic/`，见该目录 `NOTES.md` §6）；第三轮把窗口从 300×172 放大到 **372×274**（右侧每块可见 7 行，见 §2/§3）并把右侧两块面板改成「不扫描也能列出 5 类可选部件」（见 §9，数据由 GTET 侧预置） |
| `gtceu-7.5.3/logic/`         | GregTech Modern (GTCEu) | 7.5.3 | GregTechCEu/GregTech-Modern（LGPLv3.0）         | 8 个文件：材料 / 注册表 / 配方相关改动                                                  |

---

# 补丁 jar 怎么产出（**已并入 `gradlew build`，不用手工做**）

产物有两份，`gradlew build` / `gradlew jar` 会**自动产出并校验**；产物已在时那两个任务直接
`SKIPPED`（秒过）。实现在 `scripts/patches.gradle`，`build.gradle` 的 JarJar 段只消费结果。

| 补丁         | 外部源码检出（默认值可用 `-P` 覆盖）                                  | 构建命令                                        | 产物落点                                                                                |
|------------|---------------------------------------------|---------------------------------------------|-------------------------------------------------------------------------------------|
| GTM 7.5.3  | GregTech-Modern 7.5.3 补丁检出（`-PgtmRepo=<GTM 补丁检出>`）      | `gradlew build publishToMavenLocal`         | 拷成 `patchJar/maven/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/` 下的 **jar + pom + module**（jar 供打包内嵌，三件套供 dev 依赖解析） |
| GTMThings  | GTMThings 1.6.0 补丁检出（`-PgtmtRepo=<GTMThings 补丁检出>`）                   | `gradlew spotlessApply build` **→ 自动补资源 → 体检** | 拷成 `patchJar/gtmthings-1.6.0-forge.jar`                                          |

⚠️ **补丁 GTM 已以 maven 布局 vendored 在仓内 `patchJar/maven/`，dev 与打包都从这里取，`mavenLocal()` 仅作兜底**：
`scripts/repositories.gradle` 里名为 `gtetcore patchJar` 的 file-maven 仓库**排在 `mavenLocal()` 之前**，
所以任何机器（克隆后 m2 里没有补丁版）解析 `com.gregtechceu.gtceu:gtceu-1.20.1:7.5.3` 拿到的都是补丁 jar，
不会退到 `maven.gtceu.com` 的官方未打补丁版（18209988 B）。布局与未 vendored 的文件（`-sources.jar`）见
`patchJar/README.md`。

⚠️ **GTMThings 的 `build` 产出一定缺 datagen 资源，所以「补齐」是构建流程的一部分**：
它的 `blockstates/*.json` 与 `models/block|item/*.json` 全是 datagen 产物
（`build.gradle` 的 `sourceSets.main.resources { srcDir 'src/generated/resources' }`），
而那个目录在检出里默认不存在 —— 只跑 `spotlessApply build` 得到的 jar 实测 **456 条目 / `assets/**` 310**，
进游戏就是 GTMT 模型全紫黑块（`Exception loading blockstate definition: ... missing model for variant`）。

所以 `buildPatchedGtmThings` 的顺序是固定的四步：

1. 外部 `spotlessApply build`；
2. 跑 `scripts/gtmt-resfix.ps1`（Linux/macOS 是 `scripts/gtmt-resfix.sh`）：把仓内 vendored 的
   `libs/gtmt/gtmthings-1.6.0-datagen-resources.zip`（782 条 datagen 资源，来源与选法见
   `gtmthings-1.6.0/NOTES.md` §8.4.1）里缺的条目补进 build 产物；
3. 体检（`assets/** ≥ 1080` 且存在 `assets/gtmthings/models/block/` 条目）；
4. **体检通过才**覆盖 `patchJar/gtmthings-1.6.0-forge.jar`；不通过就抛 `GradleException`
   让任务失败，并明确说「patchJar 没有被覆盖」。

只做 2~4 步（不跑外部构建）的独立任务：

```
gradlew resfixGtmtJar                                    # 输入=外部检出 build/libs，输出=patchJar
gradlew resfixGtmtJar "-PgtmtBuiltJar=<jar>" "-PgtmtResfixOut=<jar>"   # 覆盖输入输出（验证/排查用）
```

触发规则：

| 情形                              | 行为                                                                        |
|---------------------------------|---------------------------------------------------------------------------|
| 产物已在                            | `buildPatchedGtm` / `buildPatchedGtmThings` 都 `SKIPPED`（快路径，不跑外部构建）         |
| 产物缺失 + 源码检出在                    | 自动执行上面那条外部构建命令（GTMThings 还会接着补齐 + 体检）                                      |
| 源码检出不存（别人机器 / CI）               | warn 后跳过；GTM 本次不内嵌，**GTMThings 也补不出来** —— 见下面那条 ⚠️                            |
| `-PgtetPatchBuild=true`         | 忽略快路径，**强制重建**两份                                                          |
| `-PgtetSkipPatchBuild=true`     | **强制跳过**外部构建（用现成产物 / CI）                                                  |

⚠️ **GTMThings 已经不存在「退回 curse 官方原件」这条退路**：`scripts/dependencies.gradle` 现在把 dev 的
compile/runtime 依赖直接指向 `patchJar/gtmthings-1.6.0-forge.jar`（`fg.deobf("jarjar:gtmthings-1.6.0:forge")`，
flatDir 仓库 `dir "patchJar"`）—— 以前 dev 用的是 curse 上的官方原件，所以补丁**在 dev 里根本没生效**。
代价是：这个 jar 现在是 dev 编译的硬依赖，仓里没有就先跑 `gradlew buildPatchedGtmThings`
（`gradlew build` / `jar` 那条链路也会自动补建），否则依赖解析直接失败。

自检（只校验、不构建、不碰外部仓库）：

```
gradlew verifyPatchedJarjar
```

判据是**产物里的字符串/内部类 + 资源规模**，不是时间戳：

- GTM：`com/gregtechceu/gtceu/api/machine/multiblock/MultiblockControllerMachine.class` 里含 `MultiblockConfigHook`；
- GTMThings：`AdvancedTerminalBehavior$ItemIconWidget` / `AdvancedTerminalBehavior$TierGroups` 两个内部类存在；
- GTMThings（**2026-09-13 新增**）：`assets/**` 条目数 ≥ 1080，且存在以 `assets/gtmthings/models/block/`
  开头的条目 —— 用来拦住「只 build 没跑 datagen」的缺资源补丁 jar（判据实现在 `scripts/patches.gradle`
  的 `jarAssetsProbe` + `jarjarPatchProblems`，`collectJarjarDeps` 也复用同一份判据 warn）。
  正确基线：总条目 1238 / `assets/**` 1092 / `*.class` 101，详见 `gtmthings-1.6.0/NOTES.md` §8。
  同一个探针也用在 `buildPatchedGtmThings` / `resfixGtmtJar` 里，那里是**硬失败**（不通过就不覆盖
  `patchJar`），所以「缺资源的补丁 jar」现在既产不出也进不了包。

## 资源补齐链路的三块拼图（2026-09-13 固化）

| 文件 | 作用 |
|---|---|
| `scripts/gtmt-resfix.ps1`（Windows）/ `scripts/gtmt-resfix.sh`（Linux、macOS） | 参数化（输入 jar / 参考 jar / 输出 jar）的资源补齐脚本，两份**双份共存**、参数与防线完全一致；.ps1 走 `powershell.exe`、.sh 走 `bash`，由 `scripts/patches.gradle` 按 `os.name` 选。四条硬约束：已存在条目一律跳过、拒绝任何 `.class`、跳过 `.cache/`、缺我们改过的 lang 就直接报错（绊线）。**.sh 需要 Python 3**（用标准库 `zipfile`，不依赖 `zip`/`unzip` 命令） |
| `libs/gtmt/gtmthings-1.6.0-datagen-resources.zip` | 仓内 vendored 的资源参考（782 条 datagen 产物 / 302 KB，无任何 `.class`）。**来源与选法**：GTMThings 1.6.0 官方 CurseForge 发布件 `gtmthings-1104310-7712957.jar`（SHA256 `72FDC64E...852F`）里「没跑 datagen 的 build 产物」缺的那些条目 —— 详见 `gtmthings-1.6.0/NOTES.md` §8.4.1（那里也写了为什么不留整份原件：范围最小、体积小、结构上不可能带进 class） |
| `scripts/patches.gradle` 的 `applyGtmtResfix` / `resfixAndVerifyGtmt` | 把上面两者接进 `buildPatchedGtmThings`（补齐 → 体检 → 才落盘），并暴露 `resfixGtmtJar` 任务 |

⚠️ Windows 上脚本里找 PowerShell 用的是**绝对路径** `%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`：
部分环境下 `powershell.exe` 不在 PATH 里（`Get-Command powershell.exe` 找不到），写 `powershell` 会直接
`Task ... FAILED`。非 Windows 走 `bash scripts/gtmt-resfix.sh`（`os.name` 里含 `win` 就用 .ps1，否则用 .sh）。
两个脚本的 stdout 一律 ASCII（中文经 Gradle 的 Exec 捕获会因代码页不匹配变乱码），
.ps1 文件本身带 UTF-8 BOM 以便 Windows PowerShell 5.1 正确读中文注释；`.sh` 由 `.gitattributes` 的
`*.sh text eol=lf` 保证 checkout 成 LF（CRLF 的 shebang 在 Linux 上会 `bad interpreter`）。

## ⚠️ 换了 `patchJar` 里 jar 的**内容**但 flatDir 坐标没变 → 必须手工清 deobf 缓存

dev 依赖是 flatDir 坐标 `jarjar:gtmthings-1.6.0:forge`（见上面的 ⚠️）。坐标没变、只有文件内容变时，
**Gradle / ForgeGradle 不会自动失效 deobf 缓存**（它按坐标缓存，不按内容 hash）：

```
<GRADLE_USER_HOME>\caches\forge_gradle\deobf_dependencies\jarjar\gtmthings-1.6.0\
  └─ forge_mapped_parchment_2023.09.03-1.20.1\gtmthings-1.6.0-forge_mapped_parchment_2023.09.03-1.20.1.jar
```

实测：源 jar 换成 1092 条的新版之后，这里仍是旧的 456 条 / `assets/**` 310。
**必须删掉 `...\jarjar\gtmthings-1.6.0\` 整个目录**（或 `--refresh-dependencies`），再跑一次 `gradlew classes`，
然后核对新 deobf jar 的 `assets/**` ≥ 1092 —— 这才是「dev 里不再紫黑块」的直接证据。
（2026-09-13 第三轮：这个目录已删掉并让 `gradlew classes` 重建过。）

⚠️ 这个目录**在 dev 客户端开着的时候删不掉**（客户端用 securejarhandler 把 jar 一直打开着，
删会报 `being used by another process`）。所以要**先关掉 runClient，再清缓存**。

## 外部构建的固定参数（内存吃紧的机器必须照用）

```
<检出目录>\gradlew.bat <tasks> --console=plain --no-daemon ^
  "-Dorg.gradle.java.installations.paths=<JDK 17 根目录>" ^
  "-Dorg.gradle.jvmargs=-Xmx512m" "-Dorg.gradle.workers.max=1"
```

- 两个检出都要求 **JDK 17**（toolchain 写死 17）；`JAVA_HOME` 指同一个 JDK 17 根目录
  （即 `scripts/patches.gradle` 的 `-PpatchJdk=` 那个值）。
- `--no-daemon` + 单 worker + 小堆是**必须**的：NeoForm 反编译峰值约 600MB，
  24G 内存/页面文件打满的机器上给多了会 `os::commit_memory(...) failed ... DOS error 1455`。
- ⚠️ GTM 检出自己的 `gradle.properties` 里有一行失效的 `org.gradle.java.home`
  （指向一个并不存在的 JDK 路径，形如 `.../eclipse_adoptium-17-.../jdk-17.0.19+10`）。
  `scripts/patches.gradle` 用命令行 `-Dorg.gradle.java.home=<JDK 17 根目录>` 覆盖它；
  手工跑外部构建时不加这一项会直接 `Java home supplied in org.gradle.java.home is invalid`。

## ⚠️ 注意：`gtceu-7.5.3/logic/` 是**归档**，别整目录覆盖到检出

GTM 检出（GregTech-Modern 7.5.3）里那 8 个文件本来就是**补丁版**
（与归档在**行尾归一化后**逐字节一致；原始字节差恰等于行数，是 CRLF/LF 的差别），
自动构建只 `build` 不覆盖源码 —— 所以**不要**再按“把 `logic/**` 整目录覆盖到检出”的旧办法操作。

（历史：这里曾有一处机械损坏 —— `GTCEuAPI.java` 第 27 行被写成 `public class /reGTCEuAPI {`，
已于收尾时改回 `public class GTCEuAPI {`，8 个文件重新比对全部一致。）

## ⚠️ 注意：GTMThings 补丁**编译期**用的是 GTM 7.5.2，不是随包的 7.5.3

GTMThings 检出的 `gradle.properties` 的 `gtceu_version` 还是上游默认的 `7.5.2`，
所以它的 `:compileJava` classpath 上是 `gtceu-1.20.1-7.5.2.jar`。补丁源码里**不能直接调用
只有 7.5.3 / GTET 补丁才有的 API**——典型是 `MultiblockState.clearCache()`：7.5.2 只有
`clean()`、没有 `clearCache()`（javap 实证），直接调用就是 `找不到符号` 让外部构建失败。
两种解法：① 版本适配（反射，见 `AdvancedBlockPattern.clearCache(MultiblockState)`）；
② 构建时覆盖 `-Pgtceu_version=7.5.3` 让它走仓内 `patchJar/maven/` 那份补丁构建（未实测）。

## 它怎么被本仓库消费

- 常量与任务都在 `scripts/patches.gradle`（`jarjarGtmJar` / `jarjarGtmtPatched` / `jarjarGtmName` /
  `jarjarGtmtName` 等 ext 属性）；`build.gradle` 的 JarJar 段**不再自己写一份**。
- `collectJarjarDeps`（`Copy`）`dependsOn buildPatchedGtmThings + buildPatchedGtm`，
  把两个 jar 统一改名成固定文件名收进 `build/jarjar/`；源在**执行期**才解析 ——
  否则会把“本次刚构建出来的补丁 jar”误判成缺失、嵌进 curse 官方原件。
- `tasks.named('jar')` 把它们塞进 `META-INF/jarjar/`，manifest 里写
  `ContainedDeps: gtceu-1.20.1-7.5.3.jar,gtmthings-1.6.0-forge.jar`。
- 升级某个内嵌 mod 时要**同时**改：`scripts/patches.gradle` 的版本常量（+ 外部检出路径）、
  `scripts/dependencies.gradle` 里 GTMThings 的 flatDir 坐标（`jarjar:gtmthings-<版本>:forge`）、
  `gradle.properties` 的 `gtm_version`。

## 历史：手工做法（现在只在排查时用）

1. 把 `<mod>-<version>/**` 里除 `NOTES.md` 外的文件按原路径覆盖到该 mod 的源码树。
2. 在该仓库跑上面的外部构建命令，产物 `build/libs/<name>.jar`。
3. GTMThings 手工把产物拷到 `patchJar/gtmthings-1.6.0-forge.jar`；
   GTM 手工把产物（jar + pom + module）从 m2 那个坐标拷到 `patchJar/maven/.../7.5.3/`
   （自动流程用的是 `publishToMavenLocal` 之后由 `buildPatchedGtm` 连带拷过来）。
