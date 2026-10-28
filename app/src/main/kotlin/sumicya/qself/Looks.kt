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
import android.view.Window
import android.view.WindowInsets
import java.lang.ref.WeakReference
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

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
        chain.proceed().also {
            val activity = chain.getArg(0) as Activity
            val decor = activity.window?.decorView ?: return@also
            watch(decor)
            noteTopInsets(decor)
            restoreStatusBar(activity)
        }
    }
    hook(ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java)) { chain ->
        // 这个钩子每个 ViewGroup 每帧都过一遍（真机日志 23.7 万次/5 小时），先用字段读判掉绝大多数。
        // 帧率采样靠这一次调用判断「界面真的画了」，空闲时就不再去抢垂直同步。
        FrameMeter.noteDraw()
        val source = chain.thisObject as? ViewGroup
        val background = source?.background
        val glass = if (background is Glass) background else floatStates[source]?.glass
        if (glass?.drawKernelContent(chain.getArg(0) as Canvas) == true) null else chain.proceed()
    }
    hook(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)) { chain ->
        val source = chain.thisObject as? ViewGroup
        if (source != null && on("玻璃底栏")) {
            aioHeaders[source]?.let { header ->
                if (header.multiSelectRoot()?.visibility != View.VISIBLE) {
                    return@hook header.dispatchPillTouch(chain.getArg(0) as MotionEvent)
                }
            }
            secNavBars[source]?.let { sec ->
                return@hook sec.dispatchPillTouch(chain.getArg(0) as MotionEvent)
            }
        }
        val bar = source?.let { barTouchHosts[it]?.get() ?: it }
        val state = floatStates[bar]?.touch
        if (state == null || source == null) chain.proceed()
        else state.dispatch(source, chain.getArg(0) as MotionEvent) { event -> chain.proceed(arrayOf<Any?>(event)) as Boolean }
    }
    // 真包 TabFrameControllerImpl#checkEnableTabAnim 会在切换页签/主题恢复时把 0x7f0a67ce (QQTabLayout) 高度重设为 106px (54dp) 并重置 TabDragAnimationView 内边距；在此处同步重套 64dp 胶囊与居中规则。
    runCatching {
        val controller = cls("com.tencent.mobileqq.activity.home.impl.TabFrameControllerImpl")
        val frameFrag = cls("com.tencent.mobileqq.app.FrameFragment")
        hook(controller.getDeclaredMethod("checkEnableTabAnim", frameFrag, Boolean::class.javaPrimitiveType), gated = false) { chain ->
            chain.proceed().also {
                floated.toList().forEach { (it as? ViewGroup)?.let(::homeBar) }
            }
        }
        hook(controller.getDeclaredMethod("recoverUselessBackground", View::class.java), gated = false) { chain ->
            if (on("玻璃底栏")) {
                controller.getDeclaredMethod("clearUselessBackground", View::class.java).apply { isAccessible = true }
                    .invoke(chain.thisObject, chain.args.getOrNull(0))
                null
            } else chain.proceed()
        }
    }
    // 真包 MainResourceConfig#n(Context) / #p(Context, Drawable) 决定主页顶栏状态栏安全区高度（140px）与底色，
    // MainUIHelperApiImpl#updateTitleBar、Leba#updateTitleBar 与 TitleAreaHeadLayout#k 均由此派生顶栏总高度（194px）与列表顶部内边距（a$r）。
    runCatching {
        val resCfgCls = cls("com.tencent.mobileqq.resconfig.MainResourceConfig")
        hook(resCfgCls.getDeclaredMethod("n", android.content.Context::class.java), gated = false) { chain ->
            val orig = (chain.proceed() as? Number)?.toInt() ?: 0
            val ctx = chain.args.getOrNull(0) as? android.content.Context
            val out = if (on("玻璃底栏") && orig > 0 && ctx != null) {
                reducedSafeTop(orig, ctx.resources.displayMetrics.density)
            } else orig
            recordQselfEvent("MainResourceConfig#n orig=${orig}px -> ${out}px（下沿=${topInsetPx}px，下移=${safeTopShiftDp()}dp）")
            out
        }
        hook(resCfgCls.getDeclaredMethod("p", android.content.Context::class.java, android.graphics.drawable.Drawable::class.java), gated = false) { chain ->
            val orig = chain.proceed()
            if (on("玻璃底栏")) null else orig
        }
    }
    runCatching {
        val mainUiHelper = cls("com.tencent.mobileqq.main.api.impl.MainUIHelperApiImpl")
        val frameCls = cls("com.tencent.mobileqq.app.Frame")
        val resCfgCls = cls("com.tencent.mobileqq.resconfig.MainResourceConfig")
        hook(mainUiHelper.getDeclaredMethod("updateTitleBar", frameCls, RelativeLayout::class.java, resCfgCls, Boolean::class.javaPrimitiveType), gated = false) { chain ->
            chain.proceed().also {
                (chain.args.getOrNull(1) as? ViewGroup)?.let(::homeTopBar)
            }
        }
    }
    runCatching {
        val lebaCls = cls("com.tencent.mobileqq.leba.Leba")
        val resCfgCls = cls("com.tencent.mobileqq.resconfig.MainResourceConfig")
        hook(lebaCls.getDeclaredMethod("updateTitleBar", RelativeLayout::class.java, resCfgCls), gated = false) { chain ->
            chain.proceed().also {
                (chain.args.getOrNull(0) as? ViewGroup)?.let(::homeTopBar)
            }
        }
    }
    runCatching {
        val headCls = cls("com.tencent.mobileqq.activity.home.view.TitleAreaHeadLayout")
        headCls.declaredMethods.firstOrNull { it.name == "k" && it.parameterTypes.size == 2 }?.let { m ->
            hook(m.apply { isAccessible = true }, gated = false) { chain ->
                chain.proceed().also {
                    (chain.thisObject as? ViewGroup)?.let(::homeTopBar)
                }
            }
        }
    }
    // 真包 SkinnableRecycleView#setClipToTopPadding(true) 会在 dispatchDraw 中用 canvas.clipRect(scrollX, scrollY + paddingTop, ...) 硬裁切会话列表顶部 194px 区域；强制关闭以允许列表滚入顶栏下方。
    runCatching {
        val skinRvCls = cls("com.tencent.qqnt.chats.view.SkinnableRecycleView")
        val clipField = skinRvCls.getDeclaredField("f").apply { isAccessible = true }
        hook(skinRvCls.getDeclaredMethod("setClipToTopPadding", Boolean::class.javaPrimitiveType), gated = false) { chain ->
            if (on("玻璃底栏")) {
                chain.proceed(arrayOf<Any?>(false))
            } else chain.proceed()
        }
        hook(skinRvCls.getDeclaredMethod("dispatchDraw", Canvas::class.java), gated = false) { chain ->
            val rv = chain.thisObject as? ViewGroup
            if (rv != null && on("玻璃底栏")) {
                runCatching { if (clipField.getBoolean(rv)) clipField.setBoolean(rv, false) }
            }
            chain.proceed()
        }
    }
    // 真包 SimpleModeHelper#u / #A 会在主页 Conversation 顶栏底部生成并显示 mStatusTitleDivider 分割线；开启无安全区顶栏时隐藏该分割线。
    runCatching {
        val simpleHelper = cls("com.tencent.mobileqq.utils.SimpleModeHelper")
        hook(simpleHelper.getDeclaredMethod("u", View::class.java), gated = false) { chain ->
            chain.proceed().also { div ->
                (div as? View)?.let { v ->
                    homeDividers.putIfAbsent(v, v.visibility)
                    if (on("玻璃底栏")) v.visibility = View.GONE
                }
            }
        }
        hook(simpleHelper.getDeclaredMethod("A", View::class.java), gated = false) { chain ->
            chain.proceed().also {
                (chain.args.getOrNull(0) as? View)?.let { v ->
                    homeDividers.putIfAbsent(v, v.visibility)
                    if (on("玻璃底栏")) v.visibility = View.GONE
                }
            }
        }
    }
    // 真包 QUISecNavBar#T(Activity) / #U(Fragment) 内联调用 W(Window)->X() 重设 mRoot.paddingTop，Z(ImageView, boolean) 为右侧按钮单独套圆底；全部钩住以保持 NagramXF 三段玻璃胶囊。
    runCatching {
        val secNavCls = cls("com.tencent.biz.qui.quisecnavbar.QUISecNavBar")
        for (mName in listOf("S", "T", "U", "W", "X", "C", "Q", "R", "Z", "updateView", "setBlur", "setTitleBlurAlpha")) {
            secNavCls.declaredMethods.filter { it.name == mName }.forEach { m ->
                hook(m.apply { isAccessible = true }, gated = false) { chain ->
                    chain.proceed().also {
                        val nav = (chain.thisObject as? ViewGroup) ?: (chain.args.firstOrNull { secNavCls.isInstance(it) } as? ViewGroup)
                        nav?.let(::secNavBar)
                    }
                }
            }
        }
    }
    // 真包 SystemBarTintManager 会通过 handleMessage / setStatusBarTintEnabled / setStatusBarAlpha 异步恢复 DecorView 顶部的 140px mStatusBarTintView 色块（遮挡上移后的胶囊上半部）。
    runCatching {
        val tintMgrCls = cls("com.tencent.widget.immersive.SystemBarTintManager")
        val tintViewField = tintMgrCls.getDeclaredField("mStatusBarTintView").apply { isAccessible = true }
        fun syncTintView(mgr: Any?) {
            val v = runCatching { mgr?.let { tintViewField.get(it) as? View } }.getOrNull() ?: return
            statusTintAlphas.putIfAbsent(v, v.alpha)
            val targetAlpha = if (on("玻璃底栏")) 0f else (statusTintAlphas[v] ?: 1f)
            v.clearAnimation()
            if (v.alpha != targetAlpha) v.alpha = targetAlpha
        }
        hook(tintMgrCls.getDeclaredMethod("setupStatusBarView", android.view.Window::class.java, ViewGroup::class.java), gated = false) { chain ->
            chain.proceed().also { syncTintView(chain.thisObject) }
        }
        hook(tintMgrCls.getDeclaredMethod("setStatusBarTintEnabled", Boolean::class.javaPrimitiveType), gated = false) { chain ->
            chain.proceed().also { syncTintView(chain.thisObject) }
        }
        hook(tintMgrCls.getDeclaredMethod("handleMessage", android.os.Message::class.java), gated = false) { chain ->
            chain.proceed().also { syncTintView(chain.thisObject) }
        }
        hook(tintMgrCls.getDeclaredMethod("setStatusBarAlpha", java.lang.Float.TYPE), gated = false) { chain ->
            chain.proceed().also { syncTintView(chain.thisObject) }
        }
    }
    runCatching { aioHeader() }
    runCatching { nativeMessageMenu() }
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
    if (!on("未读实数")) restoreBadgeLayouts() else refreshNativeBadges?.invoke()
    refreshAioHeaders()
    refreshSecNavBars()
    refreshSearchBars()
    refreshHomeTopBars()
    refreshInputLayout()
    refreshChatLayout()
    refreshMessageStyle()
    watched.values.mapNotNull { it.get() }.forEach { it.refresh() }
}

/**
 * 顶部下沿：状态栏照常显示（用户实测它会自己冒出来，不该被藏），但顶栏内容不能跑到它上面去。
 * 这里量出 `max(状态栏高, 摄像头挖孔下沿)`，作为顶栏内容的硬下界 —— 滑条压到底也停在摄像头下面。
 * 每次窗口恢复前台、每次全局布局刷新一次；不每帧读 insets（每次都会新建对象）。
 */
@Volatile
private var topInsetPx = 0

internal fun topInsetPx(): Int = topInsetPx

private fun noteTopInsets(view: View?) {
    val insets = view?.rootWindowInsets ?: return
    val status = runCatching { insets.getInsets(WindowInsets.Type.statusBars()).top }.getOrDefault(0)
    val cutout = runCatching { insets.getInsets(WindowInsets.Type.displayCutout()).top }.getOrDefault(0)
    // 两个都读到 0 时保留上次的值：全屏页可能把状态栏让成 0，不能因此把顶栏推回 y=0。
    if (status > 0 || cutout > 0) topInsetPx = maxOf(status, cutout)
}

/**
 * 退出本模块上一版「把状态栏藏掉」的现场：状态栏现在必须可见。只在恢复前台时看一眼，
 * 不可见就 show 回来一次，不做每帧巡检，也不跟系统来回抢。
 */
private fun restoreStatusBar(activity: Activity?) {
    val window = activity?.window ?: return
    runCatching {
        val shown = window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars()) ?: true
        if (shown) return@runCatching
        window.insetsController?.show(WindowInsets.Type.statusBars())
        recordQselfEvent("系统状态栏不是可见状态，已还原（本模块不再藏状态栏）")
    }.onFailure { log("状态栏还原失败，保持原样", it) }
}

private fun watch(decor: View) {
    if (preferenceListener == null) store()?.let { prefs ->
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshLooks() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        preferenceListener = listener
    }
    (decor.context as? Activity)?.let { scheduleActivitySnapshot(it, "watch") }
    if (watched[decor]?.get() != null) { watched[decor]?.get()?.refresh(); return }
    watched[decor] = WeakReference(AppearanceObserver(decor))
}

/** 整树遍历的最小间隔：藏东西的规则不需要逐帧精度。 */
private const val WALK_MIN_GAP_MS = 200L

private class AppearanceObserver(decor: View) : ViewTreeObserver.OnGlobalLayoutListener,
    ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val root = WeakReference(decor)
    private var dirty = true
    private var cancelledOnce = false
    private var lastWalkAt = 0L
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
        if (!dirty) { cancelledOnce = false; return true }
        // 滚动时 RecyclerView 每帧都会触发全局布局，逐帧整树遍历等于每帧几百个视图各查一遍文案。
        // 规则只影响「藏不藏东西」，200ms 一次足够，dirty 会保持到下一次真正遍历。
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastWalkAt < WALK_MIN_GAP_MS) return true
        lastWalkAt = now
        dirty = false
        val wasRequested = decor.isLayoutRequested
        noteTopInsets(decor)
        walk(decor, false)
        if (!wasRequested && decor.isLayoutRequested && !cancelledOnce) {
            cancelledOnce = true
            dirty = true
            return false
        }
        cancelledOnce = false
        return true
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
    resetAioHeaders()
    resetSecNavBars()
    resetSearchBars()
    resetHomeTopBars()
}

/** 一轮遍历内复用的开关值：每个视图各查一次 SharedPreferences 是纯浪费。 */
private var sideTrimOn = true
private var onlineHideOn = true

private fun walk(v: View, inDrawer: Boolean) {
    if (v.parent == null && v is ViewGroup) {
        sideTrimOn = on("侧栏精简")
        onlineHideOn = on("隐藏在线状态")
        syncStatusBarTintViews(v)
    }
    val name = v.javaClass.name
    when {
        name.endsWith(".QQTabLayout") -> { homeBar(v as ViewGroup); return }
        name == "com.tencent.biz.qui.quisecnavbar.QUISecNavBar" && v is ViewGroup -> { secNavBar(v); return }
        name == "com.tencent.mobileqq.widget.search.QUISearchBar" && v is ViewGroup -> { searchBar(v); return }
        name.endsWith(".PanelIconLinearLayout") -> { tgInput(v as ViewGroup); return }
        name == "com.tencent.mobileqq.aio.input.edit.AIOEditText" && v is android.widget.EditText -> { tgInput(v); return }
        else -> trim(v, inDrawer)
    }
    if (v is ViewGroup && (v.id == 0x7f0a11f9 || v.id == 0x7f0a6594 || v.id == 0x7f0a6cfa ||
            v.id == 0x7f0a5dff || v.id == 0x7f0a6588 || v.id == 0x7f0a3245 ||
            v.id == 0x7f0a64c2 || v.id == 0x7f0a7361 || v.id == 0x7f0a5e9c)) homeTopBar(v)
    if (v is ViewGroup && (v.id == 0x7f0a0e96 || v.id == 0x7f0a6401 || v.id == 0x7f0a5b55)) homeListClip(v)
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
    if (!sideTrimOn && !onlineHideOn) return
    val dp = v.dp
    val shortRow = v.height in 1..(72 * dp).toInt()
    val desc = if (shortRow) v.contentDescription?.toString().orEmpty() else ""
    // 聊天标题栏右上角那排：有 contentDescription、矮、贴着窗口顶。
    val title = shortRow && desc.length in 1..10 && TITLE.any { it in desc } && windowY(v) < 160 * dp
    // 侧栏里一行行可点的：打卡、天气、等级、会员、装扮……（文案多半带「我的」前缀，所以用包含）
    val row = inDrawer && v.isClickable && v.height in 1..(96 * dp).toInt() &&
        texts(v).any { t -> DRAWER.any { it in t } }
    val status = v is TextView && v.height in 1..(40 * dp).toInt() &&
        (v.text?.toString()?.trim() ?: "") in ONLINE && windowY(v) < 200 * dp
    hide(v, sideTrimOn && (title || row || v.javaClass.simpleName == "WeatherSettingMeItemView") ||
        onlineHideOn && status)
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

private val windowYLoc = IntArray(2)

fun windowY(v: View): Int {
    v.getLocationInWindow(windowYLoc)
    return windowYLoc[1]
}

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
private val tabLayoutGravityField by lazy {
    runCatching { cls("com.google.android.material.tabs.TabLayout").getDeclaredField("T").apply { isAccessible = true } }.getOrNull()
}
private val tabLayoutMinWidthField by lazy {
    runCatching { cls("com.google.android.material.tabs.TabLayout").getDeclaredField("P").apply { isAccessible = true } }.getOrNull()
}
private val tabDragSetGravityMethod by lazy {
    runCatching { cls("com.tencent.mobileqq.widget.TabDragAnimationView").getDeclaredMethod("setIconGravity", Integer.TYPE).apply { isAccessible = true } }.getOrNull()
}

private class BarState(bar: ViewGroup) {
    val params = copyLayoutParams(bar.layoutParams)
    var background = bar.background
    val glass = Glass(bar, kernelBar = true) { selectedTab(bar) }
    val outline = bar.outlineProvider
    val clip = bar.clipToOutline
    val elevation = bar.elevation
    val origTranslationX = bar.translationX
    val origTabGravity = runCatching { tabLayoutGravityField?.getInt(bar) }.getOrNull()
    val origTabMinWidth = runCatching { tabLayoutMinWidthField?.getInt(bar) }.getOrNull()
    val parentClip = (bar.parent as? ViewGroup)?.clipChildren
    val ambientShadow = bar.outlineAmbientShadowColor
    val spotShadow = bar.outlineSpotShadowColor
    val padding = intArrayOf(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom)
    val stripClip = (bar.getChildAt(0) as? ViewGroup)?.let { it.clipChildren to it.clipToPadding }
    val stripPadding = bar.getChildAt(0)?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    val tabs = WeakHashMap<View, Pair<ViewGroup.LayoutParams, IntArray>>()
    val tabMinWidths = WeakHashMap<View, Int>()
    val tabCustoms = WeakHashMap<View, ViewGroup.LayoutParams>()
    val tabCustomClips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
    val tabIcons = WeakHashMap<View, Pair<ViewGroup.LayoutParams, IntArray>>()
    val tabTexts = WeakHashMap<View, ViewGroup.LayoutParams>()
    val tabBadges = WeakHashMap<View, ViewGroup.LayoutParams>()
    val badgeClips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
    fun restoreBadgeClips() {
        badgeClips.forEach { (group, old) -> group.clipChildren = old.first; group.clipToPadding = old.second }
        badgeClips.clear()
    }
    val touch = BarTouch(bar, nativeBarContent?.invoke(bar))
    var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    var drawListener: ViewTreeObserver.OnPreDrawListener? = null
    var preDrawPasses = 0
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
    state.glass.dispose()
    (bar.background as? Glass)?.dispose()
    state.origTabGravity?.let { g -> runCatching { tabLayoutGravityField?.setInt(bar, g) } }
    state.origTabMinWidth?.let { mw -> runCatching { tabLayoutMinWidthField?.setInt(bar, mw) } }
    bar.layoutParams = copyLayoutParams(state.params)
    bar.translationX = state.origTranslationX
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
    state.tabMinWidths.forEach { (tab, mw) -> tab.minimumWidth = mw }
    state.tabCustoms.forEach { (v, oldLp) -> v.layoutParams = copyLayoutParams(oldLp) }
    state.tabCustomClips.forEach { (g, old) -> g.clipChildren = old.first; g.clipToPadding = old.second }
    state.tabIcons.forEach { (v, old) ->
        v.layoutParams = copyLayoutParams(old.first)
        old.second.let { v.setPadding(it[0], it[1], it[2], it[3]) }
    }
    state.tabTexts.forEach { (v, oldLp) -> v.layoutParams = copyLayoutParams(oldLp) }
    state.tabBadges.forEach { (v, oldLp) -> v.layoutParams = copyLayoutParams(oldLp) }
    floated.remove(bar); absorbed.remove(bar); originalFloatBottomMargin.remove(bar)
}

private fun homeBar(bar: ViewGroup) {
    if (!on("玻璃底栏")) restoreBar(bar)
    else if (floated.add(bar)) {
        floatStates[bar] = BarState(bar)
        float(bar)
    }
    val glass = bar in floated
    // material TabLayout：bar → SlidingTabIndicator → TabView × N
    (bar.getChildAt(0) as? ViewGroup)?.let { strip ->
        for (i in 0 until strip.childCount) {
            val tab = strip.getChildAt(i)
            hide(tab, on("隐藏频道动态") && texts(tab).any { "频道" in it || "动态" in it || "小世界" in it })
            if (glass) {
                floatStates[bar]?.tabs?.getOrPut(tab) { copyLayoutParams(tab.layoutParams) to
                    intArrayOf(tab.paddingLeft, tab.paddingTop, tab.paddingRight, tab.paddingBottom) }
                fit(tab)
            }
        }
    }
    if (glass) settle(bar)
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
    val glass = floatStates[bar]?.glass ?: Glass(bar, kernelBar = true) { selectedTab(bar) }
    bar.background = glass
    // 内容一滚、布局一变就重画一次玻璃（底栏自己不会因为身后的东西动而重画）；选中页签换了也要重画。
    val weak = WeakReference(bar)
    floatStates[bar]?.let { state ->
        state.scrollListener = ViewTreeObserver.OnScrollChangedListener { weak.get()?.invalidate() }
            .also(bar.viewTreeObserver::addOnScrollChangedListener)
        state.layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            weak.get()?.let { b ->
                if (b in floated) {
                    settle(b)
                    knob(b)
                    b.invalidate()
                }
            }
        }.also(bar.viewTreeObserver::addOnGlobalLayoutListener)
        state.drawListener = ViewTreeObserver.OnPreDrawListener {
            val b = weak.get() ?: return@OnPreDrawListener true
            if (b in floated) {
                val strip = b.getChildAt(0) as? ViewGroup
                if (strip != null) {
                    for (i in 0 until strip.childCount) {
                        val tab = strip.getChildAt(i)
                        if (tab.visibility != View.GONE) fit(tab)
                    }
                }
                settle(b)
                if ((b.isLayoutRequested || strip?.isLayoutRequested == true) && state.preDrawPasses < 2) {
                    state.preDrawPasses++
                    return@OnPreDrawListener false
                }
                state.preDrawPasses = 0
                knob(b)
            }
            state.glass.sync()
            true
        }.also(bar.viewTreeObserver::addOnPreDrawListener)
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

private fun uniformTabWidth(strip: ViewGroup?, dp: Float): Int {
    val maxCountW = strip?.let { group ->
        (0 until group.childCount).filter { group.getChildAt(it).visibility != View.GONE }
            .maxOfOrNull { countTabWidths[group.getChildAt(it)] ?: 0 }
    } ?: 0
    return kernelTabWidth(dp, maxCountW)
}

private fun fit(tab: View) {
    val strip = tab.parent as? ViewGroup
    val bar = strip?.parent as? ViewGroup
    val state = bar?.let { floatStates[it] }
    val dp = tab.dp
    val w = uniformTabWidth(strip, dp)
    if (bar != null) {
        runCatching {
            if (tabLayoutGravityField?.getInt(bar) != 0) tabLayoutGravityField?.setInt(bar, 0)
            if (tabLayoutMinWidthField?.getInt(bar) != w) tabLayoutMinWidthField?.setInt(bar, w)
        }
    }
    state?.tabMinWidths?.getOrPut(tab) { tab.minimumWidth }
    if (tab.minimumWidth != w) tab.minimumWidth = w
    val lp = tab.layoutParams as? LinearLayout.LayoutParams ?: return
    if (lp.width != w || lp.height != ViewGroup.LayoutParams.MATCH_PARENT || lp.weight != 0f ||
        tab.paddingLeft != 0 || tab.paddingTop != 0 || tab.paddingRight != 0 || tab.paddingBottom != 0) {
        lp.width = w
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.weight = 0f
        tab.setPadding(0, 0, 0, 0)
        tab.layoutParams = lp
    }
    // 真包 TabLayout$TabView 子视图顺序为：0=f(ImageView), 1=e(TextView), 2=m(customView: r/i/c07.xml 或 TianshuRedTouch)。
    // 严禁用 getChildAt(0) as? ViewGroup（恒为 null），直接通过 findViewById 定位图标/文字/角标并向上遍历至 tab。
    val icon = tab.findViewById<View>(0x7f0a67c7)
    val text = tab.findViewById<TextView>(0x7f0a7345)
    val badge = tab.findViewById<View>(0x7f0a74a1)
    val anchor = tab.findViewById<View>(0x7f0a75e0)
    var cur = (icon ?: text ?: badge)?.parent as? ViewGroup
    while (cur != null && cur !== tab && cur !== strip) {
        val group = cur
        state?.tabCustomClips?.getOrPut(group) { group.clipChildren to group.clipToPadding }
        if (group.clipChildren || group.clipToPadding) {
            group.clipChildren = false
            group.clipToPadding = false
        }
        group.layoutParams?.let { clp ->
            state?.tabCustoms?.getOrPut(group) { copyLayoutParams(clp) }
            if (clp.width != ViewGroup.LayoutParams.MATCH_PARENT || clp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                clp.width = ViewGroup.LayoutParams.MATCH_PARENT
                clp.height = ViewGroup.LayoutParams.MATCH_PARENT
                when (clp) {
                    is LinearLayout.LayoutParams -> clp.gravity = Gravity.CENTER
                    is FrameLayout.LayoutParams -> clp.gravity = Gravity.CENTER
                }
                group.layoutParams = clp
            }
        }
        cur = group.parent as? ViewGroup
    }
    anchor?.layoutParams?.let { alp ->
        state?.tabCustoms?.getOrPut(anchor) { copyLayoutParams(alp) }
        if (alp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            alp.height = ViewGroup.LayoutParams.MATCH_PARENT
            anchor.layoutParams = alp
        }
    }
    icon?.let { iv ->
        val ilp = iv.layoutParams
        if (ilp != null) {
            state?.tabIcons?.getOrPut(iv) {
                copyLayoutParams(ilp) to intArrayOf(iv.paddingLeft, iv.paddingTop, iv.paddingRight, iv.paddingBottom)
            }
            val hasLabel = text != null && text.visibility != View.GONE
            val padTop = if (hasLabel) (6 * dp).roundToInt() else 0
            val targetGravity = if (hasLabel) 1 else 0
            runCatching { tabDragSetGravityMethod?.invoke(iv, targetGravity) }
            if (ilp.width != ViewGroup.LayoutParams.MATCH_PARENT || ilp.height != ViewGroup.LayoutParams.MATCH_PARENT ||
                iv.paddingTop != padTop || iv.paddingBottom != 0 || iv.paddingLeft != 0 || iv.paddingRight != 0) {
                ilp.width = ViewGroup.LayoutParams.MATCH_PARENT
                ilp.height = ViewGroup.LayoutParams.MATCH_PARENT
                iv.setPadding(0, padTop, 0, 0)
                iv.layoutParams = ilp
            }
        }
    }
    text?.let { tv ->
        val tlp = tv.layoutParams as? ViewGroup.MarginLayoutParams
        if (tlp != null) {
            state?.tabTexts?.getOrPut(tv) { copyLayoutParams(tlp) }
            val btm = (6 * dp).roundToInt()
            if (tlp is RelativeLayout.LayoutParams) {
                tlp.addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
                tlp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
            }
            if (tlp.bottomMargin != btm || tlp.leftMargin != 0 || tlp.rightMargin != 0 || tv.gravity != Gravity.CENTER) {
                tlp.bottomMargin = btm
                tlp.leftMargin = 0
                tlp.rightMargin = 0
                tv.gravity = Gravity.CENTER
                tv.layoutParams = tlp
            }
        }
    }
    badge?.let { bv ->
        if (bv in badgeLayouts && bv.visibility == View.VISIBLE && tab.width > 0 && bv.width > 0) {
            val targetTx = tab.width / 2f - (bv.left + bv.width / 2f)
            if (bv.translationX != targetTx) bv.translationX = targetTx
        } else {
            val blp = bv.layoutParams as? RelativeLayout.LayoutParams
            if (blp != null) {
                state?.tabBadges?.getOrPut(bv) { copyLayoutParams(blp) }
                val topM = (3 * dp).roundToInt()
                if (blp.topMargin != topM) {
                    blp.topMargin = topM
                    bv.layoutParams = blp
                }
            }
        }
    }
}

/** material TabLayout 选中的那个 TabView（bar → SlidingTabIndicator → TabView × N）。 */
fun selectedTab(bar: ViewGroup): View? {
    val strip = bar.getChildAt(0) as? ViewGroup ?: return null
    for (i in 0 until strip.childCount) strip.getChildAt(i).let { if (it.isSelected && it.visibility == View.VISIBLE) return it }
    return null
}

/** QQ 给手势条留的底部内边距（可能随 insets 反复设回来）挪成外边距，胶囊本身不带空腔。 */
private val absorbed = WeakHashMap<View, Int>()

/** 底栏与右侧设置按钮作为整体在父容器内水平居中时，底栏相对父容器中心的水平平移量。 */
internal fun centeredBarTranslationX(hostWidth: Int, barWidth: Int, density: Float, rtl: Boolean): Float {
    if (hostWidth <= 0 || barWidth <= 0) return 0f
    val gap = (12 * density).toInt()
    val knobW = (76 * density).toInt()
    val edge = (8 * density).toInt()
    if (barWidth + gap + knobW + 2 * edge > hostWidth) return 0f
    val shift = (gap + knobW) / 2f
    return if (rtl) shift else -shift
}

private fun settle(bar: ViewGroup) {
    floatStates[bar]?.let { state ->
        if (bar.background !== state.glass) {
            if (bar.background !is Glass) state.background = bar.background
            bar.background = state.glass
        }
        val shadow = if (night()) 0x33000000 else 0x1A000000
        bar.outlineAmbientShadowColor = shadow
        bar.outlineSpotShadowColor = shadow
    }
    val dp = bar.dp
    val pad4 = (4 * dp).toInt()
    val strip = bar.getChildAt(0) as? ViewGroup
    val tabW = uniformTabWidth(strip, dp)
    var visibleTabs = 0
    strip?.let {
        if (it.paddingLeft != pad4 || it.paddingTop != pad4 || it.paddingRight != pad4 || it.paddingBottom != pad4) {
            it.setPadding(pad4, pad4, pad4, pad4)
        }
        if (it.clipChildren || it.clipToPadding) {
            it.clipChildren = false
            it.clipToPadding = false
        }
        for (i in 0 until it.childCount) {
            if (it.getChildAt(i).visibility != View.GONE) visibleTabs++
        }
    }
    runCatching {
        if (tabLayoutGravityField?.getInt(bar) != 0) tabLayoutGravityField?.setInt(bar, 0)
        if (tabLayoutMinWidthField?.getInt(bar) != tabW) tabLayoutMinWidthField?.setInt(bar, tabW)
    }
    val inset = bar.paddingBottom
    val delta = if (inset > 0) inset - (absorbed[bar] ?: 0) else 0
    if (inset > 0) absorbed[bar] = inset
    if (bar.paddingLeft != 0 || bar.paddingTop != 0 || bar.paddingRight != 0 || bar.paddingBottom != 0) {
        bar.setPadding(0, 0, 0, 0)
    }
    val targetW = if (visibleTabs > 0) visibleTabs * tabW + 2 * pad4 else ViewGroup.LayoutParams.WRAP_CONTENT
    val targetH = (64 * dp).toInt()
    val hostW = (bar.parent as? View)?.width?.takeIf { it > 0 } ?: bar.resources.displayMetrics.widthPixels
    val barW = if (targetW > 0) targetW else (bar.width.takeIf { it > 0 } ?: bar.measuredWidth)
    val targetTx = centeredBarTranslationX(hostW, barW, dp, bar.layoutDirection == View.LAYOUT_DIRECTION_RTL)
    if (bar.translationX != targetTx) bar.translationX = targetTx
    (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        val base = originalFloatBottomMargin.getOrPut(bar) { it.bottomMargin }
        val minBottom = base + (12 * dp).toInt() + (absorbed[bar] ?: 0)
        val targetBottom = if (delta != 0) it.bottomMargin + delta else it.bottomMargin.coerceAtLeast(minBottom)
        if (it.width != targetW || it.height != targetH || it.bottomMargin != targetBottom) {
            it.width = targetW
            it.height = targetH
            it.bottomMargin = targetBottom
            when (it) {
                is FrameLayout.LayoutParams -> it.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                is LinearLayout.LayoutParams -> it.gravity = Gravity.CENTER_HORIZONTAL
                is RelativeLayout.LayoutParams -> it.addRule(RelativeLayout.CENTER_HORIZONTAL)
            }
            bar.layoutParams = it
        }
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
private fun refitBarForTab(tab: View) {
    val strip = tab.parent as? ViewGroup ?: run { fit(tab); return }
    for (i in 0 until strip.childCount) {
        val child = strip.getChildAt(i)
        if (child.visibility != View.GONE) fit(child)
    }
    (strip.parent as? ViewGroup)?.let { bar -> if (bar in floated) settle(bar) }
}
private fun restoreBadgeLayouts() {
    badgeLayouts.entries.toList().forEach { (view, state) ->
        state.restore(view)
        if (view !is TextView) restoreNativeBadge?.invoke(view)
    }
    badgeLayouts.clear()
    floatStates.values.forEach { it.restoreBadgeClips() }
    countTabWidths.keys.toList().forEach { tab -> countTabWidths.remove(tab); refitBarForTab(tab) }
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
            if (on("未读实数") && bottomNumericBadge(label, viewType.getInt(view))) {
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
                if (countTabWidths.put(tab, needed) != needed) refitBarForTab(tab)
            } else if (countTabWidths.remove(tab) != null) refitBarForTab(tab)
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
        chain.proceed(if (on("未读实数")) unclampedBadgeArgs(args) else args).also {
            if (view != null) {
                legacyBadgeCount(args)?.let { counts[view] = it } ?: counts.remove(view)
                if (on("未读实数")) expand(view)
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
                if (on("未读实数")) expand(view)
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

// ---- 聊天顶栏：照 NagramXF ActionBar.setupGlass + ChatAvatarContainer 三胶囊布局（左返回、中 42dp 头像+左对齐标题副标题、右操作）。

internal data class PillBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2
    fun isEmpty(): Boolean = left >= right || top >= bottom
    fun toRect(): Rect = Rect(left, top, right, bottom)
}

internal data class NagramHeaderLayout(
    val leftPill: PillBox,
    val centerPill: PillBox,
    val rightPill: PillBox,
    val avatarLeft: Int,
    val avatarTop: Int,
    val avatarSize: Int,
    val textLeft: Int,
    val textRight: Int,
)

/** NagramXF ActionBar.setupGlass(p=6dp, s=46dp, r=23dp) 与 ChatAvatarContainer.getVisualWidth(+64dp/+30dp)。 */
internal fun nagramHeaderLayout(
    width: Int,
    height: Int,
    density: Float,
    unreadWidth: Int,
    rightCount: Int,
    titleWidth: Int,
    subtitleWidth: Int,
    hasAvatar: Boolean = true,
    topOffsetDp: Float = Float.NaN,
): NagramHeaderLayout {
    val p = (6 * density).roundToInt()
    val s = (46 * density).roundToInt()
    val pillTop = if (topOffsetDp.isFinite()) (topOffsetDp * density).roundToInt()
        else ((height - s) / 2).coerceAtLeast(0)
    val pillBottom = pillTop + s
    val leftW = if (unreadWidth > 0) maxOf(s, (36 * density).roundToInt() + unreadWidth + (10 * density).roundToInt()) else s
    val leftPill = PillBox(p, pillTop, (p + leftW).coerceAtMost(width.coerceAtLeast(0)), pillBottom)
    val rightW = when {
        rightCount <= 0 -> 0
        rightCount == 1 -> s
        else -> (12 * density).roundToInt() + rightCount * (38 * density).roundToInt()
    }
    val rightPill = if (rightW > 0) {
        val rRight = (width - p).coerceAtLeast(0)
        val rLeft = (rRight - rightW).coerceAtLeast(leftPill.right + p)
        PillBox(rLeft, pillTop, rRight, pillBottom)
    } else PillBox(width, pillTop, width, pillBottom)
    val availLeft = leftPill.right + p
    val availRight = (if (rightW > 0) rightPill.left else width) - p
    val availW = (availRight - availLeft).coerceAtLeast(0)
    val contentW = maxOf(titleWidth, subtitleWidth).coerceAtLeast(0)
    val extraW = ((if (hasAvatar) 64 else 30) * density).roundToInt()
    val minW = minOf((132 * density).roundToInt(), availW)
    val centerW = (contentW + extraW).coerceIn(minW, availW)
    val centerLeft = ((width - centerW) / 2).coerceIn(availLeft, (availRight - centerW).coerceAtLeast(availLeft))
    val centerPill = PillBox(centerLeft, pillTop, centerLeft + centerW, pillBottom)
    val avatarSize = (42 * density).roundToInt()
    val avatarInset = ((s - avatarSize) / 2).coerceAtLeast(0)
    val textLeft = centerPill.left + ((if (hasAvatar) 50 else 14) * density).roundToInt()
    val textRight = (centerPill.right - (14 * density).roundToInt()).coerceAtLeast(textLeft)
    return NagramHeaderLayout(
        leftPill = leftPill,
        centerPill = centerPill,
        rightPill = rightPill,
        avatarLeft = centerPill.left + avatarInset,
        avatarTop = pillTop + avatarInset,
        avatarSize = avatarSize,
        textLeft = textLeft,
        textRight = textRight,
    )
}

/** 顶栏在「状态栏／摄像头下沿」之外再往下让的 dp（0–48，正数下移）。 */
internal const val SAFE_TOP_SHIFT = "safe_top_shift_dp"
internal fun safeTopShiftDp(): Int = (store()?.getInt(SAFE_TOP_SHIFT, 0) ?: 0).coerceIn(0, 96)

/**
 * 顶栏顶部内边距。[origTop] 是 QQ 原本预留的顶部空间（AIO 为 `getStatusBarHeight()`，
 * 主页为 `MainResourceConfig#n()`，二级页为 `QUISecNavBar` 根内边距）。
 *
 * 状态栏照常显示，顶栏就停在 `max(状态栏高, 摄像头挖孔下沿)` 这条线上 —— 用户要的是
 * 「摄像头下面或者原始位置」，不是顶到 y=0 被挖孔压住；[SAFE_TOP_SHIFT] 只在这条线以下再往下让。
 * 还没量到下沿时退回 QQ 原值，绝不把内容推到屏幕最上沿。
 */
internal fun reducedSafeTop(origTop: Int, dp: Float, offsetDp: Float = Float.NaN, edgePx: Int = -1): Int {
    if (origTop <= 0) return 0
    val shiftDp = if (offsetDp.isNaN()) safeTopShiftDp().toFloat() else offsetDp
    // 量到 0 表示这一帧没有状态栏／挖孔（全屏页），此时按 QQ 原值走，绝不把内容推到 y=0。
    val measured = if (edgePx >= 0) edgePx else topInsetPx
    val edge = if (measured > 0) minOf(measured, origTop) else origTop
    return (edge + shiftDp * dp).roundToInt().coerceIn(0, origTop + (96 * dp).roundToInt())
}

private class HeaderPillsDrawable(private val host: View) : android.graphics.drawable.Drawable() {
    private val sharedScene = android.graphics.RenderNode("qself-header-scene")
    private var sharedSceneReady = false
    private var sceneDraws = 0
    private var sceneRecordDraw = -1
    private val sharedOverride: (Canvas) -> Boolean = { rc ->
        if (sharedSceneReady) {
            rc.drawRenderNode(sharedScene)
            true
        } else false
    }
    private val backGlass = Glass(host, 23 * host.dp, kernelSurface = true, backdropOverride = sharedOverride)
    private val centerGlass = Glass(host, 23 * host.dp, kernelSurface = true, backdropOverride = sharedOverride)
    private val rightGlass = Glass(host, 23 * host.dp, kernelSurface = true, backdropOverride = sharedOverride)
    var leftRect = Rect()
    var centerRect = Rect()
    var rightRect = Rect()
    fun update(layout: NagramHeaderLayout) {
        val nextLeft = layout.leftPill.toRect()
        val nextCenter = layout.centerPill.toRect()
        val nextRight = layout.rightPill.toRect()
        if (leftRect == nextLeft && centerRect == nextCenter && rightRect == nextRight) return
        leftRect = nextLeft; centerRect = nextCenter; rightRect = nextRight
        backGlass.bounds = leftRect; centerGlass.bounds = centerRect; rightGlass.bounds = rightRect
        invalidateSelf()
    }
    fun onTouch(event: MotionEvent, layout: NagramHeaderLayout) {
        val x = event.x; val y = event.y
        val xi = x.toInt(); val yi = y.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                when {
                    !layout.leftPill.isEmpty() && xi in layout.leftPill.left..layout.leftPill.right && yi in layout.leftPill.top..layout.leftPill.bottom -> {
                        backGlass.kernelTouchDown(x - layout.leftPill.left, y - layout.leftPill.top)
                        centerGlass.kernelTouchEnd(); rightGlass.kernelTouchEnd()
                    }
                    !layout.centerPill.isEmpty() && xi in layout.centerPill.left..layout.centerPill.right && yi in layout.centerPill.top..layout.centerPill.bottom -> {
                        centerGlass.kernelTouchDown(x - layout.centerPill.left, y - layout.centerPill.top)
                        backGlass.kernelTouchEnd(); rightGlass.kernelTouchEnd()
                    }
                    !layout.rightPill.isEmpty() && xi in layout.rightPill.left..layout.rightPill.right && yi in layout.rightPill.top..layout.rightPill.bottom -> {
                        rightGlass.kernelTouchDown(x - layout.rightPill.left, y - layout.rightPill.top)
                        backGlass.kernelTouchEnd(); centerGlass.kernelTouchEnd()
                    }
                    else -> {
                        backGlass.kernelTouchEnd(); centerGlass.kernelTouchEnd(); rightGlass.kernelTouchEnd()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                backGlass.kernelTouchEnd(); centerGlass.kernelTouchEnd(); rightGlass.kernelTouchEnd()
            }
        }
    }
    fun sync() {
        backGlass.sync(); centerGlass.sync(); if (!rightRect.isEmpty) rightGlass.sync()
    }
    fun dispose() {
        sharedSceneReady = false
        sharedScene.discardDisplayList()
        backGlass.dispose(); centerGlass.dispose(); rightGlass.dispose()
    }
    override fun draw(canvas: Canvas) {
        val count = (if (!leftRect.isEmpty) 1 else 0) + (if (!centerRect.isEmpty) 1 else 0) + (if (!rightRect.isEmpty) 1 else 0)
        if (count == 0) return
        // 三颗胶囊共用一份底图，但这块底图要重画整个顶栏带（含背后的消息列表）。
        // 每帧重录等于把列表画三遍，这里每两帧录一次：滚动时最多差一帧，肉眼看不出来。
        sceneDraws++
        val hw = canvas.isHardwareAccelerated && host.width > 0 && host.height > 0 && count >= 2 &&
            (sceneDraws - sceneRecordDraw >= 2 || !sharedSceneReady)
        if (hw) {
            val pad = (40 * host.dp).toInt()
            val w = host.width
            val h = host.height
            sharedScene.setPosition(-pad, -pad, w + pad, h + pad)
            val sc = sharedScene.beginRecording(w + 2 * pad, h + 2 * pad)
            try {
                sc.translate(pad.toFloat(), pad.toFloat())
                backGlass.recordBehind(sc, useTightBounds = false)
                sharedSceneReady = true
                sceneRecordDraw = sceneDraws
            } finally {
                sharedScene.endRecording()
            }
        }
        try {
            if (!leftRect.isEmpty) backGlass.draw(canvas)
            if (!centerRect.isEmpty) centerGlass.draw(canvas)
            if (!rightRect.isEmpty) rightGlass.draw(canvas)
        } finally {
            sharedSceneReady = false
        }
    }
    override fun setAlpha(alpha: Int) {
        backGlass.alpha = alpha; centerGlass.alpha = alpha; rightGlass.alpha = alpha
    }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        backGlass.colorFilter = colorFilter; centerGlass.colorFilter = colorFilter; rightGlass.colorFilter = colorFilter
    }
    @Deprecated("Drawable opacity")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private class HeaderState(
    val bar: ViewGroup,
    val root: View?,
    val outer: View?,
    val dividerLine: View?,
    val upMask: View?,
    val leftBtn: View?,
    val unreadBadge: TextView?,
    val titleTv: TextView?,
    val titleTailTv: TextView?,
    val subIcon: View?,
    val right1: View?,
    val right2: View?,
    val right3: () -> View?,
    val centerFlipper: () -> View?,
    val centerSub1: () -> TextView?,
    val centerSub2: () -> TextView?,
    val multiSelectRoot: () -> View?,
) {
    val barBg = bar.background
    val rootBg = root?.background
    val outerBg = outer?.background
    val parentBg = (root?.parent as? View)?.background
    /** 从顶栏一直到 decor 的整条祖先链底色：顶部那块「背景板」就藏在其中某个容器上。 */
    private val ancestorBgs = LinkedHashMap<View, android.graphics.drawable.Drawable?>()
    private var ancestorScanned = false
    var origOuterPad = outer?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    var lastAppliedOuterTop = -1
    var origRootPad = root?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    var lastAppliedRootTop = -1
    val dividerVis = dividerLine?.visibility
    val upMaskVis = upMask?.visibility
    val right1Bg = right1?.background
    val right2Bg = right2?.background
    val origTouchDelegate = bar.touchDelegate
    val titleSize = titleTv?.textSize
    val titleGravity = titleTv?.gravity
    val origTitleColor = titleTv?.currentTextColor
    val origTailColor = titleTailTv?.currentTextColor
    private val savedIconTints = WeakHashMap<android.widget.ImageView, android.content.res.ColorStateList?>()
    private val recordedIconTints = WeakHashMap<android.widget.ImageView, Boolean>()
    private val savedBgTints = WeakHashMap<View, android.content.res.ColorStateList?>()
    private val recordedBgTints = WeakHashMap<View, Boolean>()
    private val savedSubColors = WeakHashMap<TextView, Int>()
    private val collapsedBadges = WeakHashMap<View, Int>()
    var lastGeom: NagramHeaderLayout? = null
    var lastRightViews: List<View> = emptyList()
    private var activeTouchTarget: View? = null
    private var downX = 0f
    private var downY = 0f
    val pills = HeaderPillsDrawable(bar)
    val avatar = android.widget.ImageView(bar.context).apply {
        tag = "qself-aio-avatar"
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        outlineProvider = capsule()
        clipToOutline = true
        setOnClickListener { titleTv?.performClick() }
    }
    val preDraw = ViewTreeObserver.OnPreDrawListener {
        val started = System.nanoTime()
        try {
            headerPreDraw()
        } finally {
            CostMeter.add(COST_HEADER, started)
        }
        true
    }
    private fun headerPreDraw() {
        if (on("玻璃底栏")) {
            val o = outer
            val r = root
            if (bar.background !== pills || o?.background != null || r?.background != null ||
                dividerLine?.visibility == View.VISIBLE || upMask?.visibility == View.VISIBLE ||
                (o != null && ((lastAppliedOuterTop >= 0 && o.paddingTop != lastAppliedOuterTop) || (lastAppliedOuterTop < 0 && o.paddingTop > 0))) ||
                (r != null && ((lastAppliedRootTop >= 0 && r.paddingTop != lastAppliedRootTop) || (lastAppliedRootTop < 0 && r.paddingTop > 0)))) {
                applyStyle()
            } else {
                pills.sync()
            }
        }
    }
    init {
        bar.viewTreeObserver.addOnPreDrawListener(preDraw)
    }
    private fun tintIcons(view: View?, dark: Boolean) {
        if (view == null || view === avatar) return
        val bright = android.content.res.ColorStateList.valueOf(0xFFF2F5FA.toInt())
        if (view is android.widget.ImageView) {
            if (recordedIconTints[view] != true) {
                savedIconTints[view] = view.imageTintList
                recordedIconTints[view] = true
            }
            if (dark) {
                if (view.imageTintList != bright) view.imageTintList = bright
            } else {
                val orig = savedIconTints[view]
                if (view.imageTintList != orig) view.imageTintList = orig
            }
        } else if (view is TextView) {
            // sm1.u#f (0x7f0a2ec2 ivTitleBtnLeft) 是 TextView，返回箭头图标设在其 background 上，不能置空其 background！
            if (recordedBgTints[view] != true) {
                savedBgTints[view] = view.backgroundTintList
                recordedBgTints[view] = true
            }
            if (!savedSubColors.containsKey(view)) savedSubColors[view] = view.currentTextColor
            if (dark) {
                if (view.backgroundTintList != bright) view.backgroundTintList = bright
                view.compoundDrawableTintList = bright
                view.setTextColor(0xFFF2F5FA.toInt())
            } else {
                val orig = savedBgTints[view]
                if (view.backgroundTintList != orig) view.backgroundTintList = orig
                view.compoundDrawableTintList = null
                savedSubColors[view]?.let(view::setTextColor)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) tintIcons(view.getChildAt(i), dark)
        }
    }
    private fun findClickableTarget(view: View?): View? {
        var cur = view
        while (cur != null && cur !== bar) {
            if (cur.hasOnClickListeners() || cur.isClickable) return cur
            cur = cur.parent as? View
        }
        return view
    }
    fun dispatchPillTouch(event: MotionEvent): Boolean {
        val geom = lastGeom ?: return false
        if (bar.visibility != View.VISIBLE) return false
        pills.onTouch(event, geom)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            activeTouchTarget = null
            downX = event.x; downY = event.y
            val x = event.x.toInt()
            val y = event.y.toInt()
            val rawTarget: View? = when {
                !geom.leftPill.isEmpty() && x in geom.leftPill.left..geom.leftPill.right && y in geom.leftPill.top..geom.leftPill.bottom -> {
                    val unread = unreadBadge?.takeIf { it.visibility == View.VISIBLE && (it.hasOnClickListeners() || it.isClickable) }
                    if (unread != null && x >= unread.left) unread
                    else leftBtn?.takeIf { it.visibility == View.VISIBLE }
                }
                !geom.centerPill.isEmpty() && x in geom.centerPill.left..geom.centerPill.right && y in geom.centerPill.top..geom.centerPill.bottom -> {
                    val flip = centerFlipper()?.takeIf { it.visibility == View.VISIBLE && (it.hasOnClickListeners() || it.isClickable) }
                    if (flip != null && y >= flip.top) flip else titleTv?.takeIf { it.visibility == View.VISIBLE }
                }
                !geom.rightPill.isEmpty() && x in geom.rightPill.left..geom.rightPill.right && y in geom.rightPill.top..geom.rightPill.bottom -> {
                    if (lastRightViews.size == 1) lastRightViews[0]
                    else if (lastRightViews.isNotEmpty()) {
                        val slotW = (geom.rightPill.width().toFloat() / lastRightViews.size).coerceAtLeast(1f)
                        val idx = ((x - geom.rightPill.left) / slotW).toInt().coerceIn(0, lastRightViews.lastIndex)
                        lastRightViews[idx]
                    } else null
                }
                else -> null
            }
            activeTouchTarget = findClickableTarget(rawTarget)
        }
        val target = activeTouchTarget ?: return false
        val copy = MotionEvent.obtain(event)
        copy.setLocation(target.width / 2f, target.height / 2f)
        val handled = try { target.dispatchTouchEvent(copy) } finally { copy.recycle() }
        if (action == MotionEvent.ACTION_UP) {
            val slop = android.view.ViewConfiguration.get(bar.context).scaledTouchSlop
            if (!handled && kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop) {
                target.performClick()
            }
            activeTouchTarget = null
        } else if (action == MotionEvent.ACTION_CANCEL) {
            activeTouchTarget = null
        }
        return true
    }
    fun applyStyle() {
        if (!on("玻璃底栏")) { restore(); return }
        if (avatar.parent == null) {
            val size = (42 * bar.dp).roundToInt()
            bar.addView(avatar, RelativeLayout.LayoutParams(size, size))
        }
        avatar.visibility = View.VISIBLE
        if (bar.background !== pills) bar.background = pills
        // 清除 ViewUtils.d 按旧坐标挂载的 TouchDelegate，交由 dispatchPillTouch 精确按胶囊命中转发。
        if (bar.touchDelegate != null) bar.touchDelegate = null
        bar.clipChildren = false; bar.clipToPadding = false
        (root as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
        (outer as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
        root?.background = null
        outer?.background = null
        (root?.parent as? View)?.background = null
        // 顶部背景板：不只顶栏自己，整条祖先链上任何一层的不透明底色都会把顶上那条盖住，
        // 让消息和壁纸透不上来。逐层清空并记住原值，关开关时按原样还回去。
        if (!ancestorScanned) {
            ancestorScanned = true
            var p: View? = bar.parent as? View
            while (p != null && p !is android.widget.FrameLayout) {
                ancestorBgs[p] = p.background
                p = p.parent as? View
            }
        }
        for (v in ancestorBgs.keys) if (v.background != null) v.background = null
        // 动态捕获 AIOTitleVB#Y1 / #A1 延后设置的 statusBarHeight（140px）并按 reducedSafeTop 压缩顶部内边距。
        outer?.let { o ->
            if (o.paddingTop != lastAppliedOuterTop && o.paddingTop > 0) {
                origOuterPad = intArrayOf(o.paddingLeft, o.paddingTop, o.paddingRight, o.paddingBottom)
            }
            val orig = origOuterPad
            if (orig != null && orig[1] > 0) {
                val reducedTop = reducedSafeTop(orig[1], bar.dp)
                if (lastAppliedOuterTop != reducedTop) {
                    lastAppliedOuterTop = reducedTop
                    aioSafeTopRemovedPx = (orig[1] - reducedTop).coerceAtLeast(0)
                    recordQselfEvent("AIO 顶栏 outer 顶部 ${orig[1]}px -> ${reducedTop}px（状态栏/摄像头下沿=${topInsetPx}px，下移=${safeTopShiftDp()}dp）")
                    refreshInputLayout()
                }
                if (o.paddingTop != reducedTop || o.paddingBottom != 0) o.setPadding(orig[0], reducedTop, orig[2], 0)
                // 安全区一去掉，标题栏就被推到 y=0，里面的按钮会落到状态栏底下（真机：状态栏 0~141px，
                // 返回键跑到 y=26）。把去掉的那段补成标题栏自己的内容内边距，玻璃板从 y=0 铺满顶部，
                // 按钮仍留在状态栏以下，两边不相撞。
                compensateTitleSafeTop(bar, aioTopCompensation())
            }
        }
        root?.takeIf { it !== outer }?.let { r ->
            if (r.paddingTop != lastAppliedRootTop && r.paddingTop > 0) {
                origRootPad = intArrayOf(r.paddingLeft, r.paddingTop, r.paddingRight, r.paddingBottom)
            }
            val orig = origRootPad
            if (orig != null && orig[1] > 0) {
                val reducedTop = reducedSafeTop(orig[1], bar.dp)
                if (lastAppliedRootTop != reducedTop) {
                    lastAppliedRootTop = reducedTop
                    recordQselfEvent("AIO 顶栏 root 顶部 ${orig[1]}px -> ${reducedTop}px（下沿=${topInsetPx}px，下移=${safeTopShiftDp()}dp）")
                }
                if (r.paddingTop != reducedTop || r.paddingBottom != 0) r.setPadding(orig[0], reducedTop, orig[2], 0)
            }
        }
        dividerLine?.visibility = View.GONE
        upMask?.visibility = View.GONE
        right1?.background = null
        right2?.background = null
        right3()?.background = null
        val dark = night()
        for (btn in listOf(leftBtn, right1, right2, right3(), bar.findViewById(0x7f0a1630), multiSelectRoot())) tintIcons(btn, dark)
        titleTv?.let {
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17.5f)
            it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            if (dark) it.setTextColor(0xFFF5F7FA.toInt()) else origTitleColor?.let(it::setTextColor)
        }
        titleTailTv?.let {
            if (dark) it.setTextColor(0xFFD5DCE8.toInt()) else origTailColor?.let(it::setTextColor)
        }
        for (sub in listOf(centerSub1(), centerSub2())) sub?.let {
            if (!savedSubColors.containsKey(it)) savedSubColors[it] = it.currentTextColor
            it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            if (dark) it.setTextColor(0xFFB8C2D0.toInt()) else savedSubColors[it]?.let(it::setTextColor)
        }
        bar.requestLayout(); bar.invalidate()
    }
    fun restore() {
        activeTouchTarget = null
        lastGeom = null
        lastRightViews = emptyList()
        lastAppliedOuterTop = -1
        lastAppliedRootTop = -1
        if (avatar.parent === bar) bar.removeView(avatar)
        if (bar.background === pills) bar.background = barBg
        bar.touchDelegate = origTouchDelegate
        root?.background = rootBg
        outer?.background = outerBg
        (root?.parent as? View)?.background = parentBg
        for ((v, bg) in ancestorBgs) v.background = bg
        ancestorBgs.clear()
        ancestorScanned = false
        outer?.let { o -> origOuterPad?.let { o.setPadding(it[0], it[1], it[2], it[3]) } }
        root?.takeIf { it !== outer }?.let { r -> origRootPad?.let { r.setPadding(it[0], it[1], it[2], it[3]) } }
        if (dividerLine != null && dividerVis != null) dividerLine.visibility = dividerVis
        if (upMask != null && upMaskVis != null) upMask.visibility = upMaskVis
        right1?.background = right1Bg
        right2?.background = right2Bg
        for ((iv, _) in recordedIconTints) {
            iv.imageTintList = savedIconTints[iv]
        }
        savedIconTints.clear(); recordedIconTints.clear()
        for ((v, _) in recordedBgTints) {
            v.backgroundTintList = savedBgTints[v]
            if (v is TextView) v.compoundDrawableTintList = null
        }
        savedBgTints.clear(); recordedBgTints.clear()
        for ((v, vis) in collapsedBadges) v.visibility = vis
        collapsedBadges.clear()
        if (titleTv != null && titleSize != null && titleGravity != null) {
            titleTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize)
            titleTv.gravity = titleGravity
            origTitleColor?.let(titleTv::setTextColor)
        }
        if (titleTailTv != null && origTailColor != null) titleTailTv.setTextColor(origTailColor)
        for ((sub, col) in savedSubColors) sub.setTextColor(col)
        savedSubColors.clear()
        bar.requestLayout(); bar.invalidate()
    }
    fun collapseBadge(v: View, hide: Boolean) {
        if (hide) {
            collapsedBadges.putIfAbsent(v, v.visibility)
            if (v.visibility != View.GONE) v.visibility = View.GONE
        } else {
            collapsedBadges.remove(v)?.let { if (v.visibility != it) v.visibility = it }
        }
    }
    fun dispose() {
        if (bar.viewTreeObserver.isAlive) bar.viewTreeObserver.removeOnPreDrawListener(preDraw)
        restore()
        pills.dispose()
    }
}

private val aioHeaders = WeakHashMap<ViewGroup, HeaderState>()
private val topBlurs = WeakHashMap<View, Int>()
private val topMasks = WeakHashMap<View, Pair<Int, android.graphics.drawable.Drawable?>>()

private fun refreshAioHeaders() {
    aioHeaders.values.toList().forEach { it.applyStyle() }
    topBlurs.entries.toList().forEach { (view, vis) ->
        view.visibility = if (on("玻璃底栏")) View.GONE else vis
        view.alpha = if (on("玻璃底栏")) 0f else 1f
    }
    topMasks.entries.toList().forEach { (view, orig) ->
        if (on("玻璃底栏")) {
            view.visibility = View.GONE
            view.alpha = 0f
            view.background = null
        } else {
            view.visibility = orig.first
            view.alpha = 1f
            if (view.background == null && orig.second != null) view.background = orig.second
        }
    }
}

private fun resetAioHeaders() {
    aioHeaderBottomPx = 0
    aioHeaders.values.toList().forEach { it.dispose() }
    aioHeaders.clear()
    topBlurs.entries.toList().forEach { (view, vis) -> view.visibility = vis; view.alpha = 1f }
    topBlurs.clear()
    topMasks.entries.toList().forEach { (view, orig) ->
        view.visibility = orig.first
        view.alpha = 1f
        if (orig.second != null) view.background = orig.second
    }
    topMasks.clear()
}

private fun aioHeader() {
    // 真包 AIOUtil#b(a) / #A(a) 在未设置自定义聊天背景时返回 false，驱动 AIOTitleVM->UpdateVasTheme(false) 设 #25272d 顶栏安全区底色与分割线、
    // 驱动 AIOMsgListVB#K3/#L3 将 M2() paddingTop 设为 238px 硬裁切消息列表；开启玻璃底栏或输入栏药丸时统一返回 true 切入全屏透明叠层模式。
    runCatching {
        val aioUtil = cls("com.tencent.mobileqq.aio.utils.AIOUtil")
        val aioCtx = cls("com.tencent.aio.api.runtime.a")
        for (mName in listOf("b", "A")) {
            aioUtil.declaredMethods.firstOrNull { it.name == mName && it.parameterTypes.size == 1 && it.parameterTypes[0] == aioCtx }?.let { m ->
                hook(m.apply { isAccessible = true }, gated = false) { chain ->
                    val orig = chain.proceed()
                    if (on("玻璃底栏") || on("输入栏药丸")) true else orig
                }
            }
        }
        for (mName in listOf("C", "D")) {
            aioUtil.declaredMethods.firstOrNull { it.name == mName && it.parameterTypes.size == 2 && it.parameterTypes[0] == View::class.java }?.let { m ->
                hook(m.apply { isAccessible = true }, gated = false) { chain ->
                    val v = chain.args.getOrNull(0) as? View
                    if (v != null && on("玻璃底栏") && (v in aioHeaders || aioHeaders.values.any { it.root === v || it.outer === v })) {
                        return@hook null
                    }
                    chain.proceed()
                }
            }
        }
    }
    val titleVB = cls("com.tencent.mobileqq.aio.title.AIOTitleVB")
    val bindingField = titleVB.getDeclaredField("d").apply { isAccessible = true }
    val multiSelectField = titleVB.getDeclaredField("e").apply { isAccessible = true }
    val right3Field = titleVB.getDeclaredField("h").apply { isAccessible = true }
    val centerField = titleVB.getDeclaredField("i").apply { isAccessible = true }
    val bindingClass = cls("sm1.u")
    val uRoot = bindingClass.getDeclaredField("a").apply { isAccessible = true }
    val uDivider = bindingClass.getDeclaredField("d").apply { isAccessible = true }
    val uLeft = bindingClass.getDeclaredField("f").apply { isAccessible = true }
    val uRight1 = bindingClass.getDeclaredField("h").apply { isAccessible = true }
    val uRight2 = bindingClass.getDeclaredField("i").apply { isAccessible = true }
    val uSubIcon = bindingClass.getDeclaredField("j").apply { isAccessible = true }
    val uOuter = bindingClass.getDeclaredField("k").apply { isAccessible = true }
    val uBar = bindingClass.getDeclaredField("l").apply { isAccessible = true }
    val uTail = bindingClass.getDeclaredField("n").apply { isAccessible = true }
    val uTitle = bindingClass.getDeclaredField("o").apply { isAccessible = true }
    val uUnread = bindingClass.getDeclaredField("p").apply { isAccessible = true }
    val uUpMask = bindingClass.getDeclaredField("q").apply { isAccessible = true }
    val vClass = cls("sm1.v")
    val vRoot = vClass.getDeclaredMethod("f").apply { isAccessible = true }
    val wClass = cls("sm1.w")
    val wRight3 = wClass.getDeclaredField("b").apply { isAccessible = true }
    val centerVB = cls("com.tencent.mobileqq.aio.title.center.CenterVB")
    val centerFlipper = centerVB.getDeclaredMethod("B1").apply { isAccessible = true }
    val centerSub1 = centerVB.getDeclaredMethod("C1").apply { isAccessible = true }
    val centerSub2 = centerVB.getDeclaredMethod("D1").apply { isAccessible = true }
    val frameworkVB = cls("com.tencent.mvi.mvvm.framework.FrameworkVB")
    val mviCtxField = frameworkVB.getDeclaredField("mMviContext").apply { isAccessible = true }
    val aioCtxClass = cls("com.tencent.aio.api.runtime.a")
    val getParam = aioCtxClass.getDeclaredMethod("g")
    val paramSession = cls("com.tencent.aio.data.AIOParam").getDeclaredField("d").apply { isAccessible = true }
    val sessionContact = cls("com.tencent.aio.data.AIOSession").getDeclaredField("d").apply { isAccessible = true }
    val contactClass = cls("com.tencent.aio.data.AIOContact")
    val contactType = contactClass.getDeclaredField("d").apply { isAccessible = true }
    val contactUid = contactClass.getDeclaredField("e").apply { isAccessible = true }
    val contactNick = contactClass.getDeclaredField("h").apply { isAccessible = true }
    val avatarApi = cls("com.tencent.qqnt.avatar.a")
    val avatarCompanion = avatarApi.getDeclaredField("a").get(null)
    val avatarWithCtx = cls("com.tencent.qqnt.avatar.a\$a").getDeclaredMethod("b", android.content.Context::class.java)
    val avatarWithView = cls("com.tencent.qqnt.avatar.f").getDeclaredMethod("b", View::class.java)
    val avatarOptBuilder = cls("com.tencent.qqnt.avatar.b\$a").getDeclaredConstructor()
    val avatarOptBuild = cls("com.tencent.qqnt.avatar.b\$a").getDeclaredMethod("a")
    val avatarLoaderClass = cls("com.tencent.qqnt.avatar.e")
    val avatarApplyOpt = avatarLoaderClass.getDeclaredMethod("e", cls("com.tencent.qqnt.avatar.b"))
    val avatarDefaults = cls("com.tencent.qqnt.avatar.e\$a")
    val scopeClass = cls("kotlinx.coroutines.CoroutineScope")
    val avatarLoadGroup = avatarDefaults.getDeclaredMethod("a", avatarLoaderClass, java.lang.Long.TYPE, scopeClass, Integer.TYPE, Any::class.java)
    val avatarLoadC2C = avatarDefaults.getDeclaredMethod("b", avatarLoaderClass, String::class.java, java.lang.Long.TYPE, scopeClass, Integer.TYPE, Any::class.java)

    fun loadHeaderAvatar(vb: Any, avatarView: android.widget.ImageView) {
        runCatching {
            val ctx = mviCtxField.get(vb) ?: return
            if (!aioCtxClass.isInstance(ctx)) return
            val param = getParam.invoke(ctx) ?: return
            val session = paramSession.get(param) ?: return
            val contact = sessionContact.get(session) ?: return
            val chatType = contactType.getInt(contact)
            val peerUid = contactUid.get(contact) as? String ?: ""
            val nick = contactNick.get(contact) as? String ?: ""
            if (avatarView.drawable == null && nick.isNotEmpty()) {
                val size = (42 * avatarView.dp).roundToInt().coerceAtLeast(1)
                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val palette = intArrayOf(0xFFE56555.toInt(), 0xFFF28C48.toInt(), 0xFF8E85EE.toInt(), 0xFF76C84D.toInt(), 0xFF5Fbed5.toInt(), 0xFF549CDD.toInt(), 0xFFF2749A.toInt())
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[(peerUid.hashCode() and 0x7FFFFFFF) % palette.size] }
                c.drawCircle(size / 2f, size / 2f, size / 2f, p)
                p.color = -1; p.textAlign = Paint.Align.CENTER; p.textSize = size * 0.44f; p.isFakeBoldText = true
                val fm = p.fontMetrics
                c.drawText(nick.take(1), size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
                avatarView.setImageBitmap(bmp)
            }
            val opt = avatarOptBuild.invoke(avatarOptBuilder.newInstance())
            val req = avatarWithView.invoke(avatarWithCtx.invoke(avatarCompanion, avatarView.context), avatarView)
            val loader = avatarApplyOpt.invoke(req, opt)
            val groupCode = peerUid.toLongOrNull()
            if (chatType == 2 && groupCode != null) avatarLoadGroup.invoke(null, loader, groupCode, null, 2, null)
            else if (peerUid.isNotEmpty()) avatarLoadC2C.invoke(null, loader, peerUid, 0L, null, 4, null)
        }
    }

    fun bindHeader(vb: Any) {
        val u = runCatching { bindingField.get(vb) }.getOrNull() ?: return
        val bar = runCatching { uBar.get(u) as? ViewGroup }.getOrNull() ?: return
        val state = aioHeaders.getOrPut(bar) {
            HeaderState(
                bar = bar,
                root = runCatching { uRoot.get(u) as? View }.getOrNull(),
                outer = runCatching { uOuter.get(u) as? View }.getOrNull(),
                dividerLine = runCatching { uDivider.get(u) as? View }.getOrNull(),
                upMask = runCatching { uUpMask.get(u) as? View }.getOrNull(),
                leftBtn = runCatching { uLeft.get(u) as? View }.getOrNull(),
                unreadBadge = runCatching { uUnread.get(u) as? TextView }.getOrNull(),
                titleTv = runCatching { uTitle.get(u) as? TextView }.getOrNull(),
                titleTailTv = runCatching { uTail.get(u) as? TextView }.getOrNull(),
                subIcon = runCatching { uSubIcon.get(u) as? View }.getOrNull(),
                right1 = runCatching { uRight1.get(u) as? View }.getOrNull(),
                right2 = runCatching { uRight2.get(u) as? View }.getOrNull(),
                right3 = { runCatching { right3Field.get(vb)?.let { wRight3.get(it) as? View } }.getOrNull() },
                centerFlipper = { runCatching { centerField.get(vb)?.let { centerFlipper.invoke(it) as? View } }.getOrNull() },
                centerSub1 = { runCatching { centerField.get(vb)?.let { centerSub1.invoke(it) as? TextView } }.getOrNull() },
                centerSub2 = { runCatching { centerField.get(vb)?.let { centerSub2.invoke(it) as? TextView } }.getOrNull() },
                multiSelectRoot = { runCatching { multiSelectField.get(vb)?.let { vRoot.invoke(it) as? View } }.getOrNull() },
            )
        }
        state.applyStyle()
        if (on("玻璃底栏")) loadHeaderAvatar(vb, state.avatar)
        captureViewRootSnapshot(bar, "AIO-Header:${bar.context.javaClass.simpleName}")
    }

    for (name in listOf("onCreateView", "bindViewAndData", "handleUIState", "H1", "I1", "w1", "P1", "X1", "Z1", "Y1", "z1", "A1", "D1", "C1", "R1", "S1", "W1", "h1", "l1")) {
        val m = titleVB.declaredMethods.firstOrNull { it.name == name } ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val target = chain.thisObject?.takeIf(titleVB::isInstance) ?: chain.args.firstOrNull(titleVB::isInstance)
                if (target != null) bindHeader(target)
            }
        }
    }
    val titleContainerVB = cls("com.tencent.mobileqq.aio.title.AIODefaultTitleContainerVB")
    val titleContainerField = titleContainerVB.getDeclaredField("d").apply { isAccessible = true }
    for (name in listOf("onCreateView", "handleUIState")) {
        val m = titleContainerVB.declaredMethods.firstOrNull { it.name == name } ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                if (titleContainerVB.isInstance(chain.thisObject) && on("玻璃底栏")) {
                    (runCatching { titleContainerField.get(chain.thisObject) as? View }.getOrNull())?.background = null
                }
            }
        }
    }
    for (immersiveName in listOf(
        "com.tencent.widget.immersive.ImmersiveTitleBar2",
        "com.tencent.mobileqq.qqui.widget.ImmersiveTitleBar",
        "com.tencent.qqnt.base.widget.NTImmersiveTitleBar",
    )) {
        val immCls = cls(immersiveName)
        for (mName in listOf("a", "initUI", "resetBkColor", "onMeasure")) {
            immCls.declaredMethods.filter { it.name == mName }.forEach { m ->
                hook(m.apply { isAccessible = true }, gated = false) { chain ->
                    chain.proceed().also {
                        if (on("玻璃底栏") && chain.thisObject is View) {
                            val v = chain.thisObject as View
                            v.background = null
                            v.alpha = 0f
                        }
                    }
                }
            }
        }
    }
    hook(centerVB.getDeclaredMethod("handleUIState", cls("com.tencent.mvi.base.mvi.MviUIState")), gated = false) { chain ->
        chain.proceed().also {
            aioHeaders.values.forEach { state ->
                if (on("玻璃底栏")) { state.applyStyle(); state.bar.requestLayout() }
            }
        }
    }

    val titleLayout = cls("com.tencent.mobileqq.aio.widget.AIOTitleRelativeLayout")
    val setMeasured = View::class.java.getDeclaredMethod("setMeasuredDimension", Integer.TYPE, Integer.TYPE).apply { isAccessible = true }
    hook(titleLayout.getDeclaredMethod("onMeasure", Integer.TYPE, Integer.TYPE), gated = false) { chain ->
        chain.proceed().also {
            val bar = chain.thisObject as? ViewGroup ?: return@also
            if (!on("玻璃底栏") || bar !in aioHeaders) return@also
            // 标题栏高度 = 50dp + 补进去的安全区高度，不然补的内边距会把按钮挤出可视区。
            val minH = (50 * bar.dp).roundToInt() + aioTopCompensation()
            if (bar.measuredHeight in 1 until minH) setMeasured.invoke(bar, bar.measuredWidthAndState, minH)
        }
    }
    hook(titleLayout.getDeclaredMethod("onLayout", java.lang.Boolean.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE), gated = false) { chain ->
        chain.proceed().also {
            val bar = chain.thisObject as? ViewGroup ?: return@also
            val state = aioHeaders[bar] ?: return@also
            if (!on("玻璃底栏")) { aioHeaderBottomPx = 0; return@also }
            val w = bar.width; val h = bar.height
            if (w <= 0 || h <= 0) return@also
            // 顶栏的底边（窗口坐标）= 标题栏当前位置 + 自身高度。状态栏藏掉后它就是 99px，
            // 消息列表顶部内边距与「从 y=0 铺下来」的上移量都按这个数算。
            val where = IntArray(2)
            bar.getLocationInWindow(where)
            val headerBottom = where[1] + h
            if (aioHeaderBottomPx != headerBottom) {
                aioHeaderBottomPx = headerBottom
                recordQselfEvent("AIO 顶栏底边 ${headerBottom}px（标题栏 y=${where[1]} 高=$h），消息区从 y=0 铺下来")
                refreshInputLayout()
            }
            // 顶栏真正完成布局后抓一次带真实尺寸的 AIO 视图树（此前只在预加载阶段抓到 0×0 的空树）。
            aioLayoutSnapshot(bar)
            val dp = bar.dp
            if (bar.touchDelegate != null) bar.touchDelegate = null
            // 清理或收纳标题栏内动态 inflate 的铭牌/火花/VIP 装饰，保留功能性的听筒图标（0x7f0a1630）。
            for (id in intArrayOf(0x7f0a7836, 0x7f0a7831, 0x7f0a0d74, 0x7f0a0d75)) {
                bar.findViewById<View>(id)?.let { state.collapseBadge(it, on("侧栏精简")) }
            }
            val earIcon = bar.findViewById<View>(0x7f0a1630)?.takeIf { it.visibility == View.VISIBLE && it.measuredWidth > 0 }
            val earW = if (earIcon != null) earIcon.measuredWidth + (4 * dp).roundToInt() else 0
            val unread = state.unreadBadge?.takeIf { it.visibility == View.VISIBLE && !it.text.isNullOrEmpty() }
            val unreadW = unread?.measuredWidth ?: 0
            val rightViews = listOfNotNull(state.right3(), state.right2, state.right1).filter { it.visibility == View.VISIBLE && it.measuredWidth > 0 }
            val tail = state.titleTailTv?.takeIf { it.visibility == View.VISIBLE && !it.text.isNullOrEmpty() }
            val flipper = state.centerFlipper()?.takeIf { it.visibility == View.VISIBLE }
            val sub1 = state.centerSub1()
            val sub2 = state.centerSub2()
            val subText = listOfNotNull(sub1, sub2).firstOrNull { it.visibility == View.VISIBLE && !it.text.isNullOrEmpty() }
            val hasSub = flipper != null && subText != null
            val subIcon = state.subIcon?.takeIf { hasSub && it.visibility == View.VISIBLE }
            val subW = if (hasSub) ((subText.paint?.measureText(subText.text?.toString().orEmpty())?.toInt()) ?: flipper.measuredWidth) +
                (if (subIcon != null) subIcon.measuredWidth + (4 * dp).roundToInt() else 0) else 0
            val multiRoot = state.multiSelectRoot()
            val knownChildren = setOfNotNull(
                state.avatar, state.leftBtn, state.unreadBadge, state.titleTv, state.titleTailTv,
                earIcon, state.subIcon, flipper, sub1, sub2, state.right1, state.right2, state.right3(), multiRoot
            )
            if (multiRoot?.visibility != View.VISIBLE) {
                for (i in 0 until bar.childCount) {
                    val ch = bar.getChildAt(i)
                    if (ch !in knownChildren && !knownChildren.any { k -> ((k as? ViewGroup)?.indexOfChild(ch) ?: -1) >= 0 }) {
                        state.collapseBadge(ch, true)
                    }
                }
            }
            val titleTextW = ((state.titleTv?.paint?.measureText(state.titleTv.text?.toString().orEmpty())?.toInt()) ?: (state.titleTv?.measuredWidth ?: 0)) +
                (if (tail != null) tail.measuredWidth + (2 * dp).roundToInt() else 0) + earW
            val geom = nagramHeaderLayout(w, h, dp, unreadW, rightViews.size, titleTextW, subW, hasAvatar = true)
            state.lastGeom = geom
            state.lastRightViews = rightViews
            state.pills.update(geom)

            // 1. 左返回胶囊：返回箭头（sm1.u#f 0x7f0a2ec2） + 未读气泡
            val backBtn = state.leftBtn?.takeIf { it.visibility == View.VISIBLE }
            if (backBtn != null) {
                val bw = backBtn.measuredWidth.coerceAtLeast((24 * dp).roundToInt())
                val bh = backBtn.measuredHeight.coerceAtLeast((24 * dp).roundToInt())
                val bx = if (unread != null) geom.leftPill.left + (8 * dp).roundToInt() else geom.leftPill.centerX() - bw / 2
                val by = geom.leftPill.centerY() - bh / 2
                backBtn.layout(bx, by, bx + bw, by + bh)
            }
            if (unread != null) {
                val uw = unread.measuredWidth; val uh = unread.measuredHeight
                val ux = (geom.leftPill.right - (10 * dp).roundToInt() - uw).coerceAtLeast(geom.leftPill.left + (28 * dp).roundToInt())
                val uy = geom.leftPill.centerY() - uh / 2
                unread.layout(ux, uy, ux + uw, uy + uh)
            }

            // 2. 中间 ChatAvatarContainer 胶囊：42dp 圆形头像 + 左对齐标题与副标题（先按目标宽度重测 TextView 再 layout，使内部 Layout 与自绘胶囊一致）
            val avSpec = View.MeasureSpec.makeMeasureSpec(geom.avatarSize, View.MeasureSpec.EXACTLY)
            state.avatar.measure(avSpec, avSpec)
            state.avatar.layout(geom.avatarLeft, geom.avatarTop, geom.avatarLeft + geom.avatarSize, geom.avatarTop + geom.avatarSize)
            val tailW = tail?.measuredWidth ?: 0
            val maxTitleW = (geom.textRight - geom.textLeft - tailW - earW).coerceAtLeast(0)
            state.titleTv?.let { tv ->
                val rawTitleW = (titleTextW - tailW - earW + (2 * dp).roundToInt()).coerceAtLeast(0)
                val tw = minOf(maxOf(tv.measuredWidth, rawTitleW), maxTitleW)
                val th = tv.measuredHeight.coerceAtLeast((22 * dp).roundToInt())
                tv.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                tv.measure(
                    View.MeasureSpec.makeMeasureSpec(tw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(th, View.MeasureSpec.EXACTLY),
                )
                val ty = if (hasSub) geom.centerPill.top + (4 * dp).roundToInt() else geom.centerPill.centerY() - th / 2
                tv.layout(geom.textLeft, ty, geom.textLeft + tw, ty + th)
                var curRight = geom.textLeft + tw
                tail?.let { tl ->
                    val tlh = tl.measuredHeight.coerceAtLeast((18 * dp).roundToInt())
                    tl.measure(
                        View.MeasureSpec.makeMeasureSpec(tailW, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(tlh, View.MeasureSpec.EXACTLY),
                    )
                    val tly = ty + (th - tlh) / 2
                    val gap = (2 * dp).roundToInt()
                    tl.layout(curRight + gap, tly, curRight + gap + tailW, tly + tlh)
                    curRight += gap + tailW
                }
                earIcon?.let { ear ->
                    val ew = ear.measuredWidth; val eh = ear.measuredHeight
                    val ey = ty + (th - eh) / 2
                    val gap = (4 * dp).roundToInt()
                    ear.layout(curRight + gap, ey, curRight + gap + ew, ey + eh)
                }
            }
            if (hasSub) {
                var sx = geom.textLeft
                val sy = geom.centerPill.top + (24 * dp).roundToInt()
                val sh = (18 * dp).roundToInt()
                subIcon?.let { ic ->
                    val iw = ic.measuredWidth; val ih = ic.measuredHeight
                    val iy = sy + (sh - ih) / 2
                    ic.layout(sx, iy, sx + iw, iy + ih)
                    sx += iw + (4 * dp).roundToInt()
                }
                val fw = (geom.textRight - sx).coerceAtLeast(0)
                for (sub in listOfNotNull(sub1, sub2)) {
                    sub.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    (sub.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    sub.measure(
                        View.MeasureSpec.makeMeasureSpec(fw, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.EXACTLY),
                    )
                }
                flipper.measure(
                    View.MeasureSpec.makeMeasureSpec(fw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.EXACTLY),
                )
                flipper.layout(sx, sy, sx + fw, sy + sh)
                for (sub in listOfNotNull(sub1, sub2)) sub.layout(0, 0, fw, sh)
            }

            // 3. 右侧操作胶囊
            if (rightViews.size == 1) {
                val rv = rightViews[0]
                val rw = rv.measuredWidth; val rh = rv.measuredHeight
                val rx = geom.rightPill.centerX() - rw / 2; val ry = geom.rightPill.centerY() - rh / 2
                rv.layout(rx, ry, rx + rw, ry + rh)
            } else if (rightViews.size > 1) {
                val slotW = (38 * dp).roundToInt()
                var slotLeft = geom.rightPill.left + (6 * dp).roundToInt()
                for (rv in rightViews) {
                    val rw = rv.measuredWidth; val rh = rv.measuredHeight
                    val rx = slotLeft + (slotW - rw) / 2; val ry = geom.rightPill.centerY() - rh / 2
                    rv.layout(rx, ry, rx + rw, ry + rh)
                    slotLeft += slotW
                }
            }
        }
    }

    val topBlur = cls("com.tencent.mobileqq.aio.reserve1.blur.f")
    val topBlurBindingField = topBlur.getDeclaredField("d").apply { isAccessible = true }
    val topBlurViewField = cls("sm1.t").getDeclaredField("a").apply { isAccessible = true }
    fun syncTopBlur(vb: Any) {
        val binding = runCatching { topBlurBindingField.get(vb) }.getOrNull() ?: return
        val blur = runCatching { topBlurViewField.get(binding) as? View }.getOrNull() ?: return
        topBlurs.putIfAbsent(blur, blur.visibility)
        if (on("玻璃底栏")) { blur.visibility = View.GONE; blur.alpha = 0f; blur.background = null }
        else topBlurs[blur]?.let { blur.visibility = it; blur.alpha = 1f }
    }
    for (name in listOf("onCreateView", "bindViewAndData", "handleUIState", "d1", "f1", "g1")) {
        val m = topBlur.declaredMethods.firstOrNull { it.name == name } ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val target = chain.thisObject?.takeIf(topBlur::isInstance) ?: chain.args.firstOrNull(topBlur::isInstance)
                if (target != null) syncTopBlur(target)
            }
        }
    }

    val topMask = cls("com.tencent.mobileqq.aio.reserve1.mask.b")
    val topMaskBindingField = topMask.getDeclaredField("d").apply { isAccessible = true }
    val topMaskViewField = cls("sm1.y").getDeclaredField("a").apply { isAccessible = true }
    fun syncTopMask(vb: Any) {
        val binding = runCatching { topMaskBindingField.get(vb) }.getOrNull() ?: return
        val mask = runCatching { topMaskViewField.get(binding) as? View }.getOrNull() ?: return
        topMasks.putIfAbsent(mask, mask.visibility to mask.background)
        if (on("玻璃底栏")) {
            mask.visibility = View.GONE
            mask.alpha = 0f
            mask.background = null
        } else {
            topMasks[mask]?.let {
                mask.visibility = it.first
                mask.alpha = 1f
                if (mask.background == null && it.second != null) mask.background = it.second
            }
        }
    }
    for (name in listOf("onCreateView", "bindViewAndData", "handleUIState", "e1", "f1", "g1")) {
        val m = topMask.declaredMethods.firstOrNull { it.name == name } ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val target = chain.thisObject?.takeIf(topMask::isInstance) ?: chain.args.firstOrNull(topMask::isInstance)
                if (target != null) syncTopMask(target)
            }
        }
    }
}

// ---- 二级页顶栏（QUISecNavBar：聊天设置、聊天记录、转发、通用设置）与搜索栏（QUISearchBar）：延续玻璃胶囊与夜间提亮。

private class SecNavBarState(val nav: ViewGroup) : ViewTreeObserver.OnPreDrawListener, View.OnLayoutChangeListener {
    private val getBlur = runCatching { nav.javaClass.getDeclaredMethod("k").apply { isAccessible = true } }.getOrNull()
    private val getBorder = runCatching { nav.javaClass.getDeclaredMethod("l").apply { isAccessible = true } }.getOrNull()
    private val getCenter = runCatching { nav.javaClass.getDeclaredMethod("n").apply { isAccessible = true } }.getOrNull()
    private val getLeftIcon = runCatching { nav.javaClass.getDeclaredMethod("p").apply { isAccessible = true } }.getOrNull()
    private val getLeftText = runCatching { nav.javaClass.getDeclaredMethod("s").apply { isAccessible = true } }.getOrNull()
    private val getRightLayout = runCatching { nav.javaClass.getDeclaredMethod("t").apply { isAccessible = true } }.getOrNull()
    private val getRightText = runCatching { nav.javaClass.getDeclaredMethod("w").apply { isAccessible = true } }.getOrNull()
    private val getRoot = runCatching { nav.javaClass.getDeclaredMethod("x").apply { isAccessible = true } }.getOrNull()
    private val getTitleBg = runCatching { nav.javaClass.getDeclaredMethod("y").apply { isAccessible = true } }.getOrNull()
    private val getTitleRoot = runCatching { nav.javaClass.getDeclaredMethod("z").apply { isAccessible = true } }.getOrNull()
    val titleRoot: ViewGroup = (runCatching { getTitleRoot?.invoke(nav) as? ViewGroup }.getOrNull()) ?: nav
    private val rootView: ViewGroup? = (runCatching { getRoot?.invoke(nav) as? ViewGroup }.getOrNull())
        ?: (titleRoot.parent as? ViewGroup)?.takeIf { it !== nav }
    private val origNavBg = nav.background
    private val origParentBg = (nav.parent as? View)?.background
    private var appliedParentBg: android.graphics.drawable.Drawable? = null
    private val origTitleRootBg = titleRoot.background
    private val origNavPad = intArrayOf(nav.paddingLeft, nav.paddingTop, nav.paddingRight, nav.paddingBottom)
    private var origRootPad = rootView?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    private var lastAppliedRootTop = -1
    private val origTitleRootPad = intArrayOf(titleRoot.paddingLeft, titleRoot.paddingTop, titleRoot.paddingRight, titleRoot.paddingBottom)
    private val origTitleRootHeight = titleRoot.layoutParams?.height
    private val origNavClip = nav.clipChildren to nav.clipToPadding
    private val origRootClip = rootView?.let { it.clipChildren to it.clipToPadding }
    private val origTitleRootClip = titleRoot.clipChildren to titleRoot.clipToPadding
    private val titleBgView = runCatching { getTitleBg?.invoke(nav) as? View }.getOrNull()
    private val blurView = runCatching { getBlur?.invoke(nav) as? View }.getOrNull()
    private val borderView = runCatching { getBorder?.invoke(nav) as? View }.getOrNull()
    private val origTitleBgVis = titleBgView?.visibility
    private val origBlurVis = blurView?.visibility
    private val origBorderVis = borderView?.visibility
    private val savedIconTints = WeakHashMap<android.widget.ImageView, android.content.res.ColorStateList?>()
    private val recordedIconTints = WeakHashMap<android.widget.ImageView, Boolean>()
    private val savedIconBgs = WeakHashMap<View, android.graphics.drawable.Drawable?>()
    private val recordedIconBgs = WeakHashMap<View, Boolean>()
    private val savedTextColors = WeakHashMap<TextView, Int>()
    val pills = HeaderPillsDrawable(titleRoot)
    var lastGeom: NagramHeaderLayout? = null
    private var activeTarget: View? = null
    init {
        titleRoot.viewTreeObserver.addOnPreDrawListener(this)
        titleRoot.addOnLayoutChangeListener(this)
    }
    private fun tintTree(v: View?, dark: Boolean) {
        if (v == null) return
        val bright = android.content.res.ColorStateList.valueOf(0xFFF2F5FA.toInt())
        if (v is android.widget.ImageView) {
            if (recordedIconTints[v] != true) {
                savedIconTints[v] = v.imageTintList
                recordedIconTints[v] = true
            }
            if (recordedIconBgs[v] != true) {
                savedIconBgs[v] = v.background
                recordedIconBgs[v] = true
            }
            if (v.background != null) v.background = null
            if (dark) {
                if (v.imageTintList != bright) v.imageTintList = bright
            } else {
                val orig = savedIconTints[v]
                if (v.imageTintList != orig) v.imageTintList = orig
            }
        } else if (v is TextView) {
            if (!savedTextColors.containsKey(v)) savedTextColors[v] = v.currentTextColor
            if (dark) v.setTextColor(0xFFF5F7FA.toInt()) else savedTextColors[v]?.let(v::setTextColor)
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) tintTree(v.getChildAt(i), dark)
        }
    }
    private fun updateGeometry() {
        val w = titleRoot.width; val h = titleRoot.height
        if (w <= 0 || h <= 0) return
        val dp = titleRoot.dp
        val leftIcon = runCatching { getLeftIcon?.invoke(nav) as? View }.getOrNull()?.takeIf { it.visibility == View.VISIBLE }
        val leftText = runCatching { getLeftText?.invoke(nav) as? View }.getOrNull()?.takeIf { it.visibility == View.VISIBLE }
        val centerTv = runCatching { getCenter?.invoke(nav) as? TextView }.getOrNull()
        val titleW = (centerTv?.paint?.measureText(centerTv.text?.toString().orEmpty())?.toInt())
            ?: (centerTv?.measuredWidth ?: (80 * dp).roundToInt())
        val rightLayout = runCatching { getRightLayout?.invoke(nav) as? ViewGroup }.getOrNull()
        val rightTv = runCatching { getRightText?.invoke(nav) as? TextView }.getOrNull()
        val rightCount = (if (rightLayout != null && rightLayout.visibility == View.VISIBLE) {
            (0 until rightLayout.childCount).count { rightLayout.getChildAt(it).visibility == View.VISIBLE }
        } else 0) + (if (rightTv != null && rightTv.visibility == View.VISIBLE && !rightTv.text.isNullOrEmpty()) 1 else 0)
        val geom = nagramHeaderLayout(w, h, dp, 0, rightCount, titleW, 0, hasAvatar = false)
        lastGeom = geom
        pills.update(geom)
        // 同步重定位 QUISecNavBar 内部左返回按钮、居中标题与右侧按钮到三段玻璃胶囊中心，消除自绘胶囊与原生按钮位置错位。
        (leftIcon ?: leftText)?.let { lb ->
            val lw = lb.measuredWidth.takeIf { it > 0 } ?: lb.width.takeIf { it > 0 } ?: (24 * dp).roundToInt()
            val lh = lb.measuredHeight.takeIf { it > 0 } ?: lb.height.takeIf { it > 0 } ?: (24 * dp).roundToInt()
            val lx = geom.leftPill.centerX() - lw / 2
            val ly = geom.leftPill.centerY() - lh / 2
            lb.layout(lx, ly, lx + lw, ly + lh)
        }
        centerTv?.takeIf { it.visibility == View.VISIBLE }?.let { tv ->
            val cw = (geom.textRight - geom.textLeft).coerceAtLeast(0)
            val ch = tv.measuredHeight.takeIf { it > 0 } ?: tv.height.takeIf { it > 0 } ?: (24 * dp).roundToInt()
            val cy = geom.centerPill.centerY() - ch / 2
            tv.gravity = Gravity.CENTER
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ch, View.MeasureSpec.EXACTLY),
            )
            tv.layout(geom.textLeft, cy, geom.textLeft + cw, cy + ch)
        }
        if (!geom.rightPill.isEmpty()) {
            val rv = rightLayout?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }
                ?: rightTv?.takeIf { it.visibility == View.VISIBLE && !it.text.isNullOrEmpty() }
            rv?.let {
                val rw = it.measuredWidth.takeIf { v -> v > 0 } ?: it.width.takeIf { v -> v > 0 } ?: geom.rightPill.width()
                val rh = it.measuredHeight.takeIf { v -> v > 0 } ?: it.height.takeIf { v -> v > 0 } ?: (24 * dp).roundToInt()
                val rx = geom.rightPill.centerX() - rw / 2
                val ry = geom.rightPill.centerY() - rh / 2
                it.layout(rx, ry, rx + rw, ry + rh)
            }
        }
    }
    private var downX = 0f
    private var downY = 0f
    fun dispatchPillTouch(event: MotionEvent): Boolean {
        val geom = lastGeom ?: return false
        pills.onTouch(event, geom)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            activeTarget = null
            downX = event.x; downY = event.y
            val x = event.x.toInt(); val y = event.y.toInt()
            activeTarget = when {
                !geom.leftPill.isEmpty() && x in geom.leftPill.left..geom.leftPill.right && y in geom.leftPill.top..geom.leftPill.bottom -> {
                    (runCatching { getLeftIcon?.invoke(nav) as? View }.getOrNull())?.takeIf { it.visibility == View.VISIBLE }
                        ?: (runCatching { getLeftText?.invoke(nav) as? View }.getOrNull())?.takeIf { it.visibility == View.VISIBLE }
                }
                !geom.rightPill.isEmpty() && x in geom.rightPill.left..geom.rightPill.right && y in geom.rightPill.top..geom.rightPill.bottom -> {
                    val rl = (runCatching { getRightLayout?.invoke(nav) as? ViewGroup }.getOrNull())?.takeIf { it.visibility == View.VISIBLE }
                    val rt = (runCatching { getRightText?.invoke(nav) as? View }.getOrNull())?.takeIf { it.visibility == View.VISIBLE }
                    if (rl != null && rl.childCount > 0) {
                        val visibleChildren = (0 until rl.childCount).map(rl::getChildAt).filter { it.visibility == View.VISIBLE }
                        if (visibleChildren.size == 1) visibleChildren[0]
                        else if (visibleChildren.isNotEmpty()) {
                            val slotW = (geom.rightPill.width().toFloat() / visibleChildren.size).coerceAtLeast(1f)
                            val idx = ((x - geom.rightPill.left) / slotW).toInt().coerceIn(0, visibleChildren.lastIndex)
                            visibleChildren[idx]
                        } else rl
                    } else rt ?: rl
                }
                else -> null
            }
        }
        val target = activeTarget ?: return false
        val copy = MotionEvent.obtain(event)
        copy.setLocation(target.width / 2f, target.height / 2f)
        val handled = try { target.dispatchTouchEvent(copy) } finally { copy.recycle() }
        if (action == MotionEvent.ACTION_UP) {
            val slop = android.view.ViewConfiguration.get(nav.context).scaledTouchSlop
            if (!handled && kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop) {
                target.performClick()
            }
            activeTarget = null
        } else if (action == MotionEvent.ACTION_CANCEL) {
            activeTarget = null
        }
        return true
    }
    fun applyStyle() {
        if (!on("玻璃底栏")) { restore(); return }
        nav.background = null
        nav.clipChildren = false; nav.clipToPadding = false
        rootView?.let { it.clipChildren = false; it.clipToPadding = false }
        titleRoot.clipChildren = false; titleRoot.clipToPadding = false
        (nav.parent as? ViewGroup)?.let { parentView ->
            parentView.clipChildren = false; parentView.clipToPadding = false
            if (parentView is LinearLayout && parentView.orientation == LinearLayout.VERTICAL) {
                // Y 轴串联布局（如 r/i/fte.xml 0x7f0a5ef0）中，父容器背景常被设为 #000000（0x7f083633），
                // 清空 nav.background 后顶部会露出纯黑断层；优先用 nav 原始表面背景（0x7f083688 #1b1c1e）或兄弟列表背景统一铺满。
                val navSurface = origNavBg?.constantState?.newDrawable(nav.resources)?.mutate()
                val siblingBg = (0 until parentView.childCount).map(parentView::getChildAt)
                    .firstOrNull { it !== nav && it.background != null }
                    ?.background?.constantState?.newDrawable(nav.resources)?.mutate()
                val fill = navSurface ?: siblingBg ?: android.graphics.drawable.ColorDrawable(
                    if (night()) 0xFF1B1C1E.toInt() else 0xFFF5F6FA.toInt()
                )
                if (parentView.background !== appliedParentBg) {
                    appliedParentBg = fill
                    parentView.background = fill
                }
            } else {
                parentView.background = null
            }
        }
        if (titleRoot.background !== pills) titleRoot.background = pills
        titleRoot.layoutParams?.let { lp ->
            val targetH = (48 * nav.dp).roundToInt()
            if (lp.height in 1 until targetH) {
                lp.height = targetH
                titleRoot.layoutParams = lp
            }
        }
        rootView?.let { rv ->
            if (rv.paddingTop != lastAppliedRootTop && rv.paddingTop > 0) {
                origRootPad = intArrayOf(rv.paddingLeft, rv.paddingTop, rv.paddingRight, rv.paddingBottom)
            }
            val orig = origRootPad
            if (orig != null && orig[1] > 0) {
                val reduced = reducedSafeTop(orig[1], nav.dp)
                lastAppliedRootTop = reduced
                if (rv.paddingTop != reduced || rv.paddingBottom != 0) {
                    rv.setPadding(orig[0], reduced, orig[2], 0)
                }
            }
        }
        if (origNavPad[1] > 0) {
            val reduced = reducedSafeTop(origNavPad[1], nav.dp)
            if (nav.paddingTop != reduced) nav.setPadding(origNavPad[0], reduced, origNavPad[2], 0)
        }
        if (origTitleRootPad[1] > 0) {
            val reduced = reducedSafeTop(origTitleRootPad[1], titleRoot.dp)
            if (titleRoot.paddingTop != reduced) titleRoot.setPadding(origTitleRootPad[0], reduced, origTitleRootPad[2], 0)
        }
        titleBgView?.visibility = View.GONE
        blurView?.visibility = View.GONE
        borderView?.visibility = View.GONE
        val dark = night()
        tintTree(titleRoot, dark)
        updateGeometry()
        pills.sync()
    }
    fun restore() {
        activeTarget = null
        lastGeom = null
        lastAppliedRootTop = -1
        nav.background = origNavBg
        nav.clipChildren = origNavClip.first; nav.clipToPadding = origNavClip.second
        rootView?.let { rv ->
            origRootClip?.let { rv.clipChildren = it.first; rv.clipToPadding = it.second }
            origRootPad?.let { rv.setPadding(it[0], it[1], it[2], it[3]) }
        }
        titleRoot.clipChildren = origTitleRootClip.first; titleRoot.clipToPadding = origTitleRootClip.second
        (nav.parent as? View)?.let { parentView ->
            if (parentView.background === appliedParentBg || origParentBg != null) {
                parentView.background = origParentBg
            }
            appliedParentBg = null
        }
        nav.setPadding(origNavPad[0], origNavPad[1], origNavPad[2], origNavPad[3])
        if (titleRoot.background === pills) titleRoot.background = origTitleRootBg
        titleRoot.setPadding(origTitleRootPad[0], origTitleRootPad[1], origTitleRootPad[2], origTitleRootPad[3])
        if (origTitleRootHeight != null && titleRoot.layoutParams != null && titleRoot.layoutParams.height != origTitleRootHeight) {
            titleRoot.layoutParams.height = origTitleRootHeight
            titleRoot.layoutParams = titleRoot.layoutParams
        }
        if (titleBgView != null && origTitleBgVis != null) titleBgView.visibility = origTitleBgVis
        if (blurView != null && origBlurVis != null) blurView.visibility = origBlurVis
        if (borderView != null && origBorderVis != null) borderView.visibility = origBorderVis
        for ((iv, _) in recordedIconTints) iv.imageTintList = savedIconTints[iv]
        savedIconTints.clear(); recordedIconTints.clear()
        for ((v, _) in recordedIconBgs) v.background = savedIconBgs[v]
        savedIconBgs.clear(); recordedIconBgs.clear()
        for ((tv, col) in savedTextColors) tv.setTextColor(col)
        savedTextColors.clear()
        titleRoot.invalidate()
    }
    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
        if (on("玻璃底栏")) updateGeometry()
    }
    override fun onPreDraw(): Boolean {
        if (on("玻璃底栏")) {
            val rv = rootView
            if ((rv != null && lastAppliedRootTop >= 0 && rv.paddingTop != lastAppliedRootTop) ||
                titleBgView?.visibility == View.VISIBLE || blurView?.visibility == View.VISIBLE) {
                applyStyle()
            } else {
                pills.sync()
            }
        }
        return true
    }
    fun dispose() {
        if (titleRoot.viewTreeObserver.isAlive) titleRoot.viewTreeObserver.removeOnPreDrawListener(this)
        titleRoot.removeOnLayoutChangeListener(this)
        restore()
        pills.dispose()
    }
}

private val secNavBars = WeakHashMap<ViewGroup, SecNavBarState>()

private fun secNavBar(nav: ViewGroup) {
    val state = secNavBars.getOrPut(nav) {
        SecNavBarState(nav).also { secNavBars[it.titleRoot] = it }
    }
    state.applyStyle()
    captureViewRootSnapshot(nav, "QUISecNavBar:${nav.context.javaClass.simpleName}")
}

private fun refreshSecNavBars() {
    secNavBars.values.distinct().forEach { it.applyStyle() }
}

private fun resetSecNavBars() {
    secNavBars.values.distinct().forEach { it.dispose() }
    secNavBars.clear()
}

// ---- 顶栏状态栏遮罩与主页顶栏安全区清理：隐藏 SystemBarTintManager 状态栏色块，移除主页顶栏不透明安全区垫底。

private val statusTintAlphas = WeakHashMap<View, Float>()
private val homeTopBgs = WeakHashMap<ViewGroup, android.graphics.drawable.Drawable?>()
private val homeTopRecorded = WeakHashMap<ViewGroup, Boolean>()
private val homeTopClips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
private val homeTopChildShifts = WeakHashMap<View, Float>()
private val homeTopIconTints = WeakHashMap<android.widget.ImageView, android.content.res.ColorStateList?>()
private val homeTopIconRecorded = WeakHashMap<android.widget.ImageView, Boolean>()
private val homeListClips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
private val homeDividers = WeakHashMap<View, Int>()

private fun syncStatusBarTintViews(decor: ViewGroup) {
    val maxStatusH = (120 * decor.dp).roundToInt()
    val enabled = on("玻璃底栏")
    for (i in 0 until decor.childCount) {
        val ch = decor.getChildAt(i)
        val lp = ch.layoutParams as? FrameLayout.LayoutParams ?: continue
        if (ch !is ViewGroup && ch.id == View.NO_ID &&
            (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP &&
            lp.height in 1..maxStatusH) {
            statusTintAlphas.putIfAbsent(ch, ch.alpha)
            val targetAlpha = if (enabled) 0f else (statusTintAlphas[ch] ?: 1f)
            if (ch.alpha != targetAlpha) ch.alpha = targetAlpha
        }
    }
}

private fun tintHomeIcons(v: View?, dark: Boolean) {
    if (v == null) return
    val bright = android.content.res.ColorStateList.valueOf(0xFFF2F5FA.toInt())
    if (v is android.widget.ImageView) {
        if (homeTopIconRecorded[v] != true) {
            homeTopIconTints[v] = v.imageTintList
            homeTopIconRecorded[v] = true
        }
        val target = if (dark) bright else homeTopIconTints[v]
        if (v.imageTintList != target) v.imageTintList = target
    } else if (v is ViewGroup) {
        for (i in 0 until v.childCount) tintHomeIcons(v.getChildAt(i), dark)
    }
}

private val homeTopBlurVis = WeakHashMap<View, Int>()
private val homeTopAppliedRootBgs = WeakHashMap<ViewGroup, android.graphics.drawable.Drawable?>()

/**
 * AIO 顶栏被去掉的安全区像素数。真包 `AIOMsgListGroup.paddingTop = 238 = 141(状态栏) + 97(标题栏)`，
 * 顶栏整体上移后消息列表顶部内边距必须同步减掉同样的量，否则标题栏与第一条消息之间会空出一大段。
 */
@Volatile
private var aioSafeTopRemovedPx = 0

internal fun aioSafeTopRemoved(): Int = aioSafeTopRemovedPx

/**
 * 顶栏内容要往下让的像素数：状态栏还在时等于「QQ 预留的状态栏高度减去我们压掉的量」，
 * 按钮因此停在状态栏以下；状态栏被本模块藏掉后这段让位本身就是空白，一律让 0，
 * 标题栏直接顶到屏幕最上沿。
 */
private fun aioTopCompensation(): Int = aioSafeTopRemovedPx

/**
 * AIO 标题栏底边在窗口里的 y（px）。顶栏本身透明、三颗玻璃胶囊浮在上面：
 * 消息区从窗口最上沿铺下来（滚动时从胶囊底下穿过），顶部内边距取这条底边，
 * 于是收起状态栏后不再留一条空白背景带，最后一条消息也不会顶到胶囊上。
 */
@Volatile
private var aioHeaderBottomPx = 0

internal fun aioHeaderBottom(): Int = aioHeaderBottomPx

/** AIO 顶栏完成布局后 3 秒内只抓一次带真实尺寸的视图树，避免每帧都 dump。 */
private var aioSnapshotAt = 0L

private fun aioLayoutSnapshot(bar: View) {
    if (!on("全量日志")) return
    val now = android.os.SystemClock.uptimeMillis()
    if (now - aioSnapshotAt < 3000L) return
    aioSnapshotAt = now
    aioGeometry(bar)
    captureViewRootSnapshot(bar, "AIO-Header-LaidOut:${bar.context.javaClass.simpleName}")
}

/**
 * AIO 顶栏的一行几何账：状态栏到底多高、顶栏被压到多少、标题栏与消息区实际落在屏幕哪个 y、
 * 以及还有没有祖先开着 `fitsSystemWindows`（开了就会把状态栏高度重新贴回 AIO 内容区，
 * 「顶栏安全区还是没去」的残余往往就藏在这里）。只写日志，不改任何布局。
 */
/**
 * 顶栏安全区被去掉 `removed` px 后，把同样一段补成标题栏自己的内容内边距：
 * 玻璃板从 y=0 一直铺到底，按钮（返回键、群名、右侧按钮）仍停在状态栏以下，不与状态栏相撞。
 * `removed = 0`（滑条在 100%，或开关关闭）时把内边距还原成 0。
 */
private fun compensateTitleSafeTop(bar: View, removed: Int) {
    if (bar.paddingTop == removed) return
    val before = bar.paddingTop
    bar.setPadding(bar.paddingLeft, removed, bar.paddingRight, bar.paddingBottom)
    recordQselfEvent(
        "AIO 顶栏内容补偿：去掉安全区 ${removed}px 补进标题栏 paddingTop（$before -> $removed），" +
            "按钮留在状态栏以下；标题栏目标高度=${(50 * bar.dp).roundToInt() + removed}px",
    )
}

private fun aioGeometry(bar: View) {
    val root = bar.rootView
    val statusTop = runCatching {
        root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
    }.getOrDefault(0)
    val loc = IntArray(2)
    fun screenTop(v: View?): Int =
        if (v == null) -1 else runCatching { v.getLocationOnScreen(loc); loc[1] }.getOrDefault(-1)
    val title = runCatching { root.findViewById<View>(0x7f0a6c15) }.getOrNull()       // AIOTitleRelativeLayout
    val titleOuter = runCatching { root.findViewById<View>(0x7f0a6bc6) }.getOrNull()  // 顶栏外层容器
    val msgArea = runCatching { root.findViewById<View>(0x7f0a02b5) }.getOrNull()     // AIOAnimationContainer（消息区）
    val back = runCatching { root.findViewById<View>(0x7f0a2ec2) }.getOrNull()        // ivTitleBtnLeft
    var fit: View? = null
    var p = bar.parent as? View
    while (p != null) {
        if (p.fitsSystemWindows) { fit = p; break }
        p = p.parent as? View
    }
    recordQselfEvent(
        "AIO 几何 : 状态栏=${statusTop}px 顶栏容器padTop=${titleOuter?.paddingTop ?: -1}px " +
            "标题栏y=${screenTop(title)} 高=${title?.height ?: -1} 消息区y=${screenTop(msgArea)} " +
            "返回键y=${screenTop(back)} 已去掉安全区=${aioSafeTopRemovedPx}px " +
            "fitsSystemWindows祖先=${fit?.javaClass?.simpleName ?: "无"}(padTop=${fit?.paddingTop ?: -1}) " +
            "(下移=${safeTopShiftDp()}dp)",
    )
}

/**
 * 主页（`Conversation` `r/i/e5p.xml`）顶栏安全区三件套，实测值（`1272×2800`、density `1.976`、`statusBars=[0,141,0,0]`）：
 * - `0x7f0a3245` `TitleAreaHeadLayout`（头像）`topMargin = 68px`；
 * - `0x7f0a6cfa` 顶栏容器 `b=[0,0,1272,167]`（`167 = 68 + 99`），其右侧按钮区 `0x7f0a120a` 贴底排布；
 * - `0x7f0a0e96` `ClipSkinnableRecycleView` `paddingTop = 167px`（搜索框与会话列表均在列表内）。
 *
 * 三者用的是同一个安全区高度，所以只要把头像的 `topMargin` 与顶栏容器整体上移同样的 `delta`，
 * 再把会话列表 `paddingTop` 减掉 `delta`，主页顶栏安全区就真的没了；关掉开关时原样还原。
 */
private class HomeSafeArea(val head: View, val container: View, val list: View) {
    val origHeadTop: Int = (head.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    val origContainerTy: Float = container.translationY
    val origListPad: Int = list.paddingTop
}

private val homeSafeAreas = WeakHashMap<View, HomeSafeArea>()

private fun homeSafeArea(bar: ViewGroup) {
    val state = if (homeSafeAreas.containsKey(bar)) homeSafeAreas[bar] else run {
        val root = bar.rootView as? ViewGroup
        val head = bar.takeIf { it.id == 0x7f0a3245 } ?: root?.findViewById(0x7f0a3245)
        val container = root?.findViewById<View>(0x7f0a6cfa)
        val list = root?.findViewById<View>(0x7f0a0e96)
        val made = if (head != null && container != null && list != null) HomeSafeArea(head, container, list) else null
        if (made != null) homeSafeAreas[bar] = made
        made
    }
    if (state == null) return
    val target = if (on("玻璃底栏") && state.origHeadTop > 0) reducedSafeTop(state.origHeadTop, bar.dp) else state.origHeadTop
    val delta = state.origHeadTop - target
    val lp = state.head.layoutParams as? ViewGroup.MarginLayoutParams
    if (lp != null && lp.topMargin != target) {
        lp.topMargin = target
        state.head.layoutParams = lp
        recordQselfEvent("homeSafeArea 顶栏顶部 ${state.origHeadTop}px -> ${target}px（delta=$delta，下移=${safeTopShiftDp()}dp）")
    }
    val ty = state.origContainerTy - delta
    if (state.container.translationY != ty) state.container.translationY = ty
    val pad = (state.origListPad - delta).coerceAtLeast(0)
    if (state.list.paddingTop != pad) {
        state.list.setPadding(state.list.paddingLeft, pad, state.list.paddingRight, state.list.paddingBottom)
    }
}

private fun homeTopBar(bar: ViewGroup) {
    homeSafeArea(bar)
    if (homeTopRecorded[bar] != true) {
        homeTopBgs[bar] = bar.background
        homeTopClips[bar] = bar.clipChildren to bar.clipToPadding
        homeTopRecorded[bar] = true
    }
    val scope = (bar.parent as? ViewGroup) ?: bar
    if (!on("玻璃底栏")) {
        if (bar.id != 0x7f0a3245 && bar.background == null && homeTopBgs[bar] != null) bar.background = homeTopBgs[bar]
        homeTopClips[bar]?.let { bar.clipChildren = it.first; bar.clipToPadding = it.second }
        bar.findViewById<View>(0x7f0a0951)?.let { blur ->
            homeTopBlurVis.remove(blur)?.let { blur.visibility = it }
        }
        scope.findViewById<View>(0x7f0a6596)?.let { div ->
            homeDividers.remove(div)?.let { div.visibility = it }
        }
        (bar.parent as? ViewGroup)?.takeIf { it.id == 0x7f0a1142 }?.let { root ->
            if (root.background === homeTopAppliedRootBgs[root]) {
                root.background = homeTopBgs[root]
                homeTopAppliedRootBgs.remove(root)
            }
        }
        for (id in intArrayOf(0x7f0a3245, 0x7f0a3d3f, 0x7f0a120a, 0x7f0a2ef9, 0x7f0a2ed1, 0x7f0a6bb6, 0x7f0a6bb7)) {
            scope.findViewById<View>(id)?.let { ch ->
                homeTopChildShifts[ch]?.let { ch.translationY = it }
            }
        }
        for ((iv, _) in homeTopIconRecorded) iv.imageTintList = homeTopIconTints[iv]
        return
    }
    if (bar.id != 0x7f0a3245 && bar.background != null) bar.background = null
    bar.clipChildren = false; bar.clipToPadding = false
    bar.findViewById<View>(0x7f0a0951)?.let { blur ->
        homeTopBlurVis.putIfAbsent(blur, blur.visibility)
        if (blur.visibility != View.GONE) blur.visibility = View.GONE
    }
    scope.findViewById<View>(0x7f0a6596)?.let { div ->
        homeDividers.putIfAbsent(div, div.visibility)
        if (div.visibility != View.GONE) div.visibility = View.GONE
    }
    // Contacts 顶栏（r/i/e51.xml：0x7f0a1142 Vertical LinearLayout -> 0x7f0a6588 immersive_title_container_layout）
    // 为 Y 轴串联结构，清空 0x7f0a6588 背景后需把根容器 0x7f0a1142 背景同步为 0x7f0a1143 或列表表面色，避免露出顶部黑底。
    (bar.parent as? ViewGroup)?.takeIf { it.id == 0x7f0a1142 }?.let { root ->
        if (homeTopRecorded[root] != true) {
            homeTopBgs[root] = root.background
            homeTopClips[root] = root.clipChildren to root.clipToPadding
            homeTopRecorded[root] = true
        }
        val bgIv = root.findViewById<android.widget.ImageView>(0x7f0a1143)
        val fill = bgIv?.drawable?.constantState?.newDrawable(root.resources)?.mutate()
            ?: android.graphics.drawable.ColorDrawable(if (night()) 0xFF17191D.toInt() else 0xFFF5F6FA.toInt())
        if (root.background !== homeTopAppliedRootBgs[root]) {
            homeTopAppliedRootBgs[root] = fill
            root.background = fill
        }
    }
    // 主页顶栏（Conversation r/i/e5p.xml / Contacts / Leba）：安全区由 homeSafeArea 按「顶栏安全区」滑条整体去掉，
    // 未命中 homeSafeArea 三件套的页面（Contacts / Leba）仍按旧的 4dp 微调，并对夜间模式图标统一提亮。
    val shiftY = if (homeSafeAreas.containsKey(bar)) 0f else -4f * bar.dp
    val dark = night()
    for (id in intArrayOf(0x7f0a3245, 0x7f0a3d3f, 0x7f0a120a, 0x7f0a2ef9, 0x7f0a2ed1, 0x7f0a6bb6, 0x7f0a6bb7)) {
        scope.findViewById<View>(id)?.let { ch ->
            if (id != 0x7f0a3245) {
                val origY = homeTopChildShifts.getOrPut(ch) { ch.translationY }
                val targetY = origY + shiftY
                if (ch.translationY != targetY) ch.translationY = targetY
            }
            if (id == 0x7f0a120a || id == 0x7f0a2ed1 || id == 0x7f0a6bb7) tintHomeIcons(ch, dark)
        }
    }
    scope.findViewById<View>(0x7f0a1209)?.let { tintHomeIcons(it, dark) }
    captureViewRootSnapshot(bar, "HomeTopBar:0x${Integer.toHexString(bar.id)}")
}

private fun homeListClip(group: ViewGroup) {
    homeListClips.putIfAbsent(group, group.clipChildren to group.clipToPadding)
    if (on("玻璃底栏")) {
        if (group.clipChildren) group.clipChildren = false
        if (group.clipToPadding) group.clipToPadding = false
    } else {
        homeListClips[group]?.let {
            group.clipChildren = it.first
            group.clipToPadding = it.second
        }
    }
}

private fun refreshHomeTopBars() {
    homeTopRecorded.keys.toList().forEach { homeTopBar(it) }
    homeListClips.keys.toList().forEach { homeListClip(it) }
    homeDividers.entries.toList().forEach { (div, vis) ->
        div.visibility = if (on("玻璃底栏")) View.GONE else vis
    }
    statusTintAlphas.keys.toList().forEach { ch ->
        (ch.parent as? ViewGroup)?.let(::syncStatusBarTintViews)
    }
}

private fun resetHomeTopBars() {
    homeTopRecorded.keys.toList().forEach { bar ->
        bar.background = homeTopBgs[bar]
        homeTopClips[bar]?.let { bar.clipChildren = it.first; bar.clipToPadding = it.second }
    }
    homeTopBlurVis.forEach { (blur, vis) -> blur.visibility = vis }
    homeDividers.forEach { (div, vis) -> div.visibility = vis }
    homeTopChildShifts.forEach { (ch, origY) -> ch.translationY = origY }
    for ((iv, _) in homeTopIconRecorded) iv.imageTintList = homeTopIconTints[iv]
    homeListClips.forEach { (g, old) -> g.clipChildren = old.first; g.clipToPadding = old.second }
    statusTintAlphas.forEach { (ch, origA) -> ch.alpha = origA }
    homeSafeAreas.values.toList().forEach { st ->
        (st.head.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            if (lp.topMargin != st.origHeadTop) { lp.topMargin = st.origHeadTop; st.head.layoutParams = lp }
        }
        if (st.container.translationY != st.origContainerTy) st.container.translationY = st.origContainerTy
        if (st.list.paddingTop != st.origListPad) {
            st.list.setPadding(st.list.paddingLeft, st.origListPad, st.list.paddingRight, st.list.paddingBottom)
        }
    }
    homeSafeAreas.clear()
    homeTopBgs.clear(); homeTopRecorded.clear(); homeTopClips.clear()
    homeTopBlurVis.clear(); homeDividers.clear(); homeTopAppliedRootBgs.clear()
    homeTopChildShifts.clear(); homeTopIconTints.clear(); homeTopIconRecorded.clear()
    homeListClips.clear(); statusTintAlphas.clear()
}

private class SearchBarState(val bar: ViewGroup) {
    private val getInputBg = runCatching { bar.javaClass.getDeclaredMethod("getInputBg").apply { isAccessible = true } }.getOrNull()
    private val inputBg = runCatching { getInputBg?.invoke(bar) as? View }.getOrNull()
    private val origBarBg = bar.background
    private val origInputOutline = inputBg?.outlineProvider
    private val origInputClip = inputBg?.clipToOutline ?: false
    private val shape = capsule()
    private val savedIconTints = WeakHashMap<android.widget.ImageView, android.content.res.ColorStateList?>()
    private val recordedIconTints = WeakHashMap<android.widget.ImageView, Boolean>()
    private val savedTextColors = WeakHashMap<TextView, Int>()
    private fun tintTree(v: View?, dark: Boolean) {
        if (v == null) return
        val bright = android.content.res.ColorStateList.valueOf(0xFFE8EEF7.toInt())
        if (v is android.widget.ImageView) {
            if (recordedIconTints[v] != true) {
                savedIconTints[v] = v.imageTintList
                recordedIconTints[v] = true
            }
            if (dark) {
                if (v.imageTintList != bright) v.imageTintList = bright
            } else {
                val orig = savedIconTints[v]
                if (v.imageTintList != orig) v.imageTintList = orig
            }
        } else if (v is TextView) {
            if (!savedTextColors.containsKey(v)) savedTextColors[v] = v.currentTextColor
            if (dark) v.setTextColor(0xFFE8EEF7.toInt()) else savedTextColors[v]?.let(v::setTextColor)
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) tintTree(v.getChildAt(i), dark)
        }
    }
    fun applyStyle() {
        if (!on("玻璃底栏") && !on("输入栏药丸")) { restore(); return }
        bar.background = null
        inputBg?.let {
            if (it.outlineProvider !== shape) it.outlineProvider = shape
            if (!it.clipToOutline) it.clipToOutline = true
        }
        tintTree(bar, night())
    }
    fun restore() {
        bar.background = origBarBg
        inputBg?.let {
            it.outlineProvider = origInputOutline
            it.clipToOutline = origInputClip
        }
        for ((iv, _) in recordedIconTints) iv.imageTintList = savedIconTints[iv]
        savedIconTints.clear(); recordedIconTints.clear()
        for ((tv, col) in savedTextColors) tv.setTextColor(col)
        savedTextColors.clear()
    }
}

private val searchBars = WeakHashMap<ViewGroup, SearchBarState>()

private fun searchBar(bar: ViewGroup) {
    searchBars.getOrPut(bar) { SearchBarState(bar) }.applyStyle()
}

private fun refreshSearchBars() {
    searchBars.values.toList().forEach { it.applyStyle() }
}

private fun resetSearchBars() {
    searchBars.values.toList().forEach { it.restore() }
    searchBars.clear()
}

// ---- 页面与底栏页签切换过渡：共享 X 轴微缩淡入（RenderThread 属性动画，无 Y 轴下展开，零布局抖动）。

private fun runSwitchTransition(view: View?, dirX: Float = 1f, distanceDp: Float = 16f, durationMs: Long = 210L) {
    val v = view ?: return
    v.animate().cancel()
    if (!on("页面过渡动效") || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
        v.translationX = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.alpha = 1f
        return
    }
    val dx = dirX * distanceDp * v.dp
    v.translationX = dx
    v.scaleX = 0.985f
    v.scaleY = 0.985f
    v.alpha = 0.74f
    v.animate()
        .translationX(0f)
        .scaleX(1f)
        .scaleY(1f)
        .alpha(1f)
        .setDuration(durationMs)
        .setInterpolator(android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f))
        .withEndAction {
            v.translationX = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.alpha = 1f
        }
        .start()
}

fun pageAnimations() {
    runCatching {
        val controller = cls("com.tencent.mobileqq.activity.home.impl.TabFrameControllerImpl")
        val frameFrag = cls("com.tencent.mobileqq.app.FrameFragment")
        val bfCls = cls("com.tencent.mobileqq.app.bf")
        val getTabIdx = controller.getDeclaredMethod("getTabIndexFromName", String::class.java).apply { isAccessible = true }
        val getContentView = bfCls.getDeclaredMethod("getContentView").apply { isAccessible = true }
        val updateAfter = controller.getDeclaredMethod(
            "updateUIAfterTabChange",
            frameFrag,
            String::class.java,
            String::class.java,
            bfCls,
        )
        hook(updateAfter, gated = false) { chain ->
            chain.proceed().also {
                if (!on("页面过渡动效")) return@also
                val newTag = chain.args.getOrNull(1) as? String ?: return@also
                val oldTag = chain.args.getOrNull(2) as? String ?: return@also
                if (newTag == oldTag || oldTag.isEmpty()) return@also
                val frame = chain.args.getOrNull(3) ?: return@also
                val content = runCatching { getContentView.invoke(frame) as? View }.getOrNull() ?: return@also
                val newIdx = runCatching { getTabIdx.invoke(chain.thisObject, newTag) as? Int }.getOrNull() ?: 0
                val oldIdx = runCatching { getTabIdx.invoke(chain.thisObject, oldTag) as? Int }.getOrNull() ?: 0
                val dir = if (newIdx >= oldIdx) 1f else -1f
                runSwitchTransition(content, dirX = dir, distanceDp = 18f, durationMs = 220L)
            }
        }
    }
    runCatching {
        val msgListVB = cls("com.tencent.mobileqq.aio.msglist.AIOMsgListVB")
        val helpParam = cls("com.tencent.mvi.api.help.a")
        val onCreate = generateSequence<Class<*>>(msgListVB) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == "onCreateView" && it.parameterTypes.size == 1 && it.parameterTypes[0] == helpParam } }
            .firstOrNull()
        if (onCreate != null) {
            hook(onCreate.apply { isAccessible = true }, gated = false) { chain ->
                chain.proceed().also { res ->
                    if (msgListVB.isInstance(chain.thisObject) && on("页面过渡动效")) {
                        runSwitchTransition(res as? View, dirX = 1f, distanceDp = 14f, durationMs = 200L)
                    }
                }
            }
        }
    }
}


