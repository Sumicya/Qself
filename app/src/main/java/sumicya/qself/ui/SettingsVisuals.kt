/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.appcompat.R as AppCompatR
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.github.qauxv.dsl.cell.HeaderCell
import io.github.qauxv.dsl.cell.SpacerCell
import io.github.qauxv.dsl.cell.TextInfoCell
import io.github.qauxv.dsl.cell.TitleValueCell

/**
 * Material 3 Expressive style for the settings UI.
 *
 * Everything visible is built from real Material components and theme roles:
 * cards are [MaterialCardView]s on the surface-container tiers, text uses the
 * Material 3 type scale, and state layers/ripples carry the theme's primary
 * colour. Nothing here hand-paints a flat rectangle pretending to be a card.
 *
 * Shape scale (Material 3 expressive): XS 4 / S 8 / M 12 / L 16 / XL 28.
 */
object SettingsVisuals {

    /* ------------------------------------------------------------- shapes */

    const val SHAPE_XS = 4
    const val SHAPE_S = 8
    const val SHAPE_M = 12
    const val SHAPE_L = 16
    const val SHAPE_XL = 28

    /* ------------------------------------------------------------- tokens */

    const val CARD_RADIUS = SHAPE_XL
    const val ROW_RADIUS = SHAPE_L
    const val SCREEN_SIDE = 16
    const val CARD_GAP = 10
    const val TEXT_START = 20
    const val HEADER_HEIGHT = 56
    const val ROW_HEIGHT = 56
    const val RAIL_WIDTH = 56
    const val RAIL_RADIUS = SHAPE_L
    const val ICON_BUTTON = 40

    /* -------------------------------------------------- type scale (M3) */

    val TYPE_HEADLINE_SMALL: Int get() = MaterialR.style.TextAppearance_Material3_HeadlineSmall
    val TYPE_TITLE_LARGE: Int get() = MaterialR.style.TextAppearance_Material3_TitleLarge
    val TYPE_TITLE_MEDIUM: Int get() = MaterialR.style.TextAppearance_Material3_TitleMedium
    val TYPE_BODY_LARGE: Int get() = MaterialR.style.TextAppearance_Material3_BodyLarge
    val TYPE_BODY_MEDIUM: Int get() = MaterialR.style.TextAppearance_Material3_BodyMedium
    val TYPE_LABEL_LARGE: Int get() = MaterialR.style.TextAppearance_Material3_LabelLarge
    val TYPE_LABEL_MEDIUM: Int get() = MaterialR.style.TextAppearance_Material3_LabelMedium

    /* ------------------------------------------------------------ palette */

    data class Palette(
        val dark: Boolean,
        val mode: Int,
        val background: Int,
        val text: Int,
        val secondary: Int,
        val accent: Int,
        val surface: Int,
        val rim: Int,
        val container: Int,
        val onContainer: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val errorContainer: Int,
        val onErrorContainer: Int,
        val outline: Int,
        /** Card surface, one step above the window background. */
        val surfaceLow: Int = surface,
        /** Expanded/nested surface, two steps above the background. */
        val surfaceHigh: Int = surface,
        /** Accent container for selected navigation rows. */
        val selectedContainer: Int = container,
        val onSelectedContainer: Int = onContainer,
    )

    fun dp(context: Context, value: Int): Int =
        (context.resources.displayMetrics.density * value + .5f).toInt()

    @JvmStatic
    @JvmOverloads
    fun palette(context: Context, mode: Int = 1): Palette {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val attribute = TypedValue()
        val fallback = if (dark) Color.rgb(156, 186, 255) else Color.rgb(44, 83, 176)
        val themeAccent = if (context.theme.resolveAttribute(android.R.attr.colorAccent, attribute, true) &&
            attribute.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        ) attribute.data else fallback
        // Blend the theme colour into a legible foreground, not an arbitrary pastel as body text.
        val accent = MaterialColors.getColor(context, AppCompatR.attr.colorPrimary, readableAccent(themeAccent, dark))
        fun role(attr: Int, fallbackColor: Int) = MaterialColors.getColor(context, attr, fallbackColor)
        val surface = role(MaterialR.attr.colorSurface,
            if (dark) 0xff141218.toInt() else 0xfffef7ff.toInt())
        return Palette(
            dark, mode,
            surface,
            role(MaterialR.attr.colorOnSurface, if (dark) Color.WHITE else Color.BLACK),
            role(MaterialR.attr.colorOnSurfaceVariant,
                if (dark) 0xffcac4d0.toInt() else 0xff49454f.toInt()),
            accent,
            role(MaterialR.attr.colorSurfaceContainer, if (dark) 0xff211f26.toInt() else 0xfff3edf7.toInt()),
            role(MaterialR.attr.colorOutlineVariant, Color.GRAY),
            role(MaterialR.attr.colorSecondaryContainer,
                if (dark) 0xff4a4458.toInt() else 0xffe8def8.toInt()),
            role(MaterialR.attr.colorOnSecondaryContainer,
                if (dark) 0xffe8def8.toInt() else 0xff1d192b.toInt()),
            role(MaterialR.attr.colorPrimaryContainer,
                if (dark) 0xff4a3f77.toInt() else 0xffeaddff.toInt()),
            role(MaterialR.attr.colorOnPrimaryContainer,
                if (dark) 0xffeaddff.toInt() else 0xff21005d.toInt()),
            role(MaterialR.attr.colorErrorContainer,
                if (dark) 0xff8c1d18.toInt() else 0xfff9dedc.toInt()),
            role(MaterialR.attr.colorOnErrorContainer,
                if (dark) 0xfff2b8b5.toInt() else 0xff8c1d18.toInt()),
            role(MaterialR.attr.colorOutline, 0xff79747e.toInt()),
            role(MaterialR.attr.colorSurfaceContainerLow, if (dark) 0xff1d1b20.toInt() else 0xfff7f2fa.toInt()),
            role(MaterialR.attr.colorSurfaceContainerHigh, if (dark) 0xff2b2930.toInt() else 0xffece6f0.toInt()),
            role(MaterialR.attr.colorSecondaryContainer, if (dark) 0xff4a4458.toInt() else 0xffe8def8.toInt()),
            role(MaterialR.attr.colorOnSecondaryContainer, if (dark) 0xffe8def8.toInt() else 0xff1d192b.toInt()),
        )
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

    /** Non-text state glyphs must stay legible when a dynamic container changes luminance. */
    @JvmStatic
    fun stateForeground(candidate: Int, background: Int): Int {
        // ColorUtils.blendARGB can round an opaque interpolated alpha to 254.
        // The control paints its background at alpha=255; compare that actual opaque colour.
        val opaque = ColorUtils.setAlphaComponent(background, 255)
        val foreground = ColorUtils.setAlphaComponent(candidate, 255)
        return if (ColorUtils.calculateContrast(foreground, opaque) >= 3.0) foreground
        else if (ColorUtils.calculateLuminance(opaque) > .179) Color.BLACK else Color.WHITE
    }

    private fun blend(a: Int, b: Int, fraction: Float): Int = Color.rgb(
        (Color.red(a) * (1 - fraction) + Color.red(b) * fraction).toInt(),
        (Color.green(a) * (1 - fraction) + Color.green(b) * fraction).toInt(),
        (Color.blue(a) * (1 - fraction) + Color.blue(b) * fraction).toInt())

    /* -------------------------------------------------------- components */

    /**
     * A Material 3 Expressive card: tonal container, XL corner, no elevation
     * and no stroke. Used for every surface in the settings UI — section cards,
     * the management card, panels and the search field.
     */
    @JvmStatic
    @JvmOverloads
    fun card(
        context: Context,
        p: Palette,
        radius: Int = CARD_RADIUS,
        container: Int = p.surfaceLow,
        clickable: Boolean = false,
    ): MaterialCardView = MaterialCardView(context).apply {
        this.radius = dp(context, radius).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(container)
        isClickable = clickable
        isFocusable = clickable
        setRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(p.accent, 26)))
        setContentPadding(0, 0, 0, 0)
    }

    /** A text view carrying one step of the Material 3 type scale. */
    @JvmStatic
    fun text(context: Context, styleRes: Int, value: CharSequence, color: Int): TextView =
        TextView(context, null, 0, styleRes).apply {
            text = value
            setTextColor(color)
            includeFontPadding = false
        }

    /** Applies a Material 3 type style to an existing text view. */
    @JvmStatic
    fun applyType(view: TextView, context: Context, styleRes: Int) {
        val styled = TextView(context, null, 0, styleRes)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, styled.textSize)
        view.typeface = styled.typeface
        view.includeFontPadding = false
    }

    /* ----------------------------------------------------------- painters */

    @JvmStatic
    fun backdrop(p: Palette): Drawable = ColorDrawable(p.background)

    @JvmStatic
    @JvmOverloads
    fun surface(context: Context, p: Palette, radius: Int = CARD_RADIUS, clickable: Boolean = false,
                owner: View? = null): Drawable {
        val shape = ShapeAppearanceModel.builder().setAllCornerSizes(dp(context, radius).toFloat()).build()
        val material = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(p.surfaceLow) }
        if (!clickable) return material
        val mask = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(p.accent, 31)), material, mask)
    }

    /** A solid rounded fill, for icon badges and other tonal chips. */
    @JvmStatic
    fun roundedFill(context: Context, color: Int, radius: Int): Drawable {
        val shape = ShapeAppearanceModel.builder().setAllCornerSizes(dp(context, radius).toFloat()).build()
        return MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(color) }
    }

    /** Clips a view's outline to a card radius so child paints never cross the corner. */
    @JvmStatic
    @JvmOverloads
    fun clipOutline(view: View, radius: Int = CARD_RADIUS) {
        val r = dp(view.context, radius).toFloat()
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
        view.clipToOutline = true
    }

    /**
     * State layer for rows living inside an already-rounded card.
     *
     * The mask carries the card radius instead of being a bare rectangle: a
     * rectangular ripple is clipped by the card outline only where the card
     * clips, so first/last rows showed square highlight corners against the
     * round card wall (the "meaningless outline" seen when tapping rows and
     * when the expansion animation runs).
     */
    @JvmStatic
    @JvmOverloads
    fun rowStateLayer(context: Context, p: Palette, radius: Int = CARD_RADIUS): Drawable {
        val shape = ShapeAppearanceModel.builder().setAllCornerSizes(dp(context, radius).toFloat()).build()
        val mask = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(p.accent, 26)), null, mask)
    }

    /* --------------------------------------------------------- list space */

    fun addListSpacing(recycler: RecyclerView) {
        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                outRect.top = if (position == 0) dp(parent.context, 12) else 0
                outRect.bottom = if (position == state.itemCount - 1) dp(parent.context, 16)
                else dp(parent.context, CARD_GAP)
            }
        })
    }

    /* --------------------------------------------------------- decoration */

    /**
     * Standalone row decoration for plain RecyclerView screens (search results,
     * legacy category fragments): every row is its own XL card with side
     * margins, Material 3 typography and a state layer.
     */
    @JvmOverloads
    fun decorateRow(view: View, context: Context, clickable: Boolean, paletteOverride: Palette? = null) {
        if (view is SpacerCell) return
        val p = paletteOverride ?: palette(context, SettingsAppearanceItem.mode)
        if (view is HeaderCell) {
            view.titleTextView.setTextColor(p.secondary)
            applyType(view.titleTextView, context, TYPE_LABEL_LARGE)
            return
        }
        val params = (view.layoutParams as? RecyclerView.LayoutParams)
            ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.marginStart = dp(context, SCREEN_SIDE)
        params.marginEnd = dp(context, SCREEN_SIDE)
        params.bottomMargin = dp(context, CARD_GAP)
        params.topMargin = 0
        view.layoutParams = params
        view.background = surface(context, p, CARD_RADIUS, clickable, view)
        clipOutline(view, CARD_RADIUS)
        view.isFocusable = clickable
        when (view) {
            is TextInfoCell -> {
                view.textColor = p.secondary
                view.textLinkColor = p.accent
            }
            is TitleValueCell -> styleRow(view, context, p, clickable)
        }
    }

    /**
     * Row decoration inside a single container card: no per-row background, no
     * divider, zero outer margins; a bounded ripple state layer is the only row
     * affordance.
     */
    @JvmOverloads
    fun decorateCardChild(view: View, p: Palette, clickable: Boolean = true) {
        val context = view.context
        val params = view.layoutParams
        if (params is ViewGroup.MarginLayoutParams) {
            params.marginStart = 0
            params.marginEnd = 0
            params.topMargin = 0
            params.bottomMargin = 0
            view.layoutParams = params
        }
        when (view) {
            is SpacerCell -> Unit
            is HeaderCell -> {
                view.titleTextView.setTextColor(p.secondary)
                applyType(view.titleTextView, context, TYPE_LABEL_LARGE)
            }
            is TextInfoCell -> {
                view.textColor = p.secondary
                view.textLinkColor = p.accent
            }
            is TitleValueCell -> styleRow(view, context, p, clickable)
            else -> Unit
        }
    }

    /** One list row: Material 3 type scale, tonal slot colours, bounded ripple. */
    private fun styleRow(cell: TitleValueCell, context: Context, p: Palette, clickable: Boolean) {
        applyType(cell.titleView, context, TYPE_TITLE_MEDIUM)
        applyType(cell.summaryView, context, TYPE_BODY_MEDIUM)
        applyType(cell.valueView, context, TYPE_LABEL_LARGE)
        cell.switchView.paletteOverride = p
        cell.chevronView.setColorFilter(p.secondary)
        cell.hasDivider = false
        cell.titleView.setTextColor(p.text)
        cell.summaryView.setTextColor(p.secondary)
        cell.valueView.setTextColor(p.accent)
        cell.minimumHeight = dp(context, ROW_HEIGHT)
        if (clickable) cell.foreground = rowStateLayer(context, p)
    }
}
