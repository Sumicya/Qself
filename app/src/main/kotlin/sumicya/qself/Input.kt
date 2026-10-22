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

private class MsgListUnderScroll(val group: ViewGroup, val recycler: ViewGroup) {
    private var savedGroupTop = group.paddingTop
    private var savedGroupBottom = group.paddingBottom
    private val origGroupClipToPad = group.clipToPadding
    private val origGroupClipChildren = group.clipChildren
    private val origRecyclerClipToPad = recycler.clipToPadding
    private val origRecyclerTop = recycler.paddingTop
    private val origRecyclerBottom = recycler.paddingBottom
    private var active = false
    fun sync() {
        val enabled = on("TG输入栏") || on("玻璃底栏")
        if (!enabled) {
            restore()
            return
        }
        val gt = group.paddingTop
        val gb = group.paddingBottom
        if (gt > 0) savedGroupTop = gt
        if (gb > 0) savedGroupBottom = gb
        active = true
        if (group.clipToPadding) group.clipToPadding = false
        if (group.clipChildren) group.clipChildren = false
        if (group.background != null) group.background = null
        if (gt != 0 || gb != 0) {
            group.setPadding(group.paddingLeft, 0, group.paddingRight, 0)
        }
        if (recycler.clipToPadding) recycler.clipToPadding = false
        if (recycler.background != null) recycler.background = null
        val topReduction = (10 * group.dp).toInt()
        val targetTop = if (on("玻璃底栏")) (savedGroupTop - topReduction).coerceAtLeast(0) else origRecyclerTop
        val targetBottom = if (on("TG输入栏")) savedGroupBottom else origRecyclerBottom
        if (recycler.paddingTop != targetTop || recycler.paddingBottom != targetBottom) {
            recycler.setPadding(recycler.paddingLeft, targetTop, recycler.paddingRight, targetBottom)
        }
    }
    fun restore() {
        if (!active) return
        active = false
        group.clipToPadding = origGroupClipToPad
        group.clipChildren = origGroupClipChildren
        group.setPadding(group.paddingLeft, savedGroupTop, group.paddingRight, savedGroupBottom)
        recycler.clipToPadding = origRecyclerClipToPad
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
        if (!on("TG输入栏")) orig
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
    fun syncExpandable(vb: Any) {
        val bindingOrView = runCatching { expContainer.get(vb) }.getOrNull()
        val rootView = (bindingOrView as? View) ?: runCatching { bindingOrView?.get("a") as? View }.getOrNull()
        rootView?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, false) }
            if (on("TG输入栏")) dec.apply() else dec.restore()
        }
        for (fieldName in listOf("d", "f")) {
            (runCatching { bindingOrView?.get(fieldName) as? View }.getOrNull())?.let { v ->
                val dec = inputDecorations.getOrPut(v) { InputDecoration(v, false) }
                if (on("TG输入栏")) dec.apply() else dec.restore()
            }
        }
        (runCatching { expDivider.get(vb) as? View }.getOrNull())?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, true) }
            if (on("TG输入栏")) dec.apply() else dec.restore()
        }
        val ed = runCatching { bindingOrView?.get("c") as? EditText }.getOrNull()
        val send = runCatching { bindingOrView?.get("i") as? View }.getOrNull()
        if (ed != null) tgInput(ed, sendBtn = send)
    }
    for (name in listOf("onCreateView", "handleUIState", "o1", "p1", "f1")) {
        val m = generateSequence<Class<*>>(expandable) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == name } }
            .firstOrNull() ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also { if (expandable.isInstance(chain.thisObject)) syncExpandable(chain.thisObject) }
        }
    }
    val bottomBlur = cls("com.tencent.mobileqq.aio.reserve1.blur.BottomBlurVB")
    val getBottomBlurI1 = runCatching { bottomBlur.getDeclaredMethod("i1").apply { isAccessible = true } }.getOrNull()
    val getBottomBlurK1 = runCatching { bottomBlur.getDeclaredMethod("k1").apply { isAccessible = true } }.getOrNull()
    fun syncBottomBlur(vb: Any) {
        for (getter in listOfNotNull(getBottomBlurI1, getBottomBlurK1)) {
            (runCatching { getter.invoke(vb) as? View }.getOrNull())?.let { v ->
                val dec = inputDecorations.getOrPut(v) { InputDecoration(v, true) }
                if (on("TG输入栏")) dec.apply() else dec.restore()
            }
        }
    }
    for (name in listOf("onCreateView", "handleUIState", "h1")) {
        val m = generateSequence<Class<*>>(bottomBlur) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == name } }
            .firstOrNull() ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also { if (bottomBlur.isInstance(chain.thisObject)) syncBottomBlur(chain.thisObject) }
        }
    }
    val msgListVB = cls("com.tencent.mobileqq.aio.msglist.AIOMsgListVB")
    val msgListGroupGetter = msgListVB.getDeclaredMethod("M2").apply { isAccessible = true }
    val msgListRecyclerGetter = msgListVB.getDeclaredMethod("S2").apply { isAccessible = true }
    fun syncMsgListPadding(vb: Any) {
        val group = runCatching { msgListGroupGetter.invoke(vb) as? ViewGroup }.getOrNull() ?: return
        val recycler = runCatching { msgListRecyclerGetter.invoke(vb) as? ViewGroup }.getOrNull() ?: return
        msgListUnderScrolls.getOrPut(group) { MsgListUnderScroll(group, recycler) }.sync()
    }
    for (name in listOf("U0", "v0", "K3", "L3")) {
        val m = generateSequence<Class<*>>(msgListVB) { it.superclass }
            .mapNotNull { c -> c.declaredMethods.firstOrNull { it.name == name } }
            .firstOrNull() ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also { if (msgListVB.isInstance(chain.thisObject)) syncMsgListPadding(chain.thisObject) }
        }
    }
    val chatBgApi = cls("com.tencent.qqnt.aio.adapter.api.impl.AIOChatBackgroundApiImpl")
    chatBgApi.declaredMethods.firstOrNull { it.name == "setImmersiveBottomDrawable" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (on("TG输入栏") || on("玻璃底栏")) {
                val vg = chain.args.getOrNull(0) as? ViewGroup
                chain.proceed(arrayOf(vg, android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)))
            } else chain.proceed()
        }
    }
    val quiImmersive = cls("com.tencent.mobileqq.qui.b")
    quiImmersive.declaredMethods.firstOrNull { it.name == "g" && it.parameterTypes.size == 3 }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (on("TG输入栏") || on("玻璃底栏")) {
                val view = chain.args.getOrNull(0) as? View
                val top = (chain.args.getOrNull(1) as? Number)?.toInt() ?: 0
                chain.proceed(arrayOf(view, top, 0))
            } else chain.proceed()
        }
    }
    val rfwImmersive = cls("com.tencent.richframework.compat.immersive.RFWNavigationBarImmersiveHelper")
    rfwImmersive.declaredMethods.firstOrNull { it.name == "fillNavigationBarBackgroundView" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            if (on("TG输入栏") || on("玻璃底栏")) null else chain.proceed()
        }
    }
    val shortcutBar = cls("com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB")
    val getShortcutStrip = shortcutBar.getDeclaredMethod("j1")
    fun syncShortcutBar(vb: Any?) {
        val target = vb ?: return
        (runCatching { getShortcutStrip.invoke(target) as? ViewGroup }.getOrNull())?.let { strip ->
            val dec = inputDecorations.getOrPut(strip) { InputDecoration(strip, false) }
            if (on("TG输入栏")) dec.apply() else dec.restore()
            (strip.parent as? View)?.let { p ->
                val pDec = inputDecorations.getOrPut(p) { InputDecoration(p, false) }
                if (on("TG输入栏")) pDec.apply() else pDec.restore()
            }
            tgInput(strip)
        }
    }
    for (name in listOf("onCreateView", "handleUIState", "x1", "d1", "s1")) {
        val m = shortcutBar.declaredMethods.firstOrNull { it.name == name } ?: continue
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also { if (shortcutBar.isInstance(chain.thisObject)) syncShortcutBar(chain.thisObject) }
        }
    }
    shortcutBar.declaredMethods.firstOrNull { it.name == "y1" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                val vb = chain.args.getOrNull(0)?.takeIf { shortcutBar.isInstance(it) } ?: chain.thisObject?.takeIf { shortcutBar.isInstance(it) }
                syncShortcutBar(vb)
            }
        }
    }
    val panelStrip = cls("com.tencent.qqnt.aio.shortcutbar.PanelIconLinearLayout")
    panelStrip.declaredMethods.firstOrNull { it.name == "f" }?.let { m ->
        hook(m.apply { isAccessible = true }, gated = false) { chain ->
            chain.proceed().also {
                (chain.thisObject as? ViewGroup)?.let { strip ->
                    val dec = inputDecorations.getOrPut(strip) { InputDecoration(strip, false) }
                    if (on("TG输入栏")) dec.apply() else dec.restore()
                    tgInput(strip)
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
    val enabled = on("TG输入栏")
    inputDecorations.values.toList().forEach { if (enabled) it.apply() else it.restore() }
    msgListUnderScrolls.values.toList().forEach { it.sync() }
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
    fun apply() {
        val v = view.get() ?: return
        if (v.outlineProvider !== shape) v.outlineProvider = shape
        if (!v.clipToOutline) v.clipToOutline = true
        val targetRadius = if (v.height > 0) v.height / 2f else 18f * v.dp
        updateSmoothRadii(v.background, targetRadius)
        if (v is android.widget.TextView && night()) {
            if (v.currentTextColor != 0xFFFFFFFF.toInt()) v.setTextColor(0xFFFFFFFF.toInt())
        }
        v.invalidate()
    }
    fun restore() {
        val v = view.get() ?: return
        v.outlineProvider = outline
        v.clipToOutline = clip
        if (v is android.widget.TextView && origTextColor != null) v.setTextColor(origTextColor)
        for ((drawable, orig) in origRadii.entries.toList()) {
            runCatching { setRadiusMethod?.invoke(drawable, orig) }
        }
        origRadii.clear()
        v.invalidate()
    }
    private fun updateSmoothRadii(drawable: android.graphics.drawable.Drawable?, radius: Float) {
        if (drawable == null) return
        val cls = smoothClass
        if (cls != null && cls.isInstance(drawable)) {
            if (!origRadii.containsKey(drawable)) {
                val orig = runCatching { (getRadiusMethod?.invoke(drawable) as? Number)?.toFloat() }.getOrNull() ?: (4f * (view.get()?.dp ?: 3f))
                origRadii[drawable] = orig
            }
            runCatching { setRadiusMethod?.invoke(drawable, radius) }
            return
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
        val root = edit.rootView as? ViewGroup ?: return
        fun findRecyclers(v: View) {
            if (v is ViewGroup) {
                if (v.javaClass.name == "com.tencent.mobileqq.aio.widget.AIOMsgListGroup") {
                    val rv = (0 until v.childCount).map(v::getChildAt).filterIsInstance<ViewGroup>().firstOrNull { ch ->
                        generateSequence<Class<*>>(ch.javaClass) { it.superclass }.any { it.name == "androidx.recyclerview.widget.RecyclerView" }
                    }
                    if (rv != null) {
                        msgListUnderScrolls.getOrPut(v) { MsgListUnderScroll(v, rv) }.sync()
                        return
                    }
                }
                val isRv = generateSequence<Class<*>>(v.javaClass) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" }
                if (isRv) {
                    if (!savedRecyclerClips.containsKey(v)) savedRecyclerClips[v] = v.clipToPadding
                    if (v.clipToPadding) v.clipToPadding = false
                } else {
                    for (i in 0 until v.childCount) v.getChildAt(i)?.let(::findRecyclers)
                }
            }
        }
        findRecyclers(root)
    }
    fun sync() {
        val edit = edit.get() ?: return
        if (!on("TG输入栏")) { restore(); return }
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
    override fun onGlobalLayout() {
        if (!on("TG输入栏")) return
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
    override fun onPreDraw(): Boolean { sync(); return true }
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
    inputs.values.mapNotNull { it.get() }.forEach { it.dispose() }
    inputs.clear()
}
