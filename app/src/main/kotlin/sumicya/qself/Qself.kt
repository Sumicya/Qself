// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * libxposed 入口。没有设置、没有开关：装上即全部生效。
 *
 * 新增一组功能 = 在 [all] 里加一行。每组自己找类装钩子，找不到就抛，
 * 这里接住、写进日志，不连累别的。
 */
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
        val all: List<Triple<String, Boolean, () -> Any?>> = listOf(
            // 名字, 只在主进程, 装法
            Triple("系统WebView", false, ::systemWebView),
            Triple("屏蔽统计上报", false, ::noTelemetry),
            Triple("屏蔽崩溃上报", false, ::noCrashReport),
            Triple("统一气泡", true, ::plainBubble),
            Triple("统一字体", true, ::plainFont),
            Triple("去头像挂件", true, ::noPendant),
            Triple("防撤回", true, ::antiRecall),
            Triple("转发多选", true, ::multiForward),
            Triple("+面板精简", true, ::plusPanel),
            Triple("昵称只留名字", true, ::plainNick),
            Triple("屏蔽轻互动", true, ::noLightInteraction),
            Triple("屏蔽表情雨", true, ::noEmojiRain),
            Triple("侧栏菜单", true, ::drawerMenu),
            Triple("外观", true, ::looks),
        )
        val report = StringBuilder("Qself ${BuildConfig.VERSION_NAME} @ $process")
        for ((name, mainOnly, feature) in all) {
            if (mainOnly && !main) continue
            runCatching(feature)
                .onSuccess { report.append(" ✓").append(name) }
                .onFailure { report.append(" ✗").append(name).append('(').append(it.toString().take(120)).append(')') }
        }
        log(report.toString())
    }
}
