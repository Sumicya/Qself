// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** 这版 QQ 里没装上的功能。开关面板给它们画 ✗，点了也不让点。 */
val failed = mutableSetOf<String>()

/**
 * 功能清单：名字（也是开关的键）、是否只在主进程、一句描述、装法。开关面板按这个顺序排。
 * 装法找不到类就抛，install 接住写进日志，不连累别的；装法是 {} 的是 Looks.kt / Input.kt 里
 * 按名字查开关的视图规则。默认全开，一行一个开关。
 */
class Feature(val name: String, val main: Boolean, val desc: String, val install: () -> Any?)

val features = listOf(
    Feature("玻璃底栏", true, "底栏浮成一颗液态玻璃胶囊", {}),
    Feature("Monet取色", true, "玻璃的颜色跟壁纸走", {}),
    Feature("TG输入栏", true, "输入栏改 Telegram 式玻璃胶囊", {}),
    Feature("防撤回", true, "撤回的消息留下，压暗加 ✗", ::antiRecall),
    Feature("连发合并", true, "同一人连发成组，只留一个头像", ::groupRuns),
    Feature("重复项合并", true, "连着发的相同文字，第一条之后收起", ::dedupe),
    Feature("消息ID和时间", true, "每条消息挂灰色小标签；长按开关改格式", ::msgStamp),
    Feature("标题栏侧栏精简", true, "藏一起听/一起看和侧栏会员那排", ::drawerMenu),
    Feature("会员装饰归零", true, "气泡/字体/挂件按默认画", ::plainDecor),
    Feature("回复不@", true, "回复不往输入框插 @昵称", ::replyNoAt),
    Feature("转发多选", true, "转发页常显多选入口", ::multiForward),
    Feature("左滑回复不设限", true, "卡片消息也能左滑回复", ::replyAnyMsg),
    Feature("显示具体未读条数", true, "角标不再停在 99+", ::exactCount),
    Feature("+面板精简", true, "+ 面板只留正经附件", ::plusPanel),
    Feature("屏蔽红点引导", true, "红点和引导气泡不亮", ::noRedDot),
    Feature("去会员等级", true, "资料卡不画 SVIP/VIP 图标", ::plainCard),
    Feature("屏蔽轻互动", true, "轻互动特效不播", ::noLightInteraction),
    Feature("屏蔽表情雨", true, "表情雨不下", ::noEmojiRain),
    Feature("系统WebView", false, "网页走系统 WebView，不加载 X5", ::systemWebView),
    Feature("屏蔽统计上报", false, "灯塔统计空转", ::noTelemetry),
    Feature("屏蔽崩溃上报", false, "不初始化 Bugly", ::noCrashReport),
)

/** libxposed 入口。装上即全部生效；开关在 QQ 主页底栏那颗玻璃圆钮里，一行一个，默认全开。 */
class Qself : XposedModule() {
    private var process = ""
    private var installed: ClassLoader? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        process = param.processName
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // scope.list 之外再核一次实际包与进程，避免作用域被误扩大时向无关应用装钩子
        if (param.isFirstPackage && param.packageName == "com.tencent.mobileqq" && qqProcess(process))
            install(param.defaultClassLoader)
    }

    /** 换 APK 时 LSPosed 热重载：旧的一代把 ClassLoader 传给新的一代，新的一代重装。 */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(installed)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        process = param.processName
        if (qqProcess(process)) (param.savedInstanceState as? ClassLoader)?.let(::install)
    }

    private fun qqProcess(name: String) = name == "com.tencent.mobileqq" || name.startsWith("com.tencent.mobileqq:")

    private fun install(classLoader: ClassLoader) {
        xposed = this
        loader = classLoader
        installed = classLoader
        val main = process == "com.tencent.mobileqq"
        val report = StringBuilder("Qself ${BuildConfig.VERSION_NAME} @ $process")
        failed.clear()
        fun run(name: String, fn: () -> Any?) = runCatching(fn)
            .onSuccess { report.append(" ✓").append(name) }
            // ✗ = 这版 QQ 里找不到落点。面板里这一行画叉，看日志这一行查原因。
            .onFailure { failed += name; report.append(" ✗").append(name).append('(').append(it.toString().take(120)).append(')') }
        for (f in features) {
            if (f.main && !main) continue
            feature = f.name
            run(f.name, f.install)
        }
        feature = null // 视图扫描器不归任何开关管，它自己按名字查
        if (main) run("视图扫描", ::looks)
        log(report.toString())
    }
}
