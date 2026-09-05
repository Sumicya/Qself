# RFC-03：P2 试点——DisableScreenshotHelper 五件套改造

> 状态：执行中（2026-09-05）
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
