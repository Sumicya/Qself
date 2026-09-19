/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.WeakHashMap
import sumicya.qself.ProcessKind
import sumicya.qself.Qself
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.ActionFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.log.QLog
import sumicya.qself.util.HostGeneration
import sumicya.qself.util.HostRestart
import sumicya.qself.xp.Hooks

/**
 * The settings entry point *inside* QQ.
 *
 * Two problems this solves at once:
 *
 *  - There is no reachable entry point from outside: some ROMs do not render
 *    the action bar overflow, and a separate settings app is easy to lose.
 *  - Writing settings from outside needs root (a different uid) and then a
 *    manual restart. Running inside the host, Qself *is* QQ's uid: the panel
 *    writes `files/qself/settings.json` directly with no `su` at all, and
 *    restarting is `Process.killProcess(myPid())` — one tap, no root.
 *
 * The entry is a small floating "Qself" chip attached to the decor view of
 * pages whose class name looks like settings ("Setting"/"About"/"Config"), so
 * no QQ layout internals are involved and nothing breaks when QQ redesigns a
 * page. Injection is done through the framework's own
 * `Instrumentation#callActivityOnResume`, which means no QQ class name has to
 * be guessed at build time.
 *
 * A restart is still required for the toggles to take effect: the hooks have to
 * be in place before the host's startup code runs (patch application, crash
 * reporting and the upgrade check all happen before the first window), so the
 * panel offers the restart instead of pretending a live toggle would work.
 */
@QselfFeature(
    id = "ui.inqq_panel",
    name = "QQ 内设置面板",
    summary = "在 QQ 设置/关于页角落加一个 Qself 按钮：直接改开关、一键重启，全程无需 root",
    category = "ui",
    enabledByDefault = true,
)
object InQqPanel : SwitchFeature() {

    private const val TAG = "InQqPanel"

    override val id: String = "ui.inqq_panel"
    override val name: String = "QQ 内设置面板"
    override val summary: String =
        "在 QQ 设置/关于页角落加一个 Qself 按钮：直接改开关、一键重启，全程无需 root"
    override val category: FeatureCategory = FeatureCategory.UI
    override val experimental: Boolean = false
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = true
    override val hostGeneration: HostGeneration = HostGeneration.ANY

    /** Lower-cased fragments that mark a page worth hanging the chip on. */
    private val PAGE_MARKERS = arrayOf("setting", "about", "config")

    override fun initOnce(ctx: FeatureContext): Boolean {
        val target = try {
            Instrumentation::class.java.getDeclaredMethod("callActivityOnResume", Activity::class.java)
                .also { it.isAccessible = true }
        } catch (t: Throwable) {
            QLog.w(TAG, "Instrumentation#callActivityOnResume not found", t)
            return false
        }

        Hooks.beforeIfEnabled(this, target) { param ->
            val activity = param.args.firstOrNull() as? Activity ?: return@beforeIfEnabled
            // Only the host's own pages; the module's UI runs elsewhere anyway.
            if (activity.packageName != ctx.context.packageName) return@beforeIfEnabled
            val page = activity.javaClass.name.lowercase()
            if (PAGE_MARKERS.none { page.contains(it) }) return@beforeIfEnabled
            // Logged so "the chip did not show up" can be answered from a log
            // instead of a guess about which page is the settings page.
            QLog.d(TAG, "settings-like page resumed: ${activity.javaClass.name}")
            QselfPanel.attach(activity)
        }
        QLog.i(TAG, "entry armed on pages matching ${PAGE_MARKERS.joinToString("/")}")
        return true
    }
}

/** The chip and the dialog it opens. Runs entirely inside the host process. */
private object QselfPanel {

    private const val TAG = "QselfPanel"
    private val attached = WeakHashMap<Activity, Boolean>()

    fun attach(activity: Activity) {
        synchronized(attached) {
            if (attached.containsKey(activity)) return
            attached[activity] = true
        }
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            // Post: the decor is not laid out yet while onResume is being
            // dispatched.
            decor.post {
                try {
                    if (activity.isFinishing) return@post
                    val chip = TextView(activity).apply {
                        text = "Qself"
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(0xB3000000.toInt())
                        val pad = (resources.displayMetrics.density * 8).toInt()
                        setPadding(pad * 2, pad, pad * 2, pad)
                        setOnClickListener { show(activity) }
                    }
                    val params = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                        val margin = (activity.resources.displayMetrics.density * 16).toInt()
                        setMargins(margin, margin, margin, margin * 6)
                    }
                    decor.addView(chip, params)
                } catch (t: Throwable) {
                    QLog.w(TAG, "could not add the chip", t)
                }
            }
        } catch (t: Throwable) {
            QLog.w(TAG, "attach failed", t)
        }
    }

    /** Switch list + the two actions that matter: restart, and copy the log. */
    private fun show(activity: Activity) {
        try {
            val generation = Qself.host.generation
            // In the host process this is a plain file write under QQ's own
            // files/ — the whole point of the panel: no su anywhere.
            val where = Qself.bridge.sharedFile?.absolutePath ?: "（没有可用路径）"
            val features = QselfFeatures.features.filter { it !is ActionFeature }
            val labels = features.map { feature ->
                val note = if (HostGeneration.mismatches(feature.hostGeneration, generation)) {
                    "（不适用于本代 QQ）"
                } else {
                    ""
                }
                feature.name + note
            }.toTypedArray()
            val checked = BooleanArray(features.size) { features[it].isEnabled }
            AlertDialog.Builder(activity)
                .setTitle("Qself 开关（改完点「重启 QQ」）")
                .setMessage("写入 $where\n补丁类开关必须重启 QQ 才生效（它们在首个窗口出现前就已执行）。")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    // Straight into files/qself/settings.json — same uid, no su.
                    QLog.i(TAG, "toggle ${features[which].id}=$isChecked")
                    Qself.bridge.setEnabled(features[which].id, isChecked)
                }
                .setPositiveButton("立即重启 QQ") { _, _ -> HostRestart.killSelf() }
                .setNegativeButton("稍后") { _, _ -> QLog.i(TAG, "restart deferred") }
                .setNeutralButton("复制日志") { _, _ -> copyLogs(activity) }
                .show()
        } catch (t: Throwable) {
            QLog.w(TAG, "could not show the panel", t)
        }
    }

    private fun copyLogs(activity: Activity) {
        try {
            val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("Qself", QLog.snapshot()))
            QLog.i(TAG, "log copied to the clipboard")
        } catch (t: Throwable) {
            QLog.w(TAG, "could not copy the log", t)
        }
    }
}
