// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * 外观：每个 Activity 一到前台就盯住它的 decor，布局一变（节流 150ms）就走一遍视图树套规则。
 * 规则全按视图长相认 —— 类名、文字、contentDescription、位置 —— 不碰 QQ 的业务类，
 * 所以 QQ 换个小版本它们多半还活着；认错了最多是少藏一个按钮。
 */
fun looks() {
    hook(Instrumentation::class.java.getMethod("callActivityOnResume", Activity::class.java)) { chain ->
        chain.proceed().also { watch((chain.getArg(0) as Activity).window.decorView) }
    }
}

val View.dp: Float get() = resources.displayMetrics.density

private val watched: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

private fun watch(decor: View) {
    if (!watched.add(decor)) return
    var queued = false
    val scan = Runnable { queued = false; walk(decor, false) }
    decor.viewTreeObserver.addOnGlobalLayoutListener {
        if (!queued) { queued = true; decor.postDelayed(scan, 150) }
    }
    scan.run()
}

private fun walk(v: View, inDrawer: Boolean) {
    val name = v.javaClass.name
    when {
        name.endsWith(".QQTabLayout") -> { homeBar(v as ViewGroup); return }
        name.endsWith(".PanelIconLinearLayout") -> { tgInput(v as ViewGroup); return }
        else -> trim(v, inDrawer)
    }
    if (v is ViewGroup) {
        val drawer = inDrawer || name.contains("QQSettingMe") // 侧栏根是 QQSettingMeRelativeLayout
        for (i in 0 until v.childCount) walk(v.getChildAt(i), drawer)
    }
}

// ---- 标题栏、侧栏：多余的入口藏掉。规则不再匹配时恢复（列表项会被复用）。

private val TITLE = setOf("一起听", "一起看", "一起玩", "一起派对", "一起K歌", "QQ秀", "厘米秀", "群游戏", "小世界")
private val DRAWER = setOf(
    "会员", "SVIP", "钱包", "装扮", "小世界", "免流量", "小游戏", "厘米秀", "QQ秀", "QQ空间", "游戏中心", "腾讯文档", "打卡", "天气", "等级",
)

private fun trim(v: View, inDrawer: Boolean) {
    val desc = v.contentDescription?.toString().orEmpty()
    // 聊天标题栏右上角那排：有 contentDescription、矮、贴着窗口顶。
    val title = desc.length in 1..10 && TITLE.any { it in desc } && v.height in 1..(72 * v.dp).toInt() && windowY(v) < 160 * v.dp
    // 侧栏里一行行可点的：打卡、天气、等级、会员、装扮……（文案多半带「我的」前缀，所以用包含）
    val row = inDrawer && v.isClickable && v.height in 1..(96 * v.dp).toInt() &&
        texts(v).any { t -> DRAWER.any { it in t } }
    hide(v, title || row || v.javaClass.simpleName == "WeatherSettingMeItemView")
}

private val hidden = WeakHashMap<View, Int>()

fun hide(v: View, on: Boolean) {
    if (on) {
        hidden.putIfAbsent(v, v.visibility)
        if (v.visibility != View.GONE) v.visibility = View.GONE
    } else {
        hidden.remove(v)?.let { if (v.visibility != it) v.visibility = it }
    }
}

fun windowY(v: View) = IntArray(2).also(v::getLocationInWindow)[1]

/** 一个视图「写着什么」：自己的 contentDescription 加上里面（4 层内）每个 TextView 的字。 */
fun texts(v: View, depth: Int = 4): List<String> {
    val out = ArrayList<String>(2)
    fun go(x: View, d: Int) {
        x.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        if (x is TextView) x.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        else if (x is ViewGroup && d > 0) for (i in 0 until x.childCount) go(x.getChildAt(i), d - 1)
    }
    go(v, depth)
    return out
}

// ---- 首页底栏：QQ 自己的 TabLayout 原地浮成一颗玻璃胶囊，频道 / 动态页签藏掉。

private val floated: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

private fun homeBar(bar: ViewGroup) {
    // 热重载后是新一代代码、新的 floated 集合：靠背景认出上一代已经浮过的底栏，别再加一次边距。
    if (floated.add(bar) && bar.background?.javaClass?.name != Glass::class.java.name) float(bar)
    settle(bar)
    // material TabLayout：bar → SlidingTabIndicator → TabView × N
    (bar.getChildAt(0) as? ViewGroup)?.let { strip ->
        for (i in 0 until strip.childCount) {
            val tab = strip.getChildAt(i)
            hide(tab, texts(tab).any { "频道" in it || "动态" in it || "小世界" in it })
        }
    }
    // QQ 给底栏铺的通栏模糊带、分割细线、纯色垫底：胶囊两侧会露出来，都藏。
    if (bar.height == 0) return
    val frame = generateSequence(bar.parent as? ViewGroup) { it.parent as? ViewGroup }
        .firstOrNull { it.javaClass.simpleName == "TabFrameLayout" } ?: bar.parent as? ViewGroup
    frame?.let { clear(it, bar) }
}

private fun float(bar: ViewGroup) {
    bar.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
    }
    bar.clipToOutline = true
    val dp = bar.dp
    bar.elevation = 6 * dp
    (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.leftMargin += (16 * dp).toInt()
        it.rightMargin += (16 * dp).toInt()
        it.bottomMargin += (12 * dp).toInt()
        bar.layoutParams = it
    }
    bar.background = Glass(bar)
    // 内容一滚、布局一变就重画一次玻璃（底栏自己不会因为身后的东西动而重画）。
    bar.viewTreeObserver.addOnScrollChangedListener { bar.invalidate() }
    bar.viewTreeObserver.addOnGlobalLayoutListener { bar.invalidate() }
    pill(bar)
}

/** QQ 给手势条留的底部内边距（可能随 insets 反复设回来）挪成外边距，胶囊本身不带空腔。 */
private val absorbed = WeakHashMap<View, Int>()

private fun settle(bar: ViewGroup) {
    val inset = bar.paddingBottom
    if (inset == 0) return
    val delta = inset - (absorbed[bar] ?: 0) // QQ 反复设同一个值时不要越挪越高
    (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.bottomMargin += delta
        if (it.height > 0) it.height -= delta
        absorbed[bar] = inset
        bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, 0)
        bar.layoutParams = it
    }
}

/** 侧栏菜单的数据源：会员 / 钱包 / 装扮 / 小世界……这些行连生成都不生成（视图规则是兜底）。 */
@Suppress("UNCHECKED_CAST")
fun drawerMenu() {
    val config = cls("com.tencent.mobileqq.activity.qqsettingme.config.QQSettingMeMenuConfigBeanV3")
    hook(config.method("b")) { chain ->
        val groups = chain.proceed() as? Array<Array<Any?>> ?: return@hook null // QQSettingMeBizBean[][]
        val out = java.util.Arrays.copyOf(groups, groups.size) // copyOf 保住运行时的数组类型
        for ((i, g) in groups.withIndex()) {
            val kept = g.filter { bean -> bean == null || strings(bean).none { s -> DRAWER.any { it in s } } }
            out[i] = java.util.Arrays.copyOf(g, kept.size).also { kept.forEachIndexed { j, bean -> it[j] = bean } }
        }
        out
    }
}

private fun strings(o: Any): List<String> = generateSequence<Class<*>>(o.javaClass) { it.superclass }
    .flatMap { it.declaredFields.asSequence() }
    .filter { it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
    .mapNotNull { it.isAccessible = true; it.get(o) as? String }
    .toList()

/** 选中项的滑动胶囊就是 material TabLayout 自带的指示器：点按、滑动、弹性动画全都白送。 */
private fun pill(bar: ViewGroup) {
    val c = bar.javaClass
    val int = Integer.TYPE
    val dp = bar.dp
    fun call(name: String, type: Class<*>, arg: Any) = runCatching { c.getMethod(name, type).invoke(bar, arg) }
    call("setSelectedTabIndicator", android.graphics.drawable.Drawable::class.java, Pill(dp))
    call("setSelectedTabIndicatorGravity", int, 3) // STRETCH：上下撑满
    call("setTabIndicatorFullWidth", java.lang.Boolean.TYPE, true)
    call("setTabIndicatorAnimationMode", int, 1) // ELASTIC
    call("setSelectedTabIndicatorHeight", int, (56 * dp).toInt()) // 老版 material 没高度就不画
    call("setTabRippleColor", android.content.res.ColorStateList::class.java, android.content.res.ColorStateList.valueOf(0))
}

/** 底栏父容器里除内容页（ViewPager）与底栏自身之外、盖在底栏那一带的装饰：模糊层、细线、纯 View 垫底。 */
private fun clear(root: ViewGroup, bar: View) {
    val top = windowY(bar) - (24 * bar.dp).toInt()
    fun go(v: View) {
        if (v === bar || v.javaClass.name.contains("ViewPager")) return
        val name = v.javaClass.simpleName
        val strip = v.height <= bar.height * 2 && v.width > bar.width / 2
        val decorative = name.contains("Blur") || (strip && (v.javaClass == View::class.java || v.height <= 2))
        if (decorative && v.visibility == View.VISIBLE && windowY(v) + v.height > top && !v.isAncestorOf(bar)) {
            v.visibility = View.INVISIBLE
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) go(v.getChildAt(i))
    }
    go(root)
}

private fun View.isAncestorOf(v: View): Boolean = generateSequence(v.parent) { it.parent }.any { it === this }
