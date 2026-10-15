# Qself

给 NT QQ 的一个 LSPosed 模块：**自由、简单、现代、原生**。装上即全部生效，不联网、没有第三方库、没有一个 `.so`。
开关不在模块里，在 QQ 主页底栏胶囊旁边那颗 52dp 玻璃三线菜单圆钮里：点开是宽不超过 360dp、高在原面板基础上增加最多 80dp、上限 520dp 的紧凑玻璃面板，一行两项、
画出来的 ✓/–/✗ 加一句描述（✗ = 这版 QQ 没装上），存进 QQ 自己的 SharedPreferences。

只对着一台机器写：QQ 9.2.10 · Android 16 · LSPosed 2.x（libxposed API 102）。类名、方法名全部写死，
但每一个都在 CI 里拿真 dex 逐条核过（[`tools/symbols.txt`](tools/symbols.txt)，223 条）——换版本先看那张表红哪条，别猜。

## 做了什么

外观（照着 [NagramXF](https://github.com/Keeperorowner/NagramXF) 那套长相来）
- 首页底栏参考 KernelSU 的无模糊分支，使用全圆角底色和独立圆形选中项：保留面状高光，不模糊、不描边、不录制背景；页签最小 56dp，可随数字宽度变长
- 底栏角标按真实计数入口展开「99+」；底栏 QUIBadge 在 widget.d.a 转文字之前保留第 3 个参数的真实数量，数字宽度与左右内边距一同调整；底栏纯数字不画红底或边框，按页签横向居中（仍待设备验收）
- **Monet 取色**：玻璃的罩色、选中圆、圆钮的颜色跟壁纸走（系统动态色 `system_accent1_*`，NagramX 的招牌）。
  关掉就退回原来那套写死的中性色
- TG 输入栏：接入 NT 的 InputEditTextVBDelegate.U，按真实 EditText 唯一管理玻璃胶囊，不再只等旧版图标带出现，保留原输入框、发送、语音、表情和附件按钮；撤掉搬移输入区、镜像按钮、隐藏图标带和压缩兄弟布局的实现。保留用户原有开关选择，切换后立即还原/应用，不再自动重新开启；清理已核实的 NT 输入行及旧版 InputLinearLayout / 图标带背景及与输入行对齐的背景模糊层；图标只调整剩余间隙，不改按钮宽度与权重。输入与键盘效果待真机验收。
- 藏掉「频道」「动态」「小世界」页签
- 聊天标题栏去掉一起听 / 一起看 / 群游戏那排；侧栏去掉打卡、天气、等级、会员、装扮、钱包一类的入口（数据源和视图两头都拦）
- 隐藏好友聊天标题栏那行在线状态（在线 / 手机在线 / WiFi在线 / 忙碌 / 隐身…）
- 统一气泡、统一字体、去头像挂件；已移除“昵称只留名字”，保留 QQ 原昵称装饰。
- **屏蔽红点引导**：群红点、王者小队、群头衔、游戏中心红点、乐吧引导气泡、资料卡引导、虚拟形象角标一律不亮
- **去会员等级**：资料卡上 SVIP / VIP / 大会员 / 大会员年费那排图标不画

聊天
- 防撤回：私聊、群聊、重连补发的撤回都吞掉，被撤回的那条压成半透明，会话里插一条「xx 尝试撤回一条消息」灰字
- 连发合并：按真实内容组件槽位收紧续条正上间距至最多 2dp，不改正文内边距；昵称取 `i1()` 外层容器，读取主表和已激活附加表 `G`，同时识别 pit V2 与 slot V4 昵称，固定组首上方、续条折叠。列表提交后重算所有已绑定行的组键和起止，自己的连发统一身份；浮动范围按数据中的组首/组末判断，不再把仅剩一条挂载行的长组误当单条。短续条在原生测量阶段排除头像槽对行高的撑大，保留其余原生控件、最小高度及精确高度约束；关闭恢复并重测。原生异步可见性与模块覆盖分开保存；绘制/触摸仍共用同帧矩阵，滚动帧不重排、不加追赶动画。**用户反馈的跳动、不合并与昵称未折叠尚无设备复验，本次实现与离线检查不等于效果验收。**
- **回复不@**：回复消息时不再往输入框插「@昵称 」
- **转发不限人数**：转发选人到 9 个就不再卡住，顶部「已选 N 人」跟着走
- **左滑回复不设限**：卡片 (Ark) 消息也能左滑回复
- 转发页一直显示好友 / 群 / 多选入口

以上三条加防撤回那批都是从上游 [QAuxiliary](https://github.com/cinit/QAuxiliary) 同步过来的思路，落点重新按 9.2.10 的真 dex 核过。
- 「+」面板只留正经附件，一起派对、礼物、直播间之类剔掉
- 屏蔽轻互动特效与表情雨

自由化
- 网页一律走系统 WebView，不加载 X5
- 灯塔 (beacon) 与 StatisticCollector 的统计上报空转
- 不初始化崩溃上报

## 用

1. Actions 里下载最新一次构建的 APK（或 `gh run download -R Sumicya/Qself`），安装
2. LSPosed 里启用 Qself，作用域已写死为 QQ，强行停止 QQ 再打开
3. 主页底栏胶囊旁边那颗圆钮（右边放不下就在胶囊右上方）：点开勾选即时生效（钩子每次被调用都查开关）；
   输入栏开关可恢复原外观；从旧的搬移布局版本升级时必须强行停止 QQ 再打开。底栏关闭会还原布局、背景与内边距；菜单不拖动、宽度不变、只有限增高；底栏背景纵向拖动超过触摸阈值后转交 QQ 自己登记的内容页，由原生内容树分发；通过 TabFrameLayout.e 身份核对与 a() 获取，不按尺寸/滚动能力找列表，保留页签点击与徽标拖动；菜单支持恢复默认，✗ 项可查看安装失败原因
4. 每个 QQ 进程启动时打一行日志，`logcat -s Qself` 或 LSPosed 管理器的日志页可见，形如
   `Qself <版本> @ com.tencent.mobileqq ✓系统WebView ✓防撤回 … ✗某功能(NoSuchMethodException: …)`
   —— ✗ 就是那个功能在这版 QQ 里找不到落点，其余不受影响。**把这一行贴回来就能修。**
5. 之后换 APK 直接覆盖安装；当前仓库不保存正式签名私钥，正式签名 APK 不能从本仓库自动推导。

## 结构

```
app/src/main/kotlin/sumicya/qself/
  Qself.kt   入口：功能清单（也是开关列表）、按进程装、日志
  Hook.kt    libxposed 封装（hook / constant / afterNew / 反射读写字段方法）与开关读取
  Knob.kt    主页那颗圆钮与开关面板（紧凑玻璃面板）
  Quiet.kt   自由化
  Chat.kt    聊天，含防撤回用的几十行 protobuf 读取、撤回灰字、连发合并、回复不@
  Looks.kt   外观：盯住每个 Activity 的视图树套规则
  Input.kt   TG 式输入栏
  Glass.kt   液态玻璃 Drawable（RenderNode + AGSL + Monet 取色，底栏用时自带滑动亮胶囊）
app/src/test/kotlin/sumicya/qself/ProtoTest.kt   协议、分组、间距、菜单尺寸等纯逻辑回归
app/src/main/resources/META-INF/xposed/          libxposed 入口、module.prop、作用域
tools/symbols.txt    落点表：代码依赖的每一个名字
tools/dexcheck.py    拿真 dex 逐条核落点，顺带 lint 源码里没登记的名字
tools/dexq.py        最小反汇编器：按签名找落点（上游用 DexKit 在运行时搜，这里离线搜）
ci/check.sh          无 SDK 时的类型检查 + 跑 ProtoTest
```

## 核落点

```sh
git clone --depth 1 https://github.com/Sumicya/qqapk q && cat q/qq.a* > qq.apk && rm -rf q
python3 tools/dexcheck.py --apk qq.apk --lint app/src/main/kotlin     # 223 条全绿才算数
python3 tools/ui_paths.py qq.apk                                   # 完整签名、字段读写和调用关系；不是设备验收
python3 tools/dexq.py qq.apk com.tencent.mobileqq.aio.input.reply.i   # 某个类里每个方法用到什么
```

GPL-3.0-or-later。源自 [QAuxiliary](https://github.com/cinit/QAuxiliary)，只留了思路，代码全部重写。
