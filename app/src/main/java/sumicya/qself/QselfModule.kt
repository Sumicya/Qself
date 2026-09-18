/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

import de.robv.android.xposed.IXposedMod
import de.robv.android.xposed.XposedMod
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfoProvider
import sumicya.qself.xp.ClassicHookEngine

/**
 * Module entry for the classic Xposed API (Xposed / EdXposed / LSPosed 1.x).
 *
 * Not declared in the manifest in v1 (the shipped target is LSPosed 10.x);
 * keep this class for environments that use the classic API — declare it via
 * the `xposed_init` meta-data to activate.
 */
class QselfModule : XposedMod() {

    override fun initApplication(lpparam: IXposedMod.ApplicationLoadPackage) {
        if (lpparam.appName !in HostInfoProvider.HOST_PACKAGES) {
            return
        }
        QLog.i(
            "Qself",
            "classic entry: ${lpparam.appName} proc=${lpparam.processName}",
        )
        Qself.boot(
            BootParam(
                application = lpparam.appLoaded,
                packageName = lpparam.appName,
                processName = lpparam.processName,
                framework = FrameworkKind.LSPosed_1X,
            ),
            QselfFeatures.features,
            ClassicHookEngine(),
        )
    }
}
