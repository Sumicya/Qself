/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.ketal.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A rejected stored pattern must degrade to the default instead of throwing
 * per bubble (QQ 9.2.10 device evidence: "Illegal pattern character 'A'").
 */
public class TailTimeFormatTest {

    @Test
    public void detectsIllegalPatternLetters() {
        assertTrue(TailTimeFormat.isBad("A"));
        assertTrue(TailTimeFormat.isBad("yyyy-MM-dd HH:mm:ss A"));
        assertFalse(TailTimeFormat.isBad("yyyy-MM-dd HH:mm:ss"));
        assertFalse(TailTimeFormat.isBad(""));
    }

    @Test
    public void safeFallsBackOnBadPattern() {
        assertEquals("yyyy-MM-dd HH:mm:ss",
                TailTimeFormat.safe("A", "yyyy-MM-dd HH:mm:ss").toPattern());
    }

    @Test
    public void safePassesGoodPatternThrough() {
        assertEquals("HH:mm:ss", TailTimeFormat.safe("HH:mm:ss", "yyyy").toPattern());
    }
}
