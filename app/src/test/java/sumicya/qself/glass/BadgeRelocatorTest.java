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
 * Batch A2 contract tests: the badge relocation delta is a pure function of
 * the icon/badge rects — pin the arithmetic (centre-x alignment, gap above
 * icon top) including the badge-wider-than-icon and already-centred cases.
 */
public class BadgeRelocatorTest {

    private static final float EPS = 0.001f;

    private static float[] deltas(float il, float it, float ir,
            float bl, float bt, float br, float bb, float gap) {
        return BadgeRelocator.badgeDeltas(il, it, ir, bl, bt, br, bb, gap);
    }

    @Test
    public void badgeMovesFromTopRightToTopCentre() {
        // icon 100..180 x 40..110; badge hanging at 160..200 x 20..44; gap 4
        float[] d = deltas(100, 40, 180, 160, 20, 200, 44, 4);
        // centre-x: 140 vs 180 -> dx = -40 (move left)
        assertEquals(-40f, d[0], EPS);
        // icon top (40) - gap (4) - badge bottom (44) -> dy = -8 (move up)
        assertEquals(-8f, d[1], EPS);
    }

    @Test
    public void widerBadgeCentresOnNarrowIcon() {
        // icon 100..140 (centre 120); badge 90..150 (centre 120 already)
        float[] d = deltas(100, 40, 140, 90, 20, 150, 44, 0);
        assertEquals(0f, d[0], EPS);
        // gap 0: badge bottom lands exactly on icon top
        assertEquals(40 - 44 - 0, d[1], EPS);
    }

    @Test
    public void alreadyRelocatedIsZeroDelta() {
        // badge already centred and 4px above the icon top
        float[] d = deltas(100, 44, 180, 120, 12, 160, 40, 4);
        assertEquals(0f, d[0], EPS);
        assertEquals(0f, d[1], EPS);
    }

    @Test
    public void negativeSpaceStillComputes() {
        // pathological: badge below the icon — the formula still returns the
        // (large positive) delta that would drag it up above the icon
        float[] d = deltas(0, 100, 50, 0, 300, 40, 320, 2);
        assertEquals(5f, d[0], EPS);
        assertEquals(100 - 2 - 320, d[1], EPS);
    }
}
