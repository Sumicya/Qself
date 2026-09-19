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
        rows += UiRow.Diagnostics(
            version = BuildConfig.VERSION_NAME,
            hostPackage = hostPackage(),
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
    }
}
