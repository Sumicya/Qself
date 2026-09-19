/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself

import android.app.Application
import sumicya.qself.config.Settings
import sumicya.qself.config.SettingsBridge
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.QselfFeature
import sumicya.qself.host.Host
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfo
import sumicya.qself.util.HostInfoProvider
import sumicya.qself.xp.HookEngine
import java.io.File

/**
 * The Qself runtime. One instance per process.
 *
 * - Host processes (QQ) boot through [boot] with a [HookEngine].
 * - The module's own process (settings UI) boots through [bootUi] and only
 *   gets settings access.
 */
object Qself {

    data class BootParam(
        val application: Application,
        val packageName: String,
        val processName: String,
        val framework: FrameworkKind,
    )

    // ---- host process -----------------------------------------------------

    val application: Application
        get() = requireNotNull(_application) { "Qself not booted in host" }

    val settings: Settings
        get() = requireNotNull(_settings) { "Qself not booted" }

    val bridge: SettingsBridge
        get() = requireNotNull(_bridge) { "Qself not booted" }

    val host: Host
        get() = requireNotNull(_host) { "Qself not booted in host" }

    val engine: HookEngine
        get() = requireNotNull(_engine) { "Qself not booted in host" }

    val process: ProcessKind
        get() = _process ?: ProcessKind.OTHER

    val framework: FrameworkKind
        get() = _framework ?: FrameworkKind.UNKNOWN

    val packageName: String
        get() = _packageName ?: ""

    val features: List<QselfFeature>
        get() = _features

    /** feature id -> init success */
    val featureResults: Map<String, Boolean>
        @Synchronized
        get() = HashMap(_featureResults)

    /** feature id -> init error (only failed features) */
    val featureErrors: Map<String, Throwable>
        @Synchronized
        get() = HashMap(_featureErrors)

    val isBooted: Boolean
        get() = _booted

    private var _booted = false
    private var _application: Application? = null
    private var _settings: Settings? = null
    private var _bridge: SettingsBridge? = null
    private var _host: Host? = null
    private var _engine: HookEngine? = null
    private var _process: ProcessKind? = null
    private var _framework: FrameworkKind? = null
    private var _packageName: String? = null
    private var _features: List<QselfFeature> = emptyList()
    private val _featureResults = HashMap<String, Boolean>()
    private val _featureErrors = HashMap<String, Throwable>()

    @Synchronized
    fun boot(param: BootParam, features: List<QselfFeature>, engine: HookEngine) {
        if (_booted) {
            QLog.w("Qself", "already booted; ignoring second boot")
            return
        }
        try {
            bootInternal(param, features, engine)
        } catch (t: Throwable) {
            // The host must survive a broken boot: log it, stay idle, and let
            // the next process start try again.
            QLog.e("Qself", "boot aborted; module idle in this process", t)
            _booted = false
            _features = emptyList()
            _engine = null
        }
    }

    @Synchronized
    private fun bootInternal(param: BootParam, features: List<QselfFeature>, engine: HookEngine) {
        _booted = true
        _application = param.application
        _packageName = param.packageName
        _framework = param.framework
        _process = ProcessState.classify(param.packageName, param.processName)
        _settings = Settings(param.application)
        val bridge = SettingsBridge(
            hostPackage = param.packageName,
            hostFilesDir = param.application.filesDir,
            localCache = _settings,
        )
        val sharedLoaded = bridge.load()
        _bridge = bridge
        _host = Host(param.packageName)
        _engine = engine
        _features = features
        _hostInfo = HostInfoProvider.load(param.application)

        QLog.i(
            "Qself",
            "boot: pkg=${param.packageName} proc=${param.processName} " +
                "framework=${param.framework.displayName} engine=$engine features=${features.size} " +
                "host=${_hostInfo!!.versionName} " +
                "settings=${if (sharedLoaded) "shared" else "local"}",
        )

        if (!engine.supported) {
            QLog.w("Qself", "hook engine not supported in this environment; skipping feature init")
            return
        }

        val generation = _host!!.generation
        QLog.i("Qself", "host generation: ${generation.title}")

        for (feature in features) {
            if (_process !in feature.targetProcesses) {
                continue
            }
            if (feature.hostGeneration != sumicya.qself.util.HostGeneration.ANY &&
                feature.hostGeneration != generation
            ) {
                // Not a failure: the feature simply belongs to the other QQ
                // generation (see docs/NT-ADAPTATION.md).
                _featureResults[feature.id] = false
                QLog.i(
                    "Feature",
                    "${feature.id} skipped (needs ${feature.hostGeneration.title}, host is ${generation.title})",
                )
                continue
            }
            try {
                val ok = feature.init(
                    FeatureContext(param.application, _settings!!, _host!!, _hostInfo!!),
                )
                _featureResults[feature.id] = ok
                QLog.i("Feature", "${feature.id} -> ${if (ok) "ok" else "disabled"}")
            } catch (t: Throwable) {
                _featureResults[feature.id] = false
                _featureErrors[feature.id] = t
                QLog.e("Feature", "init failed: ${feature.id}", t)
            }
        }
        QLog.i("Qself", "boot complete: ${_featureResults.count { it.value }}/${_featureResults.size} features ok")
    }

    // ---- module (UI) process ----------------------------------------------

    /**
     * Boot the UI side. [hostPackage] is the package the settings apply to;
     * the authoritative settings live in its files dir and are reached
     * through the su bridge.
     */
    @Synchronized
    fun bootUi(context: Application, hostPackage: String, localCache: Settings) {
        if (_booted) {
            return
        }
        _booted = true
        _application = context
        _packageName = hostPackage
        _framework = FrameworkKind.UNKNOWN
        _process = ProcessKind.OTHER
        _settings = localCache
        _bridge = SettingsBridge(
            hostPackage = hostPackage,
            hostFilesDir = File("/data/data/$hostPackage/files"),
            localCache = localCache,
            useSuBridge = true,
        )
        _hostInfo = HostInfoProvider.load(context, hostPackage)
        QLog.i("Qself", "bootUi: host=$hostPackage (shared settings load deferred)")
    }

    /**
     * Read the authoritative settings copy. **Blocking** — it may run `su` —
     * so UI callers run it off the main thread and refresh when it returns.
     */
    @Synchronized
    fun refreshSharedSettings(): Boolean {
        val bridge = _bridge ?: return false
        _uiSharedLoaded = bridge.load()
        QLog.i(
            "Qself",
            "shared settings: ${if (_uiSharedLoaded) "loaded" else "unavailable (local cache)"}",
        )
        return _uiSharedLoaded
    }

    /** Whether the UI could reach the authoritative settings copy. */
    val uiHasSharedSettings: Boolean
        @Synchronized
        get() = _bridge != null && _uiSharedLoaded

    val hostInfo: HostInfo
        get() = requireNotNull(_hostInfo) { "Qself not booted" }

    private var _uiSharedLoaded = false
    private var _hostInfo: HostInfo? = null
}
