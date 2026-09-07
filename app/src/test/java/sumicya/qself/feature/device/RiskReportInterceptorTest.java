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
package sumicya.qself.feature.device;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The QQHook-merged block list: the two risk-control prefixes and nothing
 * else - ordinary commands must pass through untouched.
 */
public class RiskReportInterceptorTest {

    @Test
    public void blocksRiskControlPrefixes() {
        assertTrue(RiskReportInterceptor.shouldBlock("trpc.o3.mobile_security.SsoReport"));
        assertTrue(RiskReportInterceptor.shouldBlock("trpc.o3.report.NewDevice"));
    }

    @Test
    public void passesEverythingElse() {
        assertFalse(RiskReportInterceptor.shouldBlock("trpc.o3.other.Thing"));
        assertFalse(RiskReportInterceptor.shouldBlock("MessageSvc.PbSendMsg"));
        assertFalse(RiskReportInterceptor.shouldBlock(""));
        assertFalse(RiskReportInterceptor.shouldBlock(null));
    }

    @Test
    public void prefixMustMatchFromStart() {
        assertFalse(RiskReportInterceptor.shouldBlock("xxtrpc.o3.report.NewDevice"));
    }
}
