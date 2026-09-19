/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import sumicya.qself.BuildConfig
import sumicya.qself.Qself
import sumicya.qself.R
import sumicya.qself.config.Settings
import sumicya.qself.feature.ActionFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.QselfFeature
import sumicya.qself.gen.QselfFeatures
import sumicya.qself.log.QLog
import sumicya.qself.engine.HookNative
import sumicya.qself.engine.NativeJavaSelfTest
import sumicya.qself.util.HostInfoProvider

/**
 * The module's settings screen. Runs in the module's own process; the
 * authoritative settings live in the host app's files dir and are reached
 * through [sumicya.qself.config.SettingsBridge].
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)

        val settings = Settings(this)
        Qself.bootUi(application, hostPackage(), settings)

        setContentView(R.layout.activity_main)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.subtitle = BuildConfig.VERSION_NAME
        setSupportActionBar(toolbar)

        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        val adapter = FeatureAdapter(
            rows = buildRows(),
            onToggle = { feature, value ->
                Qself.bridge.setEnabled(feature.id, value)
                Snackbar.make(list, R.string.takes_effect_on_restart, Snackbar.LENGTH_SHORT).show()
            },
            onFeatureClick = { feature ->
                if (feature is ActionFeature) {
                    feature.onClick(this)
                }
            },
            onCopyLogs = {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("qself-logs", QLog.snapshot()),
                )
                Snackbar.make(list, R.string.logs_copied, Snackbar.LENGTH_SHORT).show()
            },
        )
        list.adapter = adapter

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

    private fun buildRows(): List<UiRow> {
        val rows = ArrayList<UiRow>()
        rows += UiRow.Diagnostics(
            version = BuildConfig.VERSION_NAME,
            hostPackage = hostPackage(),
            sharedSettings = if (Qself.uiHasSharedSettings) "loaded" else "local cache (su unavailable)",
            nativeEngine = "${HookNative.version} / " +
                "${HookNative.lsplantStatus} / libart ${HookNative.artSymbolStatus}",
            nativeSelfTest = "dobby=${HookNative.selfTestResult} " +
                "java=${javaSelfTest} (${NativeJavaSelfTest.explain(javaSelfTest)})",
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage(
                getString(R.string.about_text, BuildConfig.VERSION_NAME),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLogs() {
        val text = QLog.snapshot()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs)
            .setMessage(text.ifEmpty { getString(R.string.logs_empty) })
            .setNeutralButton(R.string.copy) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("qself-logs", text))
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Hooks a probe class through LSPlant; run once, it is the real proof. */
    private val javaSelfTest: Int by lazy { NativeJavaSelfTest.run() }

    companion object {
        private const val MENU_ABOUT = 1
        private const val MENU_LOGS = 2
    }
}
