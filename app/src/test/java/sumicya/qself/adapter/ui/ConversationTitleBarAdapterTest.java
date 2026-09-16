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

package sumicya.qself.adapter.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.github.qauxv.util.QQVersion;
import org.junit.Test;
import sumicya.qself.adapter.ui.ConversationTitleBarAdapter.SuperShowGeneration;

/**
 * Batch-2 contract tests. The version tables are pure functions of the
 * version code (third testable seam, RFC-03 §8): boundary continuity is
 * fully verifiable on the JVM. Negative boundaries use the constant just
 * below each threshold.
 */
public class ConversationTitleBarAdapterTest {

    private final ConversationTitleBarAdapter adapter = ConversationTitleBarAdapter.INSTANCE;

    @Test
    public void cameraHideNameBoundaries() {
        assertEquals("a", adapter.cameraHideName(QQVersion.QQ_8_8_90));
        assertEquals("G", adapter.cameraHideName(QQVersion.QQ_8_8_93));
        assertEquals("G", adapter.cameraHideName(QQVersion.QQ_8_9_5));
        assertEquals("C", adapter.cameraHideName(QQVersion.QQ_8_9_10));
        assertEquals("D", adapter.cameraHideName(QQVersion.QQ_8_9_63_BETA_11345));
    }

    @Test
    public void cameraRemoveNameBoundaries() {
        assertEquals("a", adapter.cameraRemoveName(QQVersion.QQ_8_8_90));
        assertEquals("F", adapter.cameraRemoveName(QQVersion.QQ_8_8_93));
        assertEquals("E", adapter.cameraRemoveName(QQVersion.QQ_8_9_5));
        assertEquals("B", adapter.cameraRemoveName(QQVersion.QQ_8_9_10));
        assertEquals("C", adapter.cameraRemoveName(QQVersion.QQ_8_9_63_BETA_11345));
    }

    @Test
    public void superShowGenerationBoundaries() {
        assertEquals(SuperShowGeneration.LEGACY_CTRL,
                adapter.superShowGeneration(QQVersion.QQ_8_8_80));
        assertEquals("one step below the 8.9.3 threshold stays legacy",
                SuperShowGeneration.LEGACY_CTRL,
                adapter.superShowGeneration(QQVersion.QQ_8_9_2));
        assertEquals(SuperShowGeneration.BADGE_ONE_ARG,
                adapter.superShowGeneration(QQVersion.QQ_8_9_3));
        assertEquals(SuperShowGeneration.BADGE_TWO_ARGS,
                adapter.superShowGeneration(QQVersion.QQ_8_9_10));
        assertEquals("9.0.15 sits between 8.9.10 and 9.0.20",
                SuperShowGeneration.BADGE_TWO_ARGS,
                adapter.superShowGeneration(QQVersion.QQ_9_0_15));
        assertEquals(SuperShowGeneration.CONFIG_VALIDATOR,
                adapter.superShowGeneration(QQVersion.QQ_9_0_20));
    }

    @Test
    public void jvmResolutionDegradesToNull() {
        // hostInfo uninitialized on JVM: both resolvers must fail safe
        ClassLoader cl = ConversationTitleBarAdapterTest.class.getClassLoader();
        assertNull(adapter.resolveCameraButton(cl));
        assertNull(adapter.resolveSuperShowBadge(cl));
    }
}
