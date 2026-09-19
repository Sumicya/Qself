# NT QQ（QQ 9.x）适配说明

> 结论先行：**v1 的 8 个特性全部按旧版 QQ（8.x）写的，在 NT 版上一个都不生效**
> —— 不是崩溃，是目标类不存在（日志里 `ClassNotFoundException: none of … found`）。
> 真机日志（2026-09-19）已经证实：`QQAppInterface`、`activity.aio.BaseChatItemLayout`、
> `upgrade.UpgradeController`、`statistics.StatisticCollector` 在 NT 上一个都找不到。

## 为什么不能"改改类名"了事

NT（QQ 9.x）把底层换成了 `com.tencent.qqnt.*` 一套新架构：

| 旧版（8.x） | NT（9.x） |
|---|---|
| `com.tencent.mobileqq.app.QQAppInterface` | 内核化，`com.tencent.qqnt.kernel.*` |
| `com.tencent.mobileqq.startup.director.*` | `com.tencent.qqnt.startup.NtStartup` / `NtStartupDispatcher` |
| 聊天界面 `activity.aio.*` | `com.tencent.qqnt.*` + 部分 Compose |
| `statistics.StatisticCollector` | 崩溃/统计上报路径整体重构 |

QAuxiliary 自己也公开说明过"未能对 NT 版本完整适配"（见其频道公告），
所以这条路只能靠**真实类名 + 逐特性验证**推进，猜是猜不出来的。

## 已确认的 NT 入口（来自公开源码/issue 日志）

| 类 / 方法 | 来源 | 用途 |
|---|---|---|
| `com.tencent.qqnt.startup.NtStartup` (`c`/`blockUntilFinish`) | QAuxiliary issue 日志 | NT 启动关键路径，模块早期 hook 点 |
| `com.tencent.qqnt.startup.NtStartupDispatcher` (`c`, `a`) | 同上 | 启动分发器 |
| `com.tencent.mobileqq.startup.director.a` | 同上 | 旧启动壳仍在，调用进 NT |
| `com.tencent.mobileqq.qfix.QFixApplication` | 同上 | Application 壳（热修复） |
| `com.tencent.common.app.BaseApplicationImpl` | QAuxiliary `QAppUtils.java` | 仍存在的公共基类 |
| `com.tencent.qqnt.base.BaseActivity` | 同上 | NT 的 Activity 基类（UI 特性注入点） |
| `com.tencent.qqnt.kernel.api.impl.UixConvertAdapterApiImpl` | 同上 | 内核↔UI 转换 |
| `com.tencent.qqnt.kernel.nativeinterface.MsgAttributeInfo` | `Nt_kernel_bridge.java` | 消息属性（撤回/灰条等） |

这些只是"骨架"。每个特性真正要 hook 的类，必须在**设备上**取。

## 在设备上取真实类名（已实测）

**用户设备实测（QQ 9.2.10）**：APK 389,611,416 字节、**37 个 dex**，
grep 出 **17,389 个 `com.tencent.qqnt.*` 类名**。Termux 一行即可（不依赖模块）：

```bash
su -c 'cp "$(pm path com.tencent.mobileqq | head -1 | cut -d: -f2)" /sdcard/qq-base.apk'
mkdir -p ~/qqdex && cd ~/qqdex && unzip -o /sdcard/qq-base.apk 'classes*.dex' >/dev/null
grep -a -o 'Lcom/tencent/qqnt/[A-Za-z0-9_/$]*;' classes*.dex \
  | sed 's/^[^:]*://' | sort -u | sed 's/^L//; s/;$//; s#/#.#g' > /sdcard/qqnt-classes.txt
```

注意 `grep -a`（dex 是二进制）；`-i` 会把 `dispatch*` 之类误匹配进 `patch` 结果。

### 已从 dump 中看到的真实结构

| 领域 | 真实类名（示例） |
|---|---|
| 聊天界面 | `com.tencent.qqnt.aio.SplashAIOFragment`、`aio.InputChangeEvent`、`aio.MsgRevokeEvent`、`aio.AIOLifeCycleEvent` |
| 聊天历史 | `com.tencent.qqnt.chathistory.service.KernelServiceKt`、`chathistory.ui.document.data.datasource.PlatformSearchDocumentSource` |
| 内核消息 | `com.tencent.qqnt.kernel.api.AIOSendMsgResultData`、`kernel.aio.msg.a` |
| 上报 | `com.tencent.qqnt.aio.adapter.api.impl.AIOReportImpl`、`ReportControllerApiImpl` |

### 从 dump 得到的第一批可攻目标（按可行性排序）

| 目标 | 真实类名 | 说明 |
|---|---|---|
| 热修复 | `com.tencent.mobileqq.qfix.Relax`（含 `$ApplyResult`/`$AssertDisableInstallStubsForClass`/`$RelaxHolder`）、`qfix.common.classloader.DexClassLoaderUtil`、`qfix.redirect.PatchRedirectCenter`、`qfix.redirect.IPatchRedirector`、`common.app.QFixApplicationImpl(Proxy)` | NT 的补丁机制（Tencent QFix + redirect），比旧版 `rfix.lib.*` 更明确 |
| 崩溃上报 | `com.tencent.feedback.eup.CrashReport`(+`$a`)、`com.tencent.bugly.library.Bugly`、`bugly.crashreport.crash.jni.NativeCrashHandler`、`bugly.impl.BuglyInitializer`、`bugly.crashreport.common.config.CrashConfigCreator` | NT 走 Bugly；旧版 hook 的 `StatisticCollector`/`QQCrashReportManager` 已不存在 |
| 聊天界面 | `com.tencent.qqnt.aio.SplashAIOFragment`、`aio.holder.template.BubbleLayoutCompatPress`、`aio.audiopanel.AudioPanelAdapter`、`aio.bottombord...`、**`com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.state.MsgListState`** | `com.tencent.aio.*` 是 NT 的消息列表框架（AIO），`qqnt.aio.*` 是界面层 |
| 启动 | `com.tencent.qqnt.startup.NtStartup(Dispatcher)`、`startup.task.NtTask` | 早期 hook 点（需要时可用） |

**方法签名已取到**（下一步完成）：`tools/nt-scan/qself-scan.py` 在设备上解析 37 个 dex，
按类名前缀输出 `m <flags> 方法名(参数类型): 返回类型` 与字段。默认前缀命中 **118 个类**，
完整结果在 `/sdcard/qself-methods.txt`。

> 这个过程同时修掉了 dex 解析器的两个硬 bug（`class_idx` 错当 string 索引、class_data
> 四组数组的 diff 链跨组累加）——两个都是"不报错但全错"，之前导出的类名全是错的。
> 详见 `tools/nt-scan/README.md`。

## 已实测的真实签名（QQ 9.2.10 / 37 dex）

### qfix（热补丁）

| 类 | 关键方法（真实签名） |
|---|---|
| `com.tencent.mobileqq.qfix.redirect.PatchRedirectCenter` | `static apply(Context, String, String): int`；`static unApply(): void`；`static getRedirector(int): IPatchRedirector`；`static getRedirector(int, short)`；`static fakeGetRedirector(int[, short])`；`private static loadPatchClasses(String): SparseArray`；`static COPY/unzipConfig` |
| `com.tencent.mobileqq.qfix.Relax` | `static apply(Context, File, File, File, boolean): int`；`static apply(Context, File, File, InputStream, boolean[, boolean]): int`；`protected static applyInternal(Context, File, File, InputStream, boolean, boolean): int`；`applyPatch(Context, File, File, InputStream, boolean): int`；`private static native relax(Context, Method, Method, ClassLoader, File, byte[], boolean): int`；`static getInstance(): Relax`（单例在 `Relax$RelaxHolder.sInstance`） |
| `com.tencent.mobileqq.qfix.common.classloader.DexClassLoaderUtil` | `static createDexClassLoader(String, String, String, ClassLoader): DexClassLoader` |
| `com.tencent.mobileqq.qfix.common.classloader.SystemClassLoaderInjector` | `static inject(Context, String, String[, String], boolean[, boolean]): String`；`static unloadDexElement(Context, int): String`；`makeDexElements(...)` |
| `com.tencent.mobileqq.qfix.AndroidNClassLoader` | `static inject(PathClassLoader, Application): AndroidNClassLoader`；`findClass(String)` |
| `com.tencent.mobileqq.qfix.QFixApplication` | `attachBaseContext(Context)`；`onCreate()`；`isAndroidNPatchEnable(): boolean` |
| `com.tencent.mobileqq.qfix.ApplicationDelegate` | `proxyAttachBaseContext(Context, QFixApplication): void` |

### 崩溃上报（Bugly / feedback.eup）

| 类 | 关键方法（真实签名） |
|---|---|
| `com.tencent.feedback.eup.CrashReport` | `static initCrashReport(Context, String, boolean[, CrashStrategyBean[, long]])`；`static postException(int, String, String, String, Map)` 与 `(Thread, int, …)`；`static handleCatchException(Thread, Throwable, String, byte[]): boolean`（+boolean 重载）；`static doUploadExceptionDatas(): boolean`；`needUploadCrash(): boolean`；`uploadUserInfo()`；`triggerUserInfoUpload()`；`setCrashReportAble(boolean)`；`setNativeCrashReportAble(boolean)` |
| `com.tencent.bugly.library.Bugly` | `static init(Context, BuglyBuilder[, boolean]): boolean`；`static postException(...)` ×2；`static handleCatchException(...): boolean` ×2；`setCrashMonitorAble(int, boolean)` |
| `com.tencent.bugly.crashreport.inner.InnerApi` | `static postH5CrashAsync(Thread, String, String, String, Map)`；`postCocos2dxCrashAsync(int, …)`；`postU3dCrashAsync(…)` |
| `com.tencent.feedback.eup.jni.NativeExceptionUpload` | `static native registNativeExceptionHandler(String, String, int): boolean`；`registNativeExceptionHandler2(String, String, int, int): String`；`enableHandler(boolean)`；`setmHandler(NativeExceptionHandler)` |
| `com.tencent.bugly.crashreport.crash.jni.NativeCrashHandler` | `setShouldHandleInJava(boolean)`；`reRegisterNativeHandler(boolean)`；`reRegisterANRHandler(boolean)`；`enableCatchAnrTrace()`／`disableCatchAnrTrace()` |
| `com.tencent.feedback.eup.CrashStrategyBean` | `setEnableNativeCrashMonitor(boolean)`；`setEnableANRCrashMonitor(boolean)`；`setUploadProcess(boolean)`；`setUploadSpotCrash(boolean)` |
| `com.tencent.bugly.crashreport.crash.h5.H5JavaScriptInterface` | `reportJSException(String)` |

**结构性事实**：几乎每个类都带 `static $redirector_ : IPatchRedirector` —— qfix redirect 已经
编译进全部 dex，所以"让 redirector 查不到"就能让补丁整体失效（见下）。

### 升级/更新（QQ 9.2.10）

| 类 | 关键方法（真实签名） |
|---|---|
| `com.tencent.upgrade.checker.a` | `static a(): checker.a`（单例）；**`b(UpgradeStrategy): boolean`** ← 判定"要不要升级" |
| `com.tencent.upgrade.checker.b` | **`a(UpgradeStrategy, UpgradeStrategy): boolean`**；**`b(UpgradeStrategy): boolean`** |
| `com.tencent.upgrade.core.b` | **`b(com.tencent.upgrade.request.a): void`** ← 请求派发（查询/升级请求都从这里走）；内部有 `ArrayDeque` 队列 |
| `com.tencent.upgrade.core.c` | 单例 `static b`；`a(): void`；**`b(UpgradeStrategy): boolean`**；`c(): void` |
| `com.tencent.upgrade.core.d` | 单例 `static a(): core.d`；`b(): void`；两个 `com.tencent.upgrade.storage.b` 槽位（下载/补丁状态） |
| `com.tencent.upgrade.core.h` | 内类 `h$c`/`h$d` 实现 `onSuccess(List)`、`onFail(int, String)`、`b(RDeliveryData)` —— 服务端策略落地处 |
| `com.tencent.upgrade.core.f` | RDelivery：`e(Context, UpgradeConfig, e44.e): RDelivery`；`b(String): String`；`i(): boolean` |
| `com.tencent.upgrade.download.DownloadParam` | `<init>(int, int, int)`；`getDefaultParam()`；`getDownLoadedSize()/getTotalSize()` |
| `com.tencent.mobileqq.upgrade.download.c` | `<init>(UpgradeDetailWrapper, upgrade.j)`；**`b(DownloadInfo): void`**；`onResult(List)`；`onException(int, String)`；`a(c$b): void`；`f(): int` |
| `com.tencent.mobileqq.upgrade.download.d` | **`onDownloadFinish/Update/Cancel/Pause/Wait/Error(...)`**；**`installSucceed(String, String): void`** ← 装包那一下 |
| `com.tencent.mobileqq.upgrade.j` | `i(): UpgradeDetailWrapper`；`l(): download.c`；`g(): download.b`；`n(QQAppInterface, boolean): void`；**`o(protocol.KQQConfig$UpgradeInfo): void`** |
| `com.tencent.mobileqq.upgrade.k` | **`a(UpgradeDetailWrapper): void`**；`b/c(UpgradeDetailWrapper): boolean`；`d(int): boolean`；`h(int): void`；`i()/j(): boolean` |
| `com.tencent.mobileqq.upgrade.shiply.a` | **`c(UpgradeStrategy, boolean): void`**；`a()/b()/d()/f(): void` |
| `com.tencent.mobileqq.upgrade.shiply.b` | **`a(UpgradeDetailWrapper, UpgradeStrategy): void`** |
| `com.tencent.mobileqq.upgrade.UpgradeDetailWrapper` | `i(UpgradeStrategy): boolean`；`static a(UpgradeInfo): UpgradeDetailWrapper$b`；`f(String, String): void` |
| `com.tencent.mobileqq.upgrade.unitedconfig.UpgradeConfigParser` | `c(): UpgradeDetailWrapper`；`e(UpgradeDetailWrapper): void`；`f(byte[]): UpgradeDetailWrapper`（freesia 配置） |
| 横幅 | `activity.recent.bannerprocessor.UpgradeBannerProcessor` / `InstallUpgradeBannerProcessor`：`onMessage(Message, long, boolean): void`、`updateBanner(banner.a, Message): void`、`initBanner(banner.a): View` |

**自动下载确实存在**（这是"要不要拦"的答案）：`upgrade.download.d` 的
`onDownloadFinish/onDownloadUpdate` 与 `installSucceed` 说明更新包是 SDK 自己拉下来并
尝试安装的，不需要用户点。因此 `misc.anti_update_nt` 从**判定层**就开始拦。

## 已实现的 NT 特性

| 特性 | id | 钩子 | 默认 | 说明 |
|---|---|---|---|---|
| 禁用热补丁（NT） | `misc.disable_hot_patch_nt` | `PatchRedirectCenter.apply` + `getRedirector`×2、`Relax.apply*`/`applyPatch`/`applyInternal`/native `relax` | 关（实验） | 补丁流程"报成功但不生效"，已装补丁的 redirector 查不到 → 原方法体执行 |
| 禁用崩溃上报（NT） | `misc.disable_crash_report_nt` | init/上报/上传/native 注册 四层 | 关（实验） | 不初始化上报器；丢弃 post；阻断上传；不注册 native/ANR 处理器 |
| 屏蔽更新（NT） | `misc.anti_update_nt` | 判定(4)/请求(1)/提示(4)/下载(5)/横幅(4) 五层 | 关（实验） | 判定恒 false → 不产生"有新版本"；请求派发被跳过 → 不下包；提示与横幅不出现 |

两个特性都声明 `hostGeneration = NT`，在旧版 QQ 上会被直接跳过（不会失败）。
未在真机验证前保持默认关闭；开起来后看：

```bash
su -c 'logcat -d -v threadtime | grep -aE "Qself/(NtHotPatch|NtCrashReport|NtAntiUpdate)" | tail -5'
# 期望：installed N hooks (apply blocked, redirectors inert)
#       installed N hooks (init=true, posts dropped, native off)
#       installed N hooks (decision=4 request=1 prompt=4 download=5 banner=4)
```

三个 NT 特性都**只钩 void 方法或 boolean 谓词**：返回对象的 getter（如
`core.d.a()`、`core.f.e(...)`）和构造器一律不碰 —— 调用方拿去解引用的地方返回 null 就是崩溃，
而掐掉驱动它们的状态（判定/请求/提示）已经足够。

`NtAntiUpdate` 每层单独计数，日志里能直接看出未来的 QQ 版本还缺哪一层。

`BLOCK_INIT`（`NtCrashReport.kt`）是唯一建议先动的地方：若未来某版 QQ 需要真正的
Bugly 实例，把它改成 `false`，其余三层仍然生效。

## 在设备上取真实类名（模块内置工具）

设置页菜单 → **导出宿主类名**：

- 用 `su` 把宿主 `base.apk` 复制到模块缓存，纯 Kotlin 解析每个 `classes*.dex` 的
  class-defs 表，得到**全部**类描述符；
- 只保留 `com.tencent.*` 且名字里带相关关键词（nt/chat/aio/troop/avatar/redpacket/
  upgrade/rfix/crash/statistic/…）的条目，排序写文件；
- 输出：`/sdcard/qself-classes.txt`（同时写到模块外部目录一份），
  对话框显示类数与 dex 数。

工具里还可以直接填**类名前缀**导出方法签名（默认预填 `com.tencent.mobileqq.qfix.,`
`com.tencent.feedback.eup.,com.tencent.bugly.crashreport.,com.tencent.qqnt.startup.`），
输出 `/sdcard/qself-methods.txt`。

拿到文件后，判断某个特性能不能做只需一条命令：

```bash
su -c 'grep -iE "撤|recall|revoke" /sdcard/qself-classes.txt | head -20'   # 例子
```

后续把文件发我（或把若干条 grep 结果发我），我按**真实类名**重写对应特性。

## 各特性在 NT 上的处置

| 特性 | 旧版实现 | NT 可行性 | 计划 |
|---|---|---|---|
| 屏蔽更新 | `upgrade.UpgradeController` | 需重新定位（NT 更新走 `com.tencent.qqnt.*` / 应用商店跳转） | **等签名**：下一步 dump `com.tencent.mobileqq.upgrade.` 等前缀 |
| 禁用崩溃上报 | `QQCrashReportManager` / `StatisticCollector` | Bugly 仍在且签名齐全 | ✅ `misc.disable_crash_report_nt`（默认关） |
| 禁用热修复 | `com.tencent.rfix.*` | qfix 仍在，`Relax`/`PatchRedirectCenter` 签名齐全 | ✅ `misc.disable_hot_patch_nt`（默认关） |
| 隐藏空间标题栏入口 | QZone 卡片名 | 空间在 NT 是独立模块 | 低优先 |
| 去相机按钮 / 去签到 | 旧版界面控件 | NT 界面重构（部分 Compose） | 低优先 |
| 聊天左侧显示自己的消息 | `activity.aio.BaseChatItemLayout` | NT 聊天列表重构 | 需类名 |
| 打开好友聊天记录 | `ChatHistoryActivity` | NT 路由变了 | 需类名 |

**原则**：NT 上宁可少做、不做，也不做猜类名的实现 —— 猜错就是崩在宿主里。
每个 NT 特性都必须"先确认类名 + 再小心 hook（异常全吞）"。

## 原生引擎状态（与 NT 无关但同期决定）

真机崩溃（Android 16 + PAC，`SEGV_ACCERR` 跳进非可执行内存）指向 LSPlant 在该 ART 上
工作异常。因此：**原生引擎永久默认关闭**，`use-native` 只是实验开关，且必须通过
Dobby 自检才会启用。框架引擎（LSPosed 自己的那套）是唯一默认路径。
