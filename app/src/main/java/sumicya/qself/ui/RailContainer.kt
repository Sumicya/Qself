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
 * Every [TitleValueCell] switch occupies the same [SettingsVisuals.RAIL_WIDTH]
 * slot at the trailing edge. Filled slots (on / failed) merge into continuous
 * segments across neighbouring rows; ghost (off) rows break them.
 *
 * Radius contract (mocked and confirmed on device):
 *  - trailing-side corners are always square — the enclosing card's own radius
 *    defines the outer silhouette;
 *  - the inner-leading corner is rounded at the start/end of every segment,
 *    including segments that begin or end at the card edge.
 *
 * The slot glyph itself is drawn by [SquareStateControl]; this container paints
 * only the fill behind it.
 */
open class RailContainer @JvmOverloads constructor(
    context: Context,
    private val paletteProvider: (() -> SettingsVisuals.Palette)? = null,
) : LinearLayout(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val slotRect = Rect()
    private val radii = FloatArray(8)

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

    /** Recomputes segments after a switch state change; cheap, safe from any child. */
    fun invalidateRail() = postInvalidateOnAnimation()

    override fun dispatchDraw(canvas: Canvas) {
        drawRail(canvas)
        super.dispatchDraw(canvas)
    }

    /**
     * One segment per filled run of slots. Corner rules follow the run: the
     * first and last child of a run round their inner-leading corners, the
     * rest stay square, so merged runs read as one block behind the glyphs.
     */
    private fun drawRail(canvas: Canvas) {
        val count = childCount
        if (count == 0) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val inner = SettingsVisuals.dp(context, SettingsVisuals.RAIL_RADIUS).toFloat()
        val palette = palette()
        val column = IntArray(count) { index ->
            (getChildAt(index) as? TitleValueCell)?.let { slotKind(it) } ?: EMPTY
        }
        // Path corner order: TL(0,1) TR(2,3) BR(4,5) BL(6,7); leading side rounds only.
        val topCorner = if (rtl) 2 to 3 else 0 to 1
        val bottomCorner = if (rtl) 4 to 5 else 6 to 7

        for (index in 0 until count) {
            val kind = column[index]
            if (kind == EMPTY) continue
            val cell = getChildAt(index) as? TitleValueCell ?: continue
            cell.switchView.getHitRect(slotRect)
            slotRect.offset(cell.left, cell.top)
            radii.fill(0f)
            if (index == 0 || column[index - 1] == EMPTY) {
                radii[topCorner.first] = inner
                radii[topCorner.second] = inner
            }
            if (index == count - 1 || column[index + 1] == EMPTY) {
                radii[bottomCorner.first] = inner
                radii[bottomCorner.second] = inner
            }
            path.reset()
            path.addRoundRect(
                slotRect.left.toFloat(), slotRect.top.toFloat(),
                slotRect.right.toFloat(), slotRect.bottom.toFloat(),
                radii, Path.Direction.CW)
            fill.color = if (kind == ERROR) palette.errorContainer else palette.primaryContainer
            canvas.drawPath(path, fill)
        }
    }

    private fun slotKind(cell: TitleValueCell): Int {
        if (!cell.isHasSwitch || cell.switchView.visibility != View.VISIBLE) return EMPTY
        return when {
            cell.hasError || cell.isUnavailable -> ERROR
            cell.isChecked -> ON
            else -> EMPTY // ghost: transparent slot, segment break
        }
    }

    companion object {
        private const val EMPTY = 0
        private const val ON = 1
        private const val ERROR = 2

        /** Walks up to the nearest [RailContainer], so a child control can request a redraw. */
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
