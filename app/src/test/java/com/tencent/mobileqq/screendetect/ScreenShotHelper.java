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

package com.tencent.mobileqq.screendetect;

import android.content.Context;
import android.os.Handler;

/**
 * Test-only host fixture bearing the real host FQN (see RFC-03 §2.6): the
 * adapter's degraded strategy loads exactly this name. The target method is
 * surrounded by decoys that each violate exactly one trait, pinning down the
 * matcher. android.jar stubs (returnDefaultValues) provide real signatures,
 * so reflection-based trait matching works on the JVM.
 */
@SuppressWarnings("unused")
public class ScreenShotHelper {

    // ---- decoys: each violates exactly one trait ----

    /** not static (parameters shuffled so it stays a distinct signature) */
    public void a(Handler handler, String path, Context context) {
    }

    /** wrong arity */
    public static void a(Context context, String path) {
    }

    /** wrong name */
    public static void b(Context context, String path, Handler handler) {
    }

    /** wrong return type */
    public static int a(Context context, String path, Handler handler) {
        return 0;
    }

    /** wrong parameter types */
    public static void a(String path, Context context, Handler handler) {
    }

    // ---- the target: static void a(Context, String, Handler) ----

    public static void a(Context context, String path, Handler handler) {
    }
}
