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
  hook）原生引擎 —— 进程里**优先用原生引擎装钩子**，libart.so 符号由自研解析器
  提供，框架只当加载器；引擎不可用时自动回退到框架的 Java 引擎。

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

## 开发

```
JDK 21 + Android SDK（platform 37, cmake, ndk）
./gradlew assembleDebug
```

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
