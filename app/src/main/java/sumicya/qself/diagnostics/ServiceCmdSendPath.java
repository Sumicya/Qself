/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Route policy for the QQ send path that carries O3 risk-control commands.
 *
 * <p>Deliberately separate from {@link ReportMetadata#command(String)}: reporting may only
 * echo a command it can name exactly, while blocking must not miss a risk-control command
 * merely because its shape is unusual (a numeric segment, for example). Both policies still
 * refuse payloads - only bounded, printable command strings and class/method metadata ever
 * leave this class.
 *
 * <p>The blocking rule itself is the point of the split: a stopped call must still answer
 * its caller, so only a {@code void} route may be suppressed. Any other return type would
 * require inventing a result nobody has verified, which is exactly what a host caller can
 * trip over. Unconfirmed routes stay unblocked and are reported instead.
 */
public final class ServiceCmdSendPath {

    /** The two QQHook-merged risk-control families; every other command passes untouched. */
    private static final String[] PREFIXES = {"trpc.o3.mobile_security.", "trpc.o3.report."};

    private static final int MAX_COMMAND_LENGTH = 160;
    private static final String COMMAND_CHARS = "[A-Za-z0-9_.-]+";
    private static final int MAX_PARAMETERS = 16;
    private static final int MAX_SHAPE_LENGTH = 240;
    private static final String SHAPE_TOKEN = "[A-Za-z0-9_$.\\[\\]<>-]{1,96}";

    /**
     * The recorded channel-proxy send shape: {@code sendMessage(String cmd, byte[] body, long id) -> void}.
     *
     * <p>Seen on {@code ChannelProxyExt} (the original QQHook target) and, on the 9.2.10 host, on
     * {@code ChannelManager} instead - the journal in this repo records
     * {@code ChannelManager.sendMessage(String,byte[],long)->void no-command-getter} on a device whose
     * {@code MsfCore} no longer declares any sendMessage method at all. Here the first argument is the
     * command, so no getter is needed; the shape itself is the verified carrier.
     */
    public static boolean isDirectCommandRoute(String methodName, Class<?>[] parameterTypes) {
        return "sendMessage".equals(methodName) && parameterTypes != null && parameterTypes.length == 3
                && parameterTypes[0] == String.class && parameterTypes[1] == byte[].class
                && parameterTypes[2] == long.class;
    }

    /** Classes whose sendMessage method sits on the channel path rather than the MSF queue entry. */
    public static boolean isChannelPathOwner(String ownerSimpleName) {
        return "ChannelManager".equals(ownerSimpleName) || "ChannelProxyExt".equals(ownerSimpleName);
    }

    /** Version 9.1.30 / 8538, where the in-tree ChannelProxyHook stops being available. */
    public static final long CHANNEL_PATH_UNSAFE_FROM_VERSION = 9_1_30L;

    /**
     * On this host version the channel path may only be blocked after an explicit opt-in.
     *
     * <p>The in-tree {@code ChannelProxyHook} is disabled from 9.1.30 onward because replacing that
     * method is recorded as being linked to账号下线/冻结. The same send shape exists on
     * {@code ChannelManager} in newer builds, so the same boundary applies: refusing by default is a
     * deliberate decision, not a defect to be papered over.
     */
    public static boolean channelPathNeedsOptIn(long hostVersionCode) {
        return hostVersionCode >= CHANNEL_PATH_UNSAFE_FROM_VERSION;
    }

    private ServiceCmdSendPath() { }

    /** Command families this module is allowed to stop; the only place they are listed. */
    public static String[] riskControlPrefixes() {
        return PREFIXES.clone();
    }

    /**
     * Blocking decision. Bounded and charset-checked, prefix-anchored, and it requires at
     * least one character after the prefix so a bare family name is never mistaken for a
     * command.
     */
    public static boolean isRiskControlCommand(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_COMMAND_LENGTH) return false;
        if (!raw.matches(COMMAND_CHARS)) return false;
        for (String prefix : PREFIXES) {
            if (raw.startsWith(prefix) && raw.length() > prefix.length()) return true;
        }
        return false;
    }

    /** Safe journal/log form: bounded, and numeric identifiers replaced like the report path. */
    public static String redactedCommand(String raw) {
        if (raw == null) return "";
        String bounded = raw.length() > MAX_COMMAND_LENGTH ? raw.substring(0, MAX_COMMAND_LENGTH) : raw;
        return ReportMetadata.redact(bounded);
    }

    /**
     * Indices of parameters that declare a public zero-arg {@code String getServiceCmd()}.
     * Resolved once against the declared parameter types, never per invocation and never
     * by scanning arbitrary argument objects.
     */
    public static int[] commandArgumentIndices(Class<?>[] parameterTypes) {
        if (parameterTypes == null) return new int[0];
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameter = parameterTypes[i];
            if (parameter != null && ReportMetadata.commandGetter(parameter) != null) indices.add(i);
        }
        int[] result = new int[indices.size()];
        for (int i = 0; i < result.length; i++) result[i] = indices.get(i);
        return result;
    }

    /** Same arity/abstract guard the read-only observer applies before installing anything. */
    public static boolean isHookableShape(int modifiers, int parameterCount) {
        return !Modifier.isAbstract(modifiers) && parameterCount <= MAX_PARAMETERS;
    }

    /**
     * Only a route without a result can be stopped without inventing one.
     *
     * <p>This is not stylistic: the hook bridge rejects a null result for a
     * primitive-returning method ({@code WrappedCallbacks.WrappedHookParam.checkResultCast}),
     * so assigning one throws inside the callback, and a reference-returning route would
     * hand the host caller an object nobody verified. Everything that is not {@code void}
     * therefore keeps its original behaviour.
     */
    public static boolean canSuppressReturn(Class<?> returnType) {
        return returnType == void.class;
    }

    /** Bounded, redacted route label for the journal: declared types only, never values. */
    public static String routeShape(String ownerSimpleName, String methodName,
            Class<?>[] parameterTypes, Class<?> returnType) {
        StringBuilder shape = new StringBuilder();
        shape.append(token(ownerSimpleName)).append('.').append(token(methodName)).append('(');
        if (parameterTypes != null) {
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) shape.append(',');
                shape.append(parameterTypes[i] == null ? "?" : token(parameterTypes[i].getSimpleName()));
            }
        }
        shape.append(")->").append(returnType == null ? "?" : token(returnType.getSimpleName()));
        return shape.length() <= MAX_SHAPE_LENGTH ? shape.toString() : shape.substring(0, MAX_SHAPE_LENGTH);
    }

    private static String token(String value) {
        return value != null && value.matches(SHAPE_TOKEN) ? value : "?";
    }
}
