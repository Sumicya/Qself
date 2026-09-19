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
 * Hot patch (qfix) switch for NT QQ.
 *
 * The pre-NT `rfix` pipeline is gone in 9.x; what remains is `qfix`, and every
 * signature below was harvested from the device (QQ 9.2.10, 37 dex images) with
 * `tools/nt-scan` — see docs/NT-ADAPTATION.md. Nothing here is guessed.
 *
 * Two layers, because patch application happens around `attachBaseContext`,
 * which runs *before* the module's boot hook arms:
 *
 *  1. the pipeline never runs — `PatchRedirectCenter.apply` and every
 *     `Relax.apply*` overload (including the native `relax`) report their
 *     success code without applying anything;
 *  2. patches that were already applied are inert — the redirector lookup that
 *     every patched method performs returns null, which is exactly what an
 *     unpatched method sees, so those methods execute their original body.
 */
@QselfFeature(
    id = "misc.disable_hot_patch_nt",
    name = "禁用热补丁（NT）",
    summary = "拦截 QQ 9.x 的 qfix 补丁应用，并让已装补丁失效",
    category = "misc",
    experimental = true,
)
object NtHotPatch : SwitchFeature() {

    private const val TAG = "NtHotPatch"

    override val id: String = "misc.disable_hot_patch_nt"
    override val name: String = "禁用热补丁（NT）"
    override val summary: String = "拦截 QQ 9.x 的 qfix 补丁应用，并让已装补丁失效"
    override val category: FeatureCategory = FeatureCategory.MISC
    override val experimental: Boolean = true
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN, ProcessKind.MSF)
    override val defaultEnabled: Boolean = false
    override val hostGeneration: HostGeneration = HostGeneration.NT

    override fun initOnce(ctx: FeatureContext): Boolean {
        val host = ctx.host
        val center = host.resolve("com.tencent.mobileqq.qfix.redirect.PatchRedirectCenter")
        val relax = host.resolve("com.tencent.mobileqq.qfix.Relax")
        if (center == null && relax == null) {
            QLog.w(TAG, "qfix not present — wrong host generation?")
            return false
        }

        var hooks = 0

        if (center != null) {
            val success = NtHooks.constant(host, center, "CODE_SUCCESS", 0)
            if (NtHooks.replace(
                    this,
                    host,
                    center,
                    "apply",
                    success,
                    Context::class.java,
                    String::class.java,
                    String::class.java,
                )
            ) {
                hooks++
            }
            // Every generated patch hook calls one of these two; null means
            // "no patch for this id", i.e. the original body runs.
            if (NtHooks.replace(this, host, center, "getRedirector", null, Integer.TYPE)) hooks++
            if (NtHooks.replace(this, host, center, "getRedirector", null, Integer.TYPE, java.lang.Short.TYPE)) {
                hooks++
            }
        }

        if (relax != null) {
            val success = NtHooks.constant(host, relax, "K_APPLY_SUCCESS", 0)
            hooks += NtHooks.replaceAll(this, host, relax, "apply", success)
            hooks += NtHooks.replaceAll(this, host, relax, "applyPatch", success)
            hooks += NtHooks.replaceAll(this, host, relax, "applyInternal", success)
            // The native loader underneath all of the above — last Java-level
            // choke point, so a call path we did not anticipate still stops here.
            hooks += NtHooks.replaceAll(this, host, relax, "relax", success)
        }

        if (hooks == 0) {
            QLog.w(TAG, "no qfix hook could be installed (classes: center=$center relax=$relax)")
            return false
        }
        QLog.i(TAG, "installed $hooks hooks (apply blocked, redirectors inert)")
        return true
    }
}
