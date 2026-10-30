# CHANGELOG

## 2026-10-31（十五）— 状态栏安全区自己变成渐变，页面不再每帧取消重绘

- 状态栏渐变（`Looks.kt`）：以前 `0..顶栏底边` 那条带子显形的是窗口底色，顶上是一块实心的
  `#FF0F1113`，和下面的内容之间是一条硬边。现在把渐变挂在**顶栏容器自己的背景**上——
  主页是 `0x7f0a6cfa`（`b=[0,0,1272,194]`、原本透明、在列表之后绘制），聊天页是标题栏外层
  `sm1.u#k`；内容从消息区滚上来时在顶栏底下淡出，标题按钮与胶囊仍在它之上。**没有第二层、没有描边**，
  也没有在列表上再叠一层：整块就是那层容器背景。
- 曲线取 `1 - smoothstep`：顶端不透明度 `255`、底端 `0`，且两端斜率都为 0 —— 只做线性渐变的话，
  底端会在容器下沿留一条斜率突变（马赫带），眼睛能看出「到这儿为止」的界线。13 个停靠点喂给同一个
  `LinearGradient`，仍然是单层。
- 底色不是写死的：`fadeColorBehind()` 先看同父容器里排在它前面、铺满整块的实心 `ColorDrawable`，
  再退到窗口根底色、`DecorView` 底色，最后才是夜间/日间常量；挂载时写一行
  `状态栏渐变 : 挂到 <类名>@0x<id> 高=…px 底色=#… 来源=…`，来源对不上就能当场看出来。
- 客观读数：`StatusFadeDrawable` 在尺寸或底色变化时，把这条渐变**交给 Skia 真正渲染**成 1×`h` 的
  位图再逐行读回 alpha，写一行 `状态栏渐变 : 高=194px … Skia 读数 相邻行不透明度最大差=n/255
  亮度上限=n/255 阈值=6`。亮度是各通道的凸组合，所以相邻行亮度差的上限就是相邻行不透明度差 ——
  这条把「截图 x=1100、y=0..194 相邻行亮度差 < 6/255」从主观判断变成可读的数字。
- 帧率（`Looks.kt`）：真机日志里每秒数十条 `VRI performTraversals: cancelAndRedraw,
  mLastPerformTraversalsSkipDrawReason predraw_sumicya.qself.LooksKt$$ExternalSyntheticLambdaNN`，
  两处 `onPreDraw` 在「规则刚改过布局」时 `return false` 取消当前帧。取消一帧等于让整棵树再走一遍
  `measure/layout`，而两处规则本身都是幂等的、晚一帧生效看不出来。两处都改成不取消（`BarState`
  的 `preDrawPasses` 与 `AppearanceObserver` 的 `cancelledOnce` 随之删除），把布局请求交给下一次遍历。
- 自证：`statusFadeAlpha` / `statusFadeColors` / `statusFadeAlphaAt` 抽成纯函数并加 2 条单测（77 → 79 条），
  钉住首尾精确值、单调性、两端斜率中段最陡、以及真机 194px 高时相邻行不透明度差 `worst < 6/255`；
  `tools/check.sh` 编译 136 个类、79 条测试全过，`dexcheck lint bad = 0`，`Looks.kt` 无新增编译告警。

## 2026-10-30（十四）— 全部页面进入改成「从点按处展开」，返回手势跟手收拢

- 先修一个没人发现的事实：`pageAnimations()` 从写出来那天起就没有在 `looks()` 里被调用过，等于上一版
  「共享 X 轴微缩淡入」从来没生效；QQ 现在跑的是它自己的页面切换。这次把它接上，并换成展开。
- 展开（`Looks.kt`）：每个页面根只动一次 `scaleX 0.86` / `scaleY 0.90` → `1`，`260ms`，
  `PathInterpolator(0.2, 0, 0, 1)`；**不动 alpha**（整页加 alpha 会多开一层 1272×2800 的离屏缓冲），
  **不碰布局**，动画由 RenderThread 跑。展开原点取最后一次按下的屏幕坐标（`dispatchTouchEvent` 里只记
  `ACTION_DOWN`，且只认最外层窗口），换算到目标视图局部坐标后夹进 `15%` 至 `85%`；没有点按（通知冷启动、
  程序内跳转）就落在中心 —— 这就是「预测你从哪一行进来」。起始值在第一帧 `onPreDraw` 里摆好再返回 `true`，
  不会先闪一帧全尺寸；这个监听器一次性，播完立刻移除，不留在垂直同步上。
- 覆盖范围：①底部页签切换（`TabFrameControllerImpl#updateUIAfterTabChange` → 内容根）；②会话与群聊
  （`AIOMsgListVB#onCreateView` → 由消息列表向上找到 `android.R.id.content` 下面那一层的整页根，
  标题栏与消息区一起进来，C2C 与群聊走同一条 AIO 钩子）；③普通 Activity 第一次恢复前台
  （`Instrumentation#callActivityOnResume` → `android.R.id.content` 里面积最大的孩子）。
  同一个页面根 `700ms` 内只播一次，Activity 另有 `enteredActivities` 记录，退对话框/选图页回来不会重播。
  悬浮窗（对话框式 Activity）与含 `SurfaceView` 的页面（相机、视频，SurfaceView 独立合成、缩放父容器只会错位）不展开。
- 预测性动画：`Activity#getOnBackInvokedDispatcher` 返回一个包装件，`OnBackAnimationCallback` 的
  `onBackStarted/Progressed/Cancelled/Invoked` 全部原样转发，进度同时驱动页面 `scale 1 → 0.94`、
  沿滑出边横移最多 `10%` 宽；取消与提交都用 `160ms` 回位，并按「边/最大进度/帧数/用时」写一行
  `返回预测 : …`。系统不给进度事件时（QQ 没启用预测性返回）这段代码什么都不做，也不写噪声行。
- 自证：`expandPivot`、`backScale`、`backShift` 抽成纯函数并加 2 条单测（75 → 77 条全过），钉住
  夹取边界、越界进度与左右方向；`tools/check.sh` 编译 135 个类、77 条测试全过，`dexcheck lint bad = 0`。
- 规范收口（同一提交）：按 `GLOBAL.md`（`adbcf50a`）「CHANGELOG 只留最新 5 条」把 2026-10-24（九）（八）
  两条原样移入 `bad/CHANGELOG-history.md` 开头；核对 `LICENSE` 为 GPL-3.0 全文 `35149` 字节、README 六个
  二级章节顺序与固定末行、全仓无 `AGENTS.md` 变体。`LICENSE §5(d)` 要求的「关于/许可证」可点入口本仓
  尚无，记为待办，不在本轮擅自加 UI。

## 2026-10-30（十三）— 消息区几何改成单一窗口坐标，底部安全区真的让位

- 底部安全区（`Input.kt`）：真机导出（26.10.28.8.358，17:47:38 UTC+8）里视图树实测消息列表
  `scr=[0,141]` 高 `1740`、即窗口内 `141..1881`，输入浮层 `InterceptableRelativeLayout`
  `scr=[0,1778]`、高 `115`，两者重叠 `103px`；而列表的 `paddingBottom` 一直是 `0`，最后一条消息连同
  它下面的时间整个压在输入栏底下（截图里 `下午5,35.20` 直接叠在发送按钮上）。
  根因是两套坐标系混算：浮层高度按「窗口根视图高 2800 − 浮层顶 1778」记成 `216`，消息区那边又用
  `窗口根视图高 2800 − 列表底 1881 = 919` 去减，`216 − 919` 恒为负数，取 `max(原生 0, 负数)` 就是 `0`；
  另外 `syncBottomOverlay` 里还有一条「键盘弹出就按 0 算」的分支，把键盘态也一起归零。
  现在只记浮层上沿的窗口 y，底部内边距 = `列表底边 − 浮层上沿 + 6dp`，两边同一套坐标，键盘弹起时
  浮层自己上移、重叠量自己变大，不再需要键盘分支；浮层完全在列表下方时结果为 0。
- 自证：`listBottomInsetPx()` 抽成纯函数并加 1 条单测（75 条全过），
  用真机实测数字（1881 / 1778 / 12 → 115）钉住；几何每次变化写一行
  `消息区几何 列表=… 浮层顶=… 内边距=上…/下…`，若出现「有重叠却没让位」当场追加 `← 异常`，
  下次导出就能从日志里核对，而不是靠主观觉得好看。

## 2026-10-28（十二）— 合规收口（测试归位、docs/ 清空、工作流对齐）+ 帧率录制改成「一次滑动一行」

- 测试归位：`tools/test_dexq.py`、`tools/test_ui_paths.py` 是本仓反汇编与字段解码的真纯函数回归（真二进制夹具，
  不读业务源码做字符串匹配），按「单元测试只放项目标准测试目录」迁到 `app/src/test/python/`，导入路径指向 `tools/`；
  `build.yml` 与 `pr-check.yml` 的单测步骤同步改路径（`tools/` 下已无测试文件，Python 3.12 的 `unittest`
  在「一条都没跑到」时退出码为 5，`build` 为此红过一轮，本轮已修）；`tools/check.sh` 也接上这两条回归，
  本地自检与 CI 同口径。8 条断言在新位置全过。
- 文档收敛：删除 `docs/` 下四个文件。`design-principles.md` 按规范仅 `Selffont` 维护；`apk-analysis.md`、
  `kernel-port.md`、`message-ui.md` 属未经主人要求的过程文档。README 里指向它们的两处引用同提交清掉，
  「源码结构」补上 Python 测试与着色器检查两行，不留指向已删文件的链接。
- 号源口径复核：`build.yml` 取号用 `github.run_number` 原值 + 当日序号差值锚定，打印 `HEAD SHA`/`run_id`/
  `run_number`/`R_first`/当日序号/最终版本，取不到即退出；现存五个产物末段 `348–352` 与其来源运行号逐个对齐。
- 工作流结构复核：`Build` 仅 `push` 与 `workflow_dispatch` 触发，三个 job（`build` / `cleanup-artifacts` /
  `cleanup-releases`），两个清理 job 都带 `if: always()`，`cleanup-releases` 另与默认分支条件相与；
  `PR check` 仅 `pull_request` 触发、不取号不出包、含独立 `cleanup-artifacts`；两个工作流顶层都有
  `SEGMENTS: '5'` 与 `CLEANUP_APPLY: 'true'`，artifact 四字段齐全。
- 工作流补过滤（`build.yml`）：按新规范 C2 给 `push` 加 `paths-ignore`（`**.md`、`docs/**`、`bad/**`、`LICENSE`、
  `.gitignore`），纯文档推送不建 run、不占 `run_number`；`.github/**` 与代码不进名单，改工作流自身照常触发。
- 帧率录制（`Perf.kt`、`Looks.kt`）：真机日志显示头部「帧率（真机）」只反映最后一次进程（本次导出 `累计 997 帧`，
  等于装包重启后 8 秒），事件流里却有 3203 条 60 帧记录、跨 73 个分钟位 —— 数据在，结论读不出来。三处改：
  ① `dispatchTouchEvent` 切手势窗口，按下到抬起单独统计，抬手**无条件**写一行
  `帧率手势 <起>-<止> 帧=N 平均=Xms（Yfps） 掉帧=J(P%) 卡顿=S 最长=Wms` 进事件流，
  合并导出后跨进程保留；② 卡顿段一结束立即收口窗口：原来必须凑满「连续 30 帧不卡」才收口，滑动中 stall 会把
  `gap` 反复清零，密度高的卡顿只能等手停下来才落盘 —— 导出头部那 10 个窗口全是 8 秒静止画面里的
  `帧=30 掉帧=1(3%) 最长=16ms`，就是这个原因；改成卡顿段一结束就收口（窗口至少 20 帧），卡顿与前后文一起落盘；
  ③ 导出头部新增「最近手势」一行，本进程还没滑动就直说。`ProtoTest` 新增 2 条（手势成行且短触摸不记、
  卡顿段结束即收口），72 条全过。
- 主线程归因（`Perf.kt`、`Hook.kt`、`Qself.kt`）：帧间隔只说「这一帧没出来」，说不清「谁不让它出来」。
  新增 `MainThreadMeter`：装 `Handler#dispatchMessage` 计时（`counted=false`，不进钩子事件流；只认主线程），
  每帧取走主线程耗时写进窗口行 `主线程=均 X.Xms/帧（N 条消息）`；单条消息超过 `32ms` 由看门狗线程每 `20ms`
  一巡、当场抓主线程栈，跳掉 `android.`/`java.`/`kotlin.` 这些壳取前 4 个业务帧。导出头部新增 `主线程`
  与 `卡顿栈` 两行 —— 主线程忙满一帧和主线程闲着但渲染管线堵住，从此分得开。
  热路径只多两次 `nanoTime` 和两个字段写入，字符串只在慢消息上才拼。`ProtoTest` 新增 2 条
  （按帧聚合与点名最慢消息、跳系统帧取业务帧），74 条全过。
- dex 核对（不靠真机日志）：真包 `389.6 MB` / `37` 个 dex，`tools/dexcheck.py --apk /tmp/qq.apk --lint`
  跑出 **702 条符号 0 条对不上**，现有钩子落点在当前 QQ 版本上仍然成立。查 `FPSCalculator` 一系：
  `com.tencent.mobileqq.fpsreport.FPSXListView`／`FPSSwipListView`／`FPSSlideDetectListView` 分别继承
  `com.tencent.widget.XListView`／`SwipListView`／`SlideDetectListView`（全是旧 `ListView` 系），而 AIO 的列表是
  `androidx.recyclerview.widget.AIORecycleView`（`RecyclerView` 派生），`AIOMsgListVB` 也不是它们。
  dex 里没找到给 AIO 记 FPS 的实现，所以这项数字只能自采，没有现成结论可抄。
  另外 `AIORecycleView#dispatchDraw` 自己就在 `save()`/`restore()` 里按 `getChildCount()` 逐个子 View 走一遍
  （由 `com.tencent.aio.pref.a#j` 开关控制），这是「帧时间随可见消息条数涨」的候选，已列入下一步量测。
- 清理脚本（`cleanup.py`）：项目名前缀不再写死，改由 `REPO` 推导（`REPO.split("/")[-1] + "-"`）；artifact 名
  不合前缀的理由串改用前缀变量；两处 docstring 与一处过期注释（号源仍写「git 提交数」）同步改掉。行为等价：
  本仓 `REPO=Sumicya/Qself` 推导值仍是 `Qself-`，现存产物名与 `run_number` 的 join 结论一个不变。
- CI 单测修复（`ProtoTest.kt`、`tools/check.sh`）：`Build` 与 `PR check` 在 `ee83218b` 上双双红在
  `mainThreadMeterNamesTheWorstMessageAndKeepsPerFrameTotals`，报 `java.lang.AssertionError at ProtoTest.kt:840`，
  而 `take()` 的算术是整数纳秒除以 `1e6`，本地两套编译器（2.2.20 / 2.4.20）都算得出 `56.0`。取 JUnit 4.13.2 的
  `Assert.java` 原文核对：两参 `assertEquals(double, double)` 先转成 `assertEquals(null, expected, actual)`，
  落到 `assertEquals(String, double, double)`，而那一支的实现只有一句
  `fail("Use assertEquals(expected, actual, delta) to compare floating-point numbers")` —— **与数值无关，
  写了就必红**；本地替身把这四个数当对象比，所以一路全绿。四处改成带容差的三参
  `assertEquals(56.0, into[0], 1e-9)`；`tools/check.sh` 的 JUnit 替身按真包语义补齐「两参浮点断言 = 编译错误 +
  delta 三参重载」，本地再写两参直接红在编译期（已实测：改回两参后 `check.sh` 退出码 1）。74 条全过。

## 2026-10-27（十一）— 顶栏顶部背景板整条清掉、聊天帧率按「每两帧重录」和「遍历节流」两处真开销收，新增通知现代化

- 顶部背景板（`Looks.kt`）：主人指出顶上那条背景板挡着。原先只清了顶栏自己和 `root`／`outer`／父容器四层底色，
  中间任意一层容器的不透明底色都会把顶上整条盖住。现在从顶栏一路往上扫到 `decor`，逐层清空并记住原值，
  关开关时按原样还回去，消息与壁纸能透上来。
- 聊天帧率（`Glass.kt`、`KernelBar.kt`、`Looks.kt`、`Chat.kt`）：真机日志里进聊天后的窗口长期 `24.9~27.1ms`（约 `37~40fps`），
  而玻璃板的底图是「把背后整棵子树重画一遍」。四处改：① 玻璃底图与顶栏三胶囊的共享底图、输入区 KernelSU 底图、
  底栏合成底图，全部改成**每两帧重录一次**（按帧不按时间 —— 25ms 一帧时按时间节流等于没节流）；
  ② 不透明且无滤镜时不再为每颗胶囊开 `saveLayer`；③ `AppearanceObserver` 的整树遍历从「每次全局布局都走一遍」
  改成最快 200ms 一次，`trim()` 先按最便宜的判据出局、`windowY()` 复用同一个数组、开关值一轮只读一次
  （原来每个视图各查一次 `SharedPreferences`）；④ 连发头像的正文上下界改用复用矩阵，不再每帧每行 `new Matrix()`。
- 通知现代化（新增 `Notify.kt`，开关在「系统与诊断」）：`NotificationManager#notify` 落盘前把 QQ 的消息通知换成
  `BigTextStyle`，标题保留、正文展开成完整内容，并打开时间显示；带自定义 `RemoteViews` 的通知一律放行不动，
  改动通过 `chain.proceed(新参数)` 回填，不重调 `notify`，无递归风险。
- 工作流（`.github/workflows/build.yml`）：两个清理作业补 `if: always()`。此前只写 `needs: build`，构建一红清理整体跳过，
  失败运行只吃号不清理，保留上限当场失效。
- 通知现代化收敛（`Notify.kt`）：判断逻辑抽成纯函数 `notificationText()`（取大文本或正文、已经展开的不重建、
  标题 120 / 正文 400 字符截断），`ProtoTest` 新增三条断言覆盖展开、跳过与截断；没有正文可展开的通知直接放行。
  功能改为默认开（其余交付功能均为默认开，且改样式失败时仍按原样发出），开关保留在「系统与诊断」。
- Ark 卡片越界（`Chat.kt`、`MessageStyle.kt`）：卡片按自己排版后的宽度整块绘制。截图逐像素量到气泡底板 `x=105..1078`、
  卡片蓝框 `x=107..1097` —— 左侧对齐、右侧多出 `19px`。裁掉会把按钮右边缘切掉，所以改成量出卡片画到的最右像素、
  给气泡补等宽右侧内边距，底板长到和卡片一样宽；补完再量一次，无效就退回原值，两种结果都写日志。
  类名判定抽成纯函数 `looksLikeArkCard()`，`ProtoTest` 用真机出现过的类名正反各跑一遍。
- 帧率采样（`Perf.kt`、`Looks.kt`）：采样回调原来无条件每帧重投，屏幕静止时也把进程钉在最高刷新率；
  真机 14 分钟攒出 101132 帧（≈ 14 × 60 × 122），「累计帧数」量的其实是跑了多久。现在由 `ViewGroup#dispatchDraw`
  钩子在真正绘制时唤醒，空闲就停；空闲后的第一帧不计入帧耗时。附带把通知现代化的命中情况记成计数（每 100 条一行）。
- 设计取向：从主人已有反馈里提炼成七条（顶栏落点、顶栏不留下透明底色、安全区只找落点、零描边、不擅自降材质、
  底板跟着卡片长、消息穿底与底部让位、性能真修、群名自设不修、待办不许删了事），每条附日期与关键词；
  2026-10-28 按规范「该文件仅 Selffont 维护」撤出本仓，内容留在 git 历史与本条记录里。
- 文档：README 源码清单补 `Notify.kt`（十四个文件），「项目限制」补上只有真机能判的三项与 Ark 卡片的现状；
  「自由化」补通知现代化条目；顶栏安全区那段按现行实现重写——上一版百分比滑条与 `safeTopPercent()` 已不存在，
  现行是「固定停在状态栏／摄像头下沿 + 面板滑条 `0~48dp` 只往下让」，并写明 `26.10.24.3.3165` 那批日志属旧策略。
