// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 面板里就这 4 行，顺序即展示顺序；名字就是 SharedPreferences 的键。
 * 只有会互相打架的（底栏 / 输入栏 / 取色）和「关掉要重启才复原」的（防撤回）值得给开关，其余一律生效。
 */
val knobs = listOf("玻璃底栏", "Monet取色", "TG输入栏", "防撤回")

/** 装得上东西的功能：名字（= ✓/✗ 日志里的名字）、是不是只在主进程装、装法。
 *  纯视图规则（玻璃底栏 / Monet取色 / TG输入栏 / 藏页签 / 藏在线状态）不进这张表 —— 它们没东西可装，
 *  自己在 Looks.kt / Input.kt 里按名字查开关。 */
class Feature(val name: String, val main: Boolean = true, val install: () -> Any?)

val features = listOf(
    Feature("防撤回", install = ::antiRecall),
    Feature("标题栏侧栏精简", install = ::drawerMenu),
    Feature("会员装饰归零", install = ::plainDecor),
    Feature("昵称只留名字", install = ::plainNick),
    Feature("连发合并", install = ::groupRuns),
    Feature("回复不@", install = ::replyNoAt),
    Feature("转发多选", install = ::multiForward),
    Feature("转发不限人数", install = ::noForwardLimit),
    Feature("左滑回复不设限", install = ::replyAnyMsg),
    Feature("显示具体未读条数", install = ::exactCount),
    Feature("+面板精简", install = ::plusPanel),
    Feature("屏蔽红点引导", install = ::noRedDot),
    Feature("去会员等级", install = ::plainCard),
    Feature("屏蔽轻互动", install = ::noLightInteraction),
    Feature("屏蔽表情雨", install = ::noEmojiRain),
    Feature("系统WebView", main = false, install = ::systemWebView),
    Feature("屏蔽统计上报", main = false, install = ::noTelemetry),
    Feature("屏蔽崩溃上报", main = false, install = ::noCrashReport),
)

/** libxposed 入口。装上即全部生效；那 4 个开关在 QQ 主页底栏那颗圆钮里。 */
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
            // ✗ = 这版 QQ 里找不到落点。只有这一行日志能看到，面板里不画（真机上核不准）。
            .onFailure { report.append(" ✗").append(name).append('(').append(it.toString().take(120)).append(')') }
        for (f in features) {
            if (f.main && !main) continue
            feature = if (f.name in knobs) f.name else null // 只有面板里那几行受开关管，其余钩子不查偏好
            run(f.name, f.install)
        }
        feature = null // 视图扫描器不归任何开关管，它自己按名字查
        if (main) run("视图扫描", ::looks)
        log(report.toString())
    }
}
