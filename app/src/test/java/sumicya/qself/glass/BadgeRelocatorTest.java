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
 * Badge relocation, final form: the number rides the icon's top edge —
 * badge centre on the icon's centre-x and top line. The icon never moves.
 */
public class BadgeRelocatorTest {

    private static final float EPS = 0.001f;

    @Test
    public void badgeRidesIconTopEdge() {
        // icon 100..180 top 40; badge 160..200 x 20..44 (centre 180,32)
        float[] d = BadgeRelocator.overlayDeltas(100, 40, 180, 160, 20, 200, 44, 0);
        assertEquals(-40f, d[0], EPS); // centre-x 180 -> 140
        assertEquals(8f, d[1], EPS);   // centre-y 32 -> 40 (straddles the edge)
    }

    @Test
    public void widerBadgeCentresOnIcon() {
        // icon 100..140 top 40 (centre 120); badge 90..150 x 20..44 (centre 120)
        float[] d = BadgeRelocator.overlayDeltas(100, 40, 140, 90, 20, 150, 44, 0);
        assertEquals(0f, d[0], EPS);
        assertEquals(8f, d[1], EPS);
    }

    @Test
    public void gapLiftsTheCentreLine() {
        float[] d = BadgeRelocator.overlayDeltas(100, 40, 180, 160, 20, 200, 44, 3);
        assertEquals(-40f, d[0], EPS);
        assertEquals(5f, d[1], EPS); // 40 - 3 - 32
    }

    @Test
    public void targetIsAFixedPoint() {
        float[] first = BadgeRelocator.overlayDeltas(100, 40, 180, 160, 20, 200, 44, 0);
        float[] second = BadgeRelocator.overlayDeltas(100, 40, 180,
                160 + first[0], 20 + first[1], 200 + first[0], 44 + first[1], 0);
        assertEquals(0f, second[0], EPS);
        assertEquals(0f, second[1], EPS);
    }
}
