// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider

/**
 * QQ 自己的底栏，原地不动 —— 图标、红点、点击都还是 QQ 的 —— 只让它浮起来变成一颗胶囊，
 * 并去掉不要的页签。浮起来之后的玻璃**借 QQ 自己那层模糊**，不自绘：
 * 上一版自己录内容再折射，在你这台机器上就是一团糊，那条路不走了。
 *
 * 底栏按结构认：屏幕下方四分之一里、高度不到屏幕四分之一、有两个以上页签的那个 ViewGroup。
 * 认不出来时把候选写进报告，不猜。
 */
object Bar {
    private const val HOME = "com.tencent.mobileqq.activity.SplashActivity"
    private const val GUILD = "频道"
    private val FEED = setOf("动态", "小世界")

    object Capsule : Switch("glass_bar") {
        override val live get() = true
        override fun install() = refresh()
        override fun uninstall() = refresh()
        override fun onResume(activity: Activity) = attach(activity)
        override fun status() = barStatus()
    }

    object HideGuild : Switch("hide_tab_guild") {
        override val live get() = true
        override fun install() = refresh()
        override fun uninstall() = refresh()
        override fun onResume(activity: Activity) = attach(activity)
        override fun status() = barStatus()
    }

    object HideFeed : Switch("hide_tab_feed") {
        override val live get() = true
        override fun install() = refresh()
        override fun uninstall() = refresh()
        override fun onResume(activity: Activity) = attach(activity)
        override fun status() = barStatus()
    }

    private var applied: Applied? = null
    private var missed: String? = null

    private fun wanted() = Capsule.active || HideGuild.active || HideFeed.active

    private fun barStatus(): String {
        val a = applied?.takeIf { it.bar.isAttachedToWindow }
        if (a == null) return "没找到" + (missed?.let { " · 候选 $it" } ?: "")
        return "藏了 ${a.hidden}" + (a.pillError?.let { " · 滑块失败 $it" } ?: "")
    }

    private fun refresh() {
        val a = applied ?: return
        if (wanted()) a.sync() else { a.restore(); applied = null }
    }

    private fun attach(activity: Activity) {
        if (!wanted() || activity.javaClass.name != HOME) return
        val decor = activity.window?.decorView ?: return
        decor.post {
            val bar = find(decor)
            if (bar == null) {
                missed = describe(decor)
                return@post
            }
            missed = null
            if (applied?.bar !== bar) {
                applied?.restore()
                applied = Applied(bar)
            }
            applied?.sync()
        }
    }

    /** 屏幕下方四分之一里长得像页签栏的东西，取最深的一个（最具体的那个）。 */
    private fun find(root: View): ViewGroup? {
        val floor = root.height * 3 / 4
        val hits = ArrayList<Pair<ViewGroup, Int>>()
        fun go(v: View, depth: Int) {
            if (v is ViewGroup && v.height in 1..(root.height / 4) && Screen.windowY(v) >= floor && tabs(v) >= 2) {
                hits += v to depth
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) go(v.getChildAt(i), depth + 1)
        }
        go(root, 0)
        return hits.filter { it.first.javaClass.simpleName.contains("Tab") }.maxByOrNull { it.second }?.first
            ?: hits.maxByOrNull { it.second }?.first
    }

    private fun describe(root: View): String {
        val floor = root.height * 3 / 4
        val out = ArrayList<String>(3)
        fun go(v: View) {
            if (out.size >= 3) return
            if (v is ViewGroup && v.height in 1..(root.height / 3) && Screen.windowY(v) >= floor && tabs(v) >= 1) {
                out += "${v.javaClass.simpleName}(${v.width}x${v.height},${tabs(v)}项)"
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) go(v.getChildAt(i))
        }
        go(root)
        return if (out.isEmpty()) "无" else out.joinToString("/")
    }

    private fun tabs(v: ViewGroup): Int = (0 until v.childCount).count { looksLikeTab(v.getChildAt(it)) }

    private fun looksLikeTab(v: View): Boolean = v.isClickable || icon(v)

    private fun icon(v: View): Boolean =
        v is android.widget.ImageView || (v is ViewGroup && (0 until v.childCount).any { icon(v.getChildAt(it)) })

    private class Applied(val bar: ViewGroup) {
        private val lp = bar.layoutParams
        private val margins = (lp as? ViewGroup.MarginLayoutParams)
            ?.let { intArrayOf(it.leftMargin, it.rightMargin, it.bottomMargin) }
        private val outline = bar.outlineProvider
        private val clip = bar.clipToOutline
        private val elevation = bar.elevation
        private val saved = HashMap<View, Int>()
        private var down = false
        private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> filter() }

        var pillError: String? = null
        val hidden: Int get() = saved.size

        init {
            bar.addOnLayoutChangeListener(relayout)
        }

        fun sync() {
            filter()
            if (Capsule.active != down) if (Capsule.active) float() else sink()
        }

        private fun filter() {
            for (tab in items()) {
                val label = Screen.labels(tab, 2).firstOrNull()
                val hide = (HideGuild.active && label == GUILD) || (HideFeed.active && label != null && label in FEED)
                if (hide) {
                    saved.putIfAbsent(tab, tab.visibility)
                    if (tab.visibility != View.GONE) tab.visibility = View.GONE
                } else {
                    saved.remove(tab)?.let { if (tab.visibility != it) tab.visibility = it }
                }
            }
        }

        private fun float() {
            down = true
            bar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
            bar.clipToOutline = true
            bar.elevation = bar.resources.displayMetrics.density * 6f
            (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                val side = (18 * bar.resources.displayMetrics.density).toInt()
                it.leftMargin = margins!![0] + side
                it.rightMargin = margins[1] + side
                it.bottomMargin = margins[2] + (12 * bar.resources.displayMetrics.density).toInt()
                bar.layoutParams = it
            }
            pill(true)
        }

        private fun sink() {
            down = false
            bar.outlineProvider = outline
            bar.clipToOutline = clip
            bar.elevation = elevation
            (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.leftMargin = margins!![0]
                it.rightMargin = margins[1]
                it.bottomMargin = margins[2]
                bar.layoutParams = it
            }
            pill(false)
        }

        /**
         * 滑动胶囊就是 Material TabLayout 自己的选中指示器，QQTabLayout 继承它、也用它，
         * 所以点按和滑动都带在，弹性动画白送。
         */
        private fun pill(on: Boolean) = runCatching {
            pillError = null
            val c = bar.javaClass
            val int = Int::class.javaPrimitiveType!!
            val bool = Boolean::class.javaPrimitiveType!!
            if (on) {
                c.getMethod("setTabIndicatorFullWidth", bool).invoke(bar, true)
                c.getMethod("setSelectedTabIndicatorGravity", int).invoke(bar, 3) // stretch
                c.getMethod("setTabIndicatorAnimationMode", int).invoke(bar, 1) // elastic
                c.getMethod("setSelectedTabIndicator", Drawable::class.java).invoke(bar, Pill(bar))
                // 胶囊自己没有高度，不设这个它不显示。
                c.getMethod("setSelectedTabIndicatorHeight", int)
                    .invoke(bar, bar.height.coerceAtLeast((56 * bar.resources.displayMetrics.density).toInt()))
                runCatching {
                    c.getMethod("setTabRippleColor", ColorStateList::class.java).invoke(bar, ColorStateList.valueOf(0))
                }
            } else {
                c.getMethod("setSelectedTabIndicator", Drawable::class.java).invoke(bar, ColorDrawable(0))
                c.getMethod("setSelectedTabIndicatorGravity", int).invoke(bar, 0)
            }
        }.onFailure { pillError = it.toString().take(80) }

        fun restore() {
            bar.removeOnLayoutChangeListener(relayout)
            sink()
            saved.forEach { (v, visibility) -> v.visibility = visibility }
            saved.clear()
        }

        private fun items(): List<View> {
            val out = ArrayList<View>(4)
            fun go(v: View, depth: Int) {
                if (v.javaClass.name.endsWith("TabView")) { out += v; return }
                if (v is ViewGroup && depth > 0) for (i in 0 until v.childCount) go(v.getChildAt(i), depth - 1)
            }
            go(bar, 4)
            if (out.isEmpty()) for (i in 0 until bar.childCount) {
                val c = bar.getChildAt(i)
                if (c.isClickable || c is ViewGroup) out += c
            }
            return out
        }
    }

    /** 选中项的胶囊：一条半透明白，没描边。 */
    private class Pill(private val host: View) : Drawable() {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()
        private val inset = 4f * host.resources.displayMetrics.density

        override fun draw(canvas: android.graphics.Canvas) {
            rect.set(bounds)
            rect.inset(inset, inset)
            if (rect.width() <= 0f || rect.height() <= 0f) return
            val r = rect.height() / 2f
            fill.color = if (night()) 0x33FFFFFF else 0x8CFFFFFF.toInt()
            canvas.drawRoundRect(rect, r, r, fill)
        }

        private fun night(): Boolean = runCatching {
            Class.forName("com.tencent.mobileqq.utils.QQTheme", false, Core.loader)
                .getMethod("isNowThemeIsNight").invoke(null) as Boolean
        }.getOrDefault(host.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
