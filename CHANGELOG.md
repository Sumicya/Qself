# CHANGELOG

## 2026-10-31（二十五）— 图标换成自适应：加 monochrome，ColorOS 圆环里不再有方形硬边

- 原来只有一枚 `res/drawable/icon.xml`（108dp 矢量，深底 + 描边放大镜），manifest 直接拿它当 `android:icon`。
  这种写法在桌面会被塞进一个方形／圆角方形里，各家启动器的形状和缩放都不归我们管。
- 现在拆成三层：`ic_launcher_background.xml`（108dp 满屏 `#0F1115`）、`ic_launcher_foreground.xml`
  （放大镜绕 (54,54) 缩 0.62，描边改纯白）、`mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml`
  （background / foreground / monochrome 三层都指向前两个 vector，monochrome 用前景）。
  manifest 改 `@mipmap/ic_launcher` 并补 `android:roundIcon="@mipmap/ic_launcher_round"`，旧 `drawable/icon.xml` 删除。
- 前景安全区是算过的，不是手感：镜圈外缘 16.74dp + 半描边 2.79 = 19.53dp，手柄末端 (71.36,71.36) 离中心
  24.55 + 2.79 = 27.34dp，都在自适应图标 66dp 安全圆（半径 33dp）以内，圆形／水滴／圆角方形怎么裁都切不到镜身。
- 只有 `anydpi-v26` 一份：`minSdk = 36`，没有 API 26 以下的设备要兜底。主题图标（ColorOS 的图标换肤）平台接口
  不开放，不做，也不写进 README 当卖点。

## 2026-10-31（二十四）— 风格契约收敛：间距收到 4dp 栅格、颜色只留中性两档、圆角去掉中间值

- 间距：`N * dp` 里不是 4 的倍数、且大于 3dp 的字面量，全部就近收到 4 的倍数（正中时向上：5→4、6→8、7→8、
  10→12、14→16、18→20、22→24、38→40）。≤3dp 的 1/2/3 是描边与像素对齐，19 次一律不动。只改数字，不改结构。
  读数（同一命令）：`grep -rhoE "[0-9]+ \* dp" app/src/main/kotlin | awk '{v=$1; if (v+0>3 && v%4!=0) n++} END{print n+0}'`
  → 改前 `73`、改后 `0`；分母：30 个 dp 常量、156 次出现，其中非 4 倍数 92 次（含 19 次豁免）。
  验收命令 `grep -rnoE "[0-9]+ \* dp" app/src/main/kotlin | awk -F: '{print $3}' | sort -u` 改后只剩
  1/2/3 与 4 的倍数。
- 颜色：`Color.WHITE` 2 处（`KernelBar.kt:328` 软件画布的兜底填充、`:416` 底栏文字的中性染色）改为仓内已有的
  中性亮档 `0xFFF5F6FA`，暗档沿用 `0xFF1C1C1E`／`0xFF242424`，没有引入任何新颜色。读数：
  `grep -rn "Color.WHITE" app/src/main/kotlin | wc -l` → 改前 `2`、改后 `0`；全仓颜色字面量集合
  （排除 `0x7f…` 资源 id）改前改后都是 `58` 个、逐个相同。另三处 `#RRGGBB` 落在注释里，是记录 QQ 原生
  顶栏与父容器底色的事实（`#25272d`、`#000000`、`#1b1c1e`，都带资源 id），不是我们的硬编码，未动。
- 圆角：`Knob.kt` 设置面板卡片的 `val radius = 24 * dp`（原 22dp，被间距那条先收到 24）改成 `0f` 直角，
  `outlineProvider` 同步 `capsule(0f)`。契约只留「全圆」与「零」；这张卡在小屏上占大半屏、内容是滚动列表，
  全圆会把它变成胶囊，所以取零（要改回全圆，把两个 `0f` 换成 `Float.MAX_VALUE` 即可）。读数：
  `grep -rn "val radius = " app/src/main/kotlin | wc -l` → 改前 `2`（面板卡片 22dp、头像圆角函数），改后 `1`
  （只剩 `avatarCorner(size, percent)`；头像圆角是用户可调功能，不动）。
  半径全量清单（`grep -rnoE "Glass\([a-zA-Z]+, *[^,)]+|cornerRadius = [^,)]+" app/src/main/kotlin`
  → 11 行）：0（`Knob.kt:614`，本轮）、`Float.MAX_VALUE` 全圆 2 处（`Input.kt:766` 等）、12dp 两处
  （`Knob.kt:295/471`）、8dp（`Knob.kt:526` 菜单项四角）、`cornerRadius` 动态赋值的两处（`Input.kt:694/707`，
  值来自 `capsule()` 即全圆）。另有 4 处中间值**不在任务点名的两处之内，本轮按「不许顺手改别的」未动**：
  顶栏三枚胶囊 `Looks.kt:1399–1401` 的 `23 * host.dp`、搜索根视图 `Input.kt:59` 的 `20 * root.dp`
  （注意 23dp 也不满足间距那条，因为它写的是 `host.dp` 不是 `dp`，验收正则抓不到）。下一轮要不要收，主人定。
  气泡圆角 17dp 与头像圆角同属用户可调项（滑条 0–32），不属这一类。
- 更正一处点数：任务里点名的 `Looks.kt:2128` 的 `22 * dp` 不是圆角，是标题 `TextView` 的最小高度
  （`th = tv.measuredHeight.coerceAtLeast((24 * dp).roundToInt())`，已由间距那条收到 24dp），本轮不改它的语义。

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

