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
                          'if (!attached || posted) return', 'originals.clear()'):
            self.assertIn(operation, KERNEL)
        self.assertNotIn('requestLayout()', KERNEL)
        self.assertNotIn('ValueAnimator.ofFloat', KERNEL)

    def test_drag_activates_real_tab_only_at_release_and_keeps_vertical_handoff(self):
        drag = KERNEL.split('fun drag(x:', 1)[1].split('fun end(', 1)[0]
        self.assertNotIn('performClick', drag)
        self.assertIn('if (commit) chosen?.performClick()', KERNEL)
        self.assertIn('endKernelDrag(event.actionMasked == MotionEvent.ACTION_UP)', LOOKS)
        self.assertIn('verticalBarDrag(event.x - first.x', LOOKS)
        self.assertIn('send(source, dest, event)', LOOKS)
        self.assertIn('state.parentClip', LOOKS)
