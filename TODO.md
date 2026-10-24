# 聊天界面整改

## 已确认的设计与边界

- 目标为尽量接近 [NagramXF](https://github.com/Keeperorowner/NagramXF) 的视觉与布局，整体统一；聊天页先做，不把局部修改称为整体完成。
- 采用紧凑排版，保留信息和原有操作，允许重排；不缩小点击区域、不隐藏或挤压输入框与按钮。每条消息时间始终可见，不只覆盖普通文字。
- 头像显示与身份一致，点击打开对应人的资料卡，长按保留原生语义；引用定位不冒充头像点击。
- 菜单仅当前有效项目随指高亮，移出取消，取消不执行；危险操作明确区分。
- 保留玻璃、正常透镜与动态效果，优先优化算法，不擅自降低效果或引入降级方案；允许按需 fork，不意味着任意引入依赖。
- 默认主题的明暗模式都要适配。第一阶段包含顶栏、消息列表、头像昵称时间、各类消息、输入区与菜单；首页底栏检查一致性，资料卡先保证跳转正确。
- 代码/CI、实际界面、设备验收分开标注。当前没有本轮 QQ/GPU 设备验收证据。

## 未完成与待验收

- [ ] 实机验收聊天顶栏三胶囊布局（`AIOTitleVB` + `AIODefaultTitleContainerVB` + `AIOTitleRelativeLayout` + `CenterVB` + `ImmersiveTitleBar2`/`ImmersiveTitleBar`/`NTImmersiveTitleBar`）：剥离顶部安全区实色背景、上移三段胶囊缩小上部距离（`topOffsetDp = 1dp`）、点击胶囊任意位置稳定触发菜单并驱动 `INTERACTIVE_HIGHLIGHT_SHADER` 径向高光；代码与 DEX 落点已接线，待真机视觉与点击复验。
- [ ] 实机验收浅色模式底栏胶囊与 Qself 设置按钮（`MenuGlyph` 重写 `setTintList` 动态着浅色 `#1C1C1E` / 深色 `#FFFFFF`、`KNOB` 统一 `10dp` `0x1A000000` 柔和阴影、`BarState.glass` 持久守护底栏背景不被 QQ 主题 `#F5F5F5` 覆盖）、5 项实时滑条（头像圆角 `0~50%`、玻璃模糊 `0~15dp`、气泡圆角 `0~32dp`、气泡边距 `0~16dp`、顶栏偏移 `-12~16dp`）以及新增的「双击回应」「语音倍速」「原生表情/相册/+面板玻璃化」「长按头像 @ 微震反馈」。
- [ ] 实机验收 QQ 原生消息长按菜单（`QQCustomMenuExpandableLayout` 纵向 48dp 行高 / 18dp 左右内边距 / 0dp 上下内边距 / 12dp 圆角 `ActionBarPopupWindow` 列表，严禁将聊天列表 `T: mSourceView` 置 `GONE`）与设置面板的逐项随指高亮、跨项移动、项外取消、展开态纵滑交接、撤回与危险操作（删除、撤回、结束进程）稳定性；真包落点与接线已完成，待真机手势复验。
- [ ] 实测普通聊天头像圆角、连发定位与己方组首 ID（单条顶对齐、连发粘性滚动合并）：普通聊天 `RoundRectImageView#draw` 下拦截 `QQProAvatarDrawable#z`（跳过 `proavatar.j#b` / `proavatar.mask.a#a` 的 360° 圆形 `Path.addArc` 遮罩）并与 `proavatar.e$a#a`、`meta.b$a#b` 同步生效头像圆角（0% 为直角，50% 为圆形，不联动气泡）；仅他人消息居左（头像左距 8dp、气泡左距 6dp，保留 `WRAP_CONTENT` 与清零 `msgAvatar` 顶内边距防止侵入气泡或下行 `LVxx` 头衔）、自己消息保持原生居右与右侧头像并在连发组首同样右对齐显示头衔+昵称 ID（续条折叠）、居中灰字提示（`template.m`）保持原生居中且撤回变灰字时自动解绑连发；单条独立消息保持顶对齐不居中，仅连发多条消息（`>= 2`）按组上下边界粘性滚动合并；待真机在快速滚动、异步更新、行复用、分组变化、私聊与群聊中复验。
- [ ] 实机验收全类型外部常显时间（`ExternalStamp` 转换同容器兄弟 `MATCH_PARENT` 为 `WRAP_CONTENT` 防止压窄图片/表情/卡片/短文本）、四角全圆角（`17dp`，不切直角）、四边统一 `5dp` 气泡内边距与无左竖条圆角引用块（`ReplyQuoteBar`）：全部消息类型统一在 `msgContent`（`0x7f0a11b9`）容器底部外侧显示自定义格式时间（他人左对齐 `START`、自己右对齐 `END`），顶部 `AIOTimestampComponent` 槽位随开关折叠/恢复；待真机检验各气泡与卡片边距视觉效果。
- [ ] 核对连续消息昵称折叠与“保留信息”的边界，避免复用恢复错误、装饰丢失和文字遮挡。
- [ ] 实机验收输入区（`InputEditTextVBDelegate` + `AIOInputExpandableVB` + `compat.b` + `BottomBlurVB` + `AIOShortcutBarVB`）输入框与右侧发送按钮（`AIOInputSendBtn` + `com.tencent.qui.smoothcorner.b#j`）各自独立 `height / 2` 完全药丸化、安全区背景去除后的组件重定位（`RecyclerView` `clipToPadding = false` 与输入栏安全边距）、夜间模式全局图标/按钮/材质提亮、键盘升降与长按操作。
- [ ] 按 NagramXF 的层级与比例检查首页底栏的一致性，保持已确认的透镜、短胶囊及点击/滑动行为。
- [ ] 验证默认主题明暗模式、大字体、RTL、窄屏、长消息、长时间格式与菜单内部滚动。
- [ ] 分析玻璃采样、分配及重绘开销，优化算法而不弱化效果；提供实际性能证据，不用静态检查推断流畅性；核查现有失败回退路径，不能把纯色或原生绘制回退当作玻璃验收成功。
