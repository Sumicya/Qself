/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public class FeatureErrorMetadataTest {
    @Test public void onlyTypeAndCodePositionsAreRecorded() {
        Throwable error = new IllegalStateException("private-message-123456789", new Exception("private-cause"));
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("safe.Owner", "init", "/private/path/account.txt", 42)});
        String result = FeatureErrorMetadata.describe(error);
        assertEquals("java.lang.IllegalStateException safe.Owner.init:42", result);
        assertFalse(result.contains("private"));
    }
    @Test public void boundsAndInvalidFrameNamesAreEnforced() {
        StackTraceElement[] stack = new StackTraceElement[200];
        java.util.Arrays.fill(stack, new StackTraceElement("unsafe\nvalue", "bad method", "hidden", 1));
        Throwable error = new Throwable("never include this"); error.setStackTrace(stack);
        String result = FeatureErrorMetadata.describe(error);
        assertFalse(result.contains("\n")); assertFalse(result.contains("hidden"));
        assertEquals(6, result.split("unknown.unknown", -1).length - 1);
        assertTrue(result.length() <= 900);
        assertEquals("none", FeatureErrorMetadata.describe(null));
    }
}
