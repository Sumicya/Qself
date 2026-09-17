/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
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

package io.github.qauxv.dsl.cell

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import io.github.qauxv.util.LayoutHelper
import io.github.qauxv.util.LayoutHelperViewScope
import io.github.qauxv.util.ui.ThemeAttrUtils
import io.github.qauxv.R

class HeaderCell(context: Context) : FrameLayout(context), LayoutHelperViewScope {

    val titleTextView: TextView
    var cellHeight: Int = 0
    var topMargin: Int = 0
    var paddings: Int = 0

    init {
        cellHeight = 28.dp
        topMargin = 8.dp
        paddings = 20.dp
        titleTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(ThemeAttrUtils.resolveColorOrDefaultColorRes(context, androidx.appcompat.R.attr.colorAccent, R.color.colorAccent))
            minHeight = cellHeight - topMargin
            setPadding(0, 0, 0, 4.dp)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
        addView(titleTextView, LayoutHelper.newFrameLayoutParamsRel(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.TOP or Gravity.START, paddings, topMargin, paddings, 0))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    var title: String
        get() = titleTextView.text?.toString() ?: ""
        set(value) {
            titleTextView.text = value
        }
}
