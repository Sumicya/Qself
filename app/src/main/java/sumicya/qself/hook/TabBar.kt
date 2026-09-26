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
 * QQ 自己的底栏，保持原样 —— 图标、未读红点、点击都还是 QQ 的 —— 只是浮起来做成盖在页面上的
 * 胶囊玻璃，并去掉不属于这里的页签。做过的每件事都记着，关掉开关就还回去。
 */
object TabBar {
    private const val HOME = "com.tencent.mobileqq.activity.SplashActivity"
    private val barNames = setOf("QQTabLayout", "QQTabWidget")
    private val guildLabels = setOf("频道")
    private val feedLabels = setOf("动态", "小世界")

    object Glass : Switch("glass_bar") {
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
    private var pillError: String? = null

    private fun wanted() = Glass.active || HideGuild.active || HideFeed.active

    private fun barStatus(): String {
        val bar = applied?.takeIf { it.bar.isAttachedToWindow }?.bar ?: return "底栏未找到"
        return "藏了 ${applied?.hiddenCount ?: 0}" + (pillError?.let { " · 滑块失败 $it" } ?: "")
    }

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

    private class Applied(val bar: ViewGroup) {
        private val lp = bar.layoutParams
        private val margins = (lp as? ViewGroup.MarginLayoutParams)?.let { intArrayOf(it.leftMargin, it.rightMargin, it.bottomMargin) }
        private val hiddenTabs = HashMap<View, Int>()
        private var glassed = false
        private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> filter() }

        val hiddenCount: Int get() = hiddenTabs.size

        init {
            bar.addOnLayoutChangeListener(relayout)
        }

        fun sync() {
            filter()
            if (Glass.active != glassed) if (Glass.active) glassUp() else glassDown()
        }

        private fun filter() {
            for (tab in tabs(bar)) {
                val label = labelOf(tab)
                val hide = (HideGuild.active && label in guildLabels) || (HideFeed.active && label in feedLabels)
                if (hide) {
                    hiddenTabs.putIfAbsent(tab, tab.visibility)
                    if (tab.visibility != View.GONE) tab.visibility = View.GONE
                } else {
                    hiddenTabs.remove(tab)?.let { if (tab.visibility != it) tab.visibility = it }
                }
            }
        }

        private fun glassUp() {
            if (!GlassKit.apply(bar, capsule = true, elevationDp = 6f)) return
            glassed = true
            (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                val side = GlassKit.dp(bar, 18f).toInt()
                it.leftMargin = margins!![0] + side
                it.rightMargin = margins[1] + side
                it.bottomMargin = margins[2] + GlassKit.dp(bar, 12f).toInt()
                bar.layoutParams = it
            }
            indicator(true)
        }

        private fun glassDown() {
            glassed = false
            GlassKit.remove(bar)
            (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.leftMargin = margins!![0]
                it.rightMargin = margins[1]
                it.bottomMargin = margins[2]
                bar.layoutParams = it
            }
            indicator(false)
        }

        /**
         * 那个滑动胶囊就是 Material TabLayout 自己的选中指示器，而 QQTabLayout 继承它、也用它，
         * 所以点按和滑动都会带上，弹性动画白送。
         */
        private fun indicator(on: Boolean) = runCatching {
            pillError = null
            val c = bar.javaClass
            val int = Int::class.javaPrimitiveType!!
            val bool = Boolean::class.javaPrimitiveType!!
            if (on) {
                c.getMethod("setTabIndicatorFullWidth", bool).invoke(bar, true)
                c.getMethod("setSelectedTabIndicatorGravity", int).invoke(bar, 3) // stretch
                c.getMethod("setTabIndicatorAnimationMode", int).invoke(bar, 1) // elastic
                c.getMethod("setSelectedTabIndicator", Drawable::class.java).invoke(bar, PillDrawable(bar))
                // 胶囊自己没有高度，不设这个它不显示。
                c.getMethod("setSelectedTabIndicatorHeight", int).invoke(bar, bar.height.coerceAtLeast(GlassKit.dp(bar, 56f).toInt()))
                runCatching { c.getMethod("setTabRippleColor", ColorStateList::class.java).invoke(bar, ColorStateList.valueOf(0)) }
            } else {
                c.getMethod("setSelectedTabIndicator", Drawable::class.java).invoke(bar, ColorDrawable(0))
                c.getMethod("setSelectedTabIndicatorGravity", int).invoke(bar, 0)
            }
        }.onFailure { pillError = it.toString().take(80) }

        fun restore() {
            bar.removeOnLayoutChangeListener(relayout)
            glassDown()
            hiddenTabs.forEach { (v, visibility) -> v.visibility = visibility }
            hiddenTabs.clear()
        }

        private fun tabs(v: View): List<View> {
            if (v.javaClass.name.endsWith("TabView")) return listOf(v)
            if (v !is ViewGroup) return emptyList()
            return (0 until v.childCount).flatMap { tabs(v.getChildAt(it)) }
        }

        private fun labelOf(v: View): String? {
            v.contentDescription?.toString()?.let { d ->
                (guildLabels + feedLabels).firstOrNull { d.contains(it) }?.let { return it }
            }
            if (v is TextView) return v.text?.toString()?.trim()
            if (v is ViewGroup) for (i in 0 until v.childCount) {
                labelOf(v.getChildAt(i))?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return null
        }
    }
}
