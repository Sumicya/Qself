"""输入/角标/事件转交的源码契约，只约束已发现的接线错误，不等同 Android UI 测试。"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/sumicya/qself'
LOOKS = (SRC / 'Looks.kt').read_text()
INPUT = (SRC / 'Input.kt').read_text()


class AppearanceContractTest(unittest.TestCase):
    def test_counter_hooks_badge_binding_before_number_becomes_wording(self):
        self.assertIn('getDeclaredMethod("a", badge,', LOOKS)
        self.assertIn('hook(binding, gated = false)', LOOKS)
        self.assertIn('unclampedBadgeArgs(args)', LOOKS)
        self.assertIn('"setRedNum", "setGrayNum", "setAIOBarNum"', LOOKS)
        self.assertNotIn('hook(badge.method("updateNum"))', LOOKS)

    def test_counter_reenable_replays_known_data_and_wording_clears_it(self):
        self.assertIn('else refreshNativeBadges?.invoke()', LOOKS)
        self.assertIn('nativeTexts.remove(view)', LOOKS)
        self.assertIn('counts.remove(view)', LOOKS)
        self.assertIn('"setRedText", "setGrayText", "setRedDot", "setGrayDot"', LOOKS)

    def test_nt_input_is_discovered_even_while_off(self):
        self.assertIn('InputEditTextVBDelegate', INPUT)
        self.assertIn('getDeclaredMethod("U", cls("com.tencent.mobileqq.aio.input.adorn.e"))', INPUT)
        self.assertIn('hook(prepared, gated = false)', INPUT)
        self.assertIn('::inputLayout', (SRC / 'Qself.kt').read_text())
        self.assertNotIn('putBoolean("TG输入栏", true)', INPUT)

    def test_editors_are_unique_and_setting_changes_refresh_them(self):
        self.assertIn('WeakHashMap<EditText, WeakReference<NativeInput>>()', INPUT)
        self.assertIn('inputs[edit]?.get()', INPUT)
        self.assertIn('refreshInputLayout()', LOOKS)
        self.assertIn('edit.viewTreeObserver.addOnPreDrawListener(this)', INPUT)
        self.assertNotIn('setMinimumHeight', INPUT)

    def test_round_bar_has_no_blur_or_border_and_badges_are_text_only(self):
        glass = (ROOT / 'app/src/main/kotlin/sumicya/qself/Glass.kt').read_text()
        branch = glass.split('if (roundBar)', 1)[1].split('lens.setFloatUniform', 1)[0]
        for forbidden in ('backdrop(', 'drawBorder', 'STROKE', 'RenderEffect'):
            self.assertNotIn(forbidden, branch)
        self.assertIn('drawRoundRect', branch)
        self.assertIn('pill(canvas, night)', branch)
        self.assertIn('roundBar = true', LOOKS)
        self.assertIn('badge.getDeclaredMethod("onDraw"', LOOKS)
        self.assertIn('countTabWidths', LOOKS)

    def test_background_drag_replays_down_after_native_cancel(self):
        body = LOOKS.split('private class BarTouch(', 1)[1].split('private val barTouchHosts', 1)[0]
        self.assertLess(body.index('try { native(cancel) }'), body.index('send(source, dest, first)'))
        self.assertLess(body.index('send(source, dest, first)'), body.index('if (forwarding) send(source, dest, event)'))
        self.assertIn('copy.transform(matrix); copy.offsetLocation(offsetX, offsetY); dest.dispatchTouchEvent(copy)', body)
        self.assertIn('onViewDetachedFromWindow(view: View) = cancel()', body)
        self.assertNotIn('onInterceptTouchEvent(', body)
        self.assertNotIn('onTouchEvent(', body)

    def test_background_target_is_qq_registered_pager_not_a_size_guess(self):
        self.assertIn('cls("com.tencent.mobileqq.tab.TabFrameLayout")', LOOKS)
        self.assertIn('frame.getDeclaredField("e")', LOOKS)
        self.assertIn('frame.getDeclaredMethod("a")', LOOKS)
        self.assertIn('if (tab.get(owner) !== bar) null', LOOKS)
        self.assertIn('?.takeIf { it.parent === bar.parent }', LOOKS)
        self.assertIn('target = WeakReference(pager)', LOOKS)
        for forbidden in ('scrollTarget(', 'canScrollVertically(', 'pager.width / 2', 'contains("ViewPager")'):
            self.assertNotIn(forbidden, LOOKS)

    def test_menus_do_not_implement_the_background_drag_request(self):
        menu = (SRC / 'Knob.kt').read_text()
        self.assertNotIn('dim.setOnTouchListener', menu)
        self.assertNotIn('heading.setOnTouchListener', menu)
        self.assertIn('dim.setOnClickListener { close() }', menu)


if __name__ == '__main__':
    unittest.main()
