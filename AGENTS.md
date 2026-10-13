# 给改这个仓库的人（和 agent）

## 是什么

一个纯钩子的 LSPosed 模块（libxposed API 102），只针对 QQ 9.2.10 / Android 16。模块 APK 没有界面；
纯 Kotlin，八个文件（约 1500 行），没有第三方依赖、没有 `.so`、不联网。外观照 NagramXF 那套长相做（液态玻璃 + Monet 取色 + TG 式输入栏）。

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
- 外观那三个文件（`Glass.kt` / `Input.kt` / `Looks.kt`）全是视图规则，dex 核不到，只能靠真机；
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

CI 四个作业：`dex` 核落点（真包从 `Sumicya/qqapk` 拼），`apk` 按五段版本跑单元测试再出包，
出包后 `cleanup-artifacts` 立刻把 artifact 收口到 5 个（任何分支与同仓 PR 都跑，最小 `actions: write`），
`cleanup-releases` 只在默认分支上把 Release 收口到 1 个（`contents: write`，与上面的写权限分开授权、不同授）。
失败日志以 GitHub Actions 原始日志为准。CI 不自动创建 Release、正式发行 tag 或 Release asset。

删工作流文件不会带走它的运行记录。旧工作流（test/apk/ci/pr_ci/check_commit_message/qself/qself-next/push_ci/spec-check）
的运行记录要清就一次性清，由主人点名后执行，范围永远排除 `build.yml`（五段总序号数它的成功出包数，
保留的 artifact 也挂在它的运行上）；清理用一次性工具，跑完把工具从仓库撤掉，不留常驻写权限作业。
运行记录的清理不并进常驻 CI：规范把可常驻的 `actions: write` 限定在「只用于删本项目 artifact」。

本地要 gradle 出包同样得显式给版本：`QSELF_VERSION=26.10.13.1.1 ./gradlew :app:assembleDebug`，没给就当场失败。

## 全局规范同步（上次同步 = 第二十五版修订 · 2026-10-13）

本仓库从 [Sumicya/selfs/GLOBAL.md](https://github.com/Sumicya/selfs/blob/main/GLOBAL.md)（第二十五版修订 · 2026-10-13）继承全部规则：
项目条目只做补充和收紧，不另立例外，也不把旧版条目当豁免；冲突时以 GLOBAL.md 为准，本仓库更严格的条目优先。

- 版本：产物统一五段 `yy.m.d.当日序号.总序号`，日界线 UTC+8。CI 的当日序号 = 本仓 `build.yml` 在该 UTC+8 日已触发、run_id 小于本次的运行数 +1；
  总序号 = 本仓库历史成功出包的运行数 +1；`run_attempt > 1` 复用第一次 attempt 的版本号，取不回就失败，不重新加号。
  文件名与 artifact 名都是 `Qself-<五段版本>`。
- 本地序号：本地出包同样五段，号必须可追溯——当日序号 = 本仓 `build.yml` 在该 UTC+8 日已触发的运行数 +1，
  总序号 = 历史成功出包运行数 +1，都从 `gh api "repos/Sumicya/Qself/actions/workflows/build.yml/runs?..."`
  的同一批记录里取；查不到记录就停下，不许拿当前时间或随手写的数充数。
  本地号可能和被随后触发的 CI 撞号，所以本地 APK 只作自测，发行一律用 CI 的 artifact。
- 衔接：口径切换后的第一个运行起按上式取数；历史 artifact 名里的序号只作历史记录，不重算、不回退。
- 清理：Release 留最近 1 个、artifact 跨分支跨事件合计留最近 5 个，artifact 另设 `retention-days: 5`（只是时间兜底）。
  artifact 清理用最小 `actions: write`（只用于删本项目 artifact），可在任意触发事件（含 pull_request）的构建 job 上跑，
  不与该 job 的 `contents: write` 同授；Release / tag 清理另开作业、只在默认分支、只给 `contents: write`。
  清理脚本写死「只删归属核验通过的本项目对象」，完整分页、按时间降序，删除前打印完整保留/删除清单，失败只报错不回滚。
- 发版与对外动作：CI 不创建 Release、正式 tag 或 Release asset，正式发版必须先获主人对项目、版本、触发条件的明确允许；
  删除远程分支、删除标签这类对外动作也要主人明确授权，CI 不隐式执行。
- 规范由 agent 在会话中核对，不设规范检查工作流（本仓 `spec-check.yml` 已删）。本仓变更记在 `CHANGELOG.md`。
