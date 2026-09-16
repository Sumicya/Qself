/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Build
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatCheckBox

/**
 * Trailing state slot for a feature row — a fixed 52dp square that the row
 * measures flush against its trailing edge and card wall.
 *
 * Three meanings, drawn as vector strokes rather than font glyphs (font
 * glyphs differ in curvature and optical centring):
 *  - checked: straight two-stroke check in the primary container;
 *  - unchecked (ghost): a neutral minus with NO background block;
 *  - failed / unavailable: an X in the error container.
 *
 * Inside a [RailContainer] the fill is painted by the merged rail itself
 * (segments join across rows and share corner rules); this view then draws
 * only the glyph. Outside a rail (standalone RecyclerView cards) it paints
 * a fully rounded 14dp block on its own.
 */
class SquareStateControl(context: Context) : AppCompatCheckBox(context) {
    var unavailable = false
        set(value) { field = value; refreshDrawableState(); invalidate(); RailContainer.invalidateAncestor(this) }
    var failed = false
        set(value) { field = value; refreshDrawableState(); invalidate(); RailContainer.invalidateAncestor(this) }
    var paletteOverride: SettingsVisuals.Palette? = null

    private val tileBox = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Glyph paths are rebuilt when the drawable box changes.
    private val checkPaths = listOf(Path(), Path())
    private val minusPath = Path()
    private val crossPaths = listOf(Path(), Path())
    private val checkMeasures = checkPaths.map { PathMeasure(it, false) }
    private val minusMeasure = PathMeasure(minusPath, false)
    private val crossMeasures = crossPaths.map { PathMeasure(it, false) }
    private var glyphBoxW = 0f
    private var glyphBoxH = 0f

    private var animator: ValueAnimator? = null
    private var morph = 1f          // 0..1 of the incoming glyph
    private var previousKind = -1

    private val inRail: Boolean
        get() {
            var current: ViewGroup? = parent as? ViewGroup
            while (current != null) {
                if (current is RailContainer) return true
                current = current.parent as? ViewGroup
            }
            return false
        }

    private val kind: Int
        get() = when {
            failed || unavailable -> CROSS
            isChecked -> CHECK
            else -> MINUS
        }

    companion object {
        private const val MINUS = 0
        private const val CHECK = 1
        private const val CROSS = 2
    }

    fun setCheckedWithoutAnimation(checked: Boolean) {
        animator?.cancel()
        morph = 1f
        previousKind = -1
        super.setChecked(checked)
        invalidate()
        RailContainer.invalidateAncestor(this)
    }

    override fun setChecked(checked: Boolean) {
        if (isChecked == checked) return
        val beforeKind = kind
        super.setChecked(checked)
        RailContainer.invalidateAncestor(this)
        animator?.cancel()
        if (!isLaidOut || !isAttachedToWindow || !SettingsMotion.enabled()) {
            morph = 1f; previousKind = -1; invalidate(); return
        }
        previousKind = beforeKind
        morph = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SettingsMotion.duration(context, true)
            interpolator = SettingsMotion.easing(context)
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); morph = 1f; previousKind = -1
        super.onDetachedFromWindow()
    }

    init {
        buttonDrawable = null
        text = ""
        setPadding(0, 0, 0, 0)
        minimumWidth = SettingsVisuals.dp(context, SettingsVisuals.RAIL_WIDTH)
        minimumHeight = SettingsVisuals.dp(context, SettingsVisuals.ROW_HEIGHT)
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        val p = paletteOverride ?: SettingsVisuals.palette(context)
        val density = resources.displayMetrics.density
        val rail = inRail
        val slot = SettingsVisuals.dp(context, SettingsVisuals.RAIL_WIDTH).toFloat()

        if (rail) {
            // The rail container owns the fill; the slot covers the full view.
            tileBox.set(0f, 0f, width.toFloat(), height.toFloat())
        } else {
            val boxH = minOf(slot, height.toFloat())
            val top = (height - boxH) / 2f
            tileBox.set(width - slot, top, width.toFloat(), top + boxH)
        }
        val radius = SettingsVisuals.RAIL_RADIUS * density

        val targetKind = kind
        val errored = targetKind == CROSS
        val fillColor = if (errored) p.errorContainer else p.primaryContainer

        // Standalone slots paint their own block; the rail paints merged fills.
        if (!rail && (isChecked || errored)) {
            fillPaint.color = fillColor
            canvas.drawRoundRect(tileBox, radius, radius, fillPaint)
        }

        // ---- glyphs ----
        ensureGlyphs(tileBox.width(), tileBox.height())
        val glyphColor = when (targetKind) {
            CROSS -> p.onErrorContainer
            CHECK -> if (rail) p.onPrimaryContainer
                     else SettingsVisuals.stateForeground(p.onPrimaryContainer, fillColor)
            else -> p.outline
        }
        paint.strokeWidth = 2.8f * density
        paint.color = glyphColor

        val settled = morph >= 1f || previousKind < 0
        val pop = if (settled) 1f else 0.85f + 0.15f * popCurve(morph)
        val save = canvas.save()
        canvas.scale(pop, pop, width / 2f, height / 2f)

        // Outgoing glyph fades through the first 40% of the morph.
        if (!settled && previousKind in MINUS..CROSS) {
            val out = (1f - morph / 0.4f).coerceIn(0f, 1f)
            if (out > 0) {
                paint.alpha = (out * 0.6f * 255).toInt()
                drawGlyph(canvas, tileBox, previousKind, 1f)
            }
        }

        paint.alpha = 255
        val drawFraction = if (settled || errored) 1f else morph
        drawGlyph(canvas, tileBox, targetKind, drawFraction)
        canvas.restoreToCount(save)
    }

    /** Slight overshoot for the incoming glyph. */
    private fun popCurve(f: Float): Float {
        val s = f.coerceIn(0f, 1f)
        return 1f + 0.18f * Math.sin((s * Math.PI).toDouble()).toFloat() * (1f - s)
    }

    /** Builds the three glyphs centred inside a slot of the given size. */
    private fun ensureGlyphs(w: Float, h: Float) {
        if (glyphBoxW == w && glyphBoxH == h) return
        glyphBoxW = w; glyphBoxH = h
        val u = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        // Glyph footprint ~25dp inside a 52dp slot.
        val half = u * 0.24f

        // Check: two straight strokes joined at the bottom; no arc.
        checkPaths[0].reset()
        checkPaths[0].moveTo(cx - half * 0.95f, cy + half * 0.05f)
        checkPaths[0].lineTo(cx - half * 0.15f, cy + half * 0.85f)
        checkPaths[1].reset()
        checkPaths[1].moveTo(cx - half * 0.15f, cy + half * 0.85f)
        checkPaths[1].lineTo(cx + half * 1.02f, cy - half * 0.82f)

        // Minus: one horizontal bar, optically centred.
        minusPath.reset()
        minusPath.moveTo(cx - half, cy)
        minusPath.lineTo(cx + half, cy)

        // Cross: two diagonal bars.
        crossPaths[0].reset()
        crossPaths[0].moveTo(cx - half * 0.72f, cy - half * 0.72f)
        crossPaths[0].lineTo(cx + half * 0.72f, cy + half * 0.72f)
        crossPaths[1].reset()
        crossPaths[1].moveTo(cx + half * 0.72f, cy - half * 0.72f)
        crossPaths[1].lineTo(cx - half * 0.72f, cy + half * 0.72f)

        checkPaths.forEachIndexed { i, path -> checkMeasures[i].setPath(path, false) }
        minusMeasure.setPath(minusPath, false)
        crossPaths.forEachIndexed { i, path -> crossMeasures[i].setPath(path, false) }
    }

    /** Draws one glyph's strokes up to [fraction] of their length. */
    private fun drawGlyph(canvas: Canvas, box: RectF, glyph: Int, fraction: Float) {
        if (fraction <= 0f) return
        val (paths, measures) = when (glyph) {
            CHECK -> checkPaths to checkMeasures
            CROSS -> crossPaths to crossMeasures
            else -> listOf(minusPath) to listOf(minusMeasure)
        }
        val n = paths.size
        val drawn = Path()
        for (i in 0 until n) {
            val local = (fraction * n - i).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val len = measures[i].length
            measures[i].getSegment(0f, len * local, drawn, true)
        }
        // Glyphs were built in slot coordinates; translate into the box.
        val save = canvas.save()
        canvas.translate(box.left, box.top)
        canvas.drawPath(drawn, paint)
        canvas.restoreToCount(save)
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (Build.VERSION.SDK_INT >= 30) info.stateDescription = when {
            failed -> "初始化或运行出错，配置${if (isChecked) "开启" else "关闭"}"
            unavailable -> "与当前宿主版本不兼容，开关已锁定"
            isChecked -> "已开启"
            else -> "未开启"
        }
    }
}
