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
        self.assertNotIn('putBoolean("输入栏药丸", true)', INPUT)

    def test_editors_are_unique_and_setting_changes_refresh_them(self):
        self.assertIn('WeakHashMap<EditText, WeakReference<NativeInput>>()', INPUT)
        self.assertIn('inputs[edit]?.get()', INPUT)
        self.assertIn('refreshInputLayout()', LOOKS)
        self.assertIn('edit.viewTreeObserver.addOnPreDrawListener(this)', INPUT)
        self.assertNotIn('setMinimumHeight', INPUT)

    def test_settings_capsule_uses_kernel_material_and_badges_are_text_only(self):
        glass = (SRC / 'Glass.kt').read_text()
        self.assertNotIn('if (roundBar)', glass)
        self.assertIn('kernelBar || kernelButton', glass)
        self.assertIn('kernelBar = true', LOOKS)
        knob = (SRC / 'Knob.kt').read_text()
        self.assertIn('kernelButton = true', knob)
        self.assertIn('badge.getDeclaredMethod("onDraw"', LOOKS)
        self.assertIn('countTabWidths', LOOKS)

    def test_background_drag_replays_down_after_native_cancel(self):
        body = LOOKS.split('private class BarTouch(', 1)[1].split('private val barTouchHosts', 1)[0]
        self.assertLess(body.index('try { native(cancel) }'), body.index('send(source, dest, first)'))
        self.assertLess(body.index('send(source, dest, first)'), body.index('if (forwarding && dest != null) send(source, dest, event)'))
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
        self.assertIn('target = pager?.let { WeakReference(it) }', LOOKS)
        for forbidden in ('scrollTarget(', 'canScrollVertically(', 'pager.width / 2', 'contains("ViewPager")'):
            self.assertNotIn(forbidden, LOOKS)

    def test_menu_and_input_share_material_without_darkening_the_backdrop(self):
        menu = (SRC / 'Knob.kt').read_text().split('private fun sheet(', 1)[1]
        self.assertIn('kernelSurface = true', menu)
        self.assertIn('kernelSurface = true', INPUT)
        self.assertNotIn('0x66000000', menu)
        self.assertNotIn('borderlessRipple', menu)
        self.assertNotIn('pressMotion()', menu)
        self.assertIn('配置已开启', menu)
        self.assertIn('不代表效果已验证', menu)
        self.assertIn('部分功能需重进页面或重启', menu)
        self.assertIn('只结束当前进程', menu)
        self.assertNotIn('indices step 2', menu)
        self.assertIn('grid.addView(TextView', menu)
        self.assertIn('grid.addView(LinearLayout', menu)

    def test_menu_repositions_and_releases_layout_listener(self):
        menu = (SRC / 'Knob.kt').read_text().split('private fun sheet(', 1)[1]
        self.assertIn('anchor.transformMatrixToGlobal(matrix)', menu)
        self.assertIn('decor.transformMatrixToLocal(matrix)', menu)
        self.assertIn('WindowInsets.Type.ime()', menu)
        self.assertIn('addOnGlobalLayoutListener(layout)', menu)
        self.assertIn('removeOnGlobalLayoutListener(layout)', menu)
        self.assertIn('card.pivotX', menu)
        self.assertIn('card.pivotY', menu)

    def test_menus_do_not_implement_the_background_drag_request(self):
        menu = (SRC / 'Knob.kt').read_text()
        sheet_body = menu.split('private fun sheet(', 1)[1]
        kill_body = sheet_body.split('addView(button("结束进程")', 1)[1].split('addView(button("好"', 1)[0]
        self.assertNotIn('dim.setOnTouchListener', menu)
        self.assertNotIn('heading.setOnTouchListener', menu)
        self.assertIn('dim.setOnClickListener { close() }', menu)
        self.assertIn('TAG_SHEET_CLOSE', menu)
        self.assertIn('anchor.bringToFront()', menu)
        self.assertNotIn('AlertDialog', kill_body)
        self.assertIn('android.os.Process.killProcess(android.os.Process.myPid())', kill_body)


if __name__ == '__main__':
    unittest.main()
