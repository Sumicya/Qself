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

    def test_status_bar_is_hidden_and_header_sits_at_window_top(self):
        # 真机日志：statusBars=[0,141,0,0]、顶栏容器 padTop=141、标题栏 y=141 高=99 → 屏幕最上面
        # 141px 只有系统状态栏，玻璃胶囊浮在它下面。玻璃底栏开启时整条状态栏藏掉，这 141px 归零。
        looks_src = (SRC / 'Looks.kt').read_text()
        self.assertIn('WindowInsets.Type.statusBars()', looks_src)
        self.assertIn('BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE', looks_src)
        self.assertIn('controller.hide(WindowInsets.Type.statusBars())', looks_src)
        self.assertIn('controller.show(WindowInsets.Type.statusBars())', looks_src)
        # 状态栏藏掉后，安全区百分比不再生效（否则顶栏还留着那条 141px 空白）
        reduced = looks_src.split('internal fun reducedSafeTop(', 1)[1].split('private class HeaderPillsDrawable', 1)[0]
        self.assertIn('val percent = if (systemStatusBarHidden()) 0 else safeTopPercent()', reduced)
        self.assertIn('aioTopCompensation()', looks_src)
        # 每次到前台、拿到焦点、布局变化都要再藏一遍（QQ 会自己把状态栏放回来）
        self.assertIn('callActivityOnResume', looks_src)
        self.assertIn('onWindowFocusChanged', looks_src)

    def test_message_list_starts_under_the_floating_header(self):
        inp_src = (SRC / 'Input.kt').read_text()
        self.assertIn('aioHeaderBottom()', inp_src)
        self.assertIn('group.getLocationInWindow(at)', inp_src)
        # 顶栏透明：消息区对齐到窗口 y=0，顶部内边距取标题栏底边，不再用负外边距硬拉
        self.assertIn('(headerBottom - groupTop).coerceAtLeast(0)', inp_src)
        self.assertNotIn('applyBleed', inp_src)
        # 底部让位按消息区实际底边算，输入浮层不会吞掉最后一行
        self.assertIn('aioBottomOverlayPx - covered', inp_src)

    def test_ark_card_keeps_native_width_when_stamp_is_added(self):
        # 卡片 (Ark) 按自己的宽度整块绘制：把内容根改成 WRAP_CONTENT 会被 AT_MOST 量窄，
        # 真机 26.10.24 截图里卡片右侧一列按钮与链接图标被卡片自身边界裁掉。宽度必须留在原生 MATCH_PARENT。
        stamp = STYLE.split('private class ExternalStamp(', 1)[1].split('fun messageTimestamps()', 1)[0]
        self.assertIn('var keepWidth = false', stamp)
        self.assertIn('!keepWidth && !isArkCard(ch) && chLp.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT', stamp)
        helper = STYLE.split('private fun isArkCard(', 1)[1].split('private class ExternalStamp(', 1)[0]
        self.assertIn('ArkApp', helper)
        bind = STYLE.split('fun messageTimestamps()', 1)[1].split('private val inAioAvatarLoad', 1)[0]
        self.assertIn('arkCard = name.endsWith(".AIOArkContentComponent")', bind)

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
        self.assertNotIn('Paint.Style.STROKE', CHAT)
        self.assertIn('AIOBubbleSkinInfo\\$BackgroundImageInfo', CHAT)
        self.assertIn('setWrapperMinSize', CHAT)
        self.assertIn('GLASS_BLUR', MENU)
        self.assertIn('BUBBLE_CORNER', MENU)
        self.assertIn('BUBBLE_PADDING', MENU)
        self.assertNotIn('HEADER_TOP_OFFSET', MENU)
        self.assertNotIn('headerTopOffsetDp', STYLE)
        self.assertIn('timeFormatterCache', STYLE)
        self.assertIn('override fun setTintList(tint: ColorStateList?)', MENU)
        self.assertIn('doubleTapReaction', CHAT)
        self.assertIn('pttSpeedMemory', CHAT)
        self.assertIn('PanelGlassState', inp_src)
        self.assertIn('EdgeTransparentView', inp_src)
        self.assertIn('com.tencent.mobileqq.aio.reserve1.blur.f', inp_src)
        self.assertIn('pageAnimations()', looks_src)
        self.assertIn('runSwitchTransition', looks_src)
        self.assertIn('updateUIAfterTabChange', looks_src)
        self.assertIn('sharedScene', looks_src)
        self.assertIn('AIODefaultTitleContainerVB', looks_src)
        self.assertIn('ImmersiveTitleBar2', looks_src)
        self.assertIn('reducedSafeTop', looks_src)
        self.assertIn('centeredBarTranslationX', looks_src)
        self.assertIn('syncStatusBarTintViews', looks_src)
        self.assertIn('homeTopBar', looks_src)
        self.assertIn('homeListClip', looks_src)
        self.assertIn('uniformTabWidth', looks_src)
        self.assertIn('tabLayoutGravityField', looks_src)
        self.assertIn('tabLayoutMinWidthField', looks_src)
        self.assertIn('tabDragSetGravityMethod', looks_src)
        self.assertIn('checkEnableTabAnim', looks_src)
        self.assertIn('MainUIHelperApiImpl', looks_src)
        self.assertIn('SystemBarTintManager', looks_src)
        self.assertIn('0x7f0a11f9', looks_src)
        self.assertIn('0x7f0a6594', looks_src)
        self.assertIn('0x7f0a3245', looks_src)
        self.assertIn('com.tencent.mobileqq.aio.utils.AIOUtil', looks_src)
        self.assertIn('com.tencent.mobileqq.aio.reserve1.mask.b', looks_src)
        self.assertIn('com.tencent.mobileqq.aio.reserve1.mask.b', inp_src)
        self.assertIn('AbsMsgListVB', inp_src)
        self.assertIn('TitleAreaHeadLayout', looks_src)
        self.assertIn('SkinnableRecycleView', looks_src)
        self.assertIn('SimpleModeHelper', looks_src)
        self.assertIn('MainResourceConfig', looks_src)
        self.assertIn('0x7f0a6596', looks_src)
        self.assertIn('uUpMask', looks_src)
        diag_src = (SRC / 'DiagLog.kt').read_text()
        merge_src = (SRC / 'LogMerge.kt').read_text()
        self.assertIn('fullQqLogger', diag_src)
        self.assertIn('exportFullQqLog', diag_src)
        self.assertIn('com.tencent.qphone.base.util.QLog', diag_src)
        self.assertIn('addLogItem', diag_src)
        self.assertIn('导出全量日志', MENU)
        # 顶栏安全区滑条：默认 0% 完全去掉，不再有 0.72f 比例下限。
        self.assertIn('SAFE_TOP', MENU)
        self.assertIn('SAFE_TOP_SHIFT', MENU)
        self.assertIn('SAFE_TOP_GUIDE', MENU)
        self.assertIn('safeTopPercent', looks_src)
        self.assertIn('safeTopShiftDp', looks_src)
        self.assertNotIn('origTop * 0.72f', looks_src)
        self.assertIn('HomeSafeArea', looks_src)
        self.assertIn('0x7f0a6cfa', looks_src)
        self.assertIn('0x7f0a0e96', looks_src)
        self.assertIn('aioLayoutSnapshot', looks_src)
        self.assertIn('SAFE_TOP', diag_src)

        # 反复导出只应产生一个 qself-full-log.txt：先与上一次导出合并去重，再清理历史编号副本。
        self.assertIn('mergeDiagnosticReports(prior', diag_src)
        self.assertIn('cleanupPriorExports(ctx)', diag_src)
        self.assertIn('LOG_FILE_NAME', diag_src)
        self.assertIn('"qself-full-log.txt"', merge_src)
        self.assertIn('LOG_FILE_PREFIX%', diag_src)
        self.assertIn('reportDeltaRecords(prior', diag_src)
        # 合并逻辑与 Android 解耦，才能用真机日志在 JVM 上直接回归。
        self.assertNotIn('import android', merge_src)
        # AIO 顶栏几何：每次布局后必须打一行账（状态栏高度/顶栏位置/消息区顶/fitsSystemWindows 祖先）。
        self.assertIn('private fun aioGeometry(bar: View)', looks_src)
        self.assertIn('AIO 几何 : 状态栏=', looks_src)
        self.assertIn('aioGeometry(bar)', looks_src)


if __name__ == '__main__':
    unittest.main()
