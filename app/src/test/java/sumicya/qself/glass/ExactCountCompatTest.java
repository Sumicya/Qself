/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExactCountCompatTest {
    static class Parent {
        private String mText = "99+";
        public void updateNum(int count) { }
        public void updateNum(String text) { }
    }
    static class CurrentBadge extends Parent {
        public void updateNum(int count, boolean animate) { }
        public void unrelated(int count) { }
        public static void updateNum(int count, String source) { }
    }
    @Test public void knownNumericOverloadsIncludeInheritedAndExcludeUnrelatedMethods() {
        assertEquals(2, ExactCountCompat.updateMethods(CurrentBadge.class, "updateNum").size());
        assertTrue(ExactCountCompat.updateMethods(CurrentBadge.class, "missing").isEmpty());
    }
    @Test public void inheritedPrivateTextCanBeReplacedWithUncappedCount() throws Exception {
        CurrentBadge badge = new CurrentBadge();
        java.lang.reflect.Field text = ExactCountCompat.textField(CurrentBadge.class, "mText");
        assertEquals("99+", text.get(badge));
        text.set(badge, "12345");
        assertEquals("12345", text.get(badge));
    }
    @Test(expected = NoSuchFieldException.class) public void missingKnownFieldFailsExplicitly() throws Exception {
        ExactCountCompat.textField(CurrentBadge.class, "invented");
    }
}
