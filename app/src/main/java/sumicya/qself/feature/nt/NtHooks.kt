/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.nt

import sumicya.qself.feature.QselfFeature
import sumicya.qself.host.Host
import sumicya.qself.xp.Hooks

/**
 * The three hook shapes NT features need.
 *
 * Every NT target is harvested from the device's own dex with
 * `tools/nt-scan` (see docs/NT-ADAPTATION.md) and resolved through [Host], so
 * a missing class or method is a logged miss instead of a failed feature.
 */
internal object NtHooks {

    /** Hook `cls#name(params)` and replace its result. */
    fun replace(
        feature: QselfFeature,
        host: Host,
        cls: Class<*>?,
        name: String,
        result: Any?,
        vararg params: Class<*>,
    ): Boolean {
        if (cls == null) return false
        val method = host.method(cls, name, *params) ?: return false
        Hooks.beforeIfEnabled(feature, method) { it.skip(result) }
        return true
    }

    /**
     * Resolve [className] first, then hook `name(params)` to return [result].
     * Returns false when either the class or that exact signature is missing,
     * which is the normal case for a QQ build the feature was not written for.
     */
    fun replaceIn(
        feature: QselfFeature,
        host: Host,
        className: String,
        name: String,
        result: Any?,
        vararg params: Class<*>,
    ): Boolean {
        val cls = host.resolve(className) ?: return false
        return replace(feature, host, cls, name, result, *params)
    }

    /** Hook every declared method called [name] (overloads differ only by args). */
    fun replaceAll(
        feature: QselfFeature,
        host: Host,
        cls: Class<*>?,
        name: String,
        result: Any?,
    ): Int {
        if (cls == null) return 0
        var hooked = 0
        for (method in host.methods(cls, name)) {
            Hooks.beforeIfEnabled(feature, method) { it.skip(result) }
            hooked++
        }
        return hooked
    }

    /** Hook a one-boolean setter and force the argument to false. */
    fun forceFalse(feature: QselfFeature, host: Host, cls: Class<*>?, name: String): Boolean {
        if (cls == null) return false
        val method = host.method(cls, name, java.lang.Boolean.TYPE) ?: return false
        Hooks.beforeIfEnabled(feature, method) { it.args[0] = false }
        return true
    }

    /** Read a static int constant such as `Relax.K_APPLY_SUCCESS`. */
    fun constant(host: Host, cls: Class<*>, name: String, fallback: Int): Int {
        val field = host.field(cls, name) ?: return fallback
        return try {
            field.get(null) as? Int ?: fallback
        } catch (t: Throwable) {
            fallback
        }
    }
}
