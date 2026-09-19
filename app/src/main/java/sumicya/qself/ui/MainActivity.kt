/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.Toast
import sumicya.qself.BuildConfig
import sumicya.qself.Qself
import sumicya.qself.R
import sumicya.qself.config.Settings
import sumicya.qself.engine.HookNative
import sumicya.qself.engine.NativeJavaSelfTest
import sumicya.qself.feature.ActionFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.QselfFeature
import sumicya.qself.util.HostGeneration
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfoProvider
import sumicya.qself.util.HostRestart

/**
 * The module's settings screen. Runs in the module's own process; the
 * authoritative settings live in the host app's files dir and are reached
 * through [sumicya.qself.config.SettingsBridge].
 *
 * Framework UI on purpose: a plain [Activity] with a `ListView` and the
 * theme's action bar - the whole screen needs no AndroidX or Material code.
 */
class MainActivity : Activity() {

    private lateinit var adapter: FeatureAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = Settings(this)
        Qself.bootUi(application, hostPackage(), settings)

        setContentView(R.layout.activity_main)
        actionBar?.subtitle = BuildConfig.VERSION_NAME
        // Diagnosed in the field: if this is null the platform theme gave the
        // activity no action bar, and the options menu has nowhere to appear.
        QLog.i("UI", "actionBar=${actionBar?.javaClass?.name ?: "none"}")

        adapter = FeatureAdapter(this, buildRows()) { feature, value ->
            Qself.bridge.setEnabled(feature.id, value)
            toast(R.string.takes_effect_on_restart)
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, rowView, position, _ ->
            when (val row = adapter.getItem(position)) {
                is UiRow.Diagnostics -> showActions()
                is UiRow.Feature -> onRowClick(row.feature, rowView)
                is UiRow.Header -> Unit
            }
        }

        applyWindowInsets()

        // The authoritative settings live in the host's files dir behind `su`;
        // reading them can wait on a root prompt, so never do it on the UI
        // thread. The list is redrawn once the answer is in.
        Thread {
            Qself.refreshSharedSettings()
            runOnUiThread { adapter.submit(buildRows()) }
        }.start()
    }

    /**
     * Android 15+ (targetSdk 35/36) draws every app edge-to-edge, so a list
     * that starts at the top of the window ends up under the status bar — on
     * this device the diagnostics card was simply not visible. Padding the
     * list by the system bars/cutout keeps the content out of them while
     * `clipToPadding=false` lets it scroll underneath.
     */
    private fun applyWindowInsets() {
        val list = findViewById<View>(R.id.list) ?: return
        val base = list.paddingBottom
        list.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom + base)
            insets
        }
        list.requestApplyInsets()
    }

    /**
     * The one entry point that does not depend on the action bar: some ROMs
     * (ColorOS among them) simply do not render the framework overflow menu,
     * which would leave the class dump unreachable. Tapping the diagnostics
     * card always works.
     */
    private fun showActions() {
        val items = arrayOf(
            getString(R.string.copy_logs),
            getString(R.string.dump_classes),
            getString(R.string.sync_settings),
            getString(R.string.restart_host),
            getString(R.string.about),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.actions)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> copyLogs()
                    1 -> dumpClasses()
                    2 -> syncSettings()
                    3 -> restartHost()
                    else -> showAbout()
                }
            }
            .show()
    }

    /**
     * Write every switch into the host's settings file through `su` and report
     * the outcome. Without this the bridge could fail silently: the switches
     * would move in the UI while QQ's process kept reading its defaults (that
     * is how the NT features ended up never running on the device).
     */
    private fun syncSettings() {
        toast(R.string.sync_running)
        Thread {
            val message = Qself.syncSharedSettings()
            runOnUiThread {
                adapter.submit(buildRows())
                if (message.startsWith("已写入")) {
                    // No manual step: the settings only apply at host startup, so
                    // the module stops QQ itself and the user just re-opens it.
                    toast(message)
                    restartHost()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.sync_settings)
                        .setMessage(message)
                        .setPositiveButton(R.string.restart_host) { _, _ -> restartHost() }
                        .setNegativeButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ABOUT, 0, R.string.about)
        menu.add(0, MENU_LOGS, 0, R.string.logs)
        menu.add(0, MENU_DUMP, 1, R.string.dump_classes)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_ABOUT -> {
            showAbout()
            true
        }
        MENU_LOGS -> {
            showLogs()
            true
        }
        MENU_DUMP -> {
            dumpClasses()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** Action features run their dialog; switch features toggle where tapped. */
    private fun onRowClick(feature: QselfFeature, rowView: View) {
        if (feature is ActionFeature) {
            feature.onClick(this)
        } else {
            rowView.findViewById<Switch>(R.id.feature_switch)?.toggle()
        }
    }

    private fun buildRows(): List<UiRow> {
        val rows = ArrayList<UiRow>()
        val host = hostPackage()
        val hostGeneration = hostGeneration()
        rows += UiRow.Diagnostics(
            version = BuildConfig.VERSION_NAME,
            hostPackage = "$host ${hostDetails(host)}",
            sharedSettings = Qself.settingsStatus,
            nativeEngine = "${HookNative.version} / " +
                "${HookNative.lsplantStatus} / libart ${HookNative.artSymbolStatus}",
            nativeSelfTest = "dobby=${HookNative.selfTestResult} " +
                "java=$javaSelfTest (${NativeJavaSelfTest.explain(javaSelfTest)})",
        )
        val byCategory = LinkedHashMap<FeatureCategory, MutableList<QselfFeature>>()
        for (feature in QselfFeatures.features) {
            byCategory.getOrPut(feature.category) { mutableListOf() }.add(feature)
        }
        for (category in FeatureCategory.entries) {
            val features = byCategory[category] ?: continue
            features.sortBy { it.name }
            rows += UiRow.Header(category.title)
            for (feature in features) {
                rows += UiRow.Feature(
                    feature = feature,
                    applicable = !HostGeneration.mismatches(feature.hostGeneration, hostGeneration),
                )
            }
        }
        return rows
    }

    /** Installed host version name, or null when it cannot be read. */
    private fun hostVersionName(host: String): String? = try {
        packageManager.getPackageInfo(host, 0).versionName
    } catch (t: Throwable) {
        null
    }

    /**
     * Host version plus the QQ generation it belongs to. Which generation the
     * host is decides whether half of the switches can do anything at all
     * (see docs/NT-ADAPTATION.md), so the card states it outright instead of
     * ending in a question mark.
     */
    private fun hostDetails(host: String): String {
        val name = hostVersionName(host) ?: return "?"
        val generation = HostGeneration.fromVersion(name)
        val suffix = when (generation) {
            HostGeneration.NT -> "（NT）"
            HostGeneration.PRE_NT -> "（旧版）"
            else -> ""
        }
        return name + suffix
    }

    /** Detected host generation, or null when the version is unreadable. */
    private fun hostGeneration(): HostGeneration? = HostGeneration.fromVersion(hostVersionName(hostPackage()))

    private fun hostPackage(): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            info.metaData?.getString("qself.host") ?: HostInfoProvider.PACKAGE_NAME_QQ
        } catch (t: Throwable) {
            HostInfoProvider.PACKAGE_NAME_QQ
        }
    }

    /**
     * Settings only take effect at host startup (the hooks have to exist before
     * the host's own startup code runs), so the module offers the restart
     * instead of leaving it to the user.
     */
    private fun restartHost() {
        val host = hostPackage()
        toast(R.string.restart_running)
        Thread {
            val message = HostRestart.forceStop(host)
            runOnUiThread { toast(message) }
        }.start()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_text, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLogs() {
        val text = QLog.snapshot()
        AlertDialog.Builder(this)
            .setTitle(R.string.logs)
            .setMessage(text.ifEmpty { getString(R.string.logs_empty) })
            .setNeutralButton(R.string.copy) { _, _ -> copyLogs(text) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Exports the host's real class names (see [ClassDump]) so NT-QQ support
     * can be written against the device instead of guesses. Blocking (runs
     * `su`), hence the thread.
     */
    private fun dumpClasses() {
        val input = EditText(this).apply {
            setText(ClassDump.DEFAULT_PREFIXES)
            setPadding(48, 24, 48, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dump_classes)
            .setMessage(R.string.dump_prefixes_hint)
            .setView(input)
            .setPositiveButton(R.string.dump_start) { _, _ ->
                val prefixes = input.text.toString()
                    .split(',', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }   // ClassDump normalises both forms
                runDump(prefixes)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runDump(prefixes: List<String>) {
        toast(R.string.dump_running)
        Thread {
            val result = try {
                ClassDump.dump(this, hostPackage(), prefixes)
            } catch (t: Throwable) {
                QLog.e("ClassDump", "dump failed", t)
                "导出失败：${t.javaClass.simpleName}"
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dump_classes)
                    .setMessage(result)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    private fun copyLogs(text: String = QLog.snapshot()) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("qself-logs", text))
        toast(R.string.logs_copied)
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Same, for messages that carry a result (su output, failure reason). */
    private fun toast(message: CharSequence) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Hooks a probe class through LSPlant; run once, it is the real proof. */
    private val javaSelfTest: Int by lazy { NativeJavaSelfTest.run() }

    private companion object {
        const val MENU_ABOUT = 1
        const val MENU_LOGS = 2
        const val MENU_DUMP = 3
    }
}
