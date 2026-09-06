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

package sumicya.qself.glass;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Batch A2 tests, overlay semantics: the badge straddles the icon's top edge
 * (centre-x on the icon, centre-y gapPx above the icon top), then the UNION
 * of the pair is centred in the tab. Additive deltas, fixed-point target.
 */
public class BadgeRelocatorTest {

    private static final float EPS = 0.001f;

    private static float[] reloc(float il, float it, float ir, float ib,
            float bl, float bt, float br, float bb, float w, float h, float gap) {
        return BadgeRelocator.groupRelocation(il, it, ir, ib, bl, bt, br, bb, w, h, gap);
    }

    @Test
    public void badgeOverlaysIconTopThenUnionCentred() {
        // icon 100..180 x 40..110; badge 160..200 x 20..44 (cy 32); tab 220x120; gap 4
        float[] r = reloc(100, 40, 180, 110, 160, 20, 200, 44, 220, 120, 4);
        // bx=-40; by=40-4-32=4 -> badge 120..160 x 24..48 (straddles icon top 40)
        // union 100..180 x 24..110, centre (140,67); tab centre (110,60)
        assertEquals(-30f, r[0], EPS); // icon dx
        assertEquals(-7f, r[1], EPS);  // icon dy
        assertEquals(-70f, r[2], EPS); // badge dx = -40 + -30
        assertEquals(-3f, r[3], EPS);  // badge dy = 4 + -7
    }

    @Test
    public void widerBadgeStillCentresAsAGroup() {
        // icon 100..140 x 40..110; badge 90..150 x 20..44 (wider); tab 240x150; gap 0
        float[] r = reloc(100, 40, 140, 110, 90, 20, 150, 44, 240, 150, 0);
        // bx=0; by=40-0-32=8 -> badge 90..150 x 28..52; union y 28..110 centre (120,69)
        // tab centre (120,75) -> g=(0,6)
        assertEquals(0f, r[0], EPS);
        assertEquals(6f, r[1], EPS);
        assertEquals(0f, r[2], EPS);
        assertEquals(14f, r[3], EPS);
    }

    @Test
    public void targetIsAFixedPoint() {
        float[] first = reloc(100, 40, 180, 110, 160, 20, 200, 44, 220, 120, 4);
        float[] second = reloc(
                100 + first[0], 40 + first[1], 180 + first[0], 110 + first[1],
                160 + first[2], 20 + first[3], 200 + first[2], 44 + first[3],
                220, 120, 4);
        assertEquals(0f, second[0], EPS);
        assertEquals(0f, second[1], EPS);
        assertEquals(0f, second[2], EPS);
        assertEquals(0f, second[3], EPS);
    }

    @Test
    public void pathologicalBadgeBelowIconStillComputes() {
        float[] r = reloc(0, 100, 50, 150, 0, 300, 40, 320, 60, 160, 2);
        // bx=5; by=100-2-310=-212 -> badge 5..45 x 88..108; union 0..50 x 88..150
        // centre (25,119); tab centre (30,80) -> g=(5,-39)
        assertEquals(5f, r[0], EPS);
        assertEquals(-39f, r[1], EPS);
        assertEquals(10f, r[2], EPS);
        assertEquals(-251f, r[3], EPS);
    }
}
