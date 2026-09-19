# Qself 架构（v2 重写）

> 一句话：**一个模块、五个 Gradle 工程、零反射热路径、纯原生 UI、GPL-3.0**。

## 模块布局

```
Qself/
├── app/        # 模块 APK：Xposed 入口 + 设置 UI + 功能（Kotlin）
├── core/       # 运行时：特性引擎 / Host 解析 / 配置桥 / 日志（Kotlin，无 UI 依赖）
├── native/     # C++：Dobby 原生 hook 引擎（v1），JNI 表面
├── tools/ksp/  # KSP 处理器：@QselfFeature → 生成的特性注册表
├── libs/
│   ├── Dobby/            # submodule：native inline hook + PLT（v1 使用）
│   ├── LSPlant/          # submodule：ART Java 方法 hook（v1.1 接入）
│   └── libxposed/api/    # LSPosed 10.x API stub（compileOnly）
└── docs/
```

对比 v1（旧 QAuxiliary 分支）：submodule 从 11 个减到 2 个；移除
MMKV（→ SharedPreferences + JSON 文件）、ezxhelper、XPopup、EasyAdapter、
fmt、libunwindstack、linux-syscall-support、DexKit、qq-stub、libxposed/service；
build-logic 复合构建、develocity、协议插件全部移除。

## 启动流程

```
LSPosed 10x ──(module.prop 发现)──► QselfModule10x ──► Qself.boot(param, features, engine)
经典 Xposed ──(xposed_init, v1 未声明)──► QselfModule ──┘
设置 UI（模块自身进程）─────────────────────► Qself.bootUi(context, hostPackage, settings)
```

`Qself.boot` 顺序：

1. 写入 `Settings`（进程内 SharedPreferences）与 `SettingsBridge`（宿主 `filesDir/qself/settings.json`）。
2. 从共享 JSON 加载特性开关到内存（**每次进程启动只读一次**）。
3. 构建 `Host`（QQ 类解析器，唯一反射点）与 `HookEngine`。
4. 按注册表顺序初始化每个目标进程内的特性；失败只记入 `featureErrors`，不影响其他特性。

## 特性模型

```kotlin
@QselfFeature(id = "misc.anti_update", name = "屏蔽更新", ...)
object AntiUpdate : SwitchFeature() {          // 或 ActionFeature
    override fun initOnce(ctx: FeatureContext): Boolean { ... }
}
```

- **`SwitchFeature`**：带开关。开关经 `SettingsBridge` 持久化；`isEnabled` 是
  惰性计算，UI 与运行时永远一致。
- **`ActionFeature`**：无开关，点击行执行动作（对话框/启动宿主 Activity）。
  动作运行在模块进程，**禁止依赖宿主类**——一律 `Intent.setClassName`。
- KSP 在编译期把所有 `@QselfFeature` 对象收集成
  `sumicya.qself.gen.QselfFeatures.features`（纯对象引用列表）。
  运行时对特性元数据**零反射**。

## 消除反射：Host 解析层

所有 `Class.forName` 集中在 `core/host/Host.kt`：

- `resolve(vararg fqcn)`：候选 FQCN 列表（新版优先），命中/未命中都缓存。
- `resolveSynthetic(base, 1, 2, 4, ...)`：QQ 混淆的 `Foo$N` 变体。
- `method / requireMethod / methods / field / constructor`：一次解析，终身缓存。
- 特性代码只拿 `Class/Method/Field` 引用，之后完全不再反射。

## 跨进程配置（SettingsBridge）

权威配置 = 宿主 `filesDir/qself/settings.json`（单文件 JSON）：

- **宿主进程（运行时）**：直接读写（同 uid）。
- **模块进程（设置 UI）**：跨 uid 不可达 → 走 `su 0` 文件桥
  （目标用户必然 root；su 不可用时回退本地缓存并在 UI 提示）。
- v1 契约：**开关在宿主进程下次启动时生效**（无热加载，热路径零文件 IO）。

## 加载环境（自由化）

| 环境 | v1 状态 |
|---|---|
| LSPosed 10.x | ✅ 入口已实现；Java hook 引擎在 v1.1（当前 NoopEngine：只报诊断，不装钩子，不崩宿主） |
| Xposed / EdXposed / LSPosed 1.x | `ClassicHookEngine` 已实现，`QselfModule` 入口待 manifest 声明（v1.1） |
| Frida | v1 移除 loader，见 `NATIVE-LOADING.md` 路线图 |
| 纯 native 注入 | 路线图最后一站 |

## 原生引擎（原生化 ③）

`native/` 打包 `libqself_hook.so`（v1 = Dobby，静态链接进同一 .so）：

- `HookNative.init()` / `DobbyGetVersion()`：引擎初始化与版本，设置页「诊断」可见。
- `selfTestResult`：用 DobbyHook 钩自己的 C 函数 → 验证替换体与 trampoline 都工作
  → `DobbyDestroy` 还原 → 再验证恢复原状；0 表示端到端通过。
- `pltReplace(image, symbol, addr)`：Dobby 的 import table 替换（等价 PLT hook），
  符号解析刻意留在 Java 侧（Dobby 的 symbol resolver 一旦链接就会到处崩）。
- **LSPlant（ART Java 方法 hook）在 v1.1 接入**：它需要通过 `InitInfo` 注入
  libart.so 符号解析器，属于独立工作量，见 `NATIVE-LOADING.md`。

## UI（原生化 ② + 现代化）

- 单 Activity（`MainActivity`）+ RecyclerView + Material 3（Views，无 Compose、
  无 XPopup/EasyAdapter）。
- API 31+ 动态取色（`DynamicColors`）。
- 行：标题 + beta 标签 + 摘要 + `MaterialSwitch`；分区 = `FeatureCategory`。
- 诊断卡：版本 / 宿主 / 共享配置状态 / native 引擎与自检结果。
- 关于与日志均为 `MaterialAlertDialog`（日志可复制）。

## 许可（自由化 ③）

- 代码：**GPL-3.0-or-later**（`LICENSE.md` 全文）。
- 保留的 submodule 许可均与 GPL-3.0 兼容：Dobby（MIT）、LSPlant（GPL-3.0）。
- 旧 EULA 已移除；`app/src/main/assets/eula.md` 为许可与隐私说明。
