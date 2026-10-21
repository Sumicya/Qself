# CHANGELOG

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
  - 保持长按菜单 `mSourceView`（`QQCustomMenuExpandableLayout#T`）可见、清零菜单项 `16dp` 上下内边距、恢复己方连发组首 ID、修复外部时间对兄弟视图宽度的压缩，并用 `SafeChain` 与本机撤回过滤兜住防撤回路径；新增设计准则 [`docs/design-principles.md`](docs/design-principles.md) 与架构预览图 [`docs/preview-uniform-5dp-and-architecture.png`](docs/preview-uniform-5dp-and-architecture.png)（保留旧图 [`docs/preview-realigned-layout.png`](docs/preview-realigned-layout.png)）。

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
