/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.ColorUtils
import io.github.qauxv.dsl.cell.TextInfoCell
import io.github.qauxv.dsl.cell.HeaderCell
import io.github.qauxv.dsl.cell.SpacerCell
import io.github.qauxv.dsl.cell.TitleValueCell

/** Opaque MD3 Expressive surfaces. Optical rendering is reserved for explicit overlay callers. */
object SettingsVisuals {
    data class Palette(val dark: Boolean, val mode: Int, val background: Int, val text: Int,
                       val secondary: Int, val accent: Int, val surface: Int, val rim: Int,
                       val container: Int, val onContainer: Int)

    fun dp(context: Context, value: Int): Int = (context.resources.displayMetrics.density * value + .5f).toInt()

    @JvmStatic
    fun palette(context: Context, mode: Int = 1): Palette {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val attr = TypedValue()
        val fallback = if (dark) Color.rgb(156, 186, 255) else Color.rgb(44, 83, 176)
        val themeAccent = if (context.theme.resolveAttribute(android.R.attr.colorAccent, attr, true) &&
            attr.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) attr.data else fallback
        // Blend theme color into a legible foreground, not an arbitrary pastel as body text.
        val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, readableAccent(themeAccent, dark))
        fun role(attr: Int, fallback: Int) = MaterialColors.getColor(context, attr, fallback)
        return Palette(dark, mode,
            role(com.google.android.material.R.attr.colorSurface, if (dark) 0xff141218.toInt() else 0xfffef7ff.toInt()),
            role(com.google.android.material.R.attr.colorOnSurface, if (dark) Color.WHITE else Color.BLACK),
            role(com.google.android.material.R.attr.colorOnSurfaceVariant, if (dark) 0xffcac4d0.toInt() else 0xff49454f.toInt()),
            accent,
            role(com.google.android.material.R.attr.colorSurfaceContainer, if (dark) 0xff211f26.toInt() else 0xfff3edf7.toInt()),
            role(com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY),
            role(com.google.android.material.R.attr.colorSecondaryContainer, if (dark) 0xff4a4458.toInt() else 0xffe8def8.toInt()),
            role(com.google.android.material.R.attr.colorOnSecondaryContainer, if (dark) 0xffe8def8.toInt() else 0xff1d192b.toInt()))
    }

    @JvmStatic
    fun readableAccent(color: Int, dark: Boolean): Int {
        val backdrop = if (dark) Color.rgb(48, 60, 82) else Color.rgb(203, 222, 255)
        val target = if (dark) Color.WHITE else Color.BLACK
        var result = blend(color, target, if (dark) .36f else .22f)
        repeat(24) {
            if (ColorUtils.calculateContrast(result, backdrop) >= 4.5) return result
            result = blend(result, target, .12f)
        }
        return target
    }

    /** Non-text state glyphs must remain legible when a dynamic container changes luminance. */
    @JvmStatic
    fun stateForeground(candidate: Int, background: Int): Int {
        // ColorUtils.blendARGB can round an opaque interpolated alpha to 254.
        // The control paints its background at alpha=255; compare that actual opaque color.
        val opaque = ColorUtils.setAlphaComponent(background, 255)
        val foreground = ColorUtils.setAlphaComponent(candidate, 255)
        return if (ColorUtils.calculateContrast(foreground, opaque) >= 3.0) foreground
        else if (ColorUtils.calculateLuminance(opaque) > .179) Color.BLACK else Color.WHITE
    }

    private fun blend(a: Int, b: Int, fraction: Float): Int = Color.rgb(
        (Color.red(a) * (1 - fraction) + Color.red(b) * fraction).toInt(),
        (Color.green(a) * (1 - fraction) + Color.green(b) * fraction).toInt(),
        (Color.blue(a) * (1 - fraction) + Color.blue(b) * fraction).toInt())

    @JvmStatic
    fun backdrop(p: Palette): Drawable = ColorDrawable(p.background)

    @JvmStatic
    @JvmOverloads
    fun surface(context: Context, p: Palette, radius: Int = 24, clickable: Boolean = false, owner: View? = null): Drawable {
        val shape = ShapeAppearanceModel.builder().setAllCornerSizes(dp(context, radius).toFloat()).build()
        val material = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(p.surface) }
        if (!clickable) return material
        val mask = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(p.accent, 31)), material, mask)
    }

    fun addListSpacing(recycler: RecyclerView) {
        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                outRect.top = if (position == 0) dp(parent.context, 16) else 0
                outRect.bottom = if (position == state.itemCount - 1) dp(parent.context, 16) else 0
            }
        })
    }

    @JvmOverloads
    fun decorateRow(view: View, context: Context, clickable: Boolean, paletteOverride: Palette? = null) {
        if (view is SpacerCell) return
        val p = paletteOverride ?: palette(context, SettingsAppearanceItem.mode)
        if (view is HeaderCell) {
            view.titleTextView.setTextColor(p.accent)
            return
        }
        val params = (view.layoutParams as? RecyclerView.LayoutParams)
            ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.marginStart = dp(context, 16)
        params.marginEnd = dp(context, 16)
        params.bottomMargin = dp(context, 6)
        view.layoutParams = params
        view.background = surface(context, p, 12, clickable, view)
        view.isFocusable = clickable
        if (view is TextInfoCell) {
            view.textColor = p.secondary
            view.textLinkColor = p.accent
        }
        if (view is TitleValueCell) {
            view.switchView.paletteOverride = p
            view.hasDivider = false
            view.titleView.setTextColor(p.text)
            view.summaryView.setTextColor(p.secondary)
            view.valueView.setTextColor(p.accent)
        }
    }
}
