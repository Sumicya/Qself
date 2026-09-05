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

package sumicya.qself.hostapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * Policy tests for {@link CapabilityRegistry}: the degradation state machine
 * that the future adapter layer will report into. Pure JVM, no Android types.
 */
public class CapabilityRegistryTest {

    @Before
    public void setUp() {
        CapabilityRegistry.resetForTest();
    }

    @Test
    public void unreportedCapabilityIsUnknownAndUnusable() {
        assertEquals(CapabilityState.UNKNOWN, CapabilityRegistry.stateOf("chat.send_msg"));
        assertFalse(CapabilityRegistry.isUsable("chat.send_msg"));
        assertNull(CapabilityRegistry.causeOf("chat.send_msg"));
    }

    @Test
    public void availableIsUsableAndClearsCause() {
        RuntimeException first = new RuntimeException("probe failed once");
        CapabilityRegistry.report("chat.send_msg", CapabilityState.DEGRADED, first);
        assertSame(first, CapabilityRegistry.causeOf("chat.send_msg"));

        CapabilityRegistry.report("chat.send_msg", CapabilityState.AVAILABLE);
        assertEquals(CapabilityState.AVAILABLE, CapabilityRegistry.stateOf("chat.send_msg"));
        assertTrue(CapabilityRegistry.isUsable("chat.send_msg"));
        assertNull("recovery must clear the stale cause", CapabilityRegistry.causeOf("chat.send_msg"));
    }

    @Test
    public void degradedIsUsableAndRetainsLatestCause() {
        RuntimeException old = new RuntimeException("old");
        RuntimeException recent = new RuntimeException("recent");
        CapabilityRegistry.report("media.decode", CapabilityState.DEGRADED, old);
        CapabilityRegistry.report("media.decode", CapabilityState.DEGRADED, recent);
        assertEquals(CapabilityState.DEGRADED, CapabilityRegistry.stateOf("media.decode"));
        assertTrue(CapabilityRegistry.isUsable("media.decode"));
        assertSame("a later DEGRADED report replaces the cause", recent,
                CapabilityRegistry.causeOf("media.decode"));
    }

    @Test
    public void absentIsTerminalWithinProcessLifetime() {
        ClassNotFoundException cnfe = new ClassNotFoundException("com.tencent.gone.Facade");
        assertEquals(CapabilityState.ABSENT,
                CapabilityRegistry.report("msg.revoke", CapabilityState.ABSENT, cnfe));
        // a later optimistic report must not resurrect the capability
        assertEquals(CapabilityState.ABSENT,
                CapabilityRegistry.report("msg.revoke", CapabilityState.AVAILABLE));
        assertEquals(CapabilityState.ABSENT,
                CapabilityRegistry.report("msg.revoke", CapabilityState.DEGRADED));
        assertEquals(CapabilityState.ABSENT, CapabilityRegistry.stateOf("msg.revoke"));
        assertFalse(CapabilityRegistry.isUsable("msg.revoke"));
        assertSame("first absence cause wins", cnfe, CapabilityRegistry.causeOf("msg.revoke"));
    }

    @Test
    public void availableAndDegradedMayFlap() {
        CapabilityRegistry.report("chat.input", CapabilityState.AVAILABLE);
        assertEquals(CapabilityState.DEGRADED,
                CapabilityRegistry.report("chat.input", CapabilityState.DEGRADED));
        assertEquals(CapabilityState.AVAILABLE,
                CapabilityRegistry.report("chat.input", CapabilityState.AVAILABLE));
        assertEquals(CapabilityState.AVAILABLE, CapabilityRegistry.stateOf("chat.input"));
    }

    @Test
    public void absentWithoutCauseIsAllowed() {
        assertEquals(CapabilityState.ABSENT,
                CapabilityRegistry.report("guild.troop", CapabilityState.ABSENT));
        assertNull(CapabilityRegistry.causeOf("guild.troop"));
    }

    @Test
    public void blankKeysAreRejected() {
        assertRejected("");
        assertRejected("   ");
        assertRejected("\t");
    }

    private static void assertRejected(String key) {
        try {
            CapabilityRegistry.report(key, CapabilityState.AVAILABLE);
            fail("expected IllegalArgumentException for key <" + key + ">");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void resetForTestDropsAllState() {
        CapabilityRegistry.report("a.b", CapabilityState.ABSENT, new RuntimeException());
        CapabilityRegistry.resetForTest();
        assertEquals(CapabilityState.UNKNOWN, CapabilityRegistry.stateOf("a.b"));
        assertNull(CapabilityRegistry.causeOf("a.b"));
    }
}
