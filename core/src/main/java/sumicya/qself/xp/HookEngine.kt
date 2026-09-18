/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.xp

import java.lang.reflect.Executable
import java.lang.reflect.Member

/**
 * Framework-agnostic hooking abstraction. Features only ever talk to this
 * interface (through [sumicya.qself.xp.Hooks]), which keeps the module
 * loadable from different hooking environments (classic Xposed API today,
 * the native engine later).
 */
interface HookEngine {

    /**
     * Unified view of one hooked invocation. In a before-handler, [skip]
     * replaces the original execution; in an after-handler, [skip] reports
     * whether the original execution was replaced.
     */
    interface HookParam {
        /** The method or constructor being hooked. */
        val member: Member

        /** The `this` object, or null for static members. */
        val thisObject: Any?

        /** Arguments (mutable in before-handlers). */
        val args: Array<Any?>

        /** True while running as an after-handler. */
        val isAfter: Boolean

        /**
         * Replace the original execution with [result] (before-handlers).
         * In after-handlers this is a read-only report.
         */
        fun skip(result: Any? = null)

        /** Current result: before → always null, after → original or replaced. */
        val result: Any?

        /** Exception thrown by the original execution (after-handlers). */
        val exception: Throwable?

        /**
         * Force the invocation to throw [throwable] (after-handlers only;
         * ignored before).
         */
        fun setException(throwable: Throwable?)
    }

    interface Handle {
        fun unhook()
    }

    /** Whether this engine can actually install hooks (false = boot without features). */
    val supported: Boolean
        get() = true

    /**
     * Install a hook on [executable]. Either callback may be null.
     * Handlers must never throw out of the framework; [sumicya.qself.xp.Hooks]
     * wraps them defensively.
     */
    fun hook(
        executable: Executable,
        onBefore: ((HookParam) -> Unit)?,
        onAfter: ((HookParam) -> Unit)?,
        priority: Int = PRIORITY_DEFAULT,
    ): Handle

    companion object {
        const val PRIORITY_DEFAULT = 50
    }
}
