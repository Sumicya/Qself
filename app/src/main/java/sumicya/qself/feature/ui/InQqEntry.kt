/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import sumicya.qself.BuildConfig
import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.log.QLog
import sumicya.qself.util.HostGeneration
import sumicya.qself.xp.Hooks

/**
 * The settings entry inside QQ — built the way upstream modules build it.
 *
 * Upstream (QAuxiliary's `SettingEntryHook`) does **not** float a button over
 * QQ: it hooks the settings page's *item provider* and inserts a real entry
 * into QQ's own settings list, so the entry is indistinguishable from QQ's own
 * rows and lives exactly where users look for module settings.
 *
 * That is what this feature ports:
 *
 *  1. resolve the provider that builds the settings list
 *     (`...setting.main.NewSettingConfigProvider` / `MainSettingConfigProvider` /
 *     the obfuscated `...setting.main.b`) and hook its
 *     `List getItemProcessList(Context)`;
 *  2. after the original ran, take the list QQ just built, build one more
 *     entry item from *the live list itself* (its class, constructor and click
 *     setter are all discovered from real objects — no guessed class names),
 *     wrap it in a group and insert it;
 *  3. a tap opens Qself's settings screen, like every other module.
 *
 * If the provider cannot be found (a QQ version that renamed or restructured
 * it), the feature falls back to a small floating "Qself" chip on pages whose
 * class name says settings/about/config, so there is always a way in. The log
 * says which of the two is active, and `QselfEntryRow.injected` keeps the chip
 * away once the native row works.
 *
 * Writing settings still does not need the user to do anything by hand: the
 * settings screen syncs into the host's own `files/qself/settings.json` through
 * the root bridge and then restarts QQ itself (see [sumicya.qself.util.HostRestart]).
 */
@QselfFeature(
    id = "ui.inqq_entry",
    name = "QQ 内设置入口",
    summary = "在 QQ 设置列表里插一行 Qself（上游做法）；找不到设置列表时退回悬浮按钮",
    category = "ui",
    enabledByDefault = true,
)
object InQqEntry : SwitchFeature() {

    private const val TAG = "InQqEntry"

    override val id: String = "ui.inqq_entry"
    override val name: String = "QQ 内设置入口"
    override val summary: String = "在 QQ 设置列表里插一行 Qself（上游做法）；找不到设置列表时退回悬浮按钮"
    override val category: FeatureCategory = FeatureCategory.UI
    override val experimental: Boolean = false
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = true
    override val hostGeneration: HostGeneration = HostGeneration.ANY

    override fun initOnce(ctx: FeatureContext): Boolean {
        var row = false
        try {
            row = QselfEntryRow.inject(this, ctx)
        } catch (t: Throwable) {
            QLog.w(TAG, "settings list injection failed", t)
        }
        var chip = false
        try {
            chip = QselfChip.arm(this, ctx)
        } catch (t: Throwable) {
            QLog.w(TAG, "fallback chip failed", t)
        }
        QLog.i(
            TAG,
            "entry armed: settings row=${if (row) "hooked" else "unavailable"}, " +
                "chip=${if (chip) "armed" else "no"}",
        )
        return row || chip
    }
}

/** Opens Qself's own settings screen from inside the host process. */
private object QselfSettings {

    private const val TAG = "InQqEntry"

    fun open(context: Context) {
        try {
            val intent = Intent()
                .setComponent(ComponentName(BuildConfig.APPLICATION_ID, "sumicya.qself.ui.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            QLog.i(TAG, "settings screen opened from the host")
        } catch (t: Throwable) {
            QLog.w(TAG, "could not open the settings screen", t)
        }
    }
}

/**
 * The upstream way in: one extra row in QQ's own settings list.
 *
 * Everything about the row (item class, its constructor, the click setter, the
 * group wrapper) comes from the list QQ just handed to its own settings page,
 * so no class name from another QQ version is trusted. Class names only appear
 * as *candidates to validate against the live list*, never as the source of
 * truth (docs/NT-ADAPTATION.md: build on what the device says).
 */
private object QselfEntryRow {

    private const val TAG = "InQqEntry"
    private const val TITLE = "Qself"

    /** Providers that build the settings list, newest naming first. */
    private val PROVIDERS = arrayOf(
        "com.tencent.mobileqq.setting.main.NewSettingConfigProvider",
        "com.tencent.mobileqq.setting.main.MainSettingConfigProvider",
        "com.tencent.mobileqq.setting.main.b",
    )

    /**
     * Classes whose *superclass* identifies the shared item base class. Only
     * used to validate item candidates, never on its own.
     */
    private val ITEM_PARENTS = arrayOf(
        "com.tencent.mobileqq.setting.main.processor.AccountSecurityItemProcessor",
        "com.tencent.mobileqq.setting.main.processor.AboutItemProcessor",
    )

    /** Known simple-item names, accepted only when the base class agrees. */
    private val ITEM_CANDIDATES = arrayOf(
        "com.tencent.mobileqq.setting.processor.g",
        "com.tencent.mobileqq.setting.processor.h",
        "com.tencent.mobileqq.setting.processor.i",
        "com.tencent.mobileqq.setting.processor.j",
        "as3.i",
    )

    /** Set once a row was really inserted; the floating chip steps aside then. */
    val injected = AtomicBoolean(false)

    private val reportedFailure = AtomicBoolean(false)

    fun inject(feature: InQqEntry, ctx: FeatureContext): Boolean {
        val provider = PROVIDERS.firstNotNullOfOrNull { ctx.host.resolve(it) }
        if (provider == null) {
            QLog.w(TAG, "no settings provider found (tried ${PROVIDERS.joinToString(", ")})")
            return false
        }
        val method = provider.declaredMethods.firstOrNull(::isItemListMethod)
        if (method == null) {
            QLog.w(TAG, "${provider.name} has no List getItemProcessList(Context)")
            return false
        }
        val baseClass = ITEM_PARENTS.firstNotNullOfOrNull { ctx.host.resolve(it)?.superclass }
        if (baseClass == null) {
            QLog.w(TAG, "item base class unknown; will discover the item class from the live list")
        }
        Hooks.afterIfEnabled(feature, method) { param ->
            val list = param.result as? MutableList<Any?> ?: return@afterIfEnabled
            val context = param.args.firstOrNull() as? Context ?: return@afterIfEnabled
            if (list.isEmpty()) {
                return@afterIfEnabled
            }
            try {
                insert(list, context, ctx, baseClass, provider)
            } catch (t: Throwable) {
                if (reportedFailure.compareAndSet(false, true)) {
                    QLog.w(TAG, "could not insert the settings row: ${t.javaClass.name}: ${t.message}", t)
                }
            }
        }
        QLog.i(TAG, "settings provider hooked: ${provider.name}#${method.name}")
        return true
    }

    /** `List getItemProcessList(Context)`, under whatever name QQ gave it. */
    private fun isItemListMethod(m: Method): Boolean =
        m.parameterTypes.size == 1 &&
            m.parameterTypes[0] == Context::class.java &&
            Collection::class.java.isAssignableFrom(m.returnType)

    private fun insert(
        list: MutableList<Any?>,
        context: Context,
        ctx: FeatureContext,
        baseClass: Class<*>?,
        provider: Class<*>,
    ) {
        val groupClass = list.firstOrNull()?.javaClass ?: return
        val itemClass = itemClass(ctx, list, baseClass)
        if (itemClass == null) {
            if (reportedFailure.compareAndSet(false, true)) {
                QLog.w(TAG, "no settings item class could be identified in the live list")
            }
            return
        }
        val ctor = itemConstructor(itemClass)
        if (ctor == null) {
            if (reportedFailure.compareAndSet(false, true)) {
                QLog.w(TAG, "${itemClass.name}: no (Context,int,CharSequence,int) constructor")
            }
            return
        }
        val item = newItem(ctor, context) ?: return
        if (clickSetter(itemClass) == null) {
            if (reportedFailure.compareAndSet(false, true)) {
                QLog.w(TAG, "${itemClass.name}: no void(Function0) click setter; keeping the chip")
            }
            return
        }
        bindClick(itemClass, item, context)
        val groupCtor = groupConstructor(groupClass) ?: return
        val group = newGroup(groupCtor, item) ?: return
        val index = if (list.size >= 2) 2 else list.size
        list.add(index, group)
        if (injected.compareAndSet(false, true)) {
            QLog.i(
                TAG,
                "settings row injected at $index via ${provider.name}: " +
                    "item=${itemClass.name} group=${groupClass.name}, groups=${list.size}",
            )
        }
    }

    /** The item class: validated candidates first, then the live list. */
    private fun itemClass(ctx: FeatureContext, list: List<Any?>, baseClass: Class<*>?): Class<*>? {
        if (baseClass != null) {
            for (name in ITEM_CANDIDATES) {
                val cls = ctx.host.resolve(name) ?: continue
                if (cls.superclass == baseClass && itemConstructor(cls) != null) {
                    return cls
                }
            }
        }
        return discoverItemClass(list)
    }

    /**
     * Any class in the list that *behaves* like a settings row: it has the
     * `(Context, int, CharSequence, int)` shape and a `void(Function0)` click
     * setter. Found by walking the group objects QQ built, so it needs no name.
     */
    private fun discoverItemClass(list: List<Any?>): Class<*>? {
        for (group in list) {
            val cls = group?.javaClass ?: continue
            for (field in allFields(cls)) {
                if (!Collection::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                val value = runCatching { field.get(group) as? Collection<*> }.getOrNull() ?: continue
                for (element in value) {
                    val itemCls = element?.javaClass ?: continue
                    if (itemConstructor(itemCls) != null && clickSetter(itemCls) != null) {
                        return itemCls
                    }
                }
            }
        }
        return null
    }

    private fun allFields(cls: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>(8)
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java) {
            out += current.declaredFields
            current = current.superclass
        }
        return out
    }

    private fun itemConstructor(cls: Class<*>): Constructor<*>? {
        val four = runCatching {
            cls.getDeclaredConstructor(
                Context::class.java,
                Int::class.javaPrimitiveType,
                CharSequence::class.java,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (four != null) return four
        return runCatching {
            cls.getDeclaredConstructor(
                Context::class.java,
                Int::class.javaPrimitiveType,
                CharSequence::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
        }.getOrNull()
    }

    private fun newItem(ctor: Constructor<*>, context: Context): Any? {
        ctor.isAccessible = true
        val id = View.generateViewId()
        // A framework drawable: it exists in every host's Resources, so no
        // resource injection (upstream ships its own drawable for this).
        val icon = android.R.drawable.ic_menu_preferences
        return runCatching {
            if (ctor.parameterTypes.size == 5) {
                ctor.newInstance(context, id, TITLE, icon, null)
            } else {
                ctor.newInstance(context, id, TITLE, icon)
            }
        }.onFailure { QLog.w(TAG, "could not build the settings item", it) }.getOrNull()
    }

    /**
     * QQ's items take a `kotlin.jvm.functions.Function0` (a Kotlin lambda) as
     * the click listener; a JDK proxy over the *host's* copy of that interface
     * is what upstream does too.
     */
    private fun clickSetter(itemClass: Class<*>): Method? =
        itemClass.declaredMethods.firstOrNull { m ->
            m.returnType == Void.TYPE && m.parameterTypes.size == 1 &&
                m.parameterTypes[0].name == "kotlin.jvm.functions.Function0"
        }

    private fun bindClick(itemClass: Class<*>, item: Any, context: Context) {
        val setter = clickSetter(itemClass)
        if (setter == null) {
            QLog.w(TAG, "${itemClass.name}: no void(Function0) click setter; the row would be dead")
            return
        }
        val functionType = setter.parameterTypes[0]
        val unit = runCatching {
            functionType.classLoader?.loadClass("kotlin.Unit")?.getField("INSTANCE")?.get(null)
        }.getOrNull()
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    QselfSettings.open(context)
                    unit
                }
                "toString" -> "QselfEntryClick"
                // identityHashCode, not proxy.hashCode(): the latter would come
                // straight back into this handler and recurse forever.
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        val proxy = Proxy.newProxyInstance(functionType.classLoader, arrayOf(functionType), handler)
        runCatching {
            setter.isAccessible = true
            setter.invoke(item, proxy)
        }.onFailure { QLog.w(TAG, "could not attach the click listener", it) }
    }

    /** The group wrapper: `(List, CharSequence, CharSequence)`, else the 5-arg form. */
    private fun groupConstructor(groupClass: Class<*>): Constructor<*>? {
        val three = groupClass.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 3 &&
                List::class.java.isAssignableFrom(c.parameterTypes[0]) &&
                c.parameterTypes[1] == CharSequence::class.java &&
                c.parameterTypes[2] == CharSequence::class.java
        }
        if (three != null) return three
        return groupClass.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 5 &&
                List::class.java.isAssignableFrom(c.parameterTypes[0]) &&
                c.parameterTypes[1] == CharSequence::class.java &&
                c.parameterTypes[2] == CharSequence::class.java
        }
    }

    private fun newGroup(ctor: Constructor<*>, item: Any): Any? {
        ctor.isAccessible = true
        val items = arrayListOf(item)
        return runCatching {
            if (ctor.parameterTypes.size == 5) {
                ctor.newInstance(items, "", "", 6, null)
            } else {
                ctor.newInstance(items, "", "")
            }
        }.onFailure { QLog.w(TAG, "could not wrap the settings item in a group", it) }.getOrNull()
    }
}

/**
 * Fallback only: a small floating "Qself" chip on settings-like pages, used
 * when QQ's settings list could not be hooked at all.
 *
 * It is deliberately the *second* choice: a chip draws attention to itself and
 * does not belong to QQ's layout, which is exactly the criticism of the first
 * attempt. It exists so a renamed provider costs the user a slightly ugly
 * entry instead of no entry, and it logs the page it attached to, so the real
 * fix (making [QselfEntryRow] find the provider) can be written from a log.
 */
private object QselfChip {

    private const val TAG = "InQqEntry"
    private val PAGE_MARKERS = arrayOf("setting", "about", "config")
    private val attached = WeakHashMap<Activity, Boolean>()

    fun arm(feature: InQqEntry, ctx: FeatureContext): Boolean {
        val target = runCatching {
            Instrumentation::class.java
                .getDeclaredMethod("callActivityOnResume", Activity::class.java)
                .also { it.isAccessible = true }
        }.getOrNull()
        if (target == null) {
            QLog.w(TAG, "Instrumentation#callActivityOnResume not found; no fallback entry")
            return false
        }
        Hooks.beforeIfEnabled(feature, target) { param ->
            val activity = param.args.firstOrNull() as? Activity ?: return@beforeIfEnabled
            if (activity.packageName != ctx.context.packageName) return@beforeIfEnabled
            val page = activity.javaClass.name.lowercase()
            if (PAGE_MARKERS.none { page.contains(it) }) return@beforeIfEnabled
            if (QselfEntryRow.injected.get()) {
                QLog.d(TAG, "settings row is live; no chip on ${activity.javaClass.name}")
                return@beforeIfEnabled
            }
            QLog.d(TAG, "settings-like page resumed (fallback chip): ${activity.javaClass.name}")
            attach(activity)
        }
        return true
    }

    private fun attach(activity: Activity) {
        synchronized(attached) {
            if (attached.containsKey(activity)) return
            attached[activity] = true
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return
        decor.post {
            try {
                if (activity.isFinishing) return@post
                val density = activity.resources.displayMetrics.density
                val pad = (density * 8).toInt()
                val chip = TextView(activity).apply {
                    text = "Qself"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xB3000000.toInt())
                    setPadding(pad * 2, pad, pad * 2, pad)
                    setOnClickListener { QselfSettings.open(activity) }
                }
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    val margin = (density * 16).toInt()
                    setMargins(margin, margin, margin, margin * 6)
                }
                decor.addView(chip, params)
            } catch (t: Throwable) {
                QLog.w(TAG, "could not add the fallback chip", t)
            }
        }
    }
}
