"""消息外观接线契约；真实布局、手势和 GPU 仍需设备验收。"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/sumicya/qself'
STYLE = (SRC / 'MessageStyle.kt').read_text()
MENU = (SRC / 'Knob.kt').read_text()
CHAT = (SRC / 'Chat.kt').read_text()


class MessageStyleContractTest(unittest.TestCase):
    def test_forward_private_helpers_are_accessible_before_install(self):
        for name in ('getForwardTargetKey', 'refreshRightBtn'):
            line = next(line for line in CHAT.splitlines() if 'val ' in line and f'"{name}"' in line)
            self.assertIn('getDeclaredMethod', line)
            self.assertIn('isAccessible = true', line)

    def test_icon_has_intrinsic_size_and_panel_never_highlights_as_one_item(self):
        self.assertIn('val glyphSize = (24*dp)', MENU)
        self.assertIn('override fun getIntrinsicWidth() = intrinsic', MENU)
        self.assertIn('override fun getIntrinsicHeight() = intrinsic', MENU)
        sheet = MENU.split('private fun sheet(', 1)[1]
        self.assertNotIn('kernelTouchDown', sheet)
        self.assertNotIn('override fun dispatchTouchEvent', sheet)
        self.assertIn('background = menuPress(dp)', sheet)
        self.assertIn('kernelSurface = true', sheet)

    def test_timestamp_is_an_overlay_not_appended_to_copyable_message(self):
        stamp = STYLE.split('private class MessageStamp(', 1)[1].split('fun messageTimestamps()', 1)[0]
        self.assertIn('v.overlay.add(this)', stamp)
        self.assertIn('vertical + line', stamp)
        self.assertIn('side = (10 * v.dp)', stamp)
        self.assertIn('vertical = (6 * v.dp)', stamp)
        self.assertIn('v.height - v.paddingBottom', stamp)
        self.assertNotIn('v.text =', stamp)
        self.assertIn('it.minimumWidth = minimum', stamp)
        self.assertIn('removeOnLayoutChangeListener(this)', stamp)
        bind = STYLE.split('fun messageTimestamps()', 1)[1].split('private class AvatarCorners', 1)[0]
        self.assertLess(bind.index('?.restore()'), bind.index('chain.proceed()'))
        self.assertIn('gated = false', bind)

    def test_avatar_radius_uses_native_roundrect_and_restores_saved_values(self):
        self.assertIn('"setCornerRadiusAndMode", Integer.TYPE, Integer.TYPE', STYLE)
        self.assertIn('if (enabled) radius else nativeRadius', STYLE)
        self.assertIn('if (enabled) 1 else nativeMode', STYLE)
        self.assertIn('corners.remove(it)?.dispose()', STYLE)

    def test_mentions_do_not_replace_native_movement_or_long_press(self):
        self.assertNotIn('movementMethod =', STYLE)
        self.assertIn('getSpans(range.start, range.end, ClickableSpan::class.java).isNotEmpty()', STYLE)
        self.assertIn('event.eventTime - down.third < ViewConfiguration.getLongPressTimeout()', STYLE)
        self.assertIn('scaledTouchSlop', STYLE)
        self.assertIn('chain.proceed(arrayOf<Any?>(cancel))', STYLE)
        self.assertIn('getUinFromUid', STYLE)
        self.assertIn('mentions.clear(); mentionDown.clear(); openMention = null', STYLE)

    def test_hot_reload_restores_new_features_and_settings_reset_parameters(self):
        self.assertIn('resetMessageStyle(); resetChatLayout()', (SRC / 'Qself.kt').read_text())
        self.assertIn('refreshMessageStyle()', (SRC / 'Looks.kt').read_text())
        self.assertIn('edit.putString(TIME_PATTERN, "HH:mm").putInt(AVATAR_RADIUS, 20)', MENU)
        self.assertIn('if (!validTimePattern(value)) format.error', MENU)


if __name__ == '__main__':
    unittest.main()
