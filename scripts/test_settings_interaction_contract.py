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
    def test_legacy_route_is_inline_and_never_creates_a_window(self):
        sheet = source('sumicya/qself/ui/SettingsOptionSheet.kt')
        host = source('io/github/qauxv/activity/SettingsUiFragmentHostActivity.kt')
        self.assertIn('InlineSettings.show', sheet)
        self.assertNotIn('DialogFragment', sheet)
        self.assertNotIn('showNow', sheet)
        self.assertIn('InlineSettings.presentFragment(this, fragment)', host)
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
        accordion = source('sumicya/qself/ui/SettingsAccordion.kt')
        self.assertIn('ValueAnimator.ofInt', accordion)
        self.assertIn('SettingsMotion.enter', source('sumicya/qself/ui/InlineSettings.kt'))
        self.assertIn('SettingsDynamicColors.apply(this)', source('io/github/qauxv/activity/SettingsUiFragmentHostActivity.kt'))
        self.assertIn('setCheckedWithoutAnimation', source('io/github/qauxv/dsl/item/UiAgentItem.kt'))
        self.assertIn('areAnimatorsEnabled', source('sumicya/qself/ui/SettingsMotion.kt'))
    def test_exact_count_is_registered_and_uses_current_badge_overloads(self):
        catalog = (ROOT / 'config/feature-catalog.tsv').read_text()
        self.assertEqual(catalog.count('cc.ioctl.hook.msg.ShowMsgCount'), 1)
        hook = source('cc/ioctl/hook/msg/ShowMsgCount.kt')
        for token in ['ExactCountCompat.updateMethods', 'hookAfterIfEnabled(method)', 'part("总消息数量")', 'return installed > 0']:
            self.assertIn(token, hook)
        self.assertNotIn('param.result = null', hook)
        self.assertIn('this@ShowMsgCount.hookAfterIfEnabled(this)', hook)
    def test_errors_have_detail_copy_export_without_toggling_the_switch(self):
        item = source('io/github/qauxv/dsl/item/UiAgentItem.kt')
        self.assertIn('if (hasFailure()) { showFailure(v); return }', item)
        self.assertIn('setNeutralButton("完整报告 / 导出")', item)
        self.assertIn('setPositiveButton("复制错误")', item)
        self.assertNotIn('v.context as Activity', item)
    def test_settings_transparency_is_retired_not_just_hidden(self):
        self.assertNotIn('sumicya.qself.ui.SettingsAppearanceItem', (ROOT / 'config/feature-catalog.tsv').read_text())
        appearance = source('sumicya/qself/ui/SettingsAppearanceItem.kt')
        self.assertIn('alpha = 255', appearance)
        self.assertNotIn('WINDOW_TRANSPARENCY, 12', appearance)
        self.assertNotIn('SettingsGlass.', source('sumicya/qself/ui/SettingsOptionSheet.kt'))
    def test_bar_rim_is_removed_in_all_render_paths(self):
        for file, name in [('LiquidGlassPanel.java', 'mHighlightPaint'), ('DropletPanel.java', 'mHighlight.'), ('LiquidGlassHostLayout.java', 'mBorderPaint')]:
            self.assertNotIn(name, source('sumicya/qself/glass/' + file))
    def test_journal_bounded_epoch_guarded_and_no_messages(self):
        text = source('sumicya/qself/diagnostics/FeatureJournal.kt')
        self.assertIn('ArrayBlockingQueue(128)', text)
        self.assertIn('epoch() != generation', text)
        self.assertIn('old.length() - 95', text)
        self.assertNotIn('error.message', text)
        self.assertIn('FeatureJournal.start(', source('io/github/qauxv/core/MainHook.java'))
if __name__ == '__main__': unittest.main()
