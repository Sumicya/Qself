# KernelSU 底栏移植

用户选定：只替换主页底栏，以上游原效果为准；不引入 Compose/Miuix 依赖。因此采用渲染算法与动画逻辑的 Android View 移植，而非直接运行上游组件。菜单和圆钮不跟随改造，聊天问题不归入本轮完成项。

固定来源及许可见 `third_party/NOTICE.md`。底栏高度 64dp，内边距 4dp，指示器高 56dp；按压指示器比例 78/56，页签内容比例 1.2。底层饱和度 1.5、模糊半径 4dp、折射高度/幅度 24dp；指示器折射高度 10dp×按压进度、幅度 14dp×进度、深度效果和 0.5 色散。BloomStroke 使用原双峰着色器，1dp 高光带、2dp 内扩散、0.75 底层强度，并随重力以 3° 步长转动。

弹簧按原阻尼/刚度实现解析解：位置及按压 1/1000、X 缩放 .6/250、Y 缩放 .7/250、速度 .5/300；横向拖动释放后才触发 QQ 真实页签点击，取消回到原选中项。纵向手势仍按原生内容页转交，不移动菜单。帧回调只在弹簧活动时运行；分离/关闭还原真实 View 的变换并注销传感器。

## 离线工具边界

候选验证工具 skia-python 138.0：上游 https://github.com/kyamagu/skia-python ，BSD-3-Clause；用于在本地 CPU 编译移植的 SkSL/AGSL 字符串，经 PyPI 固定版本安装到工具缓存，不打进 APK、不接入应用、不上传素材。本地已安装，导入因缺少 libEGL.so.1 被阻断，未冒称本地 shader 编译通过；改在两个既有工作流调用 ci/check-shaders.sh，安装系统 EGL/GL 和独立工具环境后实际编译四份着色器。CI 是否通过以运行结果为准。Skia 编译不等于 Android RuntimeShader、QQ 截图或 LSPosed 设备验收。

## 尚不能称为一比一的部分

QQ 原生视图保留，背景录制来自 QQ View 树，不是 Compose 的 LayerBackdrop。真实图标/文字仍由 QQ 绘制，未复制上游 Compose 的双层图标组合背景；底色已采用 Miuix v0.9.4 默认 surfaceContainer（白色 / #242424、40%），但未引入整套 Miuix Monet 主题运行时。以上差异以及手势、阴影和 GPU 结果必须明确记录，不能以参数一致或离线测试通过宣称完全照搬已验收。
