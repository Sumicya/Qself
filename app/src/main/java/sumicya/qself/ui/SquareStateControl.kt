/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.appcompat.widget.AppCompatCheckBox

/** Native checkable semantics, 48dp hit target, centered square visual: ✓ / × / −. */
class SquareStateControl(context: Context) : AppCompatCheckBox(context) {
    var unavailable = false
        set(value) { field = value; refreshDrawableState(); invalidate() }
    var failed = false
        set(value) { field = value; refreshDrawableState(); invalidate() }
    var paletteOverride: SettingsVisuals.Palette? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        buttonDrawable = null
        text = ""
        setPadding(0, 0, 0, 0)
        minimumWidth = SettingsVisuals.dp(context, 48)
        minimumHeight = SettingsVisuals.dp(context, 48)
    }
    override fun onDraw(canvas: Canvas) {
        val p = paletteOverride ?: SettingsVisuals.palette(context)
        val size = SettingsVisuals.dp(context, 32).toFloat()
        val x = (width - size) / 2f
        val y = (height - size) / 2f
        paint.style = Paint.Style.FILL
        paint.color = p.container
        canvas.drawRoundRect(x, y, x + size, y + size, 4f, 4f, paint)
        paint.color = if (failed) { if (p.dark) 0xfff2b8b5.toInt() else 0xffb3261e.toInt() } else p.onContainer
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = SettingsVisuals.dp(context, 22).toFloat()
        val glyph = if (unavailable || failed) "−" else if (isChecked) "✓" else "×"
        val fm = paint.fontMetrics
        canvas.drawText(glyph, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, paint)
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
