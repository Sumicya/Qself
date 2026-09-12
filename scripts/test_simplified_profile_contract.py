#!/usr/bin/env python3
"""Source-level startup and migration contracts; generated registries are also tested on JVM."""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java'
ALLOWED = {line.split('#')[0].strip() for line in (ROOT / 'config/simplified-features.txt').read_text().splitlines()} - {''}
class ProfileContract(unittest.TestCase):
    def test_inventory_is_explicit_and_resolves(self):
        for name in ALLOWED:
            files = [SRC / (name.replace('.', '/') + e) for e in ('.kt', '.java')]
            self.assertTrue(any(p.exists() and ('@FunctionHookEntry' in p.read_text() or '@UiItemAgentEntry' in p.read_text()) for p in files), name)
        self.assertIn('sumicya.qself.feature.device.RiskReportInterceptor', ALLOWED)
        self.assertIn('sumicya.qself.diagnostics.ReportDiagnostics', ALLOWED)
        self.assertIn('cc.ioctl.hook.misc.CleanUpMitigation', ALLOWED)
        self.assertNotIn('sumicya.qself.feature.device.ForcePadMode', ALLOWED)
    def test_shortcuts_and_early_init_are_inventory_members(self):
        catalog = (SRC / 'sumicya/qself/ui/HomeCatalog.kt').read_text()
        self.assertTrue(set(re.findall(r'"([a-z][a-z0-9_.]+\.[A-Z][A-Za-z0-9]+)"', catalog)) <= ALLOWED)
        main = (SRC / 'io/github/qauxv/core/MainHook.java').read_text()
        allowed_simple = {s.rsplit('.', 1)[1] for s in ALLOWED}
        self.assertTrue(set(re.findall(r'allowEarlyInit\((\w+)\.INSTANCE\)', main)) <= allowed_simple)
        self.assertNotIn('ExternalModuleChainLoader.loadExternalModulesForStartup()', main)
    def test_shared_dispatchers_do_not_construct_legacy_decorators(self):
        arrays = {
            'cc/hicore/message/chat/SessionHooker.java': ['RepeaterPlus', 'InputButtonHookDispatcher'],
            'io/github/qauxv/router/dispacher/InputButtonHookDispatcher.java': ['ReplyMsgWithImg'],
            'com/xiaoniu/dispatcher/MenuBuilderHook.kt': ['RepeaterPlus'],
            'me/ketal/dispacher/BaseBubbleBuilderHook.kt': ['ChatItemShowQQUin','RevokeWrapHint','GroupAdminMenu','AvatarRounding'],
        }
        for file, expected in arrays.items():
            s = (SRC / file).read_text()
            body = re.search(r'(?:DECORATORS = \{|decorators.*?= arrayOf(?:<[^>]+>)?\()(.*?)(?:\};|\n    \))', s, re.S).group(1)
            actual = [x.strip().removesuffix('.INSTANCE').rsplit('.', 1)[-1] for x in body.split(',') if x.strip()]
            self.assertEqual(actual, expected)
    def test_tail_renderer_does_not_instantiate_removed_decorators(self):
        s = (SRC / "me/ketal/hook/ChatItemShowQQUin.kt").read_text()
        self.assertNotIn("FlashPicHook", s)
        self.assertNotIn("PromptForNoSeqMessage", s)

    def test_initialize_is_gated_before_preparation_or_installation(self):
        for name in ('BaseFunctionHook','BaseComponentHook','BaseHookDispatcher','BasePersistBackgroundHook'):
            s = (SRC / f'io/github/qauxv/hook/{name}.kt').read_text().split('override fun initialize(): Boolean {')[1]
            self.assertLess(s.index('SimplifiedProfile.isAllowed(this)'), s.index('if (mInitialized)'))
        installer = (SRC / 'io/github/qauxv/core/HookInstaller.java').read_text()
        self.assertEqual(installer.count('if (!sumicya.qself.profile.SimplifiedProfile.isAllowed(hook)) return;'), 3)
    def test_ipc_never_transmits_or_consumes_legacy_numeric_indices(self):
        s = (SRC / 'io/github/qauxv/util/SyncUtils.java').read_text()
        self.assertIn('HOOK_DO_INIT_SIMPLIFIED_V1', s)
        self.assertNotIn('putExtra("hook", hookId)', s)
        self.assertNotIn('getIntExtra("hook", -1)', s)
        self.assertIn('VERSION_NAME.equals(intent.getStringExtra("qselfBuild"))', s)
        self.assertIn('candidate.getClass().getName().equals(hookClass)', s)
    def test_regular_pages_do_not_allocate_optical_renderers(self):
        for file in ('sumicya/qself/ui/SettingsVisuals.kt','sumicya/qself/ui/SettingsListLayout.kt',
                     'sumicya/qself/ui/SettingsHomeView.kt','io/github/qauxv/activity/SettingsUiFragmentHostActivity.kt'):
            self.assertNotIn('SettingsGlass.', (SRC / file).read_text())
        self.assertIn('Theme.Material3Expressive.DayNight.NoActionBar', (ROOT / 'app/src/main/res/values/qself_expressive.xml').read_text())
        self.assertIn('MaterialCardView(context)', (SRC / 'sumicya/qself/ui/SettingsHomeView.kt').read_text())
    def test_generated_registries_filter_before_emitting_instances(self):
        for file in ('FunctionHookEntryItemProcessor.kt','UiItemAgentEntryProcessor.kt'):
            s = (ROOT / 'libs/ksp/src/main/kotlin/cn/lliiooll/processors' / file).read_text()
            self.assertIn('allSymbols.filter { it.qualifiedName?.asString() in allowed }', s)
            self.assertIn('symbols.forEachIndexed', s)
        self.assertIn('"test_simplified_profile_contract.py"', (ROOT / 'app/build.gradle.kts').read_text())
if __name__ == '__main__':
    unittest.main()
