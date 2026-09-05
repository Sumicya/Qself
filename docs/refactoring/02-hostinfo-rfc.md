# RFC-02：HostInfo 统一（P1 步骤 4 设计文档）

> 状态：草案（待执行）
> 前置：docs/refactoring/01-architecture-analysis.md §8.3（首次尝试的失败教训）
> 规模：全仓 92 个文件 import 门面；其中核心包 `io.github.qauxv` 26 个（反向依赖最大残块）

## 1. 现状与问题

上游自身迁移到一半，留下两套并存的 API：

- **真身** `io.github.qauxv.util/HostInfo.kt`：`@file:JvmName("HostInfo")`，顶层函数 + `hostInfo: HostInfoImpl` 全局 + `HostSpecies` 枚举（QQ/TIM/QQ_Play/QQ_Lite/QQ_International/QQ_HD/QAuxiliary/Unknown）。
- **门面** `cc.ioctl.util/HostInfo.java`：静态委托层，92 文件仍在用，是核心包反向依赖作者包的最大单点（26 处）。

问题不止是位置：**门面与真身存在三处语义分歧**，机械搬家会静默改变行为。

## 2. 语义对照表（取证于 37ca88e）

| 门面（cc.ioctl.util.HostInfo） | 真身（io.github.qauxv.util） | 语义关系 |
|---|---|---|
| `getApplication()/getPackageName()/getAppName()/getVersionName()/getVersionCode32()/getLongVersionCode()` | `HostInfo.getHostInfo().getXxx()`（Java 视图）/ `hostInfo.xxx`（Kotlin） | ✅ 等价 |
| `isInModuleProcess()/isInHostProcess()/isAndroidxFileProviderAvailable()/isTim()` | 同名顶层成员 | ✅ 等价 |
| `isQQLite()` / `isQQHD()` | 无直接对应 | 门面=包名比较；真身应= `hostSpecies == QQ_Lite/QQ_HD`（等价改写） |
| `isQQ()` = `!isTim()` | 无 | ⚠️ **分歧①**：门面含 Play/Lite/HD/International；真身无此概念（`species == QQ` 不含） |
| `requireMinQQVersion(v)` = `isQQ() && v>=v` | `species == QQ && v>=v` | ⚠️ **分歧②**：门面在 Play/Lite/HD 上也为真 |
| `isPlayQQ()` = `!真身.isPlayQQ()` | `species == QQ_Play` | ⚠️ **分歧③**：**门面取反**——疑似上游笔误（连带 `requireMinPlayQQVersion` 在"非 PlayQQ"上为真） |
| `requireMinTimVersion(v)` | 等价 | ✅ |

## 3. 迁移原则

**P1 行为保持优先**（本 RFC 范围）：迁移只改"从哪里拿信息"，不改"判断结果"。理由：92 个调用点里混着"作者本意就是严格 QQ"与"作者事实上依赖了宽松语义"两种情况，逐点审意图是 P2+ 的语义收紧工作；混在同一步做，出问题无法定位是搬家错还是改语义错。**一次只动一个变量。**

由此，分歧①②③在迁移期按门面语义在真身侧补齐等价 API（新增，不改动既有函数）：

```kotlin
// HostInfo.kt 新增（迁移期桥接，标记 @Deprecated("语义收紧迁移用", level = WARNING)）
fun isAnyQQSpecies(): Boolean          // 分歧①的门面语义: species != TIM（即旧 isQQ()）
fun requireMinVersionAnyQQ(v: Long)    // 分歧②的门面语义: isAnyQQSpecies() && versionCode >= v
```

分歧③（取反）：迁移前先跑调用点审计——若 `门面.isPlayQQ()/requireMinPlayQQVersion` 的外部调用点为零（初查仅门面内部自用），取反路径不可达，直接按真身语义迁移（记录于提交信息）；若有外部调用点，逐点判意图。

## 4. 分阶段执行

| 阶段 | 内容 | 验收 |
|---|---|---|
| A | 真身补桥接 API（§3）+ 语义审计（grep 全部门面方法调用点，产出意图清单入 §5） | CI 绿 |
| B | Java 消费者迁移：门面静态调用 → 真身静态/`HostInfo.getHostInfo()`；按 §2 表逐方法映射（sed 脚本 + 人工过 diff） | 编译零门面引用 |
| C | Kotlin 消费者迁移：`cc.ioctl.util.HostInfo.x()` → 顶层函数 import 风格（`import io.github.qauxv.util.isTim` 等） | 同上 |
| D | 删除门面 `cc/ioctl/util/HostInfo.java`；核心包反向依赖预计再降 ≥26（含间接更多） | `grep cc\.ioctl\.util\.HostInfo` = 0；CI 绿 |
| E（P2+，独立提案） | 语义收紧：逐功能审查 `isAnyQQSpecies` 调用点是否应改为严格 species；删除桥接 API | 行为变更逐点有据 |

## 5. 调用点审计（阶段 A 产出，占位）

> 执行阶段 A 时填充：方法 × 调用文件清单 + 意图标注（严格/宽松/存疑）。

## 6. 风险

1. Kotlin 对 `@JvmName` 文件类的调用风格限制（§1.4 教训）→ 阶段 C 的重写模式必须先在一个文件上验证编译；
2. `hostInfo` 是 lateinit 全局，JVM 单测不可达（Android 类型）→ 版本判断逻辑的可测化依赖 P2 hostapi 的 `HostEnvironment` 端口抽象，本 RFC 不扩scope；
3. 阶段 B 的 sed 脚本可能撞上字符串内出现 FQN 的假阳性 → 脚本只处理 `import` 行与方法调用前缀，逐 diff 人工复核。
