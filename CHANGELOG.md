# CHANGELOG

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
