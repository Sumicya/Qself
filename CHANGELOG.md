# CHANGELOG

## 2026-10-15 — 全模块链路重构与旁支继承

- 重构八个源码文件的安装、业务门解析、监听/卸载、帧前布局和菜单链路；保留已验证的防撤回 protobuf 语义与 QQ 原生输入/发送逻辑，不为重写更改协议。每项安装失败逆序撤钩，保留真实失败状态并可查看诊断；热重载在主线程先清旧 UI，清理失败拒绝热重载。
- 去掉 150ms 外观扫描与 row.post 延迟注册，首次 attach 同步接入；同帧组首索引避免长连发反复向前反射，pre-draw 后失效。昵称与间距先完成布局再绘头像，浮动位置不做追赶动画；续条只收紧正 topMargin，原布局、昵称和头像关闭/复用可还原。
- 菜单缩为最多 360dp × 440dp / 可用高度 62%，避开系统栏和键盘；保留卡片空白区域消费触摸，新增开合、按压、勾选描画/变色、返回关闭与恢复默认。玻璃按压/切页、原生输入焦点阴影遵守系统动画开关；卸载取消本模块动画和监听，底栏关闭恢复原参数。
- 以有实际内容的多父合并继承 arena/01a109fa-qself、arena/63fa89b7-qself、arena/614127b9-qself 的历史。1396c8be 的严格编译退出、52411e87 的紧凑菜单/连发间距、83ac6dbb 的按压目标已落实；e64c8e65/b78bd64d 的精确角标沿用当前更完整实现。旧输入区搬移、已移除的昵称精简、旧版本号源/额外 CI/旧规范戳均不恢复；保留当前严格清理与五段 git 提交数取号；Gradle 同步拒绝四段号、缺失号和常数回退；PR 单测格式夹具同步为五段，仍不取号、不出包。
- 本地 Kotlin 类型检查与 15 项纯逻辑测试、2 项 dex 解析测试、5 项清理测试通过；QQ 9.2.10 真包 180 条符号、0 不匹配。真机首次浮动、长连发滚动、昵称、输入/键盘与动画效果仍待验收；不得将 CI 通过写成视觉通过。

## 2026-10-15 — 修复连发安装失败、恢复原生输入区、移除昵称精简

- 用户反馈连发合并显示 ✗。真包 class_data 证实头像 C1() 访问标记为 0x12（private final），上一版 getMethod 只找 public 导致安装失败；改用声明方法查找并显式开放访问，新增私有访问器回归。未移除 failed 标记或掩盖安装失败。
- TG 输入栏重写为 QQ 原生输入区玻璃化：不搬移任何视图、不创建镜像按钮、不隐藏图标带、不压缩兄弟槽位，保留真实 EditText 与 QQ 原发送/语音/表情/附件逻辑。按用户要求迁移时重新启用一次，后续关闭保持有效；旧搬移版升级需强行停止 QQ 后再打开，开关可还原背景与最小高度。
- 按用户要求移除“昵称只留名字”：删除功能入口、plainNick/NICK_DROP 实现及专用符号。连发的组首昵称仍保留 QQ 原装饰。
- 本地 Kotlin 类型检查与 11 项测试通过，真 QQ 包核验 180 条符号、0 不匹配。尚未连接设备，连发开关、输入框、键盘和发送行为待真机重新验收。

## 2026-10-15 — 撤回浮动验收，重做消息几何定位

- 用户反馈：自己的头像多个，单条头像偏下；撤回上一版浮动验收。已当面确认昵称采用 TG 式固定组首，不进气泡、不跟头像浮动。
- `Chat.kt` 改为在 AIOBubbleMsgItemVB 绑定后从组件表一次采集全部头像容器、实际头像图片、消息正文和昵称；用 BaseContentComponent 的实际视图边界求内容中心，不再拿整行的底部或头像容器留白定位。
- 单条头像与实际内容同中心；多条头像受组首、组末内容中心与可见窗口约束。统一隐藏所有原头像容器，只绘制一份实际头像图片，触摸坐标映射回原容器。自己的 senderUid 缺失时按 isSelf/senderUin 识别，待发送 msgId=0 时按组首对象身份分组，不再跳过。
- 修复 `tools/dexq.py`：direct_methods/virtual_methods 两个差分索引流须分别重置；旧解析把后一组索引错累加，导致方法错属和越界。清除 AGENTS.md 中将此归咎于 QQ 畸形 class_data 的错误说明，并新增两项离线解析回归。
- 本地 Kotlin 类型检查和 10 项测试通过，解析器 2 项测试通过；真 QQ 9.2.10 核验 192 条符号、0 不匹配。视觉、滚动和点击效果仍未重新验收，不记为已修好。

## 2026-10-15 — TG 连发头像浮动与昵称合并

- 替换仅隐藏后续头像的实现：每个消息列表加一层 Drawable，直接绘制 QQ 原头像，头像贴同一消息组可见底部；不搬动原视图、不改父容器裁剪、不复制头像位图。
- 昵称只留组首；相邻同发送者五分钟内合组，跨日、倒序、系统消息断组。去掉进程级消息 id 集合，避免跨列表残留。
- 绑定与局部更新前恢复旧状态；按实际组件过滤共用基类钩子；列表离屏、开关关闭、异步绑定和复用均有还原路径。头像点击转给原头像，超过触摸阈值取消点击并交列表滚动。
- 真 QQ 9.2.10（389611416B，SHA256 前缀 34bdea66e738）离线核验：183 条符号、0 不匹配；本地类型检查通过，8 项纯逻辑测试通过（含连发断组、头像可见边界）。未连接设备，滚动、长按、点击与视觉效果未验证。

## 2026-10-15

- 依据 selfs `a1148c42f8da21a9a28e064030c596bd02cf45af`：运行记录上限优先，每工作流含当天最多 5 条且不因关联产物豁免。先计算两份候选清单，再把超限运行承载的候选 artifact 转入删除清单，先删产物后删运行。
- 回归改验“第 6 条运行承载候选保留包”会先删包再删运行，不再以关联冲突阻塞；产物删除失败仍禁止级联删除。

## 2026-10-15

- 依据 selfs `f4091e3e7d91a650c605ab8c71e1121bd3f2de3a`：artifact 改为全仓按 created_at 保留最新 5 个，运行记录改为每工作流按 run_started_at（缺失用 created_at）保留最新 5 条，承载与未完成运行计入名额。
- 先打印产物及运行完整计划、先删产物再删运行；超限未完成运行先取消并等待结束，复查新产物后删除。保留产物与运行上限冲突时明确失败，不误删保留产物。
- 删除失败返回真实结果；403 不再声称清理成功，产物删除失败时禁止级联删除其运行。
- 离线回归 5 项通过：全仓产物与严格运行上限、四项无效产物判据、403/500 禁止级联删除、保留冲突停止、取消后删除；本地真实扫描 3 个 artifact 全部通过号源核验，Build 8 条运行超限 3 条，PR check 5 条超限 0 条（真删由推送后的 CI 执行）。

## 2026-10-15

- 依据 selfs `eb676eb5e03debe035331f267312908ffbc535c1`：取消 artifact 与运行记录的当日豁免，每条序列保留 5 个；保留承载产物和未完成运行保护。
- 清理补逐 artifact head sha 的 git 提交数核验，取不到即失败；同序列重复号按 artifact created_at 删旧留新。
- 删除失效的连续两次 403 升级说明，403 warning，其他错误红；同步工作流注释，移除 AGENTS.md 的全局规则副本。
- 本地全历史干跑：两件 artifact 的总序号 3103、3102 均与对应 head 提交数一致；预计删除两条超量 PR check 运行，尚未算作真删。

## 2026-10-15 — 号源换成 git 提交数；清理判据三改按 run_started_at 删旧留新；换号源过渡清理

- 依据：selfs 分支提交 `c5ac12d`（号源只有 git 提交数一处：总序号 = `git rev-list --count HEAD`，当日序号 = `git rev-list --count --since=<当日0点 UTC+8> HEAD`，`run_number`／事件／文件名／PR 一律不进版本号；硬前提 `actions/checkout` 写 `fetch-depth: 0`、取号脚本启动行打印 head sha 与两个数与命令原文、取不到数失败退出不留降级不退化四段；head 提交不在当日时当日序号取 0；提交数就是号源所以禁空提交与 force push／rebase；同一仓不得并存两种取数口径，新包出成之后再清旧口径产物并记一行真数字）、`240a214`（先出新口径包再清旧口径，不许出现一个可装的包都没有的状态）。
- `.github/workflows/build.yml`：`apk` 作业 `actions/checkout` 补 `fetch-depth: 0`（缺了提交数恒为 1）；取号段整体重写 —— 删掉 `run_number` 取数、当日运行位次、退化四段与「重试复用第一次的号」缓存三步，换成 git 提交数 + 浅克隆硬失败 + 启动行打印 head sha／两数与命令原文，取不到数即 `exit 1`；头注释与 `SEGMENTS` 注释同步改口径；`cleanup-artifacts` 作业 checkout 也补 `fetch-depth: 0`（清理脚本要打两条取号命令的输出）。
- `.github/workflows/pr-check.yml`：`cleanup` 作业 checkout 补 `fetch-depth: 0`。
- `.github/scripts/cleanup.py`：启动打印补「删除失败分级口径」与取号核对（head sha、总序号、当日序号、浅克隆标志、两条命令原文）；判据三由「保留总序号最大者」改为「按 `run_started_at` 删旧留新」，并把两个 artifact 的名与 id、两次运行的 rn 打进日志；新增换号源过渡清理（旧口径两只，守卫＝已存在总序号 ≥ 3000 的新口径产物，清完即从脚本删掉本段）。
- 实测真数字（取值 2026-10-15T00:13Z）：本仓 `arena/e207d38d-qself` head `8d90c39` 两条命令输出 —— 总序号 3101、当日序号 0（该提交日期 2026-10-14 22:00 CST，不在当日）；`origin/main` 总提交数 3069。旧口径产物 `Qself-26.10.14.4.277`（id 11900691515，rn=277，事件 `push`）与 `Qself-26.10.14.3.276`（id 11900660871，rn=276，事件 `push`）。
- 会话令牌 DELETE artifact 实测 403（`Resource not accessible by integration`，用不存在的 id 探测端点 `repos/Sumicya/Qself/actions/artifacts/1`），旧口径产物只能由 CI 清理 job 清，故过渡清理写进同一份脚本。
- 出包实测：Build rn=278（head `c5a7877c`，事件 `push`）四作业 `dex`／`apk`／`cleanup-artifacts` success、`cleanup-releases` skipped；产出新口径首包 `Qself-26.10.15.1.3102`（id 11937962375，114728B，run 38659018059）；同一次运行的 `cleanup-artifacts` 按过渡清理把旧口径两只清掉（`.4.277` id 11900691515、`.3.276` id 11900660871，按 id 复核均 404，取值 2026-10-15T00:19:13Z）；删后 artifact 名单只剩 `Qself-26.10.15.1.3102`。PR check（同 head，run 38659024806）`dex`／`test`／`cleanup` 全 success。
- 过渡清理段清完即从 `cleanup.py` 删除（连同只服务它的 `parts_of()`），产物名与守卫条件留在本文件与 `AGENTS.md` 里可核。

## 2026-10-14 — 会话分支出包首次实测；清理按重复当日序号删旧产物；修正已知风险条

- 实测（提交 `dd136e0` 推送后）：Build rn=276（事件 `push`，head `dd136e0`，分支 `arena/e207d38d-qself`）四作业 `dex`/`apk`/`cleanup-artifacts` success、`cleanup-releases` skipped（非默认分支，按其守卫只拦发行对象）；产出 `Qself-26.10.14.3.276`（id 11900660871）—— 会话分支推送出包这条验证路径首次跑通。PR 校验 run 38576144413（同 head）`dex`/`test`/`cleanup` 全 success。
- 清理实证：`cleanup-artifacts` 按「重复当日序号保留总序号最大者」删掉 `Qself-26.10.14.3.274`（id 11864292225，rn=274 的产物，旧算法下当日序号也是 3）；删除后名单（取值 2026-10-14T13:57Z）只剩 `.3.276`。分界点已记入 `AGENTS.md`。
- `AGENTS.md`：修正已知风险条 —— 默认分支实查是 `on: push / pull_request / workflow_dispatch`（`push` 不限定分支、工作流清单只有 `build.yml`），上一版写的「`branches: [main]` 限定」与实查不符，按命令输出改正。

## 2026-10-14 — 会话分支必须能出包（去掉分支限定）；清理失败按 403 与非 403 分口径

- 依据：selfs 分支提交 `a8787a8`（撤回「会话分支不出包」与 `github.ref` 守卫两条，`push` 不限定分支，会话分支推送同样出包用于验证，与默认分支共用同一命名口径与同一条 `run_number` 序列；新增硬口径「写『不许做某事』前先问是不是唯一验证手段」）、`358120a`（清理失败按原因分：403 打 `::warning::`、同一对象连续两次失败才升级阻塞项、403 以外当轮红 job 并点名对象与返回码；先进性节的降级范围收窄为「只允许清理动作的 403 权限缺口」）、`e6c9e8c`（分支去除需差异对比加主人确认；默认授权、责任固定、结尾句式三处开出例外）。
- `.github/workflows/build.yml`：`on.push` 去掉 `branches: [main]` 限定 —— 会话分支推送同样出包，这是唯一的真包验证路径；头注释改新口径（不得接 `pull_request`、不得加 `branches` 限定或 `github.ref` 守卫）；当日序号注释补「会话分支运行一并计入」；`cleanup-releases` 的 `if` 注释写明它只拦在会话分支上动发行对象、不砍出包验证。
- `.github/scripts/cleanup.py`：`delete()` 按原因分口径 —— DELETE 403 打 `::warning::`（写明对象、端点与返回码）后继续；403 以外的失败进 `FAILED`，脚本末尾 `::error::` 逐条点名并 `SystemExit` 红 job。合成测试三条：403 → `FAILED=[]`；500 → `FAILED` 一条；成功 → 无警告无失败。
- `AGENTS.md`：触发条、分支条改写；清理失败分口径按 `a8787a8` 写清；已知风险条补「默认分支仍是旧口径（含 `pull_request` + `branches: [main]`）」；分支去除条改为 `ahead_by=0` 默认授权当轮删、`ahead_by>0` 需取 compare 差异并附可取处/可去处与建议、向主人确认后才删。

## 2026-10-14 — 真删挂到 PR 校验工作流，清理不再永远跑不到

- 依据：selfs 分支提交 `b0594d3`（规范 22260B）：真删只挂出包工作流时，会话支推不上默认分支、`workflow_dispatch` 要 `actions` 写（实测 403）、PR 校验又只读预演 —— 三仓真删一次没触发过（实证 Gself `.4.145`/`.2.147`、Selffont `116`/`117`/`118` 从 03:35Z 挂到 07:11Z）。新条：真删调用点至少一个必须会话可达＝PR 校验工作流清理作业 `actions: write` + `CLEANUP_APPLY: 'true'`；两处同一 `concurrency` group 串行；DELETE 403 打 `::warning::` 降级为预演并继续；只读预演为备用形态；超量对象状态改记「待下一次 PR 校验运行」。
- `.github/workflows/pr-check.yml`：顶部 `env` 的 `CLEANUP_APPLY` `'false'`→`'true'`；清理作业 `cleanup-preview`→`cleanup`、权限 `actions: read`→`actions: write`、步骤名改「按四条判据清理（真删）」、加 `concurrency: {group: qself-cleanup-artifacts, cancel-in-progress: false}`（与 `build.yml` 同组）；头注释与作业注释同步。
- `.github/scripts/cleanup.py`：`emit()` 换成 `delete()` —— `DELETE` 失败不再 `SystemExit`（原实现遇非零退出直接把 job 弄红），改为打 `::warning::` 写明原因并继续；`APPLY=false` 仍只打印、不发请求。
- `.github/workflows/build.yml`：三处与新条文矛盾的注释改口径（顶部第 8–9 行、「`CLEANUP_APPLY`」行内注释、清理作业注释里的「真删只此一处」）。
- `AGENTS.md`：CI 分工条与清理条按新条文改写（`pr-check.yml` 第三作业 `cleanup-preview`→`cleanup`；删掉「真删只此一处」与「待出包工作流首次运行」；记两处同组串行与 fork/403 降级行为）。

## 2026-10-14 — 清理脚本启动打印读到的参数；AGENTS 记号源已知风险

- 依据：selfs 分支提交 `62df936`（清理脚本启动时打印读到的 `SEGMENTS`、`CLEANUP_APPLY`、产物名前缀、保留数与本次 run 所属序列，让预演日志能独立核对，不靠「job success 所以顶部声明生效」推断）、`4e9effb`（自检第 4 项拆词面 + 机制五场景，第 5 项按四条判据扫描并复查上轮阻塞项；阻塞项收尾只许三种状态）。
- `.github/scripts/cleanup.py`：`VERB` 行后加启动参数打印（一行，含 SEGMENTS / CLEANUP_APPLY / PREFIX / KEEP / SELF / TODAY / REPO）；拉回 run 列表后加「本次 run 所属序列」打印（取不到写「未出现在本次拉取的运行列表里」，不静默）。
- 回归干跑（合成数据，与上轮同一份）：启动打印逐项出值；本次 run 210 判为 `.github/workflows/pr-check.yml`；四条判据命中数与上轮一致（口径 1、来源 1、重复当日序号 1、数量 2）；`APPLY=true` 时 DELETE 数 10、`false` 时 0。
- `AGENTS.md`：分界点条后加「已知风险（号源）」—— 默认分支 `.github/workflows/build.yml` 仍含 `pull_request` 触发（实查 main 现文 `on: push / pull_request / workflow_dispatch`），载体是开放 PR #10（head `arena/01a109fa-qself`）；风险存续到本次交付进入默认分支为止，不报「号源已收口」。

## 2026-10-14 — 分界点改记可核对象；清理开关与段数提到工作流顶部声明

- 依据：selfs 分支提交 `bc98bd5`（分界点只引可核对象：run 号、事件、head sha、日期，加删除前 API 名单原文与取值时间；已删 artifact 的名字先按 run 记录核）、`215a796`（回收两条：`run_started_at` 缺失才退回 `created_at`、未完成的 run 不删）、`5dc0d28`（检讨条款整条删除）。
- `AGENTS.md`：分界点那条按 run 记录逐条重核后重写 —— 重复当日序号 6 的是 `rn=272`（事件 `push`）与 `rn=273`（事件 `pull_request`，同 sha 7920b0a2），不是 rn=273/275；`rn=275`（事件 `pull_request`）取号、算版本、测试与出包、上传步骤全 skipped、按 run 反查 artifact 数=0，`.6.275` 从未存在；删除前名单（取值 2026-10-14T03:17Z）`.7.271`、`.6.272`、`.6.273`、`.3.274` 四条，前三条已删，只剩 `.3.274`。同条补上「排序字段缺失才退回 `created_at`、未完成的 run 不删」两条口径。
- `build.yml`：`env` 顶部加 `CLEANUP_APPLY: 'true'`（真删开关），与 `SEGMENTS: '5'` 并排；清理步骤 env 只留 `GH_TOKEN`/`REPO`/`SELF`。`pr-check.yml`：顶部 `env` 加 `SEGMENTS: '5'` 与 `CLEANUP_APPLY: 'false'`（只预演）。两处调用的仍是同一份 `.github/scripts/cleanup.py`，脚本未动。

## 2026-10-14 — 清理判据补齐四条：单一脚本 + PR 校验只读预演，超量对象不再卡在「看不见」

- 依据：selfs 分支提交 `3d7be5b`（清理作业补三条：清理逻辑只写一份脚本，靠 `CLEANUP_APPLY` 开关分出包真删与 PR 只读预演；新增判据「重复当日序号」；run 上限与 artifact 承载解耦——承载保留产物的 run 不删也不计入上限，排序按 `run_started_at`、`id` 只作同刻 tiebreak，日志逐对象打印判据与豁免原因、话术不得与实际分支矛盾）。
- `build.yml`：头注释旧保留数写法（「artifact（5 个）与运行记录（5 条）」）改成规范话术（当日之外每条序列 5、承载不计数、当日豁免）；清理作业从内联脚本改为 checkout + 调 `.github/scripts/cleanup.py`，`CLEANUP_APPLY: 'true'`，权限 `actions: write` + `contents: read`。
- `pr-check.yml`：新增 `cleanup-preview` 作业（`actions: read` + `CLEANUP_APPLY: 'false'`），同一份脚本只打印将删名单与判据理由。死锁解开：会话支跑得到预演，真删仍只在出包工作流一处。
- 预演（live，脚本同一份）：将删 0 个 artifact、0 条 run——artifact 1 个走「当日豁免」，run 12 条里 5 条当日豁免 + 1 条「承载保留产物：不删、不计入上限」+ 7 条 pr-check 当日豁免；逐对象理由与实际分支一致（旧版那句「删除 0 条（全部为当日之外、且不承载保留产物）」的错话术已不存在）。
- 干跑（合成数据，含重复当日序号与承载 run）：将删 5 个 artifact（口径 1、来源 1、重复当日序号 1、数量 2）、5 条 run；`APPLY=true` 时正好发出这 10 条 DELETE，`APPLY=false` 时 0 条；`SEGMENTS=4` 边界按退化四段口径判。
- 文档：`AGENTS.md` 清理条目补四条判据、单一脚本与预演，并记 2026-10-14 当日序号 6 重复的分界点。

## 2026-10-14 — 清理作业按规范三条判据重写（数量 / 命名口径 / 来源）

- 依据：selfs 分支提交 `2dec668`（清理判据三条：数量=当日之外每条序列 artifact 5 个、run 5 条；命名口径=段数等于工作流顶部 `SEGMENTS` 声明值；来源=artifact 的 `workflow_run` 事件是 `pull_request` 一律删。
  数量判据当日豁免（当日序号取自当日运行列表，删当日运行会抽走计数基准），口径与来源不豁免、当轮即删；PR 校验工作流不挂清理作业，它的超量 run 由出包工作流清理 job 一并删；job 跑完打印删了哪几个、各剩几条）。
- `build.yml`：顶部新增 `env: SEGMENTS: '5'`；`cleanup-artifacts` 重写为单步脚本，仍最小 `actions: write`；承载保留产物的 run 不动（删 run 会连带删产物），正在跑的本次不删。
- 干跑（DELETE 全拦截至 stderr，脚本从工作流原文抽出）：live 数据 → 删 0 个 artifact、0 条 run（唯一 artifact 是当日豁免、run 全为当日），与实况一致；
  合成混合数据（7 个旧出包产物 / 7 条旧出包 run / 7 条旧 PR 校验 run / 1 个四段错口径 / 1 个 PR 来源）→ 三判据各命中：删 4 个 artifact（口径 1 个打「段数 4 ≠ 5」、来源 1 个打 `pull_request`、数量 2 个），保留 5 个；删 4 条 run（build 与 pr-check 各超 2 条），剩 build 6 条 / pr-check 5 条（各留 5 + 当日豁免与在跑的本次）；
  `SEGMENTS=4` 边界 → 5 段产物全按口径判据删。干跑抓到一个 bug：清冗余三元时把数量判据的保留/删除分支改坏（超量对象全进保留桶、一个不删），已改成显式分支并重跑三组通过。
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
