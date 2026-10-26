# CHANGELOG

## 2026-10-26（十）— 顶栏回到摄像头下沿（不再藏状态栏）、修掉主线程三万分之一秒级的日志开销与玻璃底图整屏重画，并按新版 GLOBAL.md 清掉被禁工具

- 顶栏位置按主人的话改口（`Looks.kt`、`Input.kt`、`Knob.kt`）：上一版把系统状态栏整个藏掉、顶栏顶到 `y=0`，主人判定「不是顶到 y=0 是摄像头下面或者原始位置」「状态栏没说要消失 实测可以冒出」。撤掉 `insetsController.hide(statusBars)` 那套机器（连同每 500ms 巡检），改量为 `max(状态栏高, 摄像头挖孔下沿)`（真机 `141px`）当硬下界：顶栏固定停在这条线以下，滑条从「安全区百分比」改成「顶栏再下移 0–48dp」，压到底也只会停在摄像头下面。消息区上沿同样对齐这条线，顶部内边距取标题栏真实底边（`141+99=240`），滚动时仍从玻璃胶囊底下穿过。留在旧版本被藏掉状态栏的现场，恢复前台时还原一次。
- 帧率（主人实测进聊天只有 `30fps`）：日志里 `帧率窗口` 长期 `平均 24.9~27.1ms`（约 `37~40fps`），而同期 `ViewGroup#dispatchDraw` 钩子被调 `236926` 次。两处真实开销已改：① `DiagLog.nowStamp()` 原先每条日志都 `new SimpleDateFormat`（一次约 10µs），全量日志开着时 QQ 一秒上千行，改成每线程复用一个实例；② 录制端不再对每一行跑 `uiKeywords` 正则，「UI/AIO/错误」小节改为导出时从同一份记录里筛。玻璃底图也不再整屏重画：`Glass.recordBehind()` 把每个兄弟视图裁到「玻璃板正下方那一带（`bounds` 加边距）」再画，之前消息列表会被整张重画一遍。
- 帧耗时归因（`Perf.kt`）：新增 `CostMeter`，只记「玻璃合成 / 头像浮绘 / 消息几何 / 顶栏」四个环节的纳秒数与次数，不分配对象；每 60 帧往事件流写一行「帧耗时 最近 60 帧 x.xms（yy fps）；「某环」z.zms/次」，导出时另有一行汇总。帧率只有总数时只能猜是谁拖慢的，有这一行才能指着某一环说话。
- 按新版 `GLOBAL.md`（`f8f51cf5`）清工具：删掉被点名的 Agent 汇报辅助脚本 `tools/report_links.py` 与其测试、七份读源码做字符串匹配的伪测试、两份 `make_*_preview.py` 生图脚本、`.github/scripts/test_cleanup.py`、`TODO.md`、`docs/design-principles.md`；`ci/` 目录并入 `tools/`（`check.sh`、`check-shaders.sh`）。工作流按 C1–C6 重排：`build.yml` 三作业（`build` / `cleanup-artifacts` / `cleanup-releases`）、`pr-check.yml` 两作业（`check` / `cleanup-artifacts`），顶部注释不超过 5 行，去掉私加的 `::error::` 注解步骤，清理作业统一传 `RUN_ID`。
- 版本口径换成 CI 运行数（同一份规范）：总序号取 `github.run_number`，当日序号按「当日现存 `build.yml` 运行最小 `run_number`」与「当日合规产物名反推 `r0-d0+1`」取小值锚定，同一 run 重试不换号；不再用 `git rev-list --count`，也不再需要完整历史。

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

