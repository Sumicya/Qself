# Qself

**自由 · 简单 · 现代 · 原生** —— 给 NT QQ 用的 LSPosed 模块，从零重写，GPL-3.0。

- **现代**：只用 libxposed **API 102**（LSPosed 2.x），带**热重载**：
  - 设置 App 里拨开关 → 通过 LSPosed 远程配置推到正在运行的 QQ，钩子当场装上/卸下，不用重启 QQ；
  - 更新模块 APK → `autoHotReload`，旧代自己拆干净，新代接管；设置页也有「热重载 QQ 里的 Qself」按钮。
- **原生**：QQ 里不塞任何 UI 库。玻璃底栏就是 QQ 自己的底栏（图标、未读、点击全是 QQ 原生的），
  背景用系统 `RenderNode` + `RenderEffect` 实时模糊。设置页是 Jetpack Compose Material 3（动态取色），只跑在模块自己的进程里。
- **简单**：一个 Gradle 模块，十几个 Kotlin 文件；没有 native 代码、没有 DexKit、没有 submodule。
  功能只挂稳定的类名（NT kernel JNI 结构体、公开 SDK 入口、未混淆的 QQ 类），挂不上的开关自己失效，不影响别的。
- **自由**：GPL-3.0-or-later，全部新写。不联网、不上传、不写外部存储。

## 功能

| 分组 | 开关 |
|---|---|
| 外观 · TG 化 | 悬浮玻璃底栏 · 底栏去掉「频道」 · 底栏去掉「动态」 · 统一气泡 · 统一字体 · 去头像挂件 |
| 聊天 | 防撤回 · 转发多选 · 屏蔽轻互动 · 屏蔽表情雨 |
| 净化 · 自由化 | 系统 WebView（禁 X5） · 屏蔽统计上报 · 屏蔽崩溃上报 |

## 用法

1. 需要 Android 12+、支持 libxposed API 102 的 LSPosed。
2. 安装 APK（`sumicya.qself`），在 LSPosed 里启用；作用域是静态声明的 QQ。
3. 打开 Qself（桌面图标，或 LSPosed 里的模块设置）拨开关。第一次启用后重启一次 QQ，之后改开关都即时生效。

目标机型：QQ 9.2.10 / ColorOS 16 / LSPosed 2.2.0。

## 构建

```sh
./gradlew :app:assembleDebug
```

需要 JDK 21 和 Android SDK platform 36。推荐的 CI 在 `docs/ci-build.yml`，拷到 `.github/workflows/` 即可（之后可删掉旧工作流和 `libs/` 占位目录）。

## 源码结构

```
app/src/main/java/sumicya/qself/
  Catalog.kt          所有开关（设置页和 QQ 里共用）
  hook/Entry.kt       libxposed 入口 + 热重载
  hook/Runtime.kt     QQ 进程里的状态、远程配置监听、Activity 跟踪
  hook/Feature.kt     开关基类：装钩子 / 卸钩子
  hook/Features.kt    除底栏以外的功能
  hook/HomeBar.kt     玻璃底栏 + 隐藏 Tab
  hook/Proto.kt       识别撤回推送用的极简 protobuf
  ui/                 Compose Material 3 设置页
```
