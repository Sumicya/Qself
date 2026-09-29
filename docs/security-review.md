# QQ 9.2.10 / Qself 安全核查（静态证据）

2026-09-29 重新从 `Sumicya/qqapk` 的提交 `bb1d7154d657182f12af13bce20e66909722beae` 克隆分卷 `qq.aa`～`qq.ae`，按文件名排序拼出 389.6 MB QQ APK。完整 SHA-256：`34bdea66e738062c3f762f7da98132addc7170394605a4f5ca1944bee1280c84`；包内有 37 个 dex。`tools/dexcheck.py` 当前检查 **180 条 Qself 挂点符号均存在**，并不检测 QQ 如何识别模块；下述发现是另用 `tools/dexq.py` 解方法引用所得。**只有静态调用线索，没有真机运行轨迹**；不能把“包含检测代码”说成“已检测到 Qself”或“会封号”。

## 找到的线索

- `classes.dex` 中 `com.tencent.qqperf.monitor.crash.c.F(StringBuilder)` 引用 `de.robv.android.xposed.installer`，调用 `com.tencent.mobileqq.utils.PackageUtil.isAppInstalled` 并附加 `isXposedInstalled:`。同类 `K(boolean,String)` 引用 `F`。这是 **旧 Xposed 安装器的包存在性检查**，位于崩溃诊断路径；不证明它能识别当前 LSPosed 或本模块，也不证明它每次启动都会执行。
- `classes16.dex` 的 `com.tencent.qqperf.monitor.crash.tools.h.e(int)` 读取 `/proc/self/maps` 并生成诊断字符串；`classes2.dex` 的 `com.tencent.turingfd.sdk.xq.Vermillion.a(Vermillion)` 也引用此路径。内存映射可能暴露已加载库/模块痕迹，但仅凭这一引用不能判断它在何时运行、具体找什么或是否上报。
- 此外存在安装包查询 API 的引用，但未追到 Qself 专属的匹配逻辑。以上只覆盖 dex 的静态可读部分；native 库、服务端判断与设备上的框架行为未核。

复查命令（解包到工作区外，不把 QQ 真包提交入库）：

```sh
git clone --depth 1 https://github.com/Sumicya/qqapk.git /tmp/qqapk
git -C /tmp/qqapk rev-parse HEAD
cat /tmp/qqapk/qq.a* > /tmp/qq.apk
sha256sum /tmp/qq.apk
python3 tools/dexcheck.py --apk /tmp/qq.apk --lint app/src/main/kotlin --quiet
python3 tools/dexq.py /tmp/qq.apk com.tencent.qqperf.monitor.crash.c com.tencent.qqperf.monitor.crash.tools.h com.tencent.turingfd.sdk.xq.Vermillion
```

`dexq.py` 只做线性指令扫描；没有完整控制流、native 代码或执行时序证据。`/proc/self/maps` 在其他 SDK 中也有正常诊断用途，切勿直接认定为模块探测。

## Qself 自身的安全边界与建议

- 已有：`scope.list` 仅声明 `com.tencent.mobileqq`；Manifest 无对外组件、无额外权限、`allowBackup=false`。这轮在 `onPackageLoaded` 和热重载路径再次验证 QQ 包名及进程，防作用域误配。**这不是反检测措施**。
- **公开签名密钥已经停用**：旧版本曾跟踪 `app/qself.p12` 并写死密码；从当前仓库树移除也不能抹掉 Git 历史，任何人仍可用旧证书签出仿冒更新。普通 CI 包现在仅用临时 debug 签名；可信发布包由维护者私有新密钥通过 Actions secrets 在 main 手动构建，且输出独立 artifact。密钥必须由维护者自行生成并离线备份；换证书的标准迁移需要卸载旧模块再安装，不能假设第三方签名绕过工具总能可靠覆盖。
- `noCrashReport()` 只处理 Bugly 初始化；`noTelemetry()` 只覆盖枚举过的 Beacon/StatisticCollector 入口。**两者都不等于阻止 QQPerf、Turing 或 native 诊断，也不该当作隐身保证**。不建议为了逃避检测对安全/风控逻辑做广域钩子；失败或缺少可见性时应明确说明。
- 排查实际行为优先在用户授权的测试设备上对齐 QQ 原版与本模块的启动/崩溃日志；避免上传聊天内容、账号、完整设备标识或原始内存映射。需要视觉/性能对照时只保留页面、时间点、`logcat -s Qself` 中已确认不含敏感信息的片段。
