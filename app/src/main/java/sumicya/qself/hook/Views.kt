// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * Per-view state that dies with the view, so a recycled or thrown-away view never keeps an undo
 * or a hook alive. The state is tagged on the view itself: a WeakHashMap<View, State> whose State
 * points back at the view never lets go of either.
 */
class Decor<S : Decor.State> {
    interface State {
        val anchor: View
        fun undo()
    }

    private val key = nextKey()
    private val all = Collections.newSetFromMap(WeakHashMap<S, Boolean>())

    @Suppress("UNCHECKED_CAST")
    operator fun get(v: View): S? = v.getTag(key) as? S

    fun put(state: S): S {
        state.anchor.setTag(key, state)
        all += state
        return state
    }

    fun each(): List<S> = all.toList()
    val size: Int get() = all.size

    fun drop(state: S) {
        all.remove(state)
        if (state.anchor.getTag(key) === state) state.anchor.setTag(key, null)
        runCatching { state.undo() }.onFailure { Core.log(Log.WARN, "undo: $it") }
    }

    fun dropAll() = each().forEach(::drop)

    private companion object {
        // Tag keys must look like resource ids of a package other than android (>= 0x02xxxxxx).
        // 0x5E belongs to nobody.
        private var next = 0x5E51_0000
        fun nextKey() = ++next
    }
}

/**
 * A switch that hides part of QQ's own UI. Matching is by what the user sees — content
 * description, text, geometry — not by obfuscated names, so it survives QQ updates.
 * Everything hidden is remembered and given back on disable.
 */
abstract class ViewRule(id: String, val inLists: Boolean = false) : Feature(id) {
    internal val hidden = WeakHashMap<View, Int>()
    var hits = 0

    protected abstract fun match(v: View): Boolean

    override fun install() = Views.refresh()
    override fun uninstall() {
        hidden.forEach { (v, visibility) -> v.visibility = visibility }
        hidden.clear()
        Views.refresh()
    }

    override fun onResume(activity: Activity) = Views.attach(activity)
    override fun status() = "命中 $hits"

    protected fun dp(v: View, x: Int) = (x * v.resources.displayMetrics.density).toInt()
    protected fun desc(v: View): String? = v.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/** The one scanner behind every [ViewRule]: walks each window's tree at most every 200 ms. */
object Views {
    private val watches = Decor<Watch>()

    private val rules get() = Core.features.filterIsInstance<ViewRule>()

    private fun active() = rules.filter { it.active }

    fun wanted() = active().isNotEmpty() || Dump.active

    fun refresh() {
        if (wanted()) watches.each().forEach { it.schedule() } else watches.dropAll()
    }

    fun attach(activity: Activity) {
        if (!wanted()) return
        val decor = activity.window?.decorView ?: return
        (watches[decor] ?: watches.put(Watch(decor, activity))).schedule()
    }

    fun clearAll() = watches.dropAll()

    private class Watch(override val anchor: View, activity: Activity) :
        Decor.State, ViewTreeObserver.OnGlobalLayoutListener, Runnable {

        private val activity = WeakReference(activity)
        private var pending = false
        private var last = 0L

        init {
            anchor.viewTreeObserver.addOnGlobalLayoutListener(this)
        }

        override fun onGlobalLayout() = schedule()

        fun schedule() {
            if (pending) return
            pending = true
            anchor.postDelayed(this, (200 - (SystemClock.uptimeMillis() - last)).coerceAtLeast(16))
        }

        override fun run() {
            pending = false
            last = SystemClock.uptimeMillis()
            runCatching { Views.scan(anchor, activity.get()) }.onFailure { Core.log(Log.WARN, "scan: $it") }
        }

        override fun undo() {
            anchor.removeCallbacks(this)
            runCatching { anchor.viewTreeObserver.removeOnGlobalLayoutListener(this) }
        }
    }

    private var lastDump = 0L

    private fun scan(root: View, activity: Activity?) {
        val list = active()
        if (list.isNotEmpty()) walk(root, list, list.any { it.inLists })
        if (Dump.active && activity != null && SystemClock.uptimeMillis() - lastDump > 3000) {
            lastDump = SystemClock.uptimeMillis()
            Dump.write(activity, root)
        }
    }

    private fun walk(v: View, list: List<ViewRule>, lists: Boolean) {
        var caught = false
        for (rule in list) {
            val was = rule.hidden[v]
            val hit = runCatching { rule.match(v) }.getOrDefault(false)
            if (hit) {
                if (was == null) {
                    rule.hidden[v] = v.visibility
                    rule.hits++
                }
                if (v.visibility != View.GONE) v.visibility = View.GONE
                caught = true
            } else if (was != null) {
                rule.hidden.remove(v)
                v.visibility = was
            }
        }
        if (caught || v !is ViewGroup) return
        val isList = v is AbsListView || v.javaClass.name.endsWith("RecyclerView")
        if (isList && !lists) return
        val rules = if (isList) list.filter { it.inLists } else list
        for (i in 0 until v.childCount) walk(v.getChildAt(i), rules, lists)
    }

    // ---- helpers the rules use ----

    fun ancestors(v: View, depth: Int = 40): Sequence<View> =
        generateSequence(v.parent as? View) { it.parent as? View }.take(depth)

    fun texts(v: View, depth: Int): List<String> {
        if (v is android.widget.TextView) return listOfNotNull(v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
        if (v !is ViewGroup || depth == 0) return emptyList()
        return (0 until v.childCount).flatMap { texts(v.getChildAt(it), depth - 1) }
    }

    private val location = IntArray(2)

    fun windowY(v: View): Int {
        v.getLocationInWindow(location)
        return location[1]
    }
}

/** Chat title bar: no listen-together, QQ Show and similar entertainment buttons. */
object TgTitleBar : ViewRule("tg_title_bar") {
    private val drop = listOf("一起听", "一起看", "一起玩", "一起派对", "一起K歌", "QQ秀", "厘米秀", "小世界", "群游戏")

    override fun match(v: View): Boolean {
        val d = desc(v) ?: return false
        if (d.length > 10 || drop.none { d.contains(it, ignoreCase = true) }) return false
        return v.height in 1..dp(v, 72) && Views.windowY(v) < dp(v, 160)
    }
}

/**
 * Side drawer: drop the mall, check-in, weather and the level badge; keep albums, favourites,
 * files, settings. A row is judged by its main label, so a row that merely contains a dropped
 * word (设置 | 日间 | 天气) is never taken down as a whole.
 */
object TgDrawer : ViewRule("tg_drawer", inLists = true) {
    private val drop = setOf(
        "开通会员", "会员中心", "超级会员", "QQ会员", "QQ钱包", "钱包", "个性装扮", "装扮",
        "我的小世界", "小世界", "免流量", "QQ小游戏", "小游戏", "厘米秀", "超级QQ秀", "QQ秀",
        "我的QQ空间", "QQ空间", "游戏中心", "腾讯文档", "打卡", "当地天气", "天气",
    )
    private val dropDesc = listOf("等级", "QQ会员", "天气")
    private val hosts = listOf("Drawer", "SettingMe", "QQSetting")

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
            Views.texts(b, 3).firstOrNull { t -> t.any(Character::isLetter) } in drop
        if (!hit) return false
        return Views.ancestors(v).any { a -> hosts.any { a.javaClass.name.contains(it) } }
    }
}

/**
 * Developer aid, off by default: every ~3 s write the front window's view tree to
 * QQ's own files/qself/ so rules can be tuned from real data.
 */
object Dump : Feature("debug_dump") {
    private var lastHash = 0

    override fun install() = Views.refresh()
    override fun uninstall() = Views.refresh()
    override fun onResume(activity: Activity) = Views.attach(activity)

    fun write(activity: Activity, root: View) {
        val sb = StringBuilder(64 * 1024)
        sb.append(activity.javaClass.name).append('\n')
        tree(root, 0, sb)
        val body = sb.toString()
        if (body.hashCode() == lastHash) return
        lastHash = body.hashCode()
        val dir = activity.getExternalFilesDir("qself") ?: return
        val name = activity.javaClass.simpleName
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
        when (v.visibility) {
            View.GONE -> sb.append(" GONE")
            View.INVISIBLE -> sb.append(" INV")
        }
        v.contentDescription?.let { sb.append(" d=\"").append(it.toString().take(24)).append('"') }
        if (v is android.widget.TextView) {
            v.text?.takeIf { it.isNotEmpty() }?.let { sb.append(" t=\"").append(it.toString().replace('\n', ' ').take(24)).append('"') }
        }
        if (v.isClickable) sb.append(" C")
        sb.append(" [").append(v.left).append(',').append(v.top).append(' ')
            .append(v.width).append('x').append(v.height).append("]\n")
        if (v is ViewGroup) for (i in 0 until v.childCount) tree(v.getChildAt(i), depth + 1, sb)
    }
}
