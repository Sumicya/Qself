/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.qauxv.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * JVM tests for {@link Initiator} name resolution and lookup semantics.
 *
 * <p>The host classloader is substituted with the test classloader via the
 * package-private {@link Initiator#initForTest(ClassLoader)} hook. Because
 * {@code :libs:stub} is {@code compileOnly}, no {@code com.tencent.*} class
 * exists on the test classpath, which doubles as a negative-path fixture:
 * "host class missing" is exactly the state after a QQ rename.</p>
 */
public class InitiatorTest {

    @Before
    public void setUp() {
        Initiator.initForTest(InitiatorTest.class.getClassLoader());
    }

    @Test
    public void loadAcceptsAllCommonNameForms() {
        assertEquals(String.class, Initiator.load("java.lang.String"));
        assertEquals(String.class, Initiator.load("java/lang/String"));
        assertEquals(String.class, Initiator.load("Ljava/lang/String;"));
    }

    @Test
    public void loadReturnsNullForMissingOrNull() {
        assertNull(Initiator.load("com.tencent.mobileqq.app.DoesNotExist"));
        assertNull(Initiator.load((String) null));
        assertNull(Initiator.load(""));
    }

    @Test
    public void uninitializedStateIsFailSafe() {
        Initiator.initForTest(null);
        assertNull(Initiator.load("java.lang.String"));
        assertFalse(Initiator.checkHostHasClass("java.lang.String"));
    }

    @Test
    public void loadClassThrowsForMissing() {
        try {
            Initiator.loadClass("com.tencent.mobileqq.app.DoesNotExist");
            fail("expected ClassNotFoundException");
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }

    @Test
    public void loadClassEitherFallsBackInOrder() throws ClassNotFoundException {
        assertEquals(String.class,
                Initiator.loadClassEither("com.tencent.no.Such1", "java.lang.String"));
        try {
            Initiator.loadClassEither("com.tencent.no.Such1", "com.tencent.no.Such2");
            fail("expected ClassNotFoundException");
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }

    @Test
    public void checkHostHasClass() {
        assertTrue(Initiator.checkHostHasClass("java.lang.String"));
        assertFalse(Initiator.checkHostHasClass("com.tencent.no.Such"));
    }

    @Test
    public void requireClassSneakyThrowsOnMissing() {
        assertEquals(String.class, Initiator.requireClass("java.lang.String"));
        try {
            Initiator.requireClass("com.tencent.no.Such");
            fail("expected ClassNotFoundException to be sneaky-thrown");
        } catch (Throwable t) {
            // requireClass rethrows via IoUtils.unsafeThrow without declaring
            // the checked exception, hence the widened catch here
            assertTrue("expected ClassNotFoundException, got: " + t,
                    t instanceof ClassNotFoundException);
        }
    }
}
