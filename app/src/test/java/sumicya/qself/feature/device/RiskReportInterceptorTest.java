/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    @Test
    public void malformedCommandsAreNeverTreatedAsRiskControl() {
        assertFalse(RiskReportInterceptor.shouldBlock("trpc.o3.report."));
        assertFalse(RiskReportInterceptor.shouldBlock("trpc.o3.report"));
        assertFalse(RiskReportInterceptor.shouldBlock("trpc.o3.report.New Device"));
        assertFalse(RiskReportInterceptor.shouldBlock("trpc.o3.report.Test?token=secret"));
        assertFalse(RiskReportInterceptor.shouldBlock("trpc.o3.report." + repeat('a', 161)));
    }

    @Test
    public void numericIdentifiersDoNotHideARiskControlCommand() {
        // Blocking stays loose on purpose; only the logged/reported form is reduced.
        assertTrue(RiskReportInterceptor.shouldBlock("trpc.o3.report.Device12345678"));
        assertTrue(RiskReportInterceptor.shouldBlock("trpc.o3.mobile_security.123456789"));
    }

    @Test
    public void blockingStaysLooseWhileReportingStaysStrict() {
        // Blocking must not miss a report whose shape is merely unusual...
        assertTrue(RiskReportInterceptor.shouldBlock("trpc.o3.report.123456789"));
        // ...while the reporting policy only echoes what it can name exactly (no payload echo).
        assertNull(sumicya.qself.diagnostics.ReportMetadata.command("trpc.o3.report.123456789"));
        assertEquals("trpc.o3.report.NewDevice",
                sumicya.qself.diagnostics.ReportMetadata.command("trpc.o3.report.NewDevice"));
        // The journal/log form is the shared redaction rule, never the raw identifier.
        assertEquals("trpc.o3.report.Device_redacted_",
                sumicya.qself.diagnostics.ServiceCmdSendPath.redactedCommand("trpc.o3.report.Device12345678"));
    }

    @Test
    public void recordedChannelShapeDecidesTheDirectRoute() {
        // The journal on QQ 9.2.10 shows MsfCore candidates=0 and only this route left.
        assertTrue(sumicya.qself.diagnostics.ServiceCmdSendPath.isDirectCommandRoute("sendMessage",
                new Class<?>[]{String.class, byte[].class, long.class}));
        assertFalse(sumicya.qself.diagnostics.ServiceCmdSendPath.isDirectCommandRoute("sendMessage",
                new Class<?>[]{String.class, byte[].class, int.class}));
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }
}
