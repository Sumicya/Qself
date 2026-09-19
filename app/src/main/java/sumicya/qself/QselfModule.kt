/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfoProvider
import sumicya.qself.xp.ClassicHookEngine

/**
 * Module entry for the classic XposedBridge API (Xposed / EdXposed /
 * LSPosed 1.x).
 *
 * Dormant by default: the shipped APK declares only the modern libxposed
 * entry. Enabling this path means adding `assets/xposed_init` with
 * `sumicya.qself.QselfModule` plus the legacy `xposedmodule` /
 * `xposedminversion` / `xposedscope` manifest metadata — see
 * docs/ARCHITECTURE.md. It stays compiled so the classic engine remains a
 * documented, dependency-free fallback instead of a rewrite.
 */
class QselfModule : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in HostInfoProvider.HOST_PACKAGES) {
            return
        }
        val app = currentApplication()
        if (app == null) {
            QLog.w("Qself", "classic entry: application not available yet")
            return
        }
        QLog.i(
            "Qself",
            "classic entry: ${lpparam.packageName} proc=${lpparam.processName}",
        )
        Qself.boot(
            Qself.BootParam(
                application = app,
                packageName = lpparam.packageName,
                processName = lpparam.processName,
                framework = FrameworkKind.LSPosed_1X,
            ),
            QselfFeatures.features,
            ClassicHookEngine(),
        )
    }

    private fun currentApplication(): Application? {
        return try {
            Class.forName("android.app.AppGlobals")
                .getMethod("getInitialApplication")
                .invoke(null) as? Application
        } catch (t: Throwable) {
            null
        }
    }
}
