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
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Plain-number overlay: label derivation from the captured count (primary)
 * with a TextView-text fallback.
 */
public class BadgeNumbersTest {

    @Test
    public void countWinsOverText() {
        assertEquals("3", BadgeNumbers.countLabel(3, "stale text"));
        assertNull(BadgeNumbers.countLabel(0, "5"));
    }

    @Test
    public void bigCountsCapAt99Plus() {
        assertEquals("99+", BadgeNumbers.countLabel(100, null));
        assertEquals("99", BadgeNumbers.countLabel(99, null));
    }

    @Test
    public void textFallbackWhenNoCount() {
        assertEquals("12", BadgeNumbers.countLabel(null, " 12 "));
        assertNull(BadgeNumbers.countLabel(null, null));
        assertNull(BadgeNumbers.countLabel(null, "   "));
    }
}
