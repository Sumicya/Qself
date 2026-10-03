# Qself

给 NT QQ 的一个 LSPosed 模块：装上即全部生效，不联网、没有第三方库、没有一个 `.so`。
开关在底栏玻璃圆钮的玻璃卡片面板里：一行两张卡、每张带一句描述，默认全开，✓/–/✗ 当场可见；
藏页签、隐藏在线状态这类纯视图规则没有开关，装了就生效。
只对一台机器写：QQ 9.2.10 · Android 16 · LSPosed 2.x（libxposed API 102）。

落点（类名、方法名）全部写死，但每一个都在 CI 里拿真 dex 逐条核过
（[`tools/symbols.txt`](tools/symbols.txt)，167 条）——换版本先看那张表红哪条，别猜。

## 做了什么

- 外观：首页底栏浮成一颗居中的液态玻璃胶囊，完全照 KernelSU 管理器的配方
  （背板取样 → 饱和 1.5 → 模糊 4dp → 边缘 24dp 透镜折射，表面 40% 表面色 + 1dp 高光细线；
  配方源自 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，Apache 2.0）。
  按压是「充气」：整颗胶囊放大 5%、指下页签放大 1.2 倍、选中亮胶囊（跟页签同宽）放大 1.39 倍，松手弹回；
  选中项平时是一块弹着滑过去的亮胶囊。Monet 取色（系统动态色，可关）。
  输入栏玻璃化（只换材质，不动 QQ 的输入控件）。藏「频道」「动态」「小世界」页签；
  清理标题栏、侧栏的运营入口；会员装饰归零；红点引导、在线状态、轻互动与表情雨一律屏蔽
- 聊天：防撤回（吞掉撤回推送，被撤回那条压成半透明、右上角画 ✗，标记重启还在）；
  连发合并（成组不重复读头像昵称）；重复项合并（连着发的相同文字，第一条之后收起）；
  每条消息挂「序号 · 时间」灰色小标签，格式长按开关可改；回复不 @；左滑回复不设限；转发页常驻多选
- 自由化：网页走系统 WebView；统计上报（灯塔 / StatisticCollector）空转；不初始化崩溃上报

防撤回、「消息 ID 和时间」那批的思路来自上游 [QAuxiliary](https://github.com/cinit/QAuxiliary)，落点按 9.2.10 的真 dex 重新核过。
外观参照 [NagramXF](https://github.com/Keeperorowner/NagramXF) 的长相。

## 装

前置：root、LSPosed 2.x。Termux 里：

1. 取最新构建。正式包只在 CI 的 Artifacts 里，去 Actions 页面最近一次成功的 run 下载；
   装过 `gh` 的话一条命令就够（版本 = 年.月.日.CI 序号，见文件名）：

   ```sh
   gh run download -R Sumicya/Qself "$(gh run list -R Sumicya/Qself -L1 --status success --json databaseId --jq '.[0].databaseId')"
   ```

   解出来是 `Qself-<版本>/qself-<版本>.apk`，如 `Qself-26.10.3.168/qself-26.10.3.168.apk`。
   分支构建也在同一份 Artifacts 列表里，别拿错。

2. 安装。Termux 主目录系统读不到，先挪到 `/data/local/tmp`（root），装完连临时文件一起删；
   没有 root 就 `termux-open` 手动装：

   ```sh
   su -c 'mv /data/data/com.termux/files/home/Qself-*/qself-*.apk /data/local/tmp/qself.apk'
   su -c 'pm install -r /data/local/tmp/qself.apk'
   su -c 'rm /data/local/tmp/qself.apk'
   ```

3. LSPosed 里启用 Qself（作用域已写死为 QQ），强行停止 QQ 再打开。
   Termux 自带的 `am` 是残缺版，用 `su -c 'am force-stop com.tencent.mobileqq'`。
   之后换包直接覆盖安装，签名固定在 `app/qself.p12`，LSPosed 会热重载。

## 查错

每个 QQ 进程启动时打一行日志，`su -c 'logcat -d -s Qself'` 或 LSPosed 管理器的日志页可见，形如：

```
Qself 26.10.3.168 @ com.tencent.mobileqq ✓防撤回 ✓玻璃底栏 … ✗某功能(NoSuchMethodException: …)
```

✗ 表示该功能在这版 QQ 里找不到落点，其余不受影响。**把这一行贴回来就能修。**

改这个仓库看 [`AGENTS.md`](AGENTS.md)：八个文件各管什么、落点表怎么核、CI 怎么出包。

## 许可

GPL-3.0-or-later。源自 [QAuxiliary](https://github.com/cinit/QAuxiliary)，只留了思路，代码全部重写。
