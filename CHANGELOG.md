# CHANGELOG

## 2026-10-22 — AndroidLiquidGlass 真实 SDF 边缘高光、连发合并头像恢复与圆角同步、输入栏真正悬浮与沉浸安全区重做、Qself 按钮单/双击退菜单、时间格式码补全与结束进程无弹窗、跨协程头像圆角、四向严格 5dp 边距及原生/自绘 51dp/54dp 统一对齐

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
  - 在 `AIOTitleRelativeLayout.onLayout` 中对 `titleTv`/`tail`/`flipper`/`sub1`/`sub2` 先执行 `MeasureSpec.EXACTLY` 重测再 `layout` 并折叠未映射装饰视图，在 `SecNavBarState` 中将 `QUISecNavBar` 原生左/中/右控件重定位到三段胶囊中心；拦截 `holder.c#a/b/c/d -> 0` 并在 `RowGeometry` 清零 `nickSlot`、`MainNickNameBlock`、`contentSlot` 与 `ExternalStamp` 水平偏移，使昵称、气泡、引用、图片、卡片与时间戳严格共线于 `x = 51dp`（左）/ `right - 54dp`（右）；同步删除旧图并生成架构预览图 [`docs/preview-liquid-glass-and-unified-layout.png`](docs/preview-liquid-glass-and-unified-layout.png)。

## 2026-10-21 — 四向严格 5dp 气泡边距与被回复消息去外框、顶栏返回箭头恢复与三胶囊触控重定向、输入栏异步防回退、二级页与搜索栏适配及长按菜单/撤回修复

- 气泡四向严格 `5dp` 内边距与被回复消息彻底去除内层外框（`Chat.kt`）：
  - 根因定位：QQ 原生 `skin.padding.j#a` / `h#a`、`TextMsgContent#H`、`AIOTextContentComponent#Z1`/`n2` 与 `AIOReplyComponent#C2`/`s2`/`K3`/`L3` 会在内层子视图（`f3()` `AIOReplyLinearLayout`、`G2()` `AIOMixLinearLayout`、`0x7f0a5c31` `ReplyTextRelativeLayout`、`0x7f0a5c11`、`j2()`、`m2()`/`l2()`）上反复重设内边距、四向 `margin` 与引用背景框 `@0x7f0803b8`，且 `android.widget.FasterTextView#mIncludePad` 默认额外增加上下约 `2.5dp` 字体留白，`RunLayout.apply()` 此前又仅在 `连发合并` 开启时才向 `BubbleSurface` 传 `true`。
  - 修复方案：当 `统一气泡` 开启时，将 `skin.padding.j`（`a,b,c,d = 0`）与 `skin.padding.h`（`j,k,l,n,o = 0`，`m = 4dp`）全部归零，仅由最外层 `BubbleSurface` 在 `rootView` 上施加唯一一层 `5dp` 四向内边距与四角 `17dp` 全圆角（连发不切直角）；`InnerClearState` 递归清零并可逆保存内层子视图 `background`、`padding`、`minimumWidth`/`Height`、四向 `MarginLayoutParams`、`TextView.includeFontPadding` 与 `FasterTextView#setIncludeFontPadding(false)`，彻底清除被回复消息 `0x7f0a5c31` 外框与左侧竖条，使被回复块与回复正文融为单一外层玻璃气泡。
- 顶栏安全区按钮重定位、返回箭头恢复与夜间提亮（`Looks.kt`）：
  - 查明 `r/i/dyp.xml` 中 `sm1.u#f`（`0x7f0a2ec2` `ivTitleBtnLeft`）是 `TextView`，其返回箭头图标由 `AIOTitleVB#Z1` 设在 `view.background`（`@0x7f0835a1`）上，而 `sm1.u#d`（`0x7f0a14ea`）是 `1px` 底部分割线而非关闭按钮；现保留 `leftBtn.background` 并隐藏 `1px` 分割线 `u.d`，在夜间模式对 `TextView` 施加 `backgroundTintList = #F2F5FA`、对 `ImageView` 施加 `imageTintList = #F2F5FA`。
  - 清空 `AIOTitleVB#u1` 按旧全宽坐标挂载的 `ViewUtils$a` `TouchDelegate`，在 `ViewGroup#dispatchTouchEvent` 中按 `lastGeom` 的左、中、右三枚悬浮胶囊命中区将触摸事件精确转发给 `leftBtn`/`unreadBadge`、`titleTv`/`centerFlipper` 及右侧操作槽位；将听筒图标（`0x7f0a1630`）内联排入中间胶囊标题右侧，并在 `标题栏侧栏精简` 开启时自动折叠动态挂入的 VIP/火花铭牌。
- 输入栏与底部快捷栏防异步回写及全胶囊化（`Input.kt`）：
  - 对 `com.tencent.mobileqq.aio.input.inputbar.compat.b` 的 `handleUIState`、`e(boolean)`、`g()`、`i(adorn.e)`、`n(boolean, boolean)` 全路径挂钩，防止状态切换与多行展开时冲掉 `AIOEditText` 的 `Glass` 胶囊与透明底栏容器；
  - 挂钩 `com.tencent.mobileqq.aio.input.inputbar.compat.e$b#f(Context, int, int)`，使 `AIOInputSendBtn`（`QUIButton`）在原生圆角计算阶段即返回 `height / 2`（未测量时 `18dp`），配合 `capsule()` 轮廓与 `SmoothCorner` 实现完整药丸形；
  - 挂钩 `com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB` 的 `onCreateView`、`handleUIState`、`x1`、`y1`、`s1` 及 `PanelIconLinearLayout#f`，拦截 `x1` 异步 `post` 的 `d1 -> y1` Runnable 将底部快捷栏刷回实色背景，并在夜间模式下提亮全部输入栏与快捷栏图标（`#E8EEF7`）。
- 二级页面导航栏（`QUISecNavBar`）、搜索栏（`QUISearchBar`）及长按菜单/撤回修复：
  - 新增 `SecNavBarState`（适配聊天设置、聊天记录、转发、通用设置等二级页顶栏 `QUISecNavBar`）与 `SearchBarState`（适配 `QUISearchBar`），延续三胶囊玻璃顶栏、左返回胶囊触控重定向、搜索框全胶囊化与夜间高对比度提亮；
  - 保持长按菜单 `mSourceView`（`QQCustomMenuExpandableLayout#T`）可见、清零菜单项 `16dp` 上下内边距、恢复己方连发组首 ID、修复外部时间对兄弟视图宽度的压缩，并用 `SafeChain` 与本机撤回过滤兜住防撤回路径；新增设计准则 [`docs/design-principles.md`](docs/design-principles.md) 与架构预览图 [`docs/preview-liquid-glass-and-unified-layout.png`](docs/preview-liquid-glass-and-unified-layout.png)。

