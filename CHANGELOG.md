# CHANGELOG

## 2026-10-14 — 清理作业按规范三条判据重写（数量 / 命名口径 / 来源）

- 依据：selfs 分支提交 `2dec668`（清理判据三条：数量=当日之外每条序列 artifact 5 个、run 5 条；命名口径=段数等于工作流顶部 `SEGMENTS` 声明值；来源=artifact 的 `workflow_run` 事件是 `pull_request` 一律删。
  数量判据当日豁免（当日序号取自当日运行列表，删当日运行会抽走计数基准），口径与来源不豁免、当轮即删；PR 校验工作流不挂清理作业，它的超量 run 由出包工作流清理 job 一并删；job 跑完打印删了哪几个、各剩几条）。
- `build.yml`：顶部新增 `env: SEGMENTS: '5'`；`cleanup-artifacts` 重写为单步脚本，仍最小 `actions: write`；承载保留产物的 run 不动（删 run 会连带删产物），正在跑的本次不删。
- 干跑（DELETE 全拦截至 stderr，用抽出的同一份脚本）：live 数据 → 删 0 个 artifact、0 条 run，剩 build 5 条 / pr-check 4 条，与实况一致；
  合成混合数据（7 个旧出包产物 / 7 条旧出包 run / 7 条旧 PR 校验 run / 1 个四段错口径 / 1 个 PR 来源）→ 删 4 个 artifact（1 个当日按来源判据、1 个当日按口径判据打「段数 4 ≠ 5」、2 个按数量判据），保留 5 个；删 4 条 run，剩 build 6 条 / pr-check 5 条（各留 5 + 当日豁免与在跑的本次）。
- 文档：`AGENTS.md` 清理条目改为三条判据。

## 2026-10-14 — 拆工作流：出包与 PR 校验分开，号段不再被 PR 运行吃

- 依据：selfs 分支 `arena/2d001a47-selfs` 提交 `5e2b978`（版本号只能取出包工作流的 `run_number`；出包与 PR 校验必须是两个工作流文件；
  出包触发只写 `push`（限定默认分支）与 `workflow_dispatch`；PR 校验只写 `pull_request`；调试 CI 的推送不许吃号；同仓不得并存两种取数口径）。
- 根因：`run_number` 在运行创建时就分配、按工作流计数，步骤级 `if: github.event_name != 'pull_request'` 只能挡上传，挡不住吃号。
  实测 `7920b0a2`、`25d56e64` 两个提交各吃两个号（rn 272+273、274+275），rn=273 还是 PR 运行却出了包（当日序号 6 的旧口径产物）。
- `build.yml`：触发收敛为 `push: branches: [main]` + `workflow_dispatch`；删掉全部 PR 守卫与 PR 专用的占位测试步骤；
  当日序号注释改「本出包工作流的运行序号」；清理作业去掉 PR 条件（清理只挂在出包工作流）。
- 新增 `pr-check.yml`：`on: pull_request`、`permissions: contents: read`，两个作业（`dex` 落点核对 + 单元测试用占位版本名），不取号、不出包、不上传、不清理。
- 旧口径产物处置：三笔旧口径产物（id 11861519548、11863836941、11864730478；当日序号序列 7 → 6 → 6 与新口径 3 并存）。会话令牌 `DELETE /repos/Sumicya/Qself/actions/artifacts/<id>` 返回 403，响应头 `X-Accepted-Github-Permissions: actions=write`（缺 actions 写权限类），本会话删除未成；超量产物按职责由出包工作流的 `cleanup-artifacts` 删。复核：三条 `GET /repos/Sumicya/Qself/actions/artifacts/<id>` 全 404，artifact 计数 1 个（只剩新口径 `Qself-26.10.14.3.274`，id 11864292225，114752B）。
- 文档：`AGENTS.md` 版本条目按两工作流重写；`README.md` 符号数 177 → 182 与实际一致；本条记入本文件。

## 2026-10-14 — 堵刷号漏洞：PR 运行只校验不取号不出包

- 依据：selfs 分支 `arena/2d001a47-selfs` 提交 `7f8adb6`（规范：出包运行只有 push 与 workflow_dispatch；PR 运行只跑校验、不取号、不上传 artifact；
  当日序号改为当日（UTC+8）该仓出包运行的序号，从 1 起按开始时间递增、PR 不计入；禁止按 head 提交位次取数；禁止空提交刷号与为触发 CI 而推送）。
- 起因：本仓 `on: push + pull_request` 双触发，同一提交出两个 artifact、吃两个总序号（实测 sha 9afda492 → run 268 + 269，sha e902407b → run 270 + 271），
  一夜把总序号从 220 刷到 271。
- `build.yml`：上传步骤加 `if: github.event_name != 'pull_request'`；算版本步骤与缓存存取步骤只在非 PR 事件跑；
  `apk` 作业任何事件都跑单元测试，PR 事件用占位版本名（不参与编号）并且不出包；`Check libxposed packaging` 同样只在出包运行跑；
  当日序号改数「当日（UTC+8）本工作流的 push / 手动运行」，按 `run_started_at` 升序取本次位次；`checkout` 不再需要 `fetch-depth: 0`（新的当日序号不查提交历史）。
- 干跑核验（拿真实运行数据跑该步脚本）：以 run 272（push）为本次 → 当日序号 2；以 run 270（push）为本次 → 序号 1；
  以 PR 运行 id 为本次 → 列表里找不到 → 打原因退化四段；`run_attempt > 1` 无缓存 → 按口径失败；有缓存 → 复用不重新加号。
- 文档对齐：`AGENTS.md` 版本条目按新口径重写；`README.md` 符号数 177 → 182（与 `tools/symbols.txt` 实际条数一致）。
- 顺带处置：本地沙箱 `.git` 再次被回滚到分支点，按规范 `git fetch` + `git reset --soft origin/arena/e207d38d-qself` 接回，diff 与远端一致。

## 2026-10-14 — 版本步锚 head 提交 + 底栏角标落点

- 版本步修正：当日序号改取「本次 run 的 head 提交」——PR 运行取 `github.event.pull_request.head.sha`，push / 手动运行取 `github.sha`。
  原先按检出提交数，PR 运行的检出是合成合并提交（祖先含默认分支当日提交），同一个 head 在两个事件下被数成两个位次
  （`e902407`：push 事件数出 5、PR 事件数出 7），同一份代码因此出两个当日序号不同的产物；改后两边都是 5。
- 干跑核验（拿真实 run 数据跑该步脚本）：push 事件（run_number 270）与 PR 事件（run_number 271）对同一 head 都得当日序号 5；
  head 不在当日（`49913cc`）→ 打原因退化四段；head 对象缺失且取不回 → 打原因退化四段；`run_attempt > 1` 无缓存 → 按口径失败。
- 底栏角标：按真包 smali 补落点——`TabFrameControllerImpl#updateTabInfo` 取底栏 `QUIBadge` 后调 `setRedNum`，
  `updateNum` 把 `mText` 压成 99+、真值留在 `mNum`；钩子补到 `setRedNum` / `setGrayNum` / `setAIOBarNum` / `onDraw`，
  `tools/symbols.txt` 同步登记，`dexcheck` 182 条 0 红。
- 文档去版号：本文件与 `AGENTS.md` 清掉版号残留，`AGENTS.md` 记「head 提交按事件取」的口径。
- 本地 `.git` 被沙箱回滚到分支点（工作树完好），按规范 `git fetch` + `git reset --soft origin/arena/e207d38d-qself` 接回，diff 与远端一致。

## 2026-10-14 — 跟进全局临时入口（PR #6，head a9fc529）

- 依据：selfs 分支 `arena/2d001a47-selfs`（PR #6）当轮读入；PR 合并后回到默认分支为准。
- 版本：总序号从「run_number − 35」改成直接取本次 run 的 `run_number`（本仓 workflow 未重建，不写基线；重建才写基线相减，未记录按原值不判失败）；
  当日序号取不到时不再失败，退化四段 `yy.m.d.总序号` 并写日志说明；`build.gradle.kts` 与 `build.yml` 的版本校验都放宽到四段/五段。
- 分支：删分支的唯一判据改成相对默认分支 compare `ahead_by=0`；先前按「继承即可删」删掉的 `arena/63fa89b7-qself`、`arena/614127b9-qself`
  已用原 SHA 恢复（`e64c8e65…`、`ae1a5a1f…`），三支会话分支（含 PR #10 的 `arena/01a109fa-qself`）ahead_by 分别为 13/10/2，一律保留并报主人。
- 文档：`AGENTS.md` 去掉同步戳与复制的全局条目，改成规范指针 + 项目事实与限制。
- 跨仓：selfs、qqapk、Selfblox 当前运行记录 0 条、无工作流文件、无待删的已合入会话分支，无需跨仓写操作；selfs PR #6 仍开放。

## 2026-10-13 — 跟进全局「第0版修订」

- 依据：selfs 分支 `arena/7a7f3552-selfs` 提交 `1984b03`（第0版修订：计数改锚 run_number、分支保留 0、清理默认授权）；尚未合入默认分支，按主人指示先行执行。
- `build.yml`：当日序号从「run_number − 当日锚点」改成「head 提交在当日（UTC+8）提交序列中的位次」
  （`git rev-list --count --since="<当日> 00:00:00 +0800" HEAD`，`apk` 作业 checkout 改 `fetch-depth: 0`）；
  停用旧锚（历史成功数、现存 artifact 最大号），删掉对应的兜底检查。
- 运行记录清理改成只留最近 5 条：取消「当日锚点」特例（版本取数不再依赖运行记录），也不再为 artifact 载体破例。
- 分支处理改正：先前按「分支 0 个」删了 `arena/63fa89b7-qself`、`arena/614127b9-qself`，但 `614127b9` 的 2 个提交
  （`83ac6db` 玻璃按压 + 精确角标、`ae1a5a1` 输入行重挂载）在留存线里没有等价补丁（`git cherry` 两个「+」），属于未继承就删。
  已用原 SHA 恢复（`e64c8e65…`、`ae1a5a1f…`），把 `614127b9` 的应用侧改动（`README.md`、`Glass.kt`、`Hook.kt`、`Input.kt`、
  `Looks.kt`、`ProtoTest.kt`、`tools/symbols.txt`）继承进本分支（`4b727a7`，CI 全绿：dex + 测试 + 出包，run_number 261），
  应用侧逐文件与本分支一致后，才按「继承之后去删」重新删掉这两个分支；它的 CI 侧改动早已被本分支的新版取代，未继承。
  `63fa89b7` 的提交全部含于 `arena/01a109fa-qself`（`git cherry` 无输出），继承成立后同样删除；`arena/01a109fa-qself` 因 PR #10 未合并保留。
- 教训两条记进 `AGENTS.md`：删会话分支前先 `git cherry` 确认无未继承提交；恢复远端 ref 会触发一次 push 构建，
  旧分支的旧工作流会在被清理过的记录上算出版本错号（本次出过两个当日序号为 0 的无效产物，已在记录里说明，随保留窗口自然淘汰）。
- 实跑核验：本次推送出包（run_number 257，当日位次 12 = head 提交在当日提交序列中的位次；总序号 222 = 257 − 35），
  运行记录收口到 5 条、artifact 5 个；位次取不到时（head 提交不在当日）会当场失败，已用本地预演验证。

## 2026-10-13 — 跟进全局第0版

- 第0版把规范压到 55 行；对 Qself 的实际变化：保留项新增「运行记录 5 条」，清理权限明确 `actions: write` 只用于删本项目 artifact 与 run。
- `build.yml`：五段版本取数改成只锚定本次 run 的 `created_at`/`run_number`——当日序号用「当日锚点」做减法，
  总序号用固定基线 35（run_number 253 ↔ 218）；删掉历史运行记录后仍然算得准，并加了「号必须大于现存产物最大号」的兜底检查。
- `cleanup-artifacts` 增加运行记录收口：留最近 5 条 + 当日锚点 + 现存 artifact 的载体，其余（含旧工作流遗留）删除；不再需要一次性工具。
- `AGENTS.md`：版本戳改「第0版」，条目按「只记事实、限制、指针」删繁。
- 已核验：删掉 248 条运行记录后，下一次运行 `run_number` 继续递增（254 → 255），出号（当日位次 9、总序号 220）；
  「总序号必须大于现存产物最大号」的兜底检查保留。

## 2026-10-13 — 跟进全局第二十五版修订

- 第二十五版修订（2026-10-13）改了清理权限：`actions: write` 允许用在任意触发事件（含 `pull_request`）的构建 job 上，
  只用于删本项目 artifact，且不得与 `contents: write` 同授。
- 照此把 `build.yml` 的一个 `cleanup` 拆成两个作业：`cleanup-artifacts`（最小 `actions: write`，任何分支的 push 与同仓 PR 都跑，
  本 run 上传成功即收口到 5 个）与 `cleanup-releases`（`contents: write`，只在默认分支，Release 留 1 个、随旧 Release 清专属 tag）。
- `AGENTS.md` 的版本戳更新为「第二十五版修订 · 2026-10-13」，清理条目按新权限改写，并写明运行记录清理不并进常驻 CI。

## 2026-10-13 — 同步全局第二十五版

- `AGENTS.md` 的规范同步段从第十五版迁到第二十五版，删掉与全局相反的「应有只读规范检查」一类表述。
- 删除 `.github/workflows/spec-check.yml`：第二十五版规定不设规范检查工作流，规范由 agent 在会话中核对。
- `build.yml`：`apk` 作业的五段版本改为「当日序号 = 本仓当日已触发、run_id 小于本次的运行数 +1（区分并发），
  总序号 = 历史成功出包运行数 +1」；`run_attempt > 1` 用缓存复用第一次 attempt 的版本号，取不回即失败。
  新增 `cleanup` 作业，只在默认分支出包上传成功后清理数量（artifact 留 5、Release 留 1，随旧 Release 清其专属 tag），
  写权限只授予该作业；其余作业保持只读。
- `app/build.gradle.kts`：本地出包必须显式给 `QSELF_VERSION`（五段格式），缺失即失败，不再用 `0.0.0.0.0` 占位；本地取号规则写在 `AGENTS.md`。
- `AGENTS.md` 写明继承关系：项目条目只做补充和收紧，不另立例外；补上可追溯的本地序号规则。
- 旧工作流的 637 条运行记录一次性清掉（保留 `build.yml` 的运行），项目 artifact 同时收口到最近 5 个。
  清理工具 `clean-old-runs.yml` 随本次分支推送触发，跑完即从仓库撤掉。此处写权限落在分支构建上，
  与清理通用步骤第 1 条「写权限只给默认分支作业」有出入，依据是第二十五版第 99 行「用户明确指示优先于本规范」，
  动作范围仅限运行记录与项目 artifact。副作用一条：2026-09-06 的 `telegram-bot-api-binary`（`push_ci.yml` 的产物，
  该工作流文件早已删除）随它那条运行一起删除，已事前说明。
- 衔接：口径切换后的第一个运行起按新口径取数；历史 artifact 名里的序号只作历史记录，不重算、不回退。
