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
        stamp = STYLE.split('private class ExternalStamp(', 1)[1].split('fun messageTimestamps()', 1)[0]
        self.assertIn('container.addView(labelView)', stamp)
        self.assertIn('container.removeView(labelView)', stamp)
        self.assertIn('Gravity.END', stamp)
        self.assertIn('Gravity.START', stamp)
        self.assertNotIn('v.text =', stamp)
        bind = STYLE.split('fun messageTimestamps()', 1)[1].split('private val inAioAvatarLoad', 1)[0]
        self.assertIn('AIOBubbleMsgItemVB', bind)
        self.assertIn('gated = false', bind)

    def test_avatar_radius_uses_native_roundrect_and_restores_saved_values(self):
        self.assertIn('"setCornerRadiusAndMode", Integer.TYPE, Integer.TYPE', STYLE)
        self.assertIn('com.tencent.qqnt.avatar.meta.b\\$a', STYLE)
        self.assertIn('com.tencent.qqnt.avatar.b\\$a', STYLE)
        self.assertIn('com.tencent.mobileqq.proavatar.QQProAvatarDrawable', STYLE)
        self.assertIn('com.tencent.mobileqq.proavatar.e\\$a', STYLE)
        self.assertIn('getCircleFaceBitmap', STYLE)
        self.assertIn('setter.invoke(v, radius, if (radius > 0) 1 else 0)', STYLE)
        self.assertIn('setter.invoke(v, nativeRadius, nativeMode)', STYLE)
        self.assertIn('TIME_FORMAT_GUIDE', MENU)

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

    def test_all_message_types_have_timestamps_and_top_timestamp_slot_restores(self):
        for component in (
            'AIOTextContentComponent',
            'AIOFileContentComponent',
            'AIOPicContentComponent',
            'AIOReplyComponent',
            'AIOMixContentComponent',
            'AIOPttContentComponent',
            'AIOMarketFaceComponent',
            'AIOArkContentComponent',
            'AIOVideoContentComponent',
            'AIOTimestampComponent',
        ):
            self.assertIn(component, STYLE)
        self.assertIn('ExternalStamp', STYLE)
        self.assertIn('externalStamps.values.toList().forEach { it.dispose() }', STYLE)
        self.assertIn('timestampSlots.entries.toList().forEach { (slot, saved) -> slot.visibility = saved }', STYLE)

    def test_native_and_settings_menus_track_single_item_and_style_danger_actions(self):
        self.assertIn('QQCustomMenuExpandableLayout', MENU)
        self.assertIn('dangerousMenuItem', MENU)
        self.assertIn('hitMenuItemIndex', MENU)
        self.assertIn('active.forEachIndexed { index, view -> view.isPressed = index == hit }', MENU)
        self.assertIn('if (hit in active.indices) active[hit].performClick()', MENU)
        self.assertIn('nativeMenus.values.toList().forEach { it.dispose() }; nativeMenus.clear()', MENU)
        self.assertIn('nativeMessageMenu()', (SRC / 'Looks.kt').read_text())

    def test_nagramxf_header_bubbles_input_and_vertical_popup_menu_contracts(self):
        looks = (SRC / 'Looks.kt').read_text()
        inp = (SRC / 'Input.kt').read_text()
        self.assertIn('aioHeader()', looks)
        self.assertIn('nagramHeaderLayout', looks)
        self.assertIn('com.tencent.mobileqq.aio.title.AIOTitleVB', looks)
        self.assertIn('com.tencent.mobileqq.aio.widget.AIOTitleRelativeLayout', looks)
        self.assertIn('com.tencent.qqnt.avatar.a', looks)
        self.assertIn('nagramBubbleRadii', CHAT)
        self.assertIn('nagramRowMargins', CHAT)
        self.assertIn('BaseMixContentComponent', CHAT)
        self.assertIn('AIOUnsuportContentComponent', CHAT)
        self.assertIn('BottomBlurVB', inp)
        self.assertIn('AIOInputExpandableVB', inp)
        self.assertIn('AIOShortcutBarVB', inp)
        self.assertIn('SendButtonPill', inp)
        self.assertIn('com.tencent.qui.smoothcorner.b', inp)
        self.assertIn('LinearLayout.VERTICAL', MENU)
        self.assertIn('48 * dp', MENU)
        self.assertIn('18 * dp', MENU)

    def test_menu_never_hides_source_view_and_bubble_padding_and_width_contracts(self):
        hook_src = (SRC / 'Hook.kt').read_text()
        looks_src = (SRC / 'Looks.kt').read_text()
        inp_src = (SRC / 'Input.kt').read_text()
        self.assertIn('SafeChain', hook_src)
        self.assertIn('sourceField', MENU)
        self.assertIn('if (src.visibility == View.GONE) src.visibility = View.VISIBLE', MENU)
        self.assertIn('MATCH_PARENT_RESTORE_TAG', STYLE)
        self.assertIn('val pad5 = (5 * dp).roundToInt()', CHAT)
        self.assertIn('android.widget.FasterTextView', CHAT)
        self.assertIn('setIncludeFontPadding', CHAT)
        self.assertIn('cleanQuoteTree(q)', CHAT)
        self.assertIn('if (q.background != null) q.background = null', CHAT)
        self.assertIn('nicks.forEach { it.apply(if (enabled && continued) View.GONE else null) }', CHAT)
        self.assertIn('dispatchPillTouch', looks_src)
        self.assertIn('SecNavBarState', looks_src)
        self.assertIn('SearchBarState', looks_src)
        self.assertIn('com.tencent.mobileqq.aio.input.inputbar.compat.e\\$b', inp_src)
        self.assertIn('com.tencent.qqnt.aio.shortcutbar.PanelIconLinearLayout', inp_src)
        self.assertIn('MsgListUnderScroll', inp_src)
        self.assertIn('com.tencent.mobileqq.aio.msglist.AIOMsgListVB', inp_src)
        self.assertIn('com.tencent.qqnt.aio.adapter.api.impl.AIOChatBackgroundApiImpl', inp_src)
        self.assertIn('com.tencent.richframework.compat.immersive.RFWNavigationBarImmersiveHelper', inp_src)
        self.assertIn('collectBubbleInners', CHAT)
        self.assertIn('setWrapperPadding', CHAT)
        self.assertIn('zeroHorizontalInsets', CHAT)
        self.assertIn('run.layout?.dispose()', CHAT)
        self.assertIn('com.tencent.qqnt.avatar.impl.engine.a', STYLE)
        self.assertIn('aioAvatarOptions', STYLE)
        self.assertIn('aioProDrawables', STYLE)
        self.assertIn('kk/k 1-24时', STYLE)


if __name__ == '__main__':
    unittest.main()
