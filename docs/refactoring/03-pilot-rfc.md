# RFC-03：P2 试点——DisableScreenshotHelper 五件套改造

> 状态：已完成（2026-09-05，CI run 绿；夹具两轮返工记录见 §4）
> 目标：为 §3 目标架构产出第一张"活样张"：feature → hostapi 端口 → adapter → CapabilityRegistry 降级 → 契约测试。

## 1. 选型（§11.4 原则的应用）

候选对比：

| 候选 | 宿主查找 | 行为 | 结论 |
|---|---|---|---|
| **DisableScreenshotHelper**（屏蔽截屏分享） | DexKit `CScreenShotHelper`（特征串 `onActivityResumeHideFloatView`）+ 方法特征匹配（static `a(Context,String,Handler)`） | 单行抑制 `setResult(null)` | ✅ **选中**：真实 L4 资产 + 单一策略，全栈可示范 |
| MuteQZoneThumbsUp（被赞说不提醒） | DexKit + 最大参数个数启发式 | 回调内含 MSG_INFO_OFFSET 状态机（运行时解析参数位置） | 逻辑与解析混杂，适合做第二批 |
| decorator 族（FxxkQQBrowser 等） | 无宿主查找（纯 Intent 逻辑） | — | 展示不了 adapter 价值，排除 |

## 2. 设计

### 2.1 分层与依赖

```
sumicya.qself.feature.screenshot.DisableScreenshotShare   (薄壳:生命周期+降级编排)
  └─ sumicya.qself.hostapi.chat.ScreenshotHelperApi        (端口:纯JDK类型,框架中立)
       └─ sumicya.qself.adapter.screenshot.ScreenshotHelperAdapter (实现:DexKit/FQN策略链+XposedBridge安装)
核心资产 CScreenShotTarget(DexKitTarget) 留在 io.github.qauxv.util.dexkit(被 adapter 引用,方向合法)
```

### 2.2 端口（框架中立的关键）

```kotlin
interface ScreenshotHelperApi {
    fun resolveShowMethod(classLoader: ClassLoader): Method?   // 纯解析,classloader 注入=可测缝
    fun installSuppressor(method: Method,
                          isEnabled: () -> Boolean,             // 运行时开关语义由 feature 注入
                          onError: (Throwable) -> Unit): Boolean
}
```

- 端口只出现 `java.lang.reflect` 与 lambda 类型——不依赖 Xposed/Hook 框架/Android；
- `classLoader` 参数是**可测缝**：契约测试注入测试类加载器（qq-stub 思路的测试版），设备上由 feature 传 `Initiator.getHostClassLoader()`；
- `isEnabled/onError` 回调把 `hookBeforeIfEnabled` 的"运行时开关 + 异常围栏"语义从框架搬进端口契约。

### 2.3 Adapter：解析策略链（降级的第一个真实用例）

```
策略1: DexKit.requireClassFromCache(CScreenShotHelper)  —— 设备主路径(按版本缓存的特征反混淆)
策略2: 直接 FQN + 方法特征匹配                          —— 降级路径(类名未混淆的构建/测试)
两策略皆 runCatching 防御边界:任何解析失败→null,绝不外泄异常
```

安装：`XposedBridge.hookMethod` + 端口注入的 isEnabled/onError 回调语义。

### 2.4 Feature：降级编排归属

- `resolve()==null` → `CapabilityRegistry.report("chat.screenshot_helper", ABSENT)` + 返回 false（功能自禁用，设置项仍在）；
- 安装成功/失败 → AVAILABLE/DEGRADED；
- **原则（RFC 结论）：adapter 只管解析与安装（纯机制），feature 编排降级策略（纯策略）**——单一职责双方都可测（adapter 契约测试、registry 策略测试已存在）。

### 2.5 兼容性

- `hookKey = "DisableScreenshotHelper"`（显式传旧 key——BaseFunctionHook 默认取类名，改名即丢用户开关）；
- 同名"屏蔽截屏分享"、同 UI 位置（Simplify.UI_MISC）、同注解（KSP 自动注册）；
- DexKit 准备步骤保留（targets 传入 → 去混淆 Step 照常生成）；
- 旧类删除，全仓零外部引用（已验证）。

### 2.6 契约测试（Layer B 首例）

- 测试源集内造**宿主同名夹具** `com.tencent.mobileqq.screendetect.ScreenShotHelper`：目标方法 + 3 个诱饵（实例版 `a`、错参数个数 `a`、异名 `b`）；
- 正向：注入测试 classloader → 解析命中目标方法（特征断言）；
- 负向：空 `URLClassLoader`（仅 bootstrap）→ null——正是"QQ 更新改名后"的日常；
- android.jar 桩（`returnDefaultValues=true`）提供真实签名，反射特征匹配在 JVM 成立。

## 3. 不做（scope 纪律）

- 不动 `HookUtils`（cc.ioctl.util，另有 1 处核心引用，属后续批次）；
- 不给 feature 的 initOnce 写 JVM 测试（牵 ConfigManager/Android，属 Layer C 设备验证域）；
- 不拆 legacy/nt 双 adapter（等第二个真实 NT 能力出现再按内核分型）。

## 4. 执行记录（2026-09-05）

1. 全套落地：端口（`hostapi.chat.ScreenshotHelperApi`）、适配器（策略链 + 防御边界）、特性（降级编排 + 旧 hookKey 显式保持）、宿主同名夹具、契约测试 ×3（特征命中/缺失返回 null/解析无副作用）；旧类删除。
2. **夹具两轮返工的教训**：Java 方法区分只看"名字+参数序列"——static 与返回类型都不参与。单一 trait 的完全正交诱饵在语言层面不可达，采用"参数序列互异 + 组合覆盖"方案（每个 trait 至少被一个诱饵违反），已在夹具注释中说明。两轮都由 CI 注解秒级定位。
3. 契约测试已进 CI 常规运行（Layer B 从设计变为现实）。

## 5. 第二批试点：MuteQZoneThumbsUp（进阶形态，2026-09-05）

与首批的差异——端口返回**领域句柄**而非裸 `Method`：

- `QZoneMsgNotifyApi.NotifierHandle(method, descArgIndex)`：旧实现回调里的 `MSG_INFO_OFFSET` 状态机（运行时找"第二个 String 参数"）被收进 adapter 的 `resolveNotifier`，一次性解析为句柄字段；feature 只拿"描述文本在第几个参数"这一领域事实。
- 契约测试钉死两个启发式：最宽 void 方法选择器（夹具含更窄 void 诱饵与更宽非 void 诱饵）+ 第二 String 参数索引（uin 在前 desc 在后 → 2）。
- `MainHook` 早初始化白名单的 import 已随迁（`allowEarlyInit(MuteQZoneThumbsUp.INSTANCE)`，KSP 注册与三阶段时序不受影响）。

## 6. RFC-02 §E 审计结论（同步完成）

58 个桥接调用点（26 文件）逐点归类：**A 类**（约 2/3）为 `requireMinVersionAnyQQ(X) || requireMinTimVersion(Y)` 复合——宽语义即作者本意（"QQ 家族或 TIM"），收紧反而改变行为；**B 类**（阈值 ≥ 8.9.0 的独立调用）在 Play(止于 8.2.11)/Lite/HD 的真实版本号空间上与严格语义**不可区分**；**C 类**（< 8.9.0 独立调用）恰属 Lite/HD/Play 并存年代，宽语义同样是当时的有意支持。**结论：不收紧。桥接 API 升格为一等公民（去掉 @Deprecated），语义正名为 "QQ-family (non-TIM)"，§E 关闭。**

## 7. 批量迁移第一批（2026-09-05）

`DisableEnterEffect` + `DisableLightInteraction`，两个新知识点进入样张库：
1. **版本分支属于 adapter**：NT(DexKit method)/legacy(Initiator 类+方法名特征) 的分支选择是易变宿主知识，feature 对版本无感知；
2. **密封句柄承载"空值语义"**：轻互动两代内核需要不同空白值（NT=空 List，legacy=null），`Handle.NtListProvider/LegacySwitch` 把语义随方法一起交付，install 路径零版本分支；
3. **第二类可测缝**：JVM 上版本门不可评估（hostInfo 未初始化）→ 端到端解析退化为 null（本身就是 fail-safe 断言），易变逻辑以公开特征谓词（`matchesLegacyTrait`/`matchesNtTrait`）逐方法钉死——adapter 是实现细节，谓词公开无 API 稳定性代价。
两类均零外部引用（KSP 注册自动发现，无需任何白名单随迁）。批次规划：第二批 = RemoveCameraButton（版本→混淆名映射表进 adapter）+ RemoveSuperQQShow（作者包 xyz.nextalone 抽取，四路分支）。
