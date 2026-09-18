/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.xp

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import sumicya.qself.Qself
import sumicya.qself.feature.QselfFeature
import sumicya.qself.log.QLog

/**
 * The only hooking entry point features should use.
 *
 * [beforeIfEnabled]/[afterIfEnabled] gate the real handler on the feature's
 * switch at hook install time; toggling later re-applies on the next host
 * process start (v1 contract — see [sumicya.qself.config.SettingsBridge]).
 */
object Hooks {

    private val engine: HookEngine
        get() = Qself.engine

    /** Hook whose handler runs only if [feature] is enabled at install time. */
    fun beforeIfEnabled(
        feature: QselfFeature,
        executable: Executable,
        handler: (HookEngine.HookParam) -> Unit,
    ): HookEngine.Handle {
        if (!feature.isEnabled) {
            return NoopHandle
        }
        return before(executable, handler)
    }

    fun before(
        executable: Executable,
        handler: (HookEngine.HookParam) -> Unit,
        priority: Int = HookEngine.PRIORITY_DEFAULT,
    ): HookEngine.Handle = engine.hook(executable, { param -> safe(executable, handler, param) }, null, priority)

    /** Hook whose handler runs only if [feature] is enabled at install time. */
    fun afterIfEnabled(
        feature: QselfFeature,
        executable: Executable,
        handler: (HookEngine.HookParam) -> Unit,
    ): HookEngine.Handle {
        if (!feature.isEnabled) {
            return NoopHandle
        }
        return after(executable, handler)
    }

    fun after(
        executable: Executable,
        handler: (HookEngine.HookParam) -> Unit,
        priority: Int = HookEngine.PRIORITY_DEFAULT,
    ): HookEngine.Handle = engine.hook(executable, null, { param -> safe(executable, handler, param) }, priority)

    /** Hook every declared constructor of [cls]. */
    fun allConstructorsIfEnabled(
        feature: QselfFeature,
        cls: Class<*>,
        handler: (HookEngine.HookParam) -> Unit,
    ): List<HookEngine.Handle> {
        if (!feature.isEnabled) return emptyList()
        return allConstructors(cls, handler)
    }

    fun allConstructors(
        cls: Class<*>,
        handler: (HookEngine.HookParam) -> Unit,
    ): List<HookEngine.Handle> {
        val handles = ArrayList<HookEngine.Handle>(cls.declaredConstructors.size)
        for (c in cls.declaredConstructors) {
            handles += before(c, handler)
        }
        return handles
    }

    /** Hook every declared method of [cls] named [name]. */
    fun allMethodsIfEnabled(
        feature: QselfFeature,
        cls: Class<*>,
        name: String,
        handler: (HookEngine.HookParam) -> Unit,
    ): List<HookEngine.Handle> {
        if (!feature.isEnabled) return emptyList()
        return allMethods(cls, name, handler)
    }

    fun allMethods(
        cls: Class<*>,
        name: String,
        handler: (HookEngine.HookParam) -> Unit,
    ): List<HookEngine.Handle> {
        val handles = ArrayList<HookEngine.Handle>()
        for (m in cls.declaredMethods) {
            if (m.name == name) {
                handles += before(m, handler)
            }
        }
        return handles
    }

    /** Convenience: resolve a method through the host cache and hook it. */
    fun method(
        cls: Class<*>,
        name: String,
        vararg paramTypes: Class<*>,
    ): Method = Qself.host.requireMethod(cls, name, *paramTypes)

    private fun safe(
        executable: Executable,
        handler: (HookEngine.HookParam) -> Unit,
        param: HookEngine.HookParam,
    ) {
        try {
            handler(param)
        } catch (t: Throwable) {
            QLog.e("Hook", "handler failed on $executable", t)
        }
    }

    private object NoopHandle : HookEngine.Handle {
        override fun unhook() = Unit
    }
}
