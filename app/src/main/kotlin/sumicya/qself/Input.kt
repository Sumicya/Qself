// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.RelativeLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 不搬移、不隐藏输入或按钮；玻璃只覆盖真实 EditText，不再给整个按钮父框铺底。 */
/** 「输入区被接管」这一个判断散在十来处，改一个开关要改十处；收成一条。 */
private fun inputStyled() = on("输入栏药丸") || on("玻璃底栏")

private val inputs = WeakHashMap<EditText, WeakReference<NativeInput>>()
private val inputDecorations = WeakHashMap<View, InputDecoration>()
private val msgListUnderScrolls = WeakHashMap<ViewGroup, MsgListUnderScroll>()

/** 活着的 NativeInput。五处「先 mapNotNull 再去重引用」收成这一条，免得每处各写一遍。 */
private fun liveInputs() = inputs.values.mapNotNull { it.get() }

@Volatile
private var aioInputTopY = 0

/**
 * 消息列表底部要留多少：列表底边被浮层盖住的部分，再加一段呼吸间距。
 *
 * 两个数都是窗口坐标，所以键盘弹出、面板展开、浮层整体上移时，量出来的重叠自己就跟着变，
 * 不需要「键盘开着就按 0 算」这类分支 —— 那个分支在真机上正好把最后一条消息摁进输入栏。
 * 浮层完全在列表下方（没重叠）时返回 0；量到 0 或负数（还没布局完）也返回 0，宁可不动。
 */
internal fun listBottomInsetPx(listBottomY: Int, overlayTopY: Int, gapPx: Int, maxPx: Int): Int {
    if (listBottomY <= 0 || overlayTopY <= 0 || maxPx <= 0) return 0
    val overlap = listBottomY - overlayTopY
    if (overlap <= 0) return 0
    return (overlap + gapPx.coerceAtLeast(0)).coerceIn(0, maxPx)
}

/** 浮层最上沿的窗口 y：从锚点（输入框）往上找真正那一层容器，取最小 y。 */
private fun overlayTopInWindowPx(root: View, anchor: View): Int {
    if (root.height <= 0) return 0
    fun topOf(v: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationInWindow(a); root.getLocationInWindow(b)
        return a[1] - b[1]
    }
    val limit = root.height * 2 / 5
    var top = topOf(anchor)
    var cur: View? = anchor.parent as? View
    var guard = 0
    while (cur != null && cur !== root && guard++ < 6) {
        val h = cur.height
        if (h in 1..limit) top = minOf(top, topOf(cur))
        cur = cur.parent as? View
    }
    return top.coerceAtLeast(0)
}

private val panelGlasses = WeakHashMap<View, PanelGlassState>()

private class PanelGlassState(val root: View) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val origBg = root.background
    private val glass = Glass(root, 20 * root.dp, kernelSurface = true)
    init {
        root.viewTreeObserver.addOnPreDrawListener(this)
        root.addOnAttachStateChangeListener(this)
    }
    private val enabled get() = inputStyled()
    fun sync() {
        if (!enabled) { restore(); return }
        if (root.background !== glass) root.background = glass
        glass.sync()
    }
    fun restore() { if (root.background === glass) root.background = origBg }
    /** 脱离只摘 PreDraw；AttachState 留到 dispose，否则挂回来没人通知。 */
    fun detach() {
        restore()
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
    }
    fun dispose() {
        detach(); glass.dispose()
        root.removeOnAttachStateChangeListener(this)
        panelGlasses.remove(root)
    }
    override fun onPreDraw(): Boolean { if (enabled) glass.sync(); return true }
    override fun onViewAttachedToWindow(v: View) {
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.addOnPreDrawListener(this)
        sync()
    }
    override fun onViewDetachedFromWindow(v: View) = detach()
}

private class MsgListUnderScroll(
    val group: ViewGroup,
    val edge: ViewGroup?,
    val refresh: ViewGroup?,
    val recycler: ViewGroup,
) {
    private var savedGroupTop = group.paddingTop.takeIf { it > 0 } ?: recycler.paddingTop
    private var savedGroupBottom = group.paddingBottom.takeIf { it > 0 } ?: recycler.paddingBottom
    private var lastAppliedTop = -1
    private var lastAppliedBottom = -1
    private val origGroupClipToPad = group.clipToPadding
    private val origGroupClipChildren = group.clipChildren
    private val origEdgeClipToPad = edge?.clipToPadding ?: true
    private val origEdgeClipChildren = edge?.clipChildren ?: true
    private val origEdgeI = runCatching { edge?.get("i") as? Int }.getOrNull() ?: 0
    private val origEdgeM = runCatching { edge?.get("m") as? Int }.getOrNull() ?: 0
    private val origEdgeD = runCatching { edge?.get("D") as? Int }.getOrNull() ?: 0
    private val origRefreshClipToPad = refresh?.clipToPadding ?: true
    private val origRefreshClipChildren = refresh?.clipChildren ?: true
    private val origRecyclerClipToPad = recycler.clipToPadding
    private val origRecyclerClipChildren = recycler.clipChildren
    private val origRecyclerTop = recycler.paddingTop
    private val origRecyclerBottom = recycler.paddingBottom
    /** 记着挂在**哪一个** observer 上，而不是「挂过没有」。换代后旧登记失效，boolean 会挡住重挂。 */
    private var watchingOn: ViewTreeObserver? = null
    /** 主线程每帧都要取一次窗口坐标，复用同一个数组，不每帧新建。 */
    private val at = IntArray(2)
    /** 是否已经接管过这块消息区：没接管过就不要在 restore 里改回别的几何。 */
    private var active = false
    /** 为「从 y=0 铺下来」自己加过的位移，关开关时按这个数还回去。 */
    private var shiftedBy = 0f

    /** 顶栏透明：消息区从窗口最上沿铺下来，滚动时从玻璃胶囊底下穿过；内边距取顶栏真实底边。 */
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val started = System.nanoTime()
        try {
            if (inputStyled()) syncGeometry()
        } finally {
            CostMeter.add(COST_LIST, started)
        }
        true
    }

    fun sync() {
        val enabled = inputStyled()
        if (!enabled) {
            restore()
            return
        }
        val gt = group.paddingTop
        val gb = group.paddingBottom
        if (gt > 0) savedGroupTop = gt
        else if (recycler.paddingTop > 0 && recycler.paddingTop != lastAppliedTop) savedGroupTop = recycler.paddingTop
        if (gb > 0) savedGroupBottom = gb
        else if (recycler.paddingBottom > 0 && recycler.paddingBottom != lastAppliedBottom) savedGroupBottom = recycler.paddingBottom
        active = true
        if (group.clipToPadding) group.clipToPadding = false
        if (group.clipChildren) group.clipChildren = false
        if (group.background != null) group.background = null
        if (gt != 0 || gb != 0) {
            group.setPadding(group.paddingLeft, 0, group.paddingRight, 0)
        }
        edge?.let { ev ->
            if (ev.clipToPadding) ev.clipToPadding = false
            if (ev.clipChildren) ev.clipChildren = false
            if (ev.background != null) ev.background = null
            if (ev.paddingTop != 0 || ev.paddingBottom != 0) ev.setPadding(ev.paddingLeft, 0, ev.paddingRight, 0)
            // 清零 EdgeTransparentView 的 i / m / D，阻止 drawChild 分配全屏 saveLayer 并用 PorterDuff.Mode.CLEAR 擦除顶部 y=0..238 消息。
            runCatching {
                if ((ev.get("i") as? Int ?: 0) != 0) ev.set("i", 0)
                if ((ev.get("m") as? Int ?: 0) != 0) ev.set("m", 0)
                if ((ev.get("D") as? Int ?: 0) != 0) ev.set("D", 0)
            }
        }
        refresh?.let { rf ->
            if (rf.clipToPadding) rf.clipToPadding = false
            if (rf.clipChildren) rf.clipChildren = false
            if (rf.background != null) rf.background = null
            if (rf.paddingTop != 0 || rf.paddingBottom != 0) rf.setPadding(rf.paddingLeft, 0, rf.paddingRight, 0)
        }
        if (recycler.clipToPadding) recycler.clipToPadding = false
        if (recycler.clipChildren) recycler.clipChildren = false
        if (recycler.background != null) recycler.background = null
        watch(true)
        syncGeometry()
    }

    /**
     * 消息区几何：
     *
     * - 状态栏照常显示，消息区不进到它下面：上沿对齐 `topInsetPx()`（状态栏与摄像头挖孔的下沿，
     *   真机 141）。全屏页把它让成 0 时按 0 算，此时列表从窗口最上沿铺下来。
     * - 顶部内边距直接取标题栏真实底边（日志实测 141+99 → 240），减去消息区自己的上沿。
     *   于是消息从胶囊底下滚过，又不和状态栏叠在一起。
     * - 底部内边距 = 列表底边与浮层上沿的重叠量 + 呼吸间距，两边都用窗口坐标，键盘开合不必分支。
     *   真机导出实测：列表 141..1881、浮层顶 1778，重叠 103，加 12 的间距 = 115。
     */
    private fun syncGeometry() {
        if (recycler.height <= 0) return
        if (on("玻璃底栏") && aioHeaderBottom() > 0) {
            group.getLocationInWindow(at)
            val inset = topInsetPx()
            // 还没量到下沿时不要往 y=0 拉：退回 QQ 自己的位置，宁可不动也不要叠进状态栏。
            val want = if (inset > 0) minOf(inset, aioHeaderBottom()) else aioHeaderBottom()
            val delta = at[1] - want
            if (delta != 0) {
                group.translationY -= delta.toFloat()
                shiftedBy += delta.toFloat()
                group.getLocationInWindow(at)
            }
        } else {
            at[1] = group.top
        }
        val groupTop = at[1]
        val groupBottom = groupTop + group.height
        val headerBottom = aioHeaderBottom()
        val targetTop = if (on("玻璃底栏") && headerBottom > 0) {
            (headerBottom - groupTop).coerceAtLeast(0)
        } else origRecyclerTop
        val targetBottom = if (inputStyled()) {
            // 浮层上沿与列表底边都取窗口坐标：键盘弹起、面板展开时两边一起动，重叠量自己就对。
            val gap = (6 * group.dp).toInt()
            val cap = group.height * 2 / 5
            maxOf(origRecyclerBottom, listBottomInsetPx(groupBottom, aioInputTopY, gap, cap))
        } else origRecyclerBottom
        if (targetTop != lastAppliedTop || targetBottom != lastAppliedBottom) {
            recordQselfEvent(
                "消息区几何 列表=${groupTop}至${groupBottom} 浮层顶=${aioInputTopY} " +
                    "内边距=上${targetTop}/下${targetBottom}" +
                    if (targetBottom == 0 && aioInputTopY in 1 until groupBottom) " ← 异常：有重叠却没让位" else "",
            )
        }
        lastAppliedTop = targetTop
        lastAppliedBottom = targetBottom
        if (recycler.paddingTop != targetTop || recycler.paddingBottom != targetBottom) {
            recycler.setPadding(recycler.paddingLeft, targetTop, recycler.paddingRight, targetBottom)
        }
    }

    private fun watch(start: Boolean) {
        val vto = group.viewTreeObserver
        if (!vto.isAlive) return
        val old = watchingOn
        if (start && old === vto) return
        old?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        watchingOn = if (start) vto.also { it.addOnPreDrawListener(preDraw) } else null
    }
    fun restore() {
        if (!active) return
        active = false
        lastAppliedTop = -1
        lastAppliedBottom = -1
        watch(false)
        if (shiftedBy != 0f) {
            group.translationY -= shiftedBy
            shiftedBy = 0f
        }
        group.clipToPadding = origGroupClipToPad
        group.clipChildren = origGroupClipChildren
        group.setPadding(group.paddingLeft, savedGroupTop, group.paddingRight, savedGroupBottom)
        edge?.let { ev ->
            ev.clipToPadding = origEdgeClipToPad
            ev.clipChildren = origEdgeClipChildren
            runCatching {
                ev.set("i", origEdgeI)
                ev.set("m", origEdgeM)
                ev.set("D", origEdgeD)
            }
        }
        refresh?.let { rf ->
            rf.clipToPadding = origRefreshClipToPad
            rf.clipChildren = origRefreshClipChildren
        }
        recycler.clipToPadding = origRecyclerClipToPad
        recycler.clipChildren = origRecyclerClipChildren
        recycler.setPadding(recycler.paddingLeft, origRecyclerTop, recycler.paddingRight, origRecyclerBottom)
    }
}

/** NT 绑定器直接提供真实 EditText 与发送按钮；发现钩子始终运行，开关只控制样式，关闭时也能登记。 */
fun inputLayout() {
    val adornType = cls("com.tencent.mobileqq.aio.input.adorn.e")
    val adornEdit = adornType.getDeclaredMethod("a")
    val adornSend = adornType.getDeclaredMethod("d")
    val smoothType = cls("com.tencent.qui.smoothcorner.b")
    val smoothGetRadius = smoothType.getDeclaredMethod("e")
    val smoothSetRadius = smoothType.getDeclaredMethod("j", java.lang.Float.TYPE)
    SendButtonPill.initSmoothCorner(smoothType, smoothGetRadius, smoothSetRadius)
    val delegate = cls("com.tencent.mobileqq.aio.input.edit.delegate.InputEditTextVBDelegate")
    val edit = delegate.getDeclaredField("e").apply { isAccessible = true }
    val prepared = delegate.getDeclaredMethod("U", cls("com.tencent.mobileqq.aio.input.adorn.e"))
    hook(prepared, gated = false) { chain -> chain.proceed().also {
        val adorn = chain.args.getOrNull(0)
        val ed = (edit.get(chain.thisObject) as? EditText) ?: runCatching { adorn?.let { adornEdit.invoke(it) as? EditText } }.getOrNull()
        val send = runCatching { adorn?.let { adornSend.invoke(it) as? View } }.getOrNull()
        ed?.let { tgInput(it, sendBtn = send) }
    } }
    val styleDelegate = cls("com.tencent.mobileqq.aio.input.inputbar.compat.b")
    val styleBinding = styleDelegate.getDeclaredField("e").apply { isAccessible = true }
    fun syncCompatDelegate(delegateObj: Any?) {
        refreshInputLayout()
        runCatching { delegateObj?.let { styleBinding.get(it) } }.getOrNull()?.let { adorn ->
            val ed = runCatching { adornEdit.invoke(adorn) as? EditText }.getOrNull()
                ?: runCatching { adorn.get("b") as? EditText }.getOrNull()
            val send = runCatching { adornSend.invoke(adorn) as? View }.getOrNull()
            ed?.let { tgInput(it, sendBtn = send) }
        }
    }
    for (name in listOf("handleUIState", "e", "g", "i", "n")) {
        val ms = styleDelegate.declaredMethods.filter { it.name == name } // 顺序不保证，firstOrNull 拿到哪个是随机的
        if (ms.isEmpty()) log("输入栏同步落点缺失：${styleDelegate.simpleName}#$name")
        for (m in ms) hook(m.apply { isAccessible = true }, gated = false) { chain -> chain.proceed().also { syncCompatDelegate(chain.thisObject) } }
    }
    val sendRadiusCompanion = cls("com.tencent.mobileqq.aio.input.inputbar.compat.e\$b")
    hook(sendRadiusCompanion.getDeclaredMethod("f", android.content.Context::class.java, Integer.TYPE, Integer.TYPE), gated = false) { chain ->
        val orig = chain.proceed()
        if (!on("输入栏药丸")) orig
        else {
            val ctx = chain.args.getOrNull(0) as? android.content.Context
            val h = (chain.args.getOrNull(2) as? Number)?.toInt() ?: 0
            if (h > 0) h / 2
            else if (ctx != null) (18f * ctx.resources.displayMetrics.density).toInt()
            else orig
        }
    }
    val expandable = cls("com.tencent.mobileqq.aio.input.AIOInputExpandableVB")
    val expContainer = expandable.getDeclaredField("e").apply { isAccessible = true }
    val expDivider = expandable.getDeclaredField("f").apply { isAccessible = true }
    val helpParam = cls("com.tencent.mvi.api.help.a")
    fun syncExpandable(vb: Any?) {
        val target = vb ?: return
        val bindingOrView = runCatching { expContainer.get(target) }.getOrNull()
        val rootView = (bindingOrView as? View) ?: runCatching { bindingOrView?.get("a") as? View }.getOrNull()
        rootView?.let { v -> generateSequence(v as View?) { it.parent as? View }.take(2).forEach { decorate(it, pillOnly = true) } }
        for (fieldName in listOf("d", "f")) {
            (runCatching { bindingOrView?.get(fieldName) as? View }.getOrNull())?.let { decorate(it, pillOnly = true) }
        }
        (runCatching { expDivider.get(target) as? View }.getOrNull())?.let { decorate(it, true, pillOnly = true) }
        val ed = runCatching { bindingOrView?.get("c") as? EditText }.getOrNull()
        val send = runCatching { bindingOrView?.get("i") as? View }.getOrNull()
        if (ed != null) tgInput(ed, sendBtn = send)
    }
    for (name in listOf("onCreateView", "handleUIState", "o1", "p1", "f1", "m1")) {
        hookAll(expandable, expandable::isInstance, name) { syncExpandable(it) }
    }
    val topBlur = cls("com.tencent.mobileqq.aio.reserve1.blur.f")
    val topBlurBindingField = topBlur.getDeclaredField("d").apply { isAccessible = true }
    val topBlurViewField = cls("sm1.t").getDeclaredField("a").apply { isAccessible = true }
    fun syncTopBlur(vb: Any?) {
        val target = vb ?: return
        val binding = runCatching { topBlurBindingField.get(target) }.getOrNull() ?: return
        (runCatching { topBlurViewField.get(binding) as? View }.getOrNull())?.let { v ->
            val dec = decorate(v, true)
        }
    }
    for (name in listOf("onCreateView", "bindViewAndData", "handleUIState", "d1", "f1", "g1")) {
        hookAll(topBlur, topBlur::isInstance, name) { syncTopBlur(it) }
    }
    val topMask = cls("com.tencent.mobileqq.aio.reserve1.mask.b")
    val topMaskBindingField = topMask.getDeclaredField("d").apply { isAccessible = true }
    val topMaskViewField = cls("sm1.y").getDeclaredField("a").apply { isAccessible = true }
    fun syncTopMask(vb: Any?) {
        val target = vb ?: return
        val binding = runCatching { topMaskBindingField.get(target) }.getOrNull() ?: return
        (runCatching { topMaskViewField.get(binding) as? View }.getOrNull())?.let { v ->
            val dec = decorate(v, true)
        }
    }
    for (name in listOf("onCreateView", "bindViewAndData", "handleUIState", "e1", "f1", "g1")) {
        hookAll(topMask, topMask::isInstance, name) { syncTopMask(it) }
    }
    val bottomBlur = cls("com.tencent.mobileqq.aio.reserve1.blur.BottomBlurVB")
    val getBottomBlurI1 = runCatching { bottomBlur.getDeclaredMethod("i1").apply { isAccessible = true } }.getOrNull()
    val getBottomBlurK1 = runCatching { bottomBlur.getDeclaredMethod("k1").apply { isAccessible = true } }.getOrNull()
    fun syncBottomBlur(vb: Any?) {
        val target = vb ?: return
        for (getter in listOfNotNull(getBottomBlurI1, getBottomBlurK1)) {
            (runCatching { getter.invoke(target) as? View }.getOrNull())?.let { v ->
                val dec = decorate(v, true)
            }
        }
    }
    for (name in listOf("onCreateView", "handleUIState", "h1", "j1", "l1", "n1", "e1", "f1", "m1", "o1")) {
        hookAll(bottomBlur, bottomBlur::isInstance, name) { syncBottomBlur(it) }
    }
    val edgeTransparent = cls("com.tencent.mobileqq.aio.widget.EdgeTransparentView")
    for (name in listOf("setTransparentSize", "setPositionAndDrawSize")) {
        edgeTransparent.declaredMethods.filter { it.name == name }.forEach { m ->
            hook(m.apply { isAccessible = true }, gated = false) { chain ->
                chain.proceed().also {
                    val ev = chain.thisObject as? View ?: return@also
                    if (inputStyled()) {
                        runCatching {
                            ev.set("i", 0)
                            ev.set("m", 0)
                            ev.set("D", 0)
                        }
                    }
                }
            }
        }
    }
    edgeTransparent.declaredMethods.firstOrNull { it.name == "drawChild" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            val ev = chain.thisObject as? View
            if (ev != null && (inputStyled())) {
                runCatching {
                    if ((ev.get("i") as? Int ?: 0) != 0) ev.set("i", 0)
                    if ((ev.get("m") as? Int ?: 0) != 0) ev.set("m", 0)
                    if ((ev.get("D") as? Int ?: 0) != 0) ev.set("D", 0)
                }
            }
            chain.proceed()
        }
    }
    edgeTransparent.declaredMethods.firstOrNull { it.name == "onInterceptTouchEvent" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            val ev = chain.thisObject as? View
            if (ev != null && (inputStyled())) {
                runCatching {
                    if ((ev.get("i") as? Int ?: 0) != 0) ev.set("i", 0)
                    if ((ev.get("m") as? Int ?: 0) != 0) ev.set("m", 0)
                }
            }
            chain.proceed()
        }
    }
    val msgListGroupCls = cls("com.tencent.mobileqq.aio.widget.AIOMsgListGroup")
    val groupEdgeField = runCatching { msgListGroupCls.getDeclaredField("h").apply { isAccessible = true } }.getOrNull()
    val groupRefreshField = runCatching { msgListGroupCls.getDeclaredField("i").apply { isAccessible = true } }.getOrNull()
    fun findRecyclerInTree(root: ViewGroup): ViewGroup? {
        for (i in 0 until root.childCount) {
            val ch = root.getChildAt(i) as? ViewGroup ?: continue
            if (generateSequence<Class<*>>(ch.javaClass) { it.superclass }.any { it.name == "androidx.recyclerview.widget.RecyclerView" }) {
                return ch
            }
            findRecyclerInTree(ch)?.let { return it }
        }
        return null
    }
    msgListGroupCls.declaredMethods.firstOrNull { it.name == "onMeasure" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            val group = chain.thisObject as? ViewGroup
            if (group != null) {
                msgListUnderScrolls[group]?.sync() ?: run {
                    val edge = (runCatching { groupEdgeField?.get(group) as? ViewGroup }.getOrNull())
                        ?: (0 until group.childCount).map(group::getChildAt).filterIsInstance<ViewGroup>().firstOrNull { edgeTransparent.isInstance(it) }
                    val refresh = (runCatching { groupRefreshField?.get(group) as? ViewGroup }.getOrNull())
                    val rv = findRecyclerInTree(edge ?: group)
                    if (rv != null) msgListUnderScrolls.getOrPut(group) { MsgListUnderScroll(group, edge, refresh, rv) }.sync()
                }
            }
            chain.proceed()
        }
    }
    val msgListVB = cls("com.tencent.mobileqq.aio.msglist.AIOMsgListVB")
    val absMsgListVB = cls("com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.core.AbsMsgListVB")
    val msgListGroupGetter = msgListVB.getDeclaredMethod("M2").apply { isAccessible = true }
    val msgListRecyclerGetter = msgListVB.getDeclaredMethod("S2").apply { isAccessible = true }
    val msgListRefreshGetter = absMsgListVB.getDeclaredMethod("m1").apply { isAccessible = true }
    fun syncMsgListPadding(vb: Any?) {
        val target = vb ?: return
        val group = runCatching { msgListGroupGetter.invoke(target) as? ViewGroup }.getOrNull() ?: return
        val recycler = runCatching { msgListRecyclerGetter.invoke(target) as? ViewGroup }.getOrNull()
            ?: findRecyclerInTree(group) ?: return
        val refresh = runCatching { msgListRefreshGetter.invoke(target) as? ViewGroup }.getOrNull()
            ?: (recycler.parent as? ViewGroup)?.takeIf { it !== group && !edgeTransparent.isInstance(it) }
        val edge = (runCatching { groupEdgeField?.get(group) as? ViewGroup }.getOrNull())
            ?: (refresh?.parent as? ViewGroup)?.takeIf { it !== group }
            ?: (recycler.parent as? ViewGroup)?.takeIf { it !== group && it !== refresh }
        msgListUnderScrolls.getOrPut(group) { MsgListUnderScroll(group, edge, refresh, recycler) }.sync()
    }
    for (name in listOf("U0", "v0", "S0", "K3", "L3", "M3", "T3", "I3", "G3", "H3", "N3", "x3")) {
        hookAll(msgListVB, msgListVB::isInstance, name) { syncMsgListPadding(it) }
    }
    val chatBgApi = cls("com.tencent.qqnt.aio.adapter.api.impl.AIOChatBackgroundApiImpl")
    chatBgApi.declaredMethods.firstOrNull { it.name == "setImmersiveBottomDrawable" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (inputStyled()) {
                val vg = chain.args.getOrNull(0) as? ViewGroup
                chain.proceed(arrayOf(vg, android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)))
            } else chain.proceed()
        }
    }
    val quiImmersive = cls("com.tencent.mobileqq.qui.b")
    quiImmersive.declaredMethods.firstOrNull { it.name == "g" && it.parameterTypes.size == 3 }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (inputStyled()) {
                val view = chain.args.getOrNull(0) as? View
                val top = (chain.args.getOrNull(1) as? Number)?.toInt() ?: 0
                chain.proceed(arrayOf(view, top, 0))
            } else chain.proceed()
        }
    }
    val rfwImmersive = cls("com.tencent.richframework.compat.immersive.RFWNavigationBarImmersiveHelper")
    rfwImmersive.declaredMethods.firstOrNull { it.name == "fillNavigationBarBackgroundView" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (inputStyled()) null else chain.proceed()
        }
    }
    val shortcutBar = cls("com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB")
    val getShortcutStrip = shortcutBar.getDeclaredMethod("j1")
    fun syncShortcutBar(vb: Any?) {
        val target = vb ?: return
        (runCatching { getShortcutStrip.invoke(target) as? ViewGroup }.getOrNull())?.let { strip ->
            generateSequence(strip as View?) { it.parent as? View }.take(3).forEach { decorate(it, pillOnly = true) }
            tgInput(strip)
        }
    }
    for (name in listOf("onCreateView", "handleUIState", "x1", "d1", "s1", "u1", "v1", "w1", "y1", "z1")) {
        val m = shortcutBar.declaredMethods.firstOrNull { it.name == name } ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val target = chain.thisObject?.takeIf(shortcutBar::isInstance) ?: chain.args.firstOrNull(shortcutBar::isInstance)
                if (target != null) syncShortcutBar(target)
            }
        }
    }
    val panelStrip = cls("com.tencent.qqnt.aio.shortcutbar.PanelIconLinearLayout")
    panelStrip.declaredMethods.firstOrNull { it.name == "f" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                (chain.thisObject as? ViewGroup)?.let { strip ->
                    val dec = decorate(strip, false, pillOnly = true)
                    tgInput(strip)
                }
            }
        }
    }
    for (panelName in listOf(
        "com.tencent.mobileqq.aio.panel.emoticon.c",
        "com.tencent.mobileqq.aio.panel.photo.PhotoPanelVB",
        "com.tencent.mobileqq.aio.panel.b",
    )) {
        val panelCls = cls(panelName)
        hook(panelCls.getDeclaredMethod("onCreateView", helpParam), gated = false) { chain ->
            chain.proceed().also { res ->
                (res as? View)?.let { root ->
                    panelGlasses.getOrPut(root) { PanelGlassState(root) }.sync()
                }
            }
        }
    }
}

/**
 * 就近声明类的**全部**同名方法挂上钩子。原来取第一个再 `?: continue`：方法表顺序不保证，
 * 重载也不止一个，于是挂到哪个随机、漏掉的落点还彻底静默。父类只在子类没有时兜底，
 * 免得子类已经 override 又把父实现一起挂上，同一次事件 sync 两遍。
 */
private fun hookAll(type: Class<*>, isTarget: (Any) -> Boolean, name: String, body: (Any) -> Unit) {
    val props = generateSequence(type) { it.superclass }
        .firstNotNullOfOrNull { c -> c.declaredMethods.filter { it.name == name }.takeIf { it.isNotEmpty() } }
    if (props == null) { log("钩子落点缺失：${type.simpleName}#$name"); return }
    for (m in props) hook(m.apply { isAccessible = true }, gated = false) { chain ->
        chain.proceed().also { val t = chain.thisObject?.takeIf(isTarget) ?: chain.args.firstOrNull(isTarget); if (t != null) body(t) }
    }
}

/** 兼容旧布局，但不再从任意祖先搜索第一个编辑器，更不强制把用户关闭的开关改回开启。 */
fun tgInput(strip: ViewGroup) {
    fun findEdit(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findEdit(view.getChildAt(i))?.let { return it }
        return null
    }
    val parent = strip.parent as? ViewGroup ?: return
    val row = (0 until parent.childCount).map(parent::getChildAt)
        .firstOrNull { it.javaClass.name.endsWith(".InputLinearLayout") }
    if (row != null) {
        findEdit(row)?.let { tgInput(it, strip) }
        return
    }
    // QQNT 的 AIOShortcutBarVB 与 AIOInputExpandableVB 同属底部输入容器：直接挂入当前活动的 NativeInput。
    liveInputs().firstOrNull()?.let {
        it.attachStrip(strip)
        it.sync()
    }
}

fun tgInput(edit: EditText, strip: ViewGroup? = null, sendBtn: View? = null) {
    inputs[edit]?.get()?.let {
        if (strip != null) it.attachStrip(strip)
        if (sendBtn != null) it.attachSendButton(sendBtn)
        it.sync()
        return
    }
    inputs[edit] = WeakReference(NativeInput(edit, strip, sendBtn).also { it.sync() })
}

fun refreshInputLayout() {
    val enabled = on("输入栏药丸")
    inputDecorations.values.toList().forEach { if (enabled) it.apply() else it.restore() }
    msgListUnderScrolls.values.toList().forEach { it.sync() }
    panelGlasses.values.toList().forEach { it.sync() }
    liveInputs().forEach { it.sync() }
}

/** 只分配现有按钮之外的剩余空隙，不改按钮宽度/weight，不挤掉原生触摸区。 */
internal fun inputButtonGap(available: Int, occupied: Int, count: Int, desired: Int): Int =
    if (count < 2) 0 else minOf(desired, ((available - occupied) / (count - 1)).coerceAtLeast(0))

/** 开关开着就套上、关着就还原。原来这九处各写一遍三元，条件还各不相同。 */
private fun InputDecoration.sync(on: Boolean) = if (on) apply() else restore()

/** 取（或建）这个 View 的装饰账并按开关节流。十一处调用点原来都是「getOrPut 再三元」两行。 */
private fun decorate(v: View, hide: Boolean = false, pillOnly: Boolean = false) {
    inputDecorations.getOrPut(v) { InputDecoration(v, hide) }.sync(if (pillOnly) on("输入栏药丸") else inputStyled())
}


private class InputDecoration(view: View, private val hide: Boolean = false) {
    private val view = WeakReference(view)
    private val background = view.background
    private val foreground = view.foreground
    private val visibility = view.visibility
    // 记下「这一路自己动过没有」。restore 只还自己动过的那几项，坐标、着色同理。
    private var cleared = false
    private var hidden = false
    fun apply() { view.get()?.let {
        if (hide) { if (it.visibility == View.VISIBLE) { it.visibility = View.INVISIBLE; hidden = true } }
        else {
            if (it.background != null) { it.background = null; cleared = true }
            if (it.foreground != null) { it.foreground = null; cleared = true }
        }
    } }
    fun restore() {
        if (!cleared && !hidden) return
        view.get()?.let {
            if (cleared) { it.background = background; it.foreground = foreground; cleared = false }
            if (hidden) { it.visibility = visibility; hidden = false }
        }
    }
}

private class SendButtonPill(view: View) {
    private val view = WeakReference(view)
    private val outline = view.outlineProvider
    private val clip = view.clipToOutline
    private val origTextColor = (view as? android.widget.TextView)?.currentTextColor
    private val shape = capsule()
    private val origRadii = WeakHashMap<Any, Float>()
    private var lastAppliedHeight = -1
    private var lastAppliedBg: android.graphics.drawable.Drawable? = null
    fun apply() {
        val v = view.get() ?: return
        var dirty = false
        if (v.outlineProvider !== shape) { v.outlineProvider = shape; dirty = true }
        if (!v.clipToOutline) { v.clipToOutline = true; dirty = true }
        if (v.height != lastAppliedHeight || v.background !== lastAppliedBg) {
            lastAppliedHeight = v.height
            lastAppliedBg = v.background
            val targetRadius = if (v.height > 0) v.height / 2f else 18f * v.dp
            updateSmoothRadii(v.background, targetRadius)
            dirty = true
        }
        if (v is android.widget.TextView && night()) {
            if (v.currentTextColor != 0xFFFFFFFF.toInt()) { v.setTextColor(0xFFFFFFFF.toInt()); dirty = true }
        }
        if (dirty) {
            v.invalidateOutline()
            v.invalidate()
        }
    }
    fun restore() {
        val v = view.get() ?: return
        lastAppliedHeight = -1
        lastAppliedBg = null
        v.outlineProvider = outline
        v.clipToOutline = clip
        if (v is android.widget.TextView && origTextColor != null) v.setTextColor(origTextColor)
        for ((drawable, orig) in origRadii.entries.toList()) {
            if (drawable is android.graphics.drawable.GradientDrawable) {
                drawable.cornerRadius = orig
            } else {
                runCatching { setRadiusMethod?.invoke(drawable, orig) }
            }
        }
        origRadii.clear()
        v.invalidateOutline()
        v.invalidate()
    }
    private fun updateSmoothRadii(drawable: android.graphics.drawable.Drawable?, radius: Float) {
        if (drawable == null) return
        if (drawable is android.graphics.drawable.GradientDrawable) {
            if (!origRadii.containsKey(drawable)) origRadii[drawable] = drawable.cornerRadius
            if (drawable.cornerRadius != radius) drawable.cornerRadius = radius
            return
        }
        val cls = smoothClass
        if (cls != null && cls.isInstance(drawable)) {
            if (!origRadii.containsKey(drawable)) {
                val orig = runCatching { (getRadiusMethod?.invoke(drawable) as? Number)?.toFloat() }.getOrNull() ?: (4f * (view.get()?.dp ?: 3f))
                origRadii[drawable] = orig
            }
            runCatching { setRadiusMethod?.invoke(drawable, radius) }
            return
        }
        if (drawable is android.graphics.drawable.LayerDrawable) {
            for (i in 0 until drawable.numberOfLayers) updateSmoothRadii(drawable.getDrawable(i), radius)
        }
        val state = drawable.constantState as? android.graphics.drawable.DrawableContainer.DrawableContainerState
        if (state != null) {
            state.children?.forEach { child -> updateSmoothRadii(child, radius) }
        }
    }
    companion object {
        private var smoothClass: Class<*>? = null
        private var getRadiusMethod: java.lang.reflect.Method? = null
        private var setRadiusMethod: java.lang.reflect.Method? = null
        fun initSmoothCorner(cls: Class<*>, getter: java.lang.reflect.Method, setter: java.lang.reflect.Method) {
            smoothClass = cls
            getRadiusMethod = getter.apply { isAccessible = true }
            setRadiusMethod = setter.apply { isAccessible = true }
        }
    }
}

private class ButtonMargin(view: View, val params: ViewGroup.MarginLayoutParams) {
    private val view = WeakReference(view)
    private val start = params.marginStart
    private val end = params.marginEnd
    fun apply(gap: Int) {
        val view = view.get() ?: return
        if (view.layoutParams !== params || params.marginStart == gap && params.marginEnd == 0) return
        params.marginStart = gap; params.marginEnd = 0; view.layoutParams = params
    }
    fun restore() { view.get()?.let { if (it.layoutParams === params) {
        params.marginStart = start; params.marginEnd = end; it.layoutParams = params
    } } }
}

private class NativeInput(edit: EditText, strip: ViewGroup?, sendBtn: View? = null) :
    ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
    private var strip = WeakReference(strip)
    private val edit = WeakReference(edit)
    private val background = edit.background
    private val outline = edit.outlineProvider
    private val clip = edit.clipToOutline
    private val origPadding = intArrayOf(edit.paddingLeft, edit.paddingTop, edit.paddingRight, edit.paddingBottom)
    private val origParentPadding = (edit.parent as? View)?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    private val origGrandPadding = ((edit.parent as? View)?.parent as? View)?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    /** repositionSafeArea 会把这三层实色底抹掉，原样留着，关开关时还回去，否则输入区变成透明窟窿。 */
    private val origBackgrounds = generateSequence(edit.parent as View?) { (it as? View)?.parent as? View }
        .take(3).map { it.background }.toList()
    private val origTextColor = edit.currentTextColor
    private val origHintColor = edit.currentHintTextColor
    private val originalTranslationZ = edit.translationZ
    private val glass = Glass(edit, Float.MAX_VALUE, kernelSurface = true)
    private val shape = capsule()
    private val margins = WeakHashMap<View, ButtonMargin>()
    /** 键在表里就表示「着色被这一路改过」，值可以是 null（原本就没着色）。一张表顶原来两张。 */
    private val iconTints = WeakHashMap<android.widget.ImageView, android.content.res.ColorStateList?>()
    private val decorations = ArrayList<InputDecoration>()
    private var sendPill: SendButtonPill? = null
    private var applied = false
    private var focused = edit.hasFocus()
    private var focusAnimator: ValueAnimator? = null
    init {
        // 真包布局 r/i/{ez,k_...}.xml：InputLinearLayout 和图标带各有一层实色背景。
        strip?.let {
            decorations.add(InputDecoration(it))
            (it.parent as? View)?.let { p -> decorations.add(InputDecoration(p)) }
        }
        // NT dxg.xml：编辑器父层 inputEditRoot (0x7f0a2b87)、inputRoot (0x7f0a2b9a) 与外层容器均自带不透明背景。
        if (edit.javaClass.name == "com.tencent.mobileqq.aio.input.edit.AIOEditText") {
            (edit.parent as? ViewGroup)?.let { parent ->
                // 往上三层，前两层无条件，第三层只认这三种容器。
                generateSequence(parent as View?) { (it as? View)?.parent as? View }.take(3).forEachIndexed { i, v ->
                    if (i < 2 || v.id == 0x7f0a2b81 || v.javaClass.name.endsWith("ConstraintLayout") || v.javaClass.name.endsWith("LinearLayout")) {
                        decorations.add(InputDecoration(v))
                    }
                }
                if (sendBtn == null) {
                    val foundSend = (0 until parent.childCount).map(parent::getChildAt)
                        .firstOrNull { it.javaClass.name == "com.tencent.mobileqq.aio.input.AIOInputSendBtn" || it.id == 0x7f0a61c0 || it.id == 0x7f0a6298 }
                        ?: ((parent.parent as? ViewGroup)?.findViewById(0x7f0a6298))
                    foundSend?.let { sendPill = SendButtonPill(it) }
                }
            }
        }
        if (sendBtn != null) sendPill = SendButtonPill(sendBtn)
        val row = generateSequence(edit.parent as? ViewGroup) { it.parent as? ViewGroup }
            .take(4).firstOrNull { it.javaClass.name.endsWith(".InputLinearLayout") }
        if (row != null) {
            generateSequence(edit.parent as? ViewGroup) { it.parent as? ViewGroup }
                .takeWhile { it !== row.parent }.forEach { decorations.add(InputDecoration(it)) }
            // 只处理明确与输入行对齐的 QQBlurView；不清整页聊天背景，不碰内容/回复面板。
            (row.parent as? ViewGroup)?.let { parent -> for (i in 0 until parent.childCount) {
                val view = parent.getChildAt(i)
                val lp = view.layoutParams as? RelativeLayout.LayoutParams ?: continue
                if (view.javaClass.name.endsWith(".QQBlurView") && row.id != View.NO_ID &&
                    lp.rules[RelativeLayout.ALIGN_TOP] == row.id && lp.rules[RelativeLayout.ALIGN_PARENT_BOTTOM] != 0)
                    decorations.add(InputDecoration(view, true))
            } }
        }
        edit.viewTreeObserver.addOnPreDrawListener(this)
        edit.viewTreeObserver.addOnGlobalLayoutListener(this)
        edit.addOnAttachStateChangeListener(this)
    }
    fun attachStrip(view: ViewGroup) {
        if (strip.get() !== view) {
            strip = WeakReference(view)
            decorations.add(InputDecoration(view))
            (view.parent as? View)?.let { p -> decorations.add(InputDecoration(p)) }
        }
    }
    fun attachSendButton(view: View) {
        if (sendPill == null) sendPill = SendButtonPill(view)
    }
    private fun brightenIconsIn(group: ViewGroup?, dark: Boolean) {
        if (group == null) return
        val brightTint = android.content.res.ColorStateList.valueOf(0xFFE8EEF7.toInt())
        fun walk(v: View) {
            if (v is android.widget.ImageView) {
                if (!iconTints.containsKey(v)) iconTints[v] = v.imageTintList
                val want = if (dark) brightTint else iconTints[v]
                if (v.imageTintList != want) v.imageTintList = want
            } else if (v is ViewGroup && (v as View) !== edit.get()) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let(::walk)
            }
        }
        walk(group)
    }
    private fun repositionSafeArea(edit: EditText) {
        val dp = edit.dp
        // 去掉底部实色安全区背景后重定位输入行与聊天列表，使胶囊输入框、发送按钮和底部快捷栏悬浮在滚动消息流之上并留出底部安全间距。
        val parent = edit.parent as? ViewGroup
        if (parent != null) {
            if (parent.background != null) parent.background = null
            val padH = (8 * dp).toInt()
            val padV = (4 * dp).toInt()
            val pl = maxOf(origParentPadding?.get(0) ?: 0, padH)
            val pt = maxOf(origParentPadding?.get(1) ?: 0, padV)
            val pr = maxOf(origParentPadding?.get(2) ?: 0, padH)
            val pb = maxOf(origParentPadding?.get(3) ?: 0, padV)
            if (parent.paddingLeft != pl || parent.paddingTop != pt || parent.paddingRight != pr || parent.paddingBottom != pb) {
                parent.setPadding(pl, pt, pr, pb)
            }
            (parent.parent as? ViewGroup)?.let { inputRoot ->
                if (inputRoot.background != null) inputRoot.background = null
                (inputRoot.parent as? ViewGroup)?.let { outer -> if (outer.background != null) outer.background = null }
                val stripView = strip.get()
                val shortcutHidden = on("输入区按导航栏留白") || stripView == null || stripView.visibility != View.VISIBLE || stripView.height <= 0
                val navBottom = runCatching {
                    edit.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
                }.getOrDefault(0)
                val imeBottom = runCatching {
                    edit.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                }.getOrDefault(0)
                val safeBottom = if (imeBottom > 0) (4 * dp).toInt()
                else if (shortcutHidden) maxOf(navBottom, (8 * dp).toInt())
                else (origGrandPadding?.get(3) ?: 0)
                val gpl = origGrandPadding?.get(0) ?: inputRoot.paddingLeft
                val gpt = origGrandPadding?.get(1) ?: inputRoot.paddingTop
                val gpr = origGrandPadding?.get(2) ?: inputRoot.paddingRight
                if (inputRoot.paddingBottom != safeBottom) {
                    inputRoot.setPadding(gpl, gpt, gpr, safeBottom)
                }
            }
        }
        msgListUnderScrolls.values.toList().forEach { it.sync() }
    }
    fun sync() {
        val edit = edit.get() ?: return
        if (!on("输入栏药丸")) { restore(); return }
        decorations.forEach { it.apply() }
        if (edit.background !== glass) edit.background = glass
        if (edit.outlineProvider !== shape) edit.outlineProvider = shape
        if (!edit.clipToOutline) edit.clipToOutline = true
        val minPadH = (14 * edit.dp).toInt()
        val padL = maxOf(origPadding[0], minPadH)
        val padR = maxOf(origPadding[2], minPadH)
        if (edit.paddingLeft != padL || edit.paddingRight != padR) {
            edit.setPadding(padL, origPadding[1], padR, origPadding[3])
        }
        val dark = night()
        if (dark) {
            val wantText = 0xFFF5F7FA.toInt()
            val wantHint = 0xFFA6B0C0.toInt()
            if (edit.currentTextColor != wantText) edit.setTextColor(wantText)
            if (edit.currentHintTextColor != wantHint) edit.setHintTextColor(wantHint)
        }
        brightenIconsIn((edit.parent as? ViewGroup)?.parent as? ViewGroup ?: edit.parent as? ViewGroup, dark)
        brightenIconsIn(strip.get(), dark)
        repositionSafeArea(edit)
        sendPill?.apply()
        glass.sync()
        val focus = edit.hasFocus()
        if (!applied || focused != focus) {
            focused = focus; focusAnimator?.cancel()
            val target = originalTranslationZ + if (focus) 2 * edit.dp else 0f
            if (!ValueAnimator.areAnimatorsEnabled()) edit.translationZ = target
            else focusAnimator = ValueAnimator.ofFloat(edit.translationZ, target).apply {
                duration = 140
                addUpdateListener { this@NativeInput.edit.get()?.translationZ = it.animatedValue as Float }
                start()
            }
        }
        if (!applied) { applied = true; onGlobalLayout() }
    }
    /** 量一次浮层上沿并让消息列表重新让位；位置没变就不碰列表，避免每帧触发重排。 */
    private fun syncBottomOverlay(edit: EditText) {
        val rootView = edit.rootView ?: return
        // 只记「浮层从哪一行开始」，不再把键盘开合折成一个 0：键盘弹出时浮层自己上移，
        // 消息区那边按同一套窗口坐标重新量重叠，该让多少就是多少。
        val top = overlayTopInWindowPx(rootView, edit)
        if (top <= 0 || top == aioInputTopY) return
        aioInputTopY = top
        msgListUnderScrolls.values.toList().forEach { it.sync() }
    }
    override fun onGlobalLayout() {
        if (!on("输入栏药丸")) return
        edit.get()?.let {
            repositionSafeArea(it)
            syncBottomOverlay(it)
            val focus = it.hasFocus()
            if (focused != focus) sync()
        }
        sendPill?.apply()
        val strip = strip.get() ?: return
        if (strip.width <= 0) return
        val children = (0 until strip.childCount).map(strip::getChildAt).filter { it.visibility == View.VISIBLE }
        if (children.any { it.width <= 0 }) return
        val gap = inputButtonGap(strip.width - strip.paddingLeft - strip.paddingRight,
            children.sumOf { it.width }, children.size, (8 * strip.dp).toInt())
        children.forEachIndexed { index, view ->
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEachIndexed
            val state = margins[view]?.takeIf { it.params === lp } ?: ButtonMargin(view, lp).also { margins[view] = it }
            state.apply(if (index == 0) 0 else gap)
        }
    }
    private fun restore() {
        if (!applied) return
        focusAnimator?.cancel(); focusAnimator = null
        if (aioInputTopY != 0) {
            aioInputTopY = 0
            msgListUnderScrolls.values.toList().forEach { it.sync() }
        }
        edit.get()?.let {
            // 三层父容器：背景与 padding 一起还，两件事原来写在两个地方，容易漏一边。
            val pads = listOf(origParentPadding, origGrandPadding)
            generateSequence(it.parent as View?) { v -> v.parent as? View }.take(3).toList()
                .forEachIndexed { i, v ->
                    v.background = origBackgrounds.getOrNull(i)
                    pads.getOrNull(i)?.let { pad -> v.setPadding(pad[0], pad[1], pad[2], pad[3]) }
                }
            it.background = background; it.outlineProvider = outline; it.clipToOutline = clip
            it.setPadding(origPadding[0], origPadding[1], origPadding[2], origPadding[3])
            it.setTextColor(origTextColor)
            it.setHintTextColor(origHintColor)
            it.translationZ = originalTranslationZ
        }
        iconTints.forEach { (iv, tint) -> iv.imageTintList = tint }
        iconTints.clear()
        msgListUnderScrolls.values.toList().forEach { it.sync() }
        sendPill?.restore()
        decorations.forEach { it.restore() }
        margins.values.forEach { it.restore() }; margins.clear()
        applied = false
    }
    override fun onPreDraw(): Boolean {
        if (on("输入栏药丸")) glass.sync()
        return true
    }
    /**
     * 两个监听挂在 ViewTreeObserver 上，脱离就摘、挂回就补；AttachState 那条**只能在 dispose 摘**，
     * 因为它是唯一能把本实例叫醒的通知，先摘掉就永远收不到「挂回」，药丸、玻璃、图标着色定格在脱离那一刻。
     */
    /** 同 MsgListUnderScroll：记 observer 本体，换代才能重挂。 */
    private var watchingOn: ViewTreeObserver? = null
    private fun watch(start: Boolean) {
        val v = edit.get()?.viewTreeObserver ?: return
        if (!v.isAlive) return
        val old = watchingOn
        if (start && old === v) return
        old?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(this); it.removeOnGlobalLayoutListener(this) }
        if (start) { v.addOnPreDrawListener(this); v.addOnGlobalLayoutListener(this); watchingOn = v } else watchingOn = null
    }
    fun detach() { restore(); watch(false) }
    override fun onViewAttachedToWindow(view: View) { watch(true); sync() }
    override fun onViewDetachedFromWindow(view: View) = detach()
    fun dispose() {
        detach(); glass.dispose()
        edit.get()?.let { it.removeOnAttachStateChangeListener(this); inputs.remove(it) }
    }
}

fun resetInputLayout() {
    inputDecorations.values.toList().forEach { it.restore() }; inputDecorations.clear()
    msgListUnderScrolls.values.toList().forEach { it.restore() }; msgListUnderScrolls.clear()
    panelGlasses.values.toList().forEach { it.dispose() }; panelGlasses.clear()
    liveInputs().forEach { it.dispose() }; inputs.clear()
}
