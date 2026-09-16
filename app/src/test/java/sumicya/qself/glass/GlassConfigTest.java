/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;
import org.junit.Test;
import static org.junit.Assert.*;
public class GlassConfigTest {
    @Test public void transparencyControlsOnlyMaterialAlpha() {
        try {
            GlassConfig.transparency = 0; assertEquals(255, GlassConfig.materialAlpha());
            GlassConfig.transparency = 60; assertEquals(102, GlassConfig.materialAlpha());
            GlassConfig.transparency = 100; assertEquals(0, GlassConfig.materialAlpha());
        } finally { GlassConfig.transparency = 0; }
    }
    @Test public void explicitToneOverridesDetectionAndAutoFollowsIt() {
        try {
            GlassConfig.tone = 0; assertTrue(GlassConfig.resolveNight(true)); assertFalse(GlassConfig.resolveNight(false));
            GlassConfig.tone = 1; assertFalse(GlassConfig.resolveNight(true));
            GlassConfig.tone = 2; assertTrue(GlassConfig.resolveNight(false));
        } finally { GlassConfig.tone = 0; }
    }
    @Test public void backgroundColorRespectsToneAndTintChoice() {
        try {
            GlassConfig.background = 1; assertNotEquals(GlassConfig.backgroundColor(true), GlassConfig.backgroundColor(false));
            int flat = GlassConfig.backgroundColor(false);
            GlassConfig.background = 2; assertNotEquals(flat, GlassConfig.backgroundColor(false));
        } finally { GlassConfig.background = 0; }
    }
}
