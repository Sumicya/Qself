// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** 这版 QQ 里没装上的功能。开关面板给它们画 ✗，点击显示失败原因，不伪装成可启用。 */
val failed = mutableSetOf<String>()
val failures = mutableMapOf<String, String>()

/**
 * 功能清单：名字（也是开关的键）、是否只在主进程、一句描述、装法。开关面板按这个顺序排。
 * 装法找不到类就抛，install 接住写进日志，不连累别的；装法是 {} 的是 Looks.kt 里按名字查开关的视图规则。
 */
class Feature(val name: String, val main: Boolean, val desc: String, val install: () -> Any?, val default: Boolean = true)

val features = listOf(
    Feature("玻璃底栏", true, "底栏浮成一颗玻璃胶囊", {}),
    Feature("Monet取色", true, "玻璃的颜色跟壁纸走", {}),
    Feature("藏频道动态", true, "底栏藏掉频道/动态/小世界", {}),
    Feature("TG输入栏", true, "原生输入区玻璃胶囊，保留输入框与全部按钮", ::inputLayout),
    Feature("标题栏侧栏精简", true, "藏一起听/一起看和侧栏会员那排", ::drawerMenu),
    Feature("统一气泡", true, "充值气泡归零，按默认画", ::plainBubble),
    Feature("统一字体", true, "魔法字体归零", ::plainFont),
    Feature("去头像挂件", true, "消息头像不画挂件", ::noPendant),
    Feature("防撤回", true, "撤回的消息留下，插灰字", ::antiRecall),
    Feature("连发合并", true, "昵称固定组首，单条头像与消息居中，连发头像随组浮动", ::groupRuns),
    Feature("回复不@", true, "回复不往输入框插 @昵称", ::replyNoAt),
    Feature("转发多选", true, "转发页常显多选入口", ::multiForward),
    Feature("转发不限人数", true, "转发选人不再卡 9 个", ::noForwardLimit),
    Feature("左滑回复不设限", true, "卡片消息也能左滑回复", ::replyAnyMsg),
    Feature("隐藏在线状态", true, "昵称下那行在线状态藏掉", {}),
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

val featureDefaults = features.associate { it.name to it.default }

/** libxposed 入口。功能默认开启；配置入口在 QQ 主页独立设置短胶囊中，实际生效受对象生命周期限制。 */
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
        if (!restoreUi()) return false
        param.setSavedInstanceState(installed)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        var uninstallFailed = false
        param.oldHookHandles.forEach { handle -> runCatching { handle.unhook() }.onFailure {
            uninstallFailed = true
            android.util.Log.e("Qself", "旧钩子卸载失败；停止新代安装，请强行停止 QQ 后再打开", it)
        } }
        if (uninstallFailed) return
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
        failures.clear()
        fun run(name: String, fn: () -> Any?, gated: Boolean = true) = runCatching {
            installFeature(if (gated) name else null, fn)
        }.onSuccess { report.append(" ✓").append(name) }
            .onFailure {
                failed += name
                failures[name] = it.stackTraceToString()
                report.append(" ✗").append(name).append('(').append(it.toString().take(120)).append(')')
                log("安装失败：$name；本项钩子已尝试回滚", it)
            }
        for (f in features) {
            if (f.main && !main) continue
            run(f.name, f.install)
        }
        feature = null // 视图扫描器不归任何开关管，它自己按名字查
        if (main) run("视图扫描", ::looks, false)
        log(report.toString())
    }
}

/** 热重载先还原旧代视图与监听器，防止两代布局、动画同时操纵同一窗口。 */
private fun restoreUi(): Boolean {
    val finished = java.util.concurrent.CountDownLatch(1)
    var failure: Throwable? = null
    val restore = Runnable {
        try {
            resetChatLayout(); resetInputLayout(); resetLooks(); resetMenus()
        } catch (t: Throwable) { failure = t } finally { finished.countDown() }
    }
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) restore.run()
    else {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(restore)
        if (!finished.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            handler.removeCallbacks(restore)
            log("热重载停止：主线程未在两秒内完成旧布局还原")
            return false
        }
    }
    failure?.let { log("热重载停止：旧布局还原失败", it) }
    return failure == null
}
