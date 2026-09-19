/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import sumicya.qself.log.QLog
import sumicya.qself.xp.HookEngine

/**
 * Fires [onApplication] the first moment the host's [Application] can be
 * reached, whichever way works on this framework.
 *
 * Why more than one trigger: the lifecycle hands `onPackageReady` over with the
 * host's classloader, and the module then has to *wait* for the Application.
 * Arming a single hook is a bet that this hook is still ahead of the framework
 * — on the device that bet lost. The log showed
 * `boot hook armed on Instrumentation#callApplicationOnCreate` three times in
 * a row with **not one line after it**: armed, but the method never reached
 * the handler. A framework is free to dispatch `onPackageReady` late in
 * `handleBindApplication` (that call has then already happened), and a host is
 * free to override the hooked form without calling it. Either way the module
 * stayed dead and nothing else could work.
 *
 * So the boot arms **every** framework call site that hands over the
 * Application, and logs the one that actually fires:
 *
 *  1. `Instrumentation#callApplicationOnCreate(Application)` — the classic
 *     entry point, before the host's `Application.onCreate`.
 *  2. `Instrumentation#newApplication(ClassLoader, String, Context)` and
 *     `Instrumentation#newApplication(Class, Context)` — where the instance is
 *     created (the *result* is the Application).
 *  3. `AppComponentFactory#instantiateApplication(ClassLoader, String)` — on
 *     Android 10+ `newApplication` delegates to the factory that `onPackageReady`
 *     already handed us, so this fires even when the host installs its own.
 *  4. `Application#onCreate` — belt and braces; every real Application calls
 *     `super.onCreate()`.
 *
 * If none of them fires the module is still not dead: the first Activity always
 * carries the Application (`Activity#getApplication`), so
 * `Instrumentation#callActivityOnCreate` is the last resort. That is *late* for
 * the startup-time features (patch, crash report and upgrade check all run
 * before the first window) and the log says so — but the settings entry and
 * every lazy hook keep working, and a log that names the trigger beats a module
 * that does nothing and says nothing.
 *
 * After-handlers only *read* the result. Replacing it (or returning null) is
 * what crashes hosts; that is a rule Qself keeps everywhere.
 */
object BootHook {

    private const val TAG = "Qself"

    /** Installs the triggers; returns false when none could be armed. */
    fun install(
        engine: HookEngine,
        /**
         * The `AppComponentFactory` instance from `onPackageReady`: the object
         * the framework will use to build the Application on Android 10+.
         */
        appComponentFactory: Any? = null,
        /** `ApplicationInfo#appComponentFactory`: the class name, available
         *  *before* the factory instance exists (i.e. at package-loaded time). */
        appComponentFactoryClass: String? = null,
        /** The classloader the factory class name is resolved against. */
        classLoader: ClassLoader? = null,
        onApplication: (Application) -> Unit,
    ): Boolean {
        val fired = AtomicBoolean(false)
        val firstActivityLogged = AtomicBoolean(false)
        val armed = ArrayList<String>(6)

        fun fire(application: Application, via: String) {
            if (!fired.compareAndSet(false, true)) {
                return
            }
            QLog.i(TAG, "boot trigger fired: $via")
            try {
                onApplication(application)
            } catch (t: Throwable) {
                // The host keeps booting even if Qself does not.
                QLog.e(TAG, "boot trigger failed", t)
            }
        }

        fun arm(
            desc: String,
            target: Method?,
            onBefore: ((HookEngine.HookParam) -> Unit)? = null,
            onAfter: ((HookEngine.HookParam) -> Unit)? = null,
        ) {
            if (target == null) {
                QLog.d(TAG, "boot target not available: $desc")
                return
            }
            // A misbehaving handler must never reach the host.
            val safeBefore = onBefore?.let { handler ->
                { param: HookEngine.HookParam ->
                    try {
                        handler(param)
                    } catch (t: Throwable) {
                        QLog.e(TAG, "boot handler failed on $desc", t)
                    }
                }
            }
            val safeAfter = onAfter?.let { handler ->
                { param: HookEngine.HookParam ->
                    try {
                        handler(param)
                    } catch (t: Throwable) {
                        QLog.e(TAG, "boot handler failed on $desc", t)
                    }
                }
            }
            try {
                engine.hook(target, safeBefore, safeAfter)
                armed += desc
                QLog.i(TAG, "boot hook armed on $desc via $engine")
            } catch (t: Throwable) {
                QLog.w(TAG, "could not arm $desc", t)
            }
        }

        // 1. Classic entry point: Application exists, its onCreate has not run.
        arm(
            "Instrumentation#callApplicationOnCreate",
            instrument("callApplicationOnCreate", Application::class.java),
            onBefore = { param ->
                val application = param.args.firstOrNull() as? Application
                if (application != null) {
                    fire(application, "Instrumentation#callApplicationOnCreate")
                }
            },
        )

        // 2. Where the instance is created. Read the result; never replace it.
        arm(
            "Instrumentation#newApplication(ClassLoader,String,Context)",
            instrument("newApplication", ClassLoader::class.java, String::class.java, Context::class.java),
            onAfter = { param ->
                val application = param.result as? Application
                if (application != null) {
                    fire(application, "Instrumentation#newApplication(classloader,name,context)")
                }
            },
        )
        arm(
            "Instrumentation#newApplication(Class,Context)",
            instrument("newApplication", Class::class.java, Context::class.java),
            onAfter = { param ->
                val application = param.result as? Application
                if (application != null) {
                    fire(application, "Instrumentation#newApplication(class,context)")
                }
            },
        )

        // 3. The factory that actually builds it (Android 10+).
        arm(
            "AppComponentFactory#instantiateApplication",
            factoryMethod(appComponentFactory, appComponentFactoryClass, classLoader),
            onAfter = { param ->
                val application = param.result as? Application
                if (application != null) {
                    fire(application, "AppComponentFactory#instantiateApplication")
                }
            },
        )

        // 4. The Application's own onCreate — every real host calls super.
        arm(
            "Application#onCreate",
            runCatching { Application::class.java.getDeclaredMethod("onCreate") }.getOrNull(),
            onBefore = { param ->
                val application = param.thisObject as? Application
                if (application != null) {
                    fire(application, "Application#onCreate")
                }
            },
        )

        // 5. Liveness probe and last resort in one: the first Activity.
        arm(
            "Instrumentation#callActivityOnCreate (probe)",
            instrument("callActivityOnCreate", Activity::class.java, Bundle::class.java),
            onBefore = { param ->
                val activity = param.args.firstOrNull() as? Activity
                if (activity != null) {
                    if (firstActivityLogged.compareAndSet(false, true)) {
                        // Proves the hook engine is alive even when every trigger
                        // above missed — the one thing the last round could not tell.
                        QLog.i(
                            TAG,
                            "boot probe: first activity ${activity.javaClass.name} " +
                                "(application trigger fired=${fired.get()})",
                        )
                    }
                    if (!fired.get()) {
                        fire(activity.application, "late: first Activity#onCreate")
                    }
                }
            },
        )

        if (armed.isEmpty()) {
            QLog.w(TAG, "no boot trigger could be armed on $engine")
        } else {
            QLog.i(TAG, "boot hooks armed (${armed.size}): ${armed.joinToString(", ")}")
        }
        return armed.isNotEmpty()
    }

    /** `Instrumentation#<name>(<paramTypes>)`, or null when it does not exist. */
    fun instrument(name: String, vararg paramTypes: Class<*>): Method? = try {
        Instrumentation::class.java.getDeclaredMethod(name, *paramTypes)
    } catch (t: Throwable) {
        null
    }

    /**
     * The factory's own `instantiateApplication`, else the framework base class
     * — a host subclass that overrides it is hooked on the subclass instead.
     */
    private fun factoryMethod(factory: Any?, factoryClassName: String?, classLoader: ClassLoader?): Method? {
        val classes = ArrayList<Class<*>>(3)
        factory?.let { classes += it.javaClass }
        factoryClassName?.let { name ->
            runCatching { classes += Class.forName(name, false, classLoader) }
        }
        runCatching { classes += Class.forName("android.app.AppComponentFactory") }
        for (cls in classes) {
            val method = runCatching {
                cls.getDeclaredMethod("instantiateApplication", ClassLoader::class.java, String::class.java)
            }.getOrNull()
            if (method != null) {
                return method
            }
        }
        return null
    }
}
