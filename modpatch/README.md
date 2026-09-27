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

内层的分类目录下**原包路径完整保留**（例如 `logic/com/gregtechceu/gtceu/api/GTCEuAPI.java`），
目标仓库里就是 `src/main/java/com/gregtechceu/gtceu/api/GTCEuAPI.java`；
`lang/` 下保留 `assets/<modid>/lang/...`（对应 `src/main/resources/assets/<modid>/lang/...`）。
一个文件只出现在**一个**分类里，跨分类的改动按“主要性质”归档并在该目录 `NOTES.md` 里注明。

## 现有内容

| 目录                           | 被补丁的 mod                | 版本    | 来源 / 许可                                       | 内容                                                                       |
|------------------------------|-------------------------|-------|-----------------------------------------------|--------------------------------------------------------------------------|
| `gtceu-7.5.3/logic/`         | GregTech Modern (GTCEu) | 7.5.3 | GregTechCEu/GregTech-Modern（LGPL-3.0）         | 8 个文件：材料 / 注册表 / 配方相关改动                                                  |

---

# 补丁 jar 怎么产出（**已并入 `gradlew build`，不用手工做**）

产物只有一份（补丁 GTM），`gradlew build` / `gradlew jar` 会**自动产出并校验**；产物已在时那个任务直接
`SKIPPED`（秒过）。实现在 `scripts/patches.gradle`，`build.gradle` 的 JarJar 段只消费结果。

| 补丁         | 外部源码检出（默认值可用 `-P` 覆盖）                                  | 构建命令                                        | 产物落点                                                                                |
|------------|---------------------------------------------|---------------------------------------------|-------------------------------------------------------------------------------------|
| GTM 7.5.3  | GregTech-Modern 7.5.3 补丁检出（`-PgtmRepo=<GTM 补丁检出>`）      | `gradlew build publishToMavenLocal`         | 拷成 `patchJar/maven/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/` 下的 **jar + pom + module**（jar 供打包内嵌，三件套供 dev 依赖解析） |

⚠️ **补丁 GTM 已以 maven 布局 vendored 在仓内 `patchJar/maven/`，dev 与打包都从这里取，`mavenLocal()` 仅作兜底**：
`scripts/repositories.gradle` 里名为 `gtetcore patchJar` 的 file-maven 仓库**排在 `mavenLocal()` 之前**，
所以任何机器（克隆后 m2 里没有补丁版）解析 `com.gregtechceu.gtceu:gtceu-1.20.1:7.5.3` 拿到的都是补丁 jar，
不会退到 `maven.gtceu.com` 的官方未打补丁版（18209988 B）。布局与未 vendored 的文件（`-sources.jar`）见
`patchJar/README.md`。

触发规则：

| 情形                              | 行为                                                                        |
|---------------------------------|---------------------------------------------------------------------------|
| 产物已在                            | `buildPatchedGtm` `SKIPPED`（快路径，不跑外部构建）                                      |
| 产物缺失 + 源码检出在                    | 自动执行上面那条外部构建命令                                                            |
| 源码检出不存（别人机器 / CI）               | warn 后跳过；本次不内嵌 GTM                                                            |
| `-PgtetPatchBuild=true`         | 忽略快路径，**强制重建**                                                             |
| `-PgtetSkipPatchBuild=true`     | **强制跳过**外部构建（用现成产物 / CI）                                                  |

自检（只校验、不构建、不碰外部仓库）：

```
gradlew verifyPatchedJarjar
```

判据是**产物里的字符串**，不是时间戳：

- GTM：`com/gregtechceu/gtceu/api/machine/multiblock/MultiblockControllerMachine.class` 里含 `MultiblockConfigHook`。

## 外部构建的固定参数（内存吃紧的机器必须照用）

```
<检出目录>\gradlew.bat <tasks> --console=plain --no-daemon ^
  "-Dorg.gradle.java.installations.paths=<JDK 17 根目录>" ^
  "-Dorg.gradle.jvmargs=-Xmx512m" "-Dorg.gradle.workers.max=1"
```

- 外部检出要求 **JDK 17**（toolchain 写死 17）；`JAVA_HOME` 指同一个 JDK 17 根目录
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

## 它怎么被本仓库消费

- 常量与任务都在 `scripts/patches.gradle`（`jarjarGtmJar` / `jarjarGtmName` 等 ext 属性）；
  `build.gradle` 的 JarJar 段**不再自己写一份**。
- `collectJarjarDeps`（`Copy`）`dependsOn buildPatchedGtm`，
  把 jar 改名成固定文件名收进 `build/jarjar/`；源在**执行期**才解析 ——
  否则会把“本次刚构建出来的补丁 jar”误判成缺失。
- `tasks.named('jar')` 把它塞进 `META-INF/jarjar/`，manifest 里写
  `ContainedDeps: gtceu-1.20.1-7.5.3.jar`。
- 升级内嵌 mod 版本时要**同时**改：`scripts/patches.gradle` 的版本常量（+ 外部检出路径）、
  `gradle.properties` 的 `gtm_version`。

## 历史：手工做法（现在只在排查时用）

1. 把 `<mod>-<version>/**` 里除 `NOTES.md` 外的文件按原路径覆盖到该 mod 的源码树。
2. 在该仓库跑上面的外部构建命令，产物 `build/libs/<name>.jar`。
3. GTM 手工把产物（jar + pom + module）从 m2 那个坐标拷到 `patchJar/maven/.../7.5.3/`
   （自动流程用的是 `publishToMavenLocal` 之后由 `buildPatchedGtm` 连带拷过来）。
