# Qself 行动表：彻底下展开，停止小窗路线

2026-09-12 · 基线：r3099 / 源码 5b872af · 当前分支 arena/01a0938e-qself

**本表是执行清单，不是完成报告。已开始界面替换和 service 102 升级，正在集中验证。最新纠正：不要求底色统一，整体遵循 MD3E，默认直接开始，有必要的问题才询问；执行时减少过程汇报，集中修改与验证。**
本轮要求覆盖上一轮的半透明 MD3 小窗方案，不能再沿用“小窗透明度 12%”作为目标。

## 行动表单

| ID | 优先级 | 行动 | 直接修改点 | 验收 / 完成条件 | 状态 |
|---|---|---|---|---|---|
| A01 | P0 | 建立唯一的原地下展开容器，替代模块内窗口与页面栈 | SettingsAccordion、SettingsMainFragment、SettingsUiFragmentHostActivity | 点击条目在其下展开；再次点击收起；返回先收起；不丢滚动、输入和展开状态 | 已进入本批实现；待最终验证 |
| A02 | P0 | 分类、功能详情、错误输出全部接入展开容器 | SettingsHomeView、SettingsOptionSheet、UiAgentItem、FuncStatusDetailsFragment | 分类不再开 Dialog；错误详情原地展开，复制/导出可用；开关不被详情点击误触 | 已进入本批实现；待最终验证 |
| A03 | P0 | 转换旧选择、输入、确认和进度小窗 | CustomDialog、MultiItemDelayableHook、各旧配置入口 | 单选、多选、输入、确认、取消保留原语义；取消不写配置；破坏性操作仍要明确确认 | 已进入本批实现；待最终验证 |
| A04 | P0 | 主题、诊断、备份、关于、搜索结果改为展开 | ThemeColorStyleDialog、ThemeModeStyleDialog、BackupRestoreConfigFragment、ReportDiagnostics、FeatureDiagnosticsItem、SearchOverlaySubFragment | 搜索结果展开目标项；主题选项、日志文本和备份操作不打开第二个模块窗口 | 已进入本批实现；待最终验证 |
| A05 | P0 | 取消设置透明度及旧浮层玻璃 UI | SettingsAppearanceItem、GlassAppearanceEditor 的设置分支、SettingsGlass、小窗动画资源 | 不再出现小窗/透明度配置入口；无透明背景采样；按 MD3E 层级使用不透明表面色（不要求底色统一）；旧值停止读取而非乱改其他配置 | 已进入本批实现；待最终验证 |
| A06 | P0 | Qself 移至最上，去掉顶部色块与重复标题区 | activity_settings_ui_host.xml、宿主标题/Insets、SettingsHomeView | Qself 作为唯一顶部标题；搜索保留；无独立底色/高光区；保留状态栏和挖孔安全间距 | 已进入本批实现；待最终验证 |
| A07 | P0 | 状态改为圆角正方形，不再通高长条 | SquareStateControl、TitleValueCell 的测量/布局 | 普通行状态区域为 48dp 方形，圆角统一；长说明不拉伸状态区；✓/×/−、RTL、点击独立保留 | 已进入本批实现；待最终验证 |
| A08 | P0 | libxposed-service 升级到正式 102.0.0，并核对实际 API 实现 | libs/libxposed/service、libs/libxposed/api、Lsp10xUnifiedHookEntry、HookStatus | 服务连接/断开、作用域读取、主/MSF 加载及 Hook 回归；不是只改 module.prop | 已固定到 102.0.0，待最终验证 |
| A09 | P1 | 更新并锁定构建环境及依赖，清除失效配置 | libs.versions.toml、Version.kt、Gradle wrapper、构建约定 | 对照正式发布和兼容要求；更新有实际差距的依赖；清理未使用的 AppCenter 条目；不无依据追 SNAPSHOT | 已进入本批实现；待最终验证 |
| A10 | P0 | 入口残留检查 + 整批回归 + APK | 可达入口清单、原生 UI 测试、依赖解析、APK 审计 | 模块设置可达的窗口/横向跳转残留为零；例外单列；通过后交付一个候选包 | 已进入本批实现；待最终验证 |

## 激进执行方式

- 不再局部贴皮：替换统一交互层，主入口与旧入口同批迁移；不用再造一个小窗包装旧弹窗。
- 不以禁用功能实现“无弹窗”；保留能力、原配置键和取消/保存语义。
- 不 Hook 全局 Android Dialog.show 来假装迁移，也不把原窗口缩成透明 1px 后偷偷保留。
- 先按可达调用链转换共用入口，再清理各调用点；用残留清单拦截遗漏，不反复靠截图猜。
- 布局、交互、依赖一次集中修改；可并行的静态检查同步跑，JVM 与 APK 校验同一提交。只在失败时补修，不为每个小修改发布一包。
- APK 交付必须报告真实遗留项；未完成项不能改名“兼容回退”后算完成。

## 已完成的源码清点

| 扫描类型 | 候选调用 | 涉及文件 |
|---|---:|---:|
| Alert/Material Builder | 152 | 78 |
| CustomDialog | 26 | 16 |
| 旧 MaterialDialog | 12 | 10 |
| XPopup Builder | 3 | 3 |
| 页面/Activity 跳转 | 62 | 41 |

这是 `app/src/main/java` 的静态模式匹配，不是运行时有效入口总数；包含遗留未注册功能、QQ 内操作及外部 Intent，各类文件可能重叠，也未覆盖所有间接封装。
逐文件候选表见 [inline-entry-candidates.tsv](inventory/inline-entry-candidates.tsv)。需要结合 `config/feature-catalog.tsv` 和共享调用链销账，不能将简单 grep 归零当作用户验收。

明确例外：系统权限授权、系统文件选择/分享器、确实打开 QQ 页面或外部应用的功能，仍交给对应系统/应用。模块内编辑、确认、说明和错误输出不属于此例外。

## 已核实的环境 / API 现状

用户主力环境仍按 PLC110 / ColorOS 16 / QQ 9.2.10(11310) / 框架 build 7854；本轮未收到设备环境变更。电脑中的依赖升级不等于替用户升级手机上的 LSPosed。

| 项目 | 当前源码 / 上游核对结果 | 行动 |
|---|---|---|
| 模块声明 | targetApiVersion=102；autoHotReload=false | 保留，不能将现有声明当新升级 |
| Hook API | 本地 API stub 的 LIB_API=API_102；已有 onModuleLoaded/onPackageReady 接入 | 对照正式 API 102 核实 ABI、回调和桥接，保留必要的旧框架边界 |
| libxposed-service | 基线为 496b76f（2024-04-23）；本批为正式 102.0.0 | 已固定 3318940876192e29cf6ab07637e899e22a87ebf0，已构建验证 |
| 上游正式发布 | api/service 均为 102.0.0，2026-06-14 发布 | 以发布 tag/提交锁定，不跟随浮动 master |
| Service 最低系统 | 上游 102 README 标为 minSdk 26，基线模块 minSdk 24，本批提高到 26 | 明确处理最低系统要求；本批提高 minSdk 到 26，并补齐 annotation 1.0.0；未只换 gitlink |
| 构建环境 | Gradle 9.7.0、AGP 9.3.1、Kotlin 2.4.10、KSP 2.3.11；CI JDK 21 | 检查组合兼容与正式发布；清理与当前版本冲突的旧注释/设置 |
| Android | compileSdk 37.0、targetSdk 36、minSdk 26；NDK 29 RC、CMake 3.31.0 | 核对可用稳定工具链；NDK 替换需 native 构建，不盲目上调 targetSdk |
| 主要 UI 依赖 | Material 1.14.0、AppCompat 1.8.0、Lifecycle 2.11.0 | 对照已发布版本并执行布局/行为回归 |

核对来源：
- https://github.com/libxposed/api/releases/tag/102.0.0
- https://github.com/libxposed/service/releases/tag/102.0.0
- https://github.com/libxposed/service/commit/496b76fa3e5af87958ebef97bd160319e05da79b
- https://github.com/libxposed/service/commit/3318940876192e29cf6ab07637e899e22a87ebf0

## 不得丢失的回归项

- 显示具体消息数量；无法取得原始数值时不编造。
- 功能错误查看/复制/导出；异步初始化后的状态刷新。
- 独立开关、旧配置迁移、安全模式、菜单/消息尾注去重。
- 大字、RTL、横竖屏、系统取色、减少动画、键盘及无障碍。
- 系统文件选择返回、配置保存/取消、宿主重建和滚动恢复。
- QQ 底栏的现有设置与无边缘高光；本轮“取消透明度”指被替换的设置窗口，不擅自删除 QQ 底栏功能。

O3 拦截匹配、旧 DiagLog 治理和固定发行签名仍属于已记录的独立待办；不因界面改造自动标为完成。


## 本批实现记录（r3104）

- `SettingsOptionSheet` 已不再是 DialogFragment，旧路由名转发到 InlineFeatureList。
- 已注册设置功能行共用 TitleValueCell；展开内容置于行下，固定 48dp 触摸框、44dp 圆角方形视觉；系统色与 MD3E 表面层级保留，不强制所有底色相同。
- `InlineAlertDialogBuilder` 沿用 AppCompat 原有选择/按钮/输入回调，将内容放入展开区，不调用 WindowManager 添加窗口。未使用全局 Dialog Hook 或透明小窗口伪装。
- 已适配 AppCompat/Material/CustomDialog 及目录内发现的原生 Builder 入口；手动取色器改用相同预设色列表。
- 设置宿主中的旧 Fragment 页面嵌入展开区；有独立长列表的旧页面保留有界内滚动，不将数据库/完整日志强制测量成无限高。
- 设置透明度入口已退出功能目录，旧材质方法只返回不透明背景。QQ 底栏自己的玻璃配置保留。
- 模块标题 Qself 移入最上工具栏，移除首页重复大标题和顶部独立色块；内容放在工具栏下方，避免滚动文本穿过透明标题。
- libxposed-service 固定到 3318940876192e29cf6ab07637e899e22a87ebf0（102.0.0）；注解依赖 1.0.0；minSdk 26；删除未使用 AppCenter 依赖声明。

### 不得冒充已完成的范围

- 统一窗口适配器仅在有展开容器的模块设置上下文中工作。QQ 聊天现场、模块管理 Activity 的未接入上下文，以及未注册遗留功能，不能宣称也已全部消灭窗口。
- 首页已有分类展开状态保存；新嵌入的旧 Fragment、所有旧输入表单的跨宿主重建草稿恢复尚未完整实现/验证。
- 新适配层使用打包的 AppCompat AlertParams，并附混淆保留规则；更换 AppCompat 版本必须回归此边界。
- 已验证的原生单测不能替代 PLC110 实机点击链、长列表流畅度、IME 与输入保存回归。


## 已交付候选包

- 源码：`26a000f0dc1d2b2fc553b30a834eeb8ca4be5f78`；版本 **1.6.1.r3104.26a000f / 3104**。
- [下载 APK](https://github.com/Sumicya/Qself/actions/runs/34699237079/artifacts/10299418210)。
- JVM CI [34699237093](https://github.com/Sumicya/Qself/actions/runs/34699237093) 成功。本轮报告 31 原生 UI + 10 合并 + 11 交互/数量/策略 = **52 项通过**，不冒充整个仓库全部测试总数。
- 静态合约 **39 项通过**，`git diff --check` 通过。初次提交的静态合约仍约束旧小窗，已随新交互更新；旧原生 Builder 迁移后的 nullable 查找编译错误及内容移出窗口后的 findViewById 路由已修正。
- 验证包括：原有表单保存回调、取消不保存、无 WindowManager 窗口、重复关闭、不可取消进度阻止返回/折叠、嵌入后查找输入框，以及圆角方形状态测量。
- APK CI [34699237079](https://github.com/Sumicya/Qself/actions/runs/34699237079) 成功。
- APK：`app-debug.apk`，17,808,547 字节；SHA256 `cf87fb804a18b84ed237dd76cd2e9c3b92a19d39ca6308149a484e236c13091c`。
- 签名 SHA256：`60f02afc151070a25070b8ac294a103898d04ba29e18e6d182087003697f217a`。
- Artifact `10299418210`，归档 12,894,852 字节，过期 2026-09-19T14:36:03Z。
- 审计：debuggable=false，targetApiVersion=102，autoHotReload=false；arm64、原生设置、诊断/玻璃、能力合并存在，AppCenter 不存在。
- [原生选项样例图](previews/settings-rounded-square-options.webp) 已读取检查；不是 QQ 实机截图。上方明确列出的未接入上下文、跨重建草稿/旧页面状态恢复仍未销账，不宣称全部旧窗口已消灭。
