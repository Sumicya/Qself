# QAuxiliary 重构计划

## 概述
针对 QQ 版本 9.1.75-9.2.10，对 QAuxiliary 项目进行代码质量、架构和性能优化重构。
**不要求向后兼容旧版本**。

## 核心问题分析

### 1. 架构职责混乱
**问题**: `BaseFunctionHook` 同时实现 `IDynamicHook`（钩子逻辑）和 `IUiItemAgentProvider`（UI 表示），违反单一职责原则。

**影响**: 
- 约 152 个使用 `@FunctionHookEntry` 的 Hook 类耦合了 UI 逻辑
- 难以测试和维护
- 无法独立复用 Hook 逻辑

**解决方案**:
- ✅ 创建 `FunctionHook` 基类 - 纯钩子逻辑
- ✅ 创建 `HookUiProvider` 接口 - UI 表示
- ✅ 创建 `UiEnabledFunctionHook` 组合类 - 需要 UI 的 Hook
- ✅ 迁移 `CommonSwitchFunctionHook` 和 `CommonConfigFunctionHook`

### 2. 包结构分散
**问题**: 多命名空间混用 (`io.github.qauxv.*`, `cc.ioctl.*`, `me.singleneuron.*`, `xyz.nextalone.*` 等)

**影响**:
- 代码组织混乱
- 难以理解和导航
- 约 90 个文件在 `cc.ioctl.hook` 下

**解决方案**:
- 统一迁移至 `io.github.qauxv.*` 命名空间
- 按功能模块重新组织子包:
  - `io.github.qauxv.hook.*` - 所有 Hook 实现
  - `io.github.qauxv.ui.*` - UI 相关
  - `io.github.qauxv.bridge.*` - QQ API 桥接
  - `io.github.qauxv.dsl.*` - DSL 系统
  - `io.github.qauxv.util.*` - 工具类

### 3. 依赖倒置违反
**问题**: `MainHook.java` 直接依赖具体 Hook 实现类

**现状**:
```java
HookInstaller.allowEarlyInit(RevokeMsgHook.INSTANCE);
HookInstaller.allowEarlyInit(MuteQZoneThumbsUp.INSTANCE);
// ... 硬编码 10+ 个 Hook
```

**解决方案**:
- 使用 `HookRegistry` 注册表管理所有 Hook
- 通过注解 `@FunctionHookEntry` 自动发现
- 支持优先级和依赖关系配置

### 4. DSL 系统过度设计
**问题**: 25 个 DSL 相关文件，复杂度过高

**文件列表**:
- `dsl/func/` - 8 个文件
- `dsl/item/` - 9 个文件  
- `dsl/cell/` - 4 个文件
- `dsl/LegacyDslUiPreference.kt` 等

**解决方案**:
- 保留核心 UI 接口 (`IUiItemAgent`, `ISwitchCellAgent`)
- 移除过度抽象层 (`IDslItemNode`, `IDslParentNode` 等)
- 简化为声明式 UI 构建器

### 5. Java/Kotlin 混用
**问题**: 212 个 Java 文件，核心代码仍为 Java

**关键 Java 文件需 Kotlin 化**:
- `MainHook.java` (核心入口)
- `HookInstaller.java` (Hook 安装器)
- `InjectDelayableHooks.java` (延迟 Hook 注入)
- `SettingEntryHook.java` (设置入口)
- `DeletionObserver.java` (删除观察器)
- `Reflex.java` (反射工具)

## 重构阶段

### 第一阶段：架构分离 ✅ (已完成)
**目标**: 分离 Hook 逻辑与 UI 表示

**完成项**:
- ✅ `FunctionHook.kt` - 新基类
- ✅ `HookUiProvider.kt` - UI 接口
- ✅ `CommonSwitchFunctionHook.kt` - 迁移
- ✅ `CommonConfigFunctionHook.kt` - 迁移

**待办**:
- [ ] 迁移 `BasePersistBackgroundHook` 使用者 (14 个文件)
- [ ] 迁移 `BaseDecorator` 体系
- [ ] 更新所有 `@FunctionHookEntry` 类

### 第二阶段：统一包结构
**目标**: 所有代码迁移至 `io.github.qauxv.*`

**步骤**:
1. 创建 `io.github.qauxv.hook.chat`, `io.github.qauxv.hook.friend` 等子包
2. 迁移 `cc.ioctl.hook.*` → `io.github.qauxv.hook.*`
3. 迁移 `me.singleneuron.*` → `io.github.qauxv.hook.*`
4. 迁移 `xyz.nextalone.*` → `io.github.qauxv.hook.*`
5. 迁移 `com.hicore.*` → `io.github.qauxv.hook.*`
6. 更新所有 import 语句

**影响文件**: ~200 个

### 第三阶段：依赖注入改造
**目标**: 通过 `HookRegistry` 管理所有 Hook

**新建文件**:
```kotlin
// HookRegistry.kt
object HookRegistry {
    private val hooks = mutableListOf<FunctionHook>()
    
    fun register(hook: FunctionHook)
    fun unregister(hook: FunctionHook)
    fun getAll(): List<FunctionHook>
    fun getEnabled(): List<FunctionHook>
    fun getByCategory(category: String): List<FunctionHook>
}
```

**修改文件**:
- `MainHook.java` → `MainHook.kt` (移除硬编码)
- `HookInstaller.java` → `HookInstaller.kt` (使用 Registry)
- `InjectDelayableHooks.java` → 简化逻辑

### 第四阶段：简化 DSL 系统
**目标**: 移除过度抽象，保留核心功能

**保留**:
- `IUiItemAgent` - UI 项代理
- `ISwitchCellAgent` - 开关代理
- `IUiItemAgentProvider` - UI 提供者

**移除/合并**:
- `IDslItemNode`, `IDslParentNode`, `IDslFragmentNode`
- `BaseParentNode`, `FragmentDescription`, `CategoryDescription`
- 过度设计的 Cell 类

**简化方案**:
```kotlin
// 新的声明式 UI 构建器
fun buildUiItem(
    title: String,
    summary: String? = null,
    onClick: () -> Unit = {},
    switch: Boolean? = null
): IUiItemAgent
```

### 第五阶段：Kotlin 化核心代码
**目标**: 转换核心 Java 文件为 Kotlin

**优先级**:
1. **高优先级** (核心逻辑):
   - [ ] `Reflex.java` → `Reflex.kt` (反射工具，被广泛使用)
   - [ ] `MainHook.java` → `MainHook.kt` (入口点)
   - [ ] `HookInstaller.java` → `HookInstaller.kt`

2. **中优先级** (Hook 实现):
   - [ ] `SettingEntryHook.java` → `SettingEntryHook.kt`
   - [ ] `DeletionObserver.java` → `DeletionObserver.kt`
   - [ ] `MuteAtAllAndRedPacket.java` → `MuteAtAllAndRedPacket.kt`

3. **低优先级** (工具类):
   - [ ] `LayoutHelper.java` → `LayoutHelper.kt`
   - [ ] UI Widget 类

## 性能优化

### 1. 延迟加载缓存
```kotlin
// 改进前
override val isEnabled: Boolean
    get() = ConfigManager.getDefaultConfig().getBoolean(...)

// 改进后 - 缓存结果
private var _isEnabledCache: Boolean? = null
override val isEnabled: Boolean
    get() {
        return _isEnabledCache ?: run {
            ConfigManager.getDefaultConfig().getBoolean(...).also {
                _isEnabledCache = it
            }
        }
    }
```

### 2. 反射调用缓存
```kotlin
// 使用 MethodHandle 或缓存 Method 对象
private val kQQSettingSettingActivity by lazy {
    Initiator.requireClass("com/tencent/mobileqq/activity/QQSettingSettingActivity")
}
```

### 3. DexKit 批量查找
```kotlin
// 已部分实现，需扩展到所有 Hook
val targets = arrayOf(C_StandardPkg, M_PicItemBuilder)
DexKit.findAllUsingStr(targets)
```

### 4. 减少主线程阻塞
- 将 DexDeobfStep 移至后台线程
- 异步初始化非关键 Hook
- 使用 Coroutine 替代 callback

## 实施时间表

| 阶段 | 预计时间 | 影响文件数 | 风险等级 |
|------|----------|-----------|----------|
| 1. 架构分离 | 2 天 | 20 | 低 |
| 2. 包结构统一 | 3 天 | 200 | 中 |
| 3. 依赖注入 | 2 天 | 10 | 中 |
| 4. DSL 简化 | 3 天 | 25 | 高 |
| 5. Kotlin 化 | 5 天 | 50 | 中 |
| **总计** | **15 天** | **~300** | **-** |

## 测试策略

### 单元测试
- Hook 初始化逻辑测试
- UI Agent 生成测试
- 配置读写测试

### 集成测试  
- QQ 9.1.75, 9.2.0, 9.2.10 兼容性测试
- 所有启用的 Hook 功能验证
- 性能回归测试 (启动时间、内存占用)

### 灰度发布
1. 内部测试 (5 人)
2. 小范围公测 (50 人)
3. 全量发布

## 回滚方案

如遇到严重问题:
1. Git tag 标记每个阶段
2. 保留旧基类为 `@Deprecated` (临时)
3. 配置开关可切换新旧架构

## 成功标准

- [ ] 所有单元测试通过
- [ ] 无编译错误和警告
- [ ] QQ 9.1.75-9.2.10 正常运行
- [ ] 启动时间无明显下降
- [ ] 内存占用无明显上升
- [ ] 代码覆盖率 > 60%
- [ ] 文档完整更新

---

**当前进度**: 第一阶段完成 30% (3/10 核心文件)
**下一步**: 继续迁移剩余 14 个使用 `BaseFunctionHook`/`BasePersistBackgroundHook` 的文件
