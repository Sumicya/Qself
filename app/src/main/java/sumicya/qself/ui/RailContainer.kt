/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.qauxv.dsl.cell.TitleValueCell

/**
 * Transparent vertical list that paints the merged trailing state rail.
 *
 * Every [TitleValueCell] switch occupies the same 52dp-wide slot at the
 * trailing edge. Filled slots (checked / failed) merge into continuous
 * segments across neighbouring rows; ghost (unchecked) rows break them.
 *
 * Radius contract (mocked and confirmed):
 *  - trailing-side corners are always square — the enclosing card's own
 *    radius defines the outer silhouette;
 *  - the inner-leading corner is rounded at the start/end of every segment,
 *    including segments that begin or end at the card edge.
 *
 * The slot glyph itself is drawn by [SquareStateControl]; this container
 * only paints the fill behind it.
 */
open class RailContainer @JvmOverloads constructor(
    context: Context,
    private val paletteProvider: (() -> SettingsVisuals.Palette)? = null,
) : LinearLayout(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val slotRect = Rect()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    private fun palette(): SettingsVisuals.Palette =
        paletteProvider?.invoke() ?: SettingsVisuals.palette(context)

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        invalidateRail()
    }

    override fun onViewRemoved(child: View?) {
        super.onViewRemoved(child)
        invalidateRail()
    }

    /** Recomputes segments after a switch state change; cheap, safe to call from any child. */
    fun invalidateRail() = postInvalidateOnAnimation()

    override fun dispatchDraw(canvas: Canvas) {
        drawRail(canvas)
        super.dispatchDraw(canvas)
    }

    private fun slotState(cell: TitleValueCell): Int {
        if (!cell.isHasSwitch || cell.switchView.visibility != View.VISIBLE) return NONE
        return when {
            cell.hasError || cell.isUnavailable -> ERROR
            cell.isChecked -> CHECKED
            else -> NONE // ghost: transparent slot, segment break
        }
    }

    private fun drawRail(canvas: Canvas) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val inner = SettingsVisuals.dp(context, SettingsVisuals.RAIL_RADIUS).toFloat()
        val states = IntArray(childCount) { i ->
            (getChildAt(i) as? TitleValueCell)?.let { slotState(it) } ?: NONE
        }
        val p = palette()
        val radii = FloatArray(8)
        // Path corner order: TL(0,1) TR(2,3) BR(4,5) BL(6,7); leading side rounds only.
        val (topA, topB) = if (rtl) 2 to 3 else 0 to 1
        val (botA, botB) = if (rtl) 4 to 5 else 6 to 7
        for (i in 0 until childCount) {
            val state = states[i]
            if (state == NONE) continue
            val child = getChildAt(i)
            child.switchView.getHitRect(slotRect)
            slotRect.offset(child.left, child.top)
            val roundTop = i == 0 || states[i - 1] == NONE
            val roundBottom = i == states.lastIndex || states[i + 1] == NONE
            radii.fill(0f)
            if (roundTop) { radii[topA] = inner; radii[topB] = inner }
            if (roundBottom) { radii[botA] = inner; radii[botB] = inner }
            path.reset()
            path.addRoundRect(
                slotRect.left.toFloat(), slotRect.top.toFloat(),
                slotRect.right.toFloat(), slotRect.bottom.toFloat(),
                radii, Path.Direction.CW)
            fillPaint.color = if (state == ERROR) p.errorContainer else p.primaryContainer
            canvas.drawPath(path, fillPaint)
        }
    }

    /** Walks up to the nearest [RailContainer], if any, so a child control can request a redraw. */
    companion object {
        private const val NONE = 0
        private const val CHECKED = 1
        private const val ERROR = 2

        @JvmStatic
        fun invalidateAncestor(view: View) {
            var current: ViewGroup? = view.parent as? ViewGroup
            while (current != null) {
                if (current is RailContainer) {
                    current.invalidateRail()
                    return
                }
                current = current.parent as? ViewGroup
            }
        }
    }
}
