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

package io.github.qauxv.util.dexkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JVM tests for {@link DexMethodDescriptor}, the descriptor grammar that the
 * DexKit deobfuscation cache (and the bytecode generator for the new
 * libxposed API) is built on. A parser bug here silently corrupts every
 * downstream resolution, so the grammar is pinned down bit by bit.
 */
public class DexMethodDescriptorTest {

    @SuppressWarnings("unused")
    static class Fixture {
        static void staticMethod(int a, long b, String[] c) {
        }

        void instanceMethod() {
        }

        Fixture(int ignored) {
        }
    }

    static class Child extends Fixture {
    }

    @Test
    public void parsesAndRoundTrips() {
        String desc = "Lcom/tencent/mobileqq/app/QQAppFacade;->a(I)Ljava/lang/String;";
        DexMethodDescriptor d = new DexMethodDescriptor(desc);
        assertEquals("Lcom/tencent/mobileqq/app/QQAppFacade;", d.declaringClass);
        assertEquals("a", d.name);
        assertEquals("(I)Ljava/lang/String;", d.signature);
        assertEquals("com.tencent.mobileqq.app.QQAppFacade", d.getDeclaringClassName());
        assertEquals(desc, d.toString());
        assertEquals(desc, d.getDescriptor());
    }

    @Test
    public void rejectsMalformedDescriptors() {
        try {
            new DexMethodDescriptor("no-arrow-here");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new DexMethodDescriptor("Lfoo/Bar;->noParenthesis");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new DexMethodDescriptor((String) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    @Test
    public void typeSignatures() {
        assertEquals("I", DexMethodDescriptor.getTypeSig(int.class));
        assertEquals("V", DexMethodDescriptor.getTypeSig(void.class));
        assertEquals("Z", DexMethodDescriptor.getTypeSig(boolean.class));
        assertEquals("J", DexMethodDescriptor.getTypeSig(long.class));
        assertEquals("D", DexMethodDescriptor.getTypeSig(double.class));
        assertEquals("F", DexMethodDescriptor.getTypeSig(float.class));
        assertEquals("C", DexMethodDescriptor.getTypeSig(char.class));
        assertEquals("B", DexMethodDescriptor.getTypeSig(byte.class));
        assertEquals("S", DexMethodDescriptor.getTypeSig(short.class));
        assertEquals("[I", DexMethodDescriptor.getTypeSig(int[].class));
        assertEquals("[[Ljava/lang/String;", DexMethodDescriptor.getTypeSig(String[][].class));
        assertEquals("Ljava/lang/String;", DexMethodDescriptor.getTypeSig(String.class));
        assertEquals("Lio/github/qauxv/util/dexkit/DexMethodDescriptorTest$Fixture;",
                DexMethodDescriptor.getTypeSig(Fixture.class));
    }

    @Test
    public void reflectedMethodAndConstructorSignatures() throws Exception {
        Method m = Fixture.class.getDeclaredMethod("staticMethod", int.class, long.class, String[].class);
        assertEquals("(IJ[Ljava/lang/String;)V", DexMethodDescriptor.getMethodTypeSig(m));
        assertEquals("staticMethod", new DexMethodDescriptor(m).name);
        assertEquals("(IJ[Ljava/lang/String;)V", new DexMethodDescriptor(m).signature);

        Constructor<Fixture> c = Fixture.class.getDeclaredConstructor(int.class);
        assertEquals("(I)V", DexMethodDescriptor.getConstructorTypeSig(c));
        DexMethodDescriptor dc = new DexMethodDescriptor(c);
        assertEquals("<init>", dc.name);
        assertEquals("(I)V", dc.signature);
    }

    /**
     * Regression test: splitParameterTypes used to swallow the parameter that
     * directly follows an object or array type, because the {@code L}/@code [
     * branches already advanced the cursor and the loop tail incremented it a
     * second time. Downstream, LibXposedNewApiByteCodeGenerator builds
     * ImmutableMethodReference from this list, so a swallowed parameter
     * silently produced a mismatched proxy signature.
     */
    @Test
    public void parameterTypeSplitting() {
        List<String> objectThenPrimitive = new DexMethodDescriptor(
                "Lfoo;->m(Ljava/lang/String;I)V").getParameterTypes();
        assertEquals(Arrays.asList("Ljava/lang/String;", "I"), objectThenPrimitive);

        List<String> primitiveArrayObject = new DexMethodDescriptor(
                "Lfoo;->m(I[Ljava/lang/String;J)V").getParameterTypes();
        assertEquals(Arrays.asList("I", "[Ljava/lang/String;", "J"), primitiveArrayObject);

        List<String> nestedArrays = new DexMethodDescriptor(
                "Lfoo;->m([[Ljava/lang/String;Z)V").getParameterTypes();
        assertEquals(Arrays.asList("[[Ljava/lang/String;", "Z"), nestedArrays);

        List<String> onlyPrimitives = new DexMethodDescriptor(
                "Lfoo;->m(IJZ)V").getParameterTypes();
        assertEquals(Arrays.asList("I", "J", "Z"), onlyPrimitives);

        List<String> empty = new DexMethodDescriptor("Lfoo;->m()V").getParameterTypes();
        assertEquals(Collections.emptyList(), empty);
    }

    @Test
    public void returnType() {
        assertEquals("V", new DexMethodDescriptor("Lfoo;->m()V").getReturnType());
        assertEquals("Ljava/lang/String;", new DexMethodDescriptor(
                "Lfoo;->m(I)Ljava/lang/String;").getReturnType());
    }

    @Test
    public void equalsAndHashCode() {
        DexMethodDescriptor a = new DexMethodDescriptor("Lfoo;->m(I)V");
        DexMethodDescriptor b = new DexMethodDescriptor("Lfoo;", "m", "(I)V");
        DexMethodDescriptor c = new DexMethodDescriptor("Lfoo;->m(J)V");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals(a, new DexMethodDescriptor("Lfoo;->m(I)V"));
    }

    @Test
    public void getMethodInstanceResolvesDeclaredAndInherited() throws Exception {
        ClassLoader cl = DexMethodDescriptorTest.class.getClassLoader();
        // declared static method
        DexMethodDescriptor declared = new DexMethodDescriptor(Fixture.class,
                "staticMethod", "(IJ[Ljava/lang/String;)V");
        assertEquals(Fixture.class.getDeclaredMethod("staticMethod",
                int.class, long.class, String[].class), declared.getMethodInstance(cl));
        // declared instance method
        DexMethodDescriptor instance = new DexMethodDescriptor(Fixture.class,
                "instanceMethod", "()V");
        assertEquals(Fixture.class.getDeclaredMethod("instanceMethod"), instance.getMethodInstance(cl));
        // inherited from superclass, non-private non-static lookup
        DexMethodDescriptor inherited = new DexMethodDescriptor(Child.class,
                "instanceMethod", "()V");
        assertEquals(Fixture.class.getDeclaredMethod("instanceMethod"), inherited.getMethodInstance(cl));
    }

    @Test
    public void getMethodInstanceThrowsForUnknown() {
        ClassLoader cl = DexMethodDescriptorTest.class.getClassLoader();
        try {
            new DexMethodDescriptor(Child.class, "noSuchMethod", "()V").getMethodInstance(cl);
            fail("expected NoSuchMethodException");
        } catch (NoSuchMethodException expected) {
            // expected
        }
        try {
            new DexMethodDescriptor("com/tencent/does/not/Exist;->m()V").getMethodInstance(cl);
            fail("expected NoSuchMethodException");
        } catch (NoSuchMethodException expected) {
            // expected, caused by ClassNotFoundException
        }
    }
}
