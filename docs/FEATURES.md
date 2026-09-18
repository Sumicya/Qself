# v1 功能清单

v1 目标：**少而稳**。每个功能都是从旧实现移植并重构到
`Host` + `Hooks` 新架构的；旧仓库中依赖插件体系
（qwallet/troop 插件 APK）、DexKit 或 QQNT 内部 API 的功能推迟到 v1.1+。

## 已实现（v1）

| id | 名称 | 分类 | 说明 |
|---|---|---|---|
| `misc.anti_update` | 屏蔽更新 | 其他 | 升级弹窗 / 横幅 / UpgradeController 全静默 |
| `misc.disable_crash_report` | 禁用崩溃日志上报 | 其他 | 默认开启（隐私）；QQCrashReportManager 与旧版 StatisticCollector 双路径 |
| `misc.disable_hot_patch` | 禁用热补丁 | 其他 | rfix 引擎 + 旧版 ConfigServlet（type=46 剔除）+ PatchReporter + 远古 hotpatch 类 |
| `chat.show_self_msg_left` | 自己的消息居左显示 | 消息 | `BaseChatItemLayout.setHearIconPosition` 置空 |
| `ui.remove_daily_sign` | 移除侧滑栏左上角打卡 | 界面 | 8.8.11 ~ 9.1.70 字段名映射 + NT V9 变体（尽力而为） |
| `ui.remove_camera_button` | 屏蔽标题栏相机按钮 | 界面 | 按版本选择混淆方法名；9.0.8+ / TIM 不可用 |
| `friend.open_chat_history` | 打开好友聊天记录 | 好友 | UI 动作：输入 uin 启动 ChatHistoryActivity（`setClassName`，旧版 QQ）；NT/uid 路径 v1.1 |
| `qzone.hide_title_bar_entrance` | 隐藏空间动态"此刻" | 空间 | 主路径（QZMTitleBarEntranceManager）+ 横幅路径（FeedxTopEntrance）；beta |

## 推迟（v1.1 候选，按优先级）

| 功能 | 推迟原因 |
|---|---|
| QQ 钱包相关（FakeBalance / QWalletNoAD） | 依赖旧插件体系（qwallet_plugin + PROC_TOOL）；需用 native 引擎或独立方案重做 |
| 频道复制卡片消息（GuildCopyCardMsg） | 需要 CustomMenu 构造工具；中等工作量 |
| 群文件转存永久（TroopFileSaveLasting） | 泛型签名 + 版本分支多，稳定性待验证 |
| 显示历史好友入口 | 依赖 QQ 内自绘列表页（ExfriendListFragment），UI 体系重做后再议 |
| 消息拦截 / 免打扰（AntiMessage 等） | 通知管线改动面大 |
| 图片自定义摘要（ImageCustomSummary） | 依赖消息数据对象深度反射 |
| 侧滑栏精简（SimplifyQQSettingMe） | 版本分支多 |
| 多开头像（MultiForwardAvatarHook） | 413 行复杂逻辑 |
| DexKit 类名发现 | v1 依赖 FQCN 候选表；v1.1 恢复 DexKit 以跟随 QQ 改名 |

## 移植约定

- 每个功能一个 `object`，一个包路径（`feature/<category>/`）。
- `initOnce` 里：`host.require/resolve` 拿类 → `Hooks.beforeIfEnabled/afterIfEnabled`
  装钩子。钩子 handler 内**不允许**再做反射。
- 版本分支用 `QQVersion` 常量表（`core/util/QQVersion.kt`，与旧表同步）。
- 功能失败（类缺失等）只影响自己：`Qself.featureErrors` 记录，UI 诊断可见。
