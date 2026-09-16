/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Standalone JVM regression suite: no Android SDK, JUnit or host APK required. */
public final class ServiceCmdSendPathTest {

    public static class RiskCarrier { public String getServiceCmd() { return "trpc.o3.report.Report"; } }
    public static class SecurityCarrier { public String getServiceCmd() { return "trpc.o3.mobile_security.Report"; } }
    public static class ObjectCarrier { public Object getServiceCmd() { return null; } }
    public static class PrivateCarrier { String getServiceCmd() { return "unused"; } }
    public static class StaticCarrier { public static String getServiceCmd() { return "unused"; } }

    public static class MsfLike {
        public void sendMessage(RiskCarrier message) { }
        public boolean sendMessage(SecurityCarrier message, int retry) { return false; }
    }

    public static void main(String[] args) throws Exception {
        check(ServiceCmdSendPath.isRiskControlCommand("trpc.o3.report.Report"), "report family blocks");
        check(ServiceCmdSendPath.isRiskControlCommand("trpc.o3.mobile_security.qsec.Report"), "nested security command blocks");
        check(ServiceCmdSendPath.isRiskControlCommand("trpc.o3.report.Device12345678"), "numeric suffix still blocks");
        check(ServiceCmdSendPath.isRiskControlCommand("trpc.o3.report.123456789"), "digit-leading segment still blocks");
        String[] passes = {null, "", "trpc.o3.report", "trpc.o3.report.", "trpc.o3.reportX.Test",
                "xxtrpc.o3.report.Test", " trpc.o3.report.Test", "trpc.o3.report.Test\nsecret",
                "trpc.o3.report.Test?token=secret", "MessageSvc.PbSendMsg", "trpc.o3.report." + repeat('a', 161)};
        for (String raw : passes) {
            check(!ServiceCmdSendPath.isRiskControlCommand(raw), "non-target command passes: " + raw);
        }
        check("trpc.o3.report.Device_redacted_".equals(
                ServiceCmdSendPath.redactedCommand("trpc.o3.report.Device12345678")), "numeric identifier redacted");
        check("".equals(ServiceCmdSendPath.redactedCommand(null)), "missing command redacts to empty");
        check(ServiceCmdSendPath.redactedCommand(repeat('a', 200)).length() == 160, "logged form bounded");
        check(ServiceCmdSendPath.riskControlPrefixes().length == 2, "two families only");
        ServiceCmdSendPath.riskControlPrefixes()[0] = "trpc.o3.mutated.";
        check(ServiceCmdSendPath.isRiskControlCommand("trpc.o3.report.Report"), "family list not externally mutable");

        check(Arrays.equals(new int[]{0},
                ServiceCmdSendPath.commandArgumentIndices(new Class<?>[]{RiskCarrier.class})), "carrier index found");
        check(Arrays.equals(new int[]{0, 2}, ServiceCmdSendPath.commandArgumentIndices(
                new Class<?>[]{RiskCarrier.class, String.class, SecurityCarrier.class})), "every carrier index found");
        check(ServiceCmdSendPath.commandArgumentIndices(new Class<?>[]{ObjectCarrier.class, PrivateCarrier.class,
                StaticCarrier.class, String.class}).length == 0, "untrustworthy getters ignored");
        check(ServiceCmdSendPath.commandArgumentIndices(null).length == 0, "missing parameter types ignored");

        check(ServiceCmdSendPath.canSuppressReturn(void.class), "void route may be stopped");
        check(!ServiceCmdSendPath.canSuppressReturn(boolean.class), "boolean route is not stopped");
        check(!ServiceCmdSendPath.canSuppressReturn(int.class), "int route is not stopped");
        check(!ServiceCmdSendPath.canSuppressReturn(String.class), "reference route is not stopped");

        check(ServiceCmdSendPath.isHookableShape(Modifier.PUBLIC, 1), "ordinary shape may be hooked");
        check(!ServiceCmdSendPath.isHookableShape(Modifier.ABSTRACT, 1), "abstract shape rejected");
        check(!ServiceCmdSendPath.isHookableShape(Modifier.PUBLIC, 17), "huge arity rejected");

        // The recorded channel shape: sendMessage(String cmd, byte[] body, long id) -> void.
        check(ServiceCmdSendPath.isDirectCommandRoute("sendMessage",
                new Class<?>[]{String.class, byte[].class, long.class}), "recorded channel shape recognised");
        check(!ServiceCmdSendPath.isDirectCommandRoute("sendMessage",
                new Class<?>[]{String.class, byte[].class, int.class}), "wrong id type rejected");
        check(!ServiceCmdSendPath.isDirectCommandRoute("sendMessage",
                new Class<?>[]{String.class, byte[].class}), "wrong arity rejected");
        check(!ServiceCmdSendPath.isDirectCommandRoute("sendMessageInner",
                new Class<?>[]{String.class, byte[].class, long.class}), "wrong method name rejected");
        check(!ServiceCmdSendPath.isDirectCommandRoute("sendMessage", null), "missing parameters rejected");
        check(!ServiceCmdSendPath.isDirectCommandRoute("sendMessage",
                new Class<?>[]{Object.class, byte[].class, long.class}), "Object-typed command not guessed");

        // Channel-path boundary: the in-tree ChannelProxyHook refuses this path from 9.1.30 / 8538.
        check(ServiceCmdSendPath.isChannelPathOwner("ChannelManager"), "channel manager is the channel path");
        check(ServiceCmdSendPath.isChannelPathOwner("ChannelProxyExt"), "channel proxy is the channel path");
        check(!ServiceCmdSendPath.isChannelPathOwner("MsfCore"), "MSF queue entry is not the channel path");
        check(ServiceCmdSendPath.channelPathNeedsOptIn(11310L), "9.2.10 needs an explicit opt-in");
        check(ServiceCmdSendPath.channelPathNeedsOptIn(ServiceCmdSendPath.CHANNEL_PATH_UNSAFE_FROM_VERSION),
                "boundary version included");
        check(!ServiceCmdSendPath.channelPathNeedsOptIn(8537L), "older hosts keep the previous behaviour");

        Method voidRoute = MsfLike.class.getDeclaredMethod("sendMessage", RiskCarrier.class);
        check("MsfLike.sendMessage(RiskCarrier)->void".equals(ServiceCmdSendPath.routeShape(
                "MsfLike", "sendMessage", voidRoute.getParameterTypes(), voidRoute.getReturnType())), "void route shape");
        Method booleanRoute = MsfLike.class.getDeclaredMethod("sendMessage", SecurityCarrier.class, int.class);
        check("MsfLike.sendMessage(SecurityCarrier,int)->boolean".equals(ServiceCmdSendPath.routeShape(
                "MsfLike", "sendMessage", booleanRoute.getParameterTypes(), booleanRoute.getReturnType())),
                "boolean route shape");
        check("?.?()->?".equals(ServiceCmdSendPath.routeShape(null, null, null, null)),
                "unknown metadata is not echoed");
        check("MsfLike.sendMessage(?)->?".equals(ServiceCmdSendPath.routeShape(
                "MsfLike", "sendMessage", new Class<?>[]{null}, null)), "unnamed parameter is not echoed");

        System.out.println("ServiceCmdSendPathTest: all assertions passed");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
