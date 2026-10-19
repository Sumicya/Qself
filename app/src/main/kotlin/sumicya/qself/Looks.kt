// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.Gravity
import android.app.Activity
import android.app.Instrumentation
import android.content.SharedPreferences
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * 外观：每个 Activity 一到前台就盯住它的 decor，布局一变在同一帧 pre-draw 套规则，不排 150ms 延时队列。
 * 精简规则按视图识别；底栏内容由 QQ 自己的 TabFrameLayout 绑定关系确定，不挑尺寸相近的列表。
 */
private var nativeBarContent: ((ViewGroup) -> ViewGroup?)? = null

fun looks() {
    // 真包 onFinishInflate 将 QQTabLayout 存入 e、ViewPager2 存入 d；a() 直接返回 d。
    val frame = cls("com.tencent.mobileqq.tab.TabFrameLayout")
    val tab = frame.getDeclaredField("e").apply { isAccessible = true }
    val content = frame.getDeclaredMethod("a")
    nativeBarContent = { bar ->
        val owner = generateSequence(bar.parent as? ViewGroup) { it.parent as? ViewGroup }
            .firstOrNull { frame.isInstance(it) }
        if (owner == null) null else runCatching {
            if (tab.get(owner) !== bar) null else (content.invoke(owner) as? ViewGroup)
                ?.takeIf { it.parent === bar.parent }
        }.onFailure { log("底栏原生绑定读取失败，不猜测手势目标", it) }.getOrNull()
    }
    hook(Instrumentation::class.java.getMethod("callActivityOnResume", Activity::class.java)) { chain ->
        chain.proceed().also { watch((chain.getArg(0) as Activity).window.decorView) }
    }
    hook(ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java)) { chain ->
        val source = chain.thisObject as? ViewGroup
        val glass = source?.takeIf { it in floatStates }?.background as? Glass
        if (glass?.drawKernelContent(chain.getArg(0) as Canvas) == true) null else chain.proceed()
    }
    hook(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)) { chain ->
        val source = chain.thisObject as? ViewGroup
        val bar = source?.let { barTouchHosts[it]?.get() ?: it }
        val state = floatStates[bar]?.touch
        if (state == null || source == null) chain.proceed()
        else state.dispatch(source, chain.getArg(0) as MotionEvent) { event -> chain.proceed(arrayOf<Any?>(event)) as Boolean }
    }
    nativeMessageMenu()
}

/** 三个输入均处于 badge.parent 的局部坐标；不把窗口像素直接写入 translationX。 */
internal fun centeredBadgeTranslation(previous: Float, anchor: Float, center: Float): Float =
    if (anchor.isFinite() && center.isFinite()) previous + anchor - center else previous

internal fun raisedBadgeTranslation(original: Float, glyphHeight: Int): Float =
    original - glyphHeight.coerceAtLeast(0)/2f

val View.dp: Float get() = resources.displayMetrics.density

private val watched = WeakHashMap<View, WeakReference<AppearanceObserver>>()
private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

fun refreshLooks() {
    if (!on("显示具体未读条数")) restoreBadgeLayouts() else refreshNativeBadges?.invoke()
    refreshInputLayout()
    refreshChatLayout()
    refreshMessageStyle()
    watched.values.mapNotNull { it.get() }.forEach { it.refresh() }
}

private fun watch(decor: View) {
    if (preferenceListener == null) store()?.let { prefs ->
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshLooks() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        preferenceListener = listener
    }
    if (watched[decor]?.get() != null) { watched[decor]?.get()?.refresh(); return }
    watched[decor] = WeakReference(AppearanceObserver(decor))
}

private class AppearanceObserver(decor: View) : ViewTreeObserver.OnGlobalLayoutListener,
    ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val root = WeakReference(decor)
    private var dirty = true
    init {
        decor.viewTreeObserver.addOnGlobalLayoutListener(this)
        decor.viewTreeObserver.addOnPreDrawListener(this)
        decor.addOnAttachStateChangeListener(this)
        decor.invalidate()
    }
    fun refresh() { dirty = true; root.get()?.invalidate() }
    override fun onGlobalLayout() { dirty = true }
    override fun onPreDraw(): Boolean {
        val decor = root.get() ?: return true
        if (!dirty) return true
        dirty = false
        walk(decor, false)
        return !decor.isLayoutRequested
    }
    fun dispose() {
        root.get()?.let { decor ->
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
                decor.viewTreeObserver.removeOnPreDrawListener(this)
            }
            decor.removeOnAttachStateChangeListener(this)
            watched.remove(decor)
        }
    }
    override fun onViewAttachedToWindow(view: View) = refresh()
    override fun onViewDetachedFromWindow(view: View) = dispose()
}

fun resetLooks() {
    watched.values.mapNotNull { it.get() }.forEach { it.dispose() }
    watched.clear()
    preferenceListener?.let { store()?.unregisterOnSharedPreferenceChangeListener(it) }
    preferenceListener = null
    floatStates.keys.toList().forEach(::restoreBar)
    hidden.entries.toList().forEach { (view, visibility) -> view.visibility = visibility }
    hidden.clear()
    restoreClearedDecorations()
    restoreBadgeLayouts()
}

private fun walk(v: View, inDrawer: Boolean) {
    val name = v.javaClass.name
    when {
        name.endsWith(".QQTabLayout") -> { homeBar(v as ViewGroup); return }
        name.endsWith(".PanelIconLinearLayout") -> { tgInput(v as ViewGroup); return }
        name == "com.tencent.mobileqq.aio.input.edit.AIOEditText" && v is android.widget.EditText -> { tgInput(v); return }
        else -> trim(v, inDrawer)
    }
    if (!inDrawer && generateSequence<Class<*>>(v.javaClass) { it.superclass }
            .any { it.name == "androidx.recyclerview.widget.RecyclerView" }) return
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

private val floated: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
private val originalFloatBottomMargin = WeakHashMap<View, Int>()
private val clearedDecorations = WeakHashMap<View, Int>()

private fun copyLayoutParams(lp: ViewGroup.LayoutParams): ViewGroup.LayoutParams = when (lp) {
    is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(lp)
    is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
    is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(lp)
    is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
    else -> ViewGroup.LayoutParams(lp)
}
private class BarState(bar: ViewGroup) {
    val params = copyLayoutParams(bar.layoutParams)
    val background = bar.background
    val outline = bar.outlineProvider
    val clip = bar.clipToOutline
    val elevation = bar.elevation
    val parentClip = (bar.parent as? ViewGroup)?.clipChildren
    val ambientShadow = bar.outlineAmbientShadowColor
    val spotShadow = bar.outlineSpotShadowColor
    val padding = intArrayOf(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom)
    val stripClip = (bar.getChildAt(0) as? ViewGroup)?.let { it.clipChildren to it.clipToPadding }
    val stripPadding = bar.getChildAt(0)?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    val tabs = WeakHashMap<View, Pair<ViewGroup.LayoutParams, IntArray>>()
    val badgeClips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
    fun restoreBadgeClips() {
        badgeClips.forEach { (group, old) -> group.clipChildren = old.first; group.clipToPadding = old.second }
        badgeClips.clear()
    }
    val touch = BarTouch(bar, nativeBarContent?.invoke(bar))
    var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    var drawListener: ViewTreeObserver.OnPreDrawListener? = null
}
private val floatStates = WeakHashMap<ViewGroup, BarState>()
private fun restoreBar(bar: ViewGroup) {
    val state = floatStates.remove(bar) ?: return
    (bar.parent as? ViewGroup)?.let { barTouchHosts.remove(it) }
    state.touch.dispose()
    state.restoreBadgeClips()
    badgeLayouts.forEach { (view, layout) ->
        if (generateSequence(view.parent as? View) { it.parent as? View }.any { it === bar }) layout.restorePosition(view)
    }
    if (bar.viewTreeObserver.isAlive) {
        state.scrollListener?.let(bar.viewTreeObserver::removeOnScrollChangedListener)
        state.layoutListener?.let(bar.viewTreeObserver::removeOnGlobalLayoutListener)
        state.drawListener?.let(bar.viewTreeObserver::removeOnPreDrawListener)
    }
    (bar.background as? Glass)?.dispose()
    bar.layoutParams = copyLayoutParams(state.params)
    bar.background = state.background; bar.outlineProvider = state.outline; bar.clipToOutline = state.clip
    state.parentClip?.let { (bar.parent as? ViewGroup)?.clipChildren = it }
    bar.outlineAmbientShadowColor = state.ambientShadow; bar.outlineSpotShadowColor = state.spotShadow
    bar.elevation = state.elevation
    state.padding.let { bar.setPadding(it[0], it[1], it[2], it[3]) }
    state.stripPadding?.let { bar.getChildAt(0)?.setPadding(it[0], it[1], it[2], it[3]) }
    state.stripClip?.let { old -> (bar.getChildAt(0) as? ViewGroup)?.let {
        it.clipChildren = old.first; it.clipToPadding = old.second
    } }
    state.tabs.forEach { (tab, old) ->
        tab.layoutParams = copyLayoutParams(old.first)
        old.second.let { tab.setPadding(it[0], it[1], it[2], it[3]) }
    }
    floated.remove(bar); absorbed.remove(bar); originalFloatBottomMargin.remove(bar)
}

private fun homeBar(bar: ViewGroup) {
    if (!on("玻璃底栏")) restoreBar(bar)
    else if (floated.add(bar)) {
        floatStates[bar] = BarState(bar)
        float(bar)
    }
    val glass = bar in floated
    if (glass) settle(bar)
    // material TabLayout：bar → SlidingTabIndicator → TabView × N
    (bar.getChildAt(0) as? ViewGroup)?.let { strip ->
        for (i in 0 until strip.childCount) {
            val tab = strip.getChildAt(i)
            hide(tab, on("藏频道动态") && texts(tab).any { "频道" in it || "动态" in it || "小世界" in it })
            if (glass) {
                floatStates[bar]?.tabs?.getOrPut(tab) { copyLayoutParams(tab.layoutParams) to
                    intArrayOf(tab.paddingLeft, tab.paddingTop, tab.paddingRight, tab.paddingBottom) }
                fit(tab)
            }
        }
    }
    if (bar.height == 0) return
    knob(bar)
    // QQ 给底栏铺的通栏模糊带、分割细线、纯色垫底：胶囊两侧会露出来，都藏。
    if (!glass) {
        restoreClearedDecorations()
        return
    }
    val frame = generateSequence(bar.parent as? ViewGroup) { it.parent as? ViewGroup }
        .firstOrNull { it.javaClass.simpleName == "TabFrameLayout" } ?: bar.parent as? ViewGroup
    frame?.let { clear(it, bar) }
}

private fun float(bar: ViewGroup) {
    bar.outlineProvider = capsule()
    bar.clipToOutline = false // 上游按压指示器从 56dp 扩到 78dp，不能裁在 64dp 栏内。
    (bar.parent as? ViewGroup)?.clipChildren = false
    val dp = bar.dp
    (bar.parent as? ViewGroup)?.let { barTouchHosts[it] = WeakReference(bar) }
    bar.elevation = 10 * dp
    bar.outlineAmbientShadowColor = if (night()) 0x33000000 else 0x1A000000
    bar.outlineSpotShadowColor = bar.outlineAmbientShadowColor
    // 胶囊只包住页签、居中：iOS 那样不是通栏。
    bar.layoutParams?.let {
        it.width = ViewGroup.LayoutParams.WRAP_CONTENT
        it.height = (64 * dp).toInt()
        when (it) {
            is FrameLayout.LayoutParams -> it.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            is LinearLayout.LayoutParams -> it.gravity = Gravity.CENTER_HORIZONTAL
            is RelativeLayout.LayoutParams -> it.addRule(RelativeLayout.CENTER_HORIZONTAL)
        }
        if (it is ViewGroup.MarginLayoutParams) {
            val base = originalFloatBottomMargin.getOrPut(bar) { it.bottomMargin }
            it.bottomMargin = base + (12 * dp).toInt()
        }
        bar.layoutParams = it
    }
    // 两头的留白放在页签条上而不是 bar 上：material 固定模式会把页签条量成 bar 的整宽（含 padding），放 bar 上会挤歪。
    bar.setPadding(0, 0, 0, 0)
    bar.getChildAt(0)?.setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
    (bar.getChildAt(0) as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
    val glass = Glass(bar, kernelBar = true) { selectedTab(bar) }
    bar.background = glass
    // 内容一滚、布局一变就重画一次玻璃（底栏自己不会因为身后的东西动而重画）；选中页签换了也要重画。
    val weak = WeakReference(bar)
    floatStates[bar]?.let { state ->
        state.scrollListener = ViewTreeObserver.OnScrollChangedListener { weak.get()?.invalidate() }
            .also(bar.viewTreeObserver::addOnScrollChangedListener)
        state.layoutListener = ViewTreeObserver.OnGlobalLayoutListener { weak.get()?.invalidate() }
            .also(bar.viewTreeObserver::addOnGlobalLayoutListener)
        state.drawListener = ViewTreeObserver.OnPreDrawListener { (weak.get()?.background as? Glass)?.sync(); true }
            .also(bar.viewTreeObserver::addOnPreDrawListener)
    }
}

/** DOWN 仍走原生点击；只把越过阈值的纵向拖动交还背后的原生滚动容器。 */
internal fun verticalBarDrag(dx: Float, dy: Float, slop: Int): Boolean =
    kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx)

/** 把整条手势平移进原生页的边界，保留所有后续位移和速度。 */
internal fun barTouchOffset(point: Float, size: Int): Float =
    if (size <= 2) 0f else point.coerceIn(1f, size - 1f) - point

private class BarTouch(bar: ViewGroup, pager: ViewGroup?) {
    private val pager = pager?.let { WeakReference(it) }
    private val bar = WeakReference(bar)
    private var owner: WeakReference<ViewGroup>? = null
    private var target: WeakReference<View>? = null
    private var down: MotionEvent? = null
    private var forwarding = false
    private var selecting = false
    private var badgeGesture = false
    private var offsetX = 0f
    private var offsetY = 0f
    private val slop = android.view.ViewConfiguration.get(bar.context).scaledTouchSlop
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = cancel()
    }
    init {
        bar.addOnAttachStateChangeListener(attach)
        if (pager == null) log("底栏原生内容页未绑定：保留按压/横拖，不猜纵滑目标")
    }
    fun dispose() { cancel(); bar.get()?.removeOnAttachStateChangeListener(attach) }

    private fun point(source: View, dest: View, x: Float, y: Float): FloatArray {
        val matrix = android.graphics.Matrix()
        source.transformMatrixToGlobal(matrix); dest.transformMatrixToLocal(matrix)
        return floatArrayOf(x, y).also(matrix::mapPoints)
    }
    private fun contains(source: View, dest: View, event: MotionEvent): Boolean {
        val p = point(source, dest, event.x, event.y)
        return p[0] >= 0 && p[0] < dest.width && p[1] >= 0 && p[1] < dest.height
    }
    private fun send(source: View, dest: View, event: MotionEvent): Boolean {
        val matrix = android.graphics.Matrix()
        source.transformMatrixToGlobal(matrix); dest.transformMatrixToLocal(matrix)
        val copy = MotionEvent.obtain(event)
        return try { copy.transform(matrix); copy.offsetLocation(offsetX, offsetY); dest.dispatchTouchEvent(copy) } finally { copy.recycle() }
    }
    fun dispatch(source: ViewGroup, event: MotionEvent, native: (MotionEvent) -> Boolean): Boolean {
        val bar = bar.get() ?: return native(event)
        val host = bar.parent as? ViewGroup ?: return native(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // 宿主先正常向胶囊分发；胶囊内部由自己的钩子跟踪，不拦设置按钮/浮层。
            if (source === host && contains(host, bar, event)) return native(event)
            // 徽标仍原生拖动/长按，但 DOWN 的视觉反馈不再漏掉。
            var nativeBadge = false
            if (source === bar) {
                fun badgeHit(view: View): Boolean {
                    if (!view.isShown || !contains(bar, view, event)) return false
                    if (generateSequence<Class<*>>(view.javaClass) { it.superclass }
                        .any { it.name == "com.tencent.mobileqq.quibadge.QUIBadge" }) return true
                    return view is ViewGroup && (0 until view.childCount).any { badgeHit(view.getChildAt(it)) }
                }
                nativeBadge = badgeHit(bar)
            }
            val pager = pager?.get()?.takeIf { it.isShown && it.parent === host }
            if (source === host) {
                if (pager == null) return native(event)
                if (point(host, bar, event.x, event.y)[1] < 0) return native(event)
                for (i in host.indexOfChild(pager) + 1 until host.childCount) {
                    val child = host.getChildAt(i)
                    if (child !== bar && child.isShown && child !in clearedDecorations && contains(host, child, event))
                        return native(event)
                }
            }
            cancel()
            badgeGesture = nativeBadge
            owner = WeakReference(source); target = pager?.let { WeakReference(it) }
            down = MotionEvent.obtain(event)
            if (source === bar) (bar.background as? Glass)?.kernelTouchDown(event.x)
        }
        if (owner?.get() !== source || down == null) return native(event)
        val first = down!!
        val dest = target?.get()
        if (!on("玻璃底栏") || (dest != null && !dest.isAttachedToWindow)) { cancel(); return native(event) }
        if (badgeGesture) {
            val handled = native(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) clear()
            return handled
        }
        // 未接管前的多指手势仍归原生；不向列表合成缺失的 POINTER_DOWN。
        if (!forwarding && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            (bar.background as? Glass)?.endKernelDrag(false)
            clear(); return native(event)
        }
        if (!forwarding && !selecting && event.actionMasked == MotionEvent.ACTION_MOVE &&
            kotlin.math.abs(event.x - first.x) > slop &&
            kotlin.math.abs(event.x - first.x) >= kotlin.math.abs(event.y - first.y)) {
            val glass = bar.background as? Glass
            val start = point(source, bar, first.x, first.y)
            if (source === bar && glass?.beginKernelDrag(start[0]) == true) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { native(cancel) } finally { cancel.recycle() }
                selecting = true; host.requestDisallowInterceptTouchEvent(true)
            } else { clear(); return native(event) }
        }
        if (selecting) {
            val glass = bar.background as? Glass
            if (event.actionMasked == MotionEvent.ACTION_MOVE) glass?.dragKernel(point(source, bar, event.x, event.y)[0])
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                selecting = false
                glass?.endKernelDrag(event.actionMasked == MotionEvent.ACTION_UP)
                clear()
            }
            return true
        }
        if (!forwarding && dest != null && event.actionMasked == MotionEvent.ACTION_MOVE &&
            verticalBarDrag(event.x - first.x, event.y - first.y, slop)) {
            val anchor = point(source, dest, first.x, first.y)
            offsetX = barTouchOffset(anchor[0], dest.width)
            offsetY = barTouchOffset(anchor[1], dest.height)
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { native(cancel) } finally { cancel.recycle() }
            if (!send(source, dest, first)) {
                clear()
                return native(event) // 原生页拒绝 DOWN 就不能宣称已接管，也不能吞掉后续事件。
            }
            forwarding = true
            (bar.background as? Glass)?.kernelTouchEnd()
            host.requestDisallowInterceptTouchEvent(true)
        }
        if (!forwarding && source === bar && event.actionMasked == MotionEvent.ACTION_UP) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { native(cancel) } finally { cancel.recycle() }
            val count = (bar.getChildAt(0) as? ViewGroup)?.let { strip ->
                (0 until strip.childCount).count { strip.getChildAt(it).visibility == View.VISIBLE }
            } ?: 0
            val rtl = bar.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val sameTab = kernelIndex(event.x/bar.dp,bar.width/bar.dp,count,rtl) ==
                kernelIndex(first.x/bar.dp,bar.width/bar.dp,count,rtl)
            (bar.background as? Glass)?.kernelTouchEnd(sameTab && event.x in 0f..bar.width.toFloat() && event.y in 0f..bar.height.toFloat())
            clear(); return true
        }
        if (forwarding && dest != null) send(source, dest, event) else native(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) clear()
        return true
    }
    private fun clear() {
        if (selecting) (bar.get()?.background as? Glass)?.endKernelDrag(false)
        selecting = false; badgeGesture = false
        (bar.get()?.background as? Glass)?.kernelTouchEnd()
        down?.recycle(); down = null; owner = null; target = null; forwarding = false; offsetX = 0f; offsetY = 0f
        bar.get()?.parent?.requestDisallowInterceptTouchEvent(false)
    }
    fun cancel() {
        val source = owner?.get(); val dest = target?.get()
        val event = down?.let { MotionEvent.obtain(it).apply { action = MotionEvent.ACTION_CANCEL } }
        val active = forwarding
        if (source != null && event != null) try {
            if (active && dest != null) send(source, dest, event)
            else { clear(); source.dispatchTouchEvent(event) }
        } finally { event.recycle(); clear() } else { event?.recycle(); clear() }
    }
}
private val barTouchHosts = WeakHashMap<ViewGroup, WeakReference<ViewGroup>>()

/** 上游最小宽 76dp、指示器高 56dp；各项等宽，数字变长时一同扩展。 */
private val countTabWidths = WeakHashMap<View, Int>()

private fun fit(tab: View) {
    val strip = tab.parent as? ViewGroup
    val w = kernelTabWidth(tab.dp, strip?.let { group ->
        (0 until group.childCount).filter { group.getChildAt(it).visibility != View.GONE }
            .maxOfOrNull { countTabWidths[group.getChildAt(it)] ?: 0 }
    } ?: 0)
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
        it.height = (64 * bar.dp).toInt()
        absorbed[bar] = inset
        bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, 0)
        bar.layoutParams = it
    }
}

/** 只有 QQ 已经把标签压成 99+、且方法仍提供真实数值时才展开；不猜其它徽标文案。 */
internal fun exactBadgeText(label: String?, count: Long): String? =
    if (label == "99+" && count > 99) count.toString() else null

/** 真包 d(TextView, style, count, background, limit, label, flag)：计数不是 style。 */
internal fun legacyBadgeCount(args: Array<out Any?>): Long? =
    (args.getOrNull(2) as? Number)?.toLong()?.takeIf { it > 99 && (args.getOrNull(1) as? Number)?.toInt() in setOf(3, 4, 7, 9) }

/** QUIBadge 的 d.a 入口会先 setRedText("99+") 并清空 mNum，必须在这个入口保留真计数。 */
internal fun unclampedBadgeArgs(args: Array<Any?>): Array<Any?> =
    if (args.size == 5 && legacyBadgeCount(args) != null)
        args.copyOf().also { it[3] = Int.MAX_VALUE } else args

internal fun bottomNumericBadge(label: String?, type: Int): Boolean =
    type in setOf(2, 3, 4, 5, 9) && !label.isNullOrEmpty() && label.all { it.isDigit() } && label.any { it != '0' }

internal fun numericBadgeWidth(textWidth: Float, height: Int, horizontalPadding: Int): Int =
    maxOf(height, kotlin.math.ceil(textWidth.toDouble()).toInt() + horizontalPadding * 2)

private class BadgeLayout(view: View) {
    private val params = view.layoutParams?.let(::copyLayoutParams)
    private val minWidth = view.minimumWidth
    private val translationX = view.translationX
    private val translationY = view.translationY
    fun raise(view: View, glyphHeight: Int) { view.translationY = raisedBadgeTranslation(translationY,glyphHeight) }
    fun restorePosition(view: View) { view.translationX = translationX; view.translationY = translationY; view.requestLayout() }
    private val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    private val text = (view as? TextView)?.text
    private val gravity = (view as? TextView)?.gravity
    fun restore(view: View) {
        params?.let { view.layoutParams = copyLayoutParams(it) }
        view.minimumWidth = minWidth
        restorePosition(view)
        view.setPadding(padding[0], padding[1], padding[2], padding[3])
        if (view is TextView) { view.text = text; gravity?.let { view.gravity = it } }
    }
}
private val badgeLayouts = WeakHashMap<View, BadgeLayout>()
private var restoreNativeBadge: ((View) -> Unit)? = null
private var refreshNativeBadges: (() -> Unit)? = null
private fun restoreBadgeLayouts() {
    badgeLayouts.entries.toList().forEach { (view, state) ->
        state.restore(view)
        if (view !is TextView) restoreNativeBadge?.invoke(view)
    }
    badgeLayouts.clear()
    floatStates.values.forEach { it.restoreBadgeClips() }
    countTabWidths.keys.toList().forEach { tab -> countTabWidths.remove(tab); fit(tab) }
}

/** 旧 TextView 与 QUIBadge 分别在真实数据入口处理；文字、测量宽度和内边距同时更新。 */
fun exactCount() {
    val badge = cls("com.tencent.mobileqq.quibadge.QUIBadge")
    val viewType = badge.getDeclaredField("mViewType").apply { isAccessible = true }
    val text = badge.getDeclaredField("mText").apply { isAccessible = true }
    val num = badge.getDeclaredField("mNum").apply { isAccessible = true }
    val paint = badge.getDeclaredField("mTextPaint").apply { isAccessible = true }
    val minHeight = badge.method("getMinHeight").apply { isAccessible = true }
    // 关闭时保留真实数据记录、恢复原文字；再开启不必等新消息触发一次绑定。
    val counts = WeakHashMap<View, Long>()
    val nativeTexts = WeakHashMap<View, String>()
    restoreNativeBadge = { view -> nativeTexts.remove(view)?.let { text.set(view, it); view.invalidate() } }
    val legacy = cls("com.tencent.widget.d").getDeclaredMethod("d", TextView::class.java,
        Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, String::class.java, Boolean::class.javaPrimitiveType)
    fun wrap(view: View) {
        badgeLayouts.getOrPut(view) { BadgeLayout(view) }
        view.layoutParams?.let { lp -> if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT; view.layoutParams = lp
        } }
    }
    fun expand(value: Any?) {
        val view = value as? View ?: return
        val label = text.get(view) as? String ?: return
        val count = counts[view] ?: (num.get(view) as? Number)?.toLong() ?: return
        val fixed = exactBadgeText(label, count) ?: return
        nativeTexts.putIfAbsent(view, label)
        wrap(view); text.set(view, fixed)
        view.requestLayout(); view.invalidate()
    }
    refreshNativeBadges = { counts.keys.toList().forEach { expand(it) } }
    fun beforeBinding(view: View) {
        badgeLayouts.remove(view)?.restore(view)
        restoreNativeBadge?.invoke(view)
        counts.remove(view)
    }
    fun bottomTab(view: View): View? {
        val bar = generateSequence(view.parent as? ViewGroup) { it.parent as? ViewGroup }
            .firstOrNull { it in floated } ?: return null
        val strip = bar.getChildAt(0) ?: return null
        return generateSequence(view) { it.parent as? View }.firstOrNull { it.parent === strip }
    }
    val glyphBounds = Rect()
    hook(badge.getDeclaredMethod("onLayout", Boolean::class.javaPrimitiveType,
        Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE), gated = false) { chain ->
        // 真包 onLayout 会更新 mDelegateRect；先校正位置，再让原生更新触摸区域。
        run {
            val view = chain.thisObject as View
            val tab = bottomTab(view) ?: return@run
            val label = text.get(view) as? String
            if (on("显示具体未读条数") && bottomNumericBadge(label, viewType.getInt(view))) {
                val layout = badgeLayouts.getOrPut(view) { BadgeLayout(view) }
                // 用数字字形的实际高度，不以字号或 dp 猜测；从原始位置计算，重复布局不累积。
                (paint.get(view) as Paint).getTextBounds("0123456789",0,10,glyphBounds)
                layout.raise(view,glyphBounds.height())
                val bar = tab.parent?.parent as? ViewGroup
                floatStates[bar]?.let { state ->
                    var group = view.parent as? ViewGroup
                    while (group != null && group !== tab.parent) {
                        state.badgeClips.getOrPut(group) { group.clipChildren to group.clipToPadding }
                        group.clipChildren = false; group.clipToPadding = false
                        if (group === tab) break
                        group = group.parent as? ViewGroup
                    }
                }
                val parent = view.parent as? View ?: return@run
                val transform = Matrix()
                tab.transformMatrixToGlobal(transform); parent.transformMatrixToLocal(transform)
                val anchor = floatArrayOf(tab.width/2f,tab.height/2f).also(transform::mapPoints)
                transform.reset()
                view.transformMatrixToGlobal(transform); parent.transformMatrixToLocal(transform)
                val center = floatArrayOf(view.width/2f,view.height/2f).also(transform::mapPoints)
                view.translationX = centeredBadgeTranslation(view.translationX,anchor[0],center[0])
                val needed = maxOf((56 * view.dp).toInt(), numericBadgeWidth((paint.get(view) as Paint).measureText(label), 0, (6 * view.dp).toInt()))
                if (countTabWidths.put(tab, needed) != needed) fit(tab)
            } else if (countTabWidths.remove(tab) != null) fit(tab)
        }
        chain.proceed()
    }
    // 底栏纯数字直接绘字，不调用原生 drawBackground/drawBorder；其他场景不动。
    hook(badge.getDeclaredMethod("onDraw", android.graphics.Canvas::class.java)) { chain ->
        val view = chain.thisObject as View
        val label = text.get(view) as? String ?: return@hook chain.proceed()
        if (bottomTab(view) == null || !bottomNumericBadge(label, viewType.getInt(view))) chain.proceed()
        else {
            val p = Paint(paint.get(view) as Paint).apply {
                textAlign = Paint.Align.CENTER
                color = monet(night(), 0xFF) ?: if (night()) 0xFFFFB4AB.toInt() else 0xFFB3261E.toInt()
            }
            val metrics = p.fontMetrics
            (chain.getArg(0) as android.graphics.Canvas).drawText(label, view.width / 2f,
                view.height / 2f - (metrics.ascent + metrics.descent) / 2f, p)
            null
        }
    }
    val binding = cls("com.tencent.widget.d").getDeclaredMethod("a", badge,
        Integer.TYPE, Integer.TYPE, Integer.TYPE, String::class.java)
    hook(binding, gated = false) { chain ->
        val args = chain.args.toTypedArray()
        val view = args[0] as? View
        chain.proceed(if (on("显示具体未读条数")) unclampedBadgeArgs(args) else args).also {
            if (view != null) {
                legacyBadgeCount(args)?.let { counts[view] = it } ?: counts.remove(view)
                if (on("显示具体未读条数")) expand(view)
            }
        }
    }
    // 用公开数量入口，不依赖私有 updateNum 是否被内联；关闭时也记录以支持即时重开。
    for (name in listOf("setRedNum", "setGrayNum", "setAIOBarNum")) {
        hook(badge.getDeclaredMethod(name, Integer.TYPE), gated = false) { chain ->
            val view = chain.thisObject as View
            beforeBinding(view)
            chain.proceed().also {
                counts[view] = (chain.getArg(0) as Number).toLong()
                if (on("显示具体未读条数")) expand(view)
            }
        }
    }
    // 数字变成 NEW、红点或图标时清掉旧计数，禁止把复用视图的旧数字填到新文案。
    for (name in listOf("setRedText", "setGrayText", "setRedDot", "setGrayDot", "setIcon",
            "setRedNumWithIcon", "setGrayNumWIthIcon")) {
        hook(badge.method(name), gated = false) { chain ->
            beforeBinding(chain.thisObject as View)
            chain.proceed()
        }
    }
    hook(badge.method("getMinWidth")) { chain ->
        val native = chain.proceed()
        val view = chain.thisObject as? View ?: return@hook native
        val label = text.get(view) as? String ?: return@hook native
        val count = counts[view] ?: (num.get(view) as? Number)?.toLong() ?: return@hook native
        if (count <= 99 || label != count.toString()) return@hook native
        numericBadgeWidth((paint.get(view) as Paint).measureText(label), minHeight.invoke(view) as Int, (4 * view.dp).toInt())
    }
    hook(legacy) { chain ->
        val view = chain.getArg(0) as? TextView
        view?.let { badgeLayouts.remove(it)?.restore(it) }
        val count = legacyBadgeCount(Array<Any?>(7) { chain.getArg(it) })
        chain.proceed().also {
            if (view != null && count != null) exactBadgeText(view.text?.toString(), count)?.let { fixed ->
                wrap(view)
                view.text = fixed; view.gravity = Gravity.CENTER
                val gap = (4 * view.dp).toInt()
                view.setPadding(gap, view.paddingTop, gap, view.paddingBottom)
                view.minimumWidth = maxOf(view.minimumHeight, (18 * view.dp).toInt())
                view.requestLayout(); view.invalidate()
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
private fun clear(root: ViewGroup, bar: ViewGroup) {
    val top = windowY(bar) - (24 * bar.dp).toInt()
    val content = nativeBarContent?.invoke(bar)
    fun go(v: View) {
        if (v === bar || v === content) return
        val name = v.javaClass.simpleName
        val strip = v.height <= bar.height * 2 && v.width > bar.width / 2
        val decorative = name.contains("Blur") || (strip && (v.javaClass == View::class.java || v.height <= 2))
        if (decorative && windowY(v) + v.height > top && !v.isAncestorOf(bar)) {
            clearedDecorations.putIfAbsent(v, v.visibility)
            if (v.visibility == View.VISIBLE) v.visibility = View.INVISIBLE
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) go(v.getChildAt(i))
    }
    go(root)
}

private fun restoreClearedDecorations() {
    val it = clearedDecorations.entries.iterator()
    while (it.hasNext()) {
        val (v, visibility) = it.next()
        if (v.visibility == View.INVISIBLE) v.visibility = visibility
        it.remove()
    }
}

private fun View.isAncestorOf(v: View): Boolean = generateSequence(v.parent) { it.parent }.any { it === this }
