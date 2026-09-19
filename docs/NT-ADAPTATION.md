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

## 在设备上取真实类名（模块内置工具）

设置页菜单 → **导出宿主类名**：

- 用 `su` 把宿主 `base.apk` 复制到模块缓存，纯 Kotlin 解析每个 `classes*.dex` 的
  class-defs 表，得到**全部**类描述符；
- 只保留 `com.tencent.*` 且名字里带相关关键词（nt/chat/aio/troop/avatar/redpacket/
  upgrade/rfix/crash/statistic/…）的条目，排序写文件；
- 输出：`/sdcard/qself-classes.txt`（同时写到模块外部目录一份），
  对话框显示类数与 dex 数。

拿到文件后，判断某个特性能不能做只需一条命令：

```bash
su -c 'grep -iE "撤|recall|revoke" /sdcard/qself-classes.txt | head -20'   # 例子
```

后续把文件发我（或把若干条 grep 结果发我），我按**真实类名**重写对应特性。

## 各特性在 NT 上的处置

| 特性 | 旧版实现 | NT 可行性 | 计划 |
|---|---|---|---|
| 屏蔽更新 | `upgrade.UpgradeController` | 需重新定位（NT 更新走 `com.tencent.qqnt.*` / 应用商店跳转） | 先禁用入口，等类名 |
| 禁用崩溃上报 | `QQCrashReportManager` / `StatisticCollector` | 上报路径重构 | 同上 |
| 禁用热修复 | `com.tencent.rfix.*` | QFix 仍在（`QFixApplication` 可见） | 有希望，优先 |
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
