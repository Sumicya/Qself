// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * A switch that hides pieces of QQ's own UI. Matching is by what the user sees
 * (content description, text, geometry), not by obfuscated class names, so it
 * survives QQ updates. Everything hidden is remembered and restored on disable.
 */
abstract class ViewRule(id: String, val inLists: Boolean = false) : Feature(id) {
    internal val hidden = WeakHashMap<View, Int>()

    abstract fun match(v: View): Boolean

    override fun install() = Views.refresh()
    override fun uninstall() {
        hidden.forEach { (v, vis) -> v.visibility = vis }
        hidden.clear()
        Views.refresh()
    }
    override fun onResume(activity: Activity) = Views.attach(activity)

    protected fun dp(v: View, x: Int) = (x * v.resources.displayMetrics.density).toInt()
    protected fun desc(v: View): String? = v.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

object Views {
    private val rules get() = Runtime.features.filterIsInstance<ViewRule>()
    private val decors = WeakHashMap<View, Watch>()
    private var lastDump = 0L

    private fun active() = rules.filter { it.active }
    private fun wanted() = rules.any { it.active } || Dump.active

    fun refresh() {
        if (!wanted()) {
            decors.values.toList().forEach { it.detach() }
            decors.clear()
        } else decors.values.toList().forEach { it.schedule() }
    }

    fun attach(a: Activity) {
        if (!wanted()) return
        val decor = a.window?.decorView ?: return
        (decors[decor] ?: Watch(decor, a).also { decors[decor] = it }).schedule()
    }

    /** One per window: coalesces layout storms into at most one scan every 200 ms. */
    private class Watch(val decor: View, a: Activity) : ViewTreeObserver.OnGlobalLayoutListener, Runnable {
        val activity = WeakReference(a)
        var pending = false
        var last = 0L

        init { decor.viewTreeObserver.addOnGlobalLayoutListener(this) }

        override fun onGlobalLayout() = schedule()

        fun schedule() {
            if (pending) return
            pending = true
            val wait = (200 - (SystemClock.uptimeMillis() - last)).coerceAtLeast(16)
            decor.postDelayed(this, wait)
        }

        override fun run() {
            pending = false
            last = SystemClock.uptimeMillis()
            runCatching { scan(decor, activity.get()) }.onFailure { Runtime.log(Log.WARN, "scan: $it") }
        }

        fun detach() {
            decor.removeCallbacks(this)
            runCatching { decor.viewTreeObserver.removeOnGlobalLayoutListener(this) }
        }
    }

    private fun scan(root: View, a: Activity?) {
        val list = active()
        if (list.isNotEmpty()) walk(root, list, list.any { it.inLists })
        if (Dump.active && a != null && SystemClock.uptimeMillis() - lastDump > 3000) {
            lastDump = SystemClock.uptimeMillis()
            Dump.write(a, root)
        }
    }

    private fun walk(v: View, list: List<ViewRule>, lists: Boolean) {
        var gone = false
        for (r in list) {
            val was = r.hidden[v]
            val hit = runCatching { r.match(v) }.getOrDefault(false)
            if (hit) {
                if (was == null) r.hidden[v] = v.visibility
                if (v.visibility != View.GONE) v.visibility = View.GONE
                gone = true
            } else if (was != null) {
                // Recycled or changed: it no longer is what we hid.
                r.hidden.remove(v)
                v.visibility = was
            }
        }
        if (gone || v !is ViewGroup) return
        val isList = v is AbsListView || v.javaClass.name.endsWith("RecyclerView")
        if (isList && !lists) return
        val rs = if (isList) list.filter { it.inLists } else list
        for (i in 0 until v.childCount) walk(v.getChildAt(i), rs, lists)
    }

    /** Ancestor views, nearest first. */
    fun ancestors(v: View, depth: Int = 40): Sequence<View> = generateSequence(v.parent as? View) { it.parent as? View }.take(depth)

    fun texts(v: View, depth: Int): List<String> {
        if (v is TextView) return listOfNotNull(v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
        if (v !is ViewGroup || depth == 0) return emptyList()
        return (0 until v.childCount).flatMap { texts(v.getChildAt(it), depth - 1) }
    }

    /** A LinearLayout row, or any container whose children all sit on one line. */
    fun isHorizontalRow(p: ViewGroup) = (p is LinearLayout && p.orientation == LinearLayout.HORIZONTAL) ||
        (p.childCount >= 3 && (0 until p.childCount).map { p.getChildAt(it).top }.distinct().size <= 2)

    private val loc = IntArray(2)
    fun windowY(v: View): Int { v.getLocationInWindow(loc); return loc[1] }
}

/** Chat title bar: no "listen together", QQ Show and similar entertainment buttons. */
object TgTitleBar : ViewRule("tg_title_bar") {
    private val drop = listOf("一起听", "一起看", "一起玩", "一起派对", "一起K歌", "QQ秀", "厘米秀", "小世界", "群游戏")

    override fun match(v: View): Boolean {
        val d = desc(v) ?: return false
        if (d.length > 10 || drop.none { d.contains(it, ignoreCase = true) }) return false
        return v.height in 1..dp(v, 72) && Views.windowY(v) < dp(v, 160)
    }
}

/**
 * Side drawer: drop the shopping mall, check-in, weather and level badge; keep albums, favourites,
 * files, settings. Frosted pills (a blur wrapper around one button) are hidden as a whole.
 */
object TgDrawer : ViewRule("tg_drawer", inLists = true) {
    private val drop = setOf(
        "开通会员", "会员中心", "超级会员", "QQ会员", "QQ钱包", "钱包", "个性装扮", "装扮",
        "我的小世界", "小世界", "免流量", "QQ小游戏", "小游戏", "厘米秀", "超级QQ秀", "QQ秀",
        "我的QQ空间", "QQ空间", "游戏中心", "腾讯文档", "打卡", "当地天气", "天气",
    )
    private val dropDesc = listOf("等级", "QQ会员", "天气")
    private val hosts = listOf("Drawer", "SettingMe", "QQSetting")

    /** The clickable part of [v]: itself, or its only non-blur child. */
    private fun button(v: View): View? {
        if (v.isClickable) return v
        if (v !is ViewGroup) return null
        val rest = (0 until v.childCount).map(v::getChildAt).filter { !it.javaClass.name.contains("Blur") }
        return rest.singleOrNull()?.takeIf { it.isClickable }
    }

    override fun match(v: View): Boolean {
        if (v.height !in 1..dp(v, 96)) return false
        val b = button(v) ?: return false
        val d = desc(b)
        val hit = (d != null && d.length <= 12 && dropDesc.any { d.startsWith(it) }) ||
            // Judge a button by its main label (first text with letters), so a row that merely
            // contains a dropped item (e.g. 设置 | 日间 | 天气) is never taken down as a whole.
            Views.texts(b, 3).firstOrNull { t -> t.any(Character::isLetter) } in drop
        if (!hit) return false
        return Views.ancestors(v).any { a -> hosts.any { a.javaClass.name.contains(it) } }
    }
}

/**
 * Developer aid: every ~3 s writes the front window's view tree to
 * /sdcard/Android/data/com.tencent.mobileqq/files/qself/latest.txt so rules can be tuned from real data.
 */
object Dump : Feature("debug_dump") {
    private var lastHash = 0

    override fun install() = Views.refresh()
    override fun uninstall() = Views.refresh()
    override fun onResume(activity: Activity) = Views.attach(activity)

    fun write(a: Activity, root: View) {
        val sb = StringBuilder(64 * 1024)
        sb.append(a.javaClass.name).append('\n')
        tree(root, 0, sb)
        val body = sb.toString()
        if (body.hashCode() == lastHash) return
        lastHash = body.hashCode()
        val dir = a.getExternalFilesDir("qself") ?: return
        val name = a.javaClass.simpleName
        thread(name = "qself-dump", isDaemon = true) {
            runCatching {
                File(dir, "latest.txt").writeText(body)
                File(dir, "$name.txt").writeText(body)
            }
        }
    }

    private fun tree(v: View, depth: Int, sb: StringBuilder) {
        if (sb.length > 1_500_000) return
        repeat(depth) { sb.append(' ') }
        sb.append(v.javaClass.name.removePrefix("com.tencent.").removePrefix("android.widget."))
        if (v.id != View.NO_ID) runCatching { sb.append(" #").append(v.resources.getResourceEntryName(v.id)) }
        when (v.visibility) { View.GONE -> sb.append(" GONE"); View.INVISIBLE -> sb.append(" INV") }
        v.contentDescription?.let { sb.append(" d=\"").append(it.toString().take(24)).append('"') }
        if (v is TextView) v.text?.takeIf { it.isNotEmpty() }?.let { sb.append(" t=\"").append(it.toString().replace('\n', ' ').take(24)).append('"') }
        if (v.isClickable) sb.append(" C")
        sb.append(" [").append(v.left).append(',').append(v.top).append(' ').append(v.width).append('x').append(v.height).append("]\n")
        if (v is ViewGroup) for (i in 0 until v.childCount) tree(v.getChildAt(i), depth + 1, sb)
    }
}
