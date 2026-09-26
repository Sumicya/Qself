# Qself

**自由 · 简单 · 现代 · 原生** —— 给 NT QQ 用的 LSPosed 模块，GPL-3.0。

- **现代**：只用 libxposed **API 102**（LSPosed 2.x），minSdk 36。钩子只在包加载阶段装
  （框架之后会封掉这一代），所以设置页拨开关 = 写远程配置 + 让 QQ 里的 Qself 热重载一次：
  旧的一代自己拆干净，新的一代按新配置装，不用重启 QQ；换新 APK 也走 `autoHotReload`。
- **原生**：QQ 里不塞任何 UI 库。玻璃底栏就是 QQ 自己的底栏（图标、未读、点击全是 QQ 原生的），
  背景是一颗 AGSL 圆角矩形透镜（SDF 折射 + 一点色散）+ 系统 `RenderNode`/`RenderEffect` 模糊。
  设置页是 Jetpack Compose Material 3（动态取色），只跑在模块自己的进程里。
- **简单**：一个 Gradle 模块，十二个 Kotlin 文件，没有 native、没有 DexKit、没有 submodule。
  只挂稳定的类名（NT kernel JNI 结构体、公开 SDK 入口、未混淆的 QQ 类），挂不上的开关自己失效，不连累别的。
- **自由**：不联网、不上传、不写外部存储（界面结构导出手动开，写进 QQ 自己的 `files/qself/`）。

## 功能

| 分组 | 开关 |
|---|---|
| 外观 | 悬浮玻璃底栏 · 底栏去掉「频道」 · 底栏去掉「动态」 · 首页顶栏玻璃 · 聊天玻璃 · 输入栏 TG 化 · 聊天标题栏精简 · 侧栏精简 · 统一气泡 · 统一字体 · 昵称只留名字 · 去头像挂件 |
| 聊天 | 防撤回 · 转发多选 · 「+」面板精简 · 屏蔽轻互动 · 屏蔽表情雨 |
| 净化 | 系统 WebView（禁 X5） · 屏蔽统计上报 · 屏蔽崩溃上报 |
| 调试 | 界面结构导出（默认关） |

## 用法

1. Android 16+，LSPosed 2.x（libxposed API 102）。
2. 装 APK（`sumicya.qself`），在 LSPosed 里启用，作用域是静态声明的 QQ。
3. 打开 Qself 拨开关。第一次启用后重启一次 QQ；之后纯界面的开关当场生效，要装钩子的开关由设置页顺手给 QQ 发一次热重载（半秒内合并成一次），也不用重启 QQ。
4. 出问题：设置页「生成报告」→ 复制贴出来（含每个开关的状态、失败原因和钩子数）。

目标：QQ 9.2.10 / Android 16 / LSPosed 2.2.0。

## 构建

本机要 JDK 21、Android SDK platform 37：

```sh
./gradlew :app:assembleDebug
```

手机上没 SDK，就推上去让 GitHub Actions 出包：`ci/build.yml` 是 workflow，复制到
`.github/workflows/build.yml` 一次即可（之后每次 push 都会构建，APK 在 Actions 的 Artifacts 里）。

## 源码

```
app/src/main/java/sumicya/qself/
  Catalog.kt          开关表（设置页和 QQ 里的代码都读这一份）
  hook/Entry.kt       libxposed 入口 + 热重载
  hook/Core.kt        QQ 进程里的状态、远程 pref、前台 Activity、自检报告
  hook/Switch.kt      开关基类：装钩子 / 卸钩子 / 反射帮手
  hook/Screen.kt      扫描器 + Rule 基类 + 界面结构导出
  hook/Bar.kt         底栏：浮成胶囊、藏页签（玻璃借 QQ 自己那层模糊）
  hook/Input.kt       输入栏 TG 化（QQ 的按钮原地镜像，点击穿透回 QQ）
  hook/Trim.kt        聊天标题栏与侧栏精简（认错了把「见过什么」写进报告）
  hook/Msg.kt         VAS 会员结构、防撤回、转发页、「+」面板、昵称、轻互动、表情雨
  hook/Quiet.kt       禁 X5、屏蔽统计上报、屏蔽崩溃上报
  hook/Proto.kt       认撤回推送用的极简 protobuf
  ui/                 Compose Material 3 设置页
tools/
  symbols.txt         依赖的全部名字（类 / 方法 / 字段 / 文案）
  dexcheck.py         拿真 QQ dex 逐条核 + 源码 lint
```

## 改名字之前先看这里

反射的名字一律写完整字面量，不许拼字符串 —— 拼了 `tools/dexcheck.py` 就核不了，lint 直接报错。
加功能 = 往 `tools/symbols.txt` 里加一行，不是往代码里塞字符串。名字对不上就让开关失败并写进
报告，不许留静默失效的钩子。

## 名字对不对，跑一条命令就知道

`tools/symbols.txt` 是模块依赖的全部类 / 方法 / 字段 / 文案，`tools/dexcheck.py` 拿真 QQ 的 dex
逐条核，顺带检查源码里没有漏登记的名字。只用标准库，Termux 也能跑：

```sh
pkg install python                        # 只装这一次
cat qq.a? > qq.apk                        # qqapk 仓库里的分包先拼起来
python3 tools/dexcheck.py --apk qq.apk --lint app/src/main/java
# 128 条符号，0 条对不上 -> 退出码 0
```

红了就是 QQ 换了名字。要么按它打印出来的真签名改代码，要么把这条功能删掉。
