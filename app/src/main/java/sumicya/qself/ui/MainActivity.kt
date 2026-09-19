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
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfoProvider

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

        adapter = FeatureAdapter(this, buildRows()) { feature, value ->
            Qself.bridge.setEnabled(feature.id, value)
            toast(R.string.takes_effect_on_restart)
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, rowView, position, _ ->
            when (val row = adapter.getItem(position)) {
                is UiRow.Diagnostics -> copyLogs()
                is UiRow.Feature -> onRowClick(row.feature, rowView)
                is UiRow.Header -> Unit
            }
        }

        // The authoritative settings live in the host's files dir behind `su`;
        // reading them can wait on a root prompt, so never do it on the UI
        // thread. The list is redrawn once the answer is in.
        Thread {
            Qself.refreshSharedSettings()
            runOnUiThread { adapter.submit(buildRows()) }
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
        rows += UiRow.Diagnostics(
            version = BuildConfig.VERSION_NAME,
            hostPackage = "$host ${hostDetails(host)}",
            sharedSettings = if (Qself.uiHasSharedSettings) "loaded" else "local cache (su unavailable)",
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
                rows += UiRow.Feature(feature)
            }
        }
        return rows
    }

    /**
     * Host version plus a rough NT verdict. v1's features target the pre-NT
     * QQ, so "this is a 9.x (NT) build" is the single most useful thing the
     * diagnostics row can say on such a device (see docs/NT-ADAPTATION.md).
     */
    private fun hostDetails(host: String): String {
        return try {
            val info = packageManager.getPackageInfo(host, 0)
            val name = info.versionName ?: "?"
            val nt = name.startsWith("9") || name.startsWith("8.9.6") ||
                name.startsWith("8.9.7") || name.startsWith("8.9.8") || name.startsWith("8.9.9")
            name + if (nt) " (NT?)" else ""
        } catch (t: Throwable) {
            "?"
        }
    }

    private fun hostPackage(): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            info.metaData?.getString("qself.host") ?: HostInfoProvider.PACKAGE_NAME_QQ
        } catch (t: Throwable) {
            HostInfoProvider.PACKAGE_NAME_QQ
        }
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
        toast(R.string.dump_running)
        Thread {
            val result = try {
                ClassDump.dump(this, hostPackage())
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

    /** Hooks a probe class through LSPlant; run once, it is the real proof. */
    private val javaSelfTest: Int by lazy { NativeJavaSelfTest.run() }

    private companion object {
        const val MENU_ABOUT = 1
        const val MENU_LOGS = 2
        const val MENU_DUMP = 3
    }
}
