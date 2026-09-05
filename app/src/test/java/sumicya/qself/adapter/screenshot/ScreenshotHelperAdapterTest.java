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

package sumicya.qself.adapter.screenshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import com.tencent.mobileqq.screendetect.ScreenShotHelper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import org.junit.Test;

/**
 * Contract tests for the RFC-03 pilot adapter (Layer B of the test strategy:
 * symbol resolvability against a fake host classloader).
 *
 * The test classloader carries the host-FQN fixture; the adapter's degraded
 * (direct-FQN) strategy must resolve the one method matching every trait
 * among the decoys. The negative case — an empty classloader — is exactly
 * the state after a host update renames the class, and must yield null
 * instead of an exception.
 */
public class ScreenshotHelperAdapterTest {

    private final ScreenshotHelperAdapter adapter = ScreenshotHelperAdapter.INSTANCE;

    @Test
    public void resolvesTargetMethodAmongDecoys() {
        Method m = adapter.resolveShowMethod(ScreenShotHelper.class.getClassLoader());
        assertNotNull("fixture class is on the test classpath, resolution must succeed", m);
        assertEquals("a", m.getName());
        assertTrue(Modifier.isStatic(m.getModifiers()));
        assertEquals(void.class, m.getReturnType());
        Class<?>[] argt = m.getParameterTypes();
        assertEquals(3, argt.length);
        assertEquals(Context.class, argt[0]);
        assertEquals(String.class, argt[1]);
        assertEquals(Handler.class, argt[2]);
    }

    @Test
    public void missingHostYieldsNullNotException() {
        // bootstrap-only classloader: no ScreenShotHelper anywhere
        ClassLoader empty = new URLClassLoader(new java.net.URL[0], null);
        Method m = adapter.resolveShowMethod(empty);
        assertNull("no host class => no capability => null (never an exception)", m);
    }

    @Test
    public void resolutionHasNoSideEffectOnRegistry() throws Exception {
        sumicya.qself.hostapi.CapabilityRegistry.resetForTest();
        Method m = adapter.resolveShowMethod(ScreenShotHelper.class.getClassLoader());
        assertNotNull(m);
        // adapters resolve (pure mechanism); only features report states
        assertEquals(sumicya.qself.hostapi.CapabilityState.UNKNOWN,
                sumicya.qself.hostapi.CapabilityRegistry.stateOf("chat.screenshot_helper"));
    }
}
