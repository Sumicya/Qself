# CHANGELOG

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
