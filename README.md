<div align="center">

# Qself

**自由 · 现代 · 原生 · 简单**

一个 GPL-3.0 的 QQ 增强 Xposed 模块 —— QAuxiliary 的彻底重写。

</div>

---

## 这是什么

Qself 把旧 QAuxiliary 分支的屎山一次铲平：

- **简单化**：submodule 11 → 2，移除 MMKV/ezxhelper/XPopup/EasyAdapter/DexKit 等；
  构建不再有 build-logic 复合工程；配置 = 一个 JSON 文件；运行时**零第三方依赖**
  （唯一剩下的 AndroidX 条目只服务于 compileOnly 的框架 API stub）。
- **现代化**：全 Kotlin（特性 + UI + 运行时），编译期 KSP 特性注册表
  （运行时零元数据反射）；设置界面用 framework 主题
  （`Theme.DeviceDefault`，Android 12+ 自动跟系统取色）。
- **自由化**：代码 **GPL-3.0-or-later**（旧 EULA 作废）；运行时不绑定单一框架
  （LSPosed 10.x 入口 + 经典 API 引擎 + native 引擎，见 `docs/NATIVE-LOADING.md`）。
- **原生化**：纯 Android 原生 UI（`android.app.Activity` + `ListView`，无 AndroidX、
  无 Material、无 WebView）；随包分发 Dobby（inline hook）+ LSPlant（ART Java
  hook）原生引擎，libart.so 符号由自研解析器提供。
  **默认走框架引擎**（稳定优先）；原生引擎用 `use-native` 开关启用
  （见「诊断开关」）—— 它会 patch ART 内部结构，在未经真机验证的 ART/PAC 环境下
  可能把宿主带崩，因此在验证前不作为默认。

## 使用方法

1. root + [LSPosed](https://github.com/LSPosed/LSPosed)（v1 目标环境）。
2. 安装本模块 APK（CI 产出的 debug 包已签名，可直接安装）。
3. LSPosed 中启用模块即可——作用域由 APK 内的 `META-INF/xposed/scope.list`
   静态声明（QQ / TIM），无需手动勾选；启用后重启 QQ。
4. 在 LSPosed 模块列表打开 **Qself** 设置（或从启动器图标进入）。
5. 开关保存后**重启 QQ** 生效（v1 契约）。

支持：

- Android ≥ 8.0（API 26；libxposed 框架本身要求 8.1+）
- QQ（`com.tencent.mobileqq`）；其他宿主（TIM 等）v1.1
- arm64-v8a / armeabi-v7a

## 设置与隐私

- 配置存于宿主 `filesDir/qself/settings.json`；设置界面经 `su` 桥读写
  （目标用户均 root；无 root 时界面回退本地缓存并提示）。
- **不收集、不上传任何数据。**
- 详见 `app/src/main/assets/eula.md`。

## 出问题怎么办

先用设置页**诊断**行看结论（`版本 / lsplant / libart / dobby= / java=`），
再看 QQ 进程的日志：

```bash
# 本模块自己的日志（tag 以 Qself 开头）
su -c 'logcat -d -v threadtime | grep -E "Qself|lsplant|libqself" | tail -200'
# 崩溃日志
su -c 'logcat -b crash -d -v threadtime | tail -200 > /sdcard/qself-crash.txt'
```

启动路径每一层都有日志：`onPackageReady` → `boot trigger engine:` →
`boot hook armed ...` → `native library available:` → `hook engine:` →
`boot: pkg=... engine=...` → `boot complete: N/M features ok`。
**哪一行是最后一行，就说明崩在那一步之后**；`docs/VALIDATION.md` 里有一张判定表。
模块设计成崩不掉宿主：任何一步失败都只让本进程保持 idle 并记日志。

### 诊断开关（root，文件即开关，无需重编译）

```bash
Q=/data/data/com.tencent.mobileqq/files/qself   # TIM 换成 com.tencent.tim
su -c "mkdir -p $Q && touch $Q/safe-mode"   # 只加载、只记日志，不装任何钩子
su -c "touch $Q/no-features"                # 正常启动，但不装任何特性钩子
su -c "touch $Q/use-native"                 # 启用 LSPlant/Dobby 原生引擎（实验）
su -c "rm -f $Q/safe-mode $Q/no-features $Q/use-native"   # 恢复正常
```

| 配置 | 结果 | 结论 |
|---|---|---|
| `safe-mode` | 仍崩 | 与我们的钩子无关（框架注入 / 宿主 / 其他模块 / 环境） |
| `no-features` | 仍崩 | 崩在自举钩子或引擎本身，而不是某个特性 |
| `no-features` | 不崩 | 崩在某个特性（当前默认开启的只有「禁用崩溃日志上报」） |
| `use-native` | 崩 | 崩在原生层（LSPlant/Dobby/ART 兼容性） |
| `use-native` | 不崩 | 原生引擎在你的 ROM 上可用 |

## 开发

```
JDK 21 + Android SDK（platform 37, cmake, ndk）
./gradlew assembleDebug
```

- 打包后自动跑契约自检（`verifyModuleApk`，`assembleDebug` 的 finalizer）：
  必需条目齐全、入口类定义在**任意** dex、dex 里 0 个 AndroidX/Material 类、
  0 个框架 stub 类；违约直接构建失败。
- **NT 版 QQ（9.x）适配**：[`docs/NT-ADAPTATION.md`](docs/NT-ADAPTATION.md)
  （v1 特性全部面向旧版 QQ；NT 上需要按真实类名重写，设置页菜单「导出宿主类名」是取类名的工具）
- 架构说明：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- v1 功能清单与推迟项：[`docs/FEATURES.md`](docs/FEATURES.md)
- 加载方式路线图（native 注入）：[`docs/NATIVE-LOADING.md`](docs/NATIVE-LOADING.md)
- 诊断：设置页首行显示 native 引擎版本、libart 符号数、两项自检结果
- 构建验证记录（含 CI 运行历史与"已验证/未验证"清单）：[`docs/VALIDATION.md`](docs/VALIDATION.md)
- CI：`bash docs/ci/bootstrap.sh`（把 `docs/ci/ci.yml` 同步到 `.github/workflows/`）

添加一个功能 = 一个带 `@QselfFeature` 的 `object`（见 `docs/FEATURES.md` 移植约定），
无需注册表、无需改 UI。

## 许可

[GPL-3.0-or-later](LICENSE.md)。

- 本项目保证开源，欢迎提交 PR，但请不要提交用于非法用途的功能。
- 一切开发旨在学习，请勿用于非法用途。
- 本模块完全免费，无任何收费，请勿二次贩卖。
- 开发团队可能随时停止更新或删除项目。

上游致谢：QAuxiliary / QNotified 社区、LSPosed（LSPlant/Dobby）、
LuckyPray（qq-stub/DexKit 的历史实现知识）。
