#!/usr/bin/env python3
"""Source-level startup and migration contracts; generated registries are also tested on JVM."""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java'
ROWS = [line.split('\t') for line in (ROOT / 'config/feature-catalog.tsv').read_text().splitlines() if line and not line.startswith('#')]
ALLOWED = {row[5] for row in ROWS}
class ProfileContract(unittest.TestCase):
    def test_inventory_is_explicit_and_resolves(self):
        for name in ALLOWED:
            files = [SRC / (name.split('$')[0].replace('.', '/') + e) for e in ('.kt', '.java')]
            annotation = r'(?m)^\s*@(?:FunctionHookEntry|UiItemAgentEntry|\[[^\]]*\b(?:FunctionHookEntry|UiItemAgentEntry)\b)'
            self.assertTrue(any(p.exists() and re.search(annotation, p.read_text()) for p in files), name)
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
        for restored in ('CustomSplash', 'MuteQZoneThumbsUp', 'GagInfoDisclosure', 'RemoveCameraButton', 'RemoveSuperQQShow', 'OptXListViewScrollBar'):
            self.assertIn('allowEarlyInit(' + restored + '.INSTANCE)', main)
    def test_shared_dispatchers_do_not_construct_legacy_decorators(self):
        arrays = {
            'cc/hicore/message/chat/SessionHooker.java': ['RepeaterPlus', 'InputButtonHookDispatcher'],
            'io/github/qauxv/router/dispacher/InputButtonHookDispatcher.java': ['ReplyMsgWithImg','AioChatPieClipPasteHook','CtrlEnterToSend'],
            'com/xiaoniu/dispatcher/MenuBuilderHook.kt': ['RepeaterPlus','CopyCardMsg','MessageCopyHook','PttForwardHook','PicMd5Hook','PicCopyToClipboard','CopyMarkdown'],
            'me/ketal/dispacher/BaseBubbleBuilderHook.kt': ['ChatItemShowQQUin','ShowMsgAt','HideTroopLevel','MultiForwardAvatarHook','RevokeWrapHint','GroupAdminMenu','AvatarRounding'],
        }
        for file, expected in arrays.items():
            s = (SRC / file).read_text()
            body = re.search(r'(?:DECORATORS = \{|decorators.*?= arrayOf(?:<[^>]+>)?\()(.*?)(?:\};|\n    \))', s, re.S).group(1)
            actual = [x.strip().removesuffix('.INSTANCE').rsplit('.', 1)[-1] for x in body.split(',') if x.strip()]
            self.assertEqual(actual, expected)
    def test_tail_rendering_is_merged_and_clears_recycled_views(self):
        s = (SRC / "me/ketal/hook/ChatItemShowQQUin.kt").read_text()
        self.assertNotIn("FlashPicHook", s)
        self.assertIn("MessageTailPolicy.resolve", s)
        self.assertIn('text = ""; tag = null; isClickable = false', s)
        old = (SRC / 'nep/timeline/PromptForNoSeqMessage.kt').read_text()
        self.assertNotIn('onGetViewNt', old)
        self.assertNotIn('ID_ADD_LAYOUT', old)
        self.assertIn('object PromptForNoSeqMessage : CommonSwitchFunctionHook()', old)

    def test_initialize_is_gated_before_preparation_or_installation(self):
        for name in ('BaseFunctionHook','BaseComponentHook','BaseHookDispatcher','BasePersistBackgroundHook'):
            s = (SRC / f'io/github/qauxv/hook/{name}.kt').read_text().split('override fun initialize(): Boolean {')[1]
            self.assertLess(s.index('SimplifiedProfile.isAllowed(this)'), s.index('if (mInitialized)'))
        installer = (SRC / 'io/github/qauxv/core/HookInstaller.java').read_text()
        self.assertEqual(installer.count('if (!sumicya.qself.profile.SimplifiedProfile.isAllowed(hook)) return;'), 3)
    def test_cache_override_cannot_delete_before_reaching_the_base_guard(self):
        s = (SRC / 'io/github/duzhaokun123/util/CacheManager.kt').read_text().split('override fun initialize(): Boolean {')[1]
        self.assertLess(s.index('SimplifiedProfile.isAllowed(this)'), s.index('ConfigManager.getDefaultConfig()'))
        base = (SRC / 'io/github/qauxv/hook/BaseFunctionHook.kt').read_text()
        self.assertNotIn('enableAllHook()', base)
        self.assertNotIn('EnableAllHook.enabled', base)

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
        self.assertIn('SettingsAccordion(context', (SRC / 'sumicya/qself/ui/SettingsHomeView.kt').read_text())
    def test_generated_registries_filter_before_emitting_instances(self):
        for file in ('FunctionHookEntryItemProcessor.kt','UiItemAgentEntryProcessor.kt'):
            s = (ROOT / 'libs/ksp/src/main/kotlin/cn/lliiooll/processors' / file).read_text()
            self.assertIn('allSymbols.filter { it.qualifiedName?.asString() in allowed }', s)
            self.assertIn('symbols.forEachIndexed', s)
        self.assertIn('"test_simplified_profile_contract.py"', (ROOT / 'app/build.gradle.kts').read_text())
    def test_catalog_is_the_only_registration_and_route_source(self):
        self.assertEqual(len(ROWS), len(ALLOWED))
        self.assertTrue(all(len(row) == 6 for row in ROWS))
        self.assertFalse((ROOT / 'config/simplified-features.txt').exists())
        self.assertFalse((SRC / 'sumicya/qself/feature/consolidation/AdPurifySuite.kt').exists())
        build = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('config/feature-catalog.tsv', build)
        self.assertIn('qself.catalogRows', build)
        for file in ('SettingsMainFragment.kt','SearchOverlaySubFragment.kt','FuncStatListFragment.kt'):
            self.assertIn('FunctionEntryRouter.locationForProvider', (SRC / 'io/github/qauxv/fragment' / file).read_text())

    def test_shared_menu_deduplicates_the_actual_method_and_uses_current_receiver(self):
        s = (SRC / 'com/xiaoniu/dispatcher/MenuBuilderHook.kt').read_text()
        self.assertIn('HookInstallRegistry<Method>()', s)
        self.assertIn('hookedMethods.install(menuMethod)', s)
        self.assertIn('val target = param.thisObject.javaClass.name', s)
        self.assertIn('if (!hook.isEnabled || !hook.isAvailable) continue', s)
        self.assertNotIn('hookedClasses', s)

if __name__ == '__main__':
    unittest.main()
