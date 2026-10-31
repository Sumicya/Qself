# CHANGELOG

## 2026-10-31（十八）— 版本号按新规范收口：月日不补零、断言收紧、不合规产物判删

- 规范生效点随之更新：selfs open PR #13 head `278bf9b1`（2026-10-31 09:40:03 UTC+8）取代
  `main` @ `adbcf50a`。新版第「六、仓库与工程 / 版本口径」要求 `yy.m.d` 的月与日一律不补零，
  取号脚本必须断言 `^\d{2}\.(1[0-2]|[1-9])\.(3[01]|[12][0-9]|[1-9])\.([1-9]\d*)\.([1-9]\d*)$`，
  且实现不得依赖 `%-m` / `%-d`（非 glibc 平台不生效）。
- `build.yml`：`local_day()` 从 `strftime("%y.%-m.%-d")` 改成整数拼接 `f"{d.year % 100}.{d.month}.{d.day}"`；
  版本断言从宽松的 `\d{1,2}` 换成规范给的那条，`26.11.01.x.y`、`26.10.31.0.368`、`26.10.31.4.0368`
  这类都直接退出。自测：`26.10.31.4.368` 与 `26.11.1.3.400` 通过，`26.11.01.3.400`、`26.10.31.0.368`、
  `26.10.31.4.0368` 被拒。
- `cleanup.py`：清理判据一补「段里有前导零」分支，补零产物按不合规产物删除并打印原因。
- 自证：`python3 -m py_compile` 通过；从 `build.yml` 里抽出内嵌 Python 单独 `compile()` 通过（39 行）；
  上述 6 个版本串逐条跑断言，结果与规范一致。

## 2026-10-31（十七）— 每帧少走一遍 ViewGroup 钩子，跨帧消息不再重复计时

- 帧率采样不再搭 `ViewGroup#dispatchDraw` 的全局钩子（`Looks.kt`）：这条钩子每个 ViewGroup 每帧都要过一遍
  （真机日志 `26.10.30.4.362` 里记到 `67735` 次），原来每次都要做一次 `FrameMeter.noteDraw()`
  （两次 volatile 写 + 一次 lambda 调用）和一次钩子计数（`ConcurrentHashMap` 查表 + `AtomicLong` 自增）。
  现在「这一帧真的画了」由根视图的 `OnDrawListener` 给，一次绘制只来一次；钩子本身 `counted = false`，
  钩子体只剩一次字段读（`background is Glass`）与「没有玻璃就立刻走」的空表判断。玻璃合成路径不变。
- 跨帧消息重复计时（`Perf.kt`）：`MainThreadMeter` 原来把一条消息的**全部**耗时记在它 `exit` 的那一帧，
  一条 200ms 的消息会让那一帧的「主线程合计」远超帧间隔 —— 真机日志里那条
  `帧率窗口 … 主线程=均75.6ms/帧（4096 条消息）` 配 8.5ms 的帧间隔，就是这个口径错。
  新增 `ThreadAccounting`：消息跨帧时在每次取帧前先切走「已经过去的那一段」，`exit` 只补剩下的，
  每纳秒只记一次、每帧拿到的部分不会超过它真正占用的时间。单测用 200ms / 8.3ms 的时间表验算：
  单帧不超过一个帧间隔，24 次取帧加最后一次补足正好 200ms，取完归零不会二次计。
- 每帧分配（`Input.kt`）：`MsgListUnderScroll.syncGeometry()` 原来每帧 `IntArray(2)` 新建一个数组取窗口
  坐标，改成复用同一个字段；内边距只在真的变了时才 `setPadding`，这点没变。
- 连发合并头像的可判定化（`Chat.kt`）：规则是「合并组默认居底」，但以前只有主观判断。现在整组首尾都在屏里
  （`hasStart && hasEnd`）时，若头像底边与组内容底边相差超过 1px，就写一行
  `头像未居底 : 组底=…px 头像底=…px 差=…px`（两秒最多一行）。组被裁掉一半时本来就要贴视口边，不判。
  这一行只报告不修正 —— 没有真机证据前不去猜公式。
- 自证：`tools/check.sh` 编译 137 个类、80 条测试全过（新增跨帧切分 1 条），`dexcheck lint bad = 0`，
  四个改动文件无新增编译告警。

## 2026-10-31（十六）— 补上 GPL-3 §5(d) 的「关于」入口，补状态栏渐变的模型渲染图

- 许可证用尽义务（`Knob.kt`、`app/src/main/assets/GPL-3.0.txt`、`README.md`）：GPL-3 §5(d) 要求交互程序
  显示版权、无担保与许可全文入口，条文写的是「逐条落地，不靠口头声明」。设置面板新增「关于 Qself」，
  点开显示版本、`Copyright (C) 2026 Sumicya`、GPL-3.0-or-later 与无担保声明，「许可全文」再点一页
  读随包的 `assets/GPL-3.0.txt`——该文件与仓根 `LICENSE` 逐字节相同（`35149` 字节，已用 `cmp` 核对），
  不另写摘要。读不到时照实显示读取失败，不伪造正文。README「许可证」章补同一条说明。
- 状态栏渐变图（`docs/status-fade-194px.png`）：左半是改动前的实心 `#0F1113` 与 y=194 的硬边，
  右半是仓里 `statusFadeColors()` 那张颜色表逐行合成的结果，另附 `alpha(y)` 曲线（实线是本行曲线，
  点线是线性渐变对照，圆圈标 y=141 状态栏底）与逐行台阶柱（红虚线是 `6/255` 上限，最大 `2/255`）。
  图上写明**这是模型渲染，不是真机截图**；真机数字只能由设备运行期写出的
  `状态栏渐变 : … Skia 读数 …` 那一行给出，本轮没有设备导出，所以没有该行。
- 自证：`tools/check.sh` 编译 136 个类、79 条测试全过，`dexcheck lint bad = 0`，`Knob.kt` 无新增编译告警。

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
