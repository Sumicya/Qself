# Qself

给 NT QQ 的一个 LSPosed 模块：**自由、简单、现代、原生**。装上即全部生效，不联网、没有第三方库、没有一个 `.so`。
开关不在模块里，在 QQ 主页底栏胶囊旁边那颗玻璃圆钮里：点开是紧凑的双列玻璃面板，
画出来的 ✓/–/✗ 表示状态（✗ = 这版 QQ 没装上）；功能说明可由无障碍朗读，状态存进 QQ 自己的 SharedPreferences。

只对着一台机器写：QQ 9.2.10 · Android 16 · LSPosed 2.x（libxposed API 102）。类名、方法名全部写死，
但每一个都在 CI 里拿真 dex 逐条核过（[`tools/symbols.txt`](tools/symbols.txt)）——换版本先看那张表红哪条，别猜。

## 做了什么

外观（照着 [NagramXF](https://github.com/Keeperorowner/NagramXF) 那套长相来）
- 首页底栏浮成一颗居中的玻璃胶囊（每个页签 56dp，只包住页签）：实验性地录制可见底图并用 AGSL 折射，
  遇到模糊层或取样失败就退回透明材质；带渐变透色、弧形上缘反光与跟手按压亮斑。按下收缩轮廓，选中项滑动时略微拉伸。聊天输入栏和设置面板使用同一材质；实际 QQ 效果需真机验收
- **Monet 取色**：玻璃的罩色、选中圆、圆钮的颜色跟壁纸走（系统动态色 `system_accent1_*`，NagramX 的招牌）。
  关掉就退回原来那套写死的中性色
- 聊天输入栏 Telegram 化：一颗悬浮玻璃胶囊 [表情 · 输入框 · +] 加右侧玻璃圆钮 麦克风⇄发送，
  输入栏底色去掉、框里的 AI 星星藏掉，原来那条图标带收起
- 藏掉「频道」「动态」「小世界」页签
- 聊天标题栏去掉一起听 / 一起看 / 群游戏那排；侧栏去掉打卡、天气、等级、会员、装扮、钱包一类的入口（数据源和视图两头都拦）
- 隐藏好友聊天标题栏那行在线状态（在线 / 手机在线 / WiFi在线 / 忙碌 / 隐身…）
- 统一气泡、统一字体、去头像挂件；昵称行只留名字（不显示群等级、头衔、成员等级、会员图标）
- 具体未读条数：超过 99 条仍显示真实数量（QUIBadge 自绘角标与旧 TextView 角标）
- **屏蔽红点引导**：群红点、王者小队、群头衔、游戏中心红点、乐吧引导气泡、资料卡引导、虚拟形象角标一律不亮
- **去会员等级**：资料卡上 SVIP / VIP / 大会员 / 大会员年费那排图标不画

聊天
- 防撤回：私聊、群聊、重连补发的撤回都吞掉，被撤回的那条压成半透明，会话里插一条「xx 尝试撤回一条消息」灰字
- 连发合并：同一人 5 分钟内连着发的消息，后面的不再画头像（占位留着）和昵称行，像 Telegram 那样成组
- **回复不@**：回复消息时不再往输入框插「@昵称 」
- **左滑回复不设限**：卡片 (Ark) 消息也能左滑回复
- 转发页一直显示好友 / 群 / 多选入口

回复、左滑和防撤回借鉴上游 [QAuxiliary](https://github.com/cinit/QAuxiliary) 的思路，落点按 9.2.10 真 dex 核过。
未保留“转发不限人数”：原路径绕过 QQ 选择流程，搜索页勾选不会同步。
- 「+」面板只留正经附件，一起派对、礼物、直播间之类剔掉
- 屏蔽轻互动特效与表情雨

自由化
- 网页一律走系统 WebView，不加载 X5
- 灯塔 (beacon) 与 StatisticCollector 的统计上报空转
- 不初始化崩溃上报

## 用

1. Actions 里下载最新一次构建的 APK（或 `gh run download -R Sumicya/Qself`），安装
2. LSPosed 里启用 Qself，作用域已写死为 QQ，强行停止 QQ 再打开
3. 主页底栏胶囊旁边那颗圆钮（右边放不下就在胶囊右上方）：点开勾选即时生效（钩子每次被调用都查开关）；
   TG 输入栏可以当场撤销并恢复 QQ 原按钮；已浮起来的底栏仍要点「重启 QQ」才复原
4. 每个 QQ 进程启动时打一行日志，`logcat -s Qself` 或 LSPosed 管理器的日志页可见，形如
   `Qself 26.9.27.12 @ com.tencent.mobileqq ✓系统WebView ✓防撤回 … ✗某功能(NoSuchMethodException: …)`
   —— ✗ 就是那个功能在这版 QQ 里找不到落点，其余不受影响。**把这一行贴回来就能修。**
5. 之后换 APK 直接覆盖安装即可，LSPosed 会热重载（签名固定在 `app/qself.p12`）

## 结构

```
app/src/main/kotlin/sumicya/qself/
  Qself.kt   入口：功能清单（也是开关列表）、按进程装、日志
  Hook.kt    libxposed 封装（hook / constant / afterNew / 反射读写字段方法）与开关读取
  Knob.kt    主页那颗圆钮与紧凑的双列开关面板
  Quiet.kt   自由化
  Chat.kt    聊天，含防撤回用的几十行 protobuf 读取、撤回灰字、连发合并、回复不@
  Looks.kt   外观：盯住每个 Activity 的视图树套规则
  Input.kt   TG 式输入栏
  Glass.kt   RenderNode + AGSL 折射 Drawable（取样失败退回透明材质、Monet、按压反馈、选中滑块）
app/src/test/kotlin/sumicya/qself/ProtoTest.kt   防撤回判断的唯一一份可跑检查
app/src/main/resources/META-INF/xposed/          libxposed 入口、module.prop、作用域
tools/symbols.txt    落点表：代码依赖的每一个名字
tools/dexcheck.py    拿真 dex 逐条核落点，顺带 lint 源码里没登记的名字
tools/dexq.py        最小反汇编器：按签名找落点（上游用 DexKit 在运行时搜，这里离线搜）
ci/check.sh          无 SDK 时的类型检查 + 跑 ProtoTest
```

## 核落点

```sh
git clone --depth 1 https://github.com/Sumicya/qqapk q && cat q/qq.a* > qq.apk && rm -rf q
python3 tools/dexcheck.py --apk qq.apk --lint app/src/main/kotlin     # 全绿才算数
python3 tools/dexq.py qq.apk com.tencent.mobileqq.aio.input.reply.i   # 某个类里每个方法用到什么
```

GPL-3.0-or-later。源自 [QAuxiliary](https://github.com/cinit/QAuxiliary)，只留了思路，代码全部重写。
