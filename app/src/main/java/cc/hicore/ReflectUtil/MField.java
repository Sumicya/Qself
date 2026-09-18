/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2023 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cc.hicore.ReflectUtil;

import java.lang.reflect.Field;
import java.util.HashMap;

public class MField {

    public static <T> T GetStaticField(Class<?> clz, String FieldName) {
        try {
            Class<?> checkClz = clz;
            while (checkClz != null) {
                for (Field f : clz.getDeclaredFields()) {
                    if (f.getName().equals(FieldName)) {
                        f.setAccessible(true);
                        return (T) f.get(null);
                    }
                }
                checkClz = checkClz.getSuperclass();
            }
        } catch (Exception ignored) {
        }
        throw new RuntimeException("Can't find field " + FieldName + " in class " + clz);
    }
}
