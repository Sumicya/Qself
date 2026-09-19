/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.hook

import android.app.Application
import android.app.Instrumentation
import java.lang.reflect.Method
import sumicya.qself.log.QLog
import sumicya.qself.xp.HookEngine

/**
 * Fires [onApplication] the moment the host creates its [Application].
 *
 * The libxposed lifecycle hands `onPackageReady` over *before* the Application
 * exists (the framework is "ready to create" it), so there is no Application
 * to boot with at that point — and reaching for one through `AppGlobals` or
 * `ActivityThread` is hidden API that Android 9+ blocks anyway.
 *
 * Qself therefore hooks the *public* `Instrumentation#callApplicationOnCreate`
 * and bootstraps itself by patching ART, which is the native path doing
 * load-bearing work instead of the framework. The hook runs before the host's
 * `Application.onCreate`, i.e. exactly as early as the classic
 * `handleLoadPackage` entry point.
 *
 * The caller chooses the engine. The rule of thumb: this hook sits on a core
 * framework method that every app start depends on, so the framework engine
 * (whose exception mode is PROTECTIVE — a throw in our code is logged, never
 * propagated) is the safe choice when it is available. The native engine stays
 * the trigger for environments without any framework hooking API.
 */
object BootHook {

    private const val TAG = "Qself"

    /** The method to hook: `public void callApplicationOnCreate(Application)`. */
    fun target(): Method? = try {
        Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java,
        )
    } catch (t: Throwable) {
        QLog.e(TAG, "Instrumentation#callApplicationOnCreate not found", t)
        null
    }

    /**
     * Installs [onApplication]. Returns false (and logs) when the engine
     * refuses the hook; the caller then has to find another way in.
     *
     * Every failure path here is caught and logged: the hook must never be
     * able to take the host down. Only a *before* hook is installed, so the
     * original method always runs (`skip()` is never called).
     */
    fun install(engine: HookEngine, onApplication: (Application) -> Unit): Boolean {
        val target = target() ?: return false
        return try {
            engine.hook(
                target,
                onBefore = { param ->
                    val application = param.args.firstOrNull() as? Application
                    if (application == null) {
                        QLog.w(TAG, "Application creation: unexpected arguments")
                    } else {
                        try {
                            onApplication(application)
                        } catch (t: Throwable) {
                            // The host keeps booting even if Qself does not.
                            QLog.e(TAG, "boot trigger failed", t)
                        }
                    }
                },
                onAfter = null,
            )
            QLog.i(TAG, "boot hook armed on Instrumentation#callApplicationOnCreate via $engine")
            true
        } catch (t: Throwable) {
            QLog.e(TAG, "could not hook Application creation", t)
            false
        }
    }
}
