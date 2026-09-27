// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** 这版 QQ 里没装上的功能。开关对话框给它们画 ✗，点了也不让点。 */
val failed = mutableSetOf<String>()

/**
 * 功能清单：名字（也是开关的键）、是否只在主进程、一句描述、装法。开关面板按这个顺序排。
 * 装法找不到类就抛，install 接住写进日志，不连累别的；装法是 {} 的是 Looks.kt 里按名字查开关的视图规则。
 */
class Feature(val name: String, val main: Boolean, val desc: String, val install: () -> Any?)

val features = listOf(
    Feature("玻璃底栏", true, "底栏浮成一颗玻璃胶囊", {}),
    Feature("Monet取色", true, "玻璃的颜色跟壁纸走", {}),
    Feature("藏频道动态", true, "底栏藏掉频道/动态/小世界", {}),
    Feature("TG输入栏", true, "输入栏改 Telegram 式玻璃胶囊", {}),
    Feature("标题栏侧栏精简", true, "藏一起听/一起看和侧栏会员那排", ::drawerMenu),
    Feature("统一气泡", true, "充值气泡归零，按默认画", ::plainBubble),
    Feature("统一字体", true, "魔法字体归零", ::plainFont),
    Feature("去头像挂件", true, "消息头像不画挂件", ::noPendant),
    Feature("昵称只留名字", true, "昵称行去掉等级/头衔/会员", ::plainNick),
    Feature("防撤回", true, "撤回的消息留下，插灰字", ::antiRecall),
    Feature("连发合并", true, "同一人连发成组，只留一个头像", ::groupRuns),
    Feature("回复不@", true, "回复不往输入框插 @昵称", ::replyNoAt),
    Feature("转发多选", true, "转发页常显多选入口", ::multiForward),
    Feature("转发不限人数", true, "转发选人不再卡 9 个", ::noForwardLimit),
    Feature("左滑回复不设限", true, "卡片消息也能左滑回复", ::replyAnyMsg),
    Feature("隐藏在线状态", true, "昵称下那行在线状态藏掉", {}),
    Feature("+面板精简", true, "+ 面板只留正经附件", ::plusPanel),
    Feature("屏蔽红点引导", true, "红点和引导气泡不亮", ::noRedDot),
    Feature("去会员等级", true, "资料卡不画 SVIP/VIP 图标", ::plainCard),
    Feature("屏蔽轻互动", true, "轻互动特效不播", ::noLightInteraction),
    Feature("屏蔽表情雨", true, "表情雨不下", ::noEmojiRain),
    Feature("系统WebView", false, "网页走系统 WebView，不加载 X5", ::systemWebView),
    Feature("屏蔽统计上报", false, "灯塔统计空转", ::noTelemetry),
    Feature("屏蔽崩溃上报", false, "不初始化 Bugly", ::noCrashReport),
)

/** libxposed 入口。装上即全部生效；开关在 QQ 主页右下角那颗圆钮里。 */
class Qself : XposedModule() {
    private var process = ""
    private var installed: ClassLoader? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        process = param.processName
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.isFirstPackage) install(param.defaultClassLoader)
    }

    /** 换 APK 时 LSPosed 热重载：旧的一代把 ClassLoader 传给新的一代，新的一代重装。 */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(installed)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        process = param.processName
        (param.savedInstanceState as? ClassLoader)?.let(::install)
    }

    private fun install(classLoader: ClassLoader) {
        xposed = this
        loader = classLoader
        installed = classLoader
        val main = process == "com.tencent.mobileqq"
        val report = StringBuilder("Qself ${BuildConfig.VERSION_NAME} @ $process")
        failed.clear()
        fun run(name: String, fn: () -> Any?) = runCatching(fn)
            .onSuccess { report.append(" ✓").append(name) }
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
