# 加载方式路线图（原生化 ③）

当前（v1）支持 LSPosed 10.x 入口；Java 级 hook 引擎在 v1.1 落地。
长期目标是**不依赖任何 hook 框架**的纯 native 加载，按以下阶段推进：

## 阶段 0（v1，已完成）

- `libqself_hook.so`：Dobby 引擎 + 原生自检 + PLT 替换表面
  （见 ARCHITECTURE.md「原生引擎」）。
- 所有 Java 特性经 `HookEngine` 抽象隔离，替换引擎不动特性代码。
- LSPlant 子模块已登记，但 v1 不参与编译。

## 阶段 1（v1.1）：LSPosed 10.x Java 引擎

- 实现 `LibXposed10xEngine`（`io.github.libxposed.api` 的
  `XposedModule.hook(Member, priority, HookerClass)` + 静态分发 hooker）。
- 在 LSPosed 1.x / 经典 Xposed 环境声明 `QselfModule`（`xposed_init`），
  复用已实现的 `ClassicHookEngine`。

## 阶段 2：native 级特性 + LSPlant

- 接入 LSPlant：实现 libart.so 符号解析器（需同时支持 .dynsym 与 .symtab），
  用 Dobby 的 `DobbyHook` 作为 `InitInfo.inline_hooker`，初始化后即可 hook ART
  Java 方法 → 这条路径是摆脱 Xposed 框架的关键一步。
- 用 `HookNative.pltReplace` 实现不经过 ART Java 层的 hook
  （如 libc 层拦截、`statfs`/`__system_property_get` 伪装等）。
- 每个 native 特性自带开关，走同一套 `SettingsBridge`。

## 阶段 3：纯 native 注入（摆脱框架）

- 目标：`qself-inject` 工具 + `libqself.so`，root 环境下
  `ptrace`/`zygote` 注入进 QQ 进程，加载自有 hook 引擎。
- Java 层仍由 ART 运行（`System.loadLibrary` + JNI 回调），
  特性代码不感知加载方式。
- 前置：LSPlant 对 QQ 各版本 ART 的兼容矩阵、16KB page 设备适配
  （`-Wl,-z,max-page-size=16384` 已就绪）、SELinux 策略。
- 安全：注入路径默认关闭，需手动执行工具 + 模块内开关双确认。

## 与 root 的关系

- 阶段 0/1：不需要 root（框架环境自带）。
- 阶段 2：建议 root（部分 native 拦截需要映射可执行内存）。
- 阶段 3：需要 root（注入本身）。
