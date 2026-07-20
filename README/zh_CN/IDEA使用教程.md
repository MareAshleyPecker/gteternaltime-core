# IDEA 使用教程

## 下载与安装

- [IntelliJ IDEA 官方下载](https://www.jetbrains.com/idea/download/)
- 社区版（Community Edition）免费，足够 Minecraft 模组开发使用
- [IntelliJ IDEA 官方文档](https://www.jetbrains.com/idea/documentation/)

---

## 基础使用

### 跳转到函数源文件

- **`Ctrl + B`** / **`Ctrl + 鼠标左键`** — 跳转到光标所在类/方法/字段的定义处
- **`Ctrl + Shift + I`** — 弹窗预览定义，不跳转
- **`Alt + F7`** — 查看该方法/类在哪些地方被调用
- **`Ctrl + Alt + ←`** — 跳转后返回原来的位置
- **`Ctrl + Alt + →`** — 返回后再次前进
- **`Ctrl + N`** — 输入类名快速打开
- **`Ctrl + Shift + N`** — 输入文件名快速打开
- **`Ctrl + Alt + Shift + N`** — 搜索方法名/变量名等
- **`Ctrl + F`** — 当前文件内搜索
- **`Ctrl + Shift + F`** — 整个项目中搜索文本

### Git 使用

IDEA 内置 Git 支持，无需命令行即可完成常用操作。

**克隆仓库：**
1. 菜单栏 `File` → `New` → `Project from Version Control...`
2. 输入仓库 URL，选择本地路径，点击 `Clone`

**常用操作：**

- **`Ctrl + K`** — 提交 (Commit)：打开提交窗口，勾选文件并填写提交信息
- **`Ctrl + Shift + K`** — 推送 (Push)：将本地提交推送到远程仓库
- **`Ctrl + T`** — 拉取 (Pull)：从远程仓库拉取最新代码
- **`Ctrl + D`** — 查看差异：在文件列表中查看文件修改内容
- **`Ctrl + Alt + Z`** — 回滚：撤销当前文件的修改

> 底部状态栏左侧有 `Git: 分支名`，点击可切换分支、创建分支、管理分支。

**提交前请注意：**
1. **请先拉取最新代码再提交你的文件**（`Ctrl + T`），避免覆盖他人代码
2. 直接推送到 `main` 分支
   1. 请务必拉取最新代码再提交你的文件并修改完相关代码问题后再提交

### 启动 Minecraft 客户端

1. 打开右侧 Gradle 面板（`View` → `Tool Windows` → `Gradle`）
2. 展开 `Tasks` → `forgegradle runs`
3. 双击 `runClient` 即可启动游戏客户端

> 首次运行会下载 Minecraft 资源文件，可能需要较长时间，请耐心等待。

---

## 相关链接

- [IntelliJ IDEA 快捷键速查表](https://resources.jetbrains.com/storage/products/intellij-idea/docs/IntelliJIDEA_ReferenceCard.pdf)
- [Kotlin 中文文档](https://book.kotlincn.net/text/getting-started.html)