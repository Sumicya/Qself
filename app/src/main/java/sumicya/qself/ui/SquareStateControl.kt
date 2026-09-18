/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Build
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatCheckBox

/**
 * The trailing state slot of a feature row: a fixed [SettingsVisuals.RAIL_WIDTH]
 * square the row measures flush against its trailing edge.
 *
 * Three meanings, drawn as vector strokes rather than font glyphs (font glyphs
 * differ in curvature and optical centring):
 *  - **on** — a two-stroke check in the primary container;
 *  - **off** — a neutral minus with no background block;
 *  - **error / unavailable** — a cross in the error container.
 *
 * Ownership is split with [RailContainer]: inside a rail the container paints
 * the merged fill segments and this control draws only the glyph; standalone
 * (a plain card row) it paints its own rounded block.
 *
 * A state change replays the glyph as a short morph — the incoming stroke
 * draws itself while the outgoing one fades over the first 40% — and the
 * animation is cancelled, never orphaned, when the view leaves the window.
 */
class SquareStateControl(context: Context) : AppCompatCheckBox(context) {

    /** Host-version incompatibility: the slot shows a cross and locks. */
    var unavailable = false
        set(value) {
            field = value
            refreshDrawableState()
            invalidate()
            RailContainer.invalidateAncestor(this)
        }

    /** Initialisation or runtime failure: the slot shows a cross. */
    var failed = false
        set(value) {
            field = value
            refreshDrawableState()
            invalidate()
            RailContainer.invalidateAncestor(this)
        }

    var paletteOverride: SettingsVisuals.Palette? = null

    private val tileBox = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val glyphs = Glyphs()
    private var morph = 1f
    private var outgoing: GlyphKind? = null
    private var animator: android.animation.ValueAnimator? = null

    init {
        buttonDrawable = null
        text = ""
        setPadding(0, 0, 0, 0)
        minimumWidth = SettingsVisuals.dp(context, SettingsVisuals.RAIL_WIDTH)
        minimumHeight = SettingsVisuals.dp(context, SettingsVisuals.ROW_HEIGHT)
        isClickable = false
    }

    /* ------------------------------------------------------------- state */

    /** Applies checked state with no glyph morph (used when binding a row). */
    fun setCheckedWithoutAnimation(checked: Boolean) {
        animator?.cancel()
        animator = null
        morph = 1f
        outgoing = null
        super.setChecked(checked)
        invalidate()
        RailContainer.invalidateAncestor(this)
    }

    override fun setChecked(checked: Boolean) {
        if (isChecked == checked) return
        val before = kind()
        super.setChecked(checked)
        RailContainer.invalidateAncestor(this)
        animator?.cancel()
        animator = null
        if (!isLaidOut || !isAttachedToWindow || !SettingsMotion.enabled()) {
            morph = 1f
            outgoing = null
            invalidate()
            return
        }
        outgoing = before
        morph = 0f
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SettingsMotion.duration(context, true)
            interpolator = SettingsMotion.easing(context)
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        morph = 1f
        outgoing = null
        super.onDetachedFromWindow()
    }

    private enum class GlyphKind { MINUS, CHECK, CROSS }

    private fun kind(): GlyphKind = when {
        failed || unavailable -> GlyphKind.CROSS
        isChecked -> GlyphKind.CHECK
        else -> GlyphKind.MINUS
    }

    /* -------------------------------------------------------------- draw */

    override fun onDraw(canvas: Canvas) {
        val palette = paletteOverride ?: SettingsVisuals.palette(context)
        val density = resources.displayMetrics.density
        val inRail = inRail()
        val slot = SettingsVisuals.dp(context, SettingsVisuals.RAIL_WIDTH).toFloat()
        if (inRail) {
            // The rail container owns the fill; the slot covers the full view.
            tileBox.set(0f, 0f, width.toFloat(), height.toFloat())
        } else {
            val boxHeight = minOf(slot, height.toFloat())
            val top = (height - boxHeight) / 2f
            tileBox.set(width - slot, top, width.toFloat(), top + boxHeight)
        }
        val radius = SettingsVisuals.RAIL_RADIUS * density
        val target = kind()
        val errored = target == GlyphKind.CROSS
        val container = if (errored) palette.errorContainer else palette.primaryContainer

        // Standalone slots paint their own block; the rail paints merged fills.
        if (!inRail && (isChecked || errored)) {
            fill.color = container
            canvas.drawRoundRect(tileBox, radius, radius, fill)
        }

        glyphs.ensure(tileBox.width(), tileBox.height())
        stroke.strokeWidth = 2.8f * density
        stroke.color = when (target) {
            GlyphKind.CROSS -> palette.onErrorContainer
            GlyphKind.CHECK -> if (inRail) palette.onPrimaryContainer
            else SettingsVisuals.stateForeground(palette.onPrimaryContainer, container)
            GlyphKind.MINUS -> palette.outline
        }

        val settled = morph >= 1f || outgoing == null
        val save = canvas.save()
        val pop = if (settled) 1f else 0.85f + 0.15f * popCurve(morph)
        canvas.scale(pop, pop, width / 2f, height / 2f)

        // Outgoing glyph fades through the first 40% of the morph.
        outgoing?.let { previous ->
            val fade = (1f - morph / 0.4f).coerceIn(0f, 1f)
            if (fade > 0f) {
                stroke.alpha = (fade * 0.6f * 255).toInt()
                glyphs.draw(canvas, stroke, tileBox, previous, 1f)
            }
        }

        stroke.alpha = 255
        glyphs.draw(canvas, stroke, tileBox, target, if (settled || errored) 1f else morph)
        canvas.restoreToCount(save)
    }

    /** Slight overshoot for the incoming glyph. */
    private fun popCurve(fraction: Float): Float {
        val s = fraction.coerceIn(0f, 1f)
        return 1f + 0.18f * Math.sin((s * Math.PI).toDouble()).toFloat() * (1f - s)
    }

    private fun inRail(): Boolean {
        var current: ViewGroup? = parent as? ViewGroup
        while (current != null) {
            if (current is RailContainer) return true
            current = current.parent as? ViewGroup
        }
        return false
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (Build.VERSION.SDK_INT >= 30) {
            info.stateDescription = when {
                failed -> "初始化或运行出错，配置${if (isChecked) "开启" else "关闭"}"
                unavailable -> "与当前宿主版本不兼容，开关已锁定"
                isChecked -> "已开启"
                else -> "未开启"
            }
        }
    }

    /**
     * The three stroke glyphs, built once per slot size in slot coordinates and
     * replayed per frame up to a fraction of their stroke length.
     */
    private class Glyphs {
        private val check = listOf(Path(), Path())
        private val minus = Path()
        private val cross = listOf(Path(), Path())
        private val measures = HashMap<Path, PathMeasure>()
        private var width = 0f
        private var height = 0f

        fun ensure(w: Float, h: Float) {
            if (width == w && height == h) return
            width = w
            height = h
            val unit = minOf(w, h)
            val cx = w / 2f
            val cy = h / 2f
            // Glyph footprint ~25dp inside a 52dp slot.
            val half = unit * 0.24f

            check[0].reset()
            check[0].moveTo(cx - half * 0.95f, cy + half * 0.05f)
            check[0].lineTo(cx - half * 0.15f, cy + half * 0.85f)
            check[1].reset()
            check[1].moveTo(cx - half * 0.15f, cy + half * 0.85f)
            check[1].lineTo(cx + half * 1.02f, cy - half * 0.82f)

            minus.reset()
            minus.moveTo(cx - half, cy)
            minus.lineTo(cx + half, cy)

            cross[0].reset()
            cross[0].moveTo(cx - half * 0.72f, cy - half * 0.72f)
            cross[0].lineTo(cx + half * 0.72f, cy + half * 0.72f)
            cross[1].reset()
            cross[1].moveTo(cx + half * 0.72f, cy - half * 0.72f)
            cross[1].lineTo(cx - half * 0.72f, cy + half * 0.72f)

            for (path in check + minus + cross) measures[path] = PathMeasure(path, false)
        }

        /** Draws one glyph's strokes up to [fraction] of their length. */
        fun draw(canvas: Canvas, paint: Paint, box: RectF, glyph: GlyphKind, fraction: Float) {
            if (fraction <= 0f) return
            val paths = when (glyph) {
                GlyphKind.CHECK -> check
                GlyphKind.CROSS -> cross
                GlyphKind.MINUS -> listOf(minus)
            }
            val drawn = Path()
            for ((i, path) in paths.withIndex()) {
                val local = (fraction * paths.size - i).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val measure = measures[path] ?: continue
                measure.getSegment(0f, measure.length * local, drawn, true)
            }
            // Glyphs were built in slot coordinates; translate into the box.
            val save = canvas.save()
            canvas.translate(box.left, box.top)
            canvas.drawPath(drawn, paint)
            canvas.restoreToCount(save)
        }
    }
}
