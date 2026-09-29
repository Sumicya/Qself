# qqapk 目录中另两个 APK 的静态检查

取自 `Sumicya/qqapk` 的 `master`；只解包并解析 Manifest、DEX 字符串、签名和目录，**没有安装或执行**。这不是代码安全审计，也不能由静态权限表断言动态加载的 native 代码行为。

| 项目 | FunBox_v2380.apk | 繁华模块_2.5.9.apk |
| --- | --- | --- |
| 原始大小 | 7,619,821 B | 4,641,746 B |
| SHA-256 | `b64783a5d050b2255445e1eb942b489543ca30e5d53fbd9446bcadcec031fd38` | `0e02367ddcbd10619810bf26623f8a8d8c12f3a7355959641bf7c691a54c1b94` |
| 包名 / 版本 | `have.fun` / `v2380` | `com.fanhua` / `2.5.9` |
| min / target SDK | 29 / 34 | 21 / 36 |
| Xposed 入口 | 旧式 `assets/xposed_init`: `fun.box001.loader.XPEntry`；另含 `zygisk/funloader.so` | libxposed `META-INF/xposed/java_init.list`: `com.fanhua.FanhuaMain`，同时含旧式入口 |
| 作用域 | `module.prop` 自述可选作用域，包中未见 `scope.list` | `scope.list` 包含 QQ、抖音、全民K歌、阅读和音乐等 8 个包 |
| Manifest 权限 | 未声明 | `INTERNET`、`QUERY_ALL_PACKAGES`、存储及电话状态等 |
| 主要代码 | 单 DEX 3,174,839 B；含 64 位 native 库 `libfun.so`、`libloader.so`、`liboat.so` | 单 DEX 4,770,212 B；含 64 位 `libfanhua-obfuscator.so` |
| 签名证书 SHA-256 | `7e4eaa9b14140bc00ff764c45627d86f87259c95bf007467b0237823c220a26f` | `44fe864a2b51127cf2e59a273377c23727e8176089404c3e1af4b75e114e26cb` |

FunBox 的 `module.prop` 声称提供「表情包、语音包模块」；不能据此认定 QQ 9.2.10 的功能可用。繁华模块的代码及原生库经过混淆；从上述文件**不能**可靠证明其具备自动点赞或真实等级加速。两个包都不是 Qself 的运行依赖；不直接复制第三方 APK 的代码、资源或权限。

## QAux 功能筛选与 QQ 9.2.10 落点

Qself 已独立构建、独立安装（`sumicya.qself`，libxposed API 102，作用域仅 QQ）；README 对 QAux 的称呼是产品思路的出处，不是必须安装的依赖。QAux 的 `OneTapTwentyLikes` 是访客/资料卡页面的一键多赞，不是后台遍历好友。`FakeQQLevel` 只伪装本地等级，不产生真实等级经验；两者都不直接移植。

| 候选功能 | QQ 真包静态证据 | 结论 |
| --- | --- | --- |
| 好友名片点赞 | `com.tencent.mobileqq.profile.vote.VoteHelper`：`b(CardProfile)` 查可用/当日已赞次数，`h(CardProfile,ImageView)` 走点击检查，`c(CardProfile,ImageView,boolean)` 使用 `VisitorsActivity`、`CardHandler` 请求并更新 `CardProfile` | 有**页面级**的原生链。后台批量需要可靠好友列表、异步加载每人 `CardProfile`、每日/单人配额及账号切换处理；只靠重复调用点击方法不能保证成功，暂未装自动钩子。 |
| 等级相关签到 | `DailySignInWebviewPlugin.callActionSignIn` 通过网页 JS 桥把 `action.userSignInForSettingMe` 交给 `SignInModule`；`QQLevelJsPlugin` 名空间是 `levelicon` | 证明有设置页签到请求，不证明可以脱离页面每天自动完成、更不证明签到等于全部等级加速任务。等级由服务器结算，不能靠本地改数值加速；暂未装自动钩子。 |
| 底栏数字/图标 | `QQTabLayout` 子树使用 `TabDragAnimationView.onDraw(Canvas)` 绘制图标，触摸仍由 QQ 自己处理；`RedTouch.getTextRedPoint` 用 `maxNum` 排版 | Qself 只替换已识别页签的绘制，不接管触摸。数字从 QQ 已显示的纯数字读取；若只得到 `99+` 则不伪造精确数。 |

静态符号核对只能证明方法存在，不能证明服务端响应、好友列表迭代完整性或实际触摸观感。要实施后台遍历，需先在用户允许的测试账号中记录 QQ 原生点赞和签到的一次完整调用/返回链，并设计每账号每天总次数、每人间隔、失败退避和显式停用。不要用循环发请求或伪造客户端等级替代验证。
