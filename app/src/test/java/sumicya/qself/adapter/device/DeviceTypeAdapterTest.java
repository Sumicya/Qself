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

package sumicya.qself.adapter.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.tencent.common.config.pad.DeviceType;
import org.junit.Test;
import sumicya.qself.hostapi.device.DeviceTypeApi.DeviceTypeHandle;

/**
 * Batch-3 contract tests: enum-set operations against the host-FQN fixture
 * and the JVM fail-safe for end-to-end resolution (getter is DexKit-bound).
 */
public class DeviceTypeAdapterTest {

    private final DeviceTypeAdapter adapter = DeviceTypeAdapter.INSTANCE;

    private DeviceTypeHandle fixtureHandle() throws Exception {
        // the getter is opaque to the enum ops; any static method stands in
        return new DeviceTypeHandle(DeviceType.class,
                DeviceTypeAdapterTest.class.getDeclaredMethod("fixtureHandle"));
    }

    @Test
    public void constantNamesListHostOrder() throws Exception {
        assertArrayEquals(new String[]{"PHONE", "PAD", "FOLD"},
                adapter.constantNames(fixtureHandle()));
    }

    @Test
    public void constantResolvesByName() throws Exception {
        assertSame(DeviceType.PAD, adapter.constant(fixtureHandle(), "PAD"));
        assertSame(DeviceType.FOLD, adapter.constant(fixtureHandle(), "FOLD"));
    }

    @Test
    public void unknownNameThrowsForLoudInitFailure() throws Exception {
        try {
            adapter.constant(fixtureHandle(), "WATCH");
            fail("expected IllegalArgumentException from valueOf");
        } catch (IllegalArgumentException expected) {
            // legacy semantics: broken stored config fails init loudly
        }
    }

    @Test
    public void jvmResolutionDegradesToNull() {
        assertNull(adapter.resolveDeviceTypeSource(
                DeviceTypeAdapterTest.class.getClassLoader()));
    }
}
