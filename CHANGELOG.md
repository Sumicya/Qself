# CHANGELOG

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

## 2026-10-24（四）— 全量日志导出改为「合并去重 + 导出前清理」，反复导出只留一个 qself-full-log.txt

- 用户回传 10 份真机日志（均为 `26.10.24.3.3165`，OnePlus `PLC110` / Android 16 / `1272×2800` / `density 1.9761429`，采集区间 `03:00:07.750` → `03:00:57.427`，其中 `qself-full-log.txt` 与 `(9).txt` 字节完全相同）后指出「清理和 log 合并是不是没做」。逐行核对结论：**确实一个字都没做** —— 仓库里只有 CI 侧的 `cleanup-artifacts`（GitHub 只保留最新 5 个构建产物，与手机无关），设备端没有任何清理与合并代码；`writeLogFiles` 每次都用同一个 `DISPLAY_NAME = qself-full-log.txt` 往 MediaStore 插入**新行**，系统自动把重名文件改名成 `(1)(2)…`，于是点 10 次就堆出 10 个副本，且每份都只保留最后一次采集的环缓冲内容、老记录白白丢掉。
- 本次修复（新增 `LogMerge.kt`，改 `DiagLog.kt`、`Knob.kt`、`ProtoTest.kt`、`tools/test_message_style.py`）：
  1. **与上一次导出合并去重**：先从上一次落盘的 `filesDir/qself-full-log.txt` 读回（应用私有目录，无需任何存储权限），`mergeDiagnosticReports(old, new)` 按 `--- [n] ---` 小节拆开后分别合并 —— 报告头与 `[2]` 钩子调用次数取最新一份（次数本就累计，叠加没有意义）；`[3]` 视图树快照按 `=== 标题 ===` 分块取并集（同标题新块覆盖旧块，超过 `MAX_MERGED_SNAPSHOTS = 16` 淘汰最老）；`[4][5][6]` 三个流式小节按「时间戳起始行 + 其续行」切记录后整条去重、按 `HH:mm:ss.SSS` 排序、上限 `MAX_MERGED_RECORDS = 12000` 保留最新，`headerWithCount` 重建小节标题里的 `(N)`。环缓冲（`[6]` 单份只留 3000 条）挤掉的老行因此会被捞回来 —— 一次导出就是到此刻为止的完整累计，再也用不着发好几个编号文件。
  2. **导出前清理历史副本**：`cleanupPriorExports(ctx)` 先按 `DISPLAY_NAME LIKE 'qself-full-log%'` 查询 MediaStore Downloads 的全部历史行逐条删除（正好覆盖 `(1)…(9)` 这类系统自动改名的副本），再扫描 `getExternalFilesDir` / `externalCacheDir` / `/sdcard/Download` 三个目录删除同名前缀文件；别的应用写进 Download 的副本删不掉时系统会抛 `RecoverableSecurityException`，此时记下它带的 `IntentSender`，写完新文件后弹一次系统授权框，用户点允许之后下次导出就真的只剩一个文件。
  3. **永远只写一个文件**：所有落盘路径统一使用 `LOG_FILE_NAME`，MediaStore 侧只在清理之后 insert 一次；报告头新增一行「合并 ：已与上一次导出按小节合并去重（上次 N 字节）；导出前清理历史副本 M 个，同名文件只保留 qself-full-log.txt」。
  4. **重复导出不再刷屏**：新增 `reportDeltaRecords(old, new)` 只统计流式小节里本次相对上次的**新增记录数**，`LSPosed` 日志与 `logcat -s QselfDiag` 只吐这一行增量（无新增时直接提示「无新增记录，完整累计日志见 /sdcard/Download/qself-full-log.txt」）；剪贴板仍是 320KB 头尾截取，完整内容在文件里。
  5. **可离线回归**：合并/去重逻辑整体抽到 `LogMerge.kt`，零 Android 依赖，可用真机日志在 JVM 上直接跑。`ProtoTest` 新增 3 条用例（并集去重与排序、增量统计、上限与快照淘汰），并用用户回传的 10 份日志按时间顺序逐份回放：`1.60MB → 4.36MB`（9 次再导出累计新增 `8340` 条），`[6] 全量 QQ QLog` 由单份上限 `3000` 条累计到 `9367` 条，`[3]` 快照由 4 块并到 15 块；重复喂同一份内容时增量 `0`、长度不变（幂等）。`tools/test_message_style.py` 增加契约断言：导出必须先合并再清理、只写 `qself-full-log.txt`、`LogMerge.kt` 不得引入 `import android`。
- 顺带用这 10 份日志核对了上一版（`26.10.24.3.3165`）顶栏安全区修复，**已确认生效**：`MainResourceConfig#n orig=95px -> 0px (percent=0% shift=0dp)`（3 处）、`AIO 顶栏 outer 安全区 141px -> 0px`（`03:00:19.791`）、`FrameLayout 0x7f0a6cfa(5un) b=[0,0,1272,99]`（原 `167`）、`TitleAreaHeadLayout 0x7f0a3245(e3u) mar=[0,0,0,0]`（原 `[0,68,0,0]`）、`ClipSkinnableRecycleView 0x7f0a0e96(u3q) pad=[0,99,0,107]`（原 `167`）、`TitleAreaRightLayout 0x7f0a120a b=[0,0,1272,99]`。仍缺一张**真 AIO** 界面树：日志里所有 `AIO-Header*` 快照的活动名都还是 `SplashActivity`（即用户全程停在主页/启动页），需要进任意聊天窗口再导出一次才能拿到 AIO 顶栏真实树。
- 按「旧图全清」清库：仓库不再保存任何图片，`docs/` 下此前那两张预览图全部删除，`README.md` / `CHANGELOG.md` / `docs/message-ui.md` / `bad/CHANGELOG-history.md` 中所有指向它们的链接与图片嵌入同步移除，不留失效引用；生成脚本 `tools/make_log_merge_preview.py`、`tools/make_safe_top_preview.py` 暂时保留待你确认是否一并删除，预览内容改为直接在回复里渲染。

## 2026-10-24（三）— 按真机全量日志定位顶栏安全区残余根因并实装「顶栏安全区 / 顶栏上下微调」滑条

- 针对用户回传的真机全量日志（三份 `qself-full-log.txt`，`26.10.24.2.3164`，OnePlus `PLC110` / Android 16 / `1272×2800` / `density 1.9761429`）逐行核对，定位「顶栏安全区还是没去」的真实根因并修复（`Looks.kt`、`Input.kt`、`Knob.kt`、`MessageStyle.kt`、`DiagLog.kt`、`ProtoTest.kt`、`tools/test_message_style.py`）：
  - **日志实测证据**：`insets: statusBars=[0,141,0,0] navBars=[0,0,0,0]`、`statusBarColor=#00000000`、`winFlags=0x81810100`、`sysUiVis=0x700`；`TitleAreaHeadLayout 0x7f0a3245(e3u) mar=[0,68,0,0]`、`FrameLayout 0x7f0a6cfa(5un) b=[0,0,1272,167]`、`ClipSkinnableRecycleView 0x7f0a0e96(u3q) pad=[0,167,0,107]`、`MainResourceConfig#n -> 68`；AIO 侧 `QQBlurViewWrapper 0x7f0a0957` 与遮罩 `0x7f0a3715` 已 `vis=G alpha=0.0`、`AIOTitleRelativeLayout` 背景已是 `sumicya.qself.HeaderPillsDrawable`，说明背景/模糊/遮罩确已清除，残余的就是顶部预留高度本身。
  - **根因：`reducedSafeTop` 自带「至少保留七成」下限**：旧实现 `minTop = origTop * 0.72f`、`baseTop = origTop - 16dp`，真机 `141px` 只能压到 `109px`（日志里 `reducedSafeTop=109`），`68px` 只能压到 `49px`，等于没去掉；同时主页完全依赖 `MainResourceConfig#n` 生效，而日志显示该钩子返回仍是 `68px`，顶栏容器与列表内边距因此纹丝不动。
  - **改为纯比例策略并给到滑条（默认 `0%` 完全去掉）**：`reducedSafeTop(origTop, dp)` 现按 `safeTopPercent()`（`SAFE_TOP`，`0~100%`，默认 `0`）取百分比再加 `safeTopShiftDp()`（`SAFE_TOP_SHIFT`，`-24~+48dp`）的 dp 微调，删除 `0.72f` 下限，只保证非负且不超过 `origTop + 96dp`；`ProtoTest` 断言 `reducedSafeTop(141, 1.9761429f) == 0`、`reducedSafeTop(68, 1.9761429f) == 0`。
  - **主页不再依赖 `MainResourceConfig#n`：新增 `HomeSafeArea` 三件套同 `delta` 上移**：把 `0x7f0a3245` 头像 `topMargin`（真机 `68px`）、`0x7f0a6cfa` 顶栏容器 `translationY` 与 `0x7f0a0e96` 会话列表 `paddingTop`（真机 `167px`）三者按同一个 `delta` 整体上移，命中三件套的页面不再叠加旧的 `-4dp` 子组件微调（避免顶栏被推到窗口上沿之外），关闭开关或 `resetHomeTopBars()` 时三者原样还原。
  - **AIO 消息列表顶部内边距与顶栏联动**：真包 `AIOMsgListGroup.paddingTop = 238 = 141（状态栏）+ 97（标题栏）`，顶栏上移后若列表不动会空出一大段；新增 `aioSafeTopRemoved()`（`HeaderState.applyStyle` 在安全区换算变化时更新并触发 `refreshInputLayout()`），`MsgListUnderScroll.sync()` 据此把 `targetTop` 再减掉同样的像素数。
  - **可核对的诊断增强**：`MainResourceConfig#n` 每次换算写入全量日志（`MainResourceConfig#n orig=…px -> …px (percent=…% shift=…dp)`），AIO `outer`/`root` 安全区换算同样落日志；AIO 顶栏 `onLayout` 完成且尺寸有效后（3 秒节流）自动抓一次带真实尺寸的视图树快照 `AIO-Header-LaidOut:*`（旧版只在预加载阶段抓到 `0×0` 空树）；诊断报告头新增 `SAFE_TOP` / `SAFE_TOP_SHIFT` 参数行。
  - 同步删除上一版预览图并生成新预览图（`tools/make_safe_top_preview.py` 可复现），`README.md` / `docs/message-ui.md` 引用同步更新（面板滑条说明由 4 项改为 6 项）。

## 2026-10-24（二）— 实装全量 QQ 日志与界面树快照录制引擎（`DiagLog.kt` / `全量日志`）及面板一键导出/复制

- 针对用户请求（「写一版录全qq日志的我返日志给你」），新增全量诊断日志引擎 `DiagLog.kt` 并集成至 `Qself.kt`、`Hook.kt`、`Looks.kt`、`Knob.kt`（`tools/symbols.txt` 增至 702 条符号）：
  1. **全量 `QLog` 拦截与调试级别解锁**：挂钩 `com.tencent.qphone.base.util.QLog#isColorLevel()` 返回 `true`（解锁 QQ 内部被 `if (QLog.isColorLevel())` 包裹的 UI/AIO/顶栏/底栏/皮肤调试日志）并拦截 `QLog#addLogItem(byte, String, int, String, Throwable)`，分双环缓冲保存最新 `3000` 条全量 `QLog` 与 `2500` 条 UI/AIO/顶栏/错误专项 `QLog`；
  2. **全钩子调用轨迹与异常采样**：在 `Hook.kt` `hook(...)` 与 `log(...)` 中记录每个已挂钩方法的调用总次数（`hookCallCounts`）、前 10 次调用的 `this`/入参/返回值摘要以及全部钩子前后置异常与线程未捕获异常栈；
  3. **主页 / AIO / 二级页完整 `DecorView` 视图树与安全区自动快照**：在 Activity 进入、AIO 顶栏绑定（`bindHeader`）、二级页 `QUISecNavBar` 绑定与主页顶栏（`homeTopBar`）刷新后自动抓取完整视图树（每个 View 的类名、资源 ID 十六进制与 entry 名、可见性、局部与屏幕坐标、`padding`/`margin`、`translation`、`clipChildren`/`clipToPadding`/`clipToOutline`、背景 Drawable 类型与颜色值、`EdgeTransparentView` `D/i/m` 与 `SkinnableRecycleView` `f` 状态），并在后台自动落盘；
  4. **三通道零门槛一键导出（`导出全量日志`）**：在 Qself 设置面板底部新增「导出全量日志」按钮，点击即刻将诊断报告复制到系统剪贴板、写入 `/sdcard/Download/qself-full-log.txt` 与 `/sdcard/Android/data/com.tencent.mobileqq/files/qself-full-log.txt`、分段输出至 `LSPosed` 日志（`QselfDiag`）并拉起系统分享面板。

