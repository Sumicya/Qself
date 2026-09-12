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

package sumicya.qself.adapter.qzone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import cooperation.qzone.push.MsgNotification;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import org.junit.Test;
import sumicya.qself.hostapi.CapabilityRegistry;
import sumicya.qself.hostapi.CapabilityState;
import sumicya.qself.hostapi.notification.QZoneMsgNotifyApi;

/**
 * Contract tests for the second pilot adapter: the domain-handle resolution
 * (widest void method + second-String index) and the missing-host negative.
 */
public class QZoneMsgNotifyAdapterTest {

    private final QZoneMsgNotifyAdapter adapter = QZoneMsgNotifyAdapter.INSTANCE;

    @Test
    public void resolvesWidestVoidMethodWithSecondStringIndex() throws Exception {
        QZoneMsgNotifyApi.NotifierHandle handle =
                adapter.resolveNotifier(MsgNotification.class.getClassLoader());
        assertNotNull(handle);
        Method expected = MsgNotification.class.getDeclaredMethod("showNotification",
                android.content.Context.class, String.class, String.class, int.class,
                java.util.HashMap.class);
        assertEquals(expected, handle.getMethod());
        assertEquals("desc must be the SECOND String parameter",
                2, handle.getDescArgIndex());
    }

    @Test
    public void missingHostYieldsNullNotException() {
        ClassLoader empty = new URLClassLoader(new java.net.URL[0], null);
        assertNull(adapter.resolveNotifier(empty));
    }

    @Test
    public void resolutionHasNoSideEffectOnRegistry() {
        CapabilityRegistry.resetForTest();
        assertNotNull(adapter.resolveNotifier(MsgNotification.class.getClassLoader()));
        assertEquals(CapabilityState.UNKNOWN,
                CapabilityRegistry.stateOf("notification.qzone_thumbs_up"));
    }
}
