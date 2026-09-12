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

package sumicya.qself.adapter.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Batch-5 contract tests: the at-all classifier method trait and the
 * comma-wrapped mute-list membership rule (substring false-match guard,
 * null-list bug-for-bug) — pure functions, fully JVM-verifiable.
 */
public class TroopMuteAdapterTest {

    private final TroopMuteAdapter adapter = TroopMuteAdapter.INSTANCE;

    @SuppressWarnings("unused")
    static class MessageInfo {
        int classify(Object appInterface, boolean z, String troopUin) {
            return 0;
        }

        Integer wrongReturnBoxed(Object appInterface, boolean z, String troopUin) {
            return 1;
        }

        int wrongArity(Object appInterface, boolean z) {
            return 0;
        }

        int wrongArg0(String s, boolean z, String troopUin) {
            return 0;
        }

        int wrongArg1(Object appInterface, int i, String troopUin) {
            return 0;
        }

        int wrongArg2(Object appInterface, boolean z, Object o) {
            return 0;
        }
    }

    @Test
    public void atAllClassifierTraitPinned() throws Exception {
        Class<?> app = Object.class;
        assertTrue(adapter.matchesAtAllClassifierTrait(
                MessageInfo.class.getDeclaredMethod("classify", Object.class, boolean.class, String.class), app));
        assertFalse(adapter.matchesAtAllClassifierTrait(
                MessageInfo.class.getDeclaredMethod("wrongReturnBoxed", Object.class, boolean.class, String.class), app));
        assertFalse(adapter.matchesAtAllClassifierTrait(
                MessageInfo.class.getDeclaredMethod("wrongArity", Object.class, boolean.class), app));
        assertFalse(adapter.matchesAtAllClassifierTrait(
                MessageInfo.class.getDeclaredMethod("wrongArg0", String.class, boolean.class, String.class), app));
        assertFalse(adapter.matchesAtAllClassifierTrait(
                MessageInfo.class.getDeclaredMethod("wrongArg1", Object.class, int.class, String.class), app));
        assertFalse(adapter.matchesAtAllClassifierTrait(
                MessageInfo.class.getDeclaredMethod("wrongArg2", Object.class, boolean.class, Object.class), app));
    }

    @Test
    public void mutedListMembership() {
        assertTrue(adapter.isTroopInMutedList("123", "123"));
        assertTrue(adapter.isTroopInMutedList("123,456", "123"));
        assertTrue(adapter.isTroopInMutedList("123,456", "456"));
        assertTrue(adapter.isTroopInMutedList(",123,", "123"));
        assertFalse(adapter.isTroopInMutedList("123,456", "45"));
        assertFalse(adapter.isTroopInMutedList("1234", "123"));
        assertFalse(adapter.isTroopInMutedList("1234", "234"));
        assertFalse(adapter.isTroopInMutedList("", "123"));
        assertFalse(adapter.isTroopInMutedList(null, "123"));
        // bug-for-bug: a null list renders as ",null," and a "null" needle matches it
        assertTrue(adapter.isTroopInMutedList(null, "null"));
        // no trimming — bug-for-bug
        assertFalse(adapter.isTroopInMutedList("123, 456", "456"));
    }
}
