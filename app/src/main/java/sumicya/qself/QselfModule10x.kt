/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

import android.app.Application
import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.annotations.XposedApiMin
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.hook.HookEngines
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfoProvider

/**
 * Module entry for the modern libxposed API (LSPosed 10.x and later).
 *
 * The framework discovers this class through
 * `META-INF/xposed/java_init.list` + `META-INF/xposed/module.prop`, and
 * restricts it to `META-INF/xposed/scope.list` (declared statically, so no
 * user-side scope setup is required).
 *
 * The class has one constructor on purpose: the module targets API 101, so
 * the framework always instantiates the no-argument form.
 *
 * The framework is only the *loader* here: which engine actually installs the
 * hooks is decided in [HookEngines] (native LSPlant when available).
 */
class QselfModule10x : XposedModule {

    @XposedApiMin(101)
    constructor() : super()

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName in HostInfoProvider.HOST_PACKAGES) {
            QLog.i("Qself", "package loaded: ${param.packageName}")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in HostInfoProvider.HOST_PACKAGES) {
            return
        }
        val app = currentApplication()
        if (app == null) {
            QLog.w("Qself", "no application available yet; cannot boot")
            return
        }
        val processName = processName(param)
        QLog.i("Qself", "booting ${param.packageName} proc=$processName")
        Qself.boot(
            Qself.BootParam(
                application = app,
                packageName = param.packageName,
                processName = processName,
                framework = FrameworkKind.LSPosed_10X,
            ),
            QselfFeatures.features,
            HookEngines.forModernFramework(this),
        )
    }

    /**
     * [PackageLoadedParam] has no process-name accessor of its own; the
     * application info carries it (falling back to the package name, which is
     * the main process).
     */
    private fun processName(param: PackageReadyParam): String {
        val info: ApplicationInfo = param.applicationInfo
        return info.processName ?: param.packageName
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
