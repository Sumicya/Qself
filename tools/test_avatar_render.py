"""头像渲染的源码契约：约束已发现的回归，不冒充 Android 渲染/手势测试。"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = (ROOT / 'app/src/main/kotlin/sumicya/qself/Chat.kt').read_text()
LOOKS = (ROOT / 'app/src/main/kotlin/sumicya/qself/Looks.kt').read_text()
RENDER = CHAT.split('private class RunAvatars(', 1)[1].split('fun refreshChatLayout()', 1)[0]


class AvatarRenderContractTest(unittest.TestCase):
    def test_scroll_predraw_never_relayouts_or_cancels_frame(self):
        body = RENDER.split('override fun onPreDraw()', 1)[1].split('override fun draw(', 1)[0]
        for forbidden in ('return false', '.requestLayout(', '.layout.apply(', '.spacing.apply(', '.nicks'):
            self.assertNotIn(forbidden, body)
        self.assertIn('commitFrame(next, clip)', body)

    def test_new_frame_explicitly_invalidates_overlay_without_idle_loop(self):
        body = RENDER.split('private fun commitFrame(', 1)[1].split('override fun onPreDraw()', 1)[0]
        self.assertIn('val changed =', body)
        self.assertIn('old.matrix != new.matrix', body)
        self.assertIn('new.view.isDirty', body)
        self.assertIn('if (changed) invalidateSelf()', body)
        self.assertNotIn('postInvalidateOnAnimation', RENDER)

    def test_draw_and_touch_share_float_local_matrices(self):
        self.assertNotIn('getLocationInWindow', RENDER)
        for operation in ('transformMatrixToGlobal', 'postConcat(toHost)', 'targetMatrix.invert(inverse)',
                          'canvas.concat(face.matrix)', 'copy.transform(face.toTarget)'):
            self.assertIn(operation, RENDER)

    def test_layout_changes_happen_at_bind_and_settings_refresh(self):
        binding = CHAT.split('hook(vb.method("k1"))', 1)[1].split('hook(adapter.method("n0"))', 1)[0]
        self.assertIn('capture(chain.thisObject, row, run)', binding)
        capture = CHAT.split('fun capture(', 1)[1].split('fun context(', 1)[0]
        self.assertLess(capture.index('run.layout?.apply('), capture.index('register(row, run)'))
        refresh = LOOKS.split('fun refreshLooks()', 1)[1].split('private fun watch(', 1)[0]
        self.assertIn('refreshChatLayout()', refresh)

    def test_fold_and_spacing_use_native_slots_and_active_auxiliary_components(self):
        self.assertIn('vb.getDeclaredField("G")', CHAT)
        self.assertIn('val active = extras.get(binder) as SparseArray<*>', CHAT)
        self.assertIn('slotNickType.isInstance(c)) -> (componentSlot.invoke(c)', CHAT)
        self.assertIn('component.getMethod("i1")', CHAT)
        self.assertIn('slots.map(::RunSpacing)', CHAT)
        self.assertIn('getDeclaredMethod("setVisibility", Integer.TYPE)', CHAT)
        self.assertIn('state.receive(chain.getArg(0) as Int)', CHAT)

    def test_compaction_uses_native_solver_only_on_registered_continuations(self):
        measure = CHAT.split('hook(constraints.getDeclaredMethod("onMeasure"', 1)[1].split('fun regroup(', 1)[0]
        self.assertIn('runMeasurements[row]?.get()', measure)
        self.assertIn('!run.continued', measure)
        self.assertIn('View.MeasureSpec.EXACTLY', measure)
        self.assertIn('child in excluded', measure)
        self.assertIn('widgetY.invoke(native)', measure)
        self.assertIn('widgetHeight.invoke(native)', measure)
        self.assertNotIn('requestLayout(', measure)
        self.assertIn('if (bindingVB.get() == true ||', CHAT)
        self.assertIn('if (rows[row]?.run === run && rows[row]?.layout === run.layout) return', CHAT)

    def test_slot_nickname_and_stable_body_geometry_are_not_omitted(self):
        self.assertIn('component.nick.slot.AIONickComponentV4', CHAT)
        self.assertIn('state.layout.contentSlots', RENDER)
        self.assertIn('it.isAttachedToWindow && it.visibility != View.GONE', RENDER)
        self.assertNotIn('if (!content.isShown)', RENDER)

    def test_group_edges_and_bindings_follow_data_not_attached_row_count(self):
        self.assertIn('hook(committed)', CHAT)
        self.assertIn('run.group = RunKey(list[start]!!)', CHAT)
        self.assertIn('val group get() = run.group', CHAT)
        self.assertIn('hasStart = hasStart || !state.run.continued', RENDER)
        self.assertIn('hasEnd = hasEnd || !state.run.continues', RENDER)
        fallback = RENDER.split('if (face == null)', 1)[1].split('commitFrame(next, clip)', 1)[0]
        self.assertIn('if (face != null) { avatars.forEach { it.hide() }; next.add(face) }', fallback)
        self.assertIn('else avatars.forEach { it.restore() }', fallback)

    def test_native_interception_does_not_clear_velocity_before_up(self):
        body = RENDER.split('fun touch(', 1)[1].split('override fun onViewDetachedFromWindow', 1)[0]
        self.assertEqual(1, body.count('host.onInterceptTouchEvent(event)'))
        self.assertIn('event.actionMasked == MotionEvent.ACTION_DOWN && host.onInterceptTouchEvent(event)', body)
        self.assertLess(body.index('host.onInterceptTouchEvent(event)'), body.index('host.onTouchEvent(event)'))
        self.assertIn('if (!dragging && move) { cancelPressed(); dragging = true }', body)


if __name__ == '__main__':
    unittest.main()
