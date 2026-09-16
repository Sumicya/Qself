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

package io.github.qauxv.dsl.cell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import sumicya.qself.ui.SquareStateControl
import sumicya.qself.ui.SettingsVisuals
import androidx.core.content.res.ResourcesCompat
import io.github.qauxv.util.LayoutHelper
import io.github.qauxv.util.LayoutHelper.MATCH_PARENT
import io.github.qauxv.util.LayoutHelper.WRAP_CONTENT
import io.github.qauxv.util.LayoutHelperViewScope
import io.github.qauxv.util.ui.ThemeAttrUtils
import io.github.qauxv.R

class TitleValueCell(
    context: Context,
) : FrameLayout(context), LayoutHelperViewScope {

    val titleView: TextView
    val summaryView: TextView
    val valueView: TextView
    val switchView: SquareStateControl
    val chevronView: ImageView

    private val dividerColor: Int
    private val dip1: Float = 1.dp.toFloat()

    private val dividerPaint by lazy { Paint() }

    private val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var trailingWidth = 0
    private var headerHeight = 0
    val inlineContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isClickable = true }

    // Unified trailing slot geometry: a 52dp square flush to the trailing card wall.
    private val railWidth = SettingsVisuals.dp(context, SettingsVisuals.RAIL_WIDTH)
    private val textStart = SettingsVisuals.dp(context, SettingsVisuals.TEXT_START)
    private val textGap = 12.dp

    init {
        minimumHeight = SettingsVisuals.dp(context, SettingsVisuals.ROW_HEIGHT)
        setWillNotDraw(false)
        addView(textColumn)
        addView(inlineContent)
        dividerColor = ResourcesCompat.getColor(resources, R.color.divideColor, context.theme)
        // title text view
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ResourcesCompat.getColor(resources, R.color.firstTextColor, context.theme))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }.also {
            textColumn.addView(it)
        }
        // summary text view
        // One line, like a settings list: a two or three line description made the rows
        // uneven and cut the number of switches that fit on one screen. The full text stays
        // in the row's accessibility description.
        summaryView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ResourcesCompat.getColor(resources, R.color.thirdTextColor, context.theme))
            gravity = Gravity.START
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }.also {
            textColumn.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 2.dp })
        }
        val valueTextColor = ThemeAttrUtils.resolveColorOrDefaultColorRes(context, androidx.appcompat.R.attr.colorAccent, R.color.colorAccent)
        // value text view
        valueView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(valueTextColor)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            visibility = GONE
        }.also {
            addView(
                it, LayoutHelper.newFrameLayoutParamsRel(
                    WRAP_CONTENT, WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL or Gravity.END, 16.dp, 0, 16.dp, 0
                )
            )
        }
        // switch view: the trailing state slot, measured flush to the card wall
        switchView = SquareStateControl(context).apply {
            visibility = GONE
            // Clicks are owned by the whole row; the slot is a visual indicator.
            isClickable = false
        }.also {
            addView(it, LayoutHelper.newFrameLayoutParamsRel(railWidth, WRAP_CONTENT, Gravity.TOP or Gravity.END, 0, 0, 0, 0))
        }
        // navigation chevron for rows that open another page (groups, utilities)
        chevronView = ImageView(context).apply {
            setImageResource(R.drawable.qself_expand_more)
            scaleType = ImageView.ScaleType.CENTER
            rotation = -90f // down chevron -> right chevron
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also {
            addView(it, LayoutHelper.newFrameLayoutParamsRel(railWidth, WRAP_CONTENT, Gravity.TOP or Gravity.END, 0, 0, 0, 0))
        }
    }

    var title: String
        get() = titleView.text?.toString() ?: ""
        set(value) {
            titleView.text = value
            switchView.contentDescription = value
            invalidate()
        }

    var summary: CharSequence?
        get() = summaryView.text
        set(value) {
            summaryView.text = value
            summaryView.visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
            requestLayout()
        }

    var value: String?
        get() = valueView.text.toString()
        set(value) {
            valueView.text = value
            valueView.visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
            if (!value.isNullOrEmpty()) {
                // value text and state slot are mutually exclusive
                switchView.visibility = GONE
                chevronView.visibility = GONE
            }
            requestLayout()
        }

    var isHasSwitch: Boolean
        get() = switchView.visibility == VISIBLE
        set(value) {
            switchView.visibility = if (value) VISIBLE else GONE
            if (isHasSwitch) {
                valueView.visibility = GONE
                chevronView.visibility = GONE
            }
        }

    var isChevron: Boolean
        get() = chevronView.visibility == VISIBLE
        set(value) {
            chevronView.visibility = if (value) VISIBLE else GONE
            if (value) {
                switchView.visibility = GONE
                valueView.visibility = GONE
            }
        }

    var isChecked: Boolean
        get() = switchView.isChecked
        set(value) {
            switchView.isChecked = value
            if (!isHasSwitch) {
                isHasSwitch = true
            }
            chevronView.visibility = GONE
        }

    var isUnavailable: Boolean = false
        set(value) { field = value; switchView.unavailable = value }

    var hasError: Boolean = false
        set(value) {
            switchView.failed = value
            field = value
        }

    var hasDivider: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    fun isClickOnSwitch(x: Int): Boolean {
        if (!isHasSwitch) return false
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        return if (rtl) x in 0 until railWidth else x in (measuredWidth - railWidth) until measuredWidth
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val hasSlot = isHasSwitch || isChevron
        val control = when {
            isHasSwitch -> switchView
            isChevron -> chevronView
            else -> valueView
        }
        trailingWidth = 0
        if (control.visibility != GONE) {
            if (hasSlot) {
                control.measure(MeasureSpec.makeMeasureSpec(railWidth, MeasureSpec.EXACTLY), unspecified)
                trailingWidth = railWidth
            } else {
                control.measure(MeasureSpec.makeMeasureSpec((width * .30f).toInt(), MeasureSpec.AT_MOST), unspecified)
                trailingWidth = control.measuredWidth + 16.dp
            }
        }
        textColumn.measure(
            MeasureSpec.makeMeasureSpec((width - textStart - textGap - trailingWidth).coerceAtLeast(0), MeasureSpec.EXACTLY),
            unspecified)
        val header = maxOf(SettingsVisuals.dp(context, SettingsVisuals.ROW_HEIGHT),
            textColumn.measuredHeight + 13.dp,
            if (!hasSlot && control.visibility != GONE) control.measuredHeight + 8.dp else 0)
        headerHeight = header
        // The state slot fills the whole header height so the rail paints flush segments.
        if (isHasSwitch) {
            switchView.measure(MeasureSpec.makeMeasureSpec(railWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(header, MeasureSpec.EXACTLY))
        }
        if (isChevron) {
            chevronView.measure(MeasureSpec.makeMeasureSpec(railWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(header, MeasureSpec.EXACTLY))
        }
        inlineContent.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), unspecified)
        setMeasuredDimension(width, resolveSize(header + inlineContent.measuredHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        inlineContent.layout(0, headerHeight, measuredWidth, headerHeight + inlineContent.measuredHeight)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val columnLeft = if (rtl) measuredWidth - textStart - textColumn.measuredWidth else textStart
        val columnTop = (headerHeight - textColumn.measuredHeight) / 2
        textColumn.layout(columnLeft, columnTop, columnLeft + textColumn.measuredWidth, columnTop + textColumn.measuredHeight)
        // State slot / navigation chevron: flush to the trailing edge and the row's vertical bounds.
        val slotStart = if (rtl) 0 else measuredWidth - railWidth
        if (switchView.visibility != GONE) {
            switchView.layout(slotStart, 0, slotStart + railWidth, headerHeight)
        }
        if (chevronView.visibility != GONE) {
            chevronView.layout(slotStart, 0, slotStart + railWidth, headerHeight)
        }
        if (valueView.visibility != GONE) {
            val vw = valueView.measuredWidth
            val vh = valueView.measuredHeight
            val x0 = if (rtl) 16.dp else measuredWidth - 16.dp - vw
            val y0 = (headerHeight - vh) / 2
            valueView.layout(x0, y0, x0 + vw, y0 + vh)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hasDivider) {
            dividerPaint.strokeWidth = dip1
            dividerPaint.color = dividerColor
            canvas.drawLine(0f, measuredHeight.toFloat(), measuredWidth.toFloat(), measuredHeight.toFloat(), dividerPaint)
        }
    }
}
