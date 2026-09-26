# 给改这个仓库的人（和 agent）

## 是什么

一个纯钩子的 LSPosed 模块（libxposed API 102），只针对 QQ 9.2.10 / Android 16。没有界面、没有配置、
没有网络、没有第三方库：只有 `compileOnly` 的 libxposed 和测试用的 JUnit。

## 规矩

按 [ponytail](https://github.com/DietrichGebert/ponytail) 来：
- 先问「不做行不行」；再看仓库里有没有现成的；再看 Kotlin 标准库；再看 Android 原生 API；最后才写代码
- 不加抽象、不加依赖、不加样板；能删就删；一个功能 = 一个顶层函数 + `Qself.kt` 清单里一行
- 钩子体不要 try/catch：libxposed 默认异常模式下，钩子抛异常等于这一次没装
- 故意省掉的地方写 `ponytail:` 注释，说清上限和往上走的路
- 非平凡逻辑留一份能跑的检查（目前只有 `ProtoTest.kt`）
- 代码先行，解释不超过三行；中文

## 怎么找落点

类名、方法名全部是从 9.2.10 的 dex 里查出来的，不是猜的。换版本时：
1. 拿到 QQ 的 APK，把所有 dex 的类 / 方法 / 字段导出成一张表（任何 dex 解析器都行，`dexdump -l plain` 也可以）
2. 用表核对每个功能里写死的名字；混淆过的短名字（`a`、`d1`）按签名和所在类找
3. 只改名字，不改结构；装上后看 `logcat -s Qself` 那一行，✗ 的再修

## 构建

本地不需要 SDK：push 之后 GitHub Actions 出 APK（`.github/workflows/build.yml`），`ci/build.yml` 是加了单元测试的建议版本。
签名固定在 `app/qself.p12`（密码 `qself`），换签名会导致覆盖安装失败。
