# 验证记录

本仓库的构建验证全部走 GitHub Actions（本机没有 Android SDK/JDK 环境）。
CI 的日志下载在当前开发环境不可用（`results-receiver.actions.githubusercontent.com`
不可达），所以 workflow 把失败信息、工具链报告和 APK 自检结果都写进
**check-run annotations**（workflow 里的 python 片段负责转换），再用 `gh api` 读取。

## 读取某次运行的结果

```bash
# 最新一次运行
gh api "repos/Sumicya/Qself/actions/runs?branch=arena/01a0b583-qself&per_page=1" \
  --jq '.workflow_runs[0] | "\(.id) \(.status) \(.conclusion) \(.head_sha[0:8])"'

# 该运行的构建步骤结论 + 注解（失败原因、APK 自检、工具链报告）
JOB=$(gh api repos/Sumicya/Qself/actions/runs/<RUN_ID>/jobs --jq '.jobs[0].id')
gh api repos/Sumicya/Qself/actions/runs/<RUN_ID>/jobs \
  --jq '.jobs[0].steps[] | "\(.conclusion)\t\(.name)"'
gh api repos/Sumicya/Qself/check-runs/$JOB/annotations --paginate
```

## 里程碑

| 运行 | 提交 | 结果 | 证明了什么 |
|---|---|---|---|
| 35409855538 | `b97d65a` 之前 | ❌ 17s | 版本目录格式错误（`TomlCatalogFileParser`） |
| 35415018363 | `2aa6f67` | ❌ | KSP 依赖被解析成 `KspExtension` |
| 35415110017 | `9ec517e` | ❌ | `native` 是 Java 关键字，不能做包名 |
| 35415220293 | `77ad147` | ❌ | core 的 Kotlin 编译错误（SAM、嵌套类型导入等） |
| 35415408783 | `167474c` | ❌ 2.5min | core+native 编译通过，卡在 app |
| 35415583623 | `b6b2ef0` | ❌ | **Kotlin 全部通过**，`ld.lld: undefined symbol: DobbyImportTableReplace` |
| 35415766091 | `99601b5` | ❌ | native 链接通过、KSP 生成成功；app 剩 14 处编译错误 |
| **35415983117** | `64362d8` | ✅ 3min | **首次打包成功**：`qself-debug.apk` 7.53 MB |
| **35416221194** | `c63c1e5` | ✅ | 现代 API 入口 + `LibXposedHookEngine` 打包成功 |
| **35416420537** | `4fc809a` | ✅ | CI 增加 APK 自检（入口文件 / dex 入口类 / 两个 ABI 的 .so / 不打包框架 stub） |
| **35416632275** | `13371f0` | ✅ | settings 桥重写（su 读取、非阻塞写入、10s 超时）后仍全绿 |
| 35417217679 | `c7cc2df` | ❌ | LSPlant 的 C++23 模块目标被 CMake 拒绝：AGP 自带 Ninja 1.10.2 < 1.11 |
| 35417497954 | `681dddb` | ❌ | Ninja 覆盖生效（LSPlant 132 个编译步骤全部通过），只剩 `duplicate symbol qself::art::Status()` |
| **35417673671** | `439a1ef` | ✅ | **LSPlant 从源码编译、链接进 `libqself_hook.so`，APK 10.3 → 14.5 MB** |
| **35417868352** | `cb1978c` | ✅ | 文档收敛（LSPlant 集成写入 ARCHITECTURE/NATIVE-LOADING/README）后仍全绿 |
| 35418167724 | `549ade7` | ❌ 25s | 配置阶段瞬时故障：KSP 插件 marker 解析失败（`not found in any of the following sources:` 后为空列表）。同一份 `settings.gradle.kts` 在 14 分钟前的运行里正常，属 runner/仓库侧抖动 |

## 已验证 / 未验证

已验证（CI 或静态检查）：

- Gradle 9.7.1 + AGP 9.3.1 + Kotlin 2.4.20 + KSP 2.3.12 全量编译通过（Debug）。
- C++：Dobby 静态链接进 `libqself_hook.so`，arm64-v8a 与 armeabi-v7a 均通过
  NDK 29 + CMake 3.31 编译链接（16KB 页对齐、`-fPIC`）。
- KSP 生成的 `sumicya.qself.gen.QselfFeatures` 被 app 编译消费（8 个特性）。
- 资源引用完整性：Kotlin 里所有 `R.id/R.string/R.layout` 都能在 `res/` 找到
  （脚本比对过的结论，见提交说明）。
- APK 元数据自检（workflow 的 *Verify APK* 步骤，结果以注解回传）：
  `META-INF/xposed/{module.prop,java_init.list,scope.list}`、两个 ABI 的
  `libqself_hook.so`、dex 中的入口类、以及"没有把框架 stub 打进包"。
  注：入口类那一项在旧版检查里是假阴性（只扫了 `classes.dex`），
  `docs/ci/verify-apk.sh` 已修并在合成 APK 上跑通两种结局（绿 / 缺文件退 1）。
- C++：LSPlant 的 C++23 模块目标（`lsplant_static`、`dex_builder_static`）
  与 Dobby 一起在 NDK r29 + CMake 3.31.0 + Ninja 1.11（系统包）下编译链接，
  arm64-v8a 与 armeabi-v7a 均通过。
- 静态检查：Kotlin 括号配平、`R.id` / `R.string` / `R.layout` 与 `res/` 双向比对、
  所有 XML 良构、依赖目录里无残留失效别名（`549ade7` 之前逐项跑过）。
- "纯 framework UI" 的机器证据：`docs/ci/verify-apk.sh` 会数 dex 里的
  `Landroidx/*` 与 `Lcom/google/android/material/*`，要求为 0。脚本已在合成
  APK 上双向彩排（干净包 → `OK no AndroidX/Material classes` 且 exit 0；
  混入 androidx/material/框架 stub → `BUNDLED …` 且 exit 1）。
  **注意**：该脚本属于 `docs/ci/` 暂存区，要跑一次 `bash docs/ci/bootstrap.sh`
  才会在 CI 里生效（当前 live 的 workflow 是用户自己那份自包含版本）。

**未验证（必须在真机确认）**：

- 模块在 LSPosed 10.x 上被识别、注入 QQ、`onPackageReady` 被调用。
- 钩子实际生效（例如"屏蔽更新"是否真的没有弹窗）。
- `HookNative.selfTestResult` 是否为 `0`（Dobby 自检端到端）。
- 设置界面的 `su` 桥在真实 root 环境下的读写。
- 各 QQ 版本上混淆类/方法名命中的比例（`docs/FEATURES.md` 的移植约定里有版本分支）。
- `su` 桥首次授权的交互（Magisk/KernelSU 弹窗）与实际读写结果。
- **native 引擎在真机上的表现**：libart.so 能否被 mmap（SELinux）、符号数是否
  合理、`lsplant::Init` 是否成功、Dobby 能否改写 ART 的代码页；
  设置页「引擎自检」的 `java=` 就是这一串的结论（0 = 端到端通过）。

真机验证步骤见 README「使用方法」；诊断信息（引擎版本、自检结果、共享配置状态）
直接在设置页首行显示，出问题时先看那里和 `QLog`（设置 → 日志）。
