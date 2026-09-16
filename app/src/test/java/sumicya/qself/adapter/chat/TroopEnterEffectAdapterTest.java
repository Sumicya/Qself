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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;

/**
 * Batch-1 contract tests. On a plain JVM the version gate cannot be
 * evaluated (hostInfo uninitialized), so end-to-end resolution degrades to
 * null — which is asserted as the fail-safe — while the volatile legacy
 * trait predicate is pinned method by method (second testable seam,
 * RFC-03 §7).
 */
public class TroopEnterEffectAdapterTest {

    @SuppressWarnings("unused")
    static class LegacyController {
        void a() {
        }

        void l() {
        }

        static void aStatic() {
        }

        void a(Object arg) {
        }

        void b() {
        }

        int aInt() {
            return 0;
        }
    }

    private static Method m(String name, Class<?>... params) throws Exception {
        return LegacyController.class.getDeclaredMethod(name, params);
    }

    @Test
    public void legacyTraitPinned() throws Exception {
        TroopEnterEffectAdapter adapter = TroopEnterEffectAdapter.INSTANCE;
        assertTrue("instance a() matches", adapter.matchesLegacyTrait(m("a")));
        assertTrue("instance l() matches", adapter.matchesLegacyTrait(m("l")));
        assertFalse("static is rejected", adapter.matchesLegacyTrait(LegacyController.class
                .getDeclaredMethod("aStatic")));
        assertFalse("arity is rejected", adapter.matchesLegacyTrait(m("a", Object.class)));
        assertFalse("wrong name is rejected", adapter.matchesLegacyTrait(m("b")));
        assertFalse("non-void return is rejected",
                adapter.matchesLegacyTrait(m("aInt")));
    }

    @Test
    public void jvmResolutionDegradesToNull() {
        // version gate unavailable on JVM -> must return null, never throw
        assertNull(TroopEnterEffectAdapter.INSTANCE.resolveEffectEntry(
                TroopEnterEffectAdapterTest.class.getClassLoader()));
    }
}
