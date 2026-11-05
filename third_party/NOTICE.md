# 底栏移植来源与许可

- KernelSU，固定提交 `3186a2fe2107f7170e06dab7aa157eb10ae7759f`：
  https://github.com/tiann/KernelSU/tree/3186a2fe2107f7170e06dab7aa157eb10ae7759f/manager/app/src/main/java/me/weishu/kernelsu/ui/component
  `FloatingBottomBar.kt`、`miuix/animation/DampedDragAnimation.kt` 和 `InteractiveHighlight.kt` 的布局参数、手势和动画逻辑移植至 `KernelBar.kt`（由 Compose 改为 Android View/Choreographer，属于修改版本）。KernelSU 仓库 GPL-3.0，与本仓同许可；原文件标明来自 Apache-2.0 项目的部分继续保留对应许可。
- Kyant0/AndroidLiquidGlass，Apache-2.0：https://github.com/Kyant0/AndroidLiquidGlass 。本轮额外参考固定提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6` 的 `LiquidBottomTabs.kt`（普通/强调色层、前景不重复叠 surface）与 `LiquidButton.kt`（2dp 模糊），仍为无依赖 View 修改实现，不是直接使用 Compose 组件。
  KernelSU `liquid/Lens.kt`、`Vibrancy.kt` 明示从该项目移植；本仓保留原始圆角矩形 SDF、折射、色散着色器，以 Android RuntimeShader 代替 Compose 包装。
- compose-miuix-ui/miuix，`v0.9.4`，Apache-2.0：
  https://github.com/compose-miuix-ui/miuix/tree/v0.9.4/miuix-blur/src/commonMain/kotlin/top/yukonga/miuix/kmp/blur
  `internal/Shaders.kt` 的 BloomStroke dual-peak 与 ColorControls AGSL 被提取到 `KernelBar.kt`；保留着色器公式；按用户本轮要求，高光描边的 BloomStroke 已不实例化/不参与绘制，仅留着色器来源快照，删去未选用的 single-peak 路径和 Compose 运行时包装。

Apache-2.0 许可全文见 `LICENSE-Apache-2.0.txt`，GPL-3.0 许可全文见 `LICENSE-GPL-3.0.txt`（与仓库根目录 LICENSE 相同）。本目录通过 assets 随 APK 分发。
无上游二进制依赖、无 Compose/Miuix 运行库、无联网运行能力。此说明不表示 QQ 与 Compose 的渲染结果已经逐像素验收。
