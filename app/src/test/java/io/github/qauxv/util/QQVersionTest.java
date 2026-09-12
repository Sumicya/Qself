/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.qauxv.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Invariants of the {@link QQVersion} constant table.
 *
 * <p>These constants drive version gating ({@code requireMinQQVersion} and
 * friends) all over the codebase, so the table must satisfy:</p>
 *
 * <ul>
 *   <li>uniqueness: two distinct QQ releases must never share a version code,
 *       otherwise gating silently degrades to the wrong branch;</li>
 *   <li>monotonicity: constants must be declared in strictly increasing order,
 *       otherwise a "min version" comparison against a later-declared but
 *       smaller constant produces wrong results and reviewers cannot append
 *       new versions mechanically.</li>
 * </ul>
 *
 * <p>Note on field order: {@link Class#getDeclaredFields()} is not guaranteed
 * by the JLS to return fields in declaration order, but both HotSpot (CI) and
 * ART (device) preserve source order in practice. If this assumption ever
 * breaks, the monotonicity test fails spuriously and should be replaced by an
 * explicitly ordered list.</p>
 */
public class QQVersionTest {

    private static List<Field> versionConstantFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : QQVersion.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && f.getType() == long.class
                    && f.getName().startsWith("QQ_")) {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        return fields;
    }

    @Test
    public void versionCodesAreUnique() throws IllegalAccessException {
        HashSet<Long> seen = new HashSet<>();
        for (Field f : versionConstantFields()) {
            long value = f.getLong(null);
            assertTrue("duplicate version code " + value + " at " + f.getName(),
                    seen.add(value));
        }
    }

    @Test
    public void versionCodesAreStrictlyIncreasingInDeclarationOrder() throws IllegalAccessException {
        List<Field> fields = versionConstantFields();
        assertTrue("expected a non-empty constant table", !fields.isEmpty());
        String prevName = null;
        long prev = Long.MIN_VALUE;
        for (Field f : fields) {
            long value = f.getLong(null);
            assertTrue(f.getName() + " (" + value + ") must be greater than "
                    + (prevName == null ? "<table start>" : prevName + " (" + prev + ")"),
                    value > prev);
            prev = value;
            prevName = f.getName();
        }
    }

    @Test
    public void knownAnchors() {
        // first supported version
        assertEquals(1296, QQVersion.QQ_8_2_0);
        // an arbitrary mid-history version
        assertEquals(2538, QQVersion.QQ_8_8_68);
        // beta suffix variants occupy their own numeric slot, e.g. 8.9.28.2 > 8.9.28
        assertTrue(QQVersion.QQ_8_9_28_2 > QQVersion.QQ_8_9_28);
        // the newest constant at the time this test was written
        assertEquals(15900, QQVersion.QQ_9_3_55);
    }
}
