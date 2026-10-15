# APK 分析记录

## 工具与边界

用途仅为核对本模块依赖的 QQ UI 数据/布局，以及分析用户指定的两份模块作为行为参考。所有解析本地执行，不运行 APK、不调用其服务、不上传 APK；不把闭源模块的反编译源码或二进制带入本仓。

- Androguard 4.1.4：Apache-2.0，隔离安装于沙箱工具缓存；实际完成 DEX 解析、交叉引用、DAD 反编译及 AXML 读取。它不是 Android 运行环境，成功解析不代表还原了保护后的全部业务。
- JADX 1.5.6：上游 skylot/jadx，Apache-2.0；Release 下载重定向至 release-assets.githubusercontent.com 后 EOF，未安装、未运行。不通过第三方镜像绕过网络限制。
- DexSQL：上游 LSPosed/dexsql-release，已阅读 README；闭源 Binary License 1.0，登记不等于安装。本环境尚未安装/验证，不将上游性能宣传写成本项目实测。
- REA：本会话没有可调用接入，不把“无接入”说成它不能解析这两份 APK。

## 输入对象

- FunBox_v2380.apk：SHA-256 `b64783a5d050b2255445e1eb942b489543ca30e5d53fbd9446bcadcec031fd38`；包名 `have.fun`。入口为 `fun.box001.loader.XPEntry`，调用 `FunLoader.load()` 原生装载；现有静态 DEX 不能据此宣称已经取得其完整功能实现。
- 繁华模块_2.5.9.apk：SHA-256 `0e02367ddcbd10619810bf26623f8a8d8c12f3a7355959641bf7c691a54c1b94`；包名 `com.fanhua`。入口 `com.fanhua.FanhuaMain` / `com.fanhua.a1` 可反编译，但有控制流混淆、字符串变换和 `libfanhua-obfuscator.so`；尚未恢复并核验目标功能实现。
- QQ 9.2.10：SHA-256 前缀 `34bdea66e738`，37 个 DEX。实际用于本次代码改动的事实如下。

## 已核验的 QQ 路径

1. **DEX 内这条底栏静态调用链是 `TabFrameControllerImpl.updateFrames → FrameControllerInjectImpl.b → com.tencent.widget.d#a(QUIBadge,int,int,int,String)`**：style 3/7/9 且 count > limit 时会调用 setRedText(limit + "+") 而非 setRedNum，mNum 被清零；因此只钩 updateNum / TextView 绑定器不能覆盖底栏。该事实已实际 DAD 反编译并用 DEX 调用关系核对。
2. `com.tencent.widget.d#d(TextView,int,int,int,int,String,boolean)`：参数依次为视图、样式、真实计数、背景、上限、文字、标记。旧实现将样式当成计数；样式 3/4/7/9 才是本次处理的数字徽标，不更改 NEW 等其他类型。
3. `QUIBadge.updateNum(int)` 将真实数存入 `mNum`，大于 99 时 `mText` 写为 `99+`；`getMinWidth()` 对普通数字使用固定档位，`onMeasure` 与 `drawText` 均依赖它。因此必须同时处理文字、原生 Paint 测量宽度与布局，而不只是替换文字。
4. `r/i/ez.xml`、`r/i/k_.xml` 等布局：输入行与图标带分别有背景；QQBlurView 以 RelativeLayout 规则对齐输入行顶部并延伸至父底部。只能清理这个明确属于输入区的背景，不能清理它们共同的全屏父容器。
5. 原生输入 EditText 与发送等控件可同处一个 FrameLayout；给整个 FrameLayout 加玻璃会把按钮区域也包进胶囊。玻璃现在只作用于真实 EditText。

6. **新版 NT 输入行 `r/i/dxg.xml` 为 ConstraintLayout + AIOEditText + AIOInputSendBtn**，没有旧图标带；`InputEditTextVBDelegate.U(adorn.e)` 在父级准备阶段设置真实 EditText 到字段 e。已实读 DEX/DAD/AXML，改用这个入口发现并管理输入样式。
7. `TabFrameLayout.onFinishInflate` 写入 d（ViewPager2）和 e（QQTabLayout），`a(): View` 读取 d；运行时以 e 的实例身份核对底栏，通过 a() 获取内容页。删除原先按尺寸/可滚动性挑选列表的代码，不再替 QQ 选择内容树内的手势目标。
8. 可复现检查：`python3 tools/ui_paths.py qq.apk`，输出 APK 完整 SHA-256、八个方法的完整签名、字段读写方向和要求的调用边。两条 CI 都运行。该检查仅证明 APK 内的静态关系，不证明设备界面采用哪条分支、钩子是否命中或最终绘制效果。

## 仍未验证

无设备连接，未验证实际 QQ 角标、输入键盘、拖动及点击效果。用户报告的头像/消息/昵称合并仍列为未解决；纯逻辑、源码契约和出包成功不能替代这些验收。
