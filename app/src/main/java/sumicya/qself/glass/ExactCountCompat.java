/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Only known numeric badge entry points; never infer counts from capped text or unrelated fields. */
public final class ExactCountCompat {
    private ExactCountCompat() { }
    public static List<Method> updateMethods(Class<?> type, String name) {
        List<Method> result = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (method.getName().equals(name) && !Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == void.class && args.length > 0
                        && (args[0] == int.class || args[0] == long.class || args[0] == Integer.class || args[0] == Long.class)) {
                    method.setAccessible(true);
                    result.add(method);
                }
            }
        }
        return result;
    }
    public static Field textField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                if ((field.getType() != String.class && field.getType() != CharSequence.class) || Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name + ": String");
    }
}
