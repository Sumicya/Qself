#!/usr/bin/env python3
"""Source-level integration checks supplement, not replace, native/device tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
def source(path): return (ROOT / 'app/src/main/java' / path).read_text()
class SettingsInteractionContract(unittest.TestCase):
    def test_one_search_entry(self):
        self.assertNotIn('button(search, HomeCatalog.SEARCH', source('sumicya/qself/ui/SettingsHomeView.kt'))
        self.assertIn('mSearchMenuItem?.collapseActionView()', source('io/github/qauxv/fragment/SettingsMainFragment.kt'))
    def test_actual_modal_and_manual_host_restore(self):
        sheet = source('sumicya/qself/ui/SettingsOptionSheet.kt')
        host = source('io/github/qauxv/activity/SettingsUiFragmentHostActivity.kt')
        for token in ['BottomSheetDialogFragment()', 'onBackPressedDispatcher', 'onSaveInstanceState()', 'showNow(', 'repeatOnLifecycle']:
            self.assertIn(token, sheet)
        self.assertIn('SettingsOptionSheet.restore(this, it)', host)
        self.assertIn('it.saveForHost()', host)
        self.assertNotIn('R.anim.enter_from_right', host)
    def test_switch_save_occurs_in_confirmed_action(self):
        text = source('io/github/qauxv/dsl/item/UiAgentItem.kt')
        self.assertGreater(text.index('switchCellAgent?.isChecked = isChecked'), text.index('val action = {'))
        self.assertIn('restoreCheck(btn, previous)', text)
        self.assertIn('cell.switchView.isEnabled', text)
    def test_material_settings_reach_actual_renderer(self):
        panel = source('sumicya/qself/glass/LiquidGlassPanel.java')
        host = source('sumicya/qself/glass/LiquidGlassHostLayout.java')
        for token in ['GlassConfig.materialAlpha()', 'GlassConfig.background', 'GlassConfig.resolveNight']:
            self.assertIn(token, panel)
        self.assertIn('GlassConfig.tone', host)
        self.assertIn('live.refreshConfiguration()', source('sumicya/qself/glass/LiquidGlassInstaller.java'))
    def test_no_badge_alpha_flip_on_successful_draw(self):
        text = source('sumicya/qself/glass/BadgeNumbers.java')
        self.assertNotIn('badge.setAlpha(1f);\n            hookUpdateNumOnce', text)
        self.assertIn('GlassConfig.badgeMode == 2', text)
        self.assertIn('GlassConfig.badgeMode == 3', text)
    def test_tab_selection_is_explicit_and_keeps_messages(self):
        text = source('xyz/nextalone/hook/SimplifyBottomTab.kt')
        self.assertIn('defaultItems = setOf<String>()', text)
        self.assertIn('check(methods.isNotEmpty())', text)
        self.assertIn('method.parameterTypes', text.replace('it.parameterTypes', 'method.parameterTypes'))
        self.assertIn('//"消息"', text)
    def test_real_backdrop_has_no_synthetic_scene(self):
        text = source('sumicya/qself/ui/SettingsGlass.kt')
        for token in ['source.draw(capture)', 'uniform shader content', 'content.eval', 'getLocationOnScreen', 'discardDisplayList', 'removeOnPreDrawListener']:
            self.assertIn(token, text)
        self.assertNotIn('float3 scene', text)
        self.assertNotIn('Bitmap.createBitmap', text)
    def test_motion_and_system_palette_are_wired_to_production(self):
        sheet = source('sumicya/qself/ui/SettingsOptionSheet.kt')
        for token in ['MaterialSharedAxis.Y', 'beginDelayedTransition', 'setDismissWithAnimation', 'SettingsMotion.enter', 'endTransitions']:
            self.assertIn(token, sheet)
        self.assertIn('SettingsDynamicColors.apply(this)', source('io/github/qauxv/activity/SettingsUiFragmentHostActivity.kt'))
        self.assertIn('setCheckedWithoutAnimation', source('io/github/qauxv/dsl/item/UiAgentItem.kt'))
        self.assertIn('areAnimatorsEnabled', source('sumicya/qself/ui/SettingsMotion.kt'))
    def test_journal_bounded_epoch_guarded_and_no_messages(self):
        text = source('sumicya/qself/diagnostics/FeatureJournal.kt')
        self.assertIn('ArrayBlockingQueue(128)', text)
        self.assertIn('epoch() != generation', text)
        self.assertIn('old.length() - 95', text)
        self.assertNotIn('error.message', text)
        self.assertIn('FeatureJournal.start(', source('io/github/qauxv/core/MainHook.java'))
if __name__ == '__main__': unittest.main()
