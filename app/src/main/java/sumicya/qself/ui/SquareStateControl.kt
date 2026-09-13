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
import androidx.appcompat.widget.AppCompatCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * Leading state tile for a feature row.
 *
 * Three meanings, drawn as vector strokes rather than font glyphs (font
 * glyphs differ in curvature and optical centring):
 *  - checked: rounded check with a gently arched long stroke;
 *  - unchecked: a perfectly centred minus (the default state);
 *  - failed: an X in the error palette; unavailable shares the minus but
 *    renders at half opacity.
 *
 * The tile is allowed to grow vertically with the row ("square can extend
 * up/down") instead of hovering as a fixed 44dp square.
 */
class SquareStateControl(context: Context) : AppCompatCheckBox(context) {
    var unavailable = false
        set(value) { field = value; refreshDrawableState(); invalidate() }
    var failed = false
        set(value) { field = value; refreshDrawableState(); invalidate() }
    var paletteOverride: SettingsVisuals.Palette? = null

    private val tileBox = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

    private val kind: Int
        get() = when {
            failed -> CROSS
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
    }

    override fun setChecked(checked: Boolean) {
        if (isChecked == checked) return
        val beforeKind = kind
        super.setChecked(checked)
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
        minimumWidth = SettingsVisuals.dp(context, 48)
        // Fallback only: the row measures this EXACTLY to its header height
        // so the tile stretches vertically with content.
        minimumHeight = SettingsVisuals.dp(context, 40)
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        val p = paletteOverride ?: SettingsVisuals.palette(context)
        val density = resources.displayMetrics.density
        // Visual tile: 44dp wide, filling the row's header height minus a
        // slim inset, centred in the 48dp-wide control.
        val tileW = 44f * density
        // The row already measures this view 6dp shorter than its header;
        // the tile fills that box, so it grows vertically with the row.
        val tileH = height.toFloat().coerceAtLeast(40f * density)
        val left = (width - tileW) / 2f
        val top = (height - tileH) / 2f
        tileBox.set(left, top, left + tileW, top + tileH)
        val radius = 14f * density

        val targetKind = kind
        val settled = morph >= 1f || previousKind < 0
        val fillFraction = when {
            failed -> 1f
            settled -> if (isChecked) 1f else 0f
            else -> if (targetKind == CHECK) morph else 1f - morph
        }

        // ---- tile background, stretched vertically with the row ----
        val errorContainer = MaterialColors.getColor(context,
            MaterialR.attr.colorErrorContainer, p.container)
        val activeContainer = MaterialColors.getColor(context,
            MaterialR.attr.colorSecondaryContainer, p.container)
        val resting = androidx.core.graphics.ColorUtils.blendARGB(
            p.surface, p.container, 0.18f)
        val tileColor = when {
            failed -> errorContainer
            else -> androidx.core.graphics.ColorUtils.blendARGB(
                resting, activeContainer, fillFraction)
        }
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tileColor
        val overallAlpha = if (unavailable && !failed) 128 else 255
        fillPaint.alpha = overallAlpha
        canvas.drawRoundRect(tileBox, radius, radius, fillPaint)

        // ---- glyphs ----
        ensureGlyphs(tileW, tileH)
        val box = tileBox
        val onActive = MaterialColors.getColor(context,
            MaterialR.attr.colorOnSecondaryContainer, p.onContainer)
        val errorOn = MaterialColors.getColor(context,
            MaterialR.attr.colorOnErrorContainer, p.text)
        val glyphColor = when (targetKind) {
            CROSS -> errorOn
            CHECK -> SettingsVisuals.stateForeground(onActive, tileColor)
            else -> p.secondary
        }
        paint.strokeWidth = 2.6f * density
        paint.color = glyphColor

        val pop = if (settled) 1f else 0.85f + 0.15f * popCurve(morph)
        val save = canvas.save()
        canvas.scale(pop, pop, width / 2f, height / 2f)

        // Outgoing glyph fades through the first 40% of the morph.
        if (!settled && previousKind in MINUS..CROSS) {
            val out = (1f - morph / 0.4f).coerceIn(0f, 1f)
            if (out > 0f) {
                paint.alpha = (overallAlpha * out * 0.6f).toInt()
                drawGlyph(canvas, box, previousKind, 1f)
            }
        }

        paint.alpha = overallAlpha
        // Failed glyphs simply appear; live state changes draw themselves.
        val drawFraction = if (settled || failed) 1f else morph
        drawGlyph(canvas, box, targetKind, drawFraction)
        canvas.restoreToCount(save)
    }

    /** Slight overshoot for the incoming glyph. */
    private fun popCurve(f: Float): Float {
        val s = f.coerceIn(0f, 1f)
        return 1f + 0.18f * Math.sin((s * Math.PI).toDouble()).toFloat() * (1f - s)
    }

    /** Builds the three glyphs centred inside a tile of the given size. */
    private fun ensureGlyphs(w: Float, h: Float) {
        if (glyphBoxW == w && glyphBoxH == h) return
        glyphBoxW = w; glyphBoxH = h
        val u = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        // Glyph footprint ~ 22dp inside a 44dp tile.
        val half = u * 0.24f

        // Check: short down stroke then a long, gently arched up stroke
        // (curvature comes from the quad + round caps/join).
        checkPaths[0].reset()
        checkPaths[0].moveTo(cx - half * 0.95f, cy + half * 0.05f)
        checkPaths[0].lineTo(cx - half * 0.15f, cy + half * 0.85f)
        checkPaths[1].reset()
        checkPaths[1].moveTo(cx - half * 0.15f, cy + half * 0.85f)
        checkPaths[1].quadTo(cx + half * 0.18f, cy + half * 0.62f,
            cx + half * 1.02f, cy - half * 0.82f)

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
        // Glyphs were built in tile coordinates; translate into the box.
        val save = canvas.save()
        canvas.translate(box.left, box.top)
        canvas.drawPath(drawn, paint)
        canvas.restoreToCount(save)
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (Build.VERSION.SDK_INT >= 30) info.stateDescription = when {
            failed -> "初始化或运行出错，配置${if (isChecked) "开启" else "关闭"}"
            unavailable -> "当前不支持，配置${if (isChecked) "开启" else "关闭"}"
            isChecked -> "已开启"
            else -> "未开启"
        }
    }
}
