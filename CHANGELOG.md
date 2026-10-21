# CHANGELOG

## 2026-10-21 — 修复长按菜单空白/截断与撤回崩溃、恢复四角全圆角与统一 5dp 内边距、修复图片/表情压窄、恢复己方组首 ID 与去掉引用竖条、夜间模式全局提亮及安全区重定位

- 修复长按菜单空白、文字下部截断与点击撤回崩溃（全仓审查）：查明 `QQCustomMenuExpandableLayout#T` 在 QQ 9.2.10 中是聊天列表 `mSourceView`（`RecyclerView`）而非分割线，移除对 `T` 的 `View.GONE` 并确保其 `VISIBLE`，仅隐藏 `host` 直接子级 `1px` 分割线 `View`；在 `H()` 后置阶段清零原生 `q()->r()/s()/t()` 覆写的 `D = 16dp` 上下内边距并关闭 `includeFontPadding`，保持 `C = 220dp` 防止 `onMeasure`（`2 * C`）裁切纵向菜单；`Hook.kt` 引入 `SafeChain` 兜住 `chain.proceed()` 后置异常，防撤回跳过本机主动触发的撤回推送并以 `runCatching` 保护 JNI 动态代理回调，撤回变灰字（`template.m` / `msgType = 5`）时立即解绑 `RunLayout` 与 `RunAvatars`。
- 气泡四角全圆角、统一 5dp 内边距、去掉引用左竖条与恢复己方组首 ID：取消连发气泡直角切割，全部消息保持 `17dp` 四角全圆角（`nagramBubbleRadii`）；气泡四边内边距统一设为 `5dp`（`BubbleSurface` + `skin.padding.j#a` / `h#a`），清零内层包装视图冗余内边距与 `57dp × 65dp` 最小宽高锁；`ReplyQuoteBar` 去掉左侧 `3dp` 竖条，仅保留圆角半透明底块；移除 `RunLayout.apply()` 中对 `self` 昵称的强制 `View.GONE` 并在 `RowGeometry` 定位己方 `msgNick`，使自己消息在连发组首同样右对齐显示头衔+昵称 ID、续条折叠。
- 修复外部时间导致图片/表情/卡片/短文本水平压窄：`BaseContentComponent#d1` 向垂直 `BubbleLayoutCompatPress`（`WRAP_CONTENT`）添加内容根时默认赋 `width = MATCH_PARENT`，加入 `WRAP_CONTENT` 的 `ExternalStamp` 后触发 `LinearLayout.measureVertical` 的 `allFillParent = false` 与 `forceUniformWidth`，将内容根压窄至时间戳宽度；现将同容器兄弟内容根的 `MATCH_PARENT` 转为 `WRAP_CONTENT` 并按收发方向（他人 `START`、自己 `END`）对齐，关闭或复用时完整还原。
- 安全区组件重定位与夜间模式全局提亮：去除顶/底实色安全区背景后将聊天 `RecyclerView` 设为 `clipToPadding = false` 并为输入区补齐安全边距；提亮夜间模式玻璃材质底色与描边（`KernelBar` / `Glass` `0x7A2A3240` + `in_brightness = 0.08f`），并主动提亮顶栏按钮/标题（`HeaderState`）、输入栏与底部快捷图标（`NativeInput` + `AIOShortcutBarVB`）、气泡描边、外部时间及菜单图文对比度；仓库新增渲染预览图 [`docs/preview-realigned-layout.png`](docs/preview-realigned-layout.png)。

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
