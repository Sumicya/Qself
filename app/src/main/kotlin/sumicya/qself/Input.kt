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
    hook(styleDelegate.getDeclaredMethod("handleUIState", mviState), gated = false) { chain ->
        chain.proceed().also {
            refreshInputLayout()
            runCatching { styleBinding.get(chain.thisObject) }.getOrNull()?.let { adorn ->
                val ed = runCatching { adornEdit.invoke(adorn) as? EditText }.getOrNull()
                    ?: runCatching { adorn.get("b") as? EditText }.getOrNull()
                val send = runCatching { adornSend.invoke(adorn) as? View }.getOrNull()
                ed?.let { tgInput(it, sendBtn = send) }
            }
        }
    }
    val expandable = cls("com.tencent.mobileqq.aio.input.AIOInputExpandableVB")
    val expContainer = expandable.getDeclaredField("e").apply { isAccessible = true }
    val expDivider = expandable.getDeclaredField("f").apply { isAccessible = true }
    val helpParam = cls("com.tencent.mvi.api.help.a")
    fun syncExpandable(vb: Any) {
        (runCatching { expContainer.get(vb) as? View }.getOrNull())?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, false) }
            if (on("TG输入栏")) dec.apply() else dec.restore()
        }
        (runCatching { expDivider.get(vb) as? View }.getOrNull())?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, true) }
            if (on("TG输入栏")) dec.apply() else dec.restore()
        }
    }
    hook(expandable.getDeclaredMethod("onCreateView", helpParam), gated = false) { chain ->
        chain.proceed().also { if (expandable.isInstance(chain.thisObject)) syncExpandable(chain.thisObject) }
    }
    hook(expandable.getDeclaredMethod("handleUIState", mviState), gated = false) { chain ->
        chain.proceed().also { if (expandable.isInstance(chain.thisObject)) syncExpandable(chain.thisObject) }
    }
    val bottomBlur = cls("com.tencent.mobileqq.aio.reserve1.blur.BottomBlurVB")
    val getBottomBlur = bottomBlur.getDeclaredMethod("i1")
    fun syncBottomBlur(vb: Any) {
        (runCatching { getBottomBlur.invoke(vb) as? View }.getOrNull())?.let { v ->
            val dec = inputDecorations.getOrPut(v) { InputDecoration(v, true) }
            if (on("TG输入栏")) dec.apply() else dec.restore()
        }
    }
    hook(bottomBlur.getDeclaredMethod("onCreateView", helpParam), gated = false) { chain ->
        chain.proceed().also { if (bottomBlur.isInstance(chain.thisObject)) syncBottomBlur(chain.thisObject) }
    }
    hook(bottomBlur.getDeclaredMethod("handleUIState", mviState), gated = false) { chain ->
        chain.proceed().also { if (bottomBlur.isInstance(chain.thisObject)) syncBottomBlur(chain.thisObject) }
    }
    val shortcutBar = cls("com.tencent.mobileqq.aio.shortcurtbar.AIOShortcutBarVB")
    val getShortcutStrip = shortcutBar.getDeclaredMethod("j1")
    fun syncShortcutBar(vb: Any) {
        (runCatching { getShortcutStrip.invoke(vb) as? ViewGroup }.getOrNull())?.let { strip ->
            val dec = inputDecorations.getOrPut(strip) { InputDecoration(strip, false) }
            if (on("TG输入栏")) dec.apply() else dec.restore()
            tgInput(strip)
        }
    }
    hook(shortcutBar.getDeclaredMethod("onCreateView", helpParam), gated = false) { chain ->
        chain.proceed().also { if (shortcutBar.isInstance(chain.thisObject)) syncShortcutBar(chain.thisObject) }
    }
    hook(shortcutBar.getDeclaredMethod("handleUIState", mviState), gated = false) { chain ->
        chain.proceed().also { if (shortcutBar.isInstance(chain.thisObject)) syncShortcutBar(chain.thisObject) }
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
        .firstOrNull { it.javaClass.name.endsWith(".InputLinearLayout") } ?: return
    findEdit(row)?.let { tgInput(it, strip) }
}

fun tgInput(edit: EditText, strip: ViewGroup? = null, sendBtn: View? = null) {
    inputs[edit]?.get()?.let {
        if (sendBtn != null) it.attachSendButton(sendBtn)
        it.sync()
        return
    }
    inputs[edit] = WeakReference(NativeInput(edit, strip, sendBtn).also { it.sync() })
}

fun refreshInputLayout() {
    val enabled = on("TG输入栏")
    inputDecorations.values.toList().forEach { if (enabled) it.apply() else it.restore() }
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
    private val shape = capsule()
    private val origRadii = WeakHashMap<Any, Float>()
    fun apply() {
        val v = view.get() ?: return
        if (v.outlineProvider !== shape) v.outlineProvider = shape
        if (!v.clipToOutline) v.clipToOutline = true
        val targetRadius = if (v.height > 0) v.height / 2f else 18f * v.dp
        updateSmoothRadii(v.background, targetRadius)
        v.invalidate()
    }
    fun restore() {
        val v = view.get() ?: return
        v.outlineProvider = outline
        v.clipToOutline = clip
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
    private val strip = WeakReference(strip)
    private val edit = WeakReference(edit)
    private val background = edit.background
    private val outline = edit.outlineProvider
    private val clip = edit.clipToOutline
    private val origPadding = intArrayOf(edit.paddingLeft, edit.paddingTop, edit.paddingRight, edit.paddingBottom)
    private val originalTranslationZ = edit.translationZ
    private val glass = Glass(edit, Float.MAX_VALUE, kernelSurface = true)
    private val shape = capsule()
    private val margins = WeakHashMap<View, ButtonMargin>()
    private val decorations = ArrayList<InputDecoration>()
    private var sendPill: SendButtonPill? = null
    private var applied = false
    private var focused = edit.hasFocus()
    private var focusAnimator: ValueAnimator? = null
    init {
        // 真包布局 r/i/{ez,k_...}.xml：InputLinearLayout 和图标带各有一层实色背景。
        strip?.let { decorations.add(InputDecoration(it)) }
        // NT dxg.xml：编辑器与发送按钮的 ConstraintLayout 自带不透明背景。
        if (edit.javaClass.name == "com.tencent.mobileqq.aio.input.edit.AIOEditText") {
            (edit.parent as? ViewGroup)?.let { parent ->
                decorations.add(InputDecoration(parent))
                if (sendBtn == null) {
                    (0 until parent.childCount).map(parent::getChildAt)
                        .firstOrNull { it.javaClass.name == "com.tencent.mobileqq.aio.input.AIOInputSendBtn" || it.id == 0x7f0a61c0 }
                        ?.let { sendPill = SendButtonPill(it) }
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
    fun attachSendButton(view: View) {
        if (sendPill == null) sendPill = SendButtonPill(view)
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
            it.translationZ = originalTranslationZ
        }
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
    inputs.values.mapNotNull { it.get() }.forEach { it.dispose() }
    inputs.clear()
}
