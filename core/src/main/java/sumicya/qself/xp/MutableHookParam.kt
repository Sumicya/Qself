/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.xp

import java.lang.reflect.Member

/**
 * [HookEngine.HookParam] state shared by the engine adapters.
 *
 * The mutable fields are the adapter's own bookkeeping (not part of the
 * contract features see); keeping them public is what holds every adapter
 * down to its framework glue instead of a third copy of this class.
 */
open class MutableHookParam(
    override val member: Member,
    override val thisObject: Any?,
    override val args: Array<Any?>,
) : HookEngine.HookParam {

    /** True while the after-handler runs. */
    var afterPhase: Boolean = false

    /** Set by [skip]: the adapter replaces the call with [resultValue]. */
    var skipped: Boolean = false

    /** Before-handler: replacement result. After-handler: what the call returned. */
    var resultValue: Any? = null

    /** Throwable the call raised, or one a handler forced. */
    var throwableValue: Throwable? = null

    override val isAfter: Boolean
        get() = afterPhase

    override val result: Any?
        get() = resultValue

    override val exception: Throwable?
        get() = throwableValue

    override fun skip(result: Any?) {
        skipped = true
        resultValue = result
    }

    override fun setException(throwable: Throwable?) {
        throwableValue = throwable
    }
}
