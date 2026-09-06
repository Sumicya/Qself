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
 * Batch A2 contract tests: group-centring relocation — the badge is proposed
 * at top-centre above the icon, then the UNION of the pair is centred in the
 * tab, with additive deltas whose target is a fixed point.
 */
public class BadgeRelocatorTest {

    private static final float EPS = 0.001f;

    private static float[] reloc(float il, float it, float ir, float ib,
            float bl, float bt, float br, float bb, float w, float h, float gap) {
        return BadgeRelocator.groupRelocation(il, it, ir, ib, bl, bt, br, bb, w, h, gap);
    }

    @Test
    public void badgeProposedAboveIconThenUnionCentred() {
        // icon 100..180 x 40..110; badge 160..200 x 20..44; tab 220x120; gap 4
        float[] r = reloc(100, 40, 180, 110, 160, 20, 200, 44, 220, 120, 4);
        // proposed badge shift: bx=-40, by=-8 -> badge 120..160 x 12..36
        // union 100..180 x 12..110, centre (140,61); tab centre (110,60)
        assertEquals(-30f, r[0], EPS); // icon dx
        assertEquals(-1f, r[1], EPS);  // icon dy
        assertEquals(-70f, r[2], EPS); // badge dx = -40 + -30
        assertEquals(-9f, r[3], EPS);  // badge dy = -8 + -1
    }

    @Test
    public void widerBadgeStillCentresAsAGroup() {
        // icon 100..140 x 40..110; badge 90..150 x 20..44 (wider); tab 240x150; gap 0
        float[] r = reloc(100, 40, 140, 110, 90, 20, 150, 44, 240, 150, 0);
        // proposed badge by = 40-0-44 = -4 -> badge 90..150 x 16..40
        // union 90..150 x 16..110 centre (120,63); tab centre (120,75)
        assertEquals(0f, r[0], EPS);
        assertEquals(12f, r[1], EPS);
        assertEquals(0f, r[2], EPS);
        assertEquals(8f, r[3], EPS);
    }

    @Test
    public void targetIsAFixedPoint() {
        // apply once, then feed the moved rects back: every delta must be zero
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
        // bx=5, by=-222 -> proposed 5..45 x 78..98; union 0..50 x 78..150 centre (25,114)
        // tab centre (30,80) -> g=(5,-34)
        assertEquals(5f, r[0], EPS);
        assertEquals(-34f, r[1], EPS);
        assertEquals(10f, r[2], EPS);
        assertEquals(-256f, r[3], EPS);
    }
}
