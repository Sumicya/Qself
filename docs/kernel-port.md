# KernelSU 底栏与独立设置短胶囊

固定来源：KernelSU [`3186a2fe`](https://github.com/tiann/KernelSU/tree/3186a2fe2107f7170e06dab7aa157eb10ae7759f/manager/app/src/main/java/me/weishu/kernelsu/ui/component)，Miuix 0.9.4 / `39c40f99844227b853f0049a0933b1f3ae6c00ba`。许可、着色器展开哈希见 `third_party/NOTICE.md`、`tools/ksu-shaders.json`。不引入 Compose、Miuix 或其它运行库，QQ 保留真实页签、子控件、点击与无障碍。

## 2026-10-17：根据用户实测修正渲染链

用户反馈数字左漂、玻璃没有完整按上游、设置按钮没有胶囊形态。此前的测试通过不能推翻这些失败证据。

### 数字定位

旧代码先清空 translationX，再把 `getLocationInWindow` 的整数窗口位置与局部宽度相减，结果直接写入局部 translationX；祖先缩放时单位不一致。现在两点均通过完整矩阵变换进入 badge.parent 坐标，再作差增量校正，不先清零；复制 QQ Paint 后显式指定 `Paint.Align.CENTER`，保留原生 Y 布局。追加 DEX 核对确认 QQ 的 initPaint 原本就设置 CENTER，所以该显式设置只是防御，不把它冒称为已证实的左漂根因。QQ onLayout 会更新 mDelegateRect，故先校正位置，再执行原生布局回调更新触摸区域，避免数字移走而命中区域留在旧位置。仍按真实计数展开、纯数字无红底，宽度随数字增长。纯逻辑测试覆盖多种宽度、窗口缩放、镜像和重复定位；不等同于 Android Matrix 实机测试。

### 绘制层对应

| 上游层 | View 移植 |
| --- | --- |
| 原始 backdrop | 录制祖先背景与前序兄弟；以 source→global→host 完整矩阵转换，避免窗口位移再叠加自身 matrix 的重复变换；背景与内容分别处理自身 scroll |
| 64dp 普通 Row | 在主栏 dispatchDraw 合成材质及 QQ 内容；普通内容不参与 1.2 倍缩放，材质单独作 `1 + 16dp/width × progress` 扩张 |
| 56dp 强调色隐藏 Row / tabsBackdrop | 调用真实 TabView 的原生 dispatchDraw 录制内容，跳过 TabView 自己的旧背景与水波纹；保留其子控件 drawChild、alpha、矩阵、动画及 display list；副本按每个页签中心作 `1 + .2 × progress` 缩放，再以强调色着色 |
| combinedBackdrop | 原始场景 + 独立 56dp 模糊/折射/40% surface + 强调色内容副本 |
| 前景指示器 | 在普通内容之后绘制，从 combinedBackdrop 取样；保留按压折射、色散、罩色、BloomStroke 与内阴影 |

不再给 host、strip 或 tab 写入材质动画用的 scale/translation；真实布局与命中区域不随按压改变。4dp 面板回弹在 Canvas 上施加，背景录制补偿该平移。指示器使用浮点 Canvas 位移，避免 RenderNode 整数位置截断；内阴影改为模糊外侧反向遮罩再裁切，不再用向下偏移的单侧残片代替。

固定尺寸/效果：64dp 栏、4dp 内边距、最小 76dp 页签、56dp 指示器、78/56 按压比例；饱和度 1.5、4dp 模糊、24dp 基础折射；指示器 10/14dp×按压进度、深度效果与 0.5 色散；1dp/2dp 双峰高光、3° 重力量化、8dp×进度内阴影。五份 AGSL 保持固定源码哈希。

弹簧保留上游阻尼/刚度与连续位置/速度，不再截掉超过 64ms 的帧间隔。速度形变改为跟踪动画位置的 VelocityTracker 结果，不再对弹簧解析导数另作一遍滤波。点击释放取消原生待发点击，再调用真实页签 performClick 一次；横拖也只在释放时点击。释放后不再由 clear() 把目标重置为尚未更新的旧选择；仅在原生选择实际改变时同步。徽标原生拖动、多指取消、纵向转交背后原生内容页的分支保留。

### 独立设置短胶囊

用户选择独立短胶囊，后续可根据实际效果改回圆形。可见尺寸 76×52dp，四周另留 8dp 绘制余量，物理布局不因按压改变。使用同一个 KernelBar 材质/按压弹簧（standalone 模式不画页签指示器），不再走旧 roundBar 渐变早退路径，也不叠加另一套水波纹或 0.96 倍 View 缩放。三线图标保持正方形比例并居中，不随胶囊宽度横向拉伸。空间不足仍置于右上方；菜单面板本身不扩张、不改材质。

## 验证记录与未验收部分

2026-10-17 09:36（Asia/Shanghai），针对本轮工作树；实现始于 Qself `cebf9ad78fae0df6dc93944fd9aebe604703b64d`，提交后 CI 结果另以对应运行记录为准：

- `bash ci/check.sh`：Kotlin 类型检查、43 项纯逻辑测试通过。
- `python3 -m unittest discover -s tools -p 'test_*.py'`：35 项工具/源码契约通过。
- 清理脚本原有 10 项测试通过；本轮未修改清理或工作流。
- 只读 QQ APK SHA-256 `34bdea66e738062c3f762f7da98132addc7170394605a4f5ca1944bee1280c84`：37 个 DEX、223 条符号匹配；`tools/ui_paths.py` 静态调用关系通过。QQTabLayout 继承 Material TabLayout，没有自行覆盖 dispatchDraw；TabLayout.onDraw 会画 TabView 旧背景，因此不能只在 background 阶段完成新合成。

这些检查不执行 Android GPU 或 LSPosed 触摸分发。CI 的 Skia 138 CPU 编译亦不是设备截图。**尚无 Android 16 / QQ 真机复验，不宣称数字漂移已获实机确认，也不宣称一比一验收完成。**

仍需核实的差异：View 场景录制不等于 Compose LayerBackdrop；图标为 QQ 真实内容而非 KernelSU 资源；强调色使用 Qself 动态色开关，surfaceContainer 使用 Miuix 默认白 / #242424（40%），没有移植 Miuix 全套 Monet/HCT 主题运行时；Android 与 Compose VelocityTracker 的逐帧结果尚未证明一致；外阴影仍由原生 elevation 提供，不是 Compose dropShadow。GPU 层合成、半透明背景、按压峰值/裁切、真实点击与纵滑交接仍待设备验收。软件 Canvas 或内容合成失败时的原生/纯色退化不计为玻璃通过。
