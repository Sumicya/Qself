/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics;

/** No message, cause text, file path, receiver, arguments or arbitrary toString calls. */
public final class FeatureErrorMetadata {
    private FeatureErrorMetadata() { }
    public static String describe(Throwable error) {
        if (error == null) return "none";
        StringBuilder result = new StringBuilder(token(error.getClass().getName()));
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < Math.min(6, frames.length); i++) {
            StackTraceElement frame = frames[i];
            result.append(' ').append(token(frame.getClassName())).append('.')
                .append(token(frame.getMethodName())).append(':').append(frame.getLineNumber());
        }
        return result.substring(0, Math.min(result.length(), 900));
    }
    private static String token(String value) {
        return value != null && value.length() <= 120 && value.matches("[A-Za-z0-9_.$<>-]+") ? value : "unknown";
    }
}
