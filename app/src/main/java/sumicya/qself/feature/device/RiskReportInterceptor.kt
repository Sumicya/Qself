/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.device

import android.app.Activity
import android.view.View
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XC_MethodHook.MethodHookParam
import io.github.qauxv.util.xpcompat.XposedBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sumicya.qself.diagnostics.FeatureJournal
import sumicya.qself.diagnostics.ReportMetadata
import sumicya.qself.diagnostics.ServiceCmdSendPath
import sumicya.qself.ui.InlineAlertDialogBuilder
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 拦截风控上报（O3）— merged from the user-provided QQHook 1.4
 * (io.github.jhl337.qqhook, dex string-table analysis 2026-09-07).
 *
 * QQHook's send points, preserved: MsfCore's queue entry (sendMessage with its
 * sendMessageInner sibling) and the channel send method
 * `sendMessage(String cmd, byte[] body, long id) -> void`.
 *
 * Route policy (see [ServiceCmdSendPath]): every declared method of a target
 * class is enumerated - never "the first one named sendMessage", whose order is
 * unspecified - and only a void route whose command is readable is hooked.
 * Anything else would need an invented result: the bridge rejects a null result
 * for a primitive-returning method (`WrappedCallbacks.WrappedHookParam.checkResultCast`),
 * and a reference return would hand the caller an unverified object.
 *
 * Host evidence (QQ 9.2.10 / 11310, this module's own feature journal):
 * `MsfCore candidates=0` - the class no longer declares sendMessage - and the
 * only remaining route is `ChannelManager.sendMessage(String,byte[],long)->void`,
 * which is the channel path. The in-tree ChannelProxyHook disables that path
 * from 9.1.30 because replacing it is recorded as linked to account problems,
 * so blocking it here requires an explicit opt-in from the settings row
 * (`rq_risk_report_interceptor.channel_path`), and the reason is journaled
 * either way instead of failing silently.
 */
@FunctionHookEntry
@UiItemAgentEntry
object RiskReportInterceptor : CommonSwitchFunctionHook(
    hookKey = "rq_risk_report_interceptor",
    targetProc = SyncUtils.PROC_MAIN or SyncUtils.PROC_MSF,
) {

    private const val TAG = "RiskReportInterceptor"

    /** One journal line per distinct command; the journal keeps 96 lines per process. */
    private const val MAX_JOURNALED_COMMANDS = 12
    private const val MAX_REPORTED_FAILURES = 4
    private const val MAX_SKIPPED_LINES = 6

    /** Explicit opt-in for the channel path on host versions the in-tree hook refuses. */
    private const val CHANNEL_OPT_IN_KEY = "rq_risk_report_interceptor.channel_path"

    /** Class to candidate method names; a missing class is a normal host difference. */
    private val ROUTES = arrayOf(
        "com.tencent.mobileqq.msf.core.MsfCore" to arrayOf("sendMessage", "sendMessageInner"),
        "com.tencent.mobileqq.channel.ChannelManager" to arrayOf("sendMessage"),
        "com.tencent.mobileqq.channel.ChannelProxyExt" to arrayOf("sendMessage"),
    )

    private val journaled = ConcurrentHashMap.newKeySet<String>()
    private val failures = AtomicInteger()
    private val blocked = AtomicInteger()
    private val stateFlow = MutableStateFlow<String?>(null)

    /** Pure: is this outgoing service cmd a risk-control command we may stop? */
    @JvmStatic
    fun shouldBlock(cmd: String?): Boolean = ServiceCmdSendPath.isRiskControlCommand(cmd)

    /**
     * Whether the channel path may be blocked on this host version.
     *
     * Defaults to on: the device owner confirmed the channel path explicitly on 2026-09-16
     * ("开"), knowing the recorded risk, because on QQ 9.2.10 it is the only send route that
     * still exists. Tapping the entry turns it back off; the choice is stored.
     */
    @JvmStatic
    var channelPathOptIn: Boolean
        get() = runCatching { ConfigManager.getDefaultConfig().getBooleanOrDefault(CHANNEL_OPT_IN_KEY, true) }
            .getOrDefault(true)
        set(value) {
            runCatching { ConfigManager.getDefaultConfig().putBoolean(CHANNEL_OPT_IN_KEY, value) }
            refreshState()
        }

    /** True when this host only offers the channel route and the user has not accepted it. */
    private val channelPathUnconfirmed: Boolean
        get() = ServiceCmdSendPath.channelPathNeedsOptIn(hostInfo.versionCode) && !channelPathOptIn

    override val name = "拦截风控上报（O3）"

    override val description = "拦截 trpc.o3.mobile_security.* 与 trpc.o3.report.* 的发出。" +
        "只拦截命令可读且返回 void 的路径；其他路径只记录原因，不编造返回值。" +
        "通道路径已按你的确认启用（仓库内既有账号异常记录，可点条目关闭）。重启生效"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.MISC_CATEGORY

    override val uiItemAgent by lazy {
        val base = super.uiItemAgent
        object : IUiItemAgent by base {
            override val valueState: StateFlow<String?> get() = stateFlow
            override val onClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ -> showMenu(activity) }
        }
    }

    override fun initOnce(): Boolean {
        var blockedRoutes = 0
        var channelRouteSkipped = false
        val skipped = ArrayList<String>()
        for ((className, methodNames) in ROUTES) {
            val type = runCatching { Initiator.loadClass(className) }.getOrNull()
            if (type == null) {
                FeatureJournal.record("COVERAGE", javaClass.name, "${className.substringAfterLast('.')} class-absent")
                continue
            }
            var candidates = 0
            var installed = 0
            for (method in type.declaredMethods.filter { it.name in methodNames }) {
                candidates++
                val shape = ServiceCmdSendPath.routeShape(
                    type.simpleName, method.name, method.parameterTypes, method.returnType)
                if (!ServiceCmdSendPath.isHookableShape(method.modifiers, method.parameterCount)) {
                    skipped += "$shape shape-unsupported"
                    continue
                }
                if (!ServiceCmdSendPath.canSuppressReturn(method.returnType)) {
                    // Never fabricate a result: the caller's interpretation is unverified.
                    skipped += "$shape return-type-unconfirmed"
                    continue
                }
                val carriers = ArrayList<Carrier>()
                if (ServiceCmdSendPath.isDirectCommandRoute(method.name, method.parameterTypes)) {
                    // The recorded channel shape carries the command as its first argument.
                    carriers += Carrier(0, null)
                } else {
                    for (index in ServiceCmdSendPath.commandArgumentIndices(method.parameterTypes)) {
                        val parameter = method.parameterTypes[index]
                        val getter = ReportMetadata.commandGetter(parameter) ?: continue
                        carriers += Carrier(index, getter)
                    }
                }
                if (carriers.isEmpty()) {
                    skipped += "$shape no-command-getter"
                    continue
                }
                if (ServiceCmdSendPath.isChannelPathOwner(type.simpleName) && channelPathUnconfirmed) {
                    channelRouteSkipped = true
                    skipped += "$shape channel-path-opt-in-required"
                    continue
                }
                runCatching { XposedBridge.hookMethod(method, blocker(carriers)) }
                    .onSuccess { installed++ }
                    .onFailure {
                        skipped += "$shape hook-failed"
                        FeatureJournal.error(javaClass.name, it)
                    }
            }
            blockedRoutes += installed
            FeatureJournal.record("COVERAGE", javaClass.name,
                "${type.simpleName} candidates=$candidates installed=$installed")
        }
        for (line in skipped.take(MAX_SKIPPED_LINES)) {
            FeatureJournal.record("COVERAGE", javaClass.name, "unblocked $line")
        }
        if (skipped.size > MAX_SKIPPED_LINES) {
            FeatureJournal.record("COVERAGE", javaClass.name, "unblocked +${skipped.size - MAX_SKIPPED_LINES} more")
        }
        Log.i("$TAG: blockedRoutes=$blockedRoutes skippedRoutes=${skipped.size} channelOptIn=${channelPathOptIn}")
        FeatureJournal.record("COVERAGE", javaClass.name,
            "channel-path=${if (channelPathOptIn) "enabled" else "refused"} host=${hostInfo.versionCode}")
        refreshState(channelRouteSkipped && blockedRoutes == 0)
        return blockedRoutes > 0
    }

    private fun refreshState(channelPending: Boolean = channelPathUnconfirmed) {
        stateFlow.value = when {
            blocked.get() > 0 && isEnabled -> "已拦截 ${blocked.get()} 条"
            blocked.get() > 0 -> "已停止（拦截过 ${blocked.get()} 条）"
            channelPending -> "通道路径待确认"
            !isEnabled -> "已关闭"
            else -> "未找到可拦截路径"
        }
    }

    /** One carrier: the argument at [index], and the getter to read it with (null = it is the command). */
    private class Carrier(val index: Int, val getter: Method?)

    private fun blocker(carriers: List<Carrier>) = object : XC_MethodHook(50) {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                if (!isEnabled) return
                for (carrier in carriers) {
                    val argument = param.args?.getOrNull(carrier.index) ?: continue
                    val raw = if (carrier.getter == null) argument as? String
                    else carrier.getter.invoke(argument) as? String
                    if (raw == null || !ServiceCmdSendPath.isRiskControlCommand(raw)) continue
                    param.setResult(null)
                    noteBlocked(raw)
                    return
                }
            } catch (error: Throwable) {
                noteFailure(error)
            }
        }
    }

    /** Logcat always sees the block; the journal keeps at most one line per distinct command. */
    private fun noteBlocked(raw: String) {
        blocked.incrementAndGet()
        refreshState()
        val cmd = ServiceCmdSendPath.redactedCommand(raw)
        Log.i("$TAG: report blocked, cmd: $cmd")
        if (journaled.size >= MAX_JOURNALED_COMMANDS) return
        if (journaled.add(cmd)) FeatureJournal.record("BLOCK", javaClass.name, cmd)
    }

    private fun noteFailure(error: Throwable) {
        Log.w("$TAG: block attempt failed: ${error.javaClass.name}")
        if (failures.incrementAndGet() <= MAX_REPORTED_FAILURES) FeatureJournal.error(javaClass.name, error)
    }

    private fun showMenu(activity: Activity) {
        val options = arrayOf(
            if (channelPathOptIn) "关闭通道路径拦截（需重启）" else "重新启用通道路径拦截（需重启）",
            "通道路径的风险记录",
        )
        InlineAlertDialogBuilder(activity).setTitle(name).setItems(options) { _, which ->
            when (which) {
                0 -> if (!channelPathOptIn) {
                    InlineAlertDialogBuilder(activity).setTitle("确认启用通道路径拦截？")
                        .setMessage("仓库内既有记录：在 QQ ≥ 9.1.30 上替换通道路径的发送方法，与账号异常下线/冻结相关。" +
                            "本机（${hostInfo.versionName}/${hostInfo.versionCode}）唯一可用的发送路径正是该通道路径。" +
                            "\n启用后只拦截 trpc.o3.mobile_security.* 与 trpc.o3.report.*，" +
                            "命令无法解析的调用原样放行；需要完整重启 QQ。")
                        .setPositiveButton("仍要启用") { _, _ ->
                            channelPathOptIn = true
                            InlineAlertDialogBuilder(activity).setTitle("已记录选择")
                                .setMessage("完整重启 QQ 后生效。功能记录里会写出已安装/未安装的路径与原因；" +
                                    "如需回退，再次点击本条目取消即可。")
                                .setPositiveButton("知道了", null).show()
                        }.setNegativeButton("取消", null).show()
                } else {
                    channelPathOptIn = false
                    InlineAlertDialogBuilder(activity).setTitle("已取消通道路径拦截")
                        .setMessage("完整重启 QQ 后不再安装该拦截。已安装的 Hook 在重启后卸除。")
                        .setPositiveButton("知道了", null).show()
                }
                1 -> InlineAlertDialogBuilder(activity).setTitle("通道路径的风险记录")
                    .setMessage("• 本机 MsfCore 已不存在 sendMessage/sendMessageInner，唯一可挂的是通道路径；\n" +
                        "• 仓库内 ChannelProxyHook 记录：QQ ≥ 9.1.30 上替换该路径与账号异常相关，故默认停用；\n" +
                        "• 本功能只拦截两个风控前缀；命令解析失败一律放行，不伪造返回值；\n" +
                        "• 拦截不等于服务器没有收到上报，也不保证避免检测。")
                    .setPositiveButton("知道了", null).show()
            }
        }.setNegativeButton("关闭", null).show()
    }
}
