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
 * Boots Qself at the moment the host creates its [Application].
 *
 * The libxposed lifecycle hands `onPackageReady` over *before* the Application
 * exists (the framework is "ready to create" it), so there is no Application
 * to boot with at that point — and reaching for one through `AppGlobals` or
 * `ActivityThread` is hidden API that Android 9+ blocks anyway.
 *
 * Qself therefore hooks the *public* `Instrumentation#callApplicationOnCreate`
 * with its own engine: the module bootstraps itself by patching ART, which is
 * the native path doing real load-bearing work instead of a framework API.
 * The hook runs before the host's `Application.onCreate`, i.e. exactly as
 * early as the classic `handleLoadPackage` entry point.
 */
object BootHook {

    private const val TAG = "Qself"

    /** `Instrumentation.callApplicationOnCreate(Application)` — public API since API 1. */
    private fun callApplicationOnCreate(): Method =
        Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java,
        )

    /**
     * Installs [onApplication], which is called with the Application instance
     * before the host's own `Application.onCreate` runs.
     *
     * Returns false (and logs) when the engine refuses the hook; the caller
     * then has to find another way in, because the hook must never be able to
     * take the host down: every failure path here is caught and logged.
     */
    fun install(engine: HookEngine, onApplication: (Application) -> Unit): Boolean {
        val target = try {
            callApplicationOnCreate()
        } catch (t: Throwable) {
            QLog.e(TAG, "Instrumentation#callApplicationOnCreate not found", t)
            return false
        }
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
                            QLog.e(TAG, "boot failed", t)
                        }
                    }
                },
                onAfter = null,
            )
            QLog.i(TAG, "boot hook installed on Instrumentation#callApplicationOnCreate")
            true
        } catch (t: Throwable) {
            QLog.e(TAG, "could not hook Application creation", t)
            false
        }
    }
}
