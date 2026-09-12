#!/usr/bin/env python3
"""Static guardrails, not a substitute for Kotlin compilation or on-device integration tests."""
import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/sumicya/qself/diagnostics"


def code(name):
    text = (SRC / name).read_text()
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)


class ReportDiagnosticsContract(unittest.TestCase):
    def test_callback_is_read_only(self):
        hook = code("ReportDiagnostics.kt")
        body = hook[hook.index("private fun observer("):hook.index("private fun showMenu(")]
        self.assertNotRegex(body, r"\b(?:setResult|setThrowable|invokeOriginalMethod)\s*\(")
        self.assertNotRegex(body, r"param\.(?:args(?:\[[^]]*\])?|result|throwable|thisObject)\s*=")
        self.assertNotRegex(body, r"\.(?:getMessage|getStackTrace|toString)\s*\(")
        self.assertNotIn("traceError(", body)
        self.assertIn("delivery=UNKNOWN", body)
        self.assertIn("getters", body)
        self.assertIn("ReportMetadata.command(", body)

    def test_explicit_opt_in_and_limited_scope(self):
        store = code("ReportDiagnosticsStore.kt")
        hook = code("ReportDiagnostics.kt")
        self.assertIn("getBooleanOrDefault(ENABLED, false)", store)
        self.assertIn("get() = ReportDiagnosticsStore.enabled", hook)
        self.assertIn("hostInfo.versionCode == 11310L", hook)
        self.assertIn("if (!isAvailable || !isEnabled || !isTargetProcess) return false", hook)
        self.assertIn("SyncUtils.PROC_MAIN or SyncUtils.PROC_MSF", hook)
        self.assertNotRegex(hook, r"putBoolean\(\s*\"rq_risk_report_interceptor")

    def test_bounded_async_persistence(self):
        store = code("ReportDiagnosticsStore.kt")
        hook = code("ReportDiagnostics.kt")
        self.assertIn("ArrayBlockingQueue(128)", store)
        self.assertIn("ThreadPoolExecutor.AbortPolicy()", store)
        self.assertIn("allowCoreThreadTimeOut(true)", store)
        self.assertNotIn("CallerRunsPolicy", store)
        self.assertIn("worker.execute", store)
        self.assertIn("ReportMetadata.append(old, line)", store)
        self.assertIn("calls.size >= 128", hook)
        self.assertIn("rateCount++ < 20", hook)

    def test_clear_invalidates_old_window(self):
        store = code("ReportDiagnosticsStore.kt")
        self.assertIn("UUID.randomUUID().toString()", store)
        self.assertIn("putString(EPOCH, epoch)", store)
        self.assertIn("isCurrentWindow(expectedEpoch, expectedGeneration)", store)
        self.assertIn('snapshot.put("epoch", expectedEpoch)', store)
        self.assertIn('it.optString("epoch") == epoch', store)

    def test_disable_invalidates_queued_and_inflight_callbacks(self):
        store = code("ReportDiagnosticsStore.kt")
        hook = code("ReportDiagnostics.kt")
        self.assertIn('putString(GENERATION, UUID.randomUUID().toString())', store)
        self.assertIn('ReportMetadata.acceptsWindow(enabled, epoch(), generation(), expectedEpoch, expectedGeneration)', store)
        self.assertIn('ReportFileLock.withLock(', store)
        self.assertEqual(hook.count('expectedGeneration = call.generation'), 2)

    def test_new_qq_legacy_interceptor_is_guarded(self):
        text = (ROOT / "app/src/main/java/awoo/linwenxuan04/hook/ChannelProxyHook.kt").read_text()
        self.assertIn("!requireMinQQVersion(QQVersion.QQ_9_1_30)", text)
        self.assertIn("if (!isAvailable) return false", text)
        prop = (ROOT / "loader/sbl/src/main/resources/META-INF/xposed/module.prop").read_text()
        self.assertIn("autoHotReload=false", prop)

    def test_telemetry_sdk_removed(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertNotIn("implementation(libs.appcenter", gradle)
        self.assertNotIn("Analytics.trackEvent", (ROOT / "app/src/main/java/io/github/qauxv/util/CliOper.java").read_text())

    def test_version_code_uses_commit_count_without_upgrade_offset(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertRegex(gradle, r"(?m)^\s*versionCode = Common\.getBuildVersionCode\(rootProject\)\s*$")
        spec = importlib.util.spec_from_file_location("apk_version_audit", ROOT / "scripts/verify_diagnostic_apk.py")
        audit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(audit)
        # Below the old branch is intentional. A naturally large future count is valid too.
        for count in (3052, 3171, 11000):
            text = f"package: name='io.github.qauxv' versionCode='{count}' versionName='1.6.1.r{count}.abcdef0'"
            self.assertEqual(int(audit.validate_package(text)[2]), count)
        for code, name in ((13050, "1.6.1.r3050.abcdef0"), (0, "1.6.1.r0.abcdef0"),
                           (-1, "1.6.1.r1.abcdef0"), (3052, "standalone")):
            with self.assertRaises(RuntimeError):
                audit.validate_package(f"package: name='io.github.qauxv' versionCode='{code}' versionName='{name}'")
        with self.assertRaises(RuntimeError):
            audit.validate_package("package: name='wrong.package' versionCode='3052' versionName='1.6.1.r3052.abcdef0'")

    def test_no_active_network_or_account_access(self):
        for path in SRC.iterdir():
            text = code(path.name)
            self.assertNotRegex(text, r"(?i)\b(?:okhttp|HttpURLConnection|Socket|AppRuntimeHelper|Analytics|Crashes)\b")
            self.assertNotRegex(text, r"get(?:Uin|Token|Cookie|WupBuffer|SendData|MsgBody)\s*\(")


if __name__ == "__main__":
    unittest.main()
