/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.nt

import android.content.Context
import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.log.QLog
import sumicya.qself.util.HostGeneration

/**
 * Crash report switch for NT QQ.
 *
 * QQ 9.x kept the Bugly/`feedback.eup` stack, so the harvested signatures in
 * docs/NT-ADAPTATION.md are stable class names — unlike the pre-NT feature,
 * which went through `QQCrashReportManager` and only ever covered the dialog.
 *
 * Four layers, each of which alone is enough to stop a report:
 * init (never start the reporter), post (drop the report), upload (drop the
 * queued batch) and the native/ANR handler registration. Reports that mention
 * the module are the reason this defaults to on for the classic feature; here
 * it is opt-in until it has been seen working on the device.
 */
@QselfFeature(
    id = "misc.disable_crash_report_nt",
    name = "禁用崩溃上报（NT）",
    summary = "阻断 QQ 9.x 的 Bugly/feedback 崩溃上报与 native 处理器注册",
    category = "misc",
    experimental = true,
)
object NtCrashReport : SwitchFeature() {

    private const val TAG = "NtCrashReport"

    /**
     * Flipping this to false keeps the post/upload/native layers and only lets
     * the reporter initialise. That is the first thing to try if a future QQ
     * build turns out to need a live Bugly instance.
     */
    private const val BLOCK_INIT = true

    override val id: String = "misc.disable_crash_report_nt"
    override val name: String = "禁用崩溃上报（NT）"
    override val summary: String = "阻断 QQ 9.x 的 Bugly/feedback 崩溃上报与 native 处理器注册"
    override val category: FeatureCategory = FeatureCategory.MISC
    override val experimental: Boolean = true
    override val targetProcesses: Set<ProcessKind> = ProcessKind.entries.toSet()
    override val defaultEnabled: Boolean = false
    override val hostGeneration: HostGeneration = HostGeneration.NT

    override fun initOnce(ctx: FeatureContext): Boolean {
        val host = ctx.host

        val report = host.resolve("com.tencent.feedback.eup.CrashReport")
        val strategy = host.resolve("com.tencent.feedback.eup.CrashStrategyBean")
        val bugly = host.resolve("com.tencent.bugly.library.Bugly")
        val builder = host.resolve("com.tencent.bugly.library.BuglyBuilder")
        val inner = host.resolve("com.tencent.bugly.crashreport.inner.InnerApi")
        val h5 = host.resolve("com.tencent.bugly.crashreport.crash.h5.H5JavaScriptInterface")
        val native = host.resolve("com.tencent.feedback.eup.jni.NativeExceptionUpload")
        val nativeHandler =
            host.resolve("com.tencent.bugly.crashreport.crash.jni.NativeCrashHandler")
        if (report == null && bugly == null && native == null) {
            QLog.w(TAG, "bugly/feedback not present — wrong host generation?")
            return false
        }

        val context = Context::class.java
        val string = String::class.java
        val bool = java.lang.Boolean.TYPE
        val int = Integer.TYPE
        val long = java.lang.Long.TYPE
        val thread = Thread::class.java
        val throwable = Throwable::class.java
        val map = java.util.Map::class.java
        val bytes = ByteArray::class.java

        /** Replace the result of `cls#name(params)`; 1 when the hook went in. */
        fun block(name: String, cls: Class<*>?, result: Any?, vararg params: Class<*>): Int =
            if (NtHooks.replace(this, host, cls, name, result, *params)) 1 else 0

        /** A one-boolean setter forced to false. */
        fun off(name: String, cls: Class<*>?): Int =
            if (NtHooks.forceFalse(this, host, cls, name)) 1 else 0

        var hooks = 0

        if (BLOCK_INIT) {
            hooks += block("initCrashReport", report, null, context, string, bool)
            if (strategy != null) {
                hooks += block("initCrashReport", report, null, context, string, bool, strategy)
                hooks += block("initCrashReport", report, null, context, string, bool, strategy, long)
            }
            if (builder != null) {
                // Pretend the Bugly init succeeded: an error return would only
                // make QQ retry or log more.
                hooks += block("init", bugly, true, context, builder)
                hooks += block("init", bugly, true, context, builder, bool)
            }
        }

        // Drop every report that is handed over, whichever entry it came from.
        hooks += block("postException", report, null, int, string, string, string, map)
        hooks += block("postException", report, null, thread, int, string, string, string, map)
        hooks += block("postException", bugly, null, int, string, string, string, map)
        hooks += block("postException", bugly, null, thread, int, string, string, string, map)
        hooks += block("postH5CrashAsync", inner, null, thread, string, string, string, map)
        hooks += block("postCocos2dxCrashAsync", inner, null, int, string, string, string, map)
        hooks += block("postU3dCrashAsync", inner, null, string, string, string, map)
        hooks += block("reportJSException", h5, null, string)

        // false = "not handled", so the caller's own error path stays intact.
        hooks += block("handleCatchException", report, false, thread, throwable, string, bytes)
        hooks += block("handleCatchException", report, false, thread, throwable, string, bytes, bool)
        hooks += block("handleCatchException", bugly, false, thread, throwable, string, bytes)
        hooks += block("handleCatchException", bugly, false, thread, throwable, string, bytes, bool)

        // Anything already queued never leaves the device.
        hooks += block("doUploadExceptionDatas", report, false)
        hooks += block("needUploadCrash", report, false)
        hooks += block("uploadUserInfo", report, null)
        hooks += block("triggerUserInfoUpload", report, null)

        // Native/ANR side: no handler registration, no re-registration.
        hooks += block("registNativeExceptionHandler", native, false, string, string, int)
        hooks += block("registNativeExceptionHandler2", native, null, string, string, int, int)
        hooks += off("enableHandler", native)
        hooks += off("setShouldHandleInJava", nativeHandler)
        hooks += block("reRegisterNativeHandler", nativeHandler, null, bool)
        hooks += block("reRegisterANRHandler", nativeHandler, null, bool)

        if (hooks == 0) {
            QLog.w(TAG, "no crash-report hook could be installed")
            return false
        }
        QLog.i(TAG, "installed $hooks hooks (init=$BLOCK_INIT, posts dropped, native off)")
        return true
    }
}
