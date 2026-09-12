/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.appcompat.widget.AppCompatCheckBox

/** Native checkable semantics, 48dp hit target, centered square visual: ✓ / × / −. */
class SquareStateControl(context: Context) : AppCompatCheckBox(context) {
    var edgeAttached = false
    private val shape = android.graphics.Path()
    var unavailable = false
        set(value) { field = value; refreshDrawableState(); invalidate() }
    var failed = false
        set(value) { field = value; refreshDrawableState(); invalidate() }
    var paletteOverride: SettingsVisuals.Palette? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: android.animation.ValueAnimator? = null
    private var progress = 1f
    private var previousChecked = false
    fun setCheckedWithoutAnimation(checked: Boolean) {
        animator?.cancel(); progress = 1f; super.setChecked(checked); invalidate()
    }
    override fun setChecked(checked: Boolean) {
        if (isChecked == checked) return
        val before = isChecked
        super.setChecked(checked)
        animator?.cancel()
        if (!isLaidOut || !isAttachedToWindow || !SettingsMotion.enabled()) { progress = 1f; invalidate(); return }
        previousChecked = before
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SettingsMotion.duration(context, true)
            interpolator = SettingsMotion.easing(context)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel(); progress = 1f; super.onDetachedFromWindow() }
    init {
        buttonDrawable = null
        text = ""
        setPadding(0, 0, 0, 0)
        minimumWidth = SettingsVisuals.dp(context, 48)
        minimumHeight = SettingsVisuals.dp(context, 48)
    }
    override fun onDraw(canvas: Canvas) {
        val p = paletteOverride ?: SettingsVisuals.palette(context)
        val size = SettingsVisuals.dp(context, 44).toFloat()
        val x = if (edgeAttached) 0f else (width - size) / 2f
        val y = if (edgeAttached) 0f else (height - size) / 2f
        paint.style = Paint.Style.FILL
        val checkedFraction = if (progress == 1f) { if (isChecked) 1f else 0f }
            else if (isChecked) progress else 1f - progress
        val background = if (failed) com.google.android.material.color.MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorErrorContainer, p.container)
            else androidx.core.graphics.ColorUtils.blendARGB(p.surface, p.container, .35f + .65f * checkedFraction)
        paint.color = background
        paint.alpha = 255
        if (edgeAttached) {
            val radius = SettingsVisuals.dp(context, 12).toFloat()
            val left = layoutDirection != LAYOUT_DIRECTION_RTL
            shape.reset()
            shape.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(),
                if (left) floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                else floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f), android.graphics.Path.Direction.CW)
            canvas.drawPath(shape, paint)
        } else canvas.drawRoundRect(x, y, x + size, y + size, SettingsVisuals.dp(context, 12).toFloat(), SettingsVisuals.dp(context, 12).toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = p.rim
        paint.alpha = ((1f - checkedFraction) * 255).toInt()
        if (!edgeAttached) canvas.drawRoundRect(x, y, x + size, y + size, SettingsVisuals.dp(context, 12).toFloat(), SettingsVisuals.dp(context, 12).toFloat(), paint)
        paint.style = Paint.Style.FILL
        val foreground = if (failed) com.google.android.material.color.MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnErrorContainer, p.text)
            else androidx.core.graphics.ColorUtils.blendARGB(p.text, p.onContainer, checkedFraction)
        paint.color = SettingsVisuals.stateForeground(foreground, background)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = SettingsVisuals.dp(context, 22).toFloat()
        val glyph = if (unavailable || failed) "−" else if (isChecked) "✓" else "×"
        val fm = paint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        if (progress < 1f && !unavailable && !failed) {
            paint.alpha = ((1f - progress) * 255).toInt()
            canvas.drawText(if (previousChecked) "✓" else "×", width / 2f, baseline, paint)
        }
        paint.alpha = if (unavailable || failed) 255 else (progress * 255).toInt()
        val save = canvas.save()
        val scale = .85f + .15f * progress
        canvas.scale(scale, scale, width / 2f, height / 2f)
        canvas.drawText(glyph, width / 2f, baseline, paint)
        canvas.restoreToCount(save)
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (Build.VERSION.SDK_INT >= 30) info.stateDescription = when {
            failed -> "初始化或运行出错，配置${if (isChecked) "开启" else "关闭"}"
            unavailable -> "当前不支持，配置${if (isChecked) "开启" else "关闭"}"
            isChecked -> "开启"
            else -> "关闭"
        }
    }
}
