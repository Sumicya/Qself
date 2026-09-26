# Qself

给 NT QQ 的一个 LSPosed 模块：**自由、简单、现代、原生**。装上即全部生效，没有网络；
开关不在模块里，在 QQ 主页右下角一颗玻璃圆钮里（系统对话框的多选框，存进 QQ 自己的 SharedPreferences）。

只对着一台机器写：QQ 9.2.10 · Android 16 · LSPosed 2.x（libxposed API 102）。类名、方法名全部写死，
换版本大概率要改 —— 改法见 [AGENTS.md](AGENTS.md)。

## 做了什么

外观
- 首页底栏浮成一颗玻璃胶囊：系统 `RenderEffect` 实时高斯模糊 + 提饱和 + 霜色 + 亮边，选中项是会滑的半透明胶囊
- 藏掉「频道」「动态」页签
- 聊天输入栏 Telegram 化：一行 表情 · 输入框 · 相册 · + · 麦克风⇄发送，原来那条图标带收起
- 聊天标题栏去掉一起听 / 一起看 / 群游戏那排；侧栏去掉打卡、天气、等级、会员、装扮、钱包一类的入口
- 统一气泡、统一字体、去头像挂件；昵称行只留名字（不显示群等级、头衔、成员等级、会员图标）

聊天
- 防撤回（私聊、群聊、重连补发的撤回都吞掉；没有灰字提示）
- 转发页一直显示好友 / 群 / 多选入口
- 「+」面板只留正经附件，一起派对、礼物、直播间之类剔掉
- 屏蔽轻互动特效与表情雨

自由化
- 网页一律走系统 WebView，不加载 X5
- 灯塔 (beacon) 与 StatisticCollector 的统计上报空转
- 不初始化崩溃上报

## 用

1. Actions 里下载最新一次构建的 APK（或 `gh run download -R Sumicya/Qself`），安装
2. LSPosed 里启用 Qself，作用域已写死为 QQ，强行停止 QQ 再打开
3. 主页右下角、底栏上方那颗圆钮：点开勾选即时生效（钩子每次被调用都查开关）；
   已经浮起来的底栏、排好的输入行要点「重启 QQ」才复原
4. 每个 QQ 进程启动时打一行日志，`logcat -s Qself` 或 LSPosed 管理器的日志页可见，形如
   `Qself 0.3.0 @ com.tencent.mobileqq ✓系统WebView ✓防撤回 … ✗某功能(NoSuchMethodException: …)`
   —— ✗ 就是那个功能在这版 QQ 里找不到落点，其余不受影响
5. 之后换 APK 直接覆盖安装即可，LSPosed 会热重载（签名固定在 `app/qself.p12`）

## 结构

```
app/src/main/kotlin/sumicya/qself/
  Qself.kt   入口：功能清单（也是开关列表）、按进程装、日志
  Hook.kt    libxposed 封装（hook / constant / afterNew / 反射读写字段）与开关读取
  Knob.kt    主页那颗圆钮与开关对话框
  Quiet.kt   自由化
  Chat.kt    聊天，含防撤回用的几十行 protobuf 读取
  Looks.kt   外观：盯住每个 Activity 的视图树套规则
  Input.kt   TG 式输入栏
  Glass.kt   玻璃与滑动胶囊两个 Drawable
app/src/test/kotlin/sumicya/qself/ProtoTest.kt   防撤回判断的唯一一份可跑检查
app/src/main/resources/META-INF/xposed/          libxposed 入口、module.prop、作用域
```

GPL-3.0-or-later。源自 [QAuxiliary](https://github.com/cinit/QAuxiliary)，只留了思路，代码全部重写。
