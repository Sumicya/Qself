# 06 · 我的功能集与新客户端愿景

状态: **v2 定稿（用户三确认完毕，锁定为壳需求基线）**
日期: 2026-09-07

## 0. 愿景三原则（用户原话）

**简化 · 现代化 · 自由化 · 原生化**（=原生 Android 设计语言化，用户已确认），交互形态可部分参考 Telegram。

- 简化: 去除一切非必要元素（薄带去除、菜单图标去除——已是既有实践）；
- 现代化: 液态玻璃、纯白数字等新视觉（视觉规范由用户出）；
- 自由化: 每个几何/行为留旋钮（GlassConfig 传统），拒绝写死。

## 1. 形态结论（探讨记录）

- QQ 协议闭源 + NT native 内核 + 风控 → 从零协议客户端不可行；
- TG 有 NagramX 是因官方客户端开源可分叉（更正记录: NagramX 是 TG 分支）；
- QQ 语境下"自己的客户端"的现实形态 = **模块即客户端**（官方 QQ 为内核，模块层拥有全部 UI/行为自由）。
- **v2 新洞察: 功能母本 = QA 基座 + FunBox 招牌功能（防撤回包围提示/群管理菜单/半透明头像）+ TG 式原则**；
  FunBox 已判死（native 载荷不可维护，见 04 §8），其价值以功能复刻方式在新客户端中延续。
- 繁华模块 2.5.6 观察（2026-09-07，样本已剖析后删除）: 等级加速（收编，风险标注）、
  自定义气泡/主题（不入集——视觉由用户设计语言统一）；技术上 targetApiVersion=102 +
  控制流混淆壳（libfanhua-obfuscator.so），为 102 热重载生产可用性再添旁证。

## 2. 《我的功能集》v2（用户实报）

### A. 已有可沿用（仓内现成，验证/适配即可）

| 功能 | 仓内对应 | 备注 |
|------|----------|------|
| 底部导航栏液态玻璃 | sumicya.qself.glass | ✅ 本周迭代至贴边+数字上位 |
| 消息显示 ID 和时间 | ChatItemShowQQUin | ✅ 本周修 pattern 崩溃 |
| +1 按钮 | RepeaterPlus（消息+1 Plus） | 上游已解决的版本互补对 |
| 回复带图 | ReplyMsgWithImg | 🔨 诊断 v2（用户实测无返回后加固）: 不可吞结构+双写（logcat + files/qself_diag.log，DiagLog），采集=`su -c "cat /data/data/com.tencent.mobileqq/files/qself_diag.log"`；首版失败归因=外层 catch 可能吞掉整行 + logcat 采集链路玄学，双修 |
| 群文件增强 | TroopFileSaveLasting | 群文件保存时效等 |
| 好友检测/历史好友 | CheckCommonGroup / FriendDeletionNotification / ShowDeletedFriendListEntry / OpenFriendChatHistory | 四件套 |
| 资料卡增强 | OpenProfileCard 等 | 细化范围待定 |
| 移除广告 | QZoneNoAD / QWalletNoAD | |
| 半透明头像 | UploadTransparentAvatar | qa/funbox 双实现，取 qa |
| 移除限制 | RemoveFavPreviewLimit 等 | "移除限制"细化范围待定 |

### B. 待建（新客户端的核心增量）

| 功能 | 底子/参考 | 备注 |
|------|-----------|------|
| **防撤回扩展**（包围消息的提示，FunBox 风格） | 树内 cc.ioctl.hook.msg.RevokeMsgHook 为底 | 🔨 **v1 已落**: RevokeMsgHook 撤回点记录 peerUid#msgSeq 注册表；新功能 `防撤回消息标记`(RevokeWrapHint) 在被撤回气泡顶部注入「已撤回 · 已保留」提示条；v2=真正边框包围（待 9.2.10 气泡布局族勘察） |
| **群管理菜单** | FunBox 复刻（规格已由用户提供） | 🔨 **规格定稿**: 入口=群聊长按头像（顶替原@行为；查共同群可另行分配到点击类交互）。五项: 标记(本地) / 修改群名片 / 撤回消息 / 禁言和踢出 / 查询共同群。实现分级: v1a=✅**已落**（入口+菜单壳+标记[本地持久化]+查询共同群[ti.qq.com recall 页直开，uin 取自消息]）；v1b=四项 kernel 动作(IKernelGroupService 桥自建，参照 MsgServiceHelper/ContactCompat 写法)。仓内群管理 API 存量=零(勘察 2026-09-07)，唯一直接可复用=CheckCommonGroup。FunBox 考古闭环: TG 流传包即精简包(9.59MB，native 已剥)，规格唯一样本=用户记忆 |
| **群日志获取**（灰字扩展） | GrayTipCapture v2 已在仓 | ✅ **v1 已落**: 升格为「群日志记录（灰字）」（消息分类），记录写入滚动 JSONL（GroupLogStore，512KiB 轮转），点击条目弹查看器（最近 200 条+清空） |
| 一键 20 赞/50 赞 | XAutoDaily 线（外部模块支持） | 点赞/等级加速，排后但入集 |
| **等级加速**（繁华观察） | 繁华 2.5.6 有此功能（实现被混淆壳保护，静态不可复刻，自建） | ✅ 收编入集，与一键赞/XAutoDaily 同线排后；⚠️ 用户标注「可能有封号风险」——实现时须可开关、默认关、动作限速 |
| **风控上报拦截**（QQHook 合并） | QQHook 1.4（io.github.jhl337.qqhook）复刻 | ✅ **已合并为功能**: `拦截风控上报（O3）`（RiskReportInterceptor，杂项分类），MsfCore.sendMessage(+Inner 回退)与 ChannelManager.sendMessage 全重载两路拦截，前缀 `trpc.o3.mobile_security.`/`trpc.o3.report.`，主+MSF 进程 |
| 通知美化 | **仓内已有**: `cc.chenhe.qqnotifyevo`（MessagingStyle 重构，9 文件） | ✅ 声音问题已修: 重构通知改为**继承原通知渠道**（用户系统设置直接生效；原实现自建渠道强设默认铃声） |
| 头像圆角调整 | 全新（AvatarGeom 定位器+outline 裁剪） | ✅ **v1.1**: 配置页已补（用户指摘 v1 只给键不给页）——条目点击弹 SeekBar（0~36dp，恢复默认），半径随气泡 bind 即时重读（tag 含半径，改值下一气泡生效，无需重启）；联系人/资料卡 v2 |
| 预返回动画 | 全新 | Android predictive back |

### C. 移除（v1 草案误收，用户明确"无"）

自定义闪屏 / 设备类型修改 / 强制平板 appid / 相机移除 / 截图分享禁用 / **通知屏蔽家族**（禁言通知、@全体、红包通知、空间点赞屏蔽）

### D. 存疑已全部裁决（2026-09-07）

- 进场特效/轻互动屏蔽: **留集**（用户确认）；
- "原生化" = 原生 Android 设计语言化: **确认**，升格为第四原则；
- 灰字捕获本体: 随"群日志"功能化一并处理；
- 壳选址: **仓内 `:client` 模块起步**（CI/热重载复用），用户确认。

## 2.5 仓库卫生（2026-09-07）

- 分支清理: 删 stupid / run / renovate×2 / archive-minsdk21 / archive-rust / dexkit-bridge，仅存 `main` + `arena/01a0718a-qself`（renovate 分支会再生，再生再删）。

## 2.7 功能合并与集合（去重普查 2026-09-07）

全量 325 个 UI 功能扫描（关键词簇+精确同名），处置：

| 簇 | 内容 | 处置 |
|---|---|---|
| 共同群 ×2 | 设置页手动输入版(CheckCommonGroup.java，已删) / 资料页弹窗版(Menu.kt，留) | ✅ 删设置页版——已有弹窗版+群管理菜单两处更强入口，净 -1 |
| 广告 ×5 | 弹窗/悬浮/评论/空间/小程序开屏，散在 4 分类 | ✅ AdPurifySuite 总开关（状态=子项 AND，写穿全部子项；子项独立可调；自身不挂钩） |
| VIP ×2 | 空间/聊天标题两处隐藏 | 不动（各得其所，均已在 Simplify 合理分类） |
| 表情 ×9 | 散在 6 分类 | 缓：属上游分类体系手术（9 处 token 变更），留大重构窗口 |
| 输入 ×3 | 粘贴/提示/加号菜单 | 不动（语义相邻、功能各异） |
| 撤回 ×4 | 批量撤/撤特殊/防撤标记/频道防撤 | 非重复（不同界面与方向） |
| 头像 ×3 | 圆角/简洁模式圆头像/转发详情 | 非重复（机制不同，共存） |

## 3. 规模评估

- 入集约 **17 项**（A 类 10 项现成 + B 类 7 项待建）——小而美量级成立；
- 待建 7 项中 3 项有底子（防撤回/群日志/点赞线），真全新 = 群管理菜单、通知美化、头像圆角、预返回。

## 4. 下一步（定稿后执行序）

1. 🐛 通知美化声音/渠道问题修复（`qqnotifyevo`，A 类，日常受益，优先）；
2. 壳骨架: 仓内 `:client` 模块立项（settings include + 空壳 + 设置页原型骨架，简化原则）；
3. UI 设计语言规范由用户出，先以"简化"骨架承接；
4. B 类待建按价值序: 防撤回扩展（FunBox 风格）→ 群日志功能化 → 群管理菜单 → 其余；
5. 期间本 fork 继续日常主力维护（A 类 9.2.10 验证随日常使用推进）。
