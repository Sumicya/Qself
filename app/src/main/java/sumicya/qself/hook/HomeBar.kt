// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
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
        private val lp = bar.layoutParams
        private val margins = (lp as? ViewGroup.MarginLayoutParams)?.let { intArrayOf(it.leftMargin, it.rightMargin, it.bottomMargin) }
        private val hiddenTabs = HashMap<View, Int>()
        private var glass: GlassSurface? = null

        private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> filterTabs() }

        init {
            bar.addOnLayoutChangeListener(relayout)
        }

        fun sync() {
            filterTabs()
            if (Glass.active != (glass != null)) if (Glass.active) glassUp() else glassDown()
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
            glass = GlassSurface(bar, GlassSpec(capsule = true), elevationDp = 6f)
            (lp as? ViewGroup.MarginLayoutParams)?.let {
                val side = dp(bar, 18f).toInt()
                it.leftMargin = margins!![0] + side
                it.rightMargin = margins[1] + side
                it.bottomMargin = margins[2] + dp(bar, 12f).toInt()
                bar.layoutParams = it
            }
            indicator(true)
        }

        private fun glassDown() {
            val g = glass ?: return
            glass = null
            g.restore()
            (lp as? ViewGroup.MarginLayoutParams)?.let {
                it.leftMargin = margins!![0]; it.rightMargin = margins[1]; it.bottomMargin = margins[2]
                bar.layoutParams = it
            }
            indicator(false)
        }

        /**
         * The sliding pill is Material TabLayout's own selected-tab indicator (QQTabLayout extends it and
         * selects through it), so it follows taps and pager swipes with the elastic animation for free.
         */
        private fun indicator(on: Boolean) = runCatching {
            val c = bar.javaClass
            val int = Int::class.javaPrimitiveType!!
            val bool = Boolean::class.javaPrimitiveType!!
            if (on) {
                c.getMethod("setTabIndicatorFullWidth", bool).invoke(bar, true)
                c.getMethod("setSelectedTabIndicatorGravity", int).invoke(bar, 3) // stretch
                c.getMethod("setTabIndicatorAnimationMode", int).invoke(bar, 1) // elastic
                c.getMethod("setSelectedTabIndicator", Drawable::class.java).invoke(bar, PillDrawable(bar))
                runCatching { c.getMethod("setTabRippleColor", ColorStateList::class.java).invoke(bar, ColorStateList.valueOf(0)) }
            } else {
                c.getMethod("setSelectedTabIndicator", Drawable::class.java).invoke(bar, ColorDrawable(0))
                c.getMethod("setSelectedTabIndicatorGravity", int).invoke(bar, 0)
            }
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
}
