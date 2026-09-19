/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.util

import java.lang.reflect.Member
import java.lang.reflect.Modifier

/**
 * `getModifiers()` predicates for members.
 *
 * Kotlin surfaces `Member.isAccessible` but not the `Modifier` flags, so the
 * host-fingerprinting features would otherwise repeat
 * `Modifier.isStatic(m.modifiers)` everywhere.
 */
val Member.isStatic: Boolean
    get() = Modifier.isStatic(modifiers)

val Member.isPublic: Boolean
    get() = Modifier.isPublic(modifiers)

val Member.isAbstract: Boolean
    get() = Modifier.isAbstract(modifiers)

val Member.isFinal: Boolean
    get() = Modifier.isFinal(modifiers)

val Member.isNative: Boolean
    get() = Modifier.isNative(modifiers)

val Member.isSynchronized: Boolean
    get() = Modifier.isSynchronized(modifiers)
