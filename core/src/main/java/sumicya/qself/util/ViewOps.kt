/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.util

import android.view.View
import android.view.ViewGroup

/** Small view helpers shared by UI-modifying features. */
object ViewOps {

    /** Depth-first search for a descendant whose entry name matches [name]. */
    fun findViewByName(root: View, name: String): View? {
        if (nameOf(root) == name) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewByName(root.getChildAt(i), name)?.let { return it }
            }
        }
        return null
    }

    /** All descendants whose entry name matches [name] (depth-first). */
    fun findAllViewsByName(root: View, name: String): List<View> {
        val out = ArrayList<View>()
        walk(root, name, out)
        return out
    }

    private fun walk(view: View, name: String, out: ArrayList<View>) {
        if (nameOf(view) == name) {
            out.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), name, out)
            }
        }
    }

    /** Detach the view from its parent if it has one. Never throws. */
    fun removeSafe(view: View) {
        try {
            (view.parent as? ViewGroup)?.removeView(view)
        } catch (t: Throwable) {
            // cosmetic only
        }
    }

    fun setGoneIf(view: View?, gone: Boolean) {
        view ?: return
        if (view.visibility != View.GONE) {
            view.visibility = if (gone) View.GONE else View.INVISIBLE
        }
    }

    fun nameOf(view: View): String {
        return try {
            val id = view.id
            if (id == View.NO_ID) "" else view.resources.getResourceEntryName(id)
        } catch (t: Throwable) {
            ""
        }
    }
}
