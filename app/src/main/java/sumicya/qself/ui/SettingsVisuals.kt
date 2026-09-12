/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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

/** Native optical glass over the settings scene; no host captures or background animation. */
object SettingsVisuals {
    data class Palette(val dark: Boolean, val mode: Int, val background: Int, val text: Int,
                       val secondary: Int, val accent: Int, val surface: Int, val rim: Int)

    fun dp(context: Context, value: Int): Int = (context.resources.displayMetrics.density * value + .5f).toInt()

    @JvmStatic
    fun palette(context: Context, mode: Int = 1): Palette {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val attr = TypedValue()
        val fallback = if (dark) Color.rgb(156, 186, 255) else Color.rgb(44, 83, 176)
        val themeAccent = if (context.theme.resolveAttribute(android.R.attr.colorAccent, attr, true) &&
            attr.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) attr.data else fallback
        // Blend theme color into a legible foreground, not an arbitrary pastel as body text.
        val accent = readableAccent(themeAccent, dark)
        return if (dark) Palette(true, mode, Color.rgb(17, 22, 33), Color.rgb(239, 242, 250),
            Color.rgb(172, 185, 205), accent, Color.rgb(36, 44, 61), Color.argb(45, 220, 232, 255))
        else Palette(false, mode, Color.rgb(239, 243, 250), Color.rgb(28, 39, 58),
            Color.rgb(84, 99, 121), accent, Color.WHITE, Color.argb(220, 255, 255, 255))
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

    private fun blend(a: Int, b: Int, fraction: Float): Int = Color.rgb(
        (Color.red(a) * (1 - fraction) + Color.red(b) * fraction).toInt(),
        (Color.green(a) * (1 - fraction) + Color.green(b) * fraction).toInt(),
        (Color.blue(a) * (1 - fraction) + Color.blue(b) * fraction).toInt())

    @JvmStatic
    fun backdrop(p: Palette): Drawable = SettingsGlass.backdrop(p, flatBackdrop(p))

    private fun flatBackdrop(p: Palette): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            // A Drawable must never clear its parent's canvas outside its own bounds.
            paint.shader = null
            paint.color = p.background
            canvas.drawRect(bounds, paint)
            if (p.mode == 2 || bounds.width() == 0) return
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            paint.shader = RadialGradient(bounds.left + width * .91f, bounds.top + height * .07f, width * .95f,
                if (p.dark) Color.rgb(39, 57, 91) else Color.rgb(203, 222, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = RadialGradient(bounds.left + width * .06f, bounds.top + height * .56f, width * .8f,
                if (p.dark) Color.argb(100, 62, 48, 87) else Color.argb(150, 225, 214, 246), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(colorFilter: ColorFilter?) { }
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.OPAQUE
    }

    @JvmStatic
    @JvmOverloads
    fun surface(context: Context, p: Palette, radius: Int = 24, clickable: Boolean = false, owner: View? = null): Drawable {
        val alpha = when (p.mode) { 0 -> if (p.dark) 190 else 140; 2 -> 255; else -> if (p.dark) 235 else 210 }
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(alpha, Color.red(p.surface), Color.green(p.surface), Color.blue(p.surface)),
                Color.argb(if (p.mode == 2) 255 else (alpha - 20).coerceAtLeast(0), Color.red(p.surface), Color.green(p.surface), Color.blue(p.surface))))
        base.cornerRadius = dp(context, radius).toFloat()
        base.setStroke(dp(context, 1), p.rim)
        val material = SettingsGlass.material(context, p, radius, owner, base)
        if (!clickable) return material
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(context, radius).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(32, Color.red(p.accent), Color.green(p.accent), Color.blue(p.accent))), material, mask)
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

    fun decorateRow(view: View, context: Context, clickable: Boolean) {
        if (view is SpacerCell) return
        val p = palette(context, SettingsAppearanceItem.mode)
        if (view is HeaderCell) {
            view.titleTextView.setTextColor(p.accent)
            return
        }
        val params = (view.layoutParams as? RecyclerView.LayoutParams)
            ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.marginStart = dp(context, 16)
        params.marginEnd = dp(context, 16)
        params.bottomMargin = dp(context, 7)
        view.layoutParams = params
        view.background = surface(context, p, 24, clickable, view)
        view.isFocusable = clickable
        if (view is TextInfoCell) {
            view.textColor = p.secondary
            view.textLinkColor = p.accent
        }
        if (view is TitleValueCell) {
            view.hasDivider = false
            view.titleView.setTextColor(p.text)
            view.summaryView.setTextColor(p.secondary)
            view.valueView.setTextColor(p.accent)
        }
    }
}
