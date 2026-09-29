// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.ceil

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
    var lastBar: ViewGroup? = null
    val scan = Runnable { queued = false; walk(decor, false) }
    // 底栏不等全量规则的 150ms 节流；已找到后复用宿主，只在它消失时重找。
    decor.viewTreeObserver.addOnGlobalLayoutListener {
        lastBar?.takeUnless { it.isShown }?.let(::knob) // 离开主页时藏钮
        val bar = lastBar?.takeIf { it.isShown && it.isAttachedToWindow && it.rootView === decor }
            ?: findHomeBar(decor).also { lastBar = it }
        bar?.let { homeBar(it); knob(it) }
        if (!queued) { queued = true; decor.postDelayed(scan, 150) } // 其他外观规则仍节流
    }
    scan.run()
}

private fun findHomeBar(v: View): ViewGroup? {
    if (v.javaClass.name.endsWith(".QQTabLayout") && v.isShown) return v as? ViewGroup
    if (v is ViewGroup) for (i in 0 until v.childCount)
        findHomeBar(v.getChildAt(i))?.let { return it }
    return null
}

fun refreshLooks(decor: View) = walk(decor, false)

private fun walk(v: View, inDrawer: Boolean) {
    val name = v.javaClass.name
    when {
        name.endsWith(".QQTabLayout") -> { homeBar(v as ViewGroup); return }
        name.endsWith(".PanelIconLinearLayout") -> { tgInput(v as ViewGroup); return }
        else -> trim(v, inDrawer)
    }
    if (v is ViewGroup) {
        val drawer = inDrawer || name.contains("QQSettingMe")
        var i = 0
        while (i < v.childCount) {
            val child = v.getChildAt(i)
            walk(child, drawer)
            // tgInput 会原地重排孩子；不要跳过新孩子，也不要访问已经不存在的下标。
            if (v.getChildAt(i) === child) i++
        }
    }
}

// ---- 标题栏、侧栏：多余的入口藏掉。规则不再匹配时恢复（列表项会被复用）。

private val TITLE = setOf("一起听", "一起看", "一起玩", "一起派对", "一起K歌", "QQ秀", "厘米秀", "群游戏", "小世界")
private val DRAWER = setOf(
    "会员", "SVIP", "钱包", "装扮", "小世界", "免流量", "小游戏", "厘米秀", "QQ秀", "QQ空间", "游戏中心", "腾讯文档", "打卡", "天气", "等级",
)

// 好友聊天标题栏昵称下面那行在线状态（文案是服务器发的，所以只能按长相认：小、在顶部、字就是这些）
private val ONLINE = setOf(
    "在线", "离线", "忙碌", "隐身", "Q我", "手机在线", "WiFi在线", "Wi-Fi在线", "4G在线", "5G在线", "PC在线", "平板在线",
)

private fun trim(v: View, inDrawer: Boolean) {
    val desc = v.contentDescription?.toString().orEmpty()
    // 聊天标题栏右上角那排：有 contentDescription、矮、贴着窗口顶。
    val title = desc.length in 1..10 && TITLE.any { it in desc } && v.height in 1..(72 * v.dp).toInt() && windowY(v) < 160 * v.dp
    // 侧栏里一行行可点的：打卡、天气、等级、会员、装扮……（文案多半带「我的」前缀，所以用包含）
    val row = inDrawer && v.isClickable && v.height in 1..(96 * v.dp).toInt() &&
        texts(v).any { t -> DRAWER.any { it in t } }
    val status = v is TextView && (v.text?.toString()?.trim() ?: "") in ONLINE &&
        v.height in 1..(40 * v.dp).toInt() && windowY(v) < 200 * v.dp
    hide(v, on("标题栏侧栏精简") && (title || row || v.javaClass.simpleName == "WeatherSettingMeItemView") ||
        on("隐藏在线状态") && status)
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

/** QQ 页签自己消费触摸，不会给父 TabLayout 设置 pressed；仅拦它自己的事件入口，原事件照常走。 */
fun barTouch() {
    val bar = cls("com.tencent.mobileqq.widget.QQTabLayout")
    val intercept = bar.getDeclaredMethod("onInterceptTouchEvent", MotionEvent::class.java)
    hook(intercept) { chain ->
        val v = chain.thisObject as View
        val e = chain.args[0] as MotionEvent
        (v.background as? Glass)?.let { glass ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> glass.press(e.x, e.y, true)
                MotionEvent.ACTION_MOVE -> glass.press(e.x, e.y, e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> glass.press(e.x, e.y, false)
            }
        }
        chain.proceed()
    }
}

private val floated: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
private val positioned: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

private fun homeBar(bar: ViewGroup) {
    // 首次处理在全局布局同步完成；pre-draw 只跟进选中态和之后的位移，不取消绘制帧。
    if (positioned.add(bar)) bar.viewTreeObserver.addOnPreDrawListener {
        if (bar.isAttachedToWindow) { (bar.background as? Glass)?.sync(); knob(bar) }
        true
    }
    // 热重载后是新一代代码、新的 floated 集合：靠背景认出上一代已经浮过的底栏，别再加一次边距。
    // ponytail: 浮起来之后关掉开关不会沉回去，重启 QQ 才复原；复原要存一堆原值，不值。
    if (bar.background?.javaClass?.name == Glass::class.java.name) floated.add(bar)
    else if (on("玻璃底栏") && floated.add(bar)) float(bar)
    val glass = bar in floated
    if (glass) settle(bar)
    // material TabLayout：bar → SlidingTabIndicator → TabView × N
    (bar.getChildAt(0) as? ViewGroup)?.let { strip ->
        for (i in 0 until strip.childCount) {
            val tab = strip.getChildAt(i)
            hide(tab, on("藏频道动态") && texts(tab).any { "频道" in it || "动态" in it || "小世界" in it })
            if (glass) fit(tab)
        }
    }
    if (bar.height == 0) return
    // QQ 给底栏铺的通栏模糊带、分割细线、纯色垫底：胶囊两侧会露出来，都藏。
    if (!glass) return
    val frame = generateSequence(bar.parent as? ViewGroup) { it.parent as? ViewGroup }
        .firstOrNull { it.javaClass.simpleName == "TabFrameLayout" } ?: bar.parent as? ViewGroup
    frame?.let { clear(it, bar) }
}

private fun float(bar: ViewGroup) {
    bar.outlineProvider = capsule()
    bar.clipToOutline = true
    val dp = bar.dp
    bar.elevation = 4 * dp
    // 胶囊只包住页签、居中：iOS 那样不是通栏。
    bar.layoutParams?.let {
        it.width = ViewGroup.LayoutParams.WRAP_CONTENT
        when (it) {
            is FrameLayout.LayoutParams -> it.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            is LinearLayout.LayoutParams -> it.gravity = Gravity.CENTER_HORIZONTAL
            is RelativeLayout.LayoutParams -> it.addRule(RelativeLayout.CENTER_HORIZONTAL)
        }
        if (it is ViewGroup.MarginLayoutParams) it.bottomMargin += (12 * dp).toInt()
        bar.layoutParams = it
    }
    // 两头的留白放在页签条上而不是 bar 上：material 固定模式会把页签条量成 bar 的整宽（含 padding），放 bar 上会挤歪。
    bar.setPadding(0, bar.paddingTop, 0, bar.paddingBottom)
    bar.getChildAt(0)?.setPadding((2 * dp).toInt(), 0, (2 * dp).toInt(), 0) // 两端留白收掉，玻璃贴着按钮
    bar.background = Glass(bar) { selectedTab(bar) }
    // 选中态与圆钮的定位共用 homeBar 注册的 pre-draw 回调。
}

/** 页签定宽 56dp：material 给的是平分整条的 weight，QQ 的页签视图又是 match_parent，胶囊就只能跟屏幕一样长。 */
private fun fit(tab: View) {
    val w = (56 * tab.dp).toInt() // 收窄：胶囊两端正好包住首尾页签的圆
    val lp = tab.layoutParams as? LinearLayout.LayoutParams ?: return
    if (lp.width == w && lp.weight == 0f && tab.paddingLeft == 0) return
    lp.width = w
    lp.weight = 0f
    tab.setPadding(0, tab.paddingTop, 0, tab.paddingBottom)
    tab.layoutParams = lp
}

/** material TabLayout 选中的那个 TabView（bar → SlidingTabIndicator → TabView × N）。 */
fun selectedTab(bar: ViewGroup): View? {
    val strip = bar.getChildAt(0) as? ViewGroup ?: return null
    for (i in 0 until strip.childCount) strip.getChildAt(i).let { if (it.isSelected && it.visibility == View.VISIBLE) return it }
    return null
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

/** QQ 两种角标：QUIBadge 自己画 mText，旧版 TextView 用第三个参数做数量；别去视图树找不存在的子 TextView。 */
fun exactCount() {
    val badge = cls("com.tencent.mobileqq.quibadge.QUIBadge")
    val text = badge.getDeclaredField("mText").apply { isAccessible = true }
    val num = badge.getDeclaredField("mNum").apply { isAccessible = true }
    val paint = badge.getDeclaredField("mTextPaint").apply { isAccessible = true }
    // updateNum 是 private 小方法，ART 可能内联；改从 QQ 对外暴露的设置入口落钩。
    for (name in listOf("setRedNum", "setGrayNum", "setAIOBarNum", "setRedNumWithIcon", "setGrayNumWIthIcon"))
        hook(badge.method(name)) { chain ->
            chain.proceed().also {
                val n = chain.args[0] as Int
                if (n > 99) text.set(chain.thisObject, n.toString())
            }
        }
    // QQ 给 99+ 固定 31dp 宽；真数字多一位，就补上那一位的像素宽度。
    hook(badge.method("getMinWidth")) { chain ->
        val width = chain.proceed() as Int
        val b = chain.thisObject
        if (num.getInt(b) <= 99) width else {
            val p = paint.get(b) as Paint
            width + ceil(p.measureText(text.get(b) as String) - p.measureText("99+")).toInt().coerceAtLeast(0)
        }
    }
    // 主页页签的角标不是 QUIBadge：TabFrameControllerImpl.generateRedTouch 创建 RedTouch，
    // 它还有独立的 maxNum 截断值。只在排版数字前提高上限，保留 QQ 自己的更新/点击流程。
    val red = cls("com.tencent.mobileqq.tianshu.ui.RedTouch")
    val maxNum = red.getDeclaredField("maxNum").apply { isAccessible = true }
    hook(red.method("getTextRedPoint")) { chain ->
        maxNum.setInt(chain.thisObject, Int.MAX_VALUE)
        chain.proceed()
    }
    hook(cls("com.tencent.widget.d").method("d")) { chain ->
        chain.proceed().also {
            val n = chain.args[2] as Int // d(TextView, type, count, background, limit, label, isRed)
            val v = chain.args[0] as? TextView
            if (n > 99 && v?.text?.toString() == "99+") {
                v.text = n.toString()
                val lp = v.layoutParams
                val width = ceil(v.paint.measureText(v.text.toString()) + v.paddingLeft + v.paddingRight + 4 * v.dp).toInt()
                if (lp != null && lp.width in 1 until width) { lp.width = width; v.layoutParams = lp }
            }
        }
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
