/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.chat

import io.github.qauxv.bridge.AppRuntimeHelper
import io.github.qauxv.util.Log
import hostInfo
import java.lang.reflect.Method

/** Group-management inventory only. Unverified write operations are deliberately disabled. */
object GroupAdminBridge {

    private const val TAG = "GroupAdminBridge"

    private var sInventoryDumped = false
    private var lastInventory: String? = null

    // Long::class.javaPrimitiveType is Class<Long>? - unusable in Class<*> varargs
    private val PRIM_LONG: Class<*> = java.lang.Long.TYPE
    private val PRIM_INT: Class<*> = java.lang.Integer.TYPE

    /** Pure: does this method look like an implementation of the action? */
    @JvmStatic
    fun matchesAction(m: Method, keyword: String, paramCount: Int, vararg paramTypes: Class<*>): Boolean {
        if (!m.name.lowercase().contains(keyword)) return false
        val ps = m.parameterTypes
        if (ps.size != paramCount) return false
        paramTypes.forEachIndexed { i, c ->
            val p = ps[i]
            if (c == PRIM_LONG && p != PRIM_LONG && p != java.lang.Long::class.java) return false
            if (c == String::class.java && p != String::class.java) return false
            if (c == PRIM_INT && p != PRIM_INT && p != java.lang.Integer::class.java) return false
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun kernelService(): Any? = runCatching {
        val app = AppRuntimeHelper.getAppRuntime() ?: error("AppRuntime unavailable")
        val kIKernelService = io.github.qauxv.util.Initiator
            .loadClass("com.tencent.qqnt.kernel.api.IKernelService") as Class<mqq.app.api.IRuntimeService>
        app.getRuntimeService(kIKernelService, "")
    }.onFailure { Log.e("$TAG: getKernelService failed: $it") }.getOrNull()

    private fun groupService(): Any? = runCatching {
        val kernelService = kernelService() ?: error("IKernelService unavailable")
        kernelService.javaClass.getMethod("getGroupService").invoke(kernelService)
    }.onFailure { Log.e("$TAG: getGroupService failed: $it") }.getOrNull()

    private fun msgService(): Any? = runCatching {
        val kernelService = kernelService() ?: error("IKernelService unavailable")
        kernelService.javaClass.getMethod("getMsgService").invoke(kernelService)
    }.onFailure { Log.e("$TAG: getMsgService failed: $it") }.getOrNull()

    /**
     * One-shot candidate inventory. Writes to DiagLog and keeps the full
     * report for [exportInventory] - the on-device answer to "what are the
     * real method names this build".
     */
    @JvmStatic
    fun dumpInventoryOnce(): String {
        if (sInventoryDumped) return lastInventory ?: ""
        sInventoryDumped = true
        val report = buildInventoryReport()
        lastInventory = report
        report.lineSequence().forEach { sumicya.qself.feature.dev.DiagLog.w("$TAG inventory $it") }
        return report
    }

    /** Full parameter/return type names, so the dump pins exact signatures. */
    private fun describeMethod(m: java.lang.reflect.Method): String =
        "${m.returnType.name} ${m.name}(${m.parameterTypes.joinToString(",") { it.name }})"

    private fun buildInventoryReport(): String {
        val kw = listOf("shutup", "mute", "kick", "remove", "card", "revoke", "recall", "member")
        val sb = StringBuilder()
        sb.appendLine("Qself group-admin inventory")
        sb.appendLine("host: ${hostInfo.versionName} (${hostInfo.versionCode})")
        sb.appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        val services = listOf("GroupService" to groupService(), "MsgService" to msgService())
        var hits = 0
        for ((label, svc) in services) {
            sb.appendLine("== $label ==")
            if (svc == null) {
                sbAppendUnavailable(sb, label)
                continue
            }
            val iface = svc.javaClass.interfaces.firstOrNull()?.name ?: svc.javaClass.name
            sb.appendLine("impl-iface: $iface")
            val matched = svc.javaClass.methods
                .filter { m -> kw.any { m.name.lowercase().contains(it) } }
                .sortedBy { it.name }
            if (matched.isEmpty()) {
                sbAppendUnavailable(sb, label)
            } else {
                matched.forEach { sb.appendLine("  ${describeMethod(it)}") }
                hits += matched.size
            }
        }
        sb.appendLine("matched: $hits")
        return sb.toString()
    }

    private fun sbAppendUnavailable(sb: StringBuilder, label: String) {
        sb.appendLine("  <unavailable: $label could not be resolved on this host>")
    }

    /**
     * Share the candidate inventory through the system share sheet. No root,
     * no storage permission: the report rides a plain ACTION_SEND intent.
     * Returns false (and logs) when neither share nor clipboard is possible.
     */
    @JvmStatic
    fun exportInventory(context: android.content.Context): Boolean {
        val report = dumpInventoryOnce().ifBlank { buildInventoryReport().also { lastInventory = it } }
        return try {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Qself group-admin inventory")
                putExtra(android.content.Intent.EXTRA_TEXT, report)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(android.content.Intent.createChooser(send, "导出群管理接口库存")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            Log.e("$TAG: inventory share failed: $t")
            try {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("qself-inventory", report))
                io.github.qauxv.util.Toasts.info(context, "接口库存已复制到剪贴板")
                true
            } catch (t2: Throwable) {
                Log.e("$TAG: inventory clipboard failed: $t2")
                false
            }
        }
    }

    // Exact descriptors/permissions/callback semantics are not verified for this host.
    // Diagnostic name/shape matching MUST NOT authorize a group-management write.
    // These stay null until an exported inventory pins the exact signatures.
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun muteMember(peerUid: String, memberUid: String, durationSec: Long): String? = null

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun kickMember(peerUid: String, memberUid: String): String? = null

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun setMemberCard(peerUid: String, memberUid: String, card: String): String? = null

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun revokeMessage(msgId: Long, msgSeq: Long): String? = null
}
