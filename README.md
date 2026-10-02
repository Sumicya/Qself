# Qself

给 NT QQ 的一个 LSPosed 模块：装上即全部生效，不联网、没有第三方库、没有一个 `.so`。
开关只有底栏玻璃圆钮里那 4 个（玻璃底栏 / Monet 取色 / TG 输入栏 / 防撤回），其余不配开关。
只对一台机器写：QQ 9.2.10 · Android 16 · LSPosed 2.x（libxposed API 102）。

落点（类名、方法名）全部写死，但每一个都在 CI 里拿真 dex 逐条核过
（[`tools/symbols.txt`](tools/symbols.txt)，167 条）——换版本先看那张表红哪条，别猜。

## 做了什么

- 外观：首页底栏浮成一颗居中的液态玻璃胶囊，完全照 KernelSU 管理器的配方
  （背板取样 → 饱和 1.5 → 模糊 4dp → 边缘 24dp 透镜折射，表面 40% 表面色 + 1dp 高光细线；
  配方源自 Kyant0/AndroidLiquidGlass，Apache 2.0）。按压是「充气」：整颗胶囊放大 5%、
  指下页签放大 1.2 倍、选中亮胶囊（跟页签同宽）放大 1.39 倍，松手弹回；
  选中项平时是一块弹着滑过去的亮胶囊。Monet 取色（系统动态色，可关）。
  输入栏玻璃化（只换材质，不动 QQ 的输入控件）。藏「频道」「动态」「小世界」页签；
  清理标题栏、侧栏的运营入口；会员装饰归零、昵称只留名字；红点引导、在线状态、轻互动与表情雨一律屏蔽
- 聊天：防撤回（吞掉撤回推送，被撤回那条压成半透明、右上角画 ✗，标记重启还在）；
  连发合并（成组不重复读头像昵称）；回复不 @；左滑回复不设限；转发页常驻多选
- 自由化：网页走系统 WebView；统计上报（灯塔 / StatisticCollector）空转；不初始化崩溃上报

防撤回那批的思路来自上游 [QAuxiliary](https://github.com/cinit/QAuxiliary)，落点按 9.2.10 的真 dex 重新核过。
外观参照 [NagramXF](https://github.com/Keeperorowner/NagramXF) 的长相。

## 装

前置：root、LSPosed 2.x。Termux 里：

1. 取最新构建。永远挂在 release `latest` 上，固定入口 `qself.apk`
   （旁边还有一份同内容的版本化副本，如 `qself-26.10.2.156.apk`；版本 = 年.月.日.CI 序号）：

   ```sh
   curl -L -o qself.apk https://github.com/Sumicya/Qself/releases/download/latest/qself.apk
   ```

2. 安装。Termux 主目录系统读不到，先挪到 `/data/local/tmp`（root），装完连临时文件一起删；
   没有 root 就 `termux-open qself.apk` 手动装：

   ```sh
   su -c 'mv /data/data/com.termux/files/home/qself.apk /data/local/tmp/qself.apk'
   su -c 'pm install -r /data/local/tmp/qself.apk'
   su -c 'rm /data/local/tmp/qself.apk'
   ```

3. LSPosed 里启用 Qself（作用域已写死为 QQ），强行停止 QQ 再打开。
   Termux 自带的 `am` 是残缺版，用 `su -c 'am force-stop com.tencent.mobileqq'`。
   之后换包直接覆盖安装，签名固定在 `app/qself.p12`，LSPosed 会热重载。

## 查错

每个 QQ 进程启动时打一行日志，`su -c 'logcat -d -s Qself'` 或 LSPosed 管理器的日志页可见，形如：

```
Qself 26.10.2.140 @ com.tencent.mobileqq ✓防撤回 ✓玻璃底栏 … ✗某功能(NoSuchMethodException: …)
```

✗ 表示该功能在这版 QQ 里找不到落点，其余不受影响。**把这一行贴回来就能修。**

## 结构

```
app/src/main/kotlin/sumicya/qself/
  Qself.kt   入口：功能清单、4 个开关、按进程装、日志
  Hook.kt    libxposed 封装（hook / constant / afterNew / 反射读写字段方法）与开关读取
  Knob.kt    主页那颗圆钮与开关面板（系统对话框）
  Quiet.kt   自由化
  Chat.kt    聊天：防撤回（含几十行 protobuf 读取与撤回标记）、连发合并、回复不 @
  Looks.kt   外观：盯住每个 Activity 的视图树套规则
  Input.kt   输入栏玻璃化（只换材质）
  Glass.kt   液态玻璃 Drawable（背板取样 + AGSL 透镜折射 + Monet 取色，底栏用时自带滑动亮胶囊）
app/src/test/kotlin/sumicya/qself/ProtoTest.kt   防撤回判断的唯一一份可跑检查
app/src/main/resources/META-INF/xposed/          libxposed 入口、module.prop、作用域
tools/symbols.txt    落点表：代码依赖的每一个名字
tools/dexcheck.py    拿真 dex 逐条核落点，顺带 lint 源码里没登记的名字
tools/dexq.py        最小反汇编器：按签名找落点
ci/check.sh          无 SDK 时的类型检查 + 跑 ProtoTest
```

## 核落点

```sh
git clone --depth 1 https://github.com/Sumicya/qqapk q && cat q/qq.a* > qq.apk && rm -rf q
python3 tools/dexcheck.py --apk qq.apk --lint app/src/main/kotlin     # 167 条全绿才算数
```

## 许可

GPL-3.0-or-later。源自 [QAuxiliary](https://github.com/cinit/QAuxiliary)，只留了思路，代码全部重写。
