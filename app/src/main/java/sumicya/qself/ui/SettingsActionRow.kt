/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A Material 3 Expressive list row: a tonal icon badge, a title with an
 * optional summary, and a trailing chevron — all inside one card.
 *
 * The row can host an inline panel ([InlineHost]), so tapping it opens the
 * panel directly beneath itself instead of in a window.
 */
class SettingsActionRow(context: Context, private val palette: SettingsVisuals.Palette) :
    LinearLayout(context), InlineHost {

    private val badge = FrameLayout(context)
    private val icon = ImageView(context)
    private val titleView = TextView(context)
    private val summaryView = TextView(context)
    private val chevron = ImageView(context)

    override val inlineContent = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = SettingsVisuals.dp(context, ROW_MIN_HEIGHT)
            setPadding(SettingsVisuals.dp(context, 16), SettingsVisuals.dp(context, 10),
                SettingsVisuals.dp(context, 16), SettingsVisuals.dp(context, 10))
            addView(badge.apply {
                background = SettingsVisuals.roundedFill(context, palette.selectedContainer, SettingsVisuals.SHAPE_M)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(icon.apply {
                    scaleType = ImageView.ScaleType.CENTER
                    setColorFilter(palette.onSelectedContainer)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(
                    SettingsVisuals.dp(context, 24), SettingsVisuals.dp(context, 24), Gravity.CENTER))
            }, LinearLayout.LayoutParams(
                SettingsVisuals.dp(context, 40), SettingsVisuals.dp(context, 40)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(titleView.apply {
                    SettingsVisuals.applyType(this, context, SettingsVisuals.TYPE_TITLE_MEDIUM)
                    setTextColor(palette.text)
                })
                addView(summaryView.apply {
                    SettingsVisuals.applyType(this, context, SettingsVisuals.TYPE_BODY_MEDIUM)
                    setTextColor(palette.secondary)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SettingsVisuals.dp(context, 16)
            })
            addView(chevron.apply {
                setImageResource(io.github.qauxv.R.drawable.ic_arrow_forward_outline_24)
                scaleType = ImageView.ScaleType.CENTER
                setColorFilter(palette.secondary)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(SettingsVisuals.dp(context, 24), SettingsVisuals.dp(context, 24)))
        }
        addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(inlineContent, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /**
     * Fills the row and wires its tap. The tap is journaled by
     * [SettingsTouchTarget] before [onClick] runs, so a dead destination is
     * always distinguishable from a dead touch.
     */
    fun bind(
        iconRes: Int,
        title: String,
        summary: String,
        actionId: String,
        onClick: () -> Unit,
    ) {
        icon.setImageResource(iconRes)
        titleView.text = title
        summaryView.text = summary
        summaryView.visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        SettingsVisuals.decorateCardChild(this, palette, true)
        SettingsTouchTarget.attach(this, actionId, if (summary.isBlank()) title else "$title，$summary", onClick = onClick)
    }

    private companion object {
        const val ROW_MIN_HEIGHT = 64
    }
}
