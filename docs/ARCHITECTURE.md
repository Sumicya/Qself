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
```
LSPosed 10.x ──(META-INF/xposed/*)──► QselfModule10x.onPackageReady
                                          │  （此刻 Application 尚未创建）
                                          ▼
                         BootHook：同时 hook 5 个框架触发点
                         callApplicationOnCreate / newApplication ×2 /
                         AppComponentFactory#instantiateApplication /
                         Application#onCreate（+ 首个 Activity 兜底）
                         ├─ 首选：框架引擎（PROTECTIVE —— 我们抛异常也不会
                         │        传到宿主；只是"谁来触发"，不装任何特性钩子）
                         └─ 兜底：自研原生引擎（无框架 API 的环境 / 框架拒绝时）
                                          │  宿主创建 Application 的那一瞬间
                                          ▼
                         Handler(main).post →  Qself.boot(param, features, engine)
                                          ▲  不在建 Application 的钩子回调里干重活
                                          │  （宿主自己的 Application.onCreate 先跑）
经典 Xposed ──(assets/xposed_init, 默认不声明)──► QselfModule ──► Qself.boot（ClassicHookEngine）
设置 UI（模块自身进程）──────────────────────────────────────► Qself.bootUi(context, hostPackage, settings)
```

**为什么要有 BootHook**：libxposed 的 `onPackageReady` 在 *Application 存在之前*
触发（官方文档："ready to create Application"），此时没有任何 Application 可以
boot；而 `AppGlobals` / `ActivityThread.currentApplication()` 这类取 Application 的
老办法既是隐藏 API（Android 9+ 反射会被拦），时机也太早。于是 Qself 在宿主创建
Application 的瞬间把它接过来——等同经典 `handleLoadPackage` 的时机。

**什么时候装钩子：`onPackageLoaded`，不是 `onPackageReady`**。真机日志里两者相差
842 ms，而 `onPackageReady` 到达时宿主 Application 已经创建（`LoadedApk` 的
classloader 是宿主自己请求的，框架只能在那之后回调）。`onPackageLoaded` 的契约
写得很清楚：默认 classloader 就绪、**自定义 AppComponentFactory 尚未实例化**——
这正是启动触发需要的时刻，所以 boot 在它里面装钩子（`onPackageReady` 再调用一次
是幂等的空操作）。

**为什么是 5 个触发点而不是 1 个**：只用 `Instrumentation#callApplicationOnCreate`
时，真机日志是"三次进程启动都停在 `boot hook armed on …`、之后一行都没有"——钩子
装上但方法再没进我们的 handler（钩子装得太晚，或宿主有自己覆写的 Instrumentation）。现在同时装
`callApplicationOnCreate`、两个 `newApplication` 重载（after，只读 result）、
`AppComponentFactory#instantiateApplication`（Android 10+ 真正创建 Application 的
地方，用 `onPackageReady` 交过来的 factory 实例）、`Application#onCreate`，外加
`Instrumentation#callActivityOnCreate` 作为**最后兜底**（第一个 Activity 一定带着
Application）。日志会点名是哪一条生效（`boot trigger fired: …`）；全部落空时兜底
那一层会写 `late: first Activity#onCreate`，此时启动期特性已太晚，但设置入口和懒
钩子照常工作。`boot probe: first activity <类名>` 用于证明钩子引擎本身是活的。

**三条安全规则**（真机闪退后定下的）：

1. **触发钩子优先用框架引擎**：它跑在每个 app 启动都依赖的核心框架方法上，
   框架的 `ExceptionMode.PROTECTIVE` 保证我们出错只写日志、不会把宿主带崩。
   原生引擎仍是所有*特性钩子*的默认实现，也仍是触发钩子的兜底
   （未来无框架加载时就是它）。钩子只挂 before-handler，原方法照常执行。
2. **重活不在钩子回调里干**：拿到的 Application 立刻 `Handler(main).post`，
   等 `handleBindApplication` 走完（宿主 `Application.onCreate` 已执行）再
   `Qself.boot`——避免在核心框架方法的替换体里再去装别的钩子。
3. **每一层都不许把异常丢给宿主**：钩子回调、`Qself.boot`、每个特性的 init
   各自 try/catch；boot 失败就本进程保持 idle 并记日志，QQ 照常运行。

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

除 Host 解析层外，模块里只剩两处反射，都在**启动路径且只跑一次**：
`BootHook` 解析那几个公共方法（`Instrumentation#callApplicationOnCreate` /
`#newApplication` / `#callActivityOnCreate`、`Application#onCreate`）；
`QselfModule10x.initialApplication()` 是取 Application 的兜底（隐藏 API，可能失败，
失败即忽略）。热路径（hook 回调、开关读取、UI）零反射。

## 运行时类名发现（HostDex）

QQ 把类改名/混淆时，写死的 FQCN 候选表就失效（`com.tencent.mobileqq.setting.main.b`
就是 9.2.30+ 的设置 provider）。上游的解法是 DexKit：**去 dex 里找类，而不是相信
名字**。Qself 用已有的 `DexReader`（现在在 core）做同一件事：

- `HostDex.find(context, fragments, validate)`：宿主进程内直接读宿主 APK
  （`applicationInfo.sourceDir`，**不需要 root/su**），按类名片段过滤，
  在**类内部方法签名层面**逐类校验（单次遍历，不是每类重扫 class_defs）；
- 校验由调用方给：例如设置 provider 的判据是"有 `Collection (Context)` 方法"——
  这个形状扛得住改名；
- 命中写进宿主 `files/qself/hostdex.txt` 缓存，下个版本/下次启动先试缓存；
- 扫描很重（几百 MB dex），所以只在"廉价候选全 miss"时、后台线程里跑一次。

第一个使用者是 QQ 内设置入口（`ui.inqq_entry`）。

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
- **引擎选择**：`HookEngines` **默认用框架引擎**；`:app` 的 `use-native` 文件开关
  才把原生引擎提到前面（此时 LSPlant 起不来会回退框架引擎——降级而不是罢工）。
  这一默认值是 2026-09-19 真机崩溃后改的：LSPlant 会 patch ART 内部结构，
  在未经真机验证的 ART 版本（该机为 Android 16，且开启了 PAC）上，出问题的代价是
  **宿主闪退**，而模块"能用"比"全原生"重要。验证通过后再把默认翻回去。
- 设置页「引擎自检」里 `java=` 是 `NativeJavaSelfTest` 的结果：它用 LSPlant
  hook 一个模块自有的探测类，检查替换体生效、unhook 后原方法恢复。
- **自举**：模块的第一个正式钩子就是那一组 Application 触发点
  （见「启动流程」）——只读不替换，任何一个先到都能把 Application 交出来。

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

## 构建期契约自检（app/build.gradle.kts 的 verifyModuleApk）

`assembleDebug` 的 finalizer 会在打包后直接读 APK，违约即让构建失败：

| 检查 | 为什么编译器抓不到 |
|---|---|
| `META-INF/xposed/{module.prop,java_init.list,scope.list}` 与两个 ABI 的 `libqself_hook.so` | 路径写错 = "装上但永不加载" |
| 入口类定义在**任意** dex 里（用 SDK 的 `dexdump` 读 class_defs） | debug 包有 ~19 个 dex，只扫 `classes.dex` 会假阴性 |
| dex 里 0 个 `Landroidx/*` / `Lcom/google/android/material/*` 类 | "纯 framework UI" 的可机器校验部分 |
| dex 里 0 个框架 stub 类 | compileOnly stub 若被打包会遮蔽框架实现 |

## 许可（自由化 ③）

- 代码：**GPL-3.0-or-later**（`LICENSE.md` 全文）。
- 保留的 submodule 许可均与 GPL-3.0 兼容：Dobby（MIT）、LSPlant（GPL-3.0）。
- 旧 EULA 已移除；`app/src/main/assets/eula.md` 为许可与隐私说明。
