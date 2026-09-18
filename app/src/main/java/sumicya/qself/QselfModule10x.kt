/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

import android.app.Application
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.annotations.XposedApiExact
import io.github.libxposed.api.annotations.XposedApiMin
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfoProvider
import sumicya.qself.xp.NoopHookEngine

/**
 * Module entry for LSPosed 10.x (libxposed API).
 *
 * The framework discovers this class through `META-INF/xposed/module.prop`.
 *
 * v1 limitation: the 10.x Java-level hooking engine lands in v1.1, so the
 * module boots with a [NoopHookEngine] — it reports diagnostics and skips
 * feature installation instead of crashing the host.
 */
@Keep
class QselfModule10x : XposedModule {

    @XposedApiExact(100)
    constructor(
        base: XposedInterface,
        param: ModuleLoadedParam,
    ) : super(base, param)

    @RequiresApi(26)
    @XposedApiMin(101)
    constructor() : super()

    @XposedApiExact(100)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName in HostInfoProvider.HOST_PACKAGES) {
            QLog.i("Qself", "10x: package loaded ${param.packageName} proc=${param.processName}")
        }
    }

    @RequiresApi(26)
    @XposedApiMin(101)
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in HostInfoProvider.HOST_PACKAGES) {
            return
        }
        val app = currentApplication()
        if (app == null) {
            QLog.w("Qself", "10x: no application available yet; cannot boot")
            return
        }
        QLog.i("Qself", "10x: booting for ${param.packageName} proc=${param.processName}")
        Qself.boot(
            BootParam(
                application = app,
                packageName = param.packageName,
                processName = param.processName,
                framework = FrameworkKind.LSPosed_10X,
            ),
            QselfFeatures.features,
            NoopHookEngine("LSPosed 10.x hook engine ships in v1.1"),
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
