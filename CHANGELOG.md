# CHANGELOG

## 2026-10-24（九）— 面板「消息外观」按四组重排并钉死滑条与标题的配对，群聊顶栏去掉安全区后改为组件重定位而非相撞

- 面板「消息外观」重排（`Knob.kt`）：气泡边距的滑条原先被 `grid.addView` 加在整节最后，和它自己的标题隔着一整片顶栏滑条；现在按「消息（头像 / 气泡）→ 玻璃 → 顶栏 → 时间格式」四组排，小组内标题紧跟对应滑条。新增契约（`tools/test_switch_groups.py`）：六条滑条必须紧跟自己的标题、四个小组顺序固定，再挪错位就红。
- 群聊顶栏安全区改为组件重定位（`Looks.kt`）：真机快照（群聊，`26.10.24.10.3172`，`SAFE_TOP=100`）实测状态栏 `141px`、顶栏容器 `0x7f0a6bc6 pad=[0,141,0,0]`、标题栏 `0x7f0a6c15 b=[0,141,1272,240]`、返回键 `y=167`、消息区 `y=239` —— 一旦把安全区去掉，标题栏被推到 `y=0`、返回键落到 `y=26`，正好压在状态栏底下，与主人说的「这个安全区去掉就和状态栏撞了」一致。新增 `compensateTitleSafeTop()`：把去掉的那段补成标题栏自己的内容内边距，玻璃板从 `y=0` 铺满整个顶部（`0~240px`），按钮仍留在 `141px` 以下；标题栏最小高度改为 `50dp + 补进去的那段`，否则补的内边距会把按钮挤出可视区。滑条在 `100%` 或开关关闭时还原为 `0`。
- 继承上一会话分支并收口文档口径：上一会话分支 `arena/c9924db7-qself` 的 3174 个提交按原样快进继承（不压缩成一个），核对 `HEAD` 已覆盖其全部提交后删除该远端分支；按 selfs 新规范重读全文（只留四个要领与底线，`REPOS.md` 降为参考），README「核落点」的符号数由旧值 `451` 改为与 `tools/symbols.txt` 实测一致的 `702`，「源码结构」由九个文件改为实际十三个并补齐 `MessageStyle.kt` / `DiagLog.kt` / `LogMerge.kt` / `Perf.kt`；全仓无 `AGENTS.md`，README 章节顺序与末行规范指针不变。

## 2026-10-24（八）— 拆掉「不点导出也自己冒日志、还带 (1)」与帧率判据被抖动带塌两处真机缺陷

- 日志不再自己往外吐（`DiagLog.kt`）：抓界面树的 `captureActivitySnapshot` / `captureViewRootSnapshot` 收尾都会调 `maybeAutoFlush`，而它每 2.5 秒就把整份日志写进 Download —— 主人不点导出也会自己冒出一个文件。现在自动落盘只写本应用私有目录（充当下次导出的累计底稿），间隔放宽到 60 秒；Download 那份只有点「导出全量日志」才写，同名直接覆盖。
- 编号副本的源头拆掉（`DiagLog.kt`）：每次写文件都往 MediaStore 插一条同名 `qself-full-log.txt` 记录，Download 里已有同名文件时 MediaStore 每次另存一份 `qself-full-log (1).txt`、`(2).txt`…… 主人那儿堆到 `(9)` 就是这么来的，而 `cleanupPriorExports` 删不动 MediaStore 管的文件，于是「清理历史副本 4 个」每次导出都报。整块删除 MediaStore 写入，只保留直接文件覆盖。
- 帧率周期不再被抖动带塌（`Perf.kt`）：真机日志（`26.10.24.10.3172`）报出「掉帧 35650（48%），错过同步 100840 次」——刷新周期取的是观测最小值，Choreographer 偶尔给出 1~5ms 的抖动，周期被带到 5ms 上下，8.2ms 的正常帧整片被判成掉帧。改成取环缓冲里落在 4~25ms 区间的帧间隔的 10 分位，每 120 帧重估一次；错过同步次数改成「帧长 ÷ 周期 − 1」。按真机分布复现：74530 帧，掉帧 230（0%）、错过同步 839 次、卡顿 30、最长 996ms、帧周期 8.2ms（约 122Hz）。
- 测试：`ci/check.sh` 125 个类、66 个用例通过（新增 `frameMeterPeriodIgnoresSpuriousShortFrames`：偶发 1.2ms 抖动不得把周期带偏、正常帧不得被判成掉帧）。

## 2026-10-24（七）— 30 个开关按六组重排并统一动词（藏/去/屏蔽 收口成 隐藏/停止），分组与命名写成静态契约；帧率采样改按真机刷新周期判掉帧，界面树快照开始计时

- 开关分组（`Qself.kt`、`Knob.kt`、`DiagLog.kt`）：30 个开关原先按添加顺序一列平铺，找不着也记不住。现在按「底栏与导航 5 / 输入与面板 3 / 消息 12 / 转发与账号 4 / 特效与上报 4 / 系统与诊断 2」六组摆，菜单里每组一个标题，导出日志的 `Switches` 也从一长条改成按组缩进。新增 `featuresByGroup()` 与 `featureGroupGaps()`：漏分组、重复分组、写了不存在的开关，安装时直接打进 Qself 日志，不靠肉眼核对。
- 开关改名，同一个意思只用同一个动词：`Monet取色→壁纸取色`、`藏频道动态→隐藏频道动态`、`TG输入栏→输入栏药丸`、`标题栏侧栏精简→侧栏精简`、`统一气泡→气泡统一`、`统一字体→字体统一`、`去头像挂件→隐藏头像挂件`、`消息内时间→消息时间`、`消息显示At对象→At可点`、`语音倍速记忆→语音倍速`、`左滑回复不设限→左滑回复`、`显示具体未读条数→未读实数`、`屏蔽红点引导→隐藏红点引导`、`去会员等级→隐藏会员等级`、`屏蔽轻互动→停止轻互动`、`屏蔽表情雨→停止表情雨`、`屏蔽统计上报→停止统计上报`、`屏蔽崩溃上报→停止崩溃上报`——藏东西一律叫「隐藏…」，停行为一律叫「停止…」。改名会重置这 18 个开关的偏好（默认全是开）。
- 帧率采样按真机刷新周期判掉帧（`Perf.kt`）：真机日志（`26.10.24.8.3170`）显示累计 77374 帧、平均 12.5ms、各窗口平均 8.8~9.0ms —— 这台机器是 120Hz，写死的 16.7ms 预算会把一半掉帧漏掉。现在周期从观测到的最短帧间隔取（测不出来兜底 60Hz），掉帧线定在 1.5 个周期，并额外统计「错过同步次数」（120Hz 上一帧 25ms 是错过两次同步，不是一个坏帧）；日志头新增「帧周期 8.3ms（约 120Hz）」。
- 界面树快照开始计时（`DiagLog.kt`）：真机日志里出现过 972ms 的最长帧，而快照必须在主线程读视图树，是首要嫌疑。现在每次快照结束把耗时写进 Qself 事件流（≥8ms 才记），下份日志能直接指认那 972ms 是不是它干的。
- 测试：`ci/check.sh` 125 个类、65 个用例通过（新增 `frameMeterDerivesJankBudgetFromTheObservedRefreshRate`）；新增 `tools/test_switch_groups.py` 五项契约（每个开关恰好进一个组、组名不重复、菜单按组渲染而不是 `features.forEach` 平铺、禁用 屏蔽/藏/去 三种动词、名字不超过 6 个汉字）。

## 2026-10-24（六）— 全仓自查：给全量日志加字节预算与单条截断、导出整段挪离主线程、新增真机帧率采样，并按 selfs GLOBAL.md / REPOS.md 逐条核过工作流与文档

- 全量日志体积收敛（真机 19MB → 稳定 6.1MB，`LogMerge.kt`）：只按 12000 条封顶不够 —— [5] 一节 12000 条实测 13.8MB（中位 167 字节、p99 15KB 的 Notification Channels / 配置 dump）。新增 `MAX_MERGED_SECTION_BYTES = 2,000,000`（从最老往回丢）、`trimRecord()`（单条超过 4096 字符从中间掐断、保留头尾，幂等）与 `capByBytes()`；同一份 19,368,750 字符的真机日志按新逻辑连续自合并三次，输出稳定在 6,109,393 → 6,111,191 → 6,111,783 字符，[5] 由 12000 条（13.8MB）降到 4188 条（1949KB）、[6] 降到 9851 条（1952KB），合并耗时 946ms / 359ms / 305ms。
- 导出不再冻住主线程（`DiagLog.kt`）：原先 `exportFullQqLog` 在主线程 `readText()` 19MB 的历史文件并合并，随后 `writeLogFiles` 又把同一份历史文件再读一遍、再合并一遍。现在读历史 + 构建 + 合并 + 写盘整段进 `ioExecutor`，`prebuiltReport` / `priorLength` 直通，主线程只剩界面快照、Toast、授权弹窗与分享面板。
- 真机帧率采样（`Perf.kt`）：`FrameMeter` 借 `Choreographer` 每帧只记一个时间戳，导出日志头给出「累计帧数 / 平均帧长 / 掉帧率 / 卡顿帧数 / 最长帧 / 最近 600 帧」，并把连续卡顿的「操作窗口」（不少于 20 帧、末尾 30 帧无卡顿即收口，最多留 12 段）写进 Qself 事件流；事件出口用 `frameEventSink` 注入，帧率统计在没有 Android 的 JVM 上也能单测。「卡不卡」从此有真机数字，不再靠自评。
- 规范核对（`Sumicya/selfs` `GLOBAL.md` / `REPOS.md` @ `f4033232`）：两个工作流 `Build` / `PR check` 触发分离且 `SEGMENTS` / `CLEANUP_APPLY` 同值、共用 `concurrency group`、`fetch-depth: 0`、artifact `Qself-版本` / `retention-days: 5` / `if-no-files-found: error`、Release 与 tag 的删除只授 `contents: write`；实测 Release 0 个（上限 1）、artifact 5 个（上限 5）、每个工作流 5 条运行（上限 5）。CHANGELOG 只留最新 5 条，其余 7 条移入 `bad/CHANGELOG-history.md`；README 六个二级章节顺序与固定末行核对通过，无图片与徽章。
- 测试：`ci/check.sh` 编译 125 个类、64 个用例通过（新增 `trimRecordCutsTheMiddleAndIsIdempotent`、`capByBytesKeepsTheNewestAndAlwaysKeepsOne`、`mergeDiagnosticReportsTrimsOverLongStreamingRecords`、`frameMeterCountsStallsClosesWindowsAndIgnoresHugeGaps`）。

## 2026-10-24（五）— 真机核对导出合并去重与清理生效，纠正「SplashActivity=主页」误判，新增 AIO 顶栏一行几何账

- 用回传的 2 份真机日志（均为 `26.10.24.4.3166`，`4713064` 字节，md5 同为 `c1b7b505dc0ea524e79f0d5738104a3d`，同一份文件的两个副本）核对上一版的合并与清理，**确认已生效**：
  - 报告头直接写明：`合并 : 已与上一次导出按小节合并去重（上次 4513568 字节）；导出前清理历史副本 4 个，同名文件只保留 qself-full-log.txt`。
  - `[4]` Qself 事件里留下 4 条连续导出记录：`2153653 → 3342740 → 4236862 → 4372001 → 4513568` 字节，每次导出前都清掉 `4` 个副本（`getExternalFilesDir` / `externalCacheDir` / `filesDir` / Download 四个落盘位置各一份），证明反复导出只会累加内容、不会再堆编号文件。
  - 合并后单文件小节账：`[3]` 视图树快照 `10` 块、`[4]` Qself 事件 `1290` 条、`[5]` UI/AIO/错误 `QLog` `3764` 条、`[6]` 全量 `QLog` `9161` 条，共 `4.71MB`。
- **纠正上一轮的误判**：`AIO-Header:*` 快照的活动名是 `SplashActivity` **不代表当时停在主页** —— QQ NT 的聊天页就是以 `SplashActivity` 作为主 Activity 承载的，快照里 `AIOTitleRelativeLayout 0x7f0a6c15`、`AIOEditText 0x7f0a2c94(input)`、`AIOInputSendBtn 0x7f0a61c0(send_btn)`、`AIOAnimationContainer 0x7f0a02b5`、`ChatFragmentRootView` 全都在同一棵树里，是**真的 AIO**。上一轮据此让用户「进任意聊天窗口再导出一次」是错误指令，那一趟白跑了。
- 新增 `Looks.kt` `aioGeometry(bar)`：开启「全量日志」时随 AIO 顶栏布局快照（3 秒节流）打**一行几何账** —— `状态栏高度 / 顶栏容器 paddingTop / 标题栏屏幕 y 与高 / 消息区屏幕 y / 返回键屏幕 y / 已去掉的安全区像素 / 仍开着 fitsSystemWindows 的祖先类名与 paddingTop / 当前 percent 与 shift`。只读，不改任何布局；`tools/test_message_style.py` 增加契约断言。
- 顺带在真包 `classes4.dex` 里定位到 `com.tencent.aio.part.fitwindow.vb.FitSystemWindowVB#onCreateView` 会调用 `View#setFitsSystemWindows(...)`（QQ 把系统窗口内边距贴回 AIO 内容区的入口，此前全仓未挂钩也未记录）。本次**只把它读出来记进几何账**，是否要动等真机 `SAFE_TOP=0%` 的日志出来再定，不臆测。
- 真机当前设置核对：`SAFE_TOP=80` / `SAFE_TOP_SHIFT=0` 时，主页顶栏内容落在屏幕 `y=61..160`（头像 `0x7f0a3245` `mar=[0,61,0,0]`，会话列表 `0x7f0a0e96 pad=[0,160,0,107]`，搜索框 `y=160`），AIO 顶栏外层 `0x7f0a6bc6 padTop=113`、`AIOTitleRelativeLayout` 落在 `y=113..212`、返回键 `y=139..186`；`MainResourceConfig#n orig=95px -> 76px`。同一份日志里 `sysUiVis=0x700`（`LOW_PROFILE|HIDE_NAVIGATION|FULLSCREEN`）且 10-23 那批截图顶部 `0..63px` 全为空，说明这台机上状态栏是隐藏的、顶部并没有系统图标要避让——`0%` 并不会撞状态栏，想让顶栏更贴屏幕上沿直接用「顶栏安全区」百分比或「顶栏上下微调」即可。

