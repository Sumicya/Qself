/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.annotations.XposedApiMin
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.hook.BootFlags
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
 *  2. [BootHook] arms a hook on every framework call site that hands over the
 *     Application (Instrumentation#callApplicationOnCreate / #newApplication,
 *     AppComponentFactory#instantiateApplication, Application#onCreate), with
 *     the first Activity as the last resort. One single entry point is not
 *     enough: on the device the armed `callApplicationOnCreate` hook never
 *     fired once, and with one trigger the module simply stayed dead.
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

    /** The engine the boot trigger is armed with; reused for the features. */
    private var bootEngine: HookEngine? = null

    /** True once the triggers are armed — `onPackageLoaded` and `onPackageReady`
     *  both call [armBoot], and only the first one may install hooks. */
    private val bootArmed = AtomicBoolean(false)

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName !in HostInfoProvider.HOST_PACKAGES) {
            return
        }
        QLog.i("Qself", "package loaded: ${param.packageName}")
        // The earliest callback there is: documented to run *before* the host's
        // AppComponentFactory is instantiated, i.e. before the Application
        // exists. Arming here is the whole difference between a module that
        // boots and one that armed its trigger after the fact — measured on the
        // device: onPackageReady arrived 0.8 s after this, when the Application
        // was already created, so a trigger armed there never fired.
        armBoot(
            packageName = param.packageName,
            processName = param.packageName,
            classLoader = runCatching { param.defaultClassLoader }.getOrNull(),
            factory = null,
            factoryClass = factoryClassName(param.applicationInfo),
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in HostInfoProvider.HOST_PACKAGES) {
            return
        }
        // First line of the process log on purpose: if a crash report shows
        // this, everything below it happened; if it does not, the crash is
        // earlier (framework injection / module load).
        QLog.i("Qself", "onPackageReady: ${param.packageName}")

        // Diagnostic escape hatch (see BootFlags): with this file present the
        // module logs and does nothing else, which separates "injection alone
        // breaks the host" from "our code breaks the host".
        if (BootFlags.safeMode(dataDirOf(param))) {
            QLog.w("Qself", "safe mode: boot skipped for ${param.packageName}")
            return
        }

        val processName = processName(param)
        armBoot(
            packageName = param.packageName,
            processName = processName,
            classLoader = runCatching { param.classLoader }.getOrNull(),
            factory = appComponentFactory(param),
            factoryClass = factoryClassName(param.applicationInfo),
        )
        if (bootEngine == null && !Qself.isBooted) {
            QLog.w("Qself", "no way to reach the Application in this process; module idle")
        }
    }

    /**
     * Arm the Application triggers once. Called from both lifecycle callbacks:
     * whichever comes first wins, the other is a no-op ([bootArmed]).
     */
    private fun armBoot(
        packageName: String,
        processName: String,
        classLoader: ClassLoader?,
        factory: Any?,
        factoryClass: String?,
    ) {
        if (!bootArmed.compareAndSet(false, true)) {
            return
        }
        val frameworkEngine = LibXposedHookEngine(this)
        val trigger: HookEngine = frameworkEngine
        bootEngine = trigger
        QLog.i("Qself", "boot trigger engine: $trigger")

        val onCreated: (Application) -> Unit = { application ->
            // Never boot inside the hook: hand it to the main looper. The
            // message is posted while `handleBindApplication` is still
            // running, so it is processed before the first Activity launch.
            Handler(Looper.getMainLooper()).post {
                bootHost(application, packageName, processName)
            }
        }

        if (BootHook.install(trigger, factory, factoryClass, classLoader, onCreated)) {
            return
        }
        // No framework hooking: fall back to our own engine as the trigger.
        HookEngines.nativeOrNull()?.let { native ->
            if (BootHook.install(native, factory, factoryClass, classLoader, onCreated)) {
                return
            }
        }
        // Everything else failed: the Application may already exist (a late
        // lifecycle callback). Qself.boot() is idempotent, so trying is safe.
        initialApplication()?.let { application ->
            bootHost(application, packageName, processName)
        }
    }

    /** `ApplicationInfo#appComponentFactory`, the class name of the host's factory. */
    private fun factoryClassName(info: ApplicationInfo?): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info?.appComponentFactory else null
    } catch (t: Throwable) {
        null
    }

    /**
     * The real process name (`com.tencent.mobileqq:MSF`, `:qzone`, ...).
     *
     * Neither lifecycle param carries it, and the boot trigger can fire from
     * either callback, so it is read from the kernel instead of guessed: a
     * process is exactly `/proc/self/cmdline`. Guessing "main process" here
     * would install MAIN-only features inside subprocesses.
     */
    private fun selfProcessName(): String? = try {
        java.io.File("/proc/self/cmdline").readBytes()
            .let { bytes -> String(bytes, Charsets.UTF_8).trimEnd('\u0000') }
            .ifEmpty { null }
    } catch (t: Throwable) {
        null
    }

    /** The heavy part, off the hook callback and off the bind path. */
    private fun bootHost(application: Application, packageName: String, processName: String) {
        if (Qself.isBooted) {
            return
        }
        val actualProcess = selfProcessName() ?: processName
        val dataDir = application.dataDir?.absolutePath
        val useNative = BootFlags.useNative(dataDir)
        val noFeatures = BootFlags.noFeatures(dataDir)
        val engine = bootEngine ?: HookEngines.forModernFramework(this, preferNative = useNative)
        val features = if (noFeatures) emptyList() else QselfFeatures.features
        QLog.i(
            "Qself",
            "booting $packageName proc=$actualProcess engine=$engine " +
                "(native ${if (useNative) "opted in" else "off"}, " +
                "features ${if (noFeatures) "suppressed by flag" else "${features.size}"})",
        )
        try {
            Qself.boot(
                Qself.BootParam(
                    application = application,
                    packageName = packageName,
                    processName = actualProcess,
                    framework = FrameworkKind.LSPosed_10X,
                ),
                features,
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
    /**
     * The factory LSPosed is about to use for the Application (Android 10+).
     * Passing it in is what lets [BootHook] hook the real creation site
     * instead of assuming `Instrumentation` is the only way in.
     */
    private fun appComponentFactory(param: PackageReadyParam): Any? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) param.appComponentFactory else null
    } catch (t: Throwable) {
        null
    }

    /** The host's data dir (`/data/data/<pkg>`), used for the diagnostic flags. */
    private fun dataDirOf(param: PackageReadyParam): String? = try {
        param.applicationInfo.dataDir
    } catch (t: Throwable) {
        null
    }

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
