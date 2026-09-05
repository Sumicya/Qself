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

import java.util.List;
import org.junit.Test;

/**
 * Batch-1 contract tests for the light-interaction adapter: the NT trait
 * predicate (exactly one parameter, List return) pinned against decoys,
 * plus the JVM fail-safe (version gate unavailable -> null).
 */
public class LightInteractionAdapterTest {

    @SuppressWarnings("unused")
    static class NtConfigSource {
        List<String> provider(String key) {
            return null;
        }

        List<String> twoArgs(String key, int type) {
            return null;
        }

        String notAList(String key) {
            return null;
        }
    }

    @Test
    public void ntTraitPinned() throws Exception {
        LightInteractionAdapter adapter = LightInteractionAdapter.INSTANCE;
        assertTrue(adapter.matchesNtTrait(NtConfigSource.class
                .getDeclaredMethod("provider", String.class)));
        assertFalse("two parameters rejected", adapter.matchesNtTrait(NtConfigSource.class
                .getDeclaredMethod("twoArgs", String.class, int.class)));
        assertFalse("non-List return rejected", adapter.matchesNtTrait(NtConfigSource.class
                .getDeclaredMethod("notAList", String.class)));
    }

    @Test
    public void jvmResolutionDegradesToNull() {
        assertNull(LightInteractionAdapter.INSTANCE.resolveConfigSource(
                LightInteractionAdapterTest.class.getClassLoader()));
    }
}
