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
private val inputs = WeakHashMap<EditText, WeakReference<NativeInput>>()
private val inputDecorations = WeakHashMap<View, InputDecoration>()
private val msgListUnderScrolls = WeakHashMap<ViewGroup, MsgListUnderScroll>()

/**
 * 底部浮层（输入药丸 + 发送按钮 + 快捷栏）在窗口里占掉的高度（px，从浮层最上沿算到窗口底）。
 *
 * 真包把输入区画在消息列表之上：安全区实色背景一去，消息列表底部就再没有任何让位，最后一条消息
 * 的卡片按钮和它下面的时间会直接压到输入栏、快捷栏底下，屏幕最下面那一行还被窗口边界切掉。
 * 这里量的是浮层自身高度，消息列表的下内边距取它和原生值的较大者。
 */
@Volatile
private var aioBottomOverlayPx = 0

/** 浮层最上沿到窗口底的距离；[root] 为窗口根视图（消息列表与输入区共用的那棵视图树）。 */
private fun bottomOverlayPx(root: View, anchor: View, dp: Float): Int {
    if (root.height <= 0 || dp <= 0f) return 0
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
    return (root.height - top + (6 * dp).toInt()).coerceIn(0, limit)
}
private val panelGlasses = WeakHashMap<View, PanelGlassState>()

private class PanelGlassState(val root: View) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val origBg = root.background
    private val glass = Glass(root, 20 * root.dp, kernelSurface = true)
    init {
        root.viewTreeObserver.addOnPreDrawListener(this)
        root.addOnAttachStateChangeListener(this)
    }
    fun sync() {
        if (!on("输入栏药丸") && !on("玻璃底栏")) {
            restore()
            return
        }
        if (root.background !== glass) root.background = glass
        glass.sync()
    }
    fun restore() {
        if (root.background === glass) root.background = origBg
    }
    fun dispose() {
        restore()
        glass.dispose()
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
        root.removeOnAttachStateChangeListener(this)
        panelGlasses.remove(root)
    }
    override fun onPreDraw(): Boolean {
        if (on("输入栏药丸") || on("玻璃底栏")) glass.sync()
        return true
    }
    override fun onViewAttachedToWindow(v: View) = sync()
    override fun onViewDetachedFromWindow(v: View) = dispose()
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
    private var watching = false
    /** 为「从 y=0 铺下来」自己加过的位移，关开关时按这个数还回去。 */
    private var shiftedBy = 0f

    /** 顶栏透明：消息区从窗口最上沿铺下来，滚动时从玻璃胶囊底下穿过；内边距取顶栏真实底边。 */
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (on("玻璃底栏") || on("输入栏药丸")) syncGeometry()
        true
    }

    fun sync() {
        val enabled = on("输入栏药丸") || on("玻璃底栏")
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
     * - 顶栏透明、三颗胶囊浮着，消息应该从窗口最上沿铺下来（滚动时从胶囊底下穿过）。
     *   真包里 QQ 只按标题栏高度把列表上移一次（`AIOMsgListGroup trans=-99`）+ 同样的 `paddingTop`，
     *   状态栏那 141px 收掉后容器高度不一定跟着缩，这里按实际屏幕位置兜底对到 y=0：
     *   先量「消息区顶部离窗口上沿还有多少」，再把这差值减掉。
     * - 顶部内边距直接取标题栏底边（日志实测 141+99 → 240；收起状态栏后 99），
     *   不再用「原内边距 - 去掉的安全区」这种容易和 QQ 自己的重排叠在一起的账。
     * - 底部内边距按消息区实际底边到输入浮层顶端的距离算，最后一行按钮和时间不会被输入栏吞掉。
     */
    private fun syncGeometry() {
        if (recycler.height <= 0) return
        val rootView = group.rootView ?: return
        val at = IntArray(2)
        if (on("玻璃底栏") && aioHeaderBottom() > 0) {
            group.getLocationInWindow(at)
            if (at[1] != 0) {
                group.translationY -= at[1].toFloat()
                shiftedBy += at[1].toFloat()
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
        val targetBottom = if (on("输入栏药丸") || on("玻璃底栏")) {
            // 输入浮层占掉的那段里，减去消息区自身已经盖住的部分，剩下的才是要留的内边距。
            val covered = (rootView.height - groupBottom).coerceAtLeast(0)
            maxOf(origRecyclerBottom, aioBottomOverlayPx - covered).coerceAtLeast(0)
        } else origRecyclerBottom
        lastAppliedTop = targetTop
        lastAppliedBottom = targetBottom
        if (recycler.paddingTop != targetTop || recycler.paddingBottom != targetBottom) {
            recycler.setPadding(recycler.paddingLeft, targetTop, recycler.paddingRight, targetBottom)
        }
    }

    private fun watch(start: Boolean) {
        if (start == watching) return
        val vto = group.viewTreeObserver
        if (!vto.isAlive) return
        if (start) vto.addOnPreDrawListener(preDraw) else vto.removeOnPreDrawListener(preDraw)
        watching = start
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
    val mviState = cls("com.tencent.mvi.base.mvi.MviUIState")
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
        val m = styleDelegate.declaredMethods.firstOrNull { it.name == name } ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also { syncCompatDelegate(chain.thisObject) }
        }
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
        rootView?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, false) }
            if (on("输入栏药丸")) dec.apply() else dec.restore()
            (v.parent as? View)?.let { p ->
                val pDec = inputDecorations.getOrPut(p) { InputDecoration(p, false) }
                if (on("输入栏药丸")) pDec.apply() else pDec.restore()
            }
        }
        for (fieldName in listOf("d", "f")) {
            (runCatching { bindingOrView?.get(fieldName) as? View }.getOrNull())?.let { v ->
                val dec = inputDecorations.getOrPut(v) { InputDecoration(v, false) }
                if (on("输入栏药丸")) dec.apply() else dec.restore()
            }
        }
        (runCatching { expDivider.get(target) as? View }.getOrNull())?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, true) }
            if (on("输入栏药丸")) dec.apply() else dec.restore()
        }
        val ed = runCatching { bindingOrView?.get("c") as? EditText }.getOrNull()
        val send = runCatching { bindingOrView?.get("i") as? View }.getOrNull()
        if (ed != null) tgInput(ed, sendBtn = send)
    }
    for (name in listOf("onCreateView", "handleUIState", "o1", "p1", "f1", "m1")) {
        val m = generateSequence<Class<*>>(expandable) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == name } }
            .firstOrNull() ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val target = chain.thisObject?.takeIf(expandable::isInstance) ?: chain.args.firstOrNull(expandable::isInstance)
                if (target != null) syncExpandable(target)
            }
        }
    }
    val topBlur = cls("com.tencent.mobileqq.aio.reserve1.blur.f")
    val topBlurBindingField = topBlur.getDeclaredField("d").apply { isAccessible = true }
    val topBlurViewField = cls("sm1.t").getDeclaredField("a").apply { isAccessible = true }
    fun syncTopBlur(vb: Any?) {
        val target = vb ?: return
        val binding = runCatching { topBlurBindingField.get(target) }.getOrNull() ?: return
        (runCatching { topBlurViewField.get(binding) as? View }.getOrNull())?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, true) }
            if (on("玻璃底栏") || on("输入栏药丸")) dec.apply() else dec.restore()
        }
    }
    for (name in listOf("onCreateView", "bindViewAndData", "handleUIState", "d1", "f1", "g1")) {
        val m = generateSequence<Class<*>>(topBlur) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == name } }
            .firstOrNull() ?: continue
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
    fun syncTopMask(vb: Any?) {
        val target = vb ?: return
        val binding = runCatching { topMaskBindingField.get(target) }.getOrNull() ?: return
        (runCatching { topMaskViewField.get(binding) as? View }.getOrNull())?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, true) }
            if (on("玻璃底栏") || on("输入栏药丸")) dec.apply() else dec.restore()
        }
    }
    for (name in listOf("onCreateView", "bindViewAndData", "handleUIState", "e1", "f1", "g1")) {
        val m = generateSequence<Class<*>>(topMask) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == name } }
            .firstOrNull() ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val target = chain.thisObject?.takeIf(topMask::isInstance) ?: chain.args.firstOrNull(topMask::isInstance)
                if (target != null) syncTopMask(target)
            }
        }
    }
    val bottomBlur = cls("com.tencent.mobileqq.aio.reserve1.blur.BottomBlurVB")
    val getBottomBlurI1 = runCatching { bottomBlur.getDeclaredMethod("i1").apply { isAccessible = true } }.getOrNull()
    val getBottomBlurK1 = runCatching { bottomBlur.getDeclaredMethod("k1").apply { isAccessible = true } }.getOrNull()
    fun syncBottomBlur(vb: Any?) {
        val target = vb ?: return
        for (getter in listOfNotNull(getBottomBlurI1, getBottomBlurK1)) {
            (runCatching { getter.invoke(target) as? View }.getOrNull())?.let { v ->
                val dec = inputDecorations.getOrPut(v) { InputDecoration(v, true) }
                if (on("输入栏药丸") || on("玻璃底栏")) dec.apply() else dec.restore()
            }
        }
    }
    for (name in listOf("onCreateView", "handleUIState", "h1", "j1", "l1", "n1", "e1", "f1", "m1", "o1")) {
        val m = generateSequence<Class<*>>(bottomBlur) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == name } }
            .firstOrNull() ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val target = chain.thisObject?.takeIf(bottomBlur::isInstance) ?: chain.args.firstOrNull(bottomBlur::isInstance)
                if (target != null) syncBottomBlur(target)
            }
        }
    }
    val edgeTransparent = cls("com.tencent.mobileqq.aio.widget.EdgeTransparentView")
    for (name in listOf("setTransparentSize", "setPositionAndDrawSize")) {
        edgeTransparent.declaredMethods.filter { it.name == name }.forEach { m ->
            hook(m.apply { isAccessible = true }, gated = false) { chain ->
                chain.proceed().also {
                    val ev = chain.thisObject as? View ?: return@also
                    if (on("输入栏药丸") || on("玻璃底栏")) {
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
            if (ev != null && (on("输入栏药丸") || on("玻璃底栏"))) {
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
            if (ev != null && (on("输入栏药丸") || on("玻璃底栏"))) {
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
        val m = generateSequence<Class<*>>(msgListVB) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == name } }
            .firstOrNull() ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val target = chain.thisObject?.takeIf(msgListVB::isInstance) ?: chain.args.firstOrNull(msgListVB::isInstance)
                if (target != null) syncMsgListPadding(target)
            }
        }
    }
    val chatBgApi = cls("com.tencent.qqnt.aio.adapter.api.impl.AIOChatBackgroundApiImpl")
    chatBgApi.declaredMethods.firstOrNull { it.name == "setImmersiveBottomDrawable" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (on("输入栏药丸") || on("玻璃底栏")) {
                val vg = chain.args.getOrNull(0) as? ViewGroup
                chain.proceed(arrayOf(vg, android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)))
            } else chain.proceed()
        }
    }
    val quiImmersive = cls("com.tencent.mobileqq.qui.b")
    quiImmersive.declaredMethods.firstOrNull { it.name == "g" && it.parameterTypes.size == 3 }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (on("输入栏药丸") || on("玻璃底栏")) {
                val view = chain.args.getOrNull(0) as? View
                val top = (chain.args.getOrNull(1) as? Number)?.toInt() ?: 0
                chain.proceed(arrayOf(view, top, 0))
            } else chain.proceed()
        }
    }
    val rfwImmersive = cls("com.tencent.richframework.compat.immersive.RFWNavigationBarImmersiveHelper")
    rfwImmersive.declaredMethods.firstOrNull { it.name == "fillNavigationBarBackgroundView" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (on("输入栏药丸") || on("玻璃底栏")) null else chain.proceed()
        }
    }
    val shortcutBar = cls("com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB")
    val getShortcutStrip = shortcutBar.getDeclaredMethod("j1")
    fun syncShortcutBar(vb: Any?) {
        val target = vb ?: return
        (runCatching { getShortcutStrip.invoke(target) as? ViewGroup }.getOrNull())?.let { strip ->
            val dec = inputDecorations.getOrPut(strip) { InputDecoration(strip, false) }
            if (on("输入栏药丸")) dec.apply() else dec.restore()
            (strip.parent as? View)?.let { p ->
                val pDec = inputDecorations.getOrPut(p) { InputDecoration(p, false) }
                if (on("输入栏药丸")) pDec.apply() else pDec.restore()
                (p.parent as? View)?.let { gp ->
                    val gpDec = inputDecorations.getOrPut(gp) { InputDecoration(gp, false) }
                    if (on("输入栏药丸")) gpDec.apply() else gpDec.restore()
                }
            }
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
                    val dec = inputDecorations.getOrPut(strip) { InputDecoration(strip, false) }
                    if (on("输入栏药丸")) dec.apply() else dec.restore()
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
    inputs.values.mapNotNull { it.get() }.firstOrNull()?.let {
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
    inputs.values.mapNotNull { it.get() }.forEach { it.sync() }
}

/** 只分配现有按钮之外的剩余空隙，不改按钮宽度/weight，不挤掉原生触摸区。 */
internal fun inputButtonGap(available: Int, occupied: Int, count: Int, desired: Int): Int =
    if (count < 2) 0 else minOf(desired, ((available - occupied) / (count - 1)).coerceAtLeast(0))

private class InputDecoration(view: View, private val hide: Boolean = false) {
    private val view = WeakReference(view)
    private val background = view.background
    private val foreground = view.foreground
    private val visibility = view.visibility
    fun apply() { view.get()?.let {
        if (hide) { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
        else { if (it.background != null) it.background = null; if (it.foreground != null) it.foreground = null }
    } }
    fun restore() { view.get()?.let { it.background = background; it.foreground = foreground; if (hide) it.visibility = visibility } }
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
    private val origTextColor = edit.currentTextColor
    private val origHintColor = edit.currentHintTextColor
    private val originalTranslationZ = edit.translationZ
    private val glass = Glass(edit, Float.MAX_VALUE, kernelSurface = true)
    private val shape = capsule()
    private val margins = WeakHashMap<View, ButtonMargin>()
    private val savedIconTints = WeakHashMap<android.widget.ImageView, android.content.res.ColorStateList?>()
    private val recordedIconTints = WeakHashMap<android.widget.ImageView, Boolean>()
    private val savedRecyclerClips = WeakHashMap<ViewGroup, Boolean>()
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
                decorations.add(InputDecoration(parent))
                (parent.parent as? ViewGroup)?.let { inputRoot ->
                    decorations.add(InputDecoration(inputRoot))
                    (inputRoot.parent as? ViewGroup)?.let { container ->
                        if (container.id == 0x7f0a2b81 || container.javaClass.name.contains("ConstraintLayout") || container.javaClass.name.contains("LinearLayout")) {
                            decorations.add(InputDecoration(container))
                        }
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
                if (recordedIconTints[v] != true) {
                    savedIconTints[v] = v.imageTintList
                    recordedIconTints[v] = true
                }
                if (dark) {
                    if (v.imageTintList != brightTint) v.imageTintList = brightTint
                } else if (recordedIconTints[v] == true) {
                    val orig = savedIconTints[v]
                    if (v.imageTintList != orig) v.imageTintList = orig
                }
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
                val shortcutHidden = on("快捷栏隐藏") || stripView == null || stripView.visibility != View.VISIBLE || stripView.height <= 0
                val navBottom = runCatching {
                    edit.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
                }.getOrDefault(0)
                val imeBottom = runCatching {
                    edit.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                }.getOrDefault(0)
                val safeBottom = if (imeBottom > 0) (4 * dp).toInt()
                else if (shortcutHidden) maxOf(navBottom, (6 * dp).toInt())
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
    /** 量一次底部浮层高度并让消息列表让位；高度没变就不碰列表，避免每帧触发重排。 */
    private fun syncBottomOverlay(edit: EditText) {
        val rootView = edit.rootView ?: return
        val ime = runCatching {
            edit.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
        }.getOrDefault(0)
        // 键盘弹出时列表跟着窗口一起缩，再补内边距会多留一大段空白。
        val need = if (ime > 0) 0 else bottomOverlayPx(rootView, edit, edit.dp)
        if (need == aioBottomOverlayPx) return
        aioBottomOverlayPx = need
        recordQselfEvent("输入浮层高度 ${need}px，消息列表底部让位")
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
        if (aioBottomOverlayPx != 0) {
            aioBottomOverlayPx = 0
            msgListUnderScrolls.values.toList().forEach { it.sync() }
        }
        edit.get()?.let {
            it.background = background; it.outlineProvider = outline; it.clipToOutline = clip
            it.setPadding(origPadding[0], origPadding[1], origPadding[2], origPadding[3])
            it.setTextColor(origTextColor)
            it.setHintTextColor(origHintColor)
            it.translationZ = originalTranslationZ
            (it.parent as? View)?.let { p ->
                origParentPadding?.let { pad -> p.setPadding(pad[0], pad[1], pad[2], pad[3]) }
                (p.parent as? View)?.let { gp ->
                    origGrandPadding?.let { gpad -> gp.setPadding(gpad[0], gpad[1], gpad[2], gpad[3]) }
                }
            }
        }
        for ((iv, _) in recordedIconTints) {
            iv.imageTintList = savedIconTints[iv]
        }
        savedIconTints.clear(); recordedIconTints.clear()
        for ((rv, clipPad) in savedRecyclerClips) {
            rv.clipToPadding = clipPad
        }
        savedRecyclerClips.clear()
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
    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = dispose()
    fun dispose() {
        restore(); glass.dispose()
        val view = edit.get() ?: return
        if (view.viewTreeObserver.isAlive) {
            view.viewTreeObserver.removeOnPreDrawListener(this)
            view.viewTreeObserver.removeOnGlobalLayoutListener(this)
        }
        view.removeOnAttachStateChangeListener(this)
        inputs.remove(view)
    }
}

fun resetInputLayout() {
    inputDecorations.values.toList().forEach { it.restore() }
    inputDecorations.clear()
    msgListUnderScrolls.values.toList().forEach { it.restore() }
    msgListUnderScrolls.clear()
    panelGlasses.values.toList().forEach { it.dispose() }
    panelGlasses.clear()
    inputs.values.mapNotNull { it.get() }.forEach { it.dispose() }
    inputs.clear()
}
