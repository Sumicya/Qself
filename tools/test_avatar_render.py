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
        self.assertLess(binding.index('run.layout?.apply('), binding.rindex('register(row, run)'))
        refresh = LOOKS.split('fun refreshLooks()', 1)[1].split('private fun watch(', 1)[0]
        self.assertIn('refreshChatLayout()', refresh)

    def test_native_interception_does_not_clear_velocity_before_up(self):
        body = RENDER.split('fun touch(', 1)[1].split('override fun onViewDetachedFromWindow', 1)[0]
        self.assertEqual(1, body.count('host.onInterceptTouchEvent(event)'))
        self.assertIn('event.actionMasked == MotionEvent.ACTION_DOWN && host.onInterceptTouchEvent(event)', body)
        self.assertLess(body.index('host.onInterceptTouchEvent(event)'), body.index('host.onTouchEvent(event)'))
        self.assertIn('if (!dragging && move) { cancelPressed(); dragging = true }', body)


if __name__ == '__main__':
    unittest.main()
