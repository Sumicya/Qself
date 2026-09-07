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

## 2. 《我的功能集》v2（用户实报）

### A. 已有可沿用（仓内现成，验证/适配即可）

| 功能 | 仓内对应 | 备注 |
|------|----------|------|
| 底部导航栏液态玻璃 | sumicya.qself.glass | ✅ 本周迭代至贴边+数字上位 |
| 消息显示 ID 和时间 | ChatItemShowQQUin | ✅ 本周修 pattern 崩溃 |
| +1 按钮 | RepeaterPlus（消息+1 Plus） | 上游已解决的版本互补对 |
| 回复带图 | ReplyMsgWithImg | ⏳ 9.2.10 DexKit 失配待修 |
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
| **群管理菜单** | FunBox 复刻（规格已由用户提供） | 🔨 **规格定稿**: 入口=群聊长按头像（顶替原@行为；查共同群可另行分配到点击类交互）。五项: 标记(本地) / 修改群名片 / 撤回消息 / 禁言和踢出 / 查询共同群。实现分级: v1a=入口+菜单壳+零API项(标记本地存储、共同群接 CheckCommonGroup.onClick 现成)；v1b=四项 kernel 动作(IKernelGroupService 桥自建，参照 MsgServiceHelper/ContactCompat 写法)。仓内群管理 API 存量=零(勘察 2026-09-07)，唯一直接可复用=CheckCommonGroup。FunBox 考古闭环: TG 流传包即精简包(9.59MB，native 已剥)，规格唯一样本=用户记忆 |
| **群日志获取**（灰字扩展） | GrayTipCapture v2 已在仓 | ✅ **v1 已落**: 升格为「群日志记录（灰字）」（消息分类），记录写入滚动 JSONL（GroupLogStore，512KiB 轮转），点击条目弹查看器（最近 200 条+清空） |
| 一键 20 赞/50 赞 | XAutoDaily 线（外部模块支持） | 点赞/等级加速，排后但入集 |
| **风控上报拦截**（QQHook 合并） | QQHook 1.4（io.github.jhl337.qqhook）复刻 | ✅ **已合并为功能**: `拦截风控上报（O3）`（RiskReportInterceptor，杂项分类），MsfCore.sendMessage(+Inner 回退)与 ChannelManager.sendMessage 全重载两路拦截，前缀 `trpc.o3.mobile_security.`/`trpc.o3.report.`，主+MSF 进程 |
| 通知美化 | **仓内已有**: `cc.chenhe.qqnotifyevo`（MessagingStyle 重构，9 文件） | ✅ 声音问题已修: 重构通知改为**继承原通知渠道**（用户系统设置直接生效；原实现自建渠道强设默认铃声） |
| 头像圆角调整 | 待查/全新 | 自由化旋钮型功能 |
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

## 3. 规模评估

- 入集约 **17 项**（A 类 10 项现成 + B 类 7 项待建）——小而美量级成立；
- 待建 7 项中 3 项有底子（防撤回/群日志/点赞线），真全新 = 群管理菜单、通知美化、头像圆角、预返回。

## 4. 下一步（定稿后执行序）

1. 🐛 通知美化声音/渠道问题修复（`qqnotifyevo`，A 类，日常受益，优先）；
2. 壳骨架: 仓内 `:client` 模块立项（settings include + 空壳 + 设置页原型骨架，简化原则）；
3. UI 设计语言规范由用户出，先以"简化"骨架承接；
4. B 类待建按价值序: 防撤回扩展（FunBox 风格）→ 群日志功能化 → 群管理菜单 → 其余；
5. 期间本 fork 继续日常主力维护（A 类 9.2.10 验证随日常使用推进）。
