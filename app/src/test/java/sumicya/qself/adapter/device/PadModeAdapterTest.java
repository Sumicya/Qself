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

import static org.junit.Assert.assertEquals;

import io.github.qauxv.util.QQVersion;
import org.junit.Test;

/**
 * Batch-6 contract tests: the AppSetting obfuscation version tables —
 * reader method name and the tablet/phone field names — as pure functions
 * of (hostIsTim, versionCode). Boundary continuity is pinned one step
 * below every threshold; QQ_9_2_30_BETA_31620 (12288) conveniently sits
 * exactly one step below QQ_9_2_30 (12330) as a real-world "below" case.
 */
public class PadModeAdapterTest {

    private final PadModeAdapter adapter = PadModeAdapter.INSTANCE;

    @Test
    public void readerMethodNameBoundary() {
        // QQ: >= 9.2.30 -> "e", below -> "f"
        assertEquals("e", adapter.appSettingReadMethodName(false, QQVersion.QQ_9_2_30));
        assertEquals("f", adapter.appSettingReadMethodName(false, QQVersion.QQ_9_2_30 - 1));
        assertEquals("f", adapter.appSettingReadMethodName(false, QQVersion.QQ_9_2_30_BETA_31620));
        assertEquals("f", adapter.appSettingReadMethodName(false, QQVersion.QQ_8_9_15));
        // TIM: always "f" regardless of version
        assertEquals("f", adapter.appSettingReadMethodName(true, 4000L));
        assertEquals("f", adapter.appSettingReadMethodName(true, 99999L));
    }

    @Test
    public void tabletFieldNameContinuity() {
        // QQ segment boundaries, one step below each threshold
        assertEquals("f", adapter.tabletAppIdFieldName(false, QQVersion.QQ_8_9_15));
        assertEquals("g", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_1_50));
        assertEquals("f", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_1_50 - 1));
        assertEquals("h", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_2_15));
        assertEquals("g", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_2_15 - 1));
        assertEquals("g", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_2_30));
        assertEquals("h", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_2_30 - 1));
        assertEquals("f", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_2_65));
        assertEquals("g", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_2_65 - 1));
        assertEquals("b", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_3_5));
        assertEquals("f", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_3_5 - 1));
        assertEquals("g", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_3_20));
        assertEquals("b", adapter.tabletAppIdFieldName(false, QQVersion.QQ_9_3_20 - 1));
        // TIM collapses to one row (availability gate >= 4.0.95)
        assertEquals("g", adapter.tabletAppIdFieldName(true, 4000L));
    }

    @Test
    public void phoneFieldNameContinuity() {
        // documented-only table (the original never assigned it); pinned as knowledge
        assertEquals("e", adapter.phoneAppIdFieldName(false, QQVersion.QQ_8_9_15));
        assertEquals("f", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_1_50));
        assertEquals("e", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_1_50 - 1));
        assertEquals("g", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_2_15));
        assertEquals("f", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_2_15 - 1));
        assertEquals("f", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_2_30));
        assertEquals("g", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_2_30 - 1));
        assertEquals("e", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_2_65));
        assertEquals("f", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_2_65 - 1));
        assertEquals("a", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_3_5));
        assertEquals("e", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_3_5 - 1));
        assertEquals("f", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_3_20));
        assertEquals("a", adapter.phoneAppIdFieldName(false, QQVersion.QQ_9_3_20 - 1));
        assertEquals("f", adapter.phoneAppIdFieldName(true, 4000L));
    }
}
