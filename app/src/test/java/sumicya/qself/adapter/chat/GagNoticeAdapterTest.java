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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import org.junit.Test;
import sumicya.qself.hostapi.chat.GagNoticeApi.AllGag;
import sumicya.qself.hostapi.chat.GagNoticeApi.GagEvent;
import sumicya.qself.hostapi.chat.GagNoticeApi.MemberGag;

/**
 * Batch-4 contract tests: the modern vMsg byte grammar (gate, big-endian
 * offsets, signed fixup), the normalization boundary, and the legacy
 * 5-param trait — all pure functions, fully JVM-verifiable.
 */
public class GagNoticeAdapterTest {

    private final GagNoticeAdapter adapter = GagNoticeAdapter.INSTANCE;

    private static byte[] gagPayload(long troop, long op, long victim, long seconds) {
        byte[] b = new byte[24];
        b[4] = 12; // gag notice gate
        putLong(b, 0, troop);
        putLong(b, 6, op);
        putLong(b, 16, victim);
        putLong(b, 20, seconds);
        return b;
    }

    private static void putLong(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    @Test
    public void parsesAllGagToggle() {
        GagEvent on = adapter.parseModernGagEvent(
                gagPayload(123456L, 10001L, 0L, 3600L));
        assertTrue(on instanceof AllGag);
        AllGag all = (AllGag) on;
        assertEquals("123456", all.getTroopUin());
        assertEquals("10001", all.getOpUin());
        assertTrue(all.getEnabled());

        GagEvent off = adapter.parseModernGagEvent(
                gagPayload(123456L, 10001L, 0L, 0L));
        assertTrue(off instanceof AllGag);
        assertFalse(((AllGag) off).getEnabled());
    }

    @Test
    public void parsesMemberMuteAndUnmute() {
        GagEvent mute = adapter.parseModernGagEvent(
                gagPayload(123456L, 10001L, 22222L, 1800L));
        assertTrue(mute instanceof MemberGag);
        MemberGag member = (MemberGag) mute;
        assertEquals("22222", member.getVictimUin());
        assertEquals(1800L, member.getSeconds());

        GagEvent unmute = adapter.parseModernGagEvent(
                gagPayload(123456L, 10001L, 22222L, 0L));
        assertTrue(unmute instanceof MemberGag);
        assertEquals(0L, ((MemberGag) unmute).getSeconds());
    }

    @Test
    public void signedUinIsFixedUpToUnsigned() {
        // 0xFFFFFFFF as int32 is negative; the adapter must map it to 4294967295
        GagEvent e = adapter.parseModernGagEvent(
                gagPayload(1L, 0xFFFFFFFFL, 0xFFFFFFFFL, 60L));
        assertTrue(e instanceof AllGag || e instanceof MemberGag);
        assertEquals("4294967295", ((AllGag) e).getOpUin());
    }

    @Test
    public void nonGagPayloadAndShortArrayYieldNull() {
        byte[] notGag = gagPayload(1L, 2L, 3L, 4L);
        notGag[4] = 11;
        assertNull(adapter.parseModernGagEvent(notGag));
        assertNull(adapter.parseModernGagEvent(new byte[10]));
    }

    @Test
    public void normalizeBoundary() {
        assertTrue(adapter.normalize("1", "2", "0", 0L) instanceof AllGag);
        assertFalse(((AllGag) adapter.normalize("1", "2", "0", 0L)).isEnabled());
        assertTrue(adapter.normalize("1", "2", "3", 0L) instanceof MemberGag);
    }

    @SuppressWarnings("unused")
    static class TroopGagMgr {
        void onGag(int code, long troop, long op, long victim, ArrayList<Object> push) {
        }

        void wrongArity(int code, long troop, long op, long victim, ArrayList<Object> push, int x) {
        }

        void wrongType(int code, long troop, long op, int victim, ArrayList<Object> push) {
        }

        int nonVoid(int code, long troop, long op, long victim, ArrayList<Object> push) {
            return 0;
        }
    }

    @Test
    public void legacyTraitPinned() throws Exception {
        assertTrue(adapter.matchesLegacyTrait(TroopGagMgr.class.getDeclaredMethod(
                "onGag", int.class, long.class, long.class, long.class, ArrayList.class)));
        assertFalse(adapter.matchesLegacyTrait(TroopGagMgr.class.getDeclaredMethod(
                "wrongArity", int.class, long.class, long.class, long.class, ArrayList.class, int.class)));
        assertFalse(adapter.matchesLegacyTrait(TroopGagMgr.class.getDeclaredMethod(
                "wrongType", int.class, long.class, long.class, int.class, ArrayList.class)));
        assertFalse(adapter.matchesLegacyTrait(TroopGagMgr.class.getDeclaredMethod(
                "nonVoid", int.class, long.class, long.class, long.class, ArrayList.class)));
    }
}
