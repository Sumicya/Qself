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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * Plain-number overlay: label derivation (a hooked count is shown exact -
 * no 99+ cap, the user runs QQ's exact-count display; text fallback stays
 * verbatim) and the QQ 9.2.10-driven updateNum matcher (name-based, numeric
 * first argument, narrowest signature wins, null when absent).
 */
public class BadgeNumbersTest {

    // ---- fixtures: the shapes QUIBadge had across versions ----

    static class ClassicBadge {
        public void updateNum(int count) {
        }
    }

    static class OverloadedBadge {
        public void updateNum(int count, String source) {
        }

        public void updateNum(int count) {
        }
    }

    static class WidenedBadge {
        public void updateNum(long count) {
        }
    }

    static class TextFirstBadge {
        public void updateNum(String label) {
        }
    }

    static class RenamedBadge {
        public void setNum(int count) {
        }
    }

    @Test
    public void countWinsOverText() {
        assertEquals("3", BadgeNumbers.countLabel(3, "stale text"));
        assertNull(BadgeNumbers.countLabel(0, "5"));
    }

    @Test
    public void hookedCountsAreExactNoCap() {
        assertEquals("237", BadgeNumbers.countLabel(237, null));
        assertEquals("100", BadgeNumbers.countLabel(100, "99+"));
    }

    @Test
    public void textFallbackWhenNoCount() {
        assertEquals("12", BadgeNumbers.countLabel(null, " 12 "));
        assertNull(BadgeNumbers.countLabel(null, null));
        assertNull(BadgeNumbers.countLabel(null, "   "));
    }

    @Test
    public void picksExactOneArgUpdateNum() throws Exception {
        Method picked = BadgeNumbers.pickUpdateNum(OverloadedBadge.class);
        assertNotNull(picked);
        assertEquals(1, picked.getParameterTypes().length);
    }

    @Test
    public void picksClassicBoxedAndWideForms() {
        assertEquals(1, BadgeNumbers.pickUpdateNum(ClassicBadge.class).getParameterTypes().length);
        assertEquals(1, BadgeNumbers.pickUpdateNum(WidenedBadge.class).getParameterTypes().length);
    }

    @Test
    public void rejectsNonNumericFirstArgAndAbsentMethod() {
        assertNull(BadgeNumbers.pickUpdateNum(TextFirstBadge.class));
        assertNull(BadgeNumbers.pickUpdateNum(RenamedBadge.class));
    }
}
