/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

/**
 * Ordered stack of open settings levels for one host activity.
 *
 * A level is one thing the user opened and expects back to close: an expanded
 * category card or an expanded row panel. The order is the open order, so back
 * always unwinds the most recent level first — that is what "返回到该有的级别"
 * means in practice, and it is the invariant this class exists to hold.
 *
 * Registration rules:
 *  - every level belongs to the container it lives in ([containerOf] may be
 *    null for a top-level panel);
 *  - collapsing a container collapses the levels inside it before the
 *    container itself, innermost first;
 *  - a level that closes by any route (header tap, back, re-tap, host detach)
 *    removes exactly its own entry.
 *
 * Pure data: no Android types, so the ordering rules are unit-testable.
 */
class SettingsLevelStack {

    private data class Entry(val token: Any, val container: Any?)

    private val entries = ArrayList<Entry>()

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Opens a level; re-opening the same token keeps its original position. */
    fun push(token: Any, container: Any?) {
        if (entries.any { it.token === token }) return
        entries.add(Entry(token, container))
    }

    /** Closes one specific level. */
    fun remove(token: Any) {
        entries.removeAll { it.token === token }
    }

    /** The level back should close now, or null when nothing is open. */
    fun top(): Any? = entries.lastOrNull()?.token

    /**
     * Levels living inside [container], innermost first, without removing them.
     * Callers collapse each one; each collapse removes its own entry.
     */
    fun inside(container: Any): List<Any> =
        entries.filter { it.container === container }.map { it.token }.asReversed()

    fun clear() = entries.clear()
}
