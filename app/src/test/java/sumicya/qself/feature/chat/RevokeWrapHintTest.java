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
package sumicya.qself.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * The wrap-hint gating: the registry key must be the exact (peerUid, msgSeq)
 * pair the recall path records, and only marked pairs light the strip.
 */
public class RevokeWrapHintTest {

    @Test
    public void keyJoinsPeerUidAndSeq() {
        assertEquals("uid-1#42", RevokeWrapHint.markKey("uid-1", 42L));
        assertEquals("null#0", RevokeWrapHint.markKey(null, 0L));
    }

    @Test
    public void onlyMarkedPairsGate() {
        Set<String> marked = new HashSet<>();
        marked.add(RevokeWrapHint.markKey("peer", 7L));
        assertTrue(RevokeWrapHint.shouldMark(marked, "peer", 7L));
        assertFalse(RevokeWrapHint.shouldMark(marked, "peer", 8L));
        assertFalse(RevokeWrapHint.shouldMark(marked, "other", 7L));
        assertFalse(RevokeWrapHint.shouldMark(marked, null, 7L));
    }
}
