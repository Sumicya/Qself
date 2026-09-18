/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Standalone JVM regression suite: no Android SDK, JUnit or host APK required. */
public final class ReportMetadataTest {
    public static class Good { public String getServiceCmd() { return "unused"; } }
    public static class WrongReturn { public Object getServiceCmd() { return null; } }
    public static class StaticGetter { public static String getServiceCmd() { return "unused"; } }
    public static class WrongArgs { public String getServiceCmd(int index) { return "unused"; } }
    public static class PrivateGetter { private String getServiceCmd() { return "unused"; } }
    public static class Child extends Good { }

    public static void main(String[] args) throws Exception {
        check("trpc.o3.report.Report".equals(ReportMetadata.command("trpc.o3.report.Report")), "report family");
        check("trpc.o3.mobile_security.Check".equals(ReportMetadata.command("trpc.o3.mobile_security.Check")), "security family");
        String[] rejected = {null, "", "trpc.o3.report.", "trpc.o3.reportX.Test", "login.auth",
                "prefix.trpc.o3.report.Test", "trpc.o3.report.Test\nsecret", "trpc.o3.report.Test?token=secret",
                "trpc.o3.report.123456789", "trpc.o3.report.中文", " trpc.o3.report.Test",
                "trpc.o3.report." + repeat('a', 161)};
        for (String raw : rejected) check(ReportMetadata.command(raw) == null, "reject malformed/non-target metadata");
        check(!ReportMetadata.command("trpc.o3.report.user123456789").contains("123456789"), "redact long numeric identifiers");
        check(ReportMetadata.command("trpc.o3.report.v2") != null, "version suffix retained");
        check(ReportMetadata.commandGetter(Good.class) != null, "public getter");
        check(ReportMetadata.commandGetter(Child.class) != null, "inherited getter");
        check(ReportMetadata.commandGetter(WrongReturn.class) == null, "wrong return rejected");
        check(ReportMetadata.commandGetter(StaticGetter.class) == null, "static getter rejected");
        check(ReportMetadata.commandGetter(WrongArgs.class) == null, "wrong arity rejected");
        check(ReportMetadata.commandGetter(PrivateGetter.class) == null, "private getter rejected");
        check(ReportMetadata.commandGetter(Object.class) == null, "Object-typed carrier not guessed");
        check("none".equals(ReportMetadata.exceptionType(null)), "no error metadata");
        check("java.lang.IllegalStateException".equals(ReportMetadata.exceptionType(
                new IllegalStateException("secret-token-account-body"))), "exception message excluded");
        check(ReportMetadata.acceptsWindow(true, "window", "generation", "window", "generation"), "current event accepted");
        check(!ReportMetadata.acceptsWindow(false, "window", "generation", "window", "generation"), "disabled rejected");
        check(!ReportMetadata.acceptsWindow(true, "new", "generation", "old", "generation"), "cleared event rejected");
        check(!ReportMetadata.acceptsWindow(true, "window", "new", "window", "old"), "stopped/restarted event rejected");
        check(!ReportMetadata.acceptsWindow(true, "", "generation", "", "generation"), "missing window rejected");
        check(!ReportMetadata.acceptsWindow(true, "window", "", "window", ""), "missing generation rejected");
        check(!ReportMetadata.acceptsWindow(true, null, "generation", null, "generation"), "null window rejected");
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 1000; i++) lines = ReportMetadata.append(lines, "event " + i);
        check(lines.size() == 128, "bounded ring");
        check(lines.get(0).equals("event 872") && lines.get(127).equals("event 999"), "oldest evicted");
        List<String> original = new ArrayList<String>(lines);
        lines = ReportMetadata.append(lines, "bad\nline");
        check(lines.equals(original), "newlines rejected");
        check(ReportMetadata.append(lines, repeat('x', 481)).equals(original), "oversized line rejected");
        check(ReportMetadata.append(Arrays.asList(null, "", "bad\rline", "good"), "next")
                .equals(Arrays.asList("good", "next")), "malformed persisted lines discarded");
        check(original.get(0).equals("event 872"), "input not mutated");
        System.out.println("ReportMetadataTest: all assertions passed");
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
