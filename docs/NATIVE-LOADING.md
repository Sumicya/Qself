# 加载方式路线图（原生化 ③）

当前（v1）支持 LSPosed 10.x 入口；Java 级 hook 引擎在 v1.1 落地。
长期目标是**不依赖任何 hook 框架**的纯 native 加载，按以下阶段推进：

## 阶段 0（v1，已完成）

- `libqself_hook.so`：Dobby 引擎 + 原生自检（见 ARCHITECTURE.md「原生引擎」）。
- `LibXposedHookEngine`：现代 API 的 Java 级引擎，LSPosed 10.x 开箱即用。
- 所有 Java 特性经 `HookEngine` 抽象隔离，替换引擎不动特性代码。
- LSPlant 子模块已登记，但 v1 不参与编译。

## 阶段 1（已完成）：两套 Java 引擎

- `LibXposedHookEngine`：`hook()` → `HookBuilder.setPriority/setExceptionMode`
  → `Chain.proceed(新参数)`，参数改写与异常语义都已实现。
- `NativeHookEngine`（阶段 2 的主体）已完成，见下。
- 在 LSPosed 1.x / 经典 Xposed 环境声明 `QselfModule`（`xposed_init`），
  复用已实现的 `ClassicHookEngine`。

## 阶段 2（引擎已完成）：LSPlant 原生 Java hook

- ✅ libart.so 符号解析器（`.dynsym` + `.symtab`，ELF32/ELF64 双架构）：
  `native/src/main/cpp/art/art_symbols.*`。
- ✅ `lsplant::Init` 接入，Dobby 作为 inline hooker：`art/lsplant_bridge.*`。
- ✅ `NativeHookEngine` + `HookEngines` 引擎选择 + 真机可见的自检
  （`NativeJavaSelfTest`）。构建侧需要 Ninja ≥ 1.11（LSPlant 的 C++23 模块
  目标），见 `native/build.gradle.kts` 的 `qself.ninja.path`。
- 待办：真正的 native 特性（不经过 ART Java 层），例如 libc 层拦截；
  需要 PLT 级拦截时再把 Dobby 的 `builtin-plugin/ImportTableReplace`
  （目前只在 Darwin 编译）接进 Android 源文件列表。

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
