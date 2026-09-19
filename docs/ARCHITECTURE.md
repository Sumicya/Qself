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

app / core 的第三方依赖只剩两个 *compileOnly* 的框架 API stub
（`de.robv.android.xposed:api`、`libs/libxposed/api`）—— 运行时的 UI 与逻辑
零第三方库。

## 启动流程

```
LSPosed 10.x ──(META-INF/xposed/*)──► QselfModule10x ──► Qself.boot(param, features, HookEngines.forModernFramework)
经典 Xposed ──(assets/xposed_init, 默认不声明)──► QselfModule ──┘（ClassicHookEngine）
设置 UI（模块自身进程）──────────────────────────────► Qself.bootUi(context, hostPackage, settings)
```

### 现代 API 契约（`tools/moduleprop`）

模块能被框架加载，靠的是 APK 里的三个文件（全部由 `:tools:moduleprop` 这个
无代码 Android library 打进 `META-INF/xposed/`）：

| 文件 | 内容 | 作用 |
|---|---|---|
| `module.prop` | `minApiVersion=101` `targetApiVersion=101` `staticScope=true` | 声明这是一个 API 101 模块且作用域静态 |
| `java_init.list` | `sumicya.qself.QselfModule10x` | 入口类（**缺了它模块会安装但永不加载**） |
| `scope.list` | `com.tencent.mobileqq` / `com.tencent.tim` | 注入范围，用户无需手动勾选 |

对应地，清单里只有 `android:label`（模块名）与 `android:description`
（模块说明），**不再有** `xposedmodule` / `xposedminversion` / `xposedscope`
这些旧元数据。CI 会在打包后自检这些文件与 dex 入口类（见 `docs/ci/ci.yml`）。

### 切回经典 API（可选）

想同时支持 Xposed / EdXposed / LSPosed 1.x 时，追加两处声明即可，代码无需改动：

1. `app/src/main/assets/xposed_init` 写入 `sumicya.qself.QselfModule`；
2. 清单里补回 `xposedmodule=true` / `xposedminversion=93` / `xposedscope` 数组。

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
| LSPosed 10.x | ✅ 已可用：`LibXposedHookEngine`（`hook()` → `HookBuilder` → `Chain`）真正安装钩子 |
| Xposed / EdXposed / LSPosed 1.x | `ClassicHookEngine` 已实现；`QselfModule` 入口默认不声明（见上文两行声明） |
| Frida | v1 移除 loader，见 `NATIVE-LOADING.md` 路线图 |
| 纯 native 注入 | 路线图最后一站 |

## 原生引擎（原生化 ①③）

`native/` 打包 `libqself_hook.so`，两套引擎共用同一个 `.so`：

**A. Dobby（inline hook）** — 任意函数地址的原生钩子 + 自检：
`HookNative.selfTestResult` 用 DobbyHook 钩自己的 C 函数 → 验证替换体与
trampoline 都工作 → `DobbyDestroy` 还原 → 再验证恢复原状；0 表示端到端通过。

**B. LSPlant（ART Java 方法 hook，阶段 2 已完成）** — 不需要任何框架 API
即可 hook Java 方法/构造器：

```
features ──► HookEngine（core 抽象）
                 ├── NativeHookEngine ──► LSPlant ──► ART
                 │        └── Dobby（inline hooker）
                 │        └── art/art_symbols（libart.so 符号解析器）
                 └── LibXposedHookEngine（框架回退）
```

- `art/art_symbols.{h,cpp}`：`dl_iterate_phdr` 找到 libart.so → `mmap` 其文件
  → 解析 ELF（arm64 用 ELF64、armv7 用 ELF32）→ 同时索引 **.dynsym 与
  .symtab**（ART 内部符号不经动态链接器导出，`dlsym` 只能兜底）。所有读取都
  经过边界检查，索引只在首次使用时构建一次，键直接指向映射内容故不需要拷贝。
  状态串（`ok: N symbols (dynsym …, symtab …)`）显示在设置页诊断行。
- `art/lsplant_bridge.{h,cpp}`：`lsplant::Init` 的 `InitInfo` 用
  `DobbyHook`/`DobbyDestroy` 作为 inline hooker 对，符号解析器即上面那个；
  `LsplantInit` 只执行一次，失败不致命。
- `NativeHookEngine`（app）：把 LSPlant 适配到 `HookEngine`。LSPlant 会把
  接收者作为 `args[0]` 传给回调（静态方法没有），因此回调里先把它拆出来，
  特性的 handler 只看到参数；参数被 in-place 修改时用新数组调用 backup；
  `InvocationTargetException` 会被解包，宿主看到的是原始异常。构造器同样
  支持（backup 按 `Method` 调用在正在初始化的实例上）。
- **引擎选择**：`HookEngines` 优先用 native，LSPlant 起不来（ABI 不支持、
  libart 符号缺失、`lsplant::Init` 失败）时回退框架引擎——降级而不是罢工。
- 设置页「引擎自检」里 `java=` 是 `NativeJavaSelfTest` 的结果：它用 LSPlant
  hook 一个模块自有的探测类，检查替换体生效、unhook 后原方法恢复。

```
诊断行示例
Native 引擎：<dobby 版本> / ready / libart ok: 58xxx symbols (dynsym …, symtab …)
引擎自检：dobby=0 java=0 (ok)
```

## UI（原生化 ② + 现代化）

设置界面**只用 Android framework**：没有 AndroidX，没有 Material，也
没有 WebView/Compose/第三方 UI 库。

- 单 Activity（`MainActivity : android.app.Activity`）+ `ListView` +
  `BaseAdapter`（`FeatureAdapter`）；ActionBar 与溢出菜单来自主题。
- 主题 `Theme.Qself` = `Theme.DeviceDefault.Light.DarkActionBar`
  （`values-night/` 换 `Theme.DeviceDefault`）。framework 的 DeviceDefault 在
  Android 12+ 本身就指向系统调色板（`system_accent*`），所以动态取色是免费的，
  不需要 Material 的 `DynamicColors`。
- 行：标题 + beta 标签（`TextView`）+ 摘要 + `android.widget.Switch`；
  分区 = `FeatureCategory` 的一行 `TextView` 表头。
- 诊断块：版本 / 宿主 / 共享配置状态 / native 引擎与自检结果；点一下复制日志。
- 关于与日志均为 `android.app.AlertDialog`；提示用 `Toast`。
- 依赖账本：`gradle/libs.versions.toml` 里唯一剩下的 AndroidX 条目是
  `androidx.annotation`，且只有 vendored 的 libxposed API stub（compileOnly）
  用它 —— **app 的运行时类路径上没有 AndroidX/Material**
  （`docs/ci/verify-apk.sh` 会在 dex 里硬校验这一点）。

## 许可（自由化 ③）

- 代码：**GPL-3.0-or-later**（`LICENSE.md` 全文）。
- 保留的 submodule 许可均与 GPL-3.0 兼容：Dobby（MIT）、LSPlant（GPL-3.0）。
- 旧 EULA 已移除；`app/src/main/assets/eula.md` 为许可与隐私说明。
