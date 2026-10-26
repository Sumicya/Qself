# Qself

给 NT QQ 的一个 LSPosed 模块：**自由、简单、现代、原生**。功能默认开启，实际生效受 QQ 版本与对象生命周期限制；不联网、没有第三方库、没有一个 `.so`。
开关位于 QQ 主页底栏旁独立的 76×64dp 玻璃三线设置短胶囊中。菜单在入口附近展开，宽不超过 360dp、高不超过 520dp；自动避让系统栏和键盘，空间不足时缩小并在安全区内定位。单列列表、状态说明与操作按钮都可在面板内部滚动。
菜单、输入区与底栏共享模糊、折射和罩色路径，按用途保留不同圆角，不把阅读面板做成大胶囊。勾选只表示配置已开启，✗ 表示当前进程记录了安装失败，不表示效果已经验证。部分功能需要重进页面或重启 QQ；恢复默认同样受此限制。“结束进程”直接结束当前进程，不保证 QQ 其他进程退出。偏好存进 QQ 自己的 SharedPreferences。

## 要求

只对着一台机器写：QQ 9.2.10 · Android 16 · LSPosed 2.x（libxposed API 102）。类名、方法名全部写死，
但每一个都在 CI 里拿真 dex 逐条核过（[`tools/symbols.txt`](tools/symbols.txt)，702 条）——换版本先看那张表红哪条，别猜。

## 功能

外观（照着 [NagramXF](https://github.com/Keeperorowner/NagramXF) 那套长相来）
- 首页底栏按 [KernelSU 固定源码](https://github.com/tiann/KernelSU/tree/3186a2fe2107f7170e06dab7aa157eb10ae7759f/manager/app/src/main/java/me/weishu/kernelsu/ui/component) 与 [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6) `InteractiveHighlight` 移植到原生 View：64dp 栏 / 最小 76dp 页签 / 56dp 指示器、默认 `1dp` 模糊（可在面板通过「玻璃模糊」`0~15dp` 滑条实时调节）、按压色散、**全仓零描边（`Paint.Style.STROKE` 全部撤除）**、长按/触摸驱动 `INTERACTIVE_HIGHLIGHT_SHADER` + `BlendMode.PLUS` 径向柔光高光（`0.08f * hp` 底光 + `0.15f * hp` `smoothstep(r, r*0.5, d)` 径向高光，`r = min(w, h) * 1.5f`）和解析弹簧。`BarState` 持久持有 `Glass` 并在 `settle(bar)`、`layoutListener` 与 `OnPreDraw` 中强制守护 `64dp` 高度与 `strip` 四周 `4dp` 对称内边距（防 `TabFrameControllerImpl#checkEnableTabAnim` 将底栏重置回 `54dp` 导致 `56dp` 选中胶囊向下溢出），`fit(tab)` 清零 `TabView` 四向内边距并将 `customView` 设为 `MATCH_PARENT × MATCH_PARENT`（`56dp`）、`TabDragAnimationView` `paddingTop = 8dp`、`mTabTextView` `bottomMargin = 4dp`，使未读数、图标与文字三层垂直分离且水平居中于页签中心，同时通过 `centeredBarTranslationX` 与 `drawListener` 同步 `knob(bar)` 使 `[底栏 + 12dp 间距 + Qself 按钮]` 整体在屏幕水平正中；右侧设置短胶囊 `MenuGlyph` 重写 `setTintList` 并按深浅色自动取色（浅色 `#1C1C1E` / 深色 `#FFFFFF` / Monet 强调色），且与底栏统一采用 `10dp` 柔和阴影（浅色 `0x1A000000` / 深色 `0x33000000`）。**未获本轮设备复验，不宣称一比一验收完成**；差异与证据见 [移植记录](docs/kernel-port.md)。
- **聊天顶栏、主页顶栏与二级页三胶囊布局（按 NagramXF `ActionBar.java` + `ChatActivity.java` 彻底剥离顶栏安全区背景、胶囊外间隙触摸直通与消息全屏穿底）**：照 NagramXF `ActionBar.setupGlass` + `ChatAvatarContainer`（`p = 6dp`、`s = 46dp`、`r = 23dp`、通过 `reducedSafeTop` 动态缩减 `sm1.u#k` / `sm1.u#a` / `QUISecNavBar#x()` / `MainResourceConfig#n` 顶部安全区高度）接入 `AIOUtil#b`/`A`/`C`/`D`（强制全屏透明叠层模式）、`AIOTitleVB`（`sm1.u` / `sm1.v` / `sm1.w` / `CenterVB`，覆盖 `H1`/`I1`/`w1`/`P1`/`X1`/`Y1`/`Z1`/`z1`/`A1`/`D1`/`C1`/`R1`/`S1`/`W1`/`h1`/`l1` 拦截 UI Handler 异步重设 `#25272d` 实色背景并隐藏 `sm1.u#d` `dividerLine` 与 `sm1.u#q` `upMask`）、`TopBlurVB`（`com.tencent.mobileqq.aio.reserve1.blur.f` `d: sm1.t -> a: QQBlurViewWrapper`）、`MaskVB`（`com.tencent.mobileqq.aio.reserve1.mask.b` `d: sm1.y -> a: View`）、`AIOMsgListGroup` + `EdgeTransparentView`（`h`）+ `AIORefreshLayout`（`AbsMsgListVB#m1`）+ `AIORecycleView`（`AIOMsgListVB#S2`）（全链路解裁切并清零 `EdgeTransparentView` 的 `i`/`m`/`D` 渐隐擦除，使消息全屏 `y = 0..2800` 顺滑滚过悬浮胶囊与状态栏下方）、`ViewGroup.dispatchTouchEvent`（胶囊外间隙直接返回 `false` 透传给底层消息列表滑动）、主页 `MainResourceConfig#n`/`p` + `TitleAreaHeadLayout#k` + `SimpleModeHelper#u`/`A`（隐藏 `0x7f0a6596` `mStatusTitleDivider`）+ `SkinnableRecycleView#dispatchDraw`（`f = false`）与二级页导航栏 `QUISecNavBar`（`SecNavBarState`，覆盖 `S`/`T`/`U`/`W`/`X`/`C`/`Q`/`R`/`Z`/`updateView`/`setBlur`/`setTitleBlurAlpha` 并清除 `Z` 按钮圆底），关闭或重置完整还原（待真机验收）。**顶栏安全区滑条**：`reducedSafeTop` 旧实现带 `origTop * 0.72f` 下限，真机 `statusBars=[0,141,0,0]` 时只能把 141px 压到 109px（等于没去掉），现已改为纯比例策略（见下条）。
- **顶栏安全区滑条（`顶栏安全区` `0~100%` + `顶栏上下微调` `-24~+48dp`，默认 `0%` 完全去掉）**：面板「消息外观」底部新增两条滑条，`reducedSafeTop(origTop, dp)` 直接按 `safeTopPercent()` 取百分比再加 dp 微调，去掉了旧的「至少保留七成」下限（`ProtoTest` 断言 `reducedSafeTop(141, 1.9761429f) == 0`）。主页 `Conversation` 不再依赖 `MainResourceConfig#n` 而是由 `HomeSafeArea` 直接把 `0x7f0a3245` 头像 `topMargin`（真机 `68px`）、`0x7f0a6cfa` 顶栏容器 `translationY` 与 `0x7f0a0e96` 会话列表 `paddingTop`（真机 `167px`）三者同步上移同一个 `delta`；AIO 消息列表顶部内边距经 `aioSafeTopRemoved()` 同步减去顶栏被去掉的安全区像素数（真包 `238 = 141 + 97`），保证标题栏与第一条消息之间不留空档；`MainResourceConfig#n`、AIO `outer`/`root` 与 `QUISecNavBar` 的每次安全区换算都会写入全量日志（`MainResourceConfig#n orig=…px -> …px`），便于真机核对；AIO 顶栏完成 `onLayout` 后（3 秒节流）自动抓一次带真实尺寸的视图树快照，不再只抓到预加载阶段的 `0×0` 空树（快照活动名是 `SplashActivity` **不代表停在主页**，QQ NT 的聊天页就以它为主 Activity 承载）；每次快照同时打一行 `AIO 几何` 账（状态栏高度、顶栏容器 `paddingTop`、标题栏与消息区的屏幕 `y`、仍开着 `fitsSystemWindows` 的祖先类名与 `paddingTop`）。**已由 `26.10.24.3.3165` 的 10 份真机日志确认生效**：`MainResourceConfig#n orig=95px -> 0px`、`AIO 顶栏 outer 安全区 141px -> 0px`、`0x7f0a6cfa b=[0,0,1272,99]`（原 `167`）、`0x7f0a3245 mar=[0,0,0,0]`（原 `[0,68,0,0]`）、`0x7f0a0e96 pad=[0,99,0,107]`（原 `167`）。
- **原生表情 / 相册 / 「+」附件面板玻璃化（`PanelGlassState`）与共享轴切换过渡（`页面过渡动效`）**：接入 `com.tencent.mobileqq.aio.panel.emoticon.c#onCreateView`、`com.tencent.mobileqq.aio.panel.photo.PhotoPanelVB#onCreateView` 与 `com.tencent.mobileqq.aio.panel.b#onCreateView`，将底部弹出的表情、相册与「+」附件面板容器替换为 `20dp` 圆角无描边液态玻璃材质；在 `TabFrameControllerImpl#updateUIAfterTabChange` 与 `AIOMsgListVB#onCreateView` 中实装纯 `RenderThread` 共享 X 轴微缩淡入切换过渡（`runSwitchTransition`，按新旧 Tab 索引差计算 `±18dp` 方向、`scale 0.985 -> 1.0`、`alpha 0.74 -> 1.0`、`PathInterpolator(0.22f, 1f, 0.36f, 1f)`，无 Y 轴下展开）。
- 底栏角标按真实计数入口展开「99+」；底栏 QUIBadge 在 widget.d.a 转文字之前保留第 3 个参数的真实数量，数字宽度与左右内边距一同调整；底栏纯数字不画红底或边框，用完整矩阵在共同父坐标中校正，并显式居中绘字；上移半个数字字形高度，减少与图标重叠，不再混用窗口坐标与局部宽度（仍待设备验收）
- **Monet 取色**：玻璃的罩色、选中项、设置短胶囊图标的颜色跟壁纸走（系统动态色 `system_accent1_*`，NagramX 的招牌）。
  关掉就退回原来那套写死的中性色
- TG 输入栏：接入 NT 的 `InputEditTextVBDelegate.U`（`adorn.e#a` 编辑器与 `adorn.e#d` 发送按钮）、`AIOInputExpandableVB`（容器 `e` 与顶部分割线 `f`）、`compat.b`（`handleUIState` / `e` / `g` / `i` / `n` 全路径防回退）、`compat.e$b#f`（发送按钮原生半高圆角）、`BottomBlurVB`（底部通栏模糊隐藏）、`AIOShortcutBarVB`（`onCreateView` / `handleUIState` / `x1` / `y1` / `s1` 拦截异步背景回写）与 `PanelIconLinearLayout#f`（图标带重建同步），将真实 `AIOEditText` 与右侧 `AIOInputSendBtn`（含 `com.tencent.qui.smoothcorner.b#j` 圆角背景）分别独立按 `height / 2` 完全药丸化（`capsule()`），去除安全区实色背景后重定位输入区与聊天列表（`RecyclerView` `clipToPadding = false`），并在夜间模式主动提亮玻璃材质、输入框文字与全部快捷操作图标，保留原输入框、发送、语音、表情和附件按钮。保留用户原有开关选择，切换后立即还原/应用；图标只调整剩余间隙，不改按钮宽度与权重。输入与键盘效果待真机验收。
- 藏掉「频道」「动态」「小世界」页签
- 聊天标题栏去掉一起听 / 一起看 / 群游戏那排；侧栏去掉打卡、天气、等级、会员、装扮、钱包一类的入口（数据源和视图两头都拦）
- 隐藏好友聊天标题栏那行在线状态（在线 / 手机在线 / WiFi在线 / 忙碌 / 隐身…）
- 气泡统一、字体统一、隐藏头像挂件；开启气泡统一时，全部消息气泡保持四角全圆角（`17dp`，不切直角，与头像圆角滑块解耦），气泡四边内边距严格统一为 `5dp`（归零 `skin.padding.j` `a,b,c,d=0` 与 `skin.padding.h` `j,k,l,n,o=0, m=4dp`，递归清零内层包装视图冗余内边距、四向 `margin` 与 `57dp × 65dp` 最小宽高锁，并关闭 `TextView` 与 `FasterTextView#mIncludePad` 的 `includeFontPadding`）；被回复消息彻底清除 `0x7f0a5c31` 外框与左部竖条，与回复正文融为单一外层玻璃气泡，覆盖 `BaseMixContentComponent#C2`/`s2`、`AIOReplyComponent#C2`/`s2`/`K3`/`L3`、`AIOTextContentComponent#Z1`/`n2`、`TextMsgContent#H` 与 `AIOUnsuportContentComponent#Y1`；已移除“昵称只留名字”，保留 QQ 原昵称装饰。
- **隐藏红点引导**：群红点、王者小队、群头衔、游戏中心红点、乐吧引导气泡、资料卡引导、虚拟形象角标一律不亮
- **隐藏会员等级**：资料卡上 SVIP / VIP / 大会员 / 大会员年费那排图标不画

聊天
- 防撤回：私聊、群聊、重连补发的撤回都吞掉，被撤回的那条压成半透明，会话里插一条「xx 尝试撤回一条消息」灰字；本机主动点击「撤回」时直接放行原生撤回流程，不拦截自身撤回。
- **消息时间（置于消息容器外部下方以适配全部消息）**：通过 `AIOBubbleMsgItemVB#k1` 与全部内容组件 `d1` 将自定义格式时间（`ExternalStamp`）直接挂载于消息槽容器 `msgContent`（`0x7f0a11b9` `BubbleLayoutCompatPress` 垂直 `LinearLayout`）底部外侧（他人消息左对齐 `START`、自己消息右对齐 `END`），并将同容器兄弟内容根的 `MATCH_PARENT` 宽度转为 `WRAP_CONTENT`（避免 `LinearLayout.measureVertical` 的 `forceUniformWidth` 将图片、表情、转发卡片或短文本水平压窄为时间戳宽度），统一适配文字、图片、文件、引用、混排、语音、表情、卡片、视频、合并转发与不支持消息等全部类型，同时折叠会话顶部 `AIOTimestampComponent` 时间分隔槽。默认 `HH:mm`，在菜单「消息外观」输入自定义格式（内附左对齐格式码说明：`yyyy/yy` 年、`MM` 月、`dd` 日、`E` 星期、`HH` 24时、`hh` 12时、`a` 上下午、`mm` 分、`ss` 秒、`SSS` 毫秒，单引号包裹文字）。时间不会混入正文、复制内容或输入框，也不破坏气泡与卡片内部子 View 树；关闭、复用与热重载均完整还原。各类型实际视觉效果仍待真机验收。
- **原生消息菜单纵向列表与设置面板随指高亮**：按 NagramXF `ActionBarPopupWindow` + `ActionBarMenuSubItem` 将 QQ 原生消息长按菜单 `QQCustomMenuExpandableLayout`（`p` / `l` / `H` / `G`）重排为 12dp 圆角玻璃纵向列表（行高 48dp、左右内边距 18dp、上下内边距清零、20dp 图标 + 12dp 间距 + 16sp 完整不截断文案、6dp 圆角按压高亮，隐藏原生暗色 `S: QQBlurViewWrapper` 与直接子级分割线 `View`，保持聊天列表 `T: mSourceView` 可见且 `C = 220dp` 不裁高），并与模块设置面板共用单有效项随指高亮、跨项移动、项外取消且松手不触发动作、展开态纵滑交还 `ScrollView`；「删除」「撤回」「结束进程」统一标记为警示红图文与按压态（待真机手势复验）。
- **消息显示 At 对象**：点按群消息中的 `@名字` 打开对应资料卡；按元素顺序定位重复名字，保留原链接及正常长按/滑动。UID 无缓存映射时明确提示，`@全体成员` 不当作个人资料卡。
- **头像圆角（普通聊天与聊天记录同步生效）**：菜单「消息外观」调整 0～50%；0% 为直角，50% 为圆形，默认 20%（仅控制头像，不联动气泡圆角）。同时拦截普通聊天 `RoundRectImageView#draw` 下的 `QQProAvatarDrawable#z`（跳过 `proavatar.j#b` / `proavatar.mask.a#a` 的 360° 圆形 `Path.addArc` 遮罩）、`com.tencent.mobileqq.proavatar.e$a#a`、`com.tencent.qqnt.avatar.meta.b$a#b`、`com.tencent.qqnt.avatar.b$a#i` 与 `BaseImageUtil#getCircleFaceBitmap`，交由 `RoundRectImageView#setCornerRadiusAndMode` 按圆角半径裁剪；切换或拖动滑块时清空 `m` 缓存重刷，关闭还原原半径与模式。
- 连发合并（他人居左、自己居右含组首 ID、灰字居中、单条顶对齐、连发粘性浮动、长按头像 `@` 微震反馈、气泡圆角与边距滑条）：仅对气泡收发模板（`template.g` / `template.l`）套用行几何，居中灰字提示（`template.m` / `sm1.z`）保持原生居中且撤回变灰字时自动解绑；他人消息居左（头像左距 8dp、气泡左距 6dp、清零 `holder.c` / `nickSlot` / `MainNickNameBlock` / `contentSlot` 水平内边距使全类型组件共线于 `51dp`）、自己消息保持原生居右与右侧头像并在每组连发首条右对齐显示头衔+昵称 ID（续条折叠，全类型共线于 `right - 54dp`）；`RunLayout.apply` 在续条绑定时即刻隐藏重复原生头像，同发送人同日连续消息不再受 5 分钟断组限制，长按连发浮绘头像或原生头像触发 `@` 时附带 `HapticFeedbackConstants.LONG_PRESS` 轻触感反馈（`AIOAvatarContentComponent#w1`），且面板支持「气泡圆角」`0~32dp`（默认 `17dp`）与「气泡边距」`0~16dp`（默认 `5dp`）滑条实时调节。**尚无本轮设备复验，本次实现与离线检查不等于效果验收。**
- **双击消息快速回应（`双击回应`）**：拦截 `TextPreviewApiImpl#openTextPreview` 与 `TextPreviewApiNtImpl#openTextPreview`，双击消息时通过 `AIOEmoReplyApiImpl#setEmojiLikes` 直接给该条消息贴上快捷回应表情（👍 `76`），不再弹出全屏文字预览页。
- **语音倍速播放记忆与进度条直拖（`语音倍速`）**：挂钩 `AIOPttAudioPlayerManager`（`h:F`、`w`、`E`、`C8`）跨条记忆用户上次选择的语音播放倍速（不被单条播放完成后的 `C8` 重置回 `1.0x`），并在 `PttAudioWaveView` 构造时开启 `setCanSupportSlide(true)` 支持直接横向拖动语音波形进度条。
- **回复不@**：回复消息时不再往输入框插「@昵称 」
- **转发不限人数**：修正私有方法反射访问，转发选人不再卡 9 个；调用 QQ 原生搜索勾选同步及顶部计数刷新。仍需设备验证实际发送端限制。
- **左滑回复**：卡片 (Ark) 消息也能左滑回复
- 转发页一直显示好友 / 群 / 多选入口

以上三条加防撤回那批都是从上游 [QAuxiliary](https://github.com/cinit/QAuxiliary) 同步过来的思路，落点重新按 9.2.10 的真 dex 核过。
- 「+」面板只留正经附件，一起派对、礼物、直播间之类剔掉
- 停止轻互动特效与表情雨

自由化
- 网页一律走系统 WebView，不加载 X5
- 灯塔 (beacon) 与 StatisticCollector 的统计上报空转
- 不初始化崩溃上报
- **全量日志录制与一键导出（`全量日志` / `DiagLog.kt` / `LogMerge.kt`）**：挂钩 `com.tencent.qphone.base.util.QLog#isColorLevel`（开启 QQ 内部 UI/AIO/顶栏/底栏调试日志）与 `QLog#addLogItem(byte, String, int, String, Throwable)`，双环缓冲记录全量 QQ `QLog`、UI/AIO/顶栏专项 `QLog`、全部 Qself 钩子调用频次与前 10 次入参/返回值样本、未捕获崩溃栈、当前进程 `logcat -d`，并在主页/AIO/二级页自动抓取完整 `DecorView` 视图树快照（含每个 View 的类名、资源 ID、屏幕坐标、`padding`/`margin`、`clip`、背景 Drawable 颜色与 `EdgeTransparentView`/`SkinnableRecycleView` 内部状态）；设置面板底部提供「导出全量日志（合并去重）」按钮，一键复制到剪贴板、写入 `/sdcard/Download/qself-full-log.txt` 与 `/sdcard/Android/data/com.tencent.mobileqq/files/qself-full-log.txt` 并拉起系统分享面板。**反复导出只产生一个文件**：每次导出先把上一次的 `qself-full-log.txt` 读回，按小节合并去重（`[3]` 视图树快照按标题取并集、`[4][5][6]` 流式记录整条去重后按时间戳排序、上限 12000 条），环缓冲被挤掉的老记录会被捞回来；随后按 `DISPLAY_NAME LIKE 'qself-full-log%'` 清掉 MediaStore Downloads 里包括 `(1)(2)…` 在内的全部历史副本（别的应用写入的副本会弹一次系统授权框），最后只写回一个 `qself-full-log.txt`，报告头会写明「上次 N 字节 / 清理 M 个」；`LSPosed` 日志与 `logcat -s QselfDiag` 只吐本次新增记录数，重复导出不再刷屏（回放验证：真机 10 份日志逐份合并后单文件 4.36MB，`[6] 全量 QQ QLog` 由单份 3000 条累计到 9367 条）。

## 安装与使用

1. Actions 里下载最新一次构建的 APK（或 `gh run download -R Sumicya/Qself`），安装
2. LSPosed 里启用 Qself，作用域已写死为 QQ，强行停止 QQ 再打开
3. 主页底栏胶囊旁边那颗圆钮（右边放不下就在胶囊右上方）：点开勾选即时生效（钩子每次被调用都查开关）；
   输入栏开关可恢复原外观；从旧的搬移布局版本升级时必须强行停止 QQ 再打开。底栏关闭会还原布局、背景与内边距；菜单不拖动、宽度不变、只有限增高；底栏背景纵向拖动超过触摸阈值后转交 QQ 自己登记的内容页，由原生内容树分发；通过 TabFrameLayout.e 身份核对与 a() 获取，不按尺寸/滚动能力找列表，保留页签点击与徽标拖动；菜单支持恢复默认，✗ 项可查看安装失败原因
4. 每个 QQ 进程启动时打一行日志，`logcat -s Qself` 或 LSPosed 管理器的日志页可见，形如
   `Qself <版本> @ com.tencent.mobileqq ✓系统WebView ✓防撤回 … ✗某功能(NoSuchMethodException: …)`
   —— ✗ 就是那个功能在这版 QQ 里找不到落点，其余不受影响。**把这一行贴回来就能修。**
5. 之后换 APK 直接覆盖安装；当前仓库不保存正式签名私钥，正式签名 APK 不能从本仓库自动推导。

## 构建

本地不需要 SDK：push 之后 GitHub Actions（`.github/workflows/build.yml`）出 APK。
当前仓库不保存签名私钥；debug APK 使用 Android 默认 debug 签名，正式签名需由仓库外的安全签名环境提供。
失败日志以 GitHub Actions 原始日志为准。
本地没有 Android SDK 时用 `tools/check.sh` 做类型检查 + 跑 ProtoTest（第一次会拉工具链到 `~/.cache/qself-tc`）。
版本号 `yy.m.d.当日序号.总序号`，按 git 提交数取，由 `build.yml` 在 CI 里算。

### 核落点

**改任何落点之前先跑 `tools/dexcheck.py`。**

```sh
git clone --depth 1 https://github.com/Sumicya/qqapk q && cat q/qq.a* > qq.apk && rm -rf q
python3 tools/dexcheck.py --apk qq.apk --lint app/src/main/kotlin     # 702 条全绿才算数
python3 tools/ui_paths.py qq.apk                                   # 完整签名、字段读写和调用关系；不是设备验收
python3 tools/dexq.py qq.apk com.tencent.mobileqq.aio.input.reply.i   # 某个类里每个方法用到什么
```

### 源码结构

纯 Kotlin，十三个源码文件，没有第三方依赖、没有 `.so`、不联网。开关状态存 QQ 的 SharedPreferences「qself」，键就是功能名。

```
app/src/main/kotlin/sumicya/qself/
  Qself.kt   入口：功能清单（也是开关列表）、按进程装、日志
  Hook.kt    libxposed 封装（hook / constant / afterNew / 反射读写字段方法）与开关读取
  Knob.kt    主页那颗圆钮与开关面板（紧凑玻璃面板）
  Quiet.kt   自由化
  Chat.kt    聊天，含防撤回用的几十行 protobuf 读取、撤回灰字、连发合并、回复不@
  Looks.kt   外观：盯住每个 Activity 的视图树套规则
  Input.kt   TG 式输入栏
  Glass.kt   液态玻璃 Drawable；底栏、设置入口、菜单与输入区委托 KernelBar
  KernelBar.kt   KernelSU 算法的无依赖 View 移植（着色器 / 弹簧 / 手势 / 生命周期）
  MessageStyle.kt  消息行几何：头像圆角、气泡边距、外部时间、连发合并的绘制与触摸
  DiagLog.kt    全量日志与界面树快照录制、一键导出
  LogMerge.kt   导出日志的合并去重（纯 JVM，可离线回放真机日志）
  Perf.kt       Choreographer 帧率采样，导出日志里的卡顿数字
app/src/test/kotlin/sumicya/qself/ProtoTest.kt   协议、分组、间距、菜单尺寸等纯逻辑回归
app/src/main/resources/META-INF/xposed/          libxposed 入口、module.prop、作用域
tools/symbols.txt    落点表：代码依赖的每一个名字
tools/dexcheck.py    拿真 dex 逐条核落点，顺带 lint 源码里没登记的名字
tools/ui_paths.py    真包字段读写与调用边；只做静态核对
tools/dexq.py        最小反汇编器：按签名找落点（上游用 DexKit 在运行时搜，这里离线搜）
tools/check.sh       无 SDK 时的类型检查 + 跑 ProtoTest
```

## 项目限制

QQ 原生消息菜单与设置面板已接入单有效项随指高亮、跨项移动、项外取消与危险操作警示色，全类型消息时间与顶部时间条折叠已接线，但手势、浮动头像及各类消息视觉效果仍待真机验收。

### 写法

按 [ponytail](https://github.com/DietrichGebert/ponytail) 来：

- 先问「不做行不行」；再看仓库里有没有现成的；再看 Kotlin 标准库；再看 Android 原生 API；最后才写代码
- 不加抽象、不加依赖、不加样板；能删就删；一个功能 = 一个顶层函数 + `Qself.kt` 清单里一行（清单顺序就是开关顺序）
- 钩子体不要 try/catch 兜底整个功能：libxposed 默认异常模式下，钩子抛异常等于这一次没装。
  但**从 `chain` 取参数、反射取视图这类可能落在兄弟子类上的动作要包 `runCatching`** —— 挂点在基类时，钩子会盖住所有子类
- 每项安装经 `installFeature()`：异常撤掉本项已经安装的钩子；钩子经 `hook()` 自动受开关管，视图规则自己 `on("功能名")`
- 连发昵称/间距在绑定及开关变化时应用；头像 pre-draw 只采样位置并提交重绘，不请求布局或取消帧，禁止恢复延迟注册。用列表本地浮点矩阵统一绘制/触摸坐标，浮动位置不加追赶补间；续条仅收紧正文容器的正 topMargin，不改正文内边距；昵称操作 i1 容器并读取主表与活跃附加表 G，组首固定。原生测量阶段可排除头像槽撑高，须按原生求解器保留其余子控件、最小高度及 EXACTLY 约束；复用和关闭还原并重测。数据提交时统一重算组键及完整组边界，不能按挂载行数判断单条。
- 输入保留 QQ 原 EditText 和父层级；不得带回旧输入栏搬移/隐藏/压缩方案，不得重新加入“昵称只留名字”。
- minSdk 36：只有 Android 16，别写 `SDK_INT` 判断和降级分支
- 故意省掉的地方写 `ponytail:` 注释，说清上限和往上走的路
- 非平凡逻辑留一份能跑的检查（目前只有 `ProtoTest.kt`）
- 外观文件（`Glass.kt` / `KernelBar.kt` / `Input.kt` / `Looks.kt` / `Knob.kt`）的实际视觉与生命周期不能由 dex 核验代替，必须真机检查；
  改完说清楚改了哪块长相，别让人猜
- 代码先行，解释不超过三行；中文

### 怎么找落点

类名、方法名全部从 9.2.10 的 dex 里查出来，写进 `tools/symbols.txt`，不是猜的。

混淆过的短名字（`a`、`k`、`d1`）按签名找，别按名字猜：`tools/dexq.py` 是个最小反汇编器，
打印一个类里每个方法用到的字符串、读写的字段、调用的方法 —— 上游 QAuxiliary 用 DexKit 在运行时搜的就是这些判据，
这里离线搜同一件事，搜到就把名字写进表和代码。两条规矩：

- `Class<*>.method(name)` 和 `constant()` 都往父类找（QQ 的挂点大半声明在 MVVM 基类 / Api 基类上），
  `declaredMethods` 只翻本类会静悄悄空转；反射**调用**用 `getMethod`（public + 父类），装钩子用 `method()`
- `dexq.py` 按 DEX 格式分别重置 direct/virtual 方法差分索引；声明关系可用 class_data 核对，
  运行时解析仍沿父类查找。`constant()` 的 pick 必须带名字过滤，避免改到父类的无关方法。
- 解析方法要在**装的时候**做完（`val m = c.getMethod("x")`），别写在钩子体里 —— 找不到就当场 ✗ 报出来，
  而不是每次绑定时被 `runCatching` 吞掉，看起来装着其实什么都没干

换版本时：
1. 拿到 QQ 的 APK（`Sumicya/qqapk` 里是 9.2.10 的分卷，`cat qq.a* > qq.apk`）
2. `python3 tools/dexcheck.py --apk qq.apk --lint app/src/main/kotlin`，看哪条红
3. 红的那条用 `tools/dexq.py` 按签名找新名字（参数类型、用到的字符串、读的字段），先改表再改代码
4. 装上后看 `logcat -s Qself` 那一行，✗ 的再修

### 做不到的（别再去挖一遍）

- **全量 Monet 化**：QQ 没有集中的取色入口。`com.tencent.mobileqq.theme` 包里只有 DarkModeManager /
  ThemeConstants，一个返回颜色的方法都没有；界面颜色散在 QUI 设计系统的几百个 drawable 里
  （`qui_button_bg_primary_default`、`qui_aio_word_primary`…）。Telegram/NagramX 能全量 Monet
  是因为有 `Theme.getColor(key)` 一个口子。hook `Resources.getColor` 只能碰纯色资源、碰不到 drawable，
  还会把整个 UI 弄花 —— 所以 Monet 只作用在 Qself 自己画的那几块玻璃上（`Glass.kt` / `Knob.kt`）。
- **屏蔽更新**：`UpgradeController` 在 9.2.10 里没有了，`ConfigHandler` 也没有上游要找的
  `void(UpgradeDetailWrapper)`；`UpgradeBannerProcessor` 里对不上 `void(Message,long,boolean)`。要做得重新找落点。
- **屏蔽抖动窗口**：`AIOShakeHelper` 类不存在（上游自己写着「作用暂时不明」）。
- **移除消息列表顶栏横幅广告**：上游靠的 `QbossADImmersionBannerManager` 不存在；剩下的
  Renewals/Push/DressUp/VasAD 四个 BannerProcessor 的 `handleMessage(Message)→boolean` 返回值语义
  没机器验不了，不敢直接吞。要做就走 Looks.kt 的视图规则，别猜返回值。
- **语音转发及保存**：上游 411 行，要自己建确认对话框和长按菜单，不是一个钩子的事。
- **气泡尾巴**：尾巴画在 9-patch 里（`skin_aio_*_bubble_nor/pressed`，朝头像那侧凸 17 行像素），QQ 也确实自带
  同尺寸(110x106)无尾巴的 `_simple` 版 —— 但两套九宫格内边距不一样，直接换 id 会让消息文字不居中；
  而且真机上聊天里本来就没有尾巴（NT 聊天大概率不走这套图，只有充值气泡/皮肤走）。0.8.1 试过，已撤，别再碰。

## 许可证

GPL-3.0-or-later。源自 [QAuxiliary](https://github.com/cinit/QAuxiliary)，只留了思路，聊天等原有实现重写；底栏包含注明来源的上游算法和着色器，见 [第三方许可](third_party/NOTICE.md)。

遵循 [Sumicya/selfs](https://github.com/Sumicya/selfs) 最新 GLOBAL.md。
