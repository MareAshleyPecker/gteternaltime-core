# patchJar/ —— 仓内 vendored 的补丁 jar

这个目录里装的是**打过补丁、随包分发**的两份模组 jar。它们不是官方原件，克隆仓库即有，
所以 `gradlew classes / build / jar` 都不需要外部源码检出、不需要 mavenLocal、也不需要联网下载 GTM。

## 目录布局

```
patchJar/
  README.md                                           本文件
  gtmthings-1.6.0-forge.jar                           补丁 GTMThings（flatDir 依赖，文件名不能改）
  maven/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/
      gtceu-1.20.1-7.5.3.jar                          补丁 GTM（唯一一份，见下）
      gtceu-1.20.1-7.5.3.pom
      gtceu-1.20.1-7.5.3.module
```

补丁 GTM **只存这一份**（maven 布局里那份），`patchJar/` 下面没有平铺副本。

## 为什么补丁 GTM 按 maven 布局放

补丁 GTM 有两个消费方，maven 布局同时满足它们：

| 消费方 | 取用方式 | 位置 |
| --- | --- | --- |
| dev 编译期依赖 `com.gregtechceu.gtceu:gtceu-1.20.1:7.5.3` | `scripts/repositories.gradle` 里名为 `gtetcore patchJar` 的 file-maven 仓库，**排在 `mavenLocal()` 之前** | `patchJar/maven/` |
| 打包内嵌 `META-INF/jarjar/gtceu-1.20.1-7.5.3.jar` | `scripts/patches.gradle` 的 `ext.jarjarGtmJar` → `build.gradle` 的 JarJar 段 | `patchJar/maven/.../gtceu-1.20.1-7.5.3.jar` |

必须排在 `mavenLocal()` 之前：否则别人机器上 m2 里若已有一份同版本（官方原件，或别的构建留下的），
dev 编译期会拿到那一份 —— **能编译通过，但运行期行为不是补丁版**。补丁 GTM 是用反射反向调用
`rain.gtetcore.gtet.util.MultiblockConfigHook` 实现的，本仓库源码与 mixin 不引用任何补丁独有 API，
所以「用官方 GTM」不会编译失败，只表现为异步结构检测的配置桥接不生效 —— 这种偏差很难在编译期发现，
因此这里用「仓内仓库优先」把它堵死。

`content { includeGroup 'com.gregtechceu.gtceu' }` 把该仓库限制在 GTM 组：file 仓库对每个依赖
都会做一次未命中探测，放开会拖慢解析。

`mavenLocal()` 保留作兜底（外部检出版 rebuild 时 `publishToMavenLocal` 的产物仍可被解析）。

## GTMThings 为什么还留在平铺位置

`scripts/dependencies.gradle` 用的是 flatDir 坐标 `fg.deobf("jarjar:gtmthings-1.6.0:forge")`，
flatDir **忽略 group、只按 `<name>-<version>.jar` 匹配文件名**，所以该 jar 必须待在
`flatDir { dir "patchJar" }` 指向的这一层，且文件名必须正好是 `gtmthings-1.6.0-forge.jar`。
不要为了"整齐"把它挪进 maven 子目录。

## 没有 vendored 的文件

- **`gtceu-1.20.1-7.5.3-sources.jar`（约 10.8 MB）没有放进仓库。**
  `.module` 里的 `sourcesElements` variant 指向它，因此 IDE 里点进 GTM 类想看源码时**会解析不到**
  （表现是"Download sources"失败或只能看反编译视图）。解决办法二选一：
  1. 从本机 mavenLocal 仓库（`<m2 本地仓库>/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/`）
     或 GTCEu Maven 单独下这份 sources jar；
  2. 把它拷进 `maven/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/` 同目录后再 `git add`。
     注意：补丁 GTM 的 sources 由外部检出的构建产出，与官方 `-sources.jar` 不完全同源，
     只影响"看源码"，不影响编译与运行。
- `gtceu-1.20.1-7.5.3-slim.jar`（约 14.3 MB）不需要：它**没有被 `.module` 的任何 variant 引用**，
  Gradle 解析时不会去要它。

## sha256（校验/dev 自检用）

| 文件 | 大小 (B) | sha256 |
| --- | --- | --- |
| `maven/.../gtceu-1.20.1-7.5.3.jar` | 18216233 | `d29d038f5f7f13b64b7326b954573adcf873aa48da682d17bc26790e93e2cef0` |
| `gtmthings-1.6.0-forge.jar` | 930790 | `afdc2e7e07d3e6ab1a6304b631aec0b109c314131c06e13dac10eee70768b46c` |

对照：官方未打补丁的 GTM 7.5.3 是 18209988 B —— 大小相近，所以**不要靠体积判断是不是补丁版**，
判据是 jar 内 `com/gregtechceu/gtceu/api/machine/multiblock/MultiblockControllerMachine.class`
里含 `MultiblockConfigHook` 字符串（`gradlew verifyPatchedJarjar` 做的就是这件事）。

## 怎么重建

两份 jar 都由 `scripts/patches.gradle` 从**外部源码检出**构建（本仓库内绝不编译它们的补丁源码），
产物已在则走快路径跳过。外部检出的路径与构建 JDK 写死在该文件顶部的三个属性：

| 属性 | 含义 |
| --- | --- |
| `gtmRepo` | GTM 补丁源码检出目录 |
| `gtmtRepo` | GTMThings 补丁源码检出目录 |
| `patchJdk` | 上面两个外部检出构建时用的 JDK 17 |

```powershell
# 缺产物时自动补建；已有产物想强制重建加 -PgtetPatchBuild=true
gradlew buildPatchedGtmThings buildPatchedGtm

# 只校验：内嵌的两份到底是不是补丁版（换机 / CI 自检）
gradlew verifyPatchedJarjar
```

重建 GTM 时：外部检出 `build publishToMavenLocal` → 校验 m2 产物含 `MultiblockConfigHook` →
**jar + pom + module 三个文件一起**拷进 `patchJar/maven/`。校验不过就抛错且**不覆盖**已有产物，
所以这个目录里的东西永远是"上一次通过校验的那份"。

升级版本时要改的地方（`scripts/patches.gradle` 头注释里也列了）：
`gtmVersion`/`gtmtVersion`（该文件）+ `gradle.properties` 的 `gtm_version`
+ `scripts/dependencies.gradle` 的 GTMThings flatDir 坐标。
maven 子目录路径由 `mc_version`/`gtmVersion` 拼出，自动跟着变（旧版本的目录要手工删掉）。
