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

/** 能自己撤销的界面改动。状态挂在 view 的 tag 上，view 一没状态跟着没。 */
interface Undo {
    val anchor: View
    fun undo()
}

class Tagged<T : Undo> {
    private val key = nextKey()
    private val alive = Collections.newSetFromMap(WeakHashMap<T, Boolean>())

    @Suppress("UNCHECKED_CAST")
    operator fun get(v: View): T? = v.getTag(key) as? T

    fun put(s: T): T {
        s.anchor.setTag(key, s)
        alive += s
        return s
    }

    fun drop(s: T) {
        alive.remove(s)
        if (s.anchor.getTag(key) === s) s.anchor.setTag(key, null)
        runCatching { s.undo() }.onFailure { Core.log(Log.WARN, "undo: $it") }
    }

    fun dropAll() = alive.toList().forEach(::drop)

    fun each(): List<T> = alive.toList()

    val size: Int get() = alive.size

    private companion object {
        // tag key 要像个资源 id 但不属于 android（>= 0x02xxxxxx）。0x5E61 谁也占不着。
        private var next = 0x5E61_0000
        fun nextKey() = ++next
    }
}

/**
 * 只动界面的开关：藏掉或挪一下 QQ 自己的某块 UI。认控件只看看得见的东西 —— 结构、文字、位置
 * —— 不看混淆后的名字。动过的都记着，关掉开关原样还回去。
 */
abstract class Rule(id: String, val lists: Boolean = false) : Switch(id) {
    internal val restored = WeakHashMap<View, Int>()
    var hits = 0

    /** 藏东西的是扫描器，不是钩子，所以能边跑边翻。 */
    override val live get() = true

    abstract fun match(v: View): Boolean

    override fun install() = Screen.wake()

    override fun uninstall() {
        restored.forEach { (v, visibility) -> v.visibility = visibility }
        restored.clear()
        Screen.wake()
    }

    override fun onResume(activity: Activity) = Screen.watch(activity)

    override fun status() = "命中 $hits"

    internal fun hide(v: View) {
        restored.putIfAbsent(v, v.visibility)
        if (v.visibility != View.GONE) v.visibility = View.GONE
    }

    internal fun show(v: View) {
        restored.remove(v)?.let { if (v.visibility != it) v.visibility = it }
    }

    protected fun dp(v: View, x: Int) = (x * v.resources.displayMetrics.density).toInt()
    protected fun desc(v: View) = v.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/** 就一个扫描器：每个窗口的树最多 200 毫秒走一遍，规则在里面自己认领控件。 */
object Screen {
    private val watches = Tagged<Watch>()

    private fun rules() = Core.switches.filterIsInstance<Rule>()

    fun wanted() = rules().any { it.active } || Dump.active

    fun wake() {
        if (wanted()) watches.each().forEach { it.poke() } else watches.dropAll()
    }

    fun watch(activity: Activity) {
        if (!wanted()) return
        val decor = activity.window?.decorView ?: return
        (watches[decor] ?: watches.put(Watch(decor, activity))).poke()
    }

    fun dropAll() = watches.dropAll()

    private class Watch(override val anchor: View, activity: Activity) :
        Undo, ViewTreeObserver.OnGlobalLayoutListener, Runnable {

        private val activity = WeakReference(activity)
        private var queued = false
        private var last = 0L

        init {
            anchor.viewTreeObserver.addOnGlobalLayoutListener(this)
        }

        override fun onGlobalLayout() = poke()

        fun poke() {
            if (queued) return
            queued = true
            anchor.postDelayed(this, (200 - (SystemClock.uptimeMillis() - last)).coerceAtLeast(16))
        }

        override fun run() {
            queued = false
            last = SystemClock.uptimeMillis()
            runCatching { Screen.pass(anchor, activity.get()) }.onFailure { Core.log(Log.WARN, "scan: $it") }
        }

        override fun undo() {
            anchor.removeCallbacks(this)
            runCatching { anchor.viewTreeObserver.removeOnGlobalLayoutListener(this) }
        }
    }

    private var lastDump = 0L

    private fun pass(root: View, activity: Activity?) {
        val rules = rules().filter { it.active }
        if (rules.isNotEmpty()) walk(root, rules, rules.any { it.lists })
        if (Dump.active && activity != null && SystemClock.uptimeMillis() - lastDump > 3000) {
            lastDump = SystemClock.uptimeMillis()
            Dump.save(activity, root)
        }
    }

    private fun walk(v: View, list: List<Rule>, lists: Boolean) {
        var claimed = false
        for (rule in list) {
            val hit = runCatching { rule.match(v) }.getOrDefault(false)
            if (hit) {
                rule.hits++
                rule.hide(v)
                claimed = true
            } else {
                rule.show(v)
            }
        }
        if (claimed || v !is ViewGroup) return
        val recycler = v is AbsListView || v.javaClass.name.endsWith("RecyclerView")
        if (recycler && !lists) return
        val rules = if (recycler) list.filter { it.lists } else list
        for (i in 0 until v.childCount) walk(v.getChildAt(i), rules, lists)
    }

    // ---- 给规则用的小工具 ----

    /** 一段子树里看得见的文字：TextView 的文本和 contentDescription，最多 6 条。 */
    fun labels(v: View, depth: Int): List<String> {
        val out = ArrayList<String>(4)
        fun go(x: View, d: Int) {
            if (out.size >= 6) return
            if (x is android.widget.TextView) {
                x.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
            if (out.size < 6) x.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            if (x is ViewGroup && d > 0) for (i in 0 until x.childCount) go(x.getChildAt(i), d - 1)
        }
        go(v, depth)
        return out
    }

    fun up(v: View, depth: Int = 40): Sequence<View> =
        generateSequence(v.parent as? View) { it.parent as? View }.take(depth)

    private val location = IntArray(2)

    fun windowY(v: View): Int {
        v.getLocationInWindow(location)
        return location[1]
    }
}

/**
 * 调试用，默认关：每 3 秒把前台窗口的 view 树写进 QQ 自己的 files/qself/，
 * 拿真数据调规则。
 */
object Dump : Switch("debug_dump") {
    override val live get() = true
    private var lastHash = 0

    override fun install() = Screen.wake()
    override fun uninstall() = Screen.wake()
    override fun onResume(activity: Activity) = Screen.watch(activity)

    fun save(activity: Activity, root: View) {
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
