# CHANGELOG

## 2026-10-23（一）— 修复浅色模式设置按钮白底白线不可见与底栏玻璃被覆盖、遵嘱移除侵入式下展开动画、实装获准的 6 项新功能（气泡圆角/边距滑条、顶栏偏移滑条、双击消息快速回应、语音倍速记忆与波形直拖、原生表情/相册/+面板玻璃化、长按头像 @ 微震反馈）

- 像素级定位并修复真机浅色模式截图（`IMG_20261023_121615.jpg`）中的 3 项底栏与设置按钮问题（`Knob.kt`、`Looks.kt`）：
  - **修复 `MenuGlyph` 浅色模式白底白线不可见**：查明 `android.graphics.drawable.Drawable#setTintList` 默认是空实现（no-op），而 `MenuGlyph` 未重写 `setTintList` 且将 `paint.color` 硬编码为 `-1`（`#FFFFFF`），导致在浅色模式 `#FDFDFD`（`253, 253, 253`）圆钮上画出纯白（`255, 255, 255`）线条；现重写 `MenuGlyph.setTintList(tint)` 并在 `MenuGlyph.draw(canvas)` 中按 `tint?.defaultColor ?: monet(night(), 0xFF) ?: if (night()) 0xFFFFFFFF else 0xFF1C1C1E` 动态着色；
  - **修复 `KNOB` 外圈 16dp 灰黑环阴影**：将 `knob(bar)` 的 `elevation` 从 `16 * dp` 下调为与 `float(bar)` 一致的 `10 * dp`，并显式设置 `outlineAmbientShadowColor = if (night()) 0x33000000 else 0x1A000000` 与 `outlineSpotShadowColor = outlineAmbientShadowColor`；
  - **修复左侧底栏胶囊被 QQ 主题二次覆盖为 `#F5F5F5` 实色背景**：在 `BarState` 中持久持有 `val glass = Glass(bar, kernelBar = true) { selectedTab(bar) }`，并在 `settle(bar)`、`state.drawListener` 与 `ViewGroup.dispatchDraw` 中守护 `bar.background = state.glass`，防止 QQ 主题/皮肤加载器在 `floated.add(bar)` 之后覆写 `bar.background` 导致底栏失去液态玻璃与选中页签胶囊。
- 遵照用户「下展开重做 要不就不要展开/下展开」移除侵入式页面/列表展开动画（`Looks.kt`、`Qself.kt`）：
  - 移除 `AppearanceObserver.onPreDraw()` 中的 `bindDownwardListExpand(decor)` 以及 `animateInPlaceMenuExpand`、`animateDownwardListItem`、`overridePendingTransition` 钩子，保持原生页面切换与 `RecyclerView` 滚动完全流畅。
- 实装用户批准（「都可以加」）的 6 项新功能（`Chat.kt`、`Input.kt`、`Looks.kt`、`MessageStyle.kt`、`Knob.kt`、`Qself.kt`、`tools/symbols.txt`、`tools/ui_paths.py`）：
  1. **双击消息快速回应（`双击回应`）**：挂钩 `com.tencent.qqnt.aio.adapter.api.impl.TextPreviewApiImpl#openTextPreview` 与 `TextPreviewApiNtImpl#openTextPreview`，提取 `AIOMsgItem#getMsgRecord()` 并调用 `com.tencent.qqnt.aio.adapter.api.impl.AIOEmoReplyApiImpl#setEmojiLikes(MsgRecord, "76", 1, true, null)` 直接贴上快捷回应表情（👍 `76`），不再弹出全屏文字预览页；
  2. **自定义气泡圆角半径（`0~32dp`，默认 `17dp`）与气泡四周内边距（`0~16dp`，默认 `5dp`）滑条**：新增 `BUBBLE_CORNER`、`BUBBLE_PADDING` 与 `SeekBar` 滑条，实时驱动 `nagramBubbleRadii` 与 `BubbleSurface.apply`；
  3. **顶部安全区胶囊距顶偏移滑条（`-12~16dp`，默认 `1dp`）**：新增 `HEADER_TOP_OFFSET` 与 `headerTopOffsetDp()`，移除 `nagramHeaderLayout` 对 `pillTop` 的 `coerceIn(0, ...)` 正值截断，支持在设置面板自由上移或下调顶栏三胶囊位置；
  4. **原生表情 / 相册 / 「+」附件面板玻璃化（`PanelGlassState`）**：挂钩 `com.tencent.mobileqq.aio.panel.emoticon.c#onCreateView`、`com.tencent.mobileqq.aio.panel.photo.PhotoPanelVB#onCreateView` 与 `com.tencent.mobileqq.aio.panel.b#onCreateView`，为底部抽屉面板挂载 `20dp` 圆角无描边 `Glass` 液态玻璃背景；
  5. **语音消息倍速播放记忆与进度条直拖（`语音倍速记忆`）**：挂钩 `com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttAudioPlayerManager`（静态字段 `h:F` 及方法 `w`、`E`、`C8`）跨条记忆语音倍速，并在 `PttAudioWaveView` 构造后调用 `setCanSupportSlide(true)` 开启波形条直接横向拖动；
  6. **长按头像直接 `@` 并附带轻触感反馈**：挂钩 `com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent#w1`，在长按连发粘性头像或普通头像触发 `@` 时调用 `performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)`；同步在同一提交中删除旧图并生成新预览图 [`docs/preview-light-mode-fix-and-custom-sliders.png`](docs/preview-light-mode-fix-and-custom-sliders.png)。

## 2026-10-22（二）— 全仓撤除描边并恢复长按底栏径向高光、玻璃模糊降至 1dp 并加滑条、无强限 5dp 消息内边距与连发头像合并重做、剥离安全区背景并上移顶栏按钮、修复菜单点击失灵及重绘全部原生页面/列表展开动画

- 全仓撤除描边（`Paint.Style.STROKE` 归零）、恢复长按底栏同款径向高光并移除人工限制（`KernelBar.kt`、`Glass.kt`、`Knob.kt`、`Looks.kt`、`Chat.kt`、`MessageStyle.kt`）：
  - 彻底移除 `KernelBar.kt` 中的 `DEFAULT_HIGHLIGHT_SHADER`、`highlightPaint`（`Paint.Style.STROKE`）与 `Chat.kt` 中 `NagramBubbleDrawable` 的 `stroke` 描边画笔，全仓源码 `Paint.Style.STROKE` 数量归零；
  - 对照 `Kyant0/AndroidLiquidGlass`（`65ab177e`）`InteractiveHighlight.kt`，在 `KernelBar.material()` 中统一使用 `BlendMode.PLUS` 叠加 `0.08f * hp` 底光与 `INTERACTIVE_HIGHLIGHT_SHADER`（`0.15f * hp`，`radius = min(w, h) * 1.5f`）径向柔光高光，并将 `Knob.pressMotion` 与 `HeaderPillsDrawable.onTouch` 的 2D 触摸坐标 `(x, y)` 接入 `kernelTouchDown(x, y)` / `kernelTouchEnd()`；
  - 审查并移除代码中的人工限制：去除 `nagramBubbleRadii` 与 `NagramBubbleDrawable` 对圆角半径的 `heightPx / 2`、`b.width() / 2f` 强行截断，去除 `avatarCorner` 的 `50%` 上限截断，去除 `validTimePattern` / `messageTime` / `EditText` 的 `80`/`120` 字符长度限制，去除 `compactBubblePadding` 的 `minOf(l, pad5)` 截断。
- 玻璃模糊默认降至 `1dp` 并在设置面板新增 `0~15dp` 模糊滑条（`Glass.kt`、`KernelBar.kt`、`Knob.kt`）：
  - 新增偏好项 `GLASS_BLUR = "glass_blur_dp"` 与 `glassBlurDp(): Float`（默认 `1f`），`KernelBar.prepare()` 按 `val blurRadius = (glassBlurDp() * dp).coerceAtLeast(.1f)` 动态构建 `RenderEffect.createBlurEffect` 并纳入 `baseKey` 缓存键；
  - 在 Qself 设置面板新增「玻璃模糊：1dp（默认 1dp）」`SeekBar`（`0~15dp`），拖动释放实时刷新全部玻璃组件，「恢复默认」同步重置为 `1dp`。
- 重做消息内边距（清零皮肤最小宽高锁、不强限圆角与边距）与连发头像合并（`Chat.kt`、`tools/symbols.txt`）：
  - 挂钩 `com.tencent.mobileqq.aio.msglist.holder.skin.AIOBubbleSkinInfo$BackgroundImageInfo#f()`（最小高度）与 `#g()`（最小宽度）在 `统一气泡` 开启时返回 `0`，并在 `BubbleSurface` 中同步调用 `com.tencent.fastertextview.wrapper.a#j(0)` / `#k(0)` 清零 `FasterTextView` 最小宽高，同时停止在 `BubbleSurface` 中强行抹除内层子视图的正常布局 `margin`，使最外层 `BubbleSurface` 的四向 `5dp` 内边距与 `17dp` 全圆角真实生效；
  - 修复连发合并头像：`RunLayout.apply(continued, enabled)` 在 `enabled && continued` 时立即隐藏续条原生头像；`follows(prev, cur)` 兼容 `msgTime == 0` 并将同日连续消息合并间隔放宽至 `Long.MAX_VALUE`（不再因超过 5 分钟断组），`adapter#n0` 在 `chain.proceed()` 后二次执行 `regroup` 即时同步相邻行状态；`RunAvatars.onPreDraw()` 仅在 `host.clipToPadding == true` 时收缩 `clip`，并在连发组滚入顶/底悬浮栏透出区时双层回退计算粘性坐标 `y`，且在无浮绘源时仅恢复组首头像、绝不恢复续条重复头像。
- 剥离顶/底安全区背景、上移顶部安全区按钮（缩小上部距离）并彻底修复菜单点不出来（`Looks.kt`、`Knob.kt`、`Input.kt`）：
  - 挂钩 `AIOTitleVB` 的 `Y1`/`z1`/`A1`、`AIODefaultTitleContainerVB#d`（`onCreateView`/`handleUIState`）以及 `ImmersiveTitleBar2`（`initUI`/`resetBkColor`/`onMeasure`）、`ImmersiveTitleBar`（`a`/`onMeasure`）、`NTImmersiveTitleBar`（`a`/`onMeasure`），彻底剥离顶部安全区背景；将 `AIOTitleVB` 外层 `sm1.u#k`、`sm1.u#a` 与 `QUISecNavBar` 的顶部安全区内边距缩减 `8~10dp`、将 `AIOTitleRelativeLayout` 最小高度收紧至 `50dp` 并以 `topOffsetDp = 1f` 上移三段玻璃胶囊与内部按钮；
  - 根治「点菜单都点不出来」：① 移除 `Knob.kt` `knob(bar)` 中每帧 `onPreDraw` 执行的 `if (decor.findViewWithTag<View>(SHEET) != null) old.bringToFront()`（该调用在面板打开后每帧触发 `decor.requestLayout()` 导致 `AppearanceObserver.onPreDraw()` 返回 `false` 死循环吞掉帧与点击）及 `350ms` 关闭防抖，改为为 `knob` 设置 `16dp` `elevation` 并在 `AppearanceObserver.onPreDraw()` 中增加单次 `cancelledOnce` 防护；② 移除 `HeaderState.dispatchPillTouch` 与 `SecNavBarState.dispatchPillTouch` 中错误的 `!(x in target.left..target.right && y in target.top..target.bottom)` 排除条件，新增 `findClickableTarget` 向上解析可点击祖先与 `ACTION_UP` `performClick()` 兜底，确保点击胶囊内任意位置均稳定弹出菜单。
- 重绘全部原生页面动画为原地展开菜单 / 下展开列表（`Looks.kt`、`Qself.kt`）：
  - 新增默认开启功能 `页面动画`（`::pageAnimations`），拦截 `Activity#overridePendingTransition(0, 0)`、`Instrumentation#callActivityOnStart`、`Activity#finish`、`Dialog#show` 与 `PopupWindow#showAtLocation`/`showAsDropDown`，对新进入页面与弹窗容器执行 `pivotY = 0` 原地缩放展开菜单动效（`scale 0.92 -> 1.0, alpha 0 -> 1, 220ms`），并对页面内 `RecyclerView` / `AbsListView` 挂载子视图自上而下展开列表动效（`translationY -16dp -> 0, scaleY 0.92 -> 1.0, 190ms`）；同步删除旧图并生成新架构预览图 [`docs/preview-light-mode-fix-and-custom-sliders.png`](docs/preview-light-mode-fix-and-custom-sliders.png)。

## 2026-10-22（一）— AndroidLiquidGlass 真实 SDF 边缘高光、连发合并头像恢复与圆角同步、输入栏真正悬浮与沉浸安全区重做、Qself 按钮单/双击退菜单、时间格式码补全与结束进程无弹窗、跨协程头像圆角、四向严格 5dp 边距及原生/自绘 51dp/54dp 统一对齐

- 移植 `Kyant0/AndroidLiquidGlass`（`65ab177e`）真实 `DefaultHighlightShaderString` AGSL 边缘高光（`KernelBar.kt`）：
  - 移除背离上游的实色底色+描边做法，恢复 `LiquidBottomTabs` `40%` 液态玻璃基底（夜间 `0x66121212` / 日间 `0x66FAFAFA`，`in_brightness = 0f`，`in_saturation = 1.5f`）；
  - 精确移植 `Shaders.kt` `DefaultHighlightShaderString`（`gradSdRoundedRect` 圆角矩形 SDF 梯度、`angle = 45°` 法线点积、`falloff = 1f`、`Color.White.copy(alpha = 0.5f)`）与 `Highlight.Default`（`width = 0.5dp`，在 `rounded` 裁切路径内以 `strokeWidth = ceil(0.5dp) * 2f`、`BlurMaskFilter(0.25dp, NORMAL)`、`BlendMode.PLUS` 加色绘制），并新增纯函数 `liquidHighlightIntensity` 供 JVM 单元测试校验。
- 修复连发合并头像失效与消除浮绘/原生分裂（`Chat.kt`）：
  - `capture(binder, row, run)` 首行先调用 `run.layout?.dispose()` 释放旧 `RunLayout`，且 `RunVisibility(view)` 优先继承 `runVisibilities[view]?.native ?: view.visibility`，根除重绑或二次 `capture` 把已被隐藏的 `View.INVISIBLE` 误存成 `native` 导致连发组找不到 `visibility.native == VISIBLE` 头像源而整组消失的问题；
  - `RunAvatars.onPreDraw()` 在 `host.clipToPadding == false` 时仍强制按 `host.paddingTop .. host.height - host.paddingBottom` 裁切粘性区间，`RunAvatars.draw()` 在开启 `头像圆角` 时按 `avatarCorner` 圆角路径裁切 `canvas`，并通过 `notifyChatAvatarsUpdated()` 在异步头像加载完成时立即刷新浮绘层。
- 实现输入栏真正悬浮与底部安全区重做（`Input.kt`）：
  - 查明 `AIOMsgListVB#K3`/`#L3` 将顶栏与底栏高度设为父容器 `AIOMsgListGroup`（`M2()`）的 `paddingTop`/`paddingBottom` 导致 `AIORecycleView`（`S2()`）被硬截断在输入栏上方；现挂钩 `AIOMsgListVB` 的 `U0`、`v0`、`K3`、`L3`，将 `M2()` 的上下内边距迁移至 `S2()`（`AIORecycleView.clipToPadding = false`、`M2().setPadding(left, 0, right, 0)`），使聊天消息真正滚入悬浮顶栏与悬浮输入栏玻璃下层；
  - 挂钩 `AIOInputExpandableVB`（`onCreateView`、`handleUIState`、`o1`、`p1`、`f1`）、`BottomBlurVB`（`onCreateView`、`handleUIState`、`h1`、`i1`、`k1`）、`AIOShortcutBarVB`（`onCreateView`、`handleUIState`、`x1`、`y1`、`d1`、`s1`）以及沉浸式安全区 `AIOChatBackgroundApiImpl#setImmersiveBottomDrawable`、`com.tencent.mobileqq.qui.b#g`、`RFWNavigationBarImmersiveHelper#fillNavigationBarBackgroundView`，清除底部实色背景条并按导航手势安全区重定位悬浮胶囊。
- 修复双击/点按 Qself 按钮退不出菜单、补全时间格式码（含 `k`/`K`）并移除结束进程确认弹窗（`Knob.kt`、`MessageStyle.kt`）：
  - 在 `sheet(anchor)` 与 `knob(bar)` 中调用 `anchor.bringToFront()` 使 Qself 按钮始终位于全屏 `dim` 遮罩之上，挂载 `TAG_SHEET_CLOSE` 与 `350ms` 防抖，单击或双击按钮均直接收起面板；
  - `TIME_FORMAT_GUIDE` 完整列出 `yyyy/yy 年 · MM/M 月 · dd/d 日 · E 星期 · HH/H 0-23时 · kk/k 1-24时 · hh/h 1-12时 · KK/K 0-11时 · a 上下午 · mm/m 分 · ss/s 秒 · SSS/S 毫秒`；
  - 移除「结束进程」多余的 `AlertDialog` 确认弹窗，点击直接执行 `Process.killProcess(Process.myPid())`，并在面板说明中写明 `（结束进程只结束当前进程）`。
- 修复普通聊天头像圆角、四向严格 `5dp` 气泡内边距及顶栏/聊天全组件 `51dp`/`54dp` 统一对齐（`MessageStyle.kt`、`Chat.kt`、`Looks.kt`）：
  - 用同步 `WeakHashMap` 集合 `aioAvatarOptions` 与 `aioProDrawables` 跨 `com.tencent.qqnt.avatar.impl.engine.a#c`/`#d` 协程线程与 `QQProAvatarDrawable#onUpdate`/`#C`/`#x` 传递聊天头像上下文，拦截 `meta.b$a#a` 剥离 `"vas_special_shape_mask"`，重置 `RoundRectImageView#mLastRadius = -1` 并附加 `capsule(radius)` 轮廓裁剪；
  - 查明 `TextMsgContent#e(ai)` 在 `H(ai)` 之后还会调 `F -> r/s` 用 `a$c`（`16dp`/`12dp`）覆写 `d: LinearLayout` 与 `e: wrapper.a`；现递归清零气泡全部内层子视图与 `wrapper.a#l(0,0,0,0)`，全量挂钩 `TextMsgContent#e`/`#H`/`#r`/`#s`/`#A`/`#F`、`padding.j#a..e`、`padding.h#a`/`#j..o`，确保四向内边距严格为 `5dp`；
  - 在 `AIOTitleRelativeLayout.onLayout` 中对 `titleTv`/`tail`/`flipper`/`sub1`/`sub2` 先执行 `MeasureSpec.EXACTLY` 重测再 `layout` 并折叠未映射装饰视图，在 `SecNavBarState` 中将 `QUISecNavBar` 原生左/中/右控件重定位到三段胶囊中心；拦截 `holder.c#a/b/c/d -> 0` 并在 `RowGeometry` 清零 `nickSlot`、`MainNickNameBlock`、`contentSlot` 与 `ExternalStamp` 水平偏移，使昵称、气泡、引用、图片、卡片与时间戳严格共线于 `x = 51dp`（左）/ `right - 54dp`（右）；同步更新架构预览图 [`docs/preview-light-mode-fix-and-custom-sliders.png`](docs/preview-light-mode-fix-and-custom-sliders.png)。

