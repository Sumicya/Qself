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
失败日志以 GitHub Actions 原始日志为准。

## 规范

规范入口：`Sumicya/selfs` 的 `GLOBAL.md`（依据 selfs 提交 `c5ac12d351842b4668a29685a64a9718b6409bad`，30728B／120 行）：
https://github.com/Sumicya/selfs/blob/arena/2d001a47-selfs/GLOBAL.md 。本文件只记项目事实、必要限制与这个指针。
默认分支 `main` 上那份是 3488B 的第 0 版（2026-10-13），已被取代，不得再引它作依据。

- 版本：本仓固定两个工作流 —— 出包 `build.yml`（`name: Build`）与 PR 校验 `pr-check.yml`（`name: PR check`）。
  号源只有 git 提交数一处（规范 `c5ac12d`:55）：总序号 = `git rev-list --count HEAD`，
  当日序号 = `git rev-list --count --since=<当日0点 UTC+8> HEAD`；`run_number`、事件类型、工作流文件名、PR 与否一律不进版本号。
  `actions/checkout` 必须 `fetch-depth: 0`（浅克隆下提交数恒为 1、所有产物都会叫 `.1.1`）；取号脚本启动行打印 head sha、两个数与命令原文，
  取不到数就失败退出，不留降级、不退化四段。head 提交不在当日（UTC+8）时当日序号取 0（重出旧提交的包），不写 1 冒充当日首包。
  同一提交重跑（重试、再 dispatch、改文件名）得到同一个号，天然幂等；撞号只可能来自同一提交重出包（覆盖，删旧留新）或历史被重写，判新旧按 artifact `created_at`。
  提交数就是号源：禁止空提交与无内容提交（禁 `--allow-empty`），禁止 force push 与 rebase 重写已出包的历史。
  文件名与 artifact 名都是 `Qself-<版本>`。
- 出包触发只有 `push`（不限定分支，会话分支推送同样出包用于验证）与 `workflow_dispatch`；不得接 `pull_request`，
  也不得为「只在默认分支出包」加 `branches` 限定或 `github.ref` 守卫 —— 那等于砍掉验证手段（依据 selfs 提交 `a8787a8`）。
- 本地要 gradle 出包必须显式给 `QSELF_VERSION`（五段），缺失即失败；本地包只作自测，发行用 CI 的 artifact。
  PR 校验不取号，给不参与编号的占位版本名，供 Gradle 用。
- CI 分工：`build.yml` 四个作业 —— `dex` 核落点（真包从 `Sumicya/qqapk` 拼）、`apk` 跑单元测试并出包上传、
  `cleanup-artifacts`（本 run 上传成功后按四条判据清理：口径=段数等于 `build.yml` 顶部 `env: SEGMENTS`；来源=artifact 的 `workflow_run` 事件是 `pull_request` 一律删；
  重复当日序号=同日同当日序号按 `run_started_at` 删旧留新（不按总序号大小判：换号源后总序号会回头，按大小判会误删新产物），删前把两个 artifact 的名与 id、两次运行的 rn 打进日志；
  数量=当日之外每条序列 artifact 5 个、run 5 条，当日豁免。
  口径、来源、重复三条不豁免、当轮即删；排队按 `run_started_at`（UTC+8 日界）、`id` 只作同刻 tiebreak，该字段缺失才退回 `created_at`；承载保留产物的 run 不删也不计入上限，正在跑的本次与未完成（`status != completed`）的 run 都不删；
  本仓 PR 校验工作流的超量 run 也在这里一并收。最小 `actions: write` + `contents: read`，与 PR 校验的清理作业同一 `concurrency` group（`qself-cleanup-artifacts`）串行）、
  `cleanup-releases`（只在默认分支收 Release 到 1 个，`contents: write`，与上者不同授）；
  `pr-check.yml` 三个作业（`dex` + 单元测试 + `cleanup`），不取号、不出包、不上传；`cleanup` 真删。
- 清理逻辑只有一份 `.github/scripts/cleanup.py`，两个工作流都 `CLEANUP_APPLY: 'true'` + `actions: write` 真删：
  PR 校验工作流的清理作业是会话可达的那个真删调用点（推送即触发），出包工作流出包后收口；两处同一 `concurrency` group 串行。
  清理失败按原因分口径（规范 `a8787a8`）：DELETE 返回 403（权限缺口，如 fork PR 只读）打 `::warning::` 写明对象、端点与返回码后继续，
  不让 job 红，同一对象连续两次运行都失败才升级为阻塞项；403 以外的失败（脚本异常、其它错误码）当轮即红 job，
  红了的 run 在汇报里列为阻塞项并附失败对象与返回码，不许吞掉、不许改成 warning 蒙过去。
  只读预演（`actions: read` + `'false'`）是备用形态，禁止拿「预演已证」当清理已做。超量对象状态记「待下一次 PR 校验运行」；不许把清理交给主人，也不许等合并。
- 2026-10-14 旧号源（`run_number`）留下的记录，换号源后不再产生：
  ① 当日序号 6 重复：`Qself-26.10.14.6.272`（rn=272，事件 `push`，sha 7920b0a2）与 `Qself-26.10.14.6.273`（rn=273，事件 `pull_request`，同 sha）；
     同日 `Qself-26.10.14.7.271`（rn=271，事件 `pull_request`，sha e902407b）当日序号 7 排在 6 之前。
     `rn=275`（sha 25d56e64）是 `pull_request` 运行，取号、算版本、测试与出包、上传步骤全 skipped、按 run 反查 artifact 数=0，`.6.275` 从未存在。
     删除前名单（取值 2026-10-14T03:17Z，`gh api repos/Sumicya/Qself/actions/artifacts`）：`.7.271`、`.6.272`、`.6.273`、`.3.274` 四条。
  ② 当日序号 3 重复：`Qself-26.10.14.3.274`（rn=274，事件 `push`，id 11864292225）与 `Qself-26.10.14.3.276`（rn=276，事件 `push`，分支 `arena/e207d38d-qself`，id 11900660871）；
     前者由 rn=276 的 `cleanup-artifacts` 按判据三删掉（当时口径为「保留总序号最大者」，现口径已改为按 `run_started_at` 删旧留新）。
  ③ 换号源过渡（已完成）：旧口径末号 `Qself-26.10.14.4.277`（id 11900691515，rn=277，事件 `push`，取值 2026-10-14T14:01:49Z）与 `Qself-26.10.14.3.276`（id 11900660871，rn=276，事件 `push`，取值 2026-10-14T13:57:41Z）
     在新口径首包出成后由 Build rn=278（head `c5a7877c`）的 `cleanup-artifacts` 按规范 `c5ac12d`:60 清掉，过渡清理由同一份 `cleanup.py` 执行、包出成前有守卫不放行；
     按 id 复核两件均 404（取值 2026-10-15T00:19:13Z）。删前事实（名、id、rn、取值时间）记在上面两行，清完该段已从脚本删除。
- 新口径首包：`Qself-26.10.15.1.3102`（id 11937962375，114728B，来自 run 38659018059，rn=278，事件 `push`，head `c5a7877c279ed714546eae120beef5f479db0b79`，创建 2026-10-15T00:16:56Z，取值 2026-10-15T00:19:13Z）。
  head `c5a7877c` 的两条取号命令输出：`git rev-list --count HEAD` = 3102、`git rev-list --count --since="2026-10-15T00:00:00+08:00" HEAD` = 1；
  其上一条提交 `8d90c39` 的输出是 3101 与 0。清理后 artifact 名单只剩 `Qself-26.10.15.1.3102`。
- 已知风险（默认分支触发）：默认分支 `.github/workflows/build.yml` 仍是旧版本（实查 2026-10-14T14:00Z：`on: push / pull_request / workflow_dispatch`，`push` 不限定分支；
  默认分支的工作流清单只有这一个文件，无 `pr-check.yml`）—— 号源改成提交数后 PR 运行不再吃号，但同一提交被 `push` 与 `pull_request` 各出一次包会产出两个同名 artifact（互相覆盖、清理还会判成重复当日序号）。
  触发条件：从旧分支开的 PR（载体：开放 PR #10，head `arena/01a109fa-qself`，ahead_by 13）按旧触发跑一次出包工作流；风险存续到本次交付进入默认分支为止，不报「号源已收口」。
  本会话不向非本会话的开放 PR 分支推送，也不做关闭 PR 的动作（关闭只由主人发起）。
- 本项目 CI 只出 debug 包，不创建 Release、正式 tag 或 asset；发版须主人明确授权。
- 会话分支只由会话删（删 ref 需 `contents: write`，不进 CI）：判据是相对默认分支 compare `ahead_by=0`，为 0 属默认授权当轮删；
  `ahead_by>0`（含 PR 关闭未合入）一律保留 —— 要去除先用 `gh api repos/<owner>/<repo>/compare/<默认分支>...<分支>` 取 `ahead_by`、
  提交清单与改动文件，逐个提交写可取处与可去处，给留存或去除的建议与后果，向主人确认后才删；
  问句必须附差异与建议，不许只问「删不删」。仍挂开放 PR 的分支保留不报「待删」。本仓变更记在 `CHANGELOG.md`。
