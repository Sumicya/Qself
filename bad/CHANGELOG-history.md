## 2026-10-23 — 移除仅按钮顶/底安全区与「调整顶栏」滑条、消息滑动重绘性能优化、共享轴页面/Tab 切换动画，以及底栏 TabLayout$TabView#w 穿透与等宽锁定

- 彻底移除 AIO 顶/底及二级页仅按钮安全区并移除「调整顶栏」滑条（`Input.kt`、`Looks.kt`、`Knob.kt`、`MessageStyle.kt`）：将 `AIOMsgListGroup` 顶/底 `padding`（`238px` / `206px`）归零并转移至 `AIORecycleView`（`F2()`，`clipToPadding = false`），清零 `EdgeTransparentView` 的 `i`/`m`/`D` 渐隐擦除使 `drawChild` 直通 `super.drawChild`（消除每帧全屏 `saveLayer` 与 `CLEAR` 遮罩），兼容 `AIOTitleVB#A1`/`h1`、`AIOMsgListVB#H3`/`N3`、`AIOShortcutBarVB#d1`/`y1` 与 `BottomBlurVB` 的 `static` 合成回调（`thisObject == null`），挂钩 `TopBlurVB`（`reserve1.blur.f`）、`TabFrameControllerImpl#recoverUselessBackground` 与 `QUISecNavBar` 全路径清零背景，并按指示移除「顶栏偏移」滑条（预览见 [`docs/preview-safe-area-scroll-and-transition.png`](../docs/preview-safe-area-scroll-and-transition.png)）。
- 消息滑动重绘性能优化与共享 X 轴切换动画（`Chat.kt`、`MessageStyle.kt`、`Glass.kt`、`Looks.kt`、`Hook.kt`）：消除 `roundRectDraw` 每次 `draw()` 触发的 `outlineProvider` 重设与 `host.invalidate()` 循环、消除 `NativeInput.onPreDraw()` 每帧全树遍历与 `SendButtonPill.invalidateOutline()`、为 `HeaderPillsDrawable` 引入单次 `sharedScene: RenderNode` 共享采样、通过 `SavedConstraint.lastAppliedKey` + `applyBubblesOnly()` + `NagramBubbleDrawable.update` 消除列表绑定时的重复 `requestLayout()` 风暴、缓存 `Hook.field` / `DateTimeFormatter` / 反射 `Method`，并在 `TabFrameControllerImpl#updateUIAfterTabChange` 与 `AIOMsgListVB#onCreateView` 中实装纯 `RenderThread` 共享 X 轴微缩淡入切换过渡（`tools/symbols.txt` 增至 657 条符号零不匹配，57 项 Kotlin 单元测试与 52 项 Python 契约测试全部通过，待真机复验）。
- 底栏 `TabLayout$TabView#w` 真实子视图穿透、`TabLayout$f#onMeasure` 等宽锁定与 `checkEnableTabAnim` 钩子守护（`Looks.kt`）：查明 `classes4.dex` `TabLayout$TabView#w` 按 `0=f(ImageView)`、`1=e(TextView)`、`2=m(customView: r/i/c07.xml 或 TianshuRedTouch)` 添加子视图导致 `getChildAt(0) as? ViewGroup` 恒为 `null`，且 `TabLayout$f#onMeasure`（`5a038c`）在 `T == 1` 超宽时将所有 Tab 重置为 `WRAP_CONTENT`（使 `Tab 0` 宽 `172px` 而 `Tab 1/2` 宽 `148px`），同时 `TabFrameControllerImpl#checkEnableTabAnim` 将底栏高度重置为 `106px`（`54dp`）；现改为直接通过 `tab.findViewById` 定位 `0x7f0a67c7`/`0x7f0a7345`/`0x7f0a74a1`/`0x7f0a75e0` 并向上遍历设为 `MATCH_PARENT`，调用 `TabDragAnimationView#setIconGravity(1)`（`paddingTop = 6dp`）与 `TextView` `bottomMargin = 6dp` 垂直居中，反射锁定 `TabLayout#T = 0`、`TabLayout#P = w`、`tab.minimumWidth = w` 与 `bar.layoutParams.width = visibleTabs * w + 2 * pad4`（`458px`），调整 `OnPreDrawListener` 先 `fit` 后 `settle`，并挂钩 `checkEnableTabAnim` 实时恢复（预览见 [`docs/preview-safe-area-scroll-and-transition.png`](../docs/preview-safe-area-scroll-and-transition.png)）。
- Z 轴叠放（主页 `Conversation`/`Contacts`/`Leba`）与 Y 轴串联（二级页 `r/i/fte.xml`）顶栏安全区彻底清除与组件同步重定位（`Looks.kt`、`Input.kt`）：挂钩 `MainUIHelperApiImpl#updateTitleBar` 与 `Leba#updateTitleBar`，剥离 `0x7f0a11f9`/`0x7f0a6594`/`0x7f0a6cfa`/`0x7f0a5dff`/`0x7f0a6588` 背景、隐藏 `0x7f0a0951`（`QQBlurView`），同步平移 `0x7f0a3245`（头像）、`0x7f0a3d3f`（昵称/状态）与 `0x7f0a120a`（右侧 `+`）并提亮暗色模式图标；针对 `r/i/fte.xml`（`0x7f0a5ef0` 垂直 `LinearLayout` 背景 `#000000`），在 `SecNavBarState.applyStyle()` 中无条件用 `origNavBg`（`#1b1c1e`）或兄弟表面色替代根容器黑底，并挂钩 `QUISecNavBar#X()` 与 `SystemBarTintManager` 压制状态栏着色层（`tools/symbols.txt` 增至 616 条符号零不匹配，57 项 Kotlin 单元测试与 52 项 Python 契约测试全部通过，待真机复验）。

## 2026-10-21 — 四向严格 5dp 气泡边距与被回复消息去外框、顶栏返回箭头恢复与三胶囊触控重定向、输入栏异步防回退、二级页与搜索栏适配及长按菜单/撤回修复

- 气泡四向严格 `5dp` 内边距与被回复消息彻底去除内层外框（`Chat.kt`）：当 `统一气泡` 开启时将 `skin.padding.j` 与 `skin.padding.h` 归零，由最外层 `BubbleSurface` 施加 `5dp` 四向内边距与四角 `17dp` 全圆角，彻底清除被回复消息 `0x7f0a5c31` 外框与左侧竖条。
- 顶栏安全区按钮重定位、返回箭头恢复与夜间提亮（`Looks.kt`）：保留 `sm1.u#f` `TextView` 返回箭头背景并隐藏 `1px` 分割线 `u.d`，在 `ViewGroup#dispatchTouchEvent` 中按左、中、右三枚悬浮胶囊命中区转发点击。
- 输入栏与底部快捷栏防异步回写及全胶囊化（`Input.kt`）：对 `compat.b`、`compat.e$b#f`、`AIOShortcutBarVB` 与 `PanelIconLinearLayout#f` 全路径挂钩，并适配二级页顶栏 `QUISecNavBar` 与搜索栏 `QUISearchBar`（预览见 [`docs/preview-safe-area-scroll-and-transition.png`](../docs/preview-safe-area-scroll-and-transition.png)）。

## 2026-10-20 — 灰字恢复居中、仅他人居左自己居右、单条头像顶对齐防侵入、T字接缝直角、消息外部时间与输入栏药丸化

- 行对齐与头像防侵入：仅对收发气泡模板（`template.g` / `template.l`）套用 `RowGeometry`，居中灰字提示模板（`template.m` / `sm1.z`）保持原生居中；仅他人消息居左（头像左距 8dp、气泡左距 6dp，保持 `msgAvatar` 宽高 `WRAP_CONTENT` 并清零其顶内边距防止侵入气泡或下行 `LVxx` 头衔）、自己消息保持原生居右与右侧头像；单条独立消息保持原生顶对齐不居中（`runAvatarBounds` 在 `merged == false` 时返回 `null`），仅连发多条消息（`>= 2`）按组上下边界粘性滚动合并。
- 普通聊天头像圆角与气泡 T 字形接缝直角：拦截 `RoundRectImageView#draw` 下的 `QQProAvatarDrawable#z`（跳过 `proavatar.j#b` / `proavatar.mask.a#a` 的 360° 圆形 `Path.addArc` 遮罩）并配合 `proavatar.e$a#a` 实现普通聊天头像圆角；单条独立气泡恢复四角全圆角（`17dp`），仅在连发多条消息 T 字形相邻接缝处设为直角，且气泡圆角与头像圆角滑块解耦，同时覆盖 `AIOUnsuportContentComponent#Y1` 提示气泡。
- 消息外部时间与紧凑纵向间距：将自定义格式时间（`ExternalStamp`）直接挂载于 `msgContent`（`0x7f0a11b9` `BubbleLayoutCompatPress`）容器底部外侧（他人左对齐 `START`、自己右对齐 `END`）以适配全部消息类型；拦截 `AIOBubbleMsgItemVB#H1`、`skin.padding.j#a`、`skin.padding.h#a` 与 `AIOArkContentComponent#p2` 缩减行间与气泡上下边距。
- 输入栏与发送按钮独立药丸化：`InputEditTextVBDelegate#U` 与 `compat.b#handleUIState` 同时绑定 `adorn.e#a`（`AIOEditText`）与 `adorn.e#d`（`AIOInputSendBtn`），将输入框与发送按钮（含 `com.tencent.qui.smoothcorner.b#j`）各自按 `height / 2` 完全药丸化（`capsule()`）；`tools/symbols.txt` 增至 451 条符号零不匹配，本地 56 项 Kotlin、50 项工具/源码契约、10 项清理脚本测试全部通过（待真机复验）。

## 2026-10-19 — 普通聊天头像圆角生效、左对齐紧凑消息与右部灵活圆角、左对齐时间与格式码说明、头像滚动合并优先于居中

- 修复普通聊天头像圆角不生效：普通聊天 `AIOAvatarContentComponent#K1` / `D1` 默认经 `FaceDrawableEngine` 将头像预裁成 360° 圆形 Bitmap 且非空 `payloads` 在 `d1` 提前返回；现拦截 `com.tencent.qqnt.avatar.meta.b$a#b` 调 `m(false)`（切至 `_no_circle` 缓存键跳过 `CircleAvatarTransform`）、半径 `< 50%` 时跳过 `com.tencent.qqnt.avatar.b$a#i` 预裁圆 VAS 头像并直通 `BaseImageUtil#getCircleFaceBitmap`，在 `K1` 与 `d1` 双落点同步 `RoundRectImageView#setCornerRadiusAndMode`（0% 即 90°直角时不圆角），调节时清空 `m` 缓存重刷。
- 消息气泡统一左部无圆角（`topLeft = 0, bottomLeft = 0`）、右部灵活圆角（外角 17dp、连发内角 6dp，按气泡半高自适应上限并与圆角滑块联动，0% 即 90°直角时 `topRight = 0, bottomRight = 0` 不圆角），同时覆盖 `BaseMixContentComponent#C2`、`AIOReplyComponent#C2` 与 `TextMsgContent#H`（清理 `b` / `d` / `l()` 内层气泡背景）。
- 收发双方消息（`r/i/dxm.xml` 与 `r/i/dye.xml`）统一左对齐并尽量缩短边距：头像 36dp 左距 2dp，正文与昵称紧贴头像右侧 2dp、右距 4dp（总左缩进 40dp，`horizontalBias = 0f`），拦截 `template.g#q` / `template.l#q` 与 `skin.padding.j#a` 消除气泡尾巴非对称内边距。
- 时间格式与时间戳左对齐：设置面板「消息外观」补充左对齐格式码说明（`yyyy/yy`、`MM`、`dd`、`E`、`HH`、`hh`、`a`、`mm`、`ss`、`SSS` 与单引号转义），文本 `MessageStamp` 与非文本胶囊 `SurfaceStamp` 统一改为左对齐绘制。
- 头像定位遵循「滚动合并大于居中」（`runAvatarBounds`）：只要存在连发续接或消息/组高度超过头像，即按整组上下边界 `[groupTop + h/2, groupBottom - h/2]` 粘性滚动合并，仅短单条居中；`tools/symbols.txt` 增至 424 条符号零不匹配，本地 56 项 Kotlin、50 项工具/源码契约、10 项清理脚本测试全部通过（待真机视觉与滚动复验）。

## 2026-10-19 — 按 NagramXF 重做聊天顶栏、消息行、气泡、输入栏与纵向菜单布局

- 对照 `Keeperorowner/NagramXF`（`30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`）的 `ActionBar.setupGlass` 与 `ChatAvatarContainer`，接入 `AIOTitleVB`（`sm1.u` / `sm1.w` / `CenterVB`）与 `AIOTitleRelativeLayout`：绘制左 46dp 返回胶囊、中 42dp 圆形会话头像 + 左对齐 17.5sp 标题 / 13.5sp 副标题胶囊（`getVisualWidth` +64dp，经 `com.tencent.qqnt.avatar.a` 从 `AIOContact` 异步加载头像）、右 46dp 操作胶囊（`p = 6dp`、`r = 23dp`），并隐藏 `com.tencent.mobileqq.aio.reserve1.blur.f` 通栏模糊。
- 对照 `ChatMessageCell`、`MessageDrawable` 与 `NekoMessageCell`：己方消息（`isSelf`）隐藏右侧头像并折叠头像槽宽度、气泡右贴 6dp 外边距且隐藏自发昵称；对方消息使用 42dp 头像（左距 6dp、气泡间距 3dp、总缩进 51dp，底对齐正文槽）；统一气泡与连发合并下绘制外角 17dp / 连发内角 6dp 无尾巴气泡与 3dp 左竖线引用块（4dp 内圆角、10dp 左右内边距），拦截 `BaseMixContentComponent#C2` / `AIOReplyComponent#C2` 防止皮肤覆盖；非文本消息时间 `SurfaceStamp` 升级为最小 17dp 高半透明胶囊并同时覆盖 `f1()` 与 `h1()`。
- 对照 `ChatInputViewsContainer` 与 `ActionBarPopupWindow` / `ActionBarMenuSubItem`：输入区接入 `AIOInputExpandableVB`、`compat.b`、`BottomBlurVB` 与 `AIOShortcutBarVB` 隐藏底部分割线与通栏模糊；原生消息长按菜单 `QQCustomMenuExpandableLayout` 重排为 12dp 圆角玻璃纵向列表（48dp 行高、18dp 左右内边距、20dp 图标 + 12dp 间距 + 16sp 完整文案、6dp 按压圆角，隐藏暗色 `S: QQBlurViewWrapper` 与分割线 `T`）。
- `tools/symbols.txt` 增至 383 条符号零不匹配，`tools/ui_paths.py` 新增顶栏、输入栏、底部模糊、快捷栏与气泡/引用皮肤落点校验；本地类型检查与 55 项 Kotlin、50 项工具/源码契约、10 项清理脚本测试全部通过（尚无本轮真机视觉与手势复验）。

## 2026-10-19 — 继承旁支、补齐原生消息菜单随指高亮与全类型消息时间

- 继承 `arena/e207d38d-qself`（`73c622e5`）与 `session/norm-utc8`（`bb5bbb14`，内容已由 `c0077d5e` 合入现代树）到当前分支，消除分叉并保持单一主线。
- 核对 QQ 9.2.10 原生消息长按菜单 `QQCustomMenuExpandableLayout`（`p` / `l` / `H` / `G` 与 `d#e` / `d#f`），实现原生消息菜单与设置面板的逐项随指高亮、跨项移动、移出边界取消且松手不触发动作，并在展开态（`a0 == true`）纵向拖动超阈值时交还 `ScrollView`；「删除」「撤回」「结束进程」统一应用警示红图文与按压背景。
- 消息内时间扩展至全部 9 类内容组件（文字、文件、图片、引用、混排、语音、表情、卡片、视频）：含 `AIOMsgTextView` 的组件走正文下部 `MessageStamp`，非文本组件在容器 overlay 右下角绘制胶囊时间 `SurfaceStamp`（不改动子 View 树），并联动折叠/恢复顶部 `AIOTimestampComponent` 时间槽。
- `tools/symbols.txt` 增至 283 条符号零不匹配，`tools/ui_paths.py` 新增原生菜单与全类型消息组件完整签名/访问标记核验；本地类型检查与 53 项 Kotlin、49 项工具/源码契约、10 项清理脚本测试全部通过（尚无本轮真机手势与视觉复验）。

## 2026-10-19 — 紧凑文字间距与头像绑定防护

- 普通文字内边距收紧至左右 10dp、上下 6dp；时间基线从实际预留行计算，不覆盖正文，不压缩点击区域。
- 浮动头像帧、绘制与触摸核对原绑定、消息组和挂载状态；失效手势取消，不能只凭复用 View 继续派发。异步回退也拒绝过期绑定。
- 设置面板取消整卡触摸驱动玻璃按压，保留各项状态反馈及玻璃材质；不是 QQ 原生撤回菜单随指高亮的完整实现。
- 将已确认设计与尚未完成的全类型消息、原生菜单、头像设备复验及性能优化记入 TODO.md，明确局部修正不等于整体完成。
- 本地类型检查与 52 项 Kotlin、47 项工具/源码契约通过；没有本轮 QQ/GPU 设备验收。

## 2026-10-18 — 统一取号命令时区写法与许可证章节

- 出包工作流的两条日期命令改用 UTC+8 固定偏移，保留原日期格式、提交计数、校验与上传逻辑。
- README 的 GPL 许可证段落独立为「许可证」二级章节，放在末行规范指针之前；正文及其他章节保持不变。

## 2026-10-17 — 底栏数字上移半个字形高度

- 用 QQ 当前字体测量数字字形高度，将角标整体上移一半，保留水平中心、字号和正常透镜形变；从原始位置计算，重复布局不累积。
- 上移先于原生触摸区域更新；只放开角标到所属页签间的裁切，关闭具体计数或玻璃底栏时恢复原位置及裁切，避免缺字与命中区域错位。
- 本地类型检查与 46 项 Kotlin、37 项工具/源码契约通过。无新包 Android GPU / QQ 实机复验，不把逻辑检查当作可读性与真实触摸验收。

## 2026-10-17 — 统一玻璃菜单，完善消息外观与转发功能

- 用户确认截图中的透镜变形是正常效果，保留原折射、指示器缩放和弹簧；设置短胶囊改为可见 76×64dp，与静止底栏同高居中，可见边缘间距 12dp，不再以最大动画包络撑大静态间隔；不足时仍上置，保留四周绘制空间与原生点击。
- 固定参考 AndroidLiquidGlass 65ab177e90e5c1d8c62e70cf7755841982da65f6。按明确要求移除 BloomStroke 边缘带及不再需要的重力监听，保留内部交互柔光与内阴影；底栏和按钮模糊由 4dp 降到 2dp，不复制其 Tabs 示例的 8dp。
- 普通内容统一中性色，强调色副本完整着色，移除前景再次叠加的 40% surface 罩色；保留 Monet 开关与 QQ 原图标形状，未宣称重绘图标，也未新增依赖。
- 本地类型检查与 45 项 Kotlin、36 项工具/源码契约通过；新增 LTR/RTL、同高/可见间隔与多密度上置测试。未做新包 Android GPU / QQ 实机复验，不能用旧截图或静态测试宣称新的颜色/排布已通过设备验收。

- 菜单与原生输入框复用底栏玻璃绘制路径，分别保留 22dp 圆角；菜单去掉全屏暗色遮罩，避免取样先被压黑。保留底栏与入口正常透镜、弹簧及输入层级。
- 菜单按入口实际矩阵定位，就近向上或向下展开；监听安全区及键盘引起的布局变化，关闭时注销监听并释放玻璃。改为单列、统一轻量按压背景；说明、功能与操作均可内部滚动，兼顾窄屏与大字体。
- 明示勾选仅代表配置，部分功能需重进页面或重启；恢复默认不宣称即时恢复全部对象。“重启 QQ”改为带确认的“结束进程”，准确说明只结束当前进程。
- 本轮类型检查、52 项 Kotlin、46 项工具/源码契约及 10 项清理脚本测试通过；真包 37 个 DEX、249 条符号及完整签名/访问标记检查通过，新增菜单定位边界与共享圆角检查。尚无本轮 Android GPU / QQ 实机验收，其他聊天与拦截功能未据此宣称完成整改。

- 设置图标补充 24dp 固有尺寸，修正无固有尺寸 Drawable 被 ImageView 放大的问题；菜单真实触摸驱动玻璃柔光，阅读面板不作按压扩张。保留底栏和入口原透镜/弹簧。
- 修复转发私有 getForwardTargetKey / refreshRightBtn 的反射访问并补原生搜索勾选同步；真包检查增加完整签名、访问标记，避免“名字存在”被误当作 getMethod 可访问。
- 新增普通文字正文统一内边距与下部独立绘制的自定义时间戳，默认 HH:mm；不向正文追加时间，关闭/复用/热重载恢复。图片、语音、卡片未强行重绘。
- 新增「消息显示 At 对象」及 0～50% 头像圆角；按真实 TextElement 匹配重复昵称与 UID/UIN，保留原链接、长按和滑动。使用真包资料卡类和原生圆角接口，不直接复制上游实现。
- 无有效浮绘源时保留原生头像，旧位置回退增加边界约束；尚未获得 QQ 设备定位复验，不宣称头像问题全部消失。
- 正常合入 main 中删除旧规范检查工作流的提交，消除真实的落后历史；不强推、不重写已出包提交，不关闭或合并 PR。

## 2026-10-17 — 修正数字坐标与玻璃合成，实现独立设置短胶囊

- 数字中心改用完整矩阵统一到父坐标，再作增量校正；显式 CENTER 绘字，不清零后混用窗口像素和局部宽度。保留纯数字无红底及可变宽。QQ 原初始化已 CENTER，显式设置只作防御；位置校正先于原生 onLayout，让其触摸区域同步更新，并增加这两条 DEX 调用关系检查。
- 底栏在 dispatchDraw 合成普通 QQ 内容、独立 56dp 强调色缩放副本和组合背景，前景透镜改从组合背景取样；材质扩张/面板平移不再改真实 QQ View 的变换。修正背景重复矩阵、浮点位置截断与单侧内阴影；速度跟踪动画位置，解析弹簧不截掉长帧时间。修正释放后回滚到旧选择的接线，点击仍走真实页签。
- 按用户选择实现独立 76×52dp 设置短胶囊，预留按压绘制空间，共享底栏玻璃与弹簧；删除旧渐变早退分支，不再叠加另一套按钮缩放/水波纹。空间不足固定右上，菜单内容与材质不扩张，不新增运行依赖。
- 本地类型检查及 43 项 Kotlin、35 项工具、10 项原有清理测试通过；只读真包 37 个 DEX / 223 条符号及 UI 静态路径通过。未做本轮 Android GPU / QQ 真机复验，不能以这些结果宣称漂移已实机消失或一比一验收完成；主题、速度跟踪器及外阴影差异列入 docs/kernel-port.md。

## 2026-10-16 — 迁移 AGENTS.md，清理逻辑并成一份脚本

- 按规范 S3 删除 AGENTS.md：仍有用的项目限制（ponytail 写法、输入栏与头像约束、找落点的方法、做不到的清单）迁入 README「项目限制」，构建说明并入「构建」；全局规则不复制，README 末行留规范指针。README 按 S1 重排为 要求 → 功能 → 安装与使用 → 构建 → 项目限制。
- 清理只留一份逻辑 `.github/scripts/cleanup.py`：新增 `CLEANUP_SCOPE`（`actions` 默认、`releases`）。Release 与 tag 清理由 build.yml 里内联的 Python 改为调用该脚本的 releases 档，并补上「无保留 Release 对应的 `Qself-` tag 一并删」；tag 删除改走 `git/refs/tags/` 路径。三个清理作业共用并发组 `qself-cleanup`；写权限仍分开授：actions 档 `actions: write` + `contents: read`，releases 档只 `contents: write`，releases 作业 checkout 不留凭据（`persist-credentials: false`）。
- CHANGELOG 只留最新 5 条，更早 27 条原样移入 `bad/CHANGELOG-history.md`。
- 验证：清理脚本测试 10 项通过（含 releases 档新增 5 项）；两个工作流 YAML 可解析、各作业权限与并发组符合上述设计。未在 GitHub Actions 上运行，Release / tag 的删除端点尚未实测。

## 2026-10-16 — 根据实测失败自查底栏接线和尺寸

- 上一版漏查 BottomBarMiuix.kt 调用处 defaultMinSize(76.dp)，错误保留 56dp 页签宽，导致 56×56 圆形；修正为 76dp 起，各可见页签随最长数字共同扩展。
- 普通 DOWN 原先不直接触发按压，只有横移超过阈值才显式启动；按压还依赖 QQ 子视图 pressed 和原生内容页绑定。现在用实际 DOWN/UP/CANCEL 驱动，取消 pressed 轮询；内容页绑定失败只禁用纵滑交接，不再禁掉按压/横拖。徽标原生拖动/长按保留，视觉反馈也接入；纵滑接管、多指、取消和分离均释放按压。
- 扩展背景绘制层边界、关闭并还原页签条裁切；整行内容缩放；按上游分两层画静止黑/白与按压黑色，修正暗色按压错误用白色。InteractiveHighlight 改用原 smoothstep AGSL，不再以线性 RadialGradient 近似；CI 编译增加为五份 shader。
- 旁边 52dp 圆钮仍是原有 Qself 设置入口，不是 KernelSU 组件；材质不变，只修正排布避让，给按压扩张和速度形变留空间。放不下时仍按原规则置于右上方。
- 本地 41 项 Kotlin、32 项工具、5 项清理逻辑测试通过；真 QQ DEX 与绑定路径另行核验。无设备，不能把源码纠错和测试通过说成实测已修复或整体一比一完成。

# CHANGELOG 历史

按规范 S4 从 CHANGELOG.md 移出的较早条目，原文未改。

## 2026-10-16 — 按明确选择移植 KernelSU 主页底栏算法

- 用户选定仅主页底栏，以上游完整效果为准，撤回无模糊/无描边限制；又明确不增加依赖。因此保留 QQ 原生页签/点击和现有模块架构，不引入 Compose/Miuix 二进制，移植上游算法，不再使用上一版纯色渐变分支冒充玻璃。
- 固定 KernelSU 3186a2fe2107f7170e06dab7aa157eb10ae7759f、其 Miuix 0.9.4 对应源码，加入带来源/许可的圆角 SDF、折射、七通道色散、ColorControls 和 BloomStroke dual-peak 着色器；采用 64/56/4dp 布局、1.5 饱和度、4dp 模糊、24dp 底层折射、按压 10/14dp 指示器折射、0.5 色散、1dp/2dp 重力双峰高光、8dp 内阴影。
- 以解析弹簧替换底栏固定时长动画，沿用原阻尼/刚度、78/56 指示器按压比例、1.2 内容缩放、速度形变和 4dp 面板回弹；横向选择只在释放时执行 QQ 真实页签点击，取消恢复原选择，纵向转交原内容页。关闭/分离还原缩放、位移、裁剪和阴影状态，移除帧回调与传感器监听，静止不循环刷帧。
- 只改底栏，菜单圆钮仍保留前一版；聊天问题不列为本轮完成。QQ View 背景录制、图标组合背景及主题运行时与 Compose 原组件有差异，已逐项列在 docs/kernel-port.md，未声称一比一已验收。
- 本地 Kotlin 类型检查及 38 项纯逻辑测试、29 项工具/契约测试通过。Skia 138.0 工具本地已安装但导入缺 libEGL，故未冒称本地 AGSL 编译通过；两个既有工作流新增同一脚本实际编译四份 shader，结果以 CI 为准。无设备，GPU 像素、手势和视觉一致性仍未验收；许可和源码来源见 third_party/NOTICE.md。

## 2026-10-15 — 补 V4 昵称分支，重做圆形底栏与手势起点

- 用户实测仍有头像跳顶、昵称折叠和消息合并无效，按未完成验收处理。真包另有 AIONickComponentV4，旧代码只识别 V2；补上 V4 的 i1 槽位，正文根替换/暂隐时改读稳定正文槽，无法取得新几何时保留本组仍持有的上次有效头像矩阵，不再恢复到原生行顶头像。
- 按用户确认，底栏上下滑动仍应滚背后的列表，不移动菜单/底栏。将原始 DOWN 坐标平移到 QQ 原生内容页边界内，整条事件使用同一偏移，保留位移/速度；取消原点击后才重放 DOWN，原生拒绝 DOWN 时不标记接管。未以此离线推导宣称设备上已滚动成功。
- 实读 KernelSU FloatingBottomBar（200818cabf8ff8ba857dee068c5fae5ae63ae6aa）：参考其 CircleShape、内容宽度与无模糊底色分支，用现有 Canvas 实现，不引入 Compose 或复制代码。底栏和菜单圆钮使用全圆角填充及独立圆形选中项，允许面状高光，不描边、不模糊、不录制背景。
- QUIBadge.drawText 原生会绘红底和边框；仅在浮动底栏的纯数字分支从框架 onDraw 入口改为绘制文字，不依赖 drawText 私有子调用是否被内联，横向对齐页签中心，页签宽度按字体测量扩展，关闭恢复位移和宽度；零未读不额外绘制数字；其他红点、NEW 及非底栏徽标不改。
- 同时修正 DEX 检查器：补齐算术指令宽度，跳过位于方法中部的数据载荷后继续解析；原检查器会误把算术操作数当载荷，漏读后续字段和调用。新增回归并重跑所有静态路径，不删失败判据。
- 本地 33 项 Kotlin、24 项工具/源码契约、5 项清理测试，以及 223 条真包符号、18 处静态路径检查通过。设备视觉/滚动和聊天实际效果未验收。

## 2026-10-15 — 统一修正聊天分组、昵称容器和浮动边界

- 按 QQ 9.2.10 DAD/DEX 核实：VB.H1 设置正文组件 i1 容器的 topMargin，旧代码却改整行根；AIONickComponentV2.d1 对 i1 外层容器设可见性，旧代码只折叠 f1 内部根。改用真实槽位，将续条正上间距限制为最多 2dp，保留零/负间距和正文内边距；主组件表之外读取 VB.G 已激活附加组件，处理延后创建，不读取未初始化组件。
- 原生布局 AXML 的头像槽为 wrap_content，TemplateLayout 继承原生 ConstraintLayout。续条测量时使用 onLayout 同源的 ConstraintWidget Y/height 排除头像槽撑高，保留其他可见控件、原生最小高度与 EXACTLY 约束；不改头像本身尺寸/测量，开关和分组变化请求原生重测，pre-draw 仍不重排。
- 为每条已绑定消息保存数据位置及完整组起止；Differ.j 列表提交完成及绑定时统一重算，覆盖历史插入、消息替换及待发 ID 转正。相邻身份优先比较双方共有 UID/UIN，自发消息统一身份。浮动范围依据实际组边界，只有一条挂载行的长组不再按单条强制居中；图源尚未就绪时也只保留一个原生头像，不把整组全部恢复。
- 可见性保存 QQ 最新原生请求与模块覆盖两份状态，防止原生刷新重新显示续条昵称/重复头像；复用、关闭和卸载恢复原生值。重复注册同一绑定不销毁其状态，嵌套附加组件创建延至外层绑定结束再采集，已激活的附加组件不反复拆装。
- 本地 Kotlin 类型检查及 31 项逻辑测试、19 项工具/源码契约、5 项清理测试通过；217 条真包符号及 16 处完整签名/字段/调用边检查通过。这些只覆盖实现和离线证据；无设备连接，用户反馈的头像跳动、消息不合并和昵称未折叠仍未完成视觉验收。本次不改底栏、菜单、输入或角标。

## 2026-10-15 — 删除底栏手势目标猜测，改用 QQ 注册关系

- 用户指出查过 DEX 仍在猜，继续承认而没有落实。删除 scrollTarget 的半屏尺寸 / canScrollVertically 递归挑选，以及按 ViewPager 类名寻找兄弟的回退。
- 按真包 TabFrameLayout.onFinishInflate 的字段赋值核对：e 为 QQTabLayout，d 为 ViewPager2，a() 读取并返回 d。运行时必须确认 e 与当前底栏同一实例、内容页与底栏同父，才将事件交给 QQ 原生内容页分发；不匹配时记录原因，不另选目标。
- tools/ui_paths.py 验证完整签名、字段读写方向及八处静态关系，覆盖底栏初始化/内容 getter、输入绑定器 e 赋值、角标 updateFrames → 注入器 → d.a → setRedText/resetValue。原有两个工作流均运行检查，并明确输出 device_execution_verified=false，不能拿静态证据冒充设备命中。
- 本地 25 项 Kotlin、16 项工具/源码契约、5 项清理测试通过；203 条真包符号及八处静态绑定检查通过。本次未宣称输入/角标症状完成；无设备连接，最终滚动、开关与角标效果仍未验收。

## 2026-10-15 — 修正底栏背景滚动对象，接入 NT 输入与底栏数量绑定器

- 用户明确底部背景拖不动、TG 输入开关无区别、99+ 仍未修，前一包效果不成立。确认所需是从底栏背景滑动也能滚动后面的列表，不是移动底栏或菜单；删除误加的菜单拖动。
- 底栏及周围底部空白保留原生 DOWN/点击，纵向位移超过触摸阈值后取消原生点击，把原 DOWN 与后续事件按浮点矩阵转交背后的可见原生滚动容器；不移动底栏，不手动调用列表拦截、不伪造 UP，保留横向手势、徽标原生拖动及上层按钮/浮层。关闭、取消、分离时回收事件并取消转交。
- 实读 TabFrameControllerImpl.updateFrames → FrameControllerInjectImpl.b → widget.d.a(QUIBadge,style,count,limit,label)：超过 limit 会 setRedText("99+")，并将 mNum 清零，绕过原先只处理数量方法的实现。改在 d.a 入口保留真实数值，接入公开数量 setter；记录开关关闭时的实际绑定，重开即时重应用，不必等新消息。NEW/红点/图标等模式会清除旧计数，关闭恢复原文字和布局。
- 真包 NT 的 AIOEditText 在 ConstraintLayout 内（dxg.xml），不要求旧版 PanelIconLinearLayout。改从 InputEditTextVBDelegate.U 的真实 e 字段接入，按编辑器而非图标带去重；开关关闭也保持发现，明确刷新现有实例，移除强制开启迁移。NT 输入行清背景，仍保留原生编辑器、发送按钮、尺寸和父子关系。
- 本地类型检查与 25 项 Kotlin 回归通过，198 条真包符号零不匹配；13 项工具/源码契约及 5 项清理测试通过，源码契约用于防止再次接错入口。没有设备连接，手势、输入开关和实际角标仍不冒称已通过设备验收。

## 2026-10-15 — 截图确认 99+ 与小扳手后修真实计数入口和菜单尺寸

- 截图仍显示 99+ 和小扳手，撤回之前将其视为已修复的结论。用 Androguard 4.1.4 的 DAD 反编译与本仓 DEX 工具交叉检查 QQ 9.2.10：com.tencent.widget.d#d(TextView, style, count, background, limit, label, flag) 的真实数量是第 3 个参数；旧代码读第 2 个 style，数量自然不会超过 99。按完整签名装钩并修正参数，增加回归；不再从整树中的任意文字猜数字。QUIBadge 同时按原 Paint 测量数字宽度，不再只改 mText 却沿用 99+ 固定宽度；TextView 数字保持居中及左右对称内边距，关闭恢复布局。
- 菜单入口改为 52dp 三线图标按钮，不再受底栏高度裁成 40dp；功能项最小高度 64dp，底部操作最小高度 48dp。面板宽度不变，在旧高度上最多增加 80dp（上下各约 40dp），上限 520dp，仍留屏幕边距；标题和空白背景可拖动，开关点击与内容滚动不被抢走。
- 从真包 AXML 核实 InputLinearLayout、PanelIconLinearLayout 和与输入行对齐的 QQBlurView 背景。玻璃改为只覆盖真实 EditText，移除强制最小高度，清理上述输入背景，不清整页聊天壁纸；按钮仅按剩余宽度分配间隙，不搬移、不隐藏按钮、不改宽度/weight，关闭还原。
- FunBox/繁华模块已换 Androguard 静态分析入口；JADX Release 下载因网络 EOF 未安装，DexSQL 仅核对上游与许可，未冒称已安装或完整逆向。原生装载和混淆仍阻碍功能实现恢复。头像/消息/昵称合并的设备问题本次尚未确认解决，不归入本次完成项。
- 本地 Kotlin 类型检查及 23 项回归、7 项工具测试、5 项清理测试通过；真包核验 185 条符号零不匹配。已验证范围为代码和离线分析，截图反馈不能被这些检查替代；实际 UI 仍未验收。

## 2026-10-15 — 重写头像滚动渲染链路

- 用户指出滚动时头像跳动/滞后；不再要求用户记忆复杂现象或上传录屏作为推进前提。自行检查确认：旧代码在 pre-draw 修改昵称/间距并 return false 取消绘制；浮动几何变化缺少显式 overlay 失效通知；坐标用整数窗口位置，触摸又保存旧位置。上述是代码事实，不宣称已证明设备上所有现象都由它们造成。
- 昵称/间距在绑定完成与设置变化时更新；滚动 pre-draw 不再重排或取消帧。改用相对列表的浮点矩阵，统一图像绘制和触摸逆映射，按同帧几何变化显式提交重绘，静止时不循环刷帧；同一组优先保留原图源，避免每滚入一行更换头像组件。
- 头像点击中坐标随帧更新；原目标离开、复用或开始拖动时发送真实 CANCEL，继续保持列表事件流。原生拦截只在 DOWN 执行，不在 UP 提前清掉 RecyclerView 的速度。保留 QQ 原头像、原输入与组首昵称目标，不修改任何 QQ 落点。
- 增加小数滚动、视口边界连续性、反向滚动、键盘视口缩放、无效几何回归，以及渲染阶段只读布局/显式重绘/同源矩阵/绑定时机/惯性事件顺序的源码契约检查；契约检查不能代替 Android 真机渲染与手势验证。
- 本地类型检查及 20 项 Kotlin 回归、7 项工具测试（5 项渲染契约 + 2 项 DEX 解析）、5 项清理测试通过；真 QQ 9.2.10 核验 180 条符号零不匹配。两个既有工作流均接入工具测试，不增新流程、不改变版本号源。设备效果仍未验证。

## 2026-10-15 — 全模块链路重构与旁支继承

- 重构八个源码文件的安装、业务门解析、监听/卸载、帧前布局和菜单链路；保留已验证的防撤回 protobuf 语义与 QQ 原生输入/发送逻辑，不为重写更改协议。每项安装失败逆序撤钩，保留真实失败状态并可查看诊断；热重载在主线程先清旧 UI，清理失败拒绝热重载。
- 去掉 150ms 外观扫描与 row.post 延迟注册，首次 attach 同步接入；同帧组首索引避免长连发反复向前反射，pre-draw 后失效。昵称与间距先完成布局再绘头像，浮动位置不做追赶动画；续条只收紧正 topMargin，原布局、昵称和头像关闭/复用可还原。
- 菜单缩为最多 360dp × 440dp / 可用高度 62%，避开系统栏和键盘；保留卡片空白区域消费触摸，新增开合、按压、勾选描画/变色、返回关闭与恢复默认。玻璃按压/切页、原生输入焦点阴影遵守系统动画开关；卸载取消本模块动画和监听，底栏关闭恢复原参数。
- 以有实际内容的多父合并继承 arena/01a109fa-qself、arena/63fa89b7-qself、arena/614127b9-qself 的历史。1396c8be 的严格编译退出、52411e87 的紧凑菜单/连发间距、83ac6dbb 的按压目标已落实；e64c8e65/b78bd64d 的精确角标沿用当前更完整实现。旧输入区搬移、已移除的昵称精简、旧版本号源/额外 CI/旧规范戳均不恢复；保留当前严格清理与五段 git 提交数取号；Gradle 同步拒绝四段号、缺失号和常数回退；PR 单测格式夹具同步为五段，仍不取号、不出包。
- 本地 Kotlin 类型检查与 15 项纯逻辑测试、2 项 dex 解析测试、5 项清理测试通过；QQ 9.2.10 真包 180 条符号、0 不匹配。真机首次浮动、长连发滚动、昵称、输入/键盘与动画效果仍待验收；不得将 CI 通过写成视觉通过。

## 2026-10-15 — 修复连发安装失败、恢复原生输入区、移除昵称精简

- 用户反馈连发合并显示 ✗。真包 class_data 证实头像 C1() 访问标记为 0x12（private final），上一版 getMethod 只找 public 导致安装失败；改用声明方法查找并显式开放访问，新增私有访问器回归。未移除 failed 标记或掩盖安装失败。
- TG 输入栏重写为 QQ 原生输入区玻璃化：不搬移任何视图、不创建镜像按钮、不隐藏图标带、不压缩兄弟槽位，保留真实 EditText 与 QQ 原发送/语音/表情/附件逻辑。按用户要求迁移时重新启用一次，后续关闭保持有效；旧搬移版升级需强行停止 QQ 后再打开，开关可还原背景与最小高度。
- 按用户要求移除“昵称只留名字”：删除功能入口、plainNick/NICK_DROP 实现及专用符号。连发的组首昵称仍保留 QQ 原装饰。
- 本地 Kotlin 类型检查与 11 项测试通过，真 QQ 包核验 180 条符号、0 不匹配。尚未连接设备，连发开关、输入框、键盘和发送行为待真机重新验收。

## 2026-10-15 — 撤回浮动验收，重做消息几何定位

- 用户反馈：自己的头像多个，单条头像偏下；撤回上一版浮动验收。已当面确认昵称采用 TG 式固定组首，不进气泡、不跟头像浮动。
- `Chat.kt` 改为在 AIOBubbleMsgItemVB 绑定后从组件表一次采集全部头像容器、实际头像图片、消息正文和昵称；用 BaseContentComponent 的实际视图边界求内容中心，不再拿整行的底部或头像容器留白定位。
- 单条头像与实际内容同中心；多条头像受组首、组末内容中心与可见窗口约束。统一隐藏所有原头像容器，只绘制一份实际头像图片，触摸坐标映射回原容器。自己的 senderUid 缺失时按 isSelf/senderUin 识别，待发送 msgId=0 时按组首对象身份分组，不再跳过。
- 修复 `tools/dexq.py`：direct_methods/virtual_methods 两个差分索引流须分别重置；旧解析把后一组索引错累加，导致方法错属和越界。清除 AGENTS.md 中将此归咎于 QQ 畸形 class_data 的错误说明，并新增两项离线解析回归。
- 本地 Kotlin 类型检查和 10 项测试通过，解析器 2 项测试通过；真 QQ 9.2.10 核验 192 条符号、0 不匹配。视觉、滚动和点击效果仍未重新验收，不记为已修好。

## 2026-10-15 — TG 连发头像浮动与昵称合并

- 替换仅隐藏后续头像的实现：每个消息列表加一层 Drawable，直接绘制 QQ 原头像，头像贴同一消息组可见底部；不搬动原视图、不改父容器裁剪、不复制头像位图。
- 昵称只留组首；相邻同发送者五分钟内合组，跨日、倒序、系统消息断组。去掉进程级消息 id 集合，避免跨列表残留。
- 绑定与局部更新前恢复旧状态；按实际组件过滤共用基类钩子；列表离屏、开关关闭、异步绑定和复用均有还原路径。头像点击转给原头像，超过触摸阈值取消点击并交列表滚动。
- 真 QQ 9.2.10（389611416B，SHA256 前缀 34bdea66e738）离线核验：183 条符号、0 不匹配；本地类型检查通过，8 项纯逻辑测试通过（含连发断组、头像可见边界）。未连接设备，滚动、长按、点击与视觉效果未验证。

## 2026-10-15

- 依据 selfs `a1148c42f8da21a9a28e064030c596bd02cf45af`：运行记录上限优先，每工作流含当天最多 5 条且不因关联产物豁免。先计算两份候选清单，再把超限运行承载的候选 artifact 转入删除清单，先删产物后删运行。
- 回归改验“第 6 条运行承载候选保留包”会先删包再删运行，不再以关联冲突阻塞；产物删除失败仍禁止级联删除。

## 2026-10-15

- 依据 selfs `f4091e3e7d91a650c605ab8c71e1121bd3f2de3a`：artifact 改为全仓按 created_at 保留最新 5 个，运行记录改为每工作流按 run_started_at（缺失用 created_at）保留最新 5 条，承载与未完成运行计入名额。
- 先打印产物及运行完整计划、先删产物再删运行；超限未完成运行先取消并等待结束，复查新产物后删除。保留产物与运行上限冲突时明确失败，不误删保留产物。
- 删除失败返回真实结果；403 不再声称清理成功，产物删除失败时禁止级联删除其运行。
- 离线回归 5 项通过：全仓产物与严格运行上限、四项无效产物判据、403/500 禁止级联删除、保留冲突停止、取消后删除；本地真实扫描 3 个 artifact 全部通过号源核验，Build 8 条运行超限 3 条，PR check 5 条超限 0 条（真删由推送后的 CI 执行）。

## 2026-10-15

- 依据 selfs `eb676eb5e03debe035331f267312908ffbc535c1`：取消 artifact 与运行记录的当日豁免，每条序列保留 5 个；保留承载产物和未完成运行保护。
- 清理补逐 artifact head sha 的 git 提交数核验，取不到即失败；同序列重复号按 artifact created_at 删旧留新。
- 删除失效的连续两次 403 升级说明，403 warning，其他错误红；同步工作流注释，移除 AGENTS.md 的全局规则副本。
- 本地全历史干跑：两件 artifact 的总序号 3103、3102 均与对应 head 提交数一致；预计删除两条超量 PR check 运行，尚未算作真删。

## 2026-10-15 — 号源换成 git 提交数；清理判据三改按 run_started_at 删旧留新；换号源过渡清理

- 依据：selfs 分支提交 `c5ac12d`（号源只有 git 提交数一处：总序号 = `git rev-list --count HEAD`，当日序号 = `git rev-list --count --since=<当日0点 UTC+8> HEAD`，`run_number`／事件／文件名／PR 一律不进版本号；硬前提 `actions/checkout` 写 `fetch-depth: 0`、取号脚本启动行打印 head sha 与两个数与命令原文、取不到数失败退出不留降级不退化四段；head 提交不在当日时当日序号取 0；提交数就是号源所以禁空提交与 force push／rebase；同一仓不得并存两种取数口径，新包出成之后再清旧口径产物并记一行真数字）、`240a214`（先出新口径包再清旧口径，不许出现一个可装的包都没有的状态）。
- `.github/workflows/build.yml`：`apk` 作业 `actions/checkout` 补 `fetch-depth: 0`（缺了提交数恒为 1）；取号段整体重写 —— 删掉 `run_number` 取数、当日运行位次、退化四段与「重试复用第一次的号」缓存三步，换成 git 提交数 + 浅克隆硬失败 + 启动行打印 head sha／两数与命令原文，取不到数即 `exit 1`；头注释与 `SEGMENTS` 注释同步改口径；`cleanup-artifacts` 作业 checkout 也补 `fetch-depth: 0`（清理脚本要打两条取号命令的输出）。
- `.github/workflows/pr-check.yml`：`cleanup` 作业 checkout 补 `fetch-depth: 0`。
- `.github/scripts/cleanup.py`：启动打印补「删除失败分级口径」与取号核对（head sha、总序号、当日序号、浅克隆标志、两条命令原文）；判据三由「保留总序号最大者」改为「按 `run_started_at` 删旧留新」，并把两个 artifact 的名与 id、两次运行的 rn 打进日志；新增换号源过渡清理（旧口径两只，守卫＝已存在总序号 ≥ 3000 的新口径产物，清完即从脚本删掉本段）。
- 实测真数字（取值 2026-10-15T00:13Z）：本仓 `arena/e207d38d-qself` head `8d90c39` 两条命令输出 —— 总序号 3101、当日序号 0（该提交日期 2026-10-14 22:00 CST，不在当日）；`origin/main` 总提交数 3069。旧口径产物 `Qself-26.10.14.4.277`（id 11900691515，rn=277，事件 `push`）与 `Qself-26.10.14.3.276`（id 11900660871，rn=276，事件 `push`）。
- 会话令牌 DELETE artifact 实测 403（`Resource not accessible by integration`，用不存在的 id 探测端点 `repos/Sumicya/Qself/actions/artifacts/1`），旧口径产物只能由 CI 清理 job 清，故过渡清理写进同一份脚本。
- 出包实测：Build rn=278（head `c5a7877c`，事件 `push`）四作业 `dex`／`apk`／`cleanup-artifacts` success、`cleanup-releases` skipped；产出新口径首包 `Qself-26.10.15.1.3102`（id 11937962375，114728B，run 38659018059）；同一次运行的 `cleanup-artifacts` 按过渡清理把旧口径两只清掉（`.4.277` id 11900691515、`.3.276` id 11900660871，按 id 复核均 404，取值 2026-10-15T00:19:13Z）；删后 artifact 名单只剩 `Qself-26.10.15.1.3102`。PR check（同 head，run 38659024806）`dex`／`test`／`cleanup` 全 success。
- 过渡清理段清完即从 `cleanup.py` 删除（连同只服务它的 `parts_of()`），产物名与守卫条件留在本文件与 `AGENTS.md` 里可核。

## 2026-10-14 — 会话分支出包首次实测；清理按重复当日序号删旧产物；修正已知风险条

- 实测（提交 `dd136e0` 推送后）：Build rn=276（事件 `push`，head `dd136e0`，分支 `arena/e207d38d-qself`）四作业 `dex`/`apk`/`cleanup-artifacts` success、`cleanup-releases` skipped（非默认分支，按其守卫只拦发行对象）；产出 `Qself-26.10.14.3.276`（id 11900660871）—— 会话分支推送出包这条验证路径首次跑通。PR 校验 run 38576144413（同 head）`dex`/`test`/`cleanup` 全 success。
- 清理实证：`cleanup-artifacts` 按「重复当日序号保留总序号最大者」删掉 `Qself-26.10.14.3.274`（id 11864292225，rn=274 的产物，旧算法下当日序号也是 3）；删除后名单（取值 2026-10-14T13:57Z）只剩 `.3.276`。分界点已记入 `AGENTS.md`。
- `AGENTS.md`：修正已知风险条 —— 默认分支实查是 `on: push / pull_request / workflow_dispatch`（`push` 不限定分支、工作流清单只有 `build.yml`），上一版写的「`branches: [main]` 限定」与实查不符，按命令输出改正。

## 2026-10-14 — 会话分支必须能出包（去掉分支限定）；清理失败按 403 与非 403 分口径

- 依据：selfs 分支提交 `a8787a8`（撤回「会话分支不出包」与 `github.ref` 守卫两条，`push` 不限定分支，会话分支推送同样出包用于验证，与默认分支共用同一命名口径与同一条 `run_number` 序列；新增硬口径「写『不许做某事』前先问是不是唯一验证手段」）、`358120a`（清理失败按原因分：403 打 `::warning::`、同一对象连续两次失败才升级阻塞项、403 以外当轮红 job 并点名对象与返回码；先进性节的降级范围收窄为「只允许清理动作的 403 权限缺口」）、`e6c9e8c`（分支去除需差异对比加主人确认；默认授权、责任固定、结尾句式三处开出例外）。
- `.github/workflows/build.yml`：`on.push` 去掉 `branches: [main]` 限定 —— 会话分支推送同样出包，这是唯一的真包验证路径；头注释改新口径（不得接 `pull_request`、不得加 `branches` 限定或 `github.ref` 守卫）；当日序号注释补「会话分支运行一并计入」；`cleanup-releases` 的 `if` 注释写明它只拦在会话分支上动发行对象、不砍出包验证。
- `.github/scripts/cleanup.py`：`delete()` 按原因分口径 —— DELETE 403 打 `::warning::`（写明对象、端点与返回码）后继续；403 以外的失败进 `FAILED`，脚本末尾 `::error::` 逐条点名并 `SystemExit` 红 job。合成测试三条：403 → `FAILED=[]`；500 → `FAILED` 一条；成功 → 无警告无失败。
- `AGENTS.md`：触发条、分支条改写；清理失败分口径按 `a8787a8` 写清；已知风险条补「默认分支仍是旧口径（含 `pull_request` + `branches: [main]`）」；分支去除条改为 `ahead_by=0` 默认授权当轮删、`ahead_by>0` 需取 compare 差异并附可取处/可去处与建议、向主人确认后才删。

## 2026-10-14 — 真删挂到 PR 校验工作流，清理不再永远跑不到

- 依据：selfs 分支提交 `b0594d3`（规范 22260B）：真删只挂出包工作流时，会话支推不上默认分支、`workflow_dispatch` 要 `actions` 写（实测 403）、PR 校验又只读预演 —— 三仓真删一次没触发过（实证 Gself `.4.145`/`.2.147`、Selffont `116`/`117`/`118` 从 03:35Z 挂到 07:11Z）。新条：真删调用点至少一个必须会话可达＝PR 校验工作流清理作业 `actions: write` + `CLEANUP_APPLY: 'true'`；两处同一 `concurrency` group 串行；DELETE 403 打 `::warning::` 降级为预演并继续；只读预演为备用形态；超量对象状态改记「待下一次 PR 校验运行」。
- `.github/workflows/pr-check.yml`：顶部 `env` 的 `CLEANUP_APPLY` `'false'`→`'true'`；清理作业 `cleanup-preview`→`cleanup`、权限 `actions: read`→`actions: write`、步骤名改「按四条判据清理（真删）」、加 `concurrency: {group: qself-cleanup-artifacts, cancel-in-progress: false}`（与 `build.yml` 同组）；头注释与作业注释同步。
- `.github/scripts/cleanup.py`：`emit()` 换成 `delete()` —— `DELETE` 失败不再 `SystemExit`（原实现遇非零退出直接把 job 弄红），改为打 `::warning::` 写明原因并继续；`APPLY=false` 仍只打印、不发请求。
- `.github/workflows/build.yml`：三处与新条文矛盾的注释改口径（顶部第 8–9 行、「`CLEANUP_APPLY`」行内注释、清理作业注释里的「真删只此一处」）。
- `AGENTS.md`：CI 分工条与清理条按新条文改写（`pr-check.yml` 第三作业 `cleanup-preview`→`cleanup`；删掉「真删只此一处」与「待出包工作流首次运行」；记两处同组串行与 fork/403 降级行为）。

## 2026-10-14 — 清理脚本启动打印读到的参数；AGENTS 记号源已知风险

- 依据：selfs 分支提交 `62df936`（清理脚本启动时打印读到的 `SEGMENTS`、`CLEANUP_APPLY`、产物名前缀、保留数与本次 run 所属序列，让预演日志能独立核对，不靠「job success 所以顶部声明生效」推断）、`4e9effb`（自检第 4 项拆词面 + 机制五场景，第 5 项按四条判据扫描并复查上轮阻塞项；阻塞项收尾只许三种状态）。
- `.github/scripts/cleanup.py`：`VERB` 行后加启动参数打印（一行，含 SEGMENTS / CLEANUP_APPLY / PREFIX / KEEP / SELF / TODAY / REPO）；拉回 run 列表后加「本次 run 所属序列」打印（取不到写「未出现在本次拉取的运行列表里」，不静默）。
- 回归干跑（合成数据，与上轮同一份）：启动打印逐项出值；本次 run 210 判为 `.github/workflows/pr-check.yml`；四条判据命中数与上轮一致（口径 1、来源 1、重复当日序号 1、数量 2）；`APPLY=true` 时 DELETE 数 10、`false` 时 0。
- `AGENTS.md`：分界点条后加「已知风险（号源）」—— 默认分支 `.github/workflows/build.yml` 仍含 `pull_request` 触发（实查 main 现文 `on: push / pull_request / workflow_dispatch`），载体是开放 PR #10（head `arena/01a109fa-qself`）；风险存续到本次交付进入默认分支为止，不报「号源已收口」。

## 2026-10-14 — 分界点改记可核对象；清理开关与段数提到工作流顶部声明

- 依据：selfs 分支提交 `bc98bd5`（分界点只引可核对象：run 号、事件、head sha、日期，加删除前 API 名单原文与取值时间；已删 artifact 的名字先按 run 记录核）、`215a796`（回收两条：`run_started_at` 缺失才退回 `created_at`、未完成的 run 不删）、`5dc0d28`（检讨条款整条删除）。
- `AGENTS.md`：分界点那条按 run 记录逐条重核后重写 —— 重复当日序号 6 的是 `rn=272`（事件 `push`）与 `rn=273`（事件 `pull_request`，同 sha 7920b0a2），不是 rn=273/275；`rn=275`（事件 `pull_request`）取号、算版本、测试与出包、上传步骤全 skipped、按 run 反查 artifact 数=0，`.6.275` 从未存在；删除前名单（取值 2026-10-14T03:17Z）`.7.271`、`.6.272`、`.6.273`、`.3.274` 四条，前三条已删，只剩 `.3.274`。同条补上「排序字段缺失才退回 `created_at`、未完成的 run 不删」两条口径。
- `build.yml`：`env` 顶部加 `CLEANUP_APPLY: 'true'`（真删开关），与 `SEGMENTS: '5'` 并排；清理步骤 env 只留 `GH_TOKEN`/`REPO`/`SELF`。`pr-check.yml`：顶部 `env` 加 `SEGMENTS: '5'` 与 `CLEANUP_APPLY: 'false'`（只预演）。两处调用的仍是同一份 `.github/scripts/cleanup.py`，脚本未动。

## 2026-10-14 — 清理判据补齐四条：单一脚本 + PR 校验只读预演，超量对象不再卡在「看不见」

- 依据：selfs 分支提交 `3d7be5b`（清理作业补三条：清理逻辑只写一份脚本，靠 `CLEANUP_APPLY` 开关分出包真删与 PR 只读预演；新增判据「重复当日序号」；run 上限与 artifact 承载解耦——承载保留产物的 run 不删也不计入上限，排序按 `run_started_at`、`id` 只作同刻 tiebreak，日志逐对象打印判据与豁免原因、话术不得与实际分支矛盾）。
- `build.yml`：头注释旧保留数写法（「artifact（5 个）与运行记录（5 条）」）改成规范话术（当日之外每条序列 5、承载不计数、当日豁免）；清理作业从内联脚本改为 checkout + 调 `.github/scripts/cleanup.py`，`CLEANUP_APPLY: 'true'`，权限 `actions: write` + `contents: read`。
- `pr-check.yml`：新增 `cleanup-preview` 作业（`actions: read` + `CLEANUP_APPLY: 'false'`），同一份脚本只打印将删名单与判据理由。死锁解开：会话支跑得到预演，真删仍只在出包工作流一处。
- 预演（live，脚本同一份）：将删 0 个 artifact、0 条 run——artifact 1 个走「当日豁免」，run 12 条里 5 条当日豁免 + 1 条「承载保留产物：不删、不计入上限」+ 7 条 pr-check 当日豁免；逐对象理由与实际分支一致（旧版那句「删除 0 条（全部为当日之外、且不承载保留产物）」的错话术已不存在）。
- 干跑（合成数据，含重复当日序号与承载 run）：将删 5 个 artifact（口径 1、来源 1、重复当日序号 1、数量 2）、5 条 run；`APPLY=true` 时正好发出这 10 条 DELETE，`APPLY=false` 时 0 条；`SEGMENTS=4` 边界按退化四段口径判。
- 文档：`AGENTS.md` 清理条目补四条判据、单一脚本与预演，并记 2026-10-14 当日序号 6 重复的分界点。

## 2026-10-14 — 清理作业按规范三条判据重写（数量 / 命名口径 / 来源）

- 依据：selfs 分支提交 `2dec668`（清理判据三条：数量=当日之外每条序列 artifact 5 个、run 5 条；命名口径=段数等于工作流顶部 `SEGMENTS` 声明值；来源=artifact 的 `workflow_run` 事件是 `pull_request` 一律删。
  数量判据当日豁免（当日序号取自当日运行列表，删当日运行会抽走计数基准），口径与来源不豁免、当轮即删；PR 校验工作流不挂清理作业，它的超量 run 由出包工作流清理 job 一并删；job 跑完打印删了哪几个、各剩几条）。
- `build.yml`：顶部新增 `env: SEGMENTS: '5'`；`cleanup-artifacts` 重写为单步脚本，仍最小 `actions: write`；承载保留产物的 run 不动（删 run 会连带删产物），正在跑的本次不删。
- 干跑（DELETE 全拦截至 stderr，脚本从工作流原文抽出）：live 数据 → 删 0 个 artifact、0 条 run（唯一 artifact 是当日豁免、run 全为当日），与实况一致；
  合成混合数据（7 个旧出包产物 / 7 条旧出包 run / 7 条旧 PR 校验 run / 1 个四段错口径 / 1 个 PR 来源）→ 三判据各命中：删 4 个 artifact（口径 1 个打「段数 4 ≠ 5」、来源 1 个打 `pull_request`、数量 2 个），保留 5 个；删 4 条 run（build 与 pr-check 各超 2 条），剩 build 6 条 / pr-check 5 条（各留 5 + 当日豁免与在跑的本次）；
  `SEGMENTS=4` 边界 → 5 段产物全按口径判据删。干跑抓到一个 bug：清冗余三元时把数量判据的保留/删除分支改坏（超量对象全进保留桶、一个不删），已改成显式分支并重跑三组通过。
- 文档：`AGENTS.md` 清理条目改为三条判据。

## 2026-10-14 — 拆工作流：出包与 PR 校验分开，号段不再被 PR 运行吃

- 依据：selfs 分支 `arena/2d001a47-selfs` 提交 `5e2b978`（版本号只能取出包工作流的 `run_number`；出包与 PR 校验必须是两个工作流文件；
  出包触发只写 `push`（限定默认分支）与 `workflow_dispatch`；PR 校验只写 `pull_request`；调试 CI 的推送不许吃号；同仓不得并存两种取数口径）。
- 根因：`run_number` 在运行创建时就分配、按工作流计数，步骤级 `if: github.event_name != 'pull_request'` 只能挡上传，挡不住吃号。
  实测 `7920b0a2`、`25d56e64` 两个提交各吃两个号（rn 272+273、274+275），rn=273 还是 PR 运行却出了包（当日序号 6 的旧口径产物）。
- `build.yml`：触发收敛为 `push: branches: [main]` + `workflow_dispatch`；删掉全部 PR 守卫与 PR 专用的占位测试步骤；
  当日序号注释改「本出包工作流的运行序号」；清理作业去掉 PR 条件（清理只挂在出包工作流）。
- 新增 `pr-check.yml`：`on: pull_request`、`permissions: contents: read`，两个作业（`dex` 落点核对 + 单元测试用占位版本名），不取号、不出包、不上传、不清理。
- 旧口径产物处置：三笔旧口径产物（id 11861519548、11863836941、11864730478；当日序号序列 7 → 6 → 6 与新口径 3 并存）。会话令牌 `DELETE /repos/Sumicya/Qself/actions/artifacts/<id>` 返回 403，响应头 `X-Accepted-Github-Permissions: actions=write`（缺 actions 写权限类），本会话删除未成；超量产物按职责由出包工作流的 `cleanup-artifacts` 删。复核：三条 `GET /repos/Sumicya/Qself/actions/artifacts/<id>` 全 404，artifact 计数 1 个（只剩新口径 `Qself-26.10.14.3.274`，id 11864292225，114752B）。
- 文档：`AGENTS.md` 版本条目按两工作流重写；`README.md` 符号数 177 → 182 与实际一致；本条记入本文件。

## 2026-10-14 — 堵刷号漏洞：PR 运行只校验不取号不出包

- 依据：selfs 分支 `arena/2d001a47-selfs` 提交 `7f8adb6`（规范：出包运行只有 push 与 workflow_dispatch；PR 运行只跑校验、不取号、不上传 artifact；
  当日序号改为当日（UTC+8）该仓出包运行的序号，从 1 起按开始时间递增、PR 不计入；禁止按 head 提交位次取数；禁止空提交刷号与为触发 CI 而推送）。
- 起因：本仓 `on: push + pull_request` 双触发，同一提交出两个 artifact、吃两个总序号（实测 sha 9afda492 → run 268 + 269，sha e902407b → run 270 + 271），
  一夜把总序号从 220 刷到 271。
- `build.yml`：上传步骤加 `if: github.event_name != 'pull_request'`；算版本步骤与缓存存取步骤只在非 PR 事件跑；
  `apk` 作业任何事件都跑单元测试，PR 事件用占位版本名（不参与编号）并且不出包；`Check libxposed packaging` 同样只在出包运行跑；
  当日序号改数「当日（UTC+8）本工作流的 push / 手动运行」，按 `run_started_at` 升序取本次位次；`checkout` 不再需要 `fetch-depth: 0`（新的当日序号不查提交历史）。
- 干跑核验（拿真实运行数据跑该步脚本）：以 run 272（push）为本次 → 当日序号 2；以 run 270（push）为本次 → 序号 1；
  以 PR 运行 id 为本次 → 列表里找不到 → 打原因退化四段；`run_attempt > 1` 无缓存 → 按口径失败；有缓存 → 复用不重新加号。
- 文档对齐：`AGENTS.md` 版本条目按新口径重写；`README.md` 符号数 177 → 182（与 `tools/symbols.txt` 实际条数一致）。
- 顺带处置：本地沙箱 `.git` 再次被回滚到分支点，按规范 `git fetch` + `git reset --soft origin/arena/e207d38d-qself` 接回，diff 与远端一致。

## 2026-10-14 — 版本步锚 head 提交 + 底栏角标落点

- 版本步修正：当日序号改取「本次 run 的 head 提交」——PR 运行取 `github.event.pull_request.head.sha`，push / 手动运行取 `github.sha`。
  原先按检出提交数，PR 运行的检出是合成合并提交（祖先含默认分支当日提交），同一个 head 在两个事件下被数成两个位次
  （`e902407`：push 事件数出 5、PR 事件数出 7），同一份代码因此出两个当日序号不同的产物；改后两边都是 5。
- 干跑核验（拿真实 run 数据跑该步脚本）：push 事件（run_number 270）与 PR 事件（run_number 271）对同一 head 都得当日序号 5；
  head 不在当日（`49913cc`）→ 打原因退化四段；head 对象缺失且取不回 → 打原因退化四段；`run_attempt > 1` 无缓存 → 按口径失败。
- 底栏角标：按真包 smali 补落点——`TabFrameControllerImpl#updateTabInfo` 取底栏 `QUIBadge` 后调 `setRedNum`，
  `updateNum` 把 `mText` 压成 99+、真值留在 `mNum`；钩子补到 `setRedNum` / `setGrayNum` / `setAIOBarNum` / `onDraw`，
  `tools/symbols.txt` 同步登记，`dexcheck` 182 条 0 红。
- 文档去版号：本文件与 `AGENTS.md` 清掉版号残留，`AGENTS.md` 记「head 提交按事件取」的口径。
- 本地 `.git` 被沙箱回滚到分支点（工作树完好），按规范 `git fetch` + `git reset --soft origin/arena/e207d38d-qself` 接回，diff 与远端一致。

## 2026-10-14 — 跟进全局临时入口（PR #6，head a9fc529）

- 依据：selfs 分支 `arena/2d001a47-selfs`（PR #6）当轮读入；PR 合并后回到默认分支为准。
- 版本：总序号从「run_number − 35」改成直接取本次 run 的 `run_number`（本仓 workflow 未重建，不写基线；重建才写基线相减，未记录按原值不判失败）；
  当日序号取不到时不再失败，退化四段 `yy.m.d.总序号` 并写日志说明；`build.gradle.kts` 与 `build.yml` 的版本校验都放宽到四段/五段。
- 分支：删分支的唯一判据改成相对默认分支 compare `ahead_by=0`；先前按「继承即可删」删掉的 `arena/63fa89b7-qself`、`arena/614127b9-qself`
  已用原 SHA 恢复（`e64c8e65…`、`ae1a5a1f…`），三支会话分支（含 PR #10 的 `arena/01a109fa-qself`）ahead_by 分别为 13/10/2，一律保留并报主人。
- 文档：`AGENTS.md` 去掉同步戳与复制的全局条目，改成规范指针 + 项目事实与限制。
- 跨仓：selfs、qqapk、Selfblox 当前运行记录 0 条、无工作流文件、无待删的已合入会话分支，无需跨仓写操作；selfs PR #6 仍开放。

## 2026-10-13 — 跟进全局「第0版修订」

- 依据：selfs 分支 `arena/7a7f3552-selfs` 提交 `1984b03`（第0版修订：计数改锚 run_number、分支保留 0、清理默认授权）；尚未合入默认分支，按主人指示先行执行。
- `build.yml`：当日序号从「run_number − 当日锚点」改成「head 提交在当日（UTC+8）提交序列中的位次」
  （`git rev-list --count --since="<当日> 00:00:00 +0800" HEAD`，`apk` 作业 checkout 改 `fetch-depth: 0`）；
  停用旧锚（历史成功数、现存 artifact 最大号），删掉对应的兜底检查。
- 运行记录清理改成只留最近 5 条：取消「当日锚点」特例（版本取数不再依赖运行记录），也不再为 artifact 载体破例。
- 分支处理改正：先前按「分支 0 个」删了 `arena/63fa89b7-qself`、`arena/614127b9-qself`，但 `614127b9` 的 2 个提交
  （`83ac6db` 玻璃按压 + 精确角标、`ae1a5a1` 输入行重挂载）在留存线里没有等价补丁（`git cherry` 两个「+」），属于未继承就删。
  已用原 SHA 恢复（`e64c8e65…`、`ae1a5a1f…`），把 `614127b9` 的应用侧改动（`README.md`、`Glass.kt`、`Hook.kt`、`Input.kt`、
  `Looks.kt`、`ProtoTest.kt`、`tools/symbols.txt`）继承进本分支（`4b727a7`，CI 全绿：dex + 测试 + 出包，run_number 261），
  应用侧逐文件与本分支一致后，才按「继承之后去删」重新删掉这两个分支；它的 CI 侧改动早已被本分支的新版取代，未继承。
  `63fa89b7` 的提交全部含于 `arena/01a109fa-qself`（`git cherry` 无输出），继承成立后同样删除；`arena/01a109fa-qself` 因 PR #10 未合并保留。
- 教训两条记进 `AGENTS.md`：删会话分支前先 `git cherry` 确认无未继承提交；恢复远端 ref 会触发一次 push 构建，
  旧分支的旧工作流会在被清理过的记录上算出版本错号（本次出过两个当日序号为 0 的无效产物，已在记录里说明，随保留窗口自然淘汰）。
- 实跑核验：本次推送出包（run_number 257，当日位次 12 = head 提交在当日提交序列中的位次；总序号 222 = 257 − 35），
  运行记录收口到 5 条、artifact 5 个；位次取不到时（head 提交不在当日）会当场失败，已用本地预演验证。

## 2026-10-13 — 跟进全局第0版

- 第0版把规范压到 55 行；对 Qself 的实际变化：保留项新增「运行记录 5 条」，清理权限明确 `actions: write` 只用于删本项目 artifact 与 run。
- `build.yml`：五段版本取数改成只锚定本次 run 的 `created_at`/`run_number`——当日序号用「当日锚点」做减法，
  总序号用固定基线 35（run_number 253 ↔ 218）；删掉历史运行记录后仍然算得准，并加了「号必须大于现存产物最大号」的兜底检查。
- `cleanup-artifacts` 增加运行记录收口：留最近 5 条 + 当日锚点 + 现存 artifact 的载体，其余（含旧工作流遗留）删除；不再需要一次性工具。
- `AGENTS.md`：版本戳改「第0版」，条目按「只记事实、限制、指针」删繁。
- 已核验：删掉 248 条运行记录后，下一次运行 `run_number` 继续递增（254 → 255），出号（当日位次 9、总序号 220）；
  「总序号必须大于现存产物最大号」的兜底检查保留。

## 2026-10-13 — 跟进全局第二十五版修订

- 第二十五版修订（2026-10-13）改了清理权限：`actions: write` 允许用在任意触发事件（含 `pull_request`）的构建 job 上，
  只用于删本项目 artifact，且不得与 `contents: write` 同授。
- 照此把 `build.yml` 的一个 `cleanup` 拆成两个作业：`cleanup-artifacts`（最小 `actions: write`，任何分支的 push 与同仓 PR 都跑，
  本 run 上传成功即收口到 5 个）与 `cleanup-releases`（`contents: write`，只在默认分支，Release 留 1 个、随旧 Release 清专属 tag）。
- `AGENTS.md` 的版本戳更新为「第二十五版修订 · 2026-10-13」，清理条目按新权限改写，并写明运行记录清理不并进常驻 CI。

## 2026-10-13 — 同步全局第二十五版

- `AGENTS.md` 的规范同步段从第十五版迁到第二十五版，删掉与全局相反的「应有只读规范检查」一类表述。
- 删除 `.github/workflows/spec-check.yml`：第二十五版规定不设规范检查工作流，规范由 agent 在会话中核对。
- `build.yml`：`apk` 作业的五段版本改为「当日序号 = 本仓当日已触发、run_id 小于本次的运行数 +1（区分并发），
  总序号 = 历史成功出包运行数 +1」；`run_attempt > 1` 用缓存复用第一次 attempt 的版本号，取不回即失败。
  新增 `cleanup` 作业，只在默认分支出包上传成功后清理数量（artifact 留 5、Release 留 1，随旧 Release 清其专属 tag），
  写权限只授予该作业；其余作业保持只读。
- `app/build.gradle.kts`：本地出包必须显式给 `QSELF_VERSION`（五段格式），缺失即失败，不再用 `0.0.0.0.0` 占位；本地取号规则写在 `AGENTS.md`。
- `AGENTS.md` 写明继承关系：项目条目只做补充和收紧，不另立例外；补上可追溯的本地序号规则。
- 旧工作流的 637 条运行记录一次性清掉（保留 `build.yml` 的运行），项目 artifact 同时收口到最近 5 个。
  清理工具 `clean-old-runs.yml` 随本次分支推送触发，跑完即从仓库撤掉。此处写权限落在分支构建上，
  与清理通用步骤第 1 条「写权限只给默认分支作业」有出入，依据是第二十五版第 99 行「用户明确指示优先于本规范」，
  动作范围仅限运行记录与项目 artifact。副作用一条：2026-09-06 的 `telegram-bot-api-binary`（`push_ci.yml` 的产物，
  该工作流文件早已删除）随它那条运行一起删除，已事前说明。
- 衔接：口径切换后的第一个运行起按新口径取数；历史 artifact 名里的序号只作历史记录，不重算、不回退。
