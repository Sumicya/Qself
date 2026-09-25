// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.TextView

/**
 * QQ's own bottom bar, kept alive and native (icons, unread, clicks all stay QQ's),
 * turned into a floating capsule whose background is a live blur of the page behind it.
 * Also hides unwanted tabs. Everything is recorded and undone when switched off.
 */
object HomeBar {
    private const val HOME = "com.tencent.mobileqq.activity.SplashActivity"
    private val barNames = setOf("QQTabLayout", "QQTabWidget")
    private val guildLabels = setOf("频道")
    private val feedLabels = setOf("动态", "小世界")

    object Glass : Feature("glass_bar") {
        override fun install() = refresh()
        override fun uninstall() = refresh()
        override fun onResume(activity: Activity) = attach(activity)
    }

    object HideGuild : Feature("hide_tab_guild") {
        override fun install() = refresh()
        override fun uninstall() = refresh()
        override fun onResume(activity: Activity) = attach(activity)
    }

    object HideFeed : Feature("hide_tab_feed") {
        override fun install() = refresh()
        override fun uninstall() = refresh()
        override fun onResume(activity: Activity) = attach(activity)
    }

    private var applied: Applied? = null

    private fun wanted() = Glass.active || HideGuild.active || HideFeed.active

    private fun refresh() {
        val a = applied ?: return
        if (wanted()) a.sync() else { a.restore(); applied = null }
    }

    private fun attach(activity: Activity) {
        if (activity.javaClass.name != HOME || !wanted()) return
        val decor = activity.window?.decorView ?: return
        decor.post {
            val bar = find(decor) ?: return@post
            if (applied?.bar !== bar) {
                applied?.restore()
                applied = Applied(bar)
            }
            applied?.sync()
        }
    }

    private fun find(v: View): ViewGroup? {
        if (v is ViewGroup && v.javaClass.simpleName in barNames) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun dp(v: View, x: Float) = x * v.resources.displayMetrics.density

    private class Applied(val bar: ViewGroup) {
        private val parent = bar.parent as ViewGroup
        private val lp = bar.layoutParams
        private val margins = (lp as? ViewGroup.MarginLayoutParams)?.let { intArrayOf(it.leftMargin, it.rightMargin, it.bottomMargin) }
        private val background = bar.background
        private val outline = bar.outlineProvider
        private val clip = bar.clipToOutline
        private val elevation = bar.elevation
        private val hostBlur = (0 until parent.childCount).map(parent::getChildAt)
            .filter { it !== bar && it.javaClass.simpleName.contains("Blur") }
            .associateWith { it.visibility }
        private val source: View? = (0 until parent.childCount).map(parent::getChildAt)
            .firstOrNull { it.javaClass.name.contains("ViewPager") }
            ?: (0 until parent.childCount).map(parent::getChildAt)
                .filter { it !== bar && it !in hostBlur }.maxByOrNull { it.width * it.height }
        private val hiddenTabs = HashMap<View, Int>()
        private var glassOn = false
        private var glass: GlassDrawable? = null

        private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> filterTabs() }
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            // Page content moved: re-record the backdrop within this same frame.
            if (glassOn) glass?.invalidateSelf()
            true
        }

        init {
            bar.addOnLayoutChangeListener(relayout)
        }

        fun sync() {
            filterTabs()
            if (Glass.active != glassOn) if (Glass.active) glassUp() else glassDown()
        }

        private fun filterTabs() {
            for (tab in tabs(bar)) {
                val label = labelOf(tab)
                val hide = (HideGuild.active && label in guildLabels) || (HideFeed.active && label in feedLabels)
                if (hide) {
                    hiddenTabs.putIfAbsent(tab, tab.visibility)
                    if (tab.visibility != View.GONE) tab.visibility = View.GONE
                } else hiddenTabs.remove(tab)?.let { if (tab.visibility != it) tab.visibility = it }
            }
        }

        private fun glassUp() {
            val src = source ?: return
            glassOn = true
            val d = GlassDrawable(bar, src)
            glass = d
            hostBlur.keys.forEach { it.visibility = View.INVISIBLE }
            bar.background = d
            bar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) =
                    o.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
            bar.clipToOutline = true
            bar.elevation = dp(bar, 6f)
            (lp as? ViewGroup.MarginLayoutParams)?.let {
                val side = dp(bar, 18f).toInt()
                it.leftMargin = margins!![0] + side
                it.rightMargin = margins[1] + side
                it.bottomMargin = margins[2] + dp(bar, 12f).toInt()
                bar.layoutParams = it
            }
            bar.viewTreeObserver.addOnPreDrawListener(preDraw)
        }

        private fun glassDown() {
            if (!glassOn) return
            glassOn = false
            runCatching { bar.viewTreeObserver.removeOnPreDrawListener(preDraw) }
            bar.background = background
            bar.outlineProvider = outline
            bar.clipToOutline = clip
            bar.elevation = elevation
            (lp as? ViewGroup.MarginLayoutParams)?.let {
                it.leftMargin = margins!![0]; it.rightMargin = margins[1]; it.bottomMargin = margins[2]
                bar.layoutParams = it
            }
            hostBlur.forEach { (v, vis) -> v.visibility = vis }
            glass?.release()
            glass = null
        }

        fun restore() {
            bar.removeOnLayoutChangeListener(relayout)
            glassDown()
            hiddenTabs.forEach { (v, vis) -> v.visibility = vis }
            hiddenTabs.clear()
        }

        private fun tabs(v: View): List<View> {
            if (v.javaClass.name.endsWith("TabView")) return listOf(v)
            if (v !is ViewGroup) return emptyList()
            return (0 until v.childCount).flatMap { tabs(v.getChildAt(it)) }
        }

        private fun labelOf(v: View): String? {
            v.contentDescription?.toString()?.let { d -> (guildLabels + feedLabels).firstOrNull { d.contains(it) }?.let { return it } }
            if (v is TextView) return v.text?.toString()?.trim()
            if (v is ViewGroup) for (i in 0 until v.childCount) labelOf(v.getChildAt(i))?.takeIf { it.isNotEmpty() }?.let { return it }
            return null
        }
    }

    /**
     * Draws [source]'s pixels that sit behind [host], blurred on the GPU (RenderNode + RenderEffect),
     * then a translucent tint and a hairline. Children of [source] are replayed from their existing
     * display lists, so recording is cheap. [host] is not inside [source]: no feedback loop.
     */
    private class GlassDrawable(private val host: View, private val source: View) : Drawable() {
        private val node = RenderNode("qself-glass")
        private val radius = dp(host, 28f)
        private val hostLoc = IntArray(2)
        private val srcLoc = IntArray(2)
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(host, 1f)
        }
        private val rect = RectF()
        private var drawing = false

        init {
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val night = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            if (canvas.isHardwareAccelerated && !drawing && source.isAttachedToWindow) {
                drawing = true
                try {
                    host.getLocationInWindow(hostLoc)
                    source.getLocationInWindow(srcLoc)
                    node.setPosition(0, 0, b.width(), b.height())
                    val rc = node.beginRecording()
                    try {
                        rc.translate((srcLoc[0] - hostLoc[0]).toFloat(), (srcLoc[1] - hostLoc[1]).toFloat())
                        source.draw(rc)
                    } finally {
                        node.endRecording()
                    }
                    canvas.drawRenderNode(node)
                } catch (_: Throwable) {
                } finally {
                    drawing = false
                }
            }
            tint.color = if (night) 0x99161618.toInt() else 0x8CF7F7FA.toInt()
            edge.color = if (night) 0x33FFFFFF else 0x66FFFFFF
            rect.set(b)
            val r = b.height() / 2f
            canvas.drawRoundRect(rect, r, r, tint)
            rect.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
            canvas.drawRoundRect(rect, r, r, edge)
        }

        fun release() = node.discardDisplayList()

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
