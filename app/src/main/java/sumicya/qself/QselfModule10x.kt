/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.annotations.XposedApiMin
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.hook.BootHook
import sumicya.qself.hook.HookEngines
import sumicya.qself.libxposed.LibXposedHookEngine
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfoProvider
import sumicya.qself.xp.HookEngine

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
 * Boot sequence (see docs/ARCHITECTURE.md):
 *  1. `onPackageReady` — the framework is only the *loader*; no Application
 *     exists yet, so nothing heavy happens here.
 *  2. [BootHook] arms a before-hook on `Instrumentation#callApplicationOnCreate`
 *     (the framework engine when available: it cannot propagate our failures
 *     into the host; the native engine otherwise).
 *  3. That hook captures the Application and posts the real boot to the main
 *     looper, so heavy work (Settings/Host/features/LSPlant) never runs inside
 *     a hook callback for a core framework method, and the host's own
 *     `Application.onCreate` stays in front of us.
 *  4. [Qself.boot] installs the feature hooks with [HookEngines] — native
 *     LSPlant when it is available, framework engine as the fallback.
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
        // First line of the process log on purpose: if a crash report shows
        // this, everything below it happened; if it does not, the crash is
        // earlier (framework injection / module load).
        QLog.i("Qself", "onPackageReady: ${param.packageName}")

        val processName = processName(param)
        val frameworkEngine = LibXposedHookEngine(this)
        val trigger: HookEngine = frameworkEngine
        QLog.i("Qself", "boot trigger engine: $trigger")

        val onCreated: (Application) -> Unit = { application ->
            // Never boot inside the hook: hand it to the main looper. The
            // message is posted while `handleBindApplication` is still
            // running, so it is processed before the first Activity launch.
            Handler(Looper.getMainLooper()).post {
                bootHost(application, param.packageName, processName)
            }
        }

        var armed = BootHook.install(trigger, onCreated)
        if (!armed) {
            // No framework hooking: fall back to our own engine as the trigger.
            HookEngines.nativeOrNull()?.let { native ->
                armed = BootHook.install(native, onCreated)
            }
        }

        if (!armed) {
            // A framework may deliver this callback late (Application already
            // created). Qself.boot() is idempotent, so trying both is safe.
            initialApplication()?.let { application ->
                bootHost(application, param.packageName, processName)
            }
        }
        if (!armed && !Qself.isBooted) {
            QLog.w("Qself", "no way to reach the Application in this process; module idle")
        }
    }

    /** The heavy part, off the hook callback and off the bind path. */
    private fun bootHost(application: Application, packageName: String, processName: String) {
        if (Qself.isBooted) {
            return
        }
        val engine = HookEngines.forModernFramework(this)
        QLog.i("Qself", "booting $packageName proc=$processName engine=$engine")
        try {
            Qself.boot(
                Qself.BootParam(
                    application = application,
                    packageName = packageName,
                    processName = processName,
                    framework = FrameworkKind.LSPosed_10X,
                ),
                QselfFeatures.features,
                engine,
            )
        } catch (t: Throwable) {
            // A broken boot must not take the host down.
            QLog.e("Qself", "boot failed; module stays idle in this process", t)
        }
    }

    /**
     * [PackageLoadedParam] has no process-name accessor of its own; the
     * application info carries it (falling back to the package name, which is
     * the main process).
     */
    private fun processName(param: PackageReadyParam): String {
        return try {
            val info: ApplicationInfo = param.applicationInfo
            info.processName ?: param.packageName
        } catch (t: Throwable) {
            param.packageName
        }
    }

    /**
     * Fallback only. `AppGlobals` is hidden API (Android 9+ blocks it for
     * normal apps), and at this point in the lifecycle the Application usually
     * does not exist yet — [BootHook] is the path that actually works.
     */
    private fun initialApplication(): Application? {
        return try {
            Class.forName("android.app.AppGlobals")
                .getMethod("getInitialApplication")
                .invoke(null) as? Application
        } catch (t: Throwable) {
            null
        }
    }
}
