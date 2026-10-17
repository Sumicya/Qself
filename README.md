# Qself

给 NT QQ 的一个 LSPosed 模块：**自由、简单、现代、原生**。装上即全部生效，不联网、没有第三方库、没有一个 `.so`。
开关不在模块里，在 QQ 主页底栏胶囊旁边独立的 76×52dp 玻璃三线设置短胶囊里：点开是宽不超过 360dp、高在原面板基础上增加最多 80dp、上限 520dp 的紧凑玻璃面板，一行两项、
画出来的 ✓/–/✗ 加一句描述（✗ = 这版 QQ 没装上），存进 QQ 自己的 SharedPreferences。

## 要求

只对着一台机器写：QQ 9.2.10 · Android 16 · LSPosed 2.x（libxposed API 102）。类名、方法名全部写死，
但每一个都在 CI 里拿真 dex 逐条核过（[`tools/symbols.txt`](tools/symbols.txt)，223 条）——换版本先看那张表红哪条，别猜。

## 功能

外观（照着 [NagramXF](https://github.com/Keeperorowner/NagramXF) 那套长相来）
- 首页底栏按 [KernelSU 固定源码](https://github.com/tiann/KernelSU/tree/3186a2fe2107f7170e06dab7aa157eb10ae7759f/manager/app/src/main/java/me/weishu/kernelsu/ui/component) 移植到原生 View：64dp 栏 / 最小 76dp 页签 / 56dp 指示器、模糊折射、按压色散、双峰重力高光和解析弹簧。普通内容与强调色缩放副本分层，前景透镜在 QQ 内容之后从组合背景取样；材质动画不再缩放真实 QQ View。设置短胶囊共享材质/按压，放不下时置于右上方，菜单本身不扩张；不增加 APK 依赖。横拖释放后选真实页签，纵滑仍交给背后原生内容页。**未获本轮设备复验，不宣称一比一验收完成**；差异与证据见 [移植记录](docs/kernel-port.md)。
- 底栏角标按真实计数入口展开「99+」；底栏 QUIBadge 在 widget.d.a 转文字之前保留第 3 个参数的真实数量，数字宽度与左右内边距一同调整；底栏纯数字不画红底或边框，用完整矩阵在共同父坐标中校正，并显式居中绘字，不再混用窗口坐标与局部宽度（仍待设备验收）
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
本地没有 Android SDK 时用 `ci/check.sh` 做类型检查 + 跑 ProtoTest（第一次会拉工具链到 `~/.cache/qself-tc`）。
版本号 `yy.m.d.当日序号.总序号`，按 git 提交数取，由 `build.yml` 在 CI 里算。

### 核落点

**改任何落点之前先跑 `tools/dexcheck.py`。**

```sh
git clone --depth 1 https://github.com/Sumicya/qqapk q && cat q/qq.a* > qq.apk && rm -rf q
python3 tools/dexcheck.py --apk qq.apk --lint app/src/main/kotlin     # 223 条全绿才算数
python3 tools/ui_paths.py qq.apk                                   # 完整签名、字段读写和调用关系；不是设备验收
python3 tools/dexq.py qq.apk com.tencent.mobileqq.aio.input.reply.i   # 某个类里每个方法用到什么
```

### 源码结构

纯 Kotlin，九个源码文件，没有第三方依赖、没有 `.so`、不联网。开关状态存 QQ 的 SharedPreferences「qself」，键就是功能名。

```
app/src/main/kotlin/sumicya/qself/
  Qself.kt   入口：功能清单（也是开关列表）、按进程装、日志
  Hook.kt    libxposed 封装（hook / constant / afterNew / 反射读写字段方法）与开关读取
  Knob.kt    主页那颗圆钮与开关面板（紧凑玻璃面板）
  Quiet.kt   自由化
  Chat.kt    聊天，含防撤回用的几十行 protobuf 读取、撤回灰字、连发合并、回复不@
  Looks.kt   外观：盯住每个 Activity 的视图树套规则
  Input.kt   TG 式输入栏
  Glass.kt   通用液态玻璃 Drawable；仅主页底栏委托 KernelBar
  KernelBar.kt   KernelSU 算法的无依赖 View 移植（着色器 / 弹簧 / 手势 / 生命周期）
app/src/test/kotlin/sumicya/qself/ProtoTest.kt   协议、分组、间距、菜单尺寸等纯逻辑回归
app/src/main/resources/META-INF/xposed/          libxposed 入口、module.prop、作用域
tools/symbols.txt    落点表：代码依赖的每一个名字
tools/dexcheck.py    拿真 dex 逐条核落点，顺带 lint 源码里没登记的名字
tools/dexq.py        最小反汇编器：按签名找落点（上游用 DexKit 在运行时搜，这里离线搜）
ci/check.sh          无 SDK 时的类型检查 + 跑 ProtoTest
```

## 项目限制

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

GPL-3.0-or-later。源自 [QAuxiliary](https://github.com/cinit/QAuxiliary)，只留了思路，聊天等原有实现重写；底栏包含注明来源的上游算法和着色器，见 [第三方许可](third_party/NOTICE.md)。

遵循 [Sumicya/selfs](https://github.com/Sumicya/selfs) 最新 GLOBAL.md。
