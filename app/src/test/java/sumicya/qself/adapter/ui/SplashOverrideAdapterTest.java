/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package sumicya.qself.adapter.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;
import sumicya.qself.adapter.ui.SplashOverrideAdapter.AssetKind;

/**
 * Batch-7 contract tests: host asset classification, the ThemeSplashHelper
 * synthetic-accessor trait (source fixtures cannot carry the synthetic flag,
 * so the synthetic requirement is pinned negatively), and the structure of
 * the transparent blank-out PNG.
 */
public class SplashOverrideAdapterTest {

    private final SplashOverrideAdapter adapter = SplashOverrideAdapter.INSTANCE;

    @Test
    public void splashAssetsClassified() {
        String[] splashes = {
            "splash.jpg", "splash.png", "splash_big.jpg",
            "splash/splash_simple.png", "splash/splash_big_simple.png", "splash/splash_main.png",
        };
        for (String name : splashes) {
            assertEquals(name, AssetKind.SPLASH, adapter.classifyAsset(name));
        }
    }

    @Test
    public void logoAssetsClassified() {
        String[] logos = {
            "splash_logo.png", "splash/splash_logo.png", "splash/splash_logo_night.png",
        };
        for (String name : logos) {
            assertEquals(name, AssetKind.LOGO, adapter.classifyAsset(name));
        }
    }

    @Test
    public void unrelatedAssetsIgnored() {
        assertNull(adapter.classifyAsset("background.png"));
        assertNull(adapter.classifyAsset("splash/splash_other.png"));
        assertNull(adapter.classifyAsset("splash_logo2.png"));
        assertNull(adapter.classifyAsset(""));
    }

    @SuppressWarnings("unused")
    static class ThemeSplashHelper {

        static Map<Integer, Object> config(int cid) {
            return null;
        }

        static Map<Integer, Object> wrongArity(int a, int b) {
            return null;
        }

        static String wrongReturn(int cid) {
            return null;
        }

        Map<Integer, Object> wrongNotStatic(int cid) {
            return null;
        }
    }

    @Test
    public void mapAccessorShapePinned() throws Exception {
        assertTrue(adapter.hasMapAccessorShape(
                ThemeSplashHelper.class.getDeclaredMethod("config", int.class)));
        assertFalse(adapter.hasMapAccessorShape(
                ThemeSplashHelper.class.getDeclaredMethod("wrongArity", int.class, int.class)));
        assertFalse(adapter.hasMapAccessorShape(
                ThemeSplashHelper.class.getDeclaredMethod("wrongReturn", int.class)));
        // shape is return+params ONLY: an instance method still has the shape —
        // staticness lives exclusively in the exact-modifiers half below
        assertTrue(adapter.hasMapAccessorShape(
                ThemeSplashHelper.class.getDeclaredMethod("wrongNotStatic", int.class)));
    }

    @Test
    public void syntheticFlagIsRequired() throws Exception {
        // a plain (non-synthetic) static with perfect shape must NOT match —
        // source fixtures cannot declare synthetic methods, so the flag
        // requirement is pinned negatively here
        assertFalse(adapter.matchesSyntheticMapAccessor(
                ThemeSplashHelper.class.getDeclaredMethod("config", int.class)));
        // and neither does an instance method (modifiers != STATIC|SYNTHETIC)
        assertFalse(adapter.matchesSyntheticMapAccessor(
                ThemeSplashHelper.class.getDeclaredMethod("wrongNotStatic", int.class)));
    }

    @Test
    public void findAccessorReturnsNullWithoutSyntheticMembers() {
        assertNull(adapter.findSyntheticMapAccessor(ThemeSplashHelper.class));
    }

    @Test
    public void transparentPngStructurePinned() {
        byte[] png = SplashOverrideAdapter.TRANSPARENT_PNG;
        // PNG magic
        assertEquals(0x89, png[0] & 0xFF);
        assertEquals(0x50, png[1] & 0xFF);
        assertEquals(0x4E, png[2] & 0xFF);
        assertEquals(0x47, png[3] & 0xFF);
        // IHDR chunk type at offset 12
        assertEquals('I', png[12]);
        assertEquals('H', png[13]);
        assertEquals('D', png[14]);
        assertEquals('R', png[15]);
        // 1x1 pixel
        assertEquals(1, ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF));
        assertEquals(1, ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF));
        // bit depth 8, color type 6 (RGBA)
        assertEquals(8, png[24] & 0xFF);
        assertEquals(6, png[25] & 0xFF);
        // IEND trailer at the very end
        assertEquals('I', png[png.length - 8]);
        assertEquals('E', png[png.length - 7]);
        assertEquals('N', png[png.length - 6]);
        assertEquals('D', png[png.length - 5]);
    }
}
