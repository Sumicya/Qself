# 给改这个仓库的人（和 agent）

## 是什么

一个纯钩子的 LSPosed 模块（libxposed API 102），只针对 QQ 9.2.10 / Android 16。模块 APK 没有界面；
纯 Kotlin，七个核心文件（约 1500 行），没有第三方依赖、没有 `.so`、不联网。外观照 NagramXF 那套长相做（液态玻璃 + Monet 取色）。

开关是 QQ 主页底栏右上角一颗圆钮弹出的系统对话框，状态存 QQ 的 SharedPreferences「qself」，键就是功能名。

**改任何落点之前先跑 `tools/dexcheck.py`**（真包在 `Sumicya/qqapk`，`cat qq.a* > qq.apk`）。
本地没有 Android SDK 时用 `ci/check.sh` 做类型检查 + 跑 ProtoTest（第一次会拉工具链到 `~/.cache/qself-tc`）。

## 规矩

按 [ponytail](https://github.com/DietrichGebert/ponytail) 来：
- 先问「不做行不行」；再看仓库里有没有现成的；再看 Kotlin 标准库；再看 Android 原生 API；最后才写代码
- 不加抽象、不加依赖、不加样板；能删就删；一个功能 = 一个顶层函数 + `Qself.kt` 清单里一行（清单顺序就是开关顺序）
- 钩子体不要 try/catch 兜底整个功能：libxposed 默认异常模式下，钩子抛异常等于这一次没装。
  但**从 `chain` 取参数、反射取视图这类可能落在兄弟子类上的动作要包 `runCatching`** —— 挂点在基类时，钩子会盖住所有子类
- 钩子经 `hook()` 装就自动受开关管；视图规则自己 `on("功能名")`
- minSdk 36：只有 Android 16，别写 `SDK_INT` 判断和降级分支
- 故意省掉的地方写 `ponytail:` 注释，说清上限和往上走的路
- 非平凡逻辑留一份能跑的检查（目前只有 `ProtoTest.kt`）
- 外观文件（`Glass.kt` / `Looks.kt`）全是视图规则，dex 核不到，只能靠真机；
  改完说清楚改了哪块长相，别让人猜
- 代码先行，解释不超过三行；中文

## 怎么找落点

类名、方法名全部从 9.2.10 的 dex 里查出来，写进 `tools/symbols.txt`，不是猜的。

混淆过的短名字（`a`、`k`、`d1`）按签名找，别按名字猜：`tools/dexq.py` 是个最小反汇编器，
打印一个类里每个方法用到的字符串、读写的字段、调用的方法 —— 上游 QAuxiliary 用 DexKit 在运行时搜的就是这些判据，
这里离线搜同一件事，搜到就把名字写进表和代码。两条规矩：

- `Class<*>.method(name)` 和 `constant()` 都往父类找（QQ 的挂点大半声明在 MVVM 基类 / Api 基类上），
  `declaredMethods` 只翻本类会静悄悄空转；反射**调用**用 `getMethod`（public + 父类），装钩子用 `method()`
- **「这方法声明在本类还是父类」离线判不准**：QQ 的 class_data 有畸形项，`dexq.py` 解到一半 method_idx
  会跳出界（撞上就 break，所以它打印的「声明方法」可能不全）。别拿它下结论，一律按沿父类找写，装上看日志。
  也正因如此 `constant()` 的 pick **必须带名字过滤** —— 写宽了会连父类、Object 的 notify/wait 一起改掉
- 解析方法要在**装的时候**做完（`val m = c.getMethod("x")`），别写在钩子体里 —— 找不到就当场 ✗ 报出来，
  而不是每次绑定时被 `runCatching` 吞掉，看起来装着其实什么都没干

换版本时：
1. 拿到 QQ 的 APK（`Sumicya/qqapk` 里是 9.2.10 的分卷，`cat qq.a* > qq.apk`）
2. `python3 tools/dexcheck.py --apk qq.apk --lint app/src/main/kotlin`，看哪条红
3. 红的那条用 `tools/dexq.py` 按签名找新名字（参数类型、用到的字符串、读的字段），先改表再改代码
4. 装上后看 `logcat -s Qself` 那一行，✗ 的再修

## 做不到的（别再去挖一遍）

- **全量 Monet 化**：QQ 没有集中的取色入口。`com.tencent.mobileqq.theme` 包里只有 DarkModeManager /
  ThemeConstants，一个返回颜色的方法都没有；界面颜色散在 QUI 设计系统的几百个 drawable 里
  （`qui_button_bg_primary_default`、`qui_aio_word_primary`…）。Telegram/NagramX 能全量 Monet
  是因为有 `Theme.getColor(key)` 一个口子。hook `Resources.getColor` 只能碰纯色资源、碰不到 drawable，
  还会把整个 UI 弄花 —— 所以 Monet 只作用在 Qself 自己画的那几块玻璃上（`Glass.kt` / `Knob.kt`）。
- **屏蔽更新**：`UpgradeController` 在 9.2.10 里没有了，`ConfigHandler` 也没有上游要找的
  `void(UpgradeDetailWrapper)`；`UpgradeBannerProcessor` 里对不上 `void(Message,long,boolean)`。要做得重新找落点。
- **屏蔽抖动窗口**：`AIOShakeHelper` 类不存在（上游自己写着「作用暂时不明」）。
- **移除消息列表顶栏横幅广告**：上游靠的 `QbossADImmersionBannerManager` 不存在；剩下的
  Renewals/Push/DressUp/VasAD 四个 BannerProcessor 的 `handleMessage(Message)→boolean` 返回值语义
  没机器验不了，不敢直接吞。要做就走 Looks.kt 的视图规则，别猜返回值。
- **语音转发及保存**：上游 411 行，要自己建确认对话框和长按菜单，不是一个钩子的事。
- **气泡尾巴**：尾巴画在 9-patch 里（`skin_aio_*_bubble_nor/pressed`，朝头像那侧凸 17 行像素），QQ 也确实自带
  同尺寸(110x106)无尾巴的 `_simple` 版 —— 但两套九宫格内边距不一样，直接换 id 会让消息文字不居中；
  而且真机上聊天里本来就没有尾巴（NT 聊天大概率不走这套图，只有充值气泡/皮肤走）。0.8.1 试过，已撤，别再碰。

## 构建

本地不需要 SDK：push 之后 GitHub Actions（`.github/workflows/build.yml`）出 APK。
当前仓库不保存签名私钥；debug APK 使用 Android 默认 debug 签名，正式签名需由仓库外的安全签名环境提供。

CI 三个作业：`dex` 核落点（真包从 `Sumicya/qqapk` 拼），`apk` 跑单元测试再出包，`cleanup_artifacts` 在出包后按全局规范保留最近 5 个本项目 artifact 并清理已核实的历史产物；失败日志以 GitHub Actions 原始日志为准。
清理必须覆盖每一个会上传 artifact 的事件（`push`、`workflow_dispatch`、同仓库分支的 `pull_request`）：只上传不清理的事件会让总数稳定超过保留上限。fork PR 的 token 只读、删除必然 403，所以跳过；改触发条件前先确认 head 仓库就是本仓库。
版本序号来自 `Build` 的真实 run 记录：`push`、`pull_request`、`workflow_dispatch` 都计数；总序号用 `run_number`，当日序号是 UTC+8 当日、按 `run_number` 排序截至本次的位次。失败运行计一次，重跑沿用原 run；`ci/check.sh` 不出 APK、不计版本。本地 Gradle 出包必须显式提供符合五段规则的 `QSELF_VERSION`，缺失或无效时停止。2026-10-6 从旧口径切换到 run_number：旧总序号截至 `Qself-26.10.6.2.175`，新总序号因此允许向前跳号，不回退、不复用历史号。
CI 不自动创建 Release、正式发行 tag 或 Release asset。

## 全局规范

本仓库上次同步 = 第二十二版。

规范指针：按 [Sumicya/selfs 的 GLOBAL.md](https://github.com/Sumicya/selfs/blob/main/GLOBAL.md) 最新版执行；本文件只保留 Qself 项目专属条目，不复制全局规则。
