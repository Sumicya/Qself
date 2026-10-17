"""固定来源/生命周期契约，不将源码检查当作 Android GPU 或手势验收。"""
import hashlib
import json
from pathlib import Path
import unittest
from check_kernel_shaders import shaders

ROOT = Path(__file__).resolve().parents[1]
KERNEL = (ROOT / 'app/src/main/kotlin/sumicya/qself/KernelBar.kt').read_text()
LOOKS = (ROOT / 'app/src/main/kotlin/sumicya/qself/Looks.kt').read_text()


class KernelPortTest(unittest.TestCase):
    def test_upstream_shader_snapshots_are_not_replaced_by_approximations(self):
        manifest = json.loads((ROOT / 'tools/ksu-shaders.json').read_text())
        self.assertEqual(manifest['expanded_shader_sha256'], {
            name: hashlib.sha256(source.encode()).hexdigest() for name, source in shaders().items()})
        self.assertEqual('3186a2fe2107f7170e06dab7aa157eb10ae7759f', manifest['kernelsu_ref'])

    def test_no_compose_or_miuix_binary_dependency(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertNotIn('androidx.compose', gradle)
        self.assertNotIn('top.yukonga', gradle)
        for package in ('import androidx.compose', 'import kotlinx.coroutines', 'import top.yukonga'):
            self.assertNotIn(package, KERNEL)
        self.assertTrue((ROOT / 'third_party/LICENSE-Apache-2.0.txt').exists())
        self.assertEqual((ROOT / 'LICENSE').read_bytes(), (ROOT / 'third_party/LICENSE-GPL-3.0.txt').read_bytes())
        self.assertIn('assets.srcDir(rootProject.file("third_party"))', gradle)

    def test_blur_lens_dispersion_and_bloom_are_connected(self):
        for operation in ('RenderEffect.createBlurEffect(4*dp,4*dp', '24*dp,24*dp,false',
                          '10*dp*p,14*dp*p,true', '"chromaticAberration",.5f',
                          '"in_saturation", 1.5f', 'shine(canvas,surface,.75f,-45f)',
                          'shine(canvas,rect,p,90f)', '8*dp*p'):
            self.assertIn(operation, KERNEL)
        self.assertIn('Glass(bar, kernelBar = true)', LOOKS)

    def test_frame_and_sensor_lifecycles_are_balanced(self):
        for operation in ('frameClock.removeFrameCallback(frame)', 'sensors.unregisterListener(this)',
                          'if (moving) schedule() else lastFrame = 0L',
                          'if (!attached || posted) return', 'tracker?.recycle()'):
            self.assertIn(operation, KERNEL)
        self.assertNotIn('requestLayout()', KERNEL)
        self.assertNotIn('ValueAnimator.ofFloat', KERNEL)

    def test_drag_activates_real_tab_only_at_release_and_keeps_vertical_handoff(self):
        drag = KERNEL.split('fun drag(x:', 1)[1].split('fun end(', 1)[0]
        self.assertNotIn('performClick', drag)
        self.assertIn('if (commit) chosen?.performClick()', KERNEL)
        end = KERNEL.split('fun end(commit:',1)[1].split('fun attach()',1)[0]
        self.assertLess(end.index('touching = false'),end.index('performClick()'))
        self.assertIn('endKernelDrag(event.actionMasked == MotionEvent.ACTION_UP)', LOOKS)
        self.assertIn('verticalBarDrag(event.x - first.x', LOOKS)
        self.assertIn('send(source, dest, event)', LOOKS)
        self.assertIn('state.parentClip', LOOKS)

    def test_down_starts_press_without_pager_or_native_pressed_polling(self):
        self.assertIn('val touch = BarTouch(bar, nativeBarContent?.invoke(bar))', LOOKS)
        body = LOOKS.split('private class BarTouch(', 1)[1].split('private val barTouchHosts', 1)[0]
        self.assertLess(body.index('kernelTouchDown(event.x)'), body.index('kotlin.math.abs(event.x - first.x) > slop'))
        self.assertIn('kernelTouchEnd()', body)
        self.assertNotIn('isPressed', KERNEL)
        self.assertIn('if (!dragging && !touching && observed', KERNEL)
        self.assertIn('badgeGesture = nativeBadge', body)
        self.assertIn('touching = false', KERNEL.split('fun stop()', 1)[1])

    def test_pressed_pill_and_tab_content_are_not_clipped_to_the_bar_height(self):
        self.assertIn('inset(-pad.toFloat(), -pad.toFloat())', KERNEL)
        self.assertIn('it.clipChildren = false; it.clipToPadding = false', LOOKS)
        self.assertIn('it.clipChildren = old.first; it.clipToPadding = old.second', LOOKS)
        self.assertIn('val factor = 1f + .2f * p', KERNEL)
        self.assertNotIn('paint.shader = RadialGradient', KERNEL)
        self.assertIn('RuntimeShader(INTERACTIVE_HIGHLIGHT_SHADER)', KERNEL)

    def test_knob_layout_reserves_pressed_visual_envelope_without_restylizing_it(self):
        knob = (ROOT / 'app/src/main/kotlin/sumicya/qself/Knob.kt').read_text()
        self.assertIn('room >= gap + clearance + width + gap', knob)
        self.assertIn('kernelOverhang((bar.width-8*dp)/count.coerceAtLeast(1), dp)', knob)
        self.assertIn('76 * dp', knob)
        self.assertIn('52 * dp', knob)
        self.assertIn('InsetDrawable(glass,inset)', knob)
        self.assertIn('kernelButton = true', knob)
        self.assertIn('pressMotion(glass)', knob)
        self.assertNotIn('roundBar = true', knob)

    def test_content_is_composited_after_native_decoration_and_sampled_by_lens(self):
        self.assertIn('getDeclaredMethod("dispatchDraw", Canvas::class.java)', LOOKS)
        self.assertIn('drawKernelContent(chain.getArg(0) as Canvas)', LOOKS)
        self.assertIn('dispatchTabs.invoke(tab,tc)', KERNEL)
        self.assertIn('cc.drawRenderNode(tabContent)', KERNEL)
        self.assertIn('ic.drawRenderNode(combined)', KERNEL)
        self.assertIn('PorterDuff.Mode.SRC_IN', KERNEL)
        for forbidden in ('host.scaleX =', 'host.scaleY =', 'tab.scaleX =', 'tab.scaleY =', 'it.translationX ='):
            self.assertNotIn(forbidden, KERNEL)
        self.assertIn('drawAt(canvas, indicator, rect.left-pad, rect.top-pad)', KERNEL)
        self.assertIn('sh.drawRect(0f,0f,tw+2*pad,56*dp+2*pad,paint)', KERNEL)

    def test_badge_alignment_does_not_mix_window_and_local_coordinates(self):
        body = LOOKS.split('hook(badge.getDeclaredMethod("onLayout"',1)[1].split('// 底栏纯数字',1)[0]
        self.assertNotIn('getLocationInWindow',body)
        self.assertNotIn('view.translationX = 0f',body)
        self.assertIn('parent.transformMatrixToLocal(transform)',body)
        self.assertIn('textAlign = Paint.Align.CENTER',LOOKS)
        self.assertLess(body.index('view.translationX = centeredBadgeTranslation'),body.index('chain.proceed()'))

    def test_backdrop_uses_a_single_full_coordinate_transform(self):
        glass = (ROOT / 'app/src/main/kotlin/sumicya/qself/Glass.kt').read_text()
        body = glass.split('private inline fun paint(',1)[1].split('private fun isBehind',1)[0]
        self.assertIn('v.transformMatrixToGlobal(transform)',body)
        self.assertIn('host.transformMatrixToLocal(transform)',body)
        self.assertNotIn('rc.concat(v.matrix)',body)
        self.assertNotIn('there[0] - here[0]',body)
        self.assertIn('if (!background) rc.translate',body)
