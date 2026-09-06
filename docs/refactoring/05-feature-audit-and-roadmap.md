# 05 · 功能冗余审计与大改路线图

状态: 审计初稿（P0 完成）· 全权委托批次推进中
日期: 2026-09-07
上游依据: 本仓静态盘点 + 设备实证（QQ 9.2.10 日志取证批次）

## 0. 环境事实与更正记录

- 适配目标框架: **LSPosed 2.2.0 (build 7854)**（用户实机环境），实现 libxposed **API 102**。
- **更正**（谱系纪律）: LSPosed **未改名**。此前分析文本据 JingMatrix/Vector 版本页自述误记为
  "LSPosed 更名 Vector"；Vector 是**另一个开源分支**，其版本页描述的是它自己的项目史。
  本仓一切适配以 LSPosed 官方线为准，Vector 仅作 API 实现参考。
- API 102 事实来源: libxposed/api PR #62（RFC 及实现）与 LSPosed 官方频道公告：
  - 热重载生命周期 `onHotReloading(HotReloadingParam)` / `onHotReloaded(HotReloadedParam)`；
    仅限**恰好一个 Java 入口类**的模块。
  - `HotReloadingParam#setSavedInstanceState` 要求类加载器中立的交接状态。
  - Service API 的 `hotReloadModule` 明确**不用于配置传播**——配置变更走
    remote preferences + listener。即"代码热重载"与"设置免重启"是两条独立管线。
  - `targetApiVersion ≥ 102` 的模块禁止调用 Legacy API。

## 1. 需求四项与分期

| # | 需求 | 分期 | 一句话方案 |
|---|------|------|-----------|
| 1 | 检查冗余/重复功能 | P1 | 静态盘点+逐对深审，互补项改名消歧，同概念跨版本项合并评审 |
| 2 | 改进各功能分类 | P1 | 展示层重组顶层分区（只动 `uiItemLocation` 赋值，不迁移类/包） |
| 3 | 大改 UI 界面 | P2 | 先功能重排落地；视觉改版（卡片/取色）二期勘察后定 |
| 4 | 适配 API 102 + 热重载 | P3 | targetApiVersion 100→102、单入口类改造、双管线（热重载+remote prefs） |

## 2. 盘点结果（静态提取，2026-09-07）

- `@UiItemAgentEntry` 共 **312** 个：hook 类 299 + fragment/dialog 13。
- 现有分类分布（提取自 `FunctionEntryRouter.Locations.*`）:
  Auxiliary 系 ~157（MESSAGE 40 / CHAT 26 / EXPERIMENTAL 21 / MISC 14 / GROUP 10 …），
  Simplify 系 ~113（UI_CHAT_MSG 18 / UI_MISC 15 / CHAT_GROUP_TITLE 12 …），
  Entertainment 13，其余 Debug/Config 各 1。
- 命名模式 ≥ 5 种: `override val name = "…"`、`override val name: String = "…"`、
  `getName()` 返回字面量、`override val preferenceTitle = "…"`、字符串资源引用；
  另有动态构造（如 RepeaterHook/RepeaterPlus）**静态提取不到名**，共 57 个。
- 提取器已知误报: `Locations.` 截断（`Auxiliary` 裸值 = `getUiItemLocation()` 动态返回）；
  TIM 系 token 重叠对为前缀共享假阳性。

**P1 第 0 步（test-first）**: 盘点工具（JVM 测试内嵌）+ 清单文件入库
  + 漂移守卫（增删/改名/换分类必须同步改清单，否则单测红）。
  产物: `app/src/test/java/sumicya/qself/guard/FeatureInventoryGuardTest.java`
  + `app/src/test/resources/feature_inventory.json`（TSV: 路径/显示名/分类，
  312 条，静态无名 35 条记 `-`；再生成用 `-Dfeature.inventory.regenerate=true`）。
  已知不精确: `Locations.` 截取在 import 短写法下只记前缀（`Auxiliary`/`Simplify`），
  守卫只求树与清单一致，不求字段完备。

## 3. 重复审计裁定（已逐对读码核实）

| 对 | 裁定 | 处置 |
|----|------|------|
| 关闭大号Emoji (`Emoji2Sticker`，管自己输入) × 屏蔽大号Emoji (`DisableBigSticker`，管别人发) | **互补，非重复** | 两者改名消歧: "输入Emoji不转大表情" / "屏蔽超级表情（接收）"（已落地） |
| 发送收藏消息添加分组 (`SendFavoriteHook`) × 允许发送收藏的语音 (`SendFavoriteVoice`) | 不同功能 | 保留，P1 归入"收藏"子组 |
| 伪装手机号码 (`FakePhone`) × 设备类型修改 (`ModifyDeviceType`) × 强制手机模式 (`ForcePhoneMode`) | 三个不同伪装维度 | 保留，P1 归入"伪装与设备"子类 |
| 侧滑栏精简 (`SimplifyQQSettingMe`) × 新版侧滑栏精简 (`SimplifyQQSettingMe2`) | **保留，不合并**（代码核实后修正原"合并候选"）: 前者钩旧面板 config 类（DexKit 目标，26 项表），后者钩 `QQSettingMeMenuPanelPartV3`（硬编码类名，7 项 d_* 表）——不同宿主架构、不同条目词汇，合并需迁移两套多选项配置，纯增风险 | 保持现状（名称已互为消歧），无动作 |
| VIP 三连: 侧滑面板 / 聊天界面 / QQ空间 VIP 图标 | 三个界面的同类隐藏 | 保留，归入"隐藏VIP标识"子组 |
| `RepeaterHook` × `RepeaterPlus` | **上游已解决，结案**: 经典版 summary 自述"不支持较新的版本，推荐使用消息+1 Plus"——版本互补（旧版走经典，新版走 Plus），且 Plus 是超集（+1 标记 + 长按菜单复读 + AIO 参数钩） | 无动作（两者名均为动态 `getTitleProvider`，清单记 `-` 属预期） |
| TIM 系重叠对（资料可选中/精华入口/频道入口/回复菜单） | 提取器假阳性 | 不处理 |

后续新嫌疑对由漂移守卫工具持续产出，逐批深审。

## 4. P1 · 分类重组方案（原则: 最小风险面）

**机制勘察结论**（FunctionEntryRouter.kt 已读透）:

- 分类体系在 `zwCreateBaseDslTree()` 的 DSL 树骨架里集中定义:
  顶层 `净化设置 host-ui`（主页/侧滑栏/聊天界面/群聊/资料卡/杂项）、
  `辅助功能 auxiliary-function`（聊天和消息/文件/好友资料/群聊/通知/实验/娱乐/杂项）、
  `配置`、`调试`、`其他`。
- 每个 hook 的 `uiItemLocation` 是 anycast 标签（`@any-cast` + id），经
  `resolveUiItemAnycastLocation` 对树骨架解析落位；**失配条目自动进 lost-and-found
  置顶节点**——现成的漂移可见信号，迁移批次的安全网。
- 迁移程序（每批）: ①改树骨架/`Locations` 常量 → ②改条目 `uiItemLocation` 赋值 →
  ③同步清单 → ④双绿 CI + 真机确认无 lost-and-found 新增。

重组原则:

- 保持 `FunctionEntryRouter.Locations` 机制与框架遍历逻辑不动，仅调整树骨架节点、
  常量组织与各 hook 的 `uiItemLocation` 指向。
- 顶层结构微调（草案）: 在 `辅助功能` 下增设 `伪装与设备`、`收藏与工具` 子组；
  `EXPERIMENTAL_CATEGORY` 保留为末位；跨组错位条目逐批评审归位。
- 增补: 搜索入口、收藏（star）、最近使用（前置勘察现有 SettingSearch 实现后接入）。
- 批次: 每批一批次分区迁移 + 清单同步 + 双绿 CI（攒批规则）。
- **批次 1（已实施，CI 已放行）**: 新增 `伪装与设备`（FakePhone/FakeLocation/
  FakePicSize/FakeVoiceTime/FakeQQLevel/FakeBattery/ForcePhoneMode/ModifyDeviceType
  + 同语义扫尾 PermissionMasking/GroupMemberManageFakeMyRole/FakeMultiWindowStatus/
  FakeNetworkType，共 12 项）与 `收藏与工具`（SendFavoriteHook/SendFavoriteVoice/
  RemoveFavPreviewLimit + 收藏更多表情/收藏表情包排序，共 5 项）两个 fragment
  节点与对应 `Locations` 常量；分区排序: 伪装/收藏上移至通知后，娱乐/实验沉底。
  同批搭载底栏未读数字锚点重做（图标锚→文字标签锚，详见 BadgeNumbers 注释）。

## 5. P2 · UI 视觉改版（勘察后定稿）

- 待勘察项（不断言）: 现设置页样式基座（模板/主题来源）、卡片化改造点、动态取色（Material You）可行性。
- 验收: 分区重排不回退（P1 清单为基准）+ 真机视觉确认。

## 6. P3 · API 102 + 热重载

1. 勘察当前入口结构（`targetApiVersion`、入口类数量、xpcompat `Lsp101HookWrapper` 边界）。
2. 单 Java 入口类改造（热重载硬性前提）。
3. targetApiVersion → 102；梳理 Legacy API 调用面（≥102 禁用）。
4. 代码热重载: 实现 `onHotReloading`/`onHotReloaded`，交接状态类加载器中立
   （玻璃 host 等运行时对象一律不进 saved state，重建即弃）。
5. 设置免重启: remote preferences + listener 独立管线，逐功能接
   （`isApplicationRestartRequired` 标注逐步摘除）。
6. 验收: LSPosed 2.2.0 (7854) 实机——模块 APK 覆盖安装后**不重启 QQ** 生效（热重载）；
   改开关不重启生效（remote prefs）。

## 7. 风险表

| 风险 | 缓解 |
|------|------|
| 分类迁移破坏路由遍历 | 只动赋值不动机制；清单守卫；每批双绿 |
| 57 个无名项漏迁 | P1 第 0 步先补齐提取器（5 种模式+动态构造白名单） |
| API102 禁 Legacy 边界不清 | P3 先做调用面清单再动版本号 |
| 热重载状态泄漏 | 交接白名单制：只允许原始类型/字符串/Bundle 语义数据 |
