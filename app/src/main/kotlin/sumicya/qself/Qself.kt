// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 功能清单：名字（也是开关的键）、是否只在主进程、装法。开关对话框按这个顺序列。
 * 每组自己找类装钩子，找不到就抛，install 接住、写进日志，不连累别的。
 * 玻璃底栏 / 藏页签 / 输入栏三项没有钩子，是 Looks.kt 视图扫描里按名字查开关的规则。
 */
val features: List<Triple<String, Boolean, () -> Any?>> = listOf(
    Triple("玻璃底栏", true, {}),
    Triple("藏频道动态", true, {}),
    Triple("TG输入栏", true, {}),
    Triple("标题栏侧栏精简", true, ::drawerMenu),
    Triple("统一气泡", true, ::plainBubble),
    Triple("统一字体", true, ::plainFont),
    Triple("去头像挂件", true, ::noPendant),
    Triple("昵称只留名字", true, ::plainNick),
    Triple("防撤回", true, ::antiRecall),
    Triple("转发多选", true, ::multiForward),
    Triple("+面板精简", true, ::plusPanel),
    Triple("屏蔽轻互动", true, ::noLightInteraction),
    Triple("屏蔽表情雨", true, ::noEmojiRain),
    Triple("系统WebView", false, ::systemWebView),
    Triple("屏蔽统计上报", false, ::noTelemetry),
    Triple("屏蔽崩溃上报", false, ::noCrashReport),
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
        fun run(name: String, fn: () -> Any?) = runCatching(fn)
            .onSuccess { report.append(" ✓").append(name) }
            .onFailure { report.append(" ✗").append(name).append('(').append(it.toString().take(120)).append(')') }
        for ((name, mainOnly, fn) in features) {
            if (mainOnly && !main) continue
            feature = name
            run(name, fn)
        }
        feature = null // 视图扫描器不归任何开关管，它自己按名字查
        if (main) run("视图扫描", ::looks)
        log(report.toString())
    }
}
