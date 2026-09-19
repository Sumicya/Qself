# 功能清单（NT QQ）

目标平台只有一个：**NT QQ（9.x，`com.tencent.qqnt.*`）**。旧世代（8.x / TIM）的
功能在 v2 里**整体删除**——它们的类在 NT 上不存在，留着只会让"开关没反应"这件事
重复发生（用户明确：旧版功能集不值得移植，那些开关要么没用要么早就失效）。

每个功能一个 `object`，一个包路径（`feature/<category>/`），`@QselfFeature` 注解注册，
`initOnce` 里 `host.resolve` 拿类 → `Hooks.*IfEnabled` 装钩子；钩子 handler 内不再反射。
**只 hook void 方法和 boolean 判定**（hook 返回对象的方法会在宿主里造成空指针）。

## 已实现

| id | 名称 | 分类 | 说明 |
|---|---|---|---|
| `misc.disable_hot_patch_nt` | 禁用热补丁（NT） | 其他 | QFix `PatchRedirectCenter.apply` 直接返回 `CODE_SUCCESS`、`getRedirector` 返回 null、`Relax.apply*` 返回 `K_APPLY_SUCCESS`（8 个钩子） |
| `misc.disable_crash_report_nt` | 禁用崩溃上报（NT） | 其他 | Bugly / feedback.eup 的初始化、`post*`、上传、native 处理器注册（26 个钩子） |
| `misc.anti_update_nt` | 屏蔽更新（NT） | 其他 | 判定(4)/请求(1)/提示(4)/下载(5)/横幅(4) 五层 |
| `ui.inqq_entry` | QQ 内设置入口 | 界面 | 上游做法：hook 设置列表 provider 的 `List getItemProcessList(Context)`，把 Qself 作为一行插进 QQ 自己的设置列表；provider 改名时走 `HostDex` 运行时发现；都失败才退悬浮按钮 |

## 基础设施（不属于功能，但决定功能能不能跑）

| 组件 | 位置 | 作用 |
|---|---|---|
| `HostDex` | core/host | 宿主进程内读宿主 APK，按**方法形状**找被混淆的类（上游 DexKit 那一步），结果缓存到 `files/qself/hostdex.txt` |
| `DexReader` | core/dex | DEX 解析（class_defs / class_data / method_ids），零依赖 |
| `Host` | core/host | 类/方法/字段解析与缓存，宿主 ClassLoader 优先 |
| `Hooks` / `HookEngine` | core/xp | 唯一 hooking 接口；框架引擎与自研原生引擎共用同一抽象 |
| `SettingsBridge` | core/config | 权威配置 = 宿主 `files/qself/settings.json`；宿主进程内同 uid 直写，跨进程走 su 桥 |
| `HostRestart` | core/util | 设置生效即重启：跨进程 `su am force-stop`，宿主进程内 `killProcess(myPid())` |

## 明确不做

| 方向 | 原因 |
|---|---|
| 旧世代（8.x / TIM）功能移植 | 目标是 NT；旧类不存在，移植等于重写另一个模块 |
| pre-NT 设置页路径（`QQSettingSettingActivity/Fragment`） | NT 上不存在；上游那两条分支是给老版本用的 |
| 依赖 QQ 插件体系（qwallet 插件 APK）、DexKit 原生库的功能 | 插件体系已消失；DexKit 的能力由 `HostDex` 以更小的代价覆盖 |
| LSPosed 1.x / classic Xposed 支持 | 用户明确放弃（"lsp1 已经没人用了"）；`ClassicHookEngine` 只作为休眠的编译期兜底 |

## 移植/新增约定

- 新功能只写 NT 路径；`hostGeneration = NT`，`defaultEnabled = false`，`experimental = true`，
  真机验证过再把默认值翻过来。
- 需要"改名也不怕"的类，先 FQCN 候选，候选全 miss 时用 `HostDex` 按形状发现，
  命中后缓存；**永远不要把猜到的类名直接写进钩子**。
- 功能失败只影响自己：`Qself.featureErrors` 记录，启动日志里每个 id 一行
  `features: <id>=ok|skip|fail/<on|off>`。
