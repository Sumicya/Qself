/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.qauxv.BuildConfig
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonConfigFunctionHook
import io.github.qauxv.ui.CommonContextWrapper
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.PACKAGE_NAME_QQ
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.Toasts
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Read-only diagnostic. Does not alter arguments, results, throwables, login or network policy. */
@FunctionHookEntry
@UiItemAgentEntry
object ReportDiagnostics : CommonConfigFunctionHook(
    hookKey = "qself.report_diagnostics",
    targetProc = SyncUtils.PROC_MAIN or SyncUtils.PROC_MSF,
) {
    override val name = "上报诊断（只读）"
    override val description = "仅观察已知 O3 命令调用，不代表服务器收到上报。默认关闭，开启后完整重启 QQ。"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY
    override val extraSearchKeywords = arrayOf("上报", "掉线", "风控", "O3", "诊断")
    override val isApplicationRestartRequired = true
    override val isAvailable: Boolean
        get() = hostInfo.packageName == PACKAGE_NAME_QQ && hostInfo.versionCode == 11310L
    // Explicit opt-in, even when the developer "enable all hooks" switch is on.
    override var isEnabled: Boolean
        get() = ReportDiagnosticsStore.enabled
        set(value) { ReportDiagnosticsStore.enabled = value }
    override val valueState = MutableStateFlow<String?>(null)
    override val onUiItemClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ ->
        showMenu(activity)
    }

    private val ids = AtomicLong()
    private val uiBusy = AtomicBoolean()
    private var rateWindow = 0L
    private var rateCount = 0
    private val source: String get() = if (SyncUtils.isMainProcess()) "main" else "msf"
    private data class Call(val command: String, val epoch: String, val generation: String, val id: Long, val started: Long)

    @Synchronized
    private fun admit(): Boolean {
        val second = SystemClock.elapsedRealtime() / 1000
        if (rateWindow != second) { rateWindow = second; rateCount = 0 }
        if (rateCount++ < 20) return true
        ReportDiagnosticsStore.noteSkipped()
        return false
    }

    override fun initOnce(): Boolean {
        if (!isAvailable || !isEnabled || !isTargetProcess) return false
        val process = source
        ReportDiagnosticsStore.record(process, "START", "module=${BuildConfig.VERSION_NAME} qq=9.2.10(11310)")
        var totalInstalled = 0
        val targets = listOf(
            "com.tencent.mobileqq.msf.core.MsfCore" to setOf("sendMessage", "sendMessageInner"),
            "com.tencent.mobileqq.channel.ChannelManager" to setOf("sendMessage"),
            "com.tencent.mobileqq.channel.ChannelProxyExt" to setOf("sendMessage"),
        )
        for ((className, names) in targets) {
            var candidates = 0
            var installed = 0
            var unsupported = 0
            var failed = 0
            try {
                val type = Initiator.load(className)
                if (type == null) {
                    ReportDiagnosticsStore.record(process, "COVERAGE", "${className.substringAfterLast('.')} class-absent")
                    continue
                }
                for (method in type.declaredMethods.filter { it.name in names }) {
                    candidates++
                    if (Modifier.isAbstract(method.modifiers) || method.parameterCount > 16) {
                        unsupported++
                        continue
                    }
                    val getters = method.parameterTypes.mapIndexedNotNull { index, parameter ->
                        ReportMetadata.commandGetter(parameter)?.let { index to it }
                    }
                    val directCommand = className == "com.tencent.mobileqq.channel.ChannelProxyExt" &&
                        method.returnType == Void.TYPE && method.parameterTypes.contentEquals(
                            arrayOf(String::class.java, ByteArray::class.java, java.lang.Long.TYPE))
                    if (getters.isEmpty() && !directCommand) { unsupported++; continue }
                    try {
                        // Method selection is exact (the reflected Method), but command carriers
                        // typed Object/no public String getter are deliberately not covered.
                        val label = "${type.simpleName}.${method.name}#$candidates/${method.parameterCount}"
                        XposedBridge.hookMethod(method, observer(process, label, getters, directCommand))
                        installed++
                    } catch (error: Throwable) {
                        failed++
                        ReportDiagnosticsStore.record(process, "HOOK_ERROR", ReportMetadata.exceptionType(error))
                    }
                }
            } catch (error: Throwable) {
                failed++
                ReportDiagnosticsStore.record(process, "RESOLVE_ERROR", ReportMetadata.exceptionType(error))
            }
            totalInstalled += installed
            ReportDiagnosticsStore.record(process, "COVERAGE",
                "${className.substringAfterLast('.')} candidates=$candidates installed=$installed unsupported=$unsupported failed=$failed")
        }
        ReportDiagnosticsStore.record(process, "INSTALL", "installed=$totalInstalled server-delivery=UNKNOWN")
        return totalInstalled > 0
    }

    private fun observer(process: String, label: String, getters: List<Pair<Int, Method>>, directCommand: Boolean): XC_MethodHook {
        // Weak keys: an interrupted host invocation must not pin its arguments or classloader.
        val calls = WeakHashMap<XC_MethodHook.MethodHookParam, Call>()
        return object : XC_MethodHook(10_000) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (!isEnabled) return
                    // Only the exact legacy signature reads arg0. Never access its byte[] payload.
                    val command = (if (directCommand) ReportMetadata.command(param.args.getOrNull(0) as? String)
                    else getters.firstNotNullOfOrNull { (index, getter) ->
                        val argument = param.args.getOrNull(index) ?: return@firstNotNullOfOrNull null
                        ReportMetadata.command(getter.invoke(argument) as? String)
                    }) ?: return
                    if (!admit()) return
                    val call = Call(command, ReportDiagnosticsStore.epoch(), ReportDiagnosticsStore.generation(), ids.incrementAndGet(), SystemClock.elapsedRealtime())
                    synchronized(calls) {
                        if (calls.size >= 128) { ReportDiagnosticsStore.noteSkipped(); return }
                        calls[param] = call
                    }
                    ReportDiagnosticsStore.record(process, "ENTRY",
                        "id=${call.id} $label cmd=${call.command}", call.epoch, observedCall = true, expectedGeneration = call.generation)
                } catch (_: Throwable) {
                    ReportDiagnosticsStore.noteObserverFailure()
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val call = synchronized(calls) { calls.remove(param) } ?: return
                    if (!isEnabled) return
                    val elapsed = SystemClock.elapsedRealtime() - call.started
                    // This is the hook-chain outcome, NOT proof that the original ran. A null
                    // result could be a normal void return or another hook cancelling the call.
                    ReportDiagnosticsStore.record(process, "AFTER_CALLBACK",
                        "id=${call.id} elapsedMs=$elapsed errorType=${ReportMetadata.exceptionType(param.throwable)} delivery=UNKNOWN",
                        call.epoch, expectedGeneration = call.generation)
                } catch (_: Throwable) {
                    ReportDiagnosticsStore.noteObserverFailure()
                }
            }
        }
    }

    private fun showMenu(activity: Activity) {
        if (!isAvailable) {
            Toasts.info(activity, "请在 QQ 9.2.10（11310）内打开；模块独立进程不共享 QQ 的诊断数据。")
            return
        }
        val ctx = CommonContextWrapper.createAppCompatContext(activity)
        val options = arrayOf(
            if (isEnabled) "停止记录（已装 Hook 在重启后卸除）" else "开启记录（需完整重启 QQ）",
            "查看 / 复制诊断摘要", "标记：刚刚实际掉线", "标记：刚刚收到风险提醒", "清空记录",
        )
        AlertDialog.Builder(ctx).setTitle(name).setItems(options) { _, which ->
            when (which) {
                0 -> if (isEnabled) {
                    io(activity, { isEnabled = false }) {
                        valueState.value = "已停止"
                        Toasts.info(activity, "已停止采集；完整重启 QQ 后卸除观察 Hook。")
                    }
                } else {
                    AlertDialog.Builder(ctx).setTitle("开启只读诊断？")
                        .setMessage("仅记录已知 O3 命令名、时间、进程、调用次数和异常类型；不读取请求正文或凭据。\n" +
                            "其他 Hook 可能改变结果；零记录不等于没有上报。每秒最多采集 20 次调用，每进程最多保留 128 行。\n" +
                            "开启后需完整重启 QQ。不会修改你现有的 O3 拦截开关。")
                        .setPositiveButton("开启") { _, _ ->
                            io(activity, {
                                if (ReportDiagnosticsStore.epoch().isEmpty()) ReportDiagnosticsStore.clear()
                                isEnabled = true
                            }) {
                                valueState.value = "待重启 / 观察"
                                Toasts.info(activity, "已保存，完整重启 QQ 后开始观察。")
                            }
                        }.setNegativeButton("取消", null).show()
                }
                1 -> io(activity, { header() + ReportDiagnosticsStore.report() }) { showReport(activity, it) }
                2, 3 -> {
                    if (!isEnabled) Toasts.info(activity, "诊断未开启，未写入标记。")
                    else {
                        ReportDiagnosticsStore.record("main", "USER_MARK",
                            if (which == 2) "logout-reported-now (manual, not an automatic detection)"
                            else "risk-notice-reported-now (manual, not server detection time)")
                        Toasts.info(activity, "已提交本地时间标记；稍后在摘要中核对。")
                    }
                }
                4 -> AlertDialog.Builder(ctx).setTitle("清空诊断记录？")
                    .setMessage("清空主进程和 MSF 记录，并使旧窗口的排队记录失效。不改变 O3 拦截或 QQ 登录状态。")
                    .setPositiveButton("清空") { _, _ -> io(activity, { ReportDiagnosticsStore.clear() }) {
                        Toasts.info(activity, "已清空，开启中的诊断会继续记录新事件。")
                    } }.setNegativeButton("取消", null).show()
            }
        }.setNegativeButton("关闭", null).show()
    }

    private fun header(): String {
        val o3 = ConfigManager.getDefaultConfig().getBooleanOrDefault("rq_risk_report_interceptor.enabled", false)
        val legacy = ConfigManager.getDefaultConfig().getBooleanOrDefault("ChannelProxyHook.enabled", false)
        return "Qself 上报诊断 v1\n模块=${BuildConfig.VERSION_NAME} QQ=${hostInfo.versionName}(${hostInfo.versionCode})\n" +
            "记录开关=${isEnabled}；O3 拦截配置=${if (o3) "开" else "关或不存在"}（不代表 Hook 已安装或有效）\n" +
            "旧 ChannelProxy 拦截配置=$legacy（此版本在 QQ≥9.1.30 强制禁止安装）\n" +
            "范围：含 ChannelProxyExt 的已知精确签名，以及 MsfCore / ChannelManager 上具有公开 String getServiceCmd() 参数的发送方法，且仅两类 O3 命令。\n" +
            "时间为 UTC；ENTRY=观察到调用，AFTER_CALLBACK=观察到回调结束，不代表真正发送。服务器接收状态未知。\n" +
            "同一次请求可能经过多层方法，次数不能当作唯一网络请求数；其他模块/拦截、限流和未覆盖路径都可能造成漏记。\n" +
            "本地记录可跨进程重启保留。丢弃/异常计数为各写入代次累计，不随清空归零。INSTALL/START 是历史记录，不保证进程仍存活；清空后须等待新事件。\n"
    }

    private fun showReport(activity: Activity, report: String) {
        val ctx = CommonContextWrapper.createAppCompatContext(activity)
        val text = TextView(ctx).apply {
            this.text = report
            textSize = 12f
            setTextIsSelectable(true)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        AlertDialog.Builder(ctx).setTitle("诊断摘要（本地）")
            .setView(ScrollView(ctx).apply { addView(text) })
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制摘要") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Qself 诊断摘要", report))
                Toasts.info(activity, "已复制元数据摘要；分享前请再次检查。")
            }.show()
    }

    private fun <T> io(activity: Activity, task: () -> T, complete: (T) -> Unit) {
        if (!uiBusy.compareAndSet(false, true)) { Toasts.info(activity, "正在处理，请稍候。"); return }
        SyncUtils.async {
            val result = runCatching(task)
            activity.runOnUiThread {
                uiBusy.set(false)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    result.fold(complete) { Toasts.error(activity, "诊断操作失败：${ReportMetadata.exceptionType(it)}") }
                }
            }
        }
    }
}
