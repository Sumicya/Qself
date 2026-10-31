# CHANGELOG

## 2026-10-31（二十三）— 连发头像改成 NagramXF 的「底边那条画」：删掉整套浮绘，少 404 行

- 规范（`63ff7f9a`）：新增「零、五级提问」——每轮回复开头先写处境/目标/验收/卡点/取舍五句；新增
  「主人点名『按某个实现做』时必须给该仓 commit、文件路径与行号，并写成可执行规则」；第 9 条补
  `gh run download` 没有 `--clobber`，同一产物重复下到同一目录会 `file exists`，命令要先 `rm -rf` 再 `mkdir -p`。
- 依据（实取，非转述）：`Keeperorowner/NagramXF@d170a0e30e52d0d6486ccc6adc23143ed4c12fec`（`dev`，2026-10-27 13:15 UTC+8），
  `TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java:7081`：
  `isAvatarVisible = needDrawAvatar() && (currentPosition == null || currentPosition.edge);`
  其中 `edge` 定义在同仓 `.../messenger/MessageObject.java:1254`（`public boolean edge;`），只在 1436／1722／1729
  三处置 `true`，即「这一格位于组的底边」。翻译成一条可执行规则：**多格块里只有底边那一格画头像**；
  对应到 QQ 的连发组就是「只有组内最后一条保留原生头像，其余行藏掉」。
- 代码（`Chat.kt`）：`RunLayout.apply` 的头像分支从「续条一律隐藏」（等于头像钉在组首）改成
  `avatarVisibleInRun(continues, enabled)` —— `enabled && !continues` 才显示。关掉「连发合并」时全量还原。
- 删除（同文件，−404 行）：整套跨行浮绘 `Face`／`sample()`／`draw()`／`touch()`／`commitFrame()`、
  预绘制监听与 `overlay.add`、`dispatchTouchEvent` 转发钩子，以及只为浮绘服务的三个纯函数
  （`floatingAvatarTop`、`runAvatarBounds`、`floatingRunAvatarTop`）；`RunAvatars` 变成一个只登记
  「哪一行还留着原生头像」的 WeakHashMap。`MessageStyle.kt` 两处 `notifyChatAvatarsUpdated()` 随之删除，
  `Perf.kt` 的 `CostMeter` 去掉「头像浮绘」槽（4 槽 → 3 槽），滚动帧不再有任何头像采样。
- 测试：删掉 8 条绑在浮绘几何上的用例，新增 2 条钉住新规则 —— 5 行组只有最后一行可见、关开关全可见
  （`onlyTheLastRowOfARunKeepsTheAvatar`、`turningOffMergeRestoresEveryNativeAvatar`）。80 → 74 条。
- 可复查读数：`tools/check.sh` → `compiled: 136 classes`、`74 tests passed`、`Ran 8 tests in 0.001s`、`OK`；
  `grep -c addOnPreDrawListener Chat.kt` → `0`；`grep -c overlay.add Chat.kt` → `0`；
  `grep -rn notifyChatAvatarsUpdated app/src/main` → 0 条；`git diff --stat` → `5 files changed, 51 insertions(+), 466 deletions(-)`。
- README 三条命令的第 1 条补 `rm -rf "$D"`（先删后建），与第 9 条新句一致。
- 真机未验证（命令与时耗见汇报）：头像落点改用原生视图后，观感要用设备看一次 —— 打开任意群，滑到一组 3 条以上的
  同人连发，`logcat -s Qself | grep TG连发定位` 应出现「头像只留组内最后一条」，约 5 分钟。

## 2026-10-31（二十二）— 装机命令改成手机端三条：下载到固定路径、`su -c pm install`、`rm -rf`

- 起因：上一版 README 第 1 条给的是 `adb install -r apk/*.apk`。主人手上只有手机，`adb` 是电脑上的工具，
  这条命令在他的环境里跑不了 —— 规范 `128e16f9` 第 9 条因此改成「命令必须能在他手上的环境里跑」，并点名
  只给 `adb install` 按未完成处理。
- 新版第 1 条给出三条命令，全程在手机上：① `D=/storage/emulated/0/Download/selfs` ＋ `mkdir -p` ＋
  `gh run download … -D "$D"` 落到固定路径；② `su -c "pm install -r $(find "$D" -name '*.apk' | head -1)"`，
  没 root 就在文件管理里点开那个 `.apk`；③ `rm -rf "$D"`。README 里 `adb` 字样已清零
  （`grep -cE '\badb\b' README.md` → `0`）。
- 验证方式（可复算）：把 README 里那三条命令原样抽出来 `sh -n` → `OK`；用桩 `gh`／`su` 实跑，
  `su` 收到 `-c pm install -r /tmp/selftest2/Qself-26.10.31.7.371/Qself-26.10.31.7.371.apk`，
  跑完 `/tmp/selftest2` 已不存在 —— 说明嵌套引号在 `sh` 下解析成一个参数，`find` 取到的是第一条 apk。
- 保留的两条事实：下载下来的 apk 权限是 `600`（`gh run download` 展开后的实际权限，主人的 `ls -l` 也是这个），
  `su` 的 root 与文件管理都读得到，不用 `chmod`；最新产物 `Qself-26.10.31.7.371` 为 `452944` 字节，
  已写进 README 当对照值。

## 2026-10-31（二十一）— 按规范 `0ab98575` 收口：装机改成一条命令，CI 读数写进仓里

- 规范变动（三处，本轮已按新口径执行）：① 收尾的「四要领」改为两项必核——「外部指标」（数值＋取值命令）与
  「结论变更」（被推翻的改前值→改后值＋触发证据，无则写「无」）；② 表达第 7 条加「关键数字必须附命令与原样输出」，
  并规定会被清理的证据（CI 运行、artifact）要把读数抄进仓内永久位置；③ 新增第 9 条「一条龙」：交付要让主人
  一条命令拿到成品，只给脚本或仓库地址算半成品。核读层级只认 `全文 N 行`／`结构级`／`未读` 三种写法，
  锚点必须是方法句或断言句，章节标题按「未读」处理。
- `README.md`「安装与使用」第 1 条：从「去 Actions 下载」改成一条命令取最新产物并装机，且给出这条命令成立的依据——
  `artifacts[0]` 是否最新，用 `gh api …/actions/artifacts --jq '.artifacts[] | .created_at'` 逐条看过，
  再判 `. == (. | sort | reverse)` 为 `true`（2026-10-31 10:22 UTC+8）。
- `README.md`「构建」补一条复现命令：`gh workflow run build.yml -R Sumicya/Qself --ref <分支>`（该工作流有 `workflow_dispatch`）。
- 永久读数（artifact 会被 `cleanup.py` 删到只剩 5 个，所以写在这里）：Build 运行 `41159485047`、run_number `371`、
  `created == run_started_at == 2026-10-31T02:19:40Z`（10:19:40 UTC+8）、产物 `Qself-26.10.31.7.371` / `452944` B /
  artifact id `13026816242` / `expired=false`；同提交 PR check `41159490780` success。
- 未验证（带命令）：本沙箱取不到产物，`curl https://productionresultssa13.blob.core.windows.net/` 返回 `000`，
  `gh run download` 两次都是 EOF——这台机器访问不了 Azure Blob，跟仓库无关。在你机器上跑 README 第 1 条那条命令即可验证，约 1 分钟。

## 2026-10-31（二十）— 第二个未登记开关：`快捷栏隐藏` 把输入区钉在「快捷栏已收起」分支

- 事实（`Input.kt` 872）：`shortcutHidden = on("快捷栏隐藏") || strip 为 null || 不可见 || 高度 ≤0`。这个键从
  2026-10-22 `4bf38dad` 起就在读，但 `features` 表里从来没有它；`on()` 对未登记键取默认 true，于是
  `shortcutHidden` 恒为 true —— 快捷栏可见时也不会走「按 QQ 原始内边距」那一支。
- 处置（不猜真机）：键名改成说清行为的「输入区按导航栏留白」并登记进 `features`（默认开），
  `featureGroups` 的「输入与面板」补位；默认值等于改前行为，**真机上的底部留白一个像素都没动**。
- 防护（`Hook.kt`）：`on()` 读到未登记的名字时，每个进程只写一行 `开关未登记 : 名字（清单里没有，按默认开启）`。
  这类错以前只能靠肉眼看代码，现在会在 logcat 里自己露头；本轮扫完全仓 `on("…")` 的 17 个键，未登记 0。
- 自证：`tools/check.sh` 编译 137 个类、80 条测试全过、`Ran 8 tests OK`；脚本解析 `Qself.kt` 得
  features 33 条、分组 33 条、未分组 0、组里多余 0、重复 0。
- 未做的判断（带命令）：快捷栏可见时该用哪种留白要真机对比 —— 装这一版，聊天页让快捷栏可见，
  关掉该开关后看输入区底边与快捷栏的间距，10 分钟内可判；我没动那一支，避免无图改外观。

## 2026-10-31（十九）— 「页面过渡动效」接回开关表：五个判定点以前读的是一个永远为真的键

- 事实（`Looks.kt` 3177／3186／3363／3388／3408）：进入动效的五处判定都写 `if (!on("页面过渡动效")) return`，
  但 `features` 表里从来没有登记这个名字；`on()` 对未登记的键按 `featureDefaults[name] ?: true` 取值，
  恒为 true。`featureGroupGaps()` 只查「表里有、组里没有」，查不出「代码在读、表里没有」。结果：动效一直开着，
  用户没有任何开关能关它，日志里也看不出来。
- 改动（`Qself.kt`）：`features` 加 `Feature("页面过渡动效", true, "页签、会话、群聊切页时整页展开一次，关掉就直出", {})`，
  默认开，装法 `{}`（与「玻璃底栏」等同属「按名字查开关」的视图规则）；`featureGroups` 的「特效与上报」
  补同名条目，免得安装时被 `featureGroupGaps()` 记一条「开关没进任何分组」。
- 默认值与改前一致，默认行为不变；关掉时 `pageEnter`／`playEnter` 直接返回，页签、会话、群聊三条钩子
  都不播展开，预测性返回包装件也不安装。
- 自证：`tools/check.sh` 编译 137 个类、80 条测试全过、`Ran 8 tests OK`。开关观感（关掉是否直出、
  打开是否只播一次）需真机，本轮**未验证**。
- 收尾：CHANGELOG 只留 5 条，`2026-10-30（十四）` 原样移入 `bad/CHANGELOG-history.md` 开头。

