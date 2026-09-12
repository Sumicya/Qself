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

package io.github.qauxv.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;

/**
 * Invariants of the {@link SyncUtils} process bitmap.
 *
 * <p>Process targeting is a bitwise protocol shared between this module and
 * every hook that declares {@code targetProcesses}. The protocol is only
 * sound while the constants stay pairwise distinct single bits, so that
 * {@code (getProcessType() &amp; target) != 0} never aliases two processes.</p>
 *
 * <p>Only the constants are asserted here; {@link SyncUtils#getProcessType()}
 * itself reaches into {@code ActivityManager} and is not testable on a plain
 * JVM (it belongs to the host-runtime adapter, see
 * docs/refactoring/01-architecture-analysis.md).</p>
 */
public class SyncUtilsProcessMapTest {

    private static long bitSetOfProcConstants() throws IllegalAccessException {
        long bits = 0;
        for (Field f : SyncUtils.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && f.getType() == int.class
                    && f.getName().startsWith("PROC_")
                    && !f.getName().equals("PROC_ERROR")
                    && !f.getName().equals("PROC_ANY")) {
                f.setAccessible(true);
                bits |= f.getInt(null);
            }
        }
        return bits;
    }

    @Test
    public void singleProcessFlagsAreDistinctSingleBits() throws IllegalAccessException {
        HashSet<Integer> seen = new HashSet<>();
        for (Field f : SyncUtils.class.getDeclaredFields()) {
            String name = f.getName();
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class
                    || !name.startsWith("PROC_")
                    || name.equals("PROC_ERROR") || name.equals("PROC_ANY")) {
                continue;
            }
            f.setAccessible(true);
            int value = f.getInt(null);
            assertTrue(name + " must be a single bit, got " + Integer.toBinaryString(value),
                    Integer.bitCount(value) == 1);
            assertTrue(name + " duplicates another process bit", seen.add(value));
        }
    }

    @Test
    public void wellKnownValues() {
        assertEquals(0, SyncUtils.PROC_ERROR);
        assertEquals(1, SyncUtils.PROC_MAIN);
        assertEquals(0xFFFFFFFF, SyncUtils.PROC_ANY);
        assertEquals(1 << 31, SyncUtils.PROC_OTHERS);
    }

    @Test
    public void declaredProcessesCoverKnownBits() throws IllegalAccessException {
        // every process flag except PROC_OTHERS lives in the low 12 bits
        assertEquals(0xFFF, bitSetOfProcConstants() & 0xFFF);
        // and with PROC_OTHERS the full set is exactly the complement of PROC_MAIN..bits
        assertEquals(0x80000FFF, bitSetOfProcConstants());
    }
}
