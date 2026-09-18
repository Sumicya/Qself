/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure metadata policy. Never accepts payloads, exception messages or object.toString().
 *
 * <p>Shared by the live paths: {@link #commandGetter(Class)} resolves the send-path command
 * carrier and {@link #redact(String)} is the single redaction rule used by both the send-path
 * journal and the diagnostics file. The remaining entries - {@link #command(String)},
 * {@link #exceptionType(Throwable)}, {@link #acceptsWindow}, {@link #append(List, String)}
 * and their limits - are the reporting-side policy of the retired read-only observer: they
 * keep their JVM suite and have no production caller today, so trim them as one unit.
 */
public final class ReportMetadata {
    public static final int MAX_LINES = 128;
    public static final int MAX_LINE_LENGTH = 480;
    private static final Pattern COMMAND = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{5,}");

    private ReportMetadata() { }

    /** Known O3 command families only; this is not coverage of all risk reporting. */
    public static String command(String raw) {
        if (raw == null || raw.length() > 160 || !COMMAND.matcher(raw).matches()) return null;
        if (!raw.startsWith("trpc.o3.mobile_security.") && !raw.startsWith("trpc.o3.report.")) return null;
        return redact(raw);
    }

    /** Long digit runs are identifiers, never values; every log/journal path shares this. */
    public static String redact(String raw) {
        return raw == null ? "" : DIGITS.matcher(raw).replaceAll("_redacted_");
    }

    public static String exceptionType(Throwable error) {
        if (error == null) return "none";
        // No message, cause, stack trace, account data or arbitrary string conversion.
        String name = error.getClass().getName();
        return name.length() <= 160 && name.matches("[A-Za-z0-9_.$]+")
                ? DIGITS.matcher(name).replaceAll("_redacted_") : "unknown-type";
    }

    /** Resolve a public, zero-arg String getter once, never scan argument objects at runtime. */
    public static Method commandGetter(Class<?> type) {
        try {
            Method method = type.getMethod("getServiceCmd");
            if (method.getReturnType() != String.class || Modifier.isStatic(method.getModifiers())) return null;
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    /** A queued event must belong to both the observation window and the enabled generation. */
    public static boolean acceptsWindow(boolean enabled, String epoch, String generation,
            String expectedEpoch, String expectedGeneration) {
        return enabled && epoch != null && !epoch.isEmpty() && generation != null && !generation.isEmpty()
                && epoch.equals(expectedEpoch) && generation.equals(expectedGeneration);
    }

    /** Bounded newest-first eviction; strips malformed persisted lines instead of echoing them. */
    public static List<String> append(List<String> previous, String next) {
        ArrayDeque<String> result = new ArrayDeque<String>();
        int start = Math.max(0, previous.size() - MAX_LINES);
        for (int i = start; i < previous.size(); i++) addLine(result, previous.get(i));
        addLine(result, next);
        return new ArrayList<String>(result);
    }

    private static void addLine(ArrayDeque<String> lines, String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE_LENGTH
                || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) return;
        while (lines.size() >= MAX_LINES) lines.removeFirst();
        lines.addLast(line);
    }
}
