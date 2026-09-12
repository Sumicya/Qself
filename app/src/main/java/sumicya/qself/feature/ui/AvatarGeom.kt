/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */
package sumicya.qself.feature.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * 聊天气泡头像定位器 — shared by the group-admin menu (entry point) and the
 * avatar rounding feature. An avatar is the roughly-square ImageView in the
 * 36..56dp window: message images live inside the content area and are
 * bubble-sized, icons are smaller, so the window separates the avatar
 * reliably without knowing the obfuscated view class.
 */
object AvatarGeom {

    @JvmStatic
    fun findAvatar(root: View, depth: Int): View? {
        if (depth > 6) {
            return null
        }
        if (root is ImageView) {
            val w = root.width
            val h = root.height
            if (w > 0 && h > 0) {
                val density = root.resources.displayMetrics.density
                val min = Math.round(36f * density)
                val max = Math.round(56f * density)
                if (w >= min && w <= max && h >= min && h <= max && Math.abs(w - h) <= Math.round(4f * density)) {
                    return root
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) ?: continue
                if (child.visibility != View.VISIBLE) continue
                val found = findAvatar(child, depth + 1)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }
}
