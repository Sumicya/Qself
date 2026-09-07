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

## 6. P3 · API 102 + 热重载（勘察后分期，2026-09-07 更新）

**仓内现状（勘察事实）**:
- 多后端 loader 已在仓: `loader/sbl`（xp51/lsp100/**lsp101**/frida 四后端）、
  `loader/startup`（HybridClassLoader/UnifiedEntryPoint）、vendored
  `libs/libxposed/api`（本批 101→102 接口面）+ `libs/libxposed/service`。
- 现代路径被 `qauxv.override.newxposedapi`（local 属性，默认关）控制；
  当前 APK 走 legacy: `assets/xposed_init` → `Xp51HookEntry`。
- 缺口: app 侧 **XposedModule 入口类不存在**; `META-INF/xposed/module.prop`
  打包任务不存在; `HookHandle.getId/replaceHook`、`HookBuilder.setId` 未植。

**分期（2026-09-07 二次勘察修正）**:
- ✅ **现状即现代路径**: 旗标 `qauxv.override.newxposedapi` 默认 **true**（与上游一致），
  APK 一直携带 `META-INF/xposed`（targetApiVersion=**101**）+ 单入口
  `Lsp10xUnifiedHookEntry`（与上游逐字一致）+ native_init（libqauxv-core0.so 本就在编）。
  9.2.10 日志栈中的 `Lsp101HookWrapper`/`xpcompat.WrappedCallbacks` 帧已实证
  装机运行走 lsp101 现代后端。"S3 第一跳"实为已完成且设备验证过的现状。
- ✅ **S1+S2 编译面（本批）**: vendored API 植入完整 102 接口面——`API_102`、
  `HotReloadingParam`/`HotReloadedParam`、`onHotReloading`/`onHotReloaded` 默认方法、
  `HookHandle.getId/replaceHook`、`HookBuilder.setId`，签名逐字取自 PR #62 diff。
- ✅ **S3 终跳（已执行，用户显式授权"直接做"）**: module.prop `targetApiVersion=102`
  + `autoHotReload=true`; 入口 `Lsp10xUnifiedHookEntry` 覆写
  `onHotReloading`（同意+交接最近包生命周期参数，框架类加载器对象=契约允许的中立值）
  /`onHotReloaded`（先 unhook 全部旧钩柄，再重放包生命周期让新代全量重初始化）。
  **已知实验面**: RFC 明文 ≥102 禁调 legacy API，xpcompat 桥栈含 de.robv 帧
  （9.2.10 实证）——禁令实际形状（拒绝加载/静默剥离/无碍）装机才知，失败模式=
  模块失效，回退=重装上一绿 APK（34069688106 及更早）。API 类为 compileOnly
  （框架提供），102 方法签名在 100/101 框架上不被调用即不解析，安全。
- **S4**: 设置免重启 remote preferences 独立管线（service API 明确与热重载分开），
  逐功能摘 `isApplicationRestartRequired`。
- 验收: 模块 APK 覆盖安装后**不重启 QQ** 生效（热重载）; 改开关不重启生效（remote prefs）。
- ✅ **热重载实机验收通过（2026-09-07）**: 更新模块后未重启 QQ，两进程日志
  `QAuxvLoader: hot reloaded: package lifecycle replayed for the new generation`，
  玻璃底栏随后在新代自装（`liquid glass installed` 于同批日志）。101→102 过渡期的
  两条"热重载失败"提示为旧代无回调所致，属一次性正常现象。

## 6.1 · 全部开启错误普查（2026-09-07 首轮，设备 9.2.10）

| 错误签名 | 频次/模式 | 定罪 | 处置 |
|---|---|---|---|
| CNFE `MainChatsCardContainerPartImpl` + NPE `getDeclaredMethods on null` | 每次 QQ 启动成对出现（跨 5 个 pid 复现） | `DisableChatsCardContainer`，9.2.x 类已删 | ✅ 本批: initOnce 判空静默无操作 |
| CCE `RelativeLayout/View → LinearLayout` | 每条群聊气泡一条（栈帧 `HideTroopLevel.onGetViewNt:86`） | ✅ `HideTroopLevel` 硬转 LinearLayout，9.2.10 起等级视图为 RelativeLayout | ✅ 本批: 改 `as? ViewGroup` 取首子（两种形状通吃） |
| `NoSuchFieldException field 'n' in QQCustomMenuExpandableLayout` + CCE `View→LinearLayout`（同一功能两 lambda） | 每次打开消息菜单 4 条（`LegacyContextMenu.kt:54/57`） | ✅ `LegacyContextMenu`（老式消息菜单）：高度字段 'n' 已删、布局方法返回普通 View | ✅ 本批: 字段写入一次失败即停试；结果转型改 `as? ViewGroup` |
| NPE `null cannot be cast to non-null type kotlin.String` | 每次点群机器人按钮 1 条（`HandleClickGroupBotMsgBtnSend.kt:63`） | ✅ `HandleClickGroupBotMsgBtnSend`：版本阶梯止于 9.1.0，9.2.10 上 label/type/enter 为 null | ✅ 本批: 三字段改可空解析，null 时落回通用确认弹窗（保住防误触本意） |
| `XposedHelpers$ClassNotFoundError: parameter type must not be null` ×4/进程 | 初始化期，sweep2 中未复现 | 未定罪（某功能类缺失后仍组 hook 参数表） | ⏳ 观察下轮 sweep（可能随 DisableChatsCardContainer 修复消失） |
| `DexTarget: XXX` 找不到 ×20+（CGuildHelperProvider/CSimpleUiUtil/CIntimateDrawer/NFriendChatPie_updateUITitle/NLeftSwipeReplyHelper_reply…）+ 硬编码类 CNFE（QQSettingMeView/BuscardHelper/VideoVolumeControl/AIOPictureView/SettingMeApolloViewController…） | 全部开启模式初始化期一次性 | 版本失配清单（9.2.10 目标签名/类名漂移），非崩溃性 | 📋 入档；按用户点名逐个适配 |
| FATAL EXCEPTION 计数 12 | 计数被剪贴板回显污染（Termux/AIUnit 把含 "FATAL EXCEPTION" 字样的命令文本记入 logcat，grep 自匹配） | 多数为自污染，真归属未知 | ⏳ 下轮采集: `logcat -c` 清缓冲后复现，grep 加 `-v ZeroTermux\|AIUnit` 排除，且改看 `E AndroidRuntime` |
- 交接纪律: 玻璃 host 等运行时对象一律不进 saved state（类加载器中立，重建即弃）。

## 7. 风险表

| 风险 | 缓解 |
|------|------|
| 分类迁移破坏路由遍历 | 只动赋值不动机制；清单守卫；每批双绿 |
| 57 个无名项漏迁 | P1 第 0 步先补齐提取器（5 种模式+动态构造白名单） |
| API102 禁 Legacy 边界不清 | P3 先做调用面清单再动版本号 |
| 热重载状态泄漏 | 交接白名单制：只允许原始类型/字符串/Bundle 语义数据 |
